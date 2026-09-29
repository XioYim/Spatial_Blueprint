package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：创建投影蓝图。
 *
 * <p>与 {@link CaptureAreaMessage} 的区别：
 * <ol>
 *   <li>不清除原有方块</li>
 *   <li>额外生成 {@link MaterialList}，内嵌于蓝图文件的 {@code materialList} 字段</li>
 *   <li>给玩家发放 {@link ProjectionBlueprintItem} 而非 SpatialBlueprintItem</li>
 * </ol>
 */
public class CaptureProjectionMessage {

    private final String name;
    private final int durationSeconds;
    private final com.xioyim.spatialblueprint.util.CaptureMode mode;
    private final String noteShort;
    private final String noteLong;
    /** 是否创建为奖励蓝图（OP 专属的投影变体）。 */
    private final boolean reward;

    public CaptureProjectionMessage(String name, int durationSeconds) {
        this(name, durationSeconds, com.xioyim.spatialblueprint.util.CaptureMode.SAFE);
    }

    public CaptureProjectionMessage(String name, int durationSeconds,
                                    com.xioyim.spatialblueprint.util.CaptureMode mode) {
        this(name, durationSeconds, mode, "", "");
    }

    public CaptureProjectionMessage(String name, int durationSeconds,
                                    com.xioyim.spatialblueprint.util.CaptureMode mode,
                                    String noteShort, String noteLong) {
        this(name, durationSeconds, mode, noteShort, noteLong, false);
    }

    /**
     * @param reward 是否创建为<b>奖励蓝图</b>（OP 专属）—— 投影蓝图的特殊变体：
     *               不含材料清单、免费放置、用后销毁
     */
    public CaptureProjectionMessage(String name, int durationSeconds,
                                    com.xioyim.spatialblueprint.util.CaptureMode mode,
                                    String noteShort, String noteLong, boolean reward) {
        this.name = name;
        this.durationSeconds = durationSeconds;
        this.mode = mode;
        this.noteShort = noteShort == null ? "" : noteShort;
        this.noteLong = noteLong == null ? "" : noteLong;
        this.reward = reward;
    }

