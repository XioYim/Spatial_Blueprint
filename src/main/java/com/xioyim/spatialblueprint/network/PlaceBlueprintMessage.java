package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.BuildBlueprintItem;
import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintPlacementHandler;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class PlaceBlueprintMessage {
    public final BlockPos targetPos;
    public final Rotation rotation;
    public final int xOffset;
    public final int yOffset;
    public final int zOffset;
    /** true = clear entire bounding box first (default); false = replace-only mode */
    public final boolean clearFirst;
    /** 流体过滤模式：放置时是否跳过特定流体方块。 */
    public final com.xioyim.spatialblueprint.util.FluidDisplayMode fluidMode;

    public PlaceBlueprintMessage(BlockPos targetPos, Rotation rotation, int xOffset, int yOffset, int zOffset,
                                  boolean clearFirst,
                                  com.xioyim.spatialblueprint.util.FluidDisplayMode fluidMode) {
        this.targetPos = targetPos;
        this.rotation = rotation;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;
        this.clearFirst = clearFirst;
        this.fluidMode = fluidMode;
    }

    public static void encode(PlaceBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.targetPos);
        buf.writeUtf(msg.rotation.name(), 32);
        buf.writeInt(msg.xOffset);
        buf.writeInt(msg.yOffset);
        buf.writeInt(msg.zOffset);
        buf.writeBoolean(msg.clearFirst);
        buf.writeByte(msg.fluidMode.toByte());
    }

    public static PlaceBlueprintMessage decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String rotStr = buf.readUtf(32);
        int x = buf.readInt(), y = buf.readInt(), z = buf.readInt();
        boolean clearFirst = buf.readBoolean();
        com.xioyim.spatialblueprint.util.FluidDisplayMode fluidMode =
                com.xioyim.spatialblueprint.util.FluidDisplayMode.fromByte(buf.readByte());

        Rotation rotation = Rotation.NONE;
        try {
            rotation = Rotation.valueOf(rotStr);
        } catch (IllegalArgumentException ignored) {}

        return new PlaceBlueprintMessage(pos, rotation, x, y, z, clearFirst, fluidMode);
    }

    public static void handle(PlaceBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!BlueprintValidator.checkAndUpdateCooldown(player)) return;

            // ── 识别手中物品（DimensionalBlueprintItem 须先于 SpatialBlueprintItem 检测）────
            ItemStack stack = null;
            InteractionHand hand = null;
            boolean isProjection  = false;
            boolean isDimensional = false;
            boolean isEngineering = false;

            ItemStack mainHand = player.getMainHandItem();
            ItemStack offHand  = player.getOffhandItem();
            if (mainHand.getItem() instanceof DimensionalBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND; isDimensional = true;
            } else if (mainHand.getItem() instanceof SpatialBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND;
            } else if (mainHand.getItem() instanceof ProjectionBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND; isProjection = true;
            } else if (mainHand.getItem() instanceof EngineeringBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND; isProjection = true; isEngineering = true;
            } else if (offHand.getItem() instanceof DimensionalBlueprintItem) {
                stack = offHand; hand = InteractionHand.OFF_HAND; isDimensional = true;
            } else if (offHand.getItem() instanceof SpatialBlueprintItem) {
                stack = offHand; hand = InteractionHand.OFF_HAND;
            } else if (offHand.getItem() instanceof ProjectionBlueprintItem) {
                stack = offHand; hand = InteractionHand.OFF_HAND; isProjection = true;
            } else if (offHand.getItem() instanceof EngineeringBlueprintItem) {
                stack = offHand; hand = InteractionHand.OFF_HAND; isProjection = true; isEngineering = true;
            }

            if (stack == null) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(msg.targetPos), "no_blueprint_item");
                sendResult(player, false, "spatial_blueprint.error.no_blueprint_item");
                return;
            }

            // ── 次元蓝图权限检查：非 OP 且非创建者不可使用 ────────────────
            if (isDimensional) {
                String creator = stack.hasTag() && stack.getTag().contains("creatorName")
                        ? stack.getTag().getString("creatorName") : "";
                boolean isOp = player.hasPermissions(2);
                boolean isOwner = creator.isEmpty() || creator.equals(player.getScoreboardName());
                if (!isOp && !isOwner) {
                    sendResult(player, false, "spatial_blueprint.error.dimensional_not_owner");
                    return;
                }
            }

            // ── 读取蓝图数据 ──────────────────────────────────────────────
            BlueprintData data = isProjection
                    ? ProjectionBlueprintItem.getBlueprintData(stack)
                    : SpatialBlueprintItem.getBlueprintData(stack);
            if (data == null) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(msg.targetPos), "no_blueprint_data");
                sendResult(player, false, "spatial_blueprint.error.no_blueprint_data");
                return;
            }

            // ── 工程蓝图：服务端维度 / 距离约束校验（防止客户端绕过）────────
            if (isEngineering) {
                EngineeringBlueprintItem.ProjectBoundsResult boundsResult =
                        EngineeringBlueprintItem.checkProjectBounds(stack, player, player.serverLevel());
                if (boundsResult == EngineeringBlueprintItem.ProjectBoundsResult.WRONG_DIM) {
                    BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                            BlueprintLogger.pos(msg.targetPos), "engineering_wrong_dimension");
                    sendResult(player, false, "spatial_blueprint.error.engineering.wrong_dimension");
                    return;
                }
                if (boundsResult == EngineeringBlueprintItem.ProjectBoundsResult.TOO_FAR) {
                    BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                            BlueprintLogger.pos(msg.targetPos), "engineering_too_far");
                    sendResult(player, false, "spatial_blueprint.error.engineering.too_far");
                    return;
                }
            }

            // ── 距离 / 坐标校验 ───────────────────────────────────────────
            BlueprintValidator.ValidationResult distCheck =
                    BlueprintValidator.validatePlacementDistance(player, msg.targetPos);
            if (!distCheck.valid()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(msg.targetPos), distCheck.failKey());
                sendResult(player, false, distCheck.failKey());
                return;
            }

            int clampedX = BlueprintValidator.clampOffset(msg.xOffset);
            int clampedY = BlueprintValidator.clampOffset(msg.yOffset);
            int clampedZ = BlueprintValidator.clampOffset(msg.zOffset);
            BlockPos actualPos = msg.targetPos.offset(clampedX, clampedY, clampedZ);

            BlueprintValidator.ValidationResult validation =
                    BlueprintValidator.validateBlockPos(player.serverLevel(), actualPos);
            if (!validation.valid()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(actualPos) + " " + BlueprintLogger.name(data.name),
                        validation.failKey());
                sendResult(player, false, validation.failKey());
                return;
            }

            // ── 服务端放置前合法性校验（生存模式，镜像客户端 PlacementPreValidator）──
            if (!player.isCreative()) {
                net.minecraft.server.level.ServerLevel sLevel = player.serverLevel();
                BlockPos size = data.size;
                int sX = size.getX(), sY = size.getY(), sZ = size.getZ();
                boolean swapXZ = (msg.rotation == Rotation.CLOCKWISE_90 || msg.rotation == Rotation.COUNTERCLOCKWISE_90);
                int bbX = swapXZ ? sZ : sX;
                int bbZ = swapXZ ? sX : sZ;

                // 实体阻挡检查
                net.minecraft.world.phys.AABB fullBox = new net.minecraft.world.phys.AABB(
                        actualPos.getX(), actualPos.getY(), actualPos.getZ(),
                        actualPos.getX() + bbX, actualPos.getY() + sY, actualPos.getZ() + bbZ);
                boolean entityBlocking = !sLevel.getEntitiesOfClass(
                        net.minecraft.world.entity.LivingEntity.class, fullBox,
                        e -> !(e instanceof net.minecraft.world.entity.player.Player)).isEmpty();
                if (entityBlocking) {
                    sendResult(player, false, "spatial_blueprint.error.placement_blocked_extra_blocks");
                    return;
                }

                // 方块碰撞检查
                outer:
                for (int x = 0; x < sX; x++) {
                    for (int y = 0; y < sY; y++) {
                        for (int z = 0; z < sZ; z++) {
                            BlockPos worldPos = actualPos.offset(
                                    com.xioyim.spatialblueprint.util.BlueprintTransform.x(x, 0, z, size, msg.rotation),
                                    y,
                                    com.xioyim.spatialblueprint.util.BlueprintTransform.z(x, 0, z, size, msg.rotation));
                            if (worldPos.getY() >= sLevel.getMaxBuildHeight()
                                    || worldPos.getY() < sLevel.getMinBuildHeight()) {
                                sendResult(player, false, "spatial_blueprint.error.out_of_bounds");
                                return;
                            }
                            net.minecraft.world.level.block.state.BlockState existing = sLevel.getBlockState(worldPos);
                            if (!existing.isAir() && !existing.canBeReplaced()) {
                                sendResult(player, false, "spatial_blueprint.error.placement_blocked_extra_blocks");
                                return;
                            }
                        }
                    }
                }
            }

            if (data.opBypassCapture) {
                com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                        "[SpatialBlueprint] [op_bypass] placing blueprint '{}' at {} by {} — captured with OP bypass, may contain full NBT content",
                        data.name, actualPos, player.getScoreboardName());
            }
            BlueprintLogger.intent(player, BlueprintLogger.Action.PLACE,
                    (isProjection ? "[projection] " : "") + BlueprintLogger.pos(actualPos)
                    + " " + BlueprintLogger.name(data.name)
                    + " rotation=" + msg.rotation.name()
                    + " blocks=" + (data.normalBlocks.size() + data.dependentBlocks.size()));

            // ── 计算动画时长 ──────────────────────────────────────────────
            int configMin    = com.xioyim.spatialblueprint.BlueprintConfig.ANIMATION_DURATION_MIN.get();
            int animDuration = (data.animDuration > 0) ? data.animDuration
                    : SpatialBlueprintItem.getAnimDuration(stack, 8);
            animDuration = Math.max(configMin, animDuration);

            // ── 次元蓝图：始终删除 dat 文件 ──────────────────────────────
            if (isDimensional) {
                String blueprintId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
                if (!blueprintId.isEmpty()) BlueprintStorage.delete(blueprintId);
            }

            // ── 生存模式：放置前先替换物品，防止动画期间重复触发 ──────────
            if (!player.isCreative()) {
                if (isDimensional) {
                    // 校验并消耗材料（若蓝图含材料清单，与投影蓝图行为一致）
                    com.xioyim.spatialblueprint.util.MaterialList mlDim =
                            ProjectionBlueprintItem.getMaterialList(stack);
                    if (mlDim != null && !mlDim.blockCounts.isEmpty()) {
                        if (!ProjectionBlueprintItem.hasSufficientMaterials(stack, mlDim)) {
                            BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                                    BlueprintLogger.pos(actualPos), "insufficient_materials");
                            sendResult(player, false, "spatial_blueprint.error.insufficient_materials");
                            return;
                        }
                        ProjectionBlueprintItem.clearDepositedMaterials(stack);
                    }
                    BlockPos placedMin = actualPos;
                    BlockPos placedMax = (msg.rotation == Rotation.CLOCKWISE_90 || msg.rotation == Rotation.COUNTERCLOCKWISE_90)
                            ? actualPos.offset(data.size.getZ() - 1, data.size.getY() - 1, data.size.getX() - 1)
                            : actualPos.offset(data.size.getX() - 1, data.size.getY() - 1, data.size.getZ() - 1);
                    ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                    CompoundTag buildNbt = new CompoundTag();
                    buildNbt.putString("blueprintName", data.name);
                    buildNbt.putLong("completionTime",
                            player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDuration));
                    buildNbt.putString("sourceType", "void_dimensional");
                    buildNbt.put("captureMin", NbtUtils.writeBlockPos(placedMin));
                    buildNbt.put("captureMax", NbtUtils.writeBlockPos(placedMax));
                    buildNbt.putDouble("tpX", player.getX());
                    buildNbt.putDouble("tpY", player.getY());
                    buildNbt.putDouble("tpZ", player.getZ());
                    buildNbt.putString("tpDimension", player.serverLevel().dimension().location().toString());
                    if (stack.hasTag() && stack.getTag().contains("creatorName"))
                        buildNbt.putString("creatorName", stack.getTag().getString("creatorName"));
                    buildStack.setTag(buildNbt);
                    player.setItemInHand(hand, buildStack);
                    ItemSyncUtil.syncHand(player, hand);
                } else if (isProjection) {
                    // 校验材料充足再替换
                    com.xioyim.spatialblueprint.util.MaterialList ml =
                            ProjectionBlueprintItem.getMaterialList(stack);
                    if (!ProjectionBlueprintItem.hasSufficientMaterials(stack, ml)) {
                        BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                                BlueprintLogger.pos(actualPos), "insufficient_materials");
                        sendResult(player, false, "spatial_blueprint.error.insufficient_materials");
                        return;
                    }
                    if (isEngineering) {
                        // 工程蓝图生存模式：参考空间蓝图，立即移除物品（存入的材料随之消耗）
                        player.setItemInHand(hand, ItemStack.EMPTY);
                        ItemSyncUtil.syncHand(player, hand);
                    } else {
                        // 投影蓝图：清除存入材料，转为 BuildBlueprintItem 等待动画完成
                        ProjectionBlueprintItem.clearDepositedMaterials(stack);
                        ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                        CompoundTag buildNbt = new CompoundTag();
                        buildNbt.putString("blueprintName", data.name);
                        if (stack.hasTag()) {
                            CompoundTag src = stack.getTag();
                            if (src.contains("blueprintId"))   buildNbt.putString("blueprintId", src.getString("blueprintId"));
                            if (src.contains("blueprintData")) buildNbt.put("blueprintData", src.getCompound("blueprintData").copy());
                        }
                        buildNbt.putLong("completionTime",
                                player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDuration));
                        buildNbt.putString("sourceType", "project");
                        buildStack.setTag(buildNbt);
                        player.setItemInHand(hand, buildStack);
                        ItemSyncUtil.syncHand(player, hand);
                    }
                } else {
                    // 空间蓝图：立即移除，再开始动画
                    player.setItemInHand(hand, ItemStack.EMPTY);
                    ItemSyncUtil.syncHand(player, hand);
                }
            } else if (isDimensional) {
                // 创造模式次元蓝图：转为 BuildBlueprintItem，动画完成后 onServerTick() 自动恢复为次元蓝图。
                // captureMin/Max 告诉 BuildBlueprintItem 放置范围，sourceType="void_dimensional" 触发恢复逻辑。
                BlockPos placedMin = actualPos;
                BlockPos placedMax = (msg.rotation == Rotation.CLOCKWISE_90 || msg.rotation == Rotation.COUNTERCLOCKWISE_90)
                        ? actualPos.offset(data.size.getZ() - 1, data.size.getY() - 1, data.size.getX() - 1)
                        : actualPos.offset(data.size.getX() - 1, data.size.getY() - 1, data.size.getZ() - 1);
                ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                CompoundTag buildNbt = new CompoundTag();
                buildNbt.putString("blueprintName", data.name);
                buildNbt.putLong("completionTime",
                        player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDuration));
                buildNbt.putString("sourceType", "void_dimensional");
                buildNbt.put("captureMin", NbtUtils.writeBlockPos(placedMin));
                buildNbt.put("captureMax", NbtUtils.writeBlockPos(placedMax));
                buildNbt.putDouble("tpX", player.getX());
                buildNbt.putDouble("tpY", player.getY());
                buildNbt.putDouble("tpZ", player.getZ());
                buildNbt.putString("tpDimension", player.serverLevel().dimension().location().toString());
                if (stack.hasTag() && stack.getTag().contains("creatorName"))
                    buildNbt.putString("creatorName", stack.getTag().getString("creatorName"));
                buildStack.setTag(buildNbt);
                player.setItemInHand(hand, buildStack);
                ItemSyncUtil.syncHand(player, hand);
            } else if (isProjection) {
                // 创造模式投影/工程蓝图：同样转为 BuildBlueprintItem（防止动画期间重复放置）
                ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                CompoundTag buildNbt = new CompoundTag();
                buildNbt.putString("blueprintName", data.name);
                if (stack.hasTag()) {
                    CompoundTag src = stack.getTag();
                    if (src.contains("blueprintId"))   buildNbt.putString("blueprintId", src.getString("blueprintId"));
                    if (src.contains("blueprintData")) buildNbt.put("blueprintData", src.getCompound("blueprintData").copy());
                }
                buildNbt.putLong("completionTime",
                        player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDuration));
                buildNbt.putString("sourceType", isEngineering ? "engineering" : "project");
                buildStack.setTag(buildNbt);
                player.setItemInHand(hand, buildStack);
                ItemSyncUtil.syncHand(player, hand);
            }

            // ── 空间蓝图 + 创造模式：放置前快照，设置撤回冷却 ───────────
            if (!isProjection && !isDimensional && player.isCreative()) {
                int[] bbox = BlueprintPlacementHandler.computeWorldBBox(actualPos, data.size, msg.rotation);
                com.xioyim.spatialblueprint.util.UndoRecord snapshot =
                        BlueprintPlacementHandler.snapshotRegion(
                                player.serverLevel(), bbox[0], bbox[1], bbox[2], bbox[3], bbox[4], bbox[5]);
                com.xioyim.spatialblueprint.util.SBUndoData.get(player.serverLevel())
                        .addRecord(player.getUUID(), snapshot,
                                com.xioyim.spatialblueprint.BlueprintConfig.MAX_UNDO_HISTORY.get());
                com.xioyim.spatialblueprint.util.SBUndoCooldown
                        .setPlacementCooldown(player.getUUID(), animDuration);
            }

            // ── 执行放置（物品已替换，无法再重复触发）────────────────────
            BlueprintPlacementHandler.startPlacement(
                    player.serverLevel(), actualPos, data, msg.rotation, player, animDuration,
                    msg.clearFirst, msg.fluidMode);

            // ── 大型蓝图全服播报 ──────────────────────────────────────────
            if (com.xioyim.spatialblueprint.BlueprintConfig.LARGE_BLUEPRINT_BROADCAST_ENABLED.get()) {
                int totalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
                int threshold = com.xioyim.spatialblueprint.BlueprintConfig.LARGE_BLUEPRINT_BROADCAST_THRESHOLD.get();
                if (totalBlocks >= threshold) {
                    net.minecraft.network.chat.Component broadcast = net.minecraft.network.chat.Component
                            .translatable("spatial_blueprint.broadcast.large_blueprint",
                                    player.getScoreboardName(), totalBlocks, animDuration);
                    player.serverLevel().getServer().getPlayerList().broadcastSystemMessage(broadcast, false);
                }
            }

            BlueprintLogger.success(player, BlueprintLogger.Action.PLACE,
                    (isProjection ? "[projection] " : "") + BlueprintLogger.pos(actualPos)
                    + " " + BlueprintLogger.name(data.name)
                    + " animDuration=" + animDuration + "s");

            sendResult(player, true, "");
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey)
        );
    }
}
