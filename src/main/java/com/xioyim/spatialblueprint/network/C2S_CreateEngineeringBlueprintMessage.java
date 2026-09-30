package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.network.CaptureAreaMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：创建独立的工程蓝图 —— 两条路共用。
 *
 * <p>{@code sourceId} 为空：现场扫描选区世界生成（选点界面 [工程] 那条路，仅创造模式）；
 * 非空：从蓝图库复制源文件（放置界面 ⌘ 那条路），生成新 UUID，源文件不动。</p>
 *
 * <p>两条路都按 {@code captureMode} 处理，与 {@link CaptureAreaMessage} 同一套语义：
 * SAFE 非 OP 预扫非法方块整单拒、危险 NBT 净化；SANITIZE 删非法方块 + 完整净化；
 * FORCE 仅 OP、原样保留。复制分支同样按模式净化，不再直接照搬源文件。</p>
 */
public class C2S_CreateEngineeringBlueprintMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_CreateEngineeringBlueprintMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_create_engineering_blueprint_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_CreateEngineeringBlueprintMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_CreateEngineeringBlueprintMessage::encode, C2S_CreateEngineeringBlueprintMessage::decode);

    @Override
    public CustomPacketPayload.Type<C2S_CreateEngineeringBlueprintMessage> type() {
        return TYPE;
    }


    private final String  sourceId;
    private final String  name;
    private final String  dimension;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final CaptureMode captureMode;
    private final int     animDuration;
    private final String  noteShort;
    private final String  noteLong;

    public C2S_CreateEngineeringBlueprintMessage(String sourceId, String name, String dimension,
                                                  BlockPos startPos, BlockPos endPos,
                                                  CaptureMode captureMode, int animDuration,
                                                  String noteShort, String noteLong) {
        this.sourceId    = sourceId;
        this.name        = name;
        this.dimension   = dimension;
        this.startPos    = startPos;
        this.endPos      = endPos;
        this.captureMode = captureMode;
        this.animDuration = animDuration;
        this.noteShort   = noteShort == null ? "" : noteShort;
        this.noteLong    = noteLong == null ? "" : noteLong;
    }

    public static void encode(C2S_CreateEngineeringBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sourceId, 64);
        buf.writeUtf(msg.name, 64);
        buf.writeUtf(msg.dimension, 128);
        buf.writeBlockPos(msg.startPos);
        buf.writeBlockPos(msg.endPos);
        buf.writeInt(msg.captureMode.ordinal());
        buf.writeInt(msg.animDuration);
        buf.writeUtf(msg.noteShort, 15);
        buf.writeUtf(msg.noteLong, 200);
    }

    public static C2S_CreateEngineeringBlueprintMessage decode(FriendlyByteBuf buf) {
        String   sourceId    = buf.readUtf(64);
        String   name        = buf.readUtf(64);
        String   dimension   = buf.readUtf(128);
        BlockPos startPos    = buf.readBlockPos();
        BlockPos endPos      = buf.readBlockPos();
        CaptureMode mode     = CaptureMode.fromOrdinal(buf.readInt());
        int      animDuration = buf.readInt();
        String   noteShort   = buf.readUtf(15);
        String   noteLong    = buf.readUtf(200);
        return new C2S_CreateEngineeringBlueprintMessage(
                sourceId, name, dimension, startPos, endPos, mode, animDuration, noteShort, noteLong);
    }

    /** Maximum allowed edge length (blocks) for an engineering project bounding box. */
    private static final int MAX_ENG_EDGE = 1024;

    private static boolean isDebug(ServerPlayer player) {
        return player.isCreative() && player.getTags().contains("DeBug");
    }

    public static void handle(C2S_CreateEngineeringBlueprintMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_CreateEngineeringBlueprintMessage msg, ServerPlayer player) {
    boolean dbg = player != null && isDebug(player);

        if (dbg) SpatialBlueprintMod.LOGGER.info(
                "[EngBP][DBG] 收到消息 player={} creative={} sourceId='{}' name='{}' dim='{}' start={} end={} mode={} dur={}",
                player.getScoreboardName(), player.isCreative(),
                msg.sourceId, msg.name, msg.dimension, msg.startPos, msg.endPos,
                msg.captureMode, msg.animDuration);

        if (player == null || !player.isCreative()) {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：非创造模式");
            return;
        }

        // 槽位校验（本入口会新建 engineering 文件）
        com.xioyim.spatialblueprint.server.BlueprintValidator.ValidationResult slot =
                com.xioyim.spatialblueprint.server.BlueprintValidator.checkSlot(player, "engineering");
        if (!slot.valid()) {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：工程蓝图槽位已满");
            BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(
                    false, slot.failKey(), slot.detailKey(), slot.detailArgs()));
            return;
        }

        // ── 服务端输入校验 ────────────────────────────────────────────
        String sanitizedName = BlueprintValidator.sanitizeName(msg.name);
        if (sanitizedName.isEmpty()) {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：名称净化后为空，原始='{}'", msg.name);
            // 客户端已没有创建页兜名字（路 B 沿用源蓝图名，可能为空）：回横幅而不是静默丢（T9）
            sendResult(player, false, "spatial_blueprint.message.name_required");
            return;
        }

        ServerLevel level = player.serverLevel();
        if (!level.isInWorldBounds(msg.startPos) || !level.isInWorldBounds(msg.endPos)) {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：坐标越界 start={} end={}",
                    msg.startPos, msg.endPos);
            return;
        }

        int dx = Math.abs(msg.startPos.getX() - msg.endPos.getX()) + 1;
        int dy = Math.abs(msg.startPos.getY() - msg.endPos.getY()) + 1;
        int dz = Math.abs(msg.startPos.getZ() - msg.endPos.getZ()) + 1;
        if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 包围盒 {}x{}x{}", dx, dy, dz);
        if (dx > MAX_ENG_EDGE || dy > MAX_ENG_EDGE || dz > MAX_ENG_EDGE) {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：包围盒超限 MAX={}", MAX_ENG_EDGE);
            return;
        }

        // 模式降级（与 CaptureAreaMessage 同）：FORCE 仅 OP 可用，改包发 FORCE 一律降回 SAFE
        boolean isOp = player.hasPermissions(2);
        final CaptureMode effMode = (msg.captureMode == CaptureMode.FORCE && !isOp)
                ? CaptureMode.SAFE : msg.captureMode;

        BlueprintData data;
        if (msg.sourceId == null || msg.sourceId.isEmpty()) {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] sourceId 为空，扫描世界区域 {} → {}", msg.startPos, msg.endPos);
            data = new BlueprintData();

            // 计算 min/max
            BlockPos sMin = new BlockPos(
                    Math.min(msg.startPos.getX(), msg.endPos.getX()),
                    Math.min(msg.startPos.getY(), msg.endPos.getY()),
                    Math.min(msg.startPos.getZ(), msg.endPos.getZ()));
            BlockPos sMax = new BlockPos(
                    Math.max(msg.startPos.getX(), msg.endPos.getX()),
                    Math.max(msg.startPos.getY(), msg.endPos.getY()),
                    Math.max(msg.startPos.getZ(), msg.endPos.getZ()));

            // 体积校验：与空间/投影/次元统一按 1.6M 绝对上限（含 OP），避免 OP 选区超限无限制创建
            com.xioyim.spatialblueprint.server.BlueprintValidator.ValidationResult vr =
                    com.xioyim.spatialblueprint.server.BlueprintValidator.validateCaptureArea(player, sMin, sMax,
                            com.xioyim.spatialblueprint.util.FluidCaptureFilter.ALL, "engineering");
            if (!vr.valid()) {
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：选区校验失败 {} bbox={} limit={}", vr.failKey(), vr.detailArgs().length > 3 ? vr.detailArgs()[3] : "?", vr.detailArgs().length > 4 ? vr.detailArgs()[4] : "?");
                BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(
                        false, vr.failKey(), vr.detailKey(), vr.detailArgs()));
                return;
            }

            // 非法方块预扫（SAFE 非 OP）：与捕获路同口径，任何写盘之前整单拒
            if (effMode == CaptureMode.SAFE && !isOp) {
                for (int x = sMin.getX(); x <= sMax.getX(); x++) {
                    for (int y = sMin.getY(); y <= sMax.getY(); y++) {
                        for (int z = sMin.getZ(); z <= sMax.getZ(); z++) {
                            BlockState state = level.getBlockState(new BlockPos(x, y, z));
                            if (state.isAir()) continue;
                            if (BlueprintSanitizer.isIllegalBlock(state)) {
                                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                                        BlueprintLogger.area(sMin, sMax) + " " + BlueprintLogger.name(sanitizedName),
                                        "illegal_block=" + state.getBlock().getName().getString());
                                sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                                return;
                            }
                        }
                    }
                }
            }

            data.size = new BlockPos(
                    sMax.getX() - sMin.getX() + 1,
                    sMax.getY() - sMin.getY() + 1,
                    sMax.getZ() - sMin.getZ() + 1);

            // 危险 NBT 处理（按模式）：SAFE 非 OP / SANITIZE 净化，FORCE 原样
            boolean stripDangerous = switch (effMode) {
                case SAFE     -> !isOp;
                case SANITIZE -> true;
                default       -> false;
            };

            // 扫描区域方块（与 CaptureAreaMessage 相同逻辑）
            java.util.Set<String> latePlaceIds = BlueprintConfig.getLatePlaceBlocks();
            for (int x = sMin.getX(); x <= sMax.getX(); x++) {
                for (int y = sMin.getY(); y <= sMax.getY(); y++) {
                    for (int z = sMin.getZ(); z <= sMax.getZ(); z++) {
                        BlockPos wPos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(wPos);
                        if (state.isAir()) continue;
                        // 净化模式：非法方块不录入（对应位置视为空气）
                        if (effMode == CaptureMode.SANITIZE && BlueprintSanitizer.isIllegalBlock(state)) continue;
                        BlockEntity be = level.getBlockEntity(wPos);
                        CompoundTag nbt = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
                        if (nbt != null) {
                            // 安全线按模式；FORCE 也保留防复制这条（工程蓝图不清除源区域、
                            // 且绑定固定坐标可反复放置，留着箱内物品就是一台复制机）。
                            // Bees（蜂箱蜜蜂）不在清理范围内。
                            if (stripDangerous) {
                                nbt = BlueprintSanitizer.stripDangerousBlockNbt(nbt);
                            } else {
                                nbt = BlueprintSanitizer.stripItemContents(nbt);
                            }
                        }
                        BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(wPos.subtract(sMin), state, nbt);
                        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(state.getBlock()).toString();
                        if (latePlaceIds.contains(blockId)) {
                            data.lateBlocks.add(entry);
                        } else if (BlueprintData.isDependent(state)) {
                            data.dependentBlocks.add(entry);
                        } else {
                            data.normalBlocks.add(entry);
                        }
                    }
                }
            }

            // 扫描装饰实体（画、展示框、荧光展示框）
            AABB captureBox = new AABB(
                    sMin.getX(), sMin.getY(), sMin.getZ(),
                    sMax.getX() + 1.0, sMax.getY() + 1.0, sMax.getZ() + 1.0);
            level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                    .forEach(e -> CaptureAreaMessage.captureHangingEntity(e, sMin, data, false));
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                    .forEach(e -> CaptureAreaMessage.captureHangingEntity(e, sMin, data, false));

            // 生成材料清单
            data.materialList = MaterialList.fromBlueprintData(data);
            // 创建信息
            data.creatorName = player.getScoreboardName();
            data.sourceName  = player.getScoreboardName();
            data.ownerUuid   = player.getUUID().toString();
            com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, level);
            data.createdAt   = System.currentTimeMillis();

            if (dbg) SpatialBlueprintMod.LOGGER.info(
                    "[EngBP][DBG] 扫描完成 mode={} normal={} dependent={} late={} entities={} 材料种类={}",
                    effMode, data.normalBlocks.size(), data.dependentBlocks.size(),
                    data.lateBlocks.size(), data.entities.size(),
                    data.materialList != null ? data.materialList.blockCounts.size() : 0);
        } else {
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 尝试加载源蓝图 sourceId='{}'", msg.sourceId);
            data = BlueprintStorage.load(msg.sourceId);
            if (data == null) {
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：源蓝图 '{}' 文件不存在", msg.sourceId);
                return;
            }
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 源蓝图加载成功 name='{}' category='{}' mode={}",
                    data.name, data.category, effMode);

            // 复制分支按模式净化（与捕获路同一套语义）
            BlueprintSanitizer.UploadScan scan = BlueprintSanitizer.scanBlueprintData(
                    data, BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(), BlueprintConfig.MAX_NBT_DEPTH.get());
            if (effMode == CaptureMode.SAFE && !isOp && !scan.isClean()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.name(sanitizedName) + " source=" + msg.sourceId,
                        "engineering_copy_illegal");
                sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                return;
            }
            if (effMode == CaptureMode.SANITIZE) {
                BlueprintSanitizer.sanitizeForUploadInPlace(data,
                        BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(), BlueprintConfig.MAX_NBT_DEPTH.get());
            } else if (effMode == CaptureMode.SAFE) {
                BlueprintSanitizer.stripContainerAndSignNbtInPlace(data);
            }
            // FORCE：原样保留。

            // 净化会删方块 / 倒容器：材料清单必须在净化之后重算
            data.materialList = MaterialList.fromBlueprintData(data);
        }

        // 写入工程专属字段
        data.category       = "engineering";
        data.name           = sanitizedName;
        data.fixedDimension = msg.dimension;
        data.fixedStartPos  = msg.startPos;
        data.fixedEndPos    = msg.endPos;
        data.saveMode       = effMode;
        // 与 effMode 绑定（CaptureAreaMessage / CaptureProjectionMessage 同）：只有 FORCE 放置免材料。
        // 复制分支不能沿用源文件的值 —— 源是 FORCE、这里选了 SANITIZE，副本就该按净化蓝图要材料。
        data.opBypassCapture = effMode == CaptureMode.FORCE;
        data.noteShort      = msg.noteShort;
        data.noteLong       = msg.noteLong;
        // 构建时长：OP 可自定义（夹到下限），否则按最终方块数自动分档
        int finalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
        if (isOp && msg.animDuration > 0) {
            data.animDuration = Math.max(BlueprintConfig.ANIMATION_DURATION_MIN.get(), msg.animDuration);
        } else {
            data.animDuration = BlueprintConfig.getAutoDuration(finalBlocks);
        }
        // 始终覆盖为当前创建者信息（即使从源蓝图复制也记录工程创建时间）
        data.creatorName    = player.getScoreboardName();
        data.sourceName     = player.getScoreboardName();
        data.ownerUuid      = player.getUUID().toString();
        com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, level);
        data.createdAt      = System.currentTimeMillis();

        UUID newId = UUID.randomUUID();
        BlueprintStorage.save(newId, data);
        if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 蓝图已保存 newId='{}'", newId);

        // 将玩家主手替换为工程蓝图物品
        ItemStack engStack = new ItemStack(SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get());
        AbstractBlueprintItem.setBlueprintId(engStack, newId.toString());
        SpatialBlueprintItem.setBlueprintName(engStack, sanitizedName);
        SpatialBlueprintItem.setSaveMode(engStack, data.saveMode);
        // 写入固定坐标绑定数据（使 hasBoundProject() 返回 true，界面预览/构建按钮可用）
        EngineeringBlueprintItem.setBoundProject(engStack, newId.toString(),
                data.fixedDimension, data.fixedStartPos, data.fixedEndPos);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, engStack);
        ItemSyncUtil.syncHand(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        // 冷却只在这里起算：蓝图已落盘、物品已换好，是一次真正成功的创建
        com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage.notifySuccess(player);
        // 主动推送蓝图数据到客户端缓存，无需玩家手动点击"下载数据"
        BlueprintNetwork.sendBlueprintTo(player, newId.toString(), data);
        // 成功提示改由服务端发（原先创建页在发包那一刻就先报「已创建」，服务端拒了也照报）
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "spatial_blueprint.gui.placement.engineering_created"), false);
        if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 主手物品已替换为 EngineeringBlueprintItem id='{}' name='{}'，已推送数据到客户端", newId, sanitizedName);

        SpatialBlueprintMod.LOGGER.info(
            "[EngBP] '{}' 创建工程蓝图 id='{}' name='{}' source='{}' mode={}",
            player.getScoreboardName(), newId, sanitizedName, msg.sourceId, effMode);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(success, messageKey));
    }
}
