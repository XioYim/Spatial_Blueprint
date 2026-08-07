package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraftforge.network.PacketDistributor;
import com.xioyim.spatialblueprint.network.CaptureAreaMessage;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：从现有蓝图文件创建独立的工程蓝图副本。
 *
 * <p>服务端将源蓝图的全部结构、方块、材料数据完整复制到 {@code engineering/} 子目录，
 * 将 {@code category} 字段改为 {@code "engineering"}，并写入固定坐标信息，
 * 生成新 UUID，不修改源文件。</p>
 *
 * <p>仅限创造模式。</p>
 */
public class C2S_CreateEngineeringBlueprintMessage {

    private final String  sourceId;
    private final String  name;
    private final String  dimension;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final int     maxDistance;

    public C2S_CreateEngineeringBlueprintMessage(String sourceId, String name, String dimension,
                                                  BlockPos startPos, BlockPos endPos, int maxDistance) {
        this.sourceId    = sourceId;
        this.name        = name;
        this.dimension   = dimension;
        this.startPos    = startPos;
        this.endPos      = endPos;
        this.maxDistance = maxDistance;
    }

    public static void encode(C2S_CreateEngineeringBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sourceId, 64);
        buf.writeUtf(msg.name, 128);
        buf.writeUtf(msg.dimension, 256);
        buf.writeBlockPos(msg.startPos);
        buf.writeBlockPos(msg.endPos);
        buf.writeInt(msg.maxDistance);
    }

    public static C2S_CreateEngineeringBlueprintMessage decode(FriendlyByteBuf buf) {
        String   sourceId    = buf.readUtf(64);
        String   name        = buf.readUtf(128);
        String   dimension   = buf.readUtf(256);
        BlockPos startPos    = buf.readBlockPos();
        BlockPos endPos      = buf.readBlockPos();
        int      maxDistance = buf.readInt();
        return new C2S_CreateEngineeringBlueprintMessage(sourceId, name, dimension, startPos, endPos, maxDistance);
    }

    /** Maximum allowed edge length (blocks) for an engineering project bounding box. */
    private static final int MAX_ENG_EDGE = 1024;
    /** Maximum allowed maxDistance value. */
    private static final int MAX_DISTANCE_LIMIT = 99999;

    private static boolean isDebug(ServerPlayer player) {
        return player.isCreative() && player.getTags().contains("DeBug");
    }

    public static void handle(C2S_CreateEngineeringBlueprintMessage msg,
                               Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            boolean dbg = player != null && isDebug(player);

            if (dbg) SpatialBlueprintMod.LOGGER.info(
                    "[EngBP][DBG] 收到消息 player={} creative={} sourceId='{}' name='{}' dim='{}' start={} end={} dist={}",
                    player.getScoreboardName(), player.isCreative(),
                    msg.sourceId, msg.name, msg.dimension, msg.startPos, msg.endPos, msg.maxDistance);

            if (player == null || !player.isCreative()) {
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：非创造模式");
                return;
            }

            // ── 服务端输入校验 ────────────────────────────────────────────
            String sanitizedName = BlueprintValidator.sanitizeName(msg.name);
            if (sanitizedName.isEmpty()) {
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：名称净化后为空，原始='{}'", msg.name);
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

            if (msg.maxDistance < 1 || msg.maxDistance > MAX_DISTANCE_LIMIT) {
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：maxDistance={} 超范围", msg.maxDistance);
                return;
            }

            // sourceId 为空（来自 PointSelectionScreen）时创建空白数据；否则复制现有蓝图
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

                data.size = new BlockPos(
                        sMax.getX() - sMin.getX() + 1,
                        sMax.getY() - sMin.getY() + 1,
                        sMax.getZ() - sMin.getZ() + 1);

                // 扫描区域方块（与 CaptureAreaMessage 相同逻辑）
                java.util.Set<String> latePlaceIds = BlueprintConfig.getLatePlaceBlocks();
                for (int x = sMin.getX(); x <= sMax.getX(); x++) {
                    for (int y = sMin.getY(); y <= sMax.getY(); y++) {
                        for (int z = sMin.getZ(); z <= sMax.getZ(); z++) {
                            BlockPos wPos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(wPos);
                            if (state.isAir()) continue;
                            BlockEntity be = level.getBlockEntity(wPos);
                            CompoundTag nbt = be != null ? be.saveWithFullMetadata() : null;
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
                // 按方块数量自动设定构建时长
                int tb = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
                data.animDuration = BlueprintConfig.getAutoDuration(tb);
                // 创建信息
                data.creatorName = player.getScoreboardName();
                data.sourceName  = player.getScoreboardName();
                data.createdAt   = System.currentTimeMillis();

                if (dbg) SpatialBlueprintMod.LOGGER.info(
                        "[EngBP][DBG] 扫描完成 normal={} dependent={} late={} entities={} 材料种类={}",
                        data.normalBlocks.size(), data.dependentBlocks.size(),
                        data.lateBlocks.size(), data.entities.size(),
                        data.materialList != null ? data.materialList.blockCounts.size() : 0);
            } else {
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 尝试加载源蓝图 sourceId='{}'", msg.sourceId);
                data = BlueprintStorage.load(msg.sourceId);
                if (data == null) {
                    if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 拒绝：源蓝图 '{}' 文件不存在", msg.sourceId);
                    return;
                }
                if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 源蓝图加载成功 name='{}' category='{}' materialList={}",
                        data.name, data.category, data.materialList != null ? data.materialList.blockCounts.size() + "种" : "null");
                // 空间蓝图没有 materialList，从方块数据补算
                if (data.materialList == null) {
                    data.materialList = MaterialList.fromBlueprintData(data);
                    if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] materialList 补算完成 种类={}",
                            data.materialList.blockCounts.size());
                }
                // 空间蓝图没有 animDuration，按方块数量自动计算
                if (data.animDuration <= 0) {
                    int tb2 = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
                    data.animDuration = BlueprintConfig.getAutoDuration(tb2);
                    if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] animDuration 自动分档 {}s ({}方块)", data.animDuration, tb2);
                }
            }

            // 写入工程专属字段
            data.category       = "engineering";
            data.name           = sanitizedName;
            data.fixedDimension = msg.dimension;
            data.fixedStartPos  = msg.startPos;
            data.fixedEndPos    = msg.endPos;
            data.maxDistance    = msg.maxDistance;
            // 始终覆盖为当前创建者信息（即使从源蓝图复制也记录工程创建时间）
            data.creatorName    = player.getScoreboardName();
            data.sourceName     = player.getScoreboardName();
            data.createdAt      = System.currentTimeMillis();

            UUID newId = UUID.randomUUID();
            BlueprintStorage.save(newId, data);
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 蓝图已保存 newId='{}'", newId);

            // 将玩家主手替换为工程蓝图物品
            ItemStack engStack = new ItemStack(SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get());
            engStack.getOrCreateTag().putString("blueprintId", newId.toString());
            SpatialBlueprintItem.setBlueprintName(engStack, sanitizedName);
            // 写入固定坐标绑定数据（使 hasBoundProject() 返回 true，界面预览/构建按钮可用）
            EngineeringBlueprintItem.setBoundProject(engStack, newId.toString(),
                    data.fixedDimension, data.fixedStartPos, data.fixedEndPos, data.maxDistance);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, engStack);
            ItemSyncUtil.syncHand(player, net.minecraft.world.InteractionHand.MAIN_HAND);
            // 主动推送蓝图数据到客户端缓存，无需玩家手动点击"下载数据"
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_BlueprintDataMessage(newId.toString(), data));
            if (dbg) SpatialBlueprintMod.LOGGER.info("[EngBP][DBG] 主手物品已替换为 EngineeringBlueprintItem id='{}' name='{}'，已推送数据到客户端", newId, sanitizedName);

            SpatialBlueprintMod.LOGGER.info(
                "[EngBP] '{}' 创建工程蓝图 id='{}' name='{}' source='{}'",
                player.getScoreboardName(), newId, sanitizedName, msg.sourceId);
        });
        ctx.get().setPacketHandled(true);
    }
}