    public static void encode(CaptureProjectionMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name);
        buf.writeInt(msg.durationSeconds);
        buf.writeInt(msg.mode.ordinal());
        buf.writeUtf(msg.noteShort, 15);
        buf.writeUtf(msg.noteLong, 200);
        buf.writeBoolean(msg.reward);
    }

    public static CaptureProjectionMessage decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(BlueprintValidator.MAX_NAME_LENGTH * 2);
        int duration = buf.readInt();
        com.xioyim.spatialblueprint.util.CaptureMode mode =
                com.xioyim.spatialblueprint.util.CaptureMode.fromOrdinal(buf.readInt());
        String noteShort = buf.readUtf(15);
        String noteLong = buf.readUtf(200);
        boolean reward = buf.readBoolean();
        return new CaptureProjectionMessage(name, duration, mode, noteShort, noteLong, reward);
    }

    public static void handle(CaptureProjectionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!BlueprintValidator.checkAndUpdateCooldown(player)) return;

            // ── 找手中的空白蓝图 ─────────────────────────────────────────
            ItemStack stack = null;
            InteractionHand hand = null;
            ItemStack mainHand = player.getMainHandItem();
            if (mainHand.getItem() instanceof BlankBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND;
            } else {
                ItemStack offHand = player.getOffhandItem();
                if (offHand.getItem() instanceof BlankBlueprintItem) {
                    stack = offHand; hand = InteractionHand.OFF_HAND;
                }
            }
            if (stack == null) {
                sendResult(player, false, "spatial_blueprint.error.no_blank_blueprint");
                return;
            }

            String safeName = BlueprintValidator.sanitizeName(msg.name);

            // ── 校验选区 ──────────────────────────────────────────────────
            BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
            BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
            if (p1 == null || p2 == null) {
                sendResult(player, false, "spatial_blueprint.error.missing_points");
                return;
            }

            BlueprintValidator.ValidationResult slot = BlueprintValidator.checkSlot(player, "project");
            if (!slot.valid()) {
                sendResult(player, false, slot.failKey(), slot.detailKey(), slot.detailArgs());
                return;
            }

            BlueprintValidator.ValidationResult validation =
                    BlueprintValidator.validateCaptureArea(player, p1, p2,
                            com.xioyim.spatialblueprint.util.FluidCaptureFilter.ALL, "project");
            if (!validation.valid()) {
                sendResult(player, false, validation.failKey(), validation.detailKey(), validation.detailArgs());
                return;
            }

            BlockPos min = new BlockPos(
                    Math.min(p1.getX(), p2.getX()),
                    Math.min(p1.getY(), p2.getY()),
                    Math.min(p1.getZ(), p2.getZ()));
            BlockPos max = new BlockPos(
                    Math.max(p1.getX(), p2.getX()),
                    Math.max(p1.getY(), p2.getY()),
                    Math.max(p1.getZ(), p2.getZ()));

            BlueprintLogger.intent(player, BlueprintLogger.Action.CAPTURE,
                    "[projection] " + BlueprintLogger.area(min, max)
                    + " " + BlueprintLogger.volume(min, max)
                    + " " + BlueprintLogger.name(safeName));

            ServerLevel level = player.serverLevel();

            // ── 非法方块检测（按模式）────────────────────────────────────
            // SAFE：普通/创造拒绝、OP 放行；SANITIZE：捕获阶段移除；FORCE：放行。
            boolean isOp = player.hasPermissions(2);
            boolean isCreative = player.isCreative();
            // FORCE 仅 OP 可用。msg.mode 由客户端决定，非 OP 改包发 FORCE 即可绕过
            // 全部非法方块检测并捕获生物，因此必须在服务端降级。SANITIZE 不限权限。
            final com.xioyim.spatialblueprint.util.CaptureMode effMode =
                    (msg.mode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE && !isOp)
                            ? com.xioyim.spatialblueprint.util.CaptureMode.SAFE
                            : msg.mode;

            // FORCE 是 OP 的「强制捕获，不受任何限制」：容器内容与实体物品完整保留。
            // 其余模式一律倒空 —— 投影蓝图不清除源区域，留着箱内物品就是一台复制机。
            final boolean stripContents = effMode != com.xioyim.spatialblueprint.util.CaptureMode.FORCE;
            if (effMode == com.xioyim.spatialblueprint.util.CaptureMode.SAFE && !isOp) {
                outer_illegal:
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            BlockState state = level.getBlockState(new BlockPos(x, y, z));
                            if (BlueprintSanitizer.isIllegalBlock(state)) {
                                sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                                return;
                            }
                        }
                    }
                }
            }

            // ── 捕捉方块数据 ──────────────────────────────────────────────
            BlueprintData data = new BlueprintData();
            data.name = safeName;
            data.size = new BlockPos(
                    max.getX() - min.getX() + 1,
                    max.getY() - min.getY() + 1,
                    max.getZ() - min.getZ() + 1);
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;
                        // 净化模式：非法方块不录入（视为空气）
                        if (effMode == com.xioyim.spatialblueprint.util.CaptureMode.SANITIZE
                                && BlueprintSanitizer.isIllegalBlock(state)) continue;
                        BlockEntity be = level.getBlockEntity(pos);
                        // 容器内容清空（防复制）：投影蓝图不清除源区域，
                        // 留着箱内物品等于给玩家一台复制机。FORCE（OP 强制）完整保留。
                        // 走通用清理而不是固定键名表 —— 后者看不见其他 mod 的容器、
                        // 套娃潜影盒与陶罐的 item 单数键；Bees（蜂箱蜜蜂）不在清理范围内。
                        CompoundTag nbt = null;
                        if (be != null) {
                            CompoundTag raw = be.saveWithFullMetadata();
                            nbt = stripContents ? BlueprintSanitizer.stripItemContents(raw) : raw;
                        }
                        BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(pos.subtract(min), state, nbt);
                        Set<String> latePlaceIds = BlueprintConfig.getLatePlaceBlocks();
                        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
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
            Collections.shuffle(data.normalBlocks);
            data.dependentBlocks.sort((a, b) -> {
                boolean aDown = BlueprintData.isDownwardGrowing(a.state);
                boolean bDown = BlueprintData.isDownwardGrowing(b.state);
                if (aDown != bDown) return Boolean.compare(aDown, bDown);
                if (aDown) return Integer.compare(b.pos.getY(), a.pos.getY());
                return Integer.compare(a.pos.getY(), b.pos.getY());
            });

            // ── 捕捉实体（按模式）────────────────────────────────────────
            AABB captureBox = new AABB(
                    min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
            boolean sanitizeEntities = effMode == com.xioyim.spatialblueprint.util.CaptureMode.SANITIZE;
            // 生物/拴绳结捕获：SAFE 仅创造游戏模式；SANITIZE 不捕获；FORCE（OP）捕获
            boolean captureMobs = switch (effMode) {
                case SAFE     -> isCreative;
                case SANITIZE -> false;
                default       -> true;
            };
            // 画
            level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                    .forEach(entity -> captureHangingEntity(entity, min, data));
            // 展示框：SANITIZE 移除物品
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true).forEach(entity -> {
                CompoundTag nbt = new CompoundTag();
                if (!entity.save(nbt)) return;
                nbt.remove("UUID");
                // 投影蓝图不清除源区域，展示框里的物品不带走（防复制）；FORCE 保留
                if (stripContents) nbt.remove("Item");
                addProjectionEntityEntry(nbt, entity, min, data);
            });
            // 盔甲架：装备不带走（防复制，同上）；FORCE 保留
            level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, captureBox, e -> true)
                    .forEach(entity -> {
                        CompoundTag nbt = new CompoundTag();
                        if (!entity.save(nbt)) return;
                        nbt.remove("UUID");
                        if (stripContents) nbt.remove("ArmorItems");
                        if (stripContents) nbt.remove("HandItems");
                        addProjectionEntityEntry(nbt, entity, min, data);
                    });
            // 白名单生物（按模式）
            if (captureMobs) {
                for (net.minecraft.world.entity.LivingEntity le :
                        level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                                captureBox, e -> e instanceof net.minecraft.world.entity.LivingEntity
                                        && !(e instanceof net.minecraft.world.entity.player.Player))) {
                    String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                    if (BlueprintSanitizer.ALLOWED_MOB_ENTITIES.contains(mobId)) {
                        CompoundTag nbt = new CompoundTag();
                        if (!le.save(nbt)) continue;
                        nbt.remove("UUID");
                        // 驮兽箱子 / 装备倒空（防复制，源区域不清除）；FORCE 保留
                        if (stripContents) BlueprintSanitizer.stripMobInventoryInPlace(nbt);
                        addProjectionEntityEntry(nbt, le, min, data);
                    }
                }
                // 拴绳结（LeashFenceKnotEntity 非 LivingEntity，需单独捕获）
                level.getEntitiesOfClass(net.minecraft.world.entity.decoration.LeashFenceKnotEntity.class,
                                captureBox, e -> true)
                        .forEach(entity -> {
                            CompoundTag nbt = new CompoundTag();
                            if (!entity.save(nbt)) return;
                            nbt.remove("UUID");
                            addProjectionEntityEntry(nbt, entity, min, data);
                        });
            }

            // ── 嵌入材料清单 + 分类（内嵌于 litematic 文件，不再另存材料清单文件）────
            // 奖励蓝图（OP 专属）：分类为 reward，材料清单留空 —— 它是发给玩家的奖励，
            // 放置完全免费。OP 事后可以自己往清单里加材料。
            boolean asReward = msg.reward && isOp && player.isCreative();
            data.materialList = asReward ? new MaterialList() : MaterialList.fromBlueprintData(data);
            data.category     = asReward ? "reward" : "project";
            data.creatorName  = player.getScoreboardName();
            data.sourceName   = player.getScoreboardName();
            data.ownerUuid    = player.getUUID().toString();
            com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, player.serverLevel());
            data.createdAt    = System.currentTimeMillis();
            data.saveMode     = effMode;
            // OP 以 FORCE 模式捕获的蓝图：放置时跳过材料校验与扣除
            data.opBypassCapture = effMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE;
            data.noteShort    = msg.noteShort;
            data.noteLong     = msg.noteLong;

            // ── 写入动画时长（全局 min/max 限制）────────────────────────
            int capDur = Math.max(BlueprintConfig.ANIMATION_DURATION_MIN.get(), msg.durationSeconds);
            capDur = Math.min(capDur, BlueprintConfig.ANIMATION_DURATION_MAX.get());
            data.animDuration = capDur;

            // ── 保存蓝图 dat 文件（包含结构、材料清单、分类、名称、时长）──
            UUID blueprintId = UUID.randomUUID();
            BlueprintStorage.save(blueprintId, data);

            // ── 创建蓝图物品（奖励蓝图是投影的特殊变体，走独立物品）────────
            ItemStack projStack = new ItemStack(asReward
                    ? SpatialBlueprintItems.REWARD_BLUEPRINT.get()
                    : SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            ProjectionBlueprintItem.setBlueprintName(projStack, safeName);
            ProjectionBlueprintItem.setBlueprintId(projStack, blueprintId.toString());
            SpatialBlueprintItem.setSaveMode(projStack, data.saveMode);

            player.setItemInHand(hand, projStack);
            ItemSyncUtil.syncHand(player, hand);

            // 主动推送蓝图数据到捕捉玩家的客户端缓存，使其无需手动点击"下载数据"
            BlueprintNetwork.sendBlueprintTo(player, blueprintId.toString(), data);

            int total = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            // 冷却只在这里起算：蓝图已落盘、物品已换好，是一次真正成功的创建
            com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage.notifySuccess(player);

            BlueprintLogger.success(player, BlueprintLogger.Action.CAPTURE,
                    "[projection] " + BlueprintLogger.area(min, max)
                    + " " + BlueprintLogger.name(safeName)
                    + " blocks=" + total
                    + " matKinds=" + data.materialList.blockCounts.size());

            sendResult(player, true, "");
        });
        ctx.get().setPacketHandled(true);
    }

    private static void captureHangingEntity(net.minecraft.world.entity.Entity entity,
                                              BlockPos min, BlueprintData data) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.save(nbt)) return;
        nbt.remove("UUID");
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        int relTileX = nbt.getInt("TileX") - min.getX();
        int relTileY = nbt.getInt("TileY") - min.getY();
        int relTileZ = nbt.getInt("TileZ") - min.getZ();
        byte facing = type.equals("minecraft:painting")
                ? nbt.getByte("facing") : nbt.getByte("Facing");
        data.entities.add(new BlueprintData.EntityEntry(relTileX, relTileY, relTileZ, facing, type, nbt));
    }

    /** 通用实体条目构造（盔甲架/生物用 Pos 计算相对坐标）。 */
    private static void addProjectionEntityEntry(CompoundTag nbt, net.minecraft.world.entity.Entity entity,
                                                 BlockPos min, BlueprintData data) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        byte facing = type.equals("minecraft:painting")
                ? nbt.getByte("facing") : nbt.getByte("Facing");
        int relX, relY, relZ;
        if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity) {
            relX = nbt.getInt("TileX") - min.getX();
            relY = nbt.getInt("TileY") - min.getY();
            relZ = nbt.getInt("TileZ") - min.getZ();
        } else {
            net.minecraft.nbt.ListTag posList = nbt.getList("Pos", 6);
            relX = (int) Math.floor(posList.getDouble(0)) - min.getX();
            relY = (int) Math.floor(posList.getDouble(1)) - min.getY();
            relZ = (int) Math.floor(posList.getDouble(2)) - min.getZ();
        }
        data.entities.add(new BlueprintData.EntityEntry(relX, relY, relZ, facing, type, nbt));
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        sendResult(player, success, messageKey, "", null);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey, String detailKey, Object[] detailArgs) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey, detailKey, detailArgs));
    }
}
