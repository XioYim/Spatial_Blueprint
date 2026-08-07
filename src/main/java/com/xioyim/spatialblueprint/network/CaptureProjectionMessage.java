package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /**
     * NBT keys that may contain item stacks inside a BlockEntity compound.
     * These are stripped when capturing for a projection blueprint to prevent
     * item duplication (the source area is NOT cleared for projection captures).
     */
    private static final java.util.Set<String> ITEM_NBT_KEYS = java.util.Set.of(
            "Items", "Item", "inventory", "Inventory",
            "RecordItem", "Book", "Patterns");

    private final String name;
    private final int durationSeconds;

    public CaptureProjectionMessage(String name, int durationSeconds) {
        this.name = name;
        this.durationSeconds = durationSeconds;
    }

    public static void encode(CaptureProjectionMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name);
        buf.writeInt(msg.durationSeconds);
    }

    public static CaptureProjectionMessage decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(BlueprintValidator.MAX_NAME_LENGTH * 2);
        int duration = buf.readInt();
        return new CaptureProjectionMessage(name, duration);
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

            BlueprintValidator.ValidationResult validation =
                    BlueprintValidator.validateCaptureArea(player, p1, p2);
            if (!validation.valid()) {
                sendResult(player, false, validation.failKey(), validation.detail());
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

            // ── 排除检查 ─────────────────────────────────────────────────
            List<String> illegalBlocks = new ArrayList<>();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockState state = level.getBlockState(new BlockPos(x, y, z));
                        if (BlueprintConfig.isExcluded(state.getBlock())) {
                            illegalBlocks.add(state.getBlock().getName().getString());
                        }
                    }
                }
            }
            if (!illegalBlocks.isEmpty()) {
                sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                return;
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
                        BlockEntity be = level.getBlockEntity(pos);
                        // Strip item-containing NBT keys to prevent duplication exploits.
                        // Projection blueprints do NOT clear the source area, so preserving
                        // item inventories would allow unlimited item duplication on SMP servers.
                        CompoundTag nbt = null;
                        if (be != null) {
                            CompoundTag rawNbt = be.saveWithFullMetadata();
                            for (String key : ITEM_NBT_KEYS) rawNbt.remove(key);
                            nbt = rawNbt;
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

            // ── 捕捉装饰实体 ─────────────────────────────────────────────
            AABB captureBox = new AABB(
                    min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
            level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                    .forEach(entity -> captureHangingEntity(entity, min, data));
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                    .forEach(entity -> captureHangingEntity(entity, min, data));

            // ── 嵌入材料清单 + 分类（内嵌于 litematic 文件，不再另存材料清单文件）────
            data.materialList = MaterialList.fromBlueprintData(data);
            data.category     = "project";
            data.creatorName  = player.getScoreboardName();
            data.sourceName   = player.getScoreboardName();
            data.createdAt    = System.currentTimeMillis();

            // ── 写入动画时长（全局 min/max 限制）────────────────────────
            int capDur = Math.max(BlueprintConfig.ANIMATION_DURATION_MIN.get(), msg.durationSeconds);
            capDur = Math.min(capDur, BlueprintConfig.ANIMATION_DURATION_MAX.get());
            data.animDuration = capDur;

            // ── 保存蓝图 dat 文件（包含结构、材料清单、分类、名称、时长）──
            UUID blueprintId = UUID.randomUUID();
            BlueprintStorage.save(blueprintId, data);

            // ── 创建投影蓝图物品 ──────────────────────────────────────────
            ItemStack projStack = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            ProjectionBlueprintItem.setBlueprintName(projStack, safeName);
            ProjectionBlueprintItem.setBlueprintId(projStack, blueprintId.toString());

            player.setItemInHand(hand, projStack);
            ItemSyncUtil.syncHand(player, hand);

            // 主动推送蓝图数据到捕捉玩家的客户端缓存，使其无需手动点击"下载数据"
            BlueprintNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new S2C_BlueprintDataMessage(blueprintId.toString(), data));

            int total = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
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
        int relTileX = nbt.getInt("TileX") - min.getX();
        int relTileY = nbt.getInt("TileY") - min.getY();
        int relTileZ = nbt.getInt("TileZ") - min.getZ();
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        byte facing = type.equals("minecraft:painting")
                ? nbt.getByte("facing") : nbt.getByte("Facing");
        data.entities.add(new BlueprintData.EntityEntry(relTileX, relTileY, relTileZ, facing, type, nbt));
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        sendResult(player, success, messageKey, "");
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey, String detail) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey, detail));
    }
}
