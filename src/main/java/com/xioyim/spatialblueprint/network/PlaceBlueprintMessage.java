package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
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
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
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

            if (!BlueprintValidator.checkAndUpdateCooldown(player)) {
                sendPlacementCancelled(player);
                return;
            }

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


            // 奖励蓝图：投影的特殊变体，放置后<b>销毁</b>（与空间蓝图一致）——
            // 投影蓝图可反复使用，奖励要是也能反复用，发一张等于发无限张。
            final boolean isReward = stack.getItem() instanceof com.xioyim.spatialblueprint.item.RewardBlueprintItem;
            // ── 次元蓝图归属检查：任何人（含 OP）都不能用别人的 ────────────
            //
            // 旧实现按物品 NBT 里的 creatorName 判，有两个洞：
            //   · creatorName 为空时视为「是主人」—— 刚捕获还没写名字的蓝图谁捡到都能用
            //   · OP 直接豁免 —— 与「次元蓝图与主人的次元袋一一绑定」的前提冲突
            // 现在统一按 ownerUuid 判，与蓝图库、右键入口的判定完全一致。
            if (isDimensional) {
                String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
                if (!player.getUUID().toString().equals(BlueprintStorage.ownerUuidOf(bpId))) {
                    sendResult(player, false, "spatial_blueprint.message.void_blueprint.not_owner");
                    return;
                }
            }

            // ── 读取蓝图数据 ──────────────────────────────────────────────
            BlueprintData data = isProjection
                    ? ProjectionBlueprintItem.getBlueprintDataServer(stack)
                    : SpatialBlueprintItem.getBlueprintDataServer(stack);
            if (data == null) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(msg.targetPos), "no_blueprint_data");
                sendResult(player, false, "spatial_blueprint.error.no_blueprint_data");
                return;
            }

            // ── 已放置的次元蓝图不可再次放置 ──────────────────────────────
            // 兜底：正常流程下次元蓝图物品在放置那一刻就被替换成 BuildBlueprintItem，
            // 已放置的蓝图回不到玩家手上。这里再拦一道，防止任何未预料的路径把它取出来
            // 重复放置 —— 那等于用一份材料无限刷同一个结构。
            if (data.placed) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(msg.targetPos), "blueprint_already_placed");
                sendResult(player, false, "spatial_blueprint.error.blueprint_already_placed");
                return;
            }

            // ── 存档归属校验：不属于本档的工程 / 次元蓝图一律拒绝放置 ──────
            // 客户端右键时就会拦下来并销毁物品，这里是防改包的底线：
            // 工程蓝图的固定坐标在别的存档里指向的是完全不相干的地方。
            if (com.xioyim.spatialblueprint.network.C2S_DiscardForeignBlueprintMessage.isForeign(data)) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                        BlueprintLogger.pos(msg.targetPos), "foreign_world");
                sendResult(player, false, "spatial_blueprint.error.foreign_world");
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

            // ── 子工程：坐标由服务端说了算，不采信客户端发来的位置 ──────────
            //
            // 领取出来的子工程蓝图，绝对坐标是领取时按「激活原点 + relP」算好、写死在
            // 物品 NBT 上的。这里不覆盖的话，改包的客户端可以把 targetPos 挪到别处，
            // 只要还在 checkProjectBounds 的距离范围内（默认 64 格）就能通过 ——
            // 结构会建在错的位置，进度却照样记成完成。
            //
            // 只对<b>子工程</b>生效（认 sbTaskSetId）：独立工程蓝图是 OP 自己创作自己用的，
            // 保持原有行为不动。
            if (isEngineering) {
                String subTaskSet = EngineeringBlueprintItem.getTaskSetId(stack);
                if (subTaskSet != null && !subTaskSet.isEmpty()
                        && EngineeringBlueprintItem.hasBoundProject(stack)) {
                    actualPos = EngineeringBlueprintItem.getBoundStartPos(stack);
                }
            }

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

            String blueprintIdForBag = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";

            // 领地插件的 EntityPlaceEvent / BreakEvent 检查必须先于任何材料、物品和文件状态修改。
            // 否则服务端二次预检拒绝时，结构没放出来但材料与蓝图已经被消耗。
            if (!BlueprintPlacementHandler.precheckPlacement(
                    player.serverLevel(), actualPos, data, msg.rotation, player,
                    msg.clearFirst, msg.fluidMode)) {
                return;
            }

            // ── 生存模式：放置前先替换物品，防止动画期间重复触发 ──────────
            // 次元蓝图的「标记已放置」也只在这里做：创造模式不替换手中物品、也不消耗
            // 次元袋，若在外面无条件打标记，手上会留下一个已被标记为已放置的蓝图
            // （再次放置会被拒），袋子还成为占着材料空间的孤儿。
            // 创造模式本就不消耗材料、蓝图可重复使用，因此标记与袋子一并不动。
            if (!player.isCreative()) {
                com.xioyim.spatialblueprint.util.MaterialSpace space =
                        com.xioyim.spatialblueprint.util.MaterialSpace.get(player.serverLevel());
                if (isDimensional) {
                    // ── 材料校验：必须持有该蓝图的次元袋 ──────────────────
                    // 袋子本身就是需求清单（捕获时存多少、放置时扣多少），因此不受
                    // blockSubstitutions / bulkMaterialConversions 配置变更导致的清单重算影响。
                    // 仅 FORCE 捕获（OP 特权：挖走不给料、放置也不要料）豁免。
                    boolean consumed = space.bagConsume(player.getUUID(), blueprintIdForBag);
                    if (!consumed && !data.opBypassCapture) {
                        BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                                BlueprintLogger.pos(actualPos), "insufficient_materials");
                        sendResult(player, false, "spatial_blueprint.error.insufficient_materials");
                        return;
                    }
                    // 放置即消耗，但**不再删除 dat 文件** —— 改为打上「已放置」标记
                    // 并记录放置点。文件保留下来，玩家手上的空置次元蓝图可通过「重新储存」
                    // 覆盖写回本文件并清除标记；在此之前该蓝图在列表中显示「(已放置)」且禁止取出。
                    markPlacedAndSync(player, blueprintIdForBag);
                    S2C_MaterialSpaceSyncMessage.sendFor(player, null);
                    BlockPos placedMin = actualPos;
                    BlockPos placedMax = (msg.rotation == Rotation.CLOCKWISE_90 || msg.rotation == Rotation.COUNTERCLOCKWISE_90)
                            ? actualPos.offset(data.size.getZ() - 1, data.size.getY() - 1, data.size.getX() - 1)
                            : actualPos.offset(data.size.getX() - 1, data.size.getY() - 1, data.size.getZ() - 1);
                    ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                    CompoundTag buildNbt = new CompoundTag();
                    buildNbt.putString("blueprintName", data.name);
                    buildNbt.putInt("saveMode", data.saveMode.ordinal());
                    buildNbt.putLong("completionTime",
                            player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDuration));
                    buildNbt.putString("sourceType", "void_dimensional");
                    // 空置次元蓝图必须携带 blueprintId，否则「重新储存」找不到要覆盖写回的文件
                    if (!blueprintIdForBag.isEmpty()) buildNbt.putString("blueprintId", blueprintIdForBag);
                    buildNbt.put("captureMin", NbtUtils.writeBlockPos(placedMin));
                    buildNbt.put("captureMax", NbtUtils.writeBlockPos(placedMax));
                    buildNbt.putDouble("tpX", player.getX());
                    buildNbt.putDouble("tpY", player.getY());
                    buildNbt.putDouble("tpZ", player.getZ());
                    buildNbt.putString("tpDimension", player.serverLevel().dimension().location().toString());
                    // 存档身份：空置次元蓝图的坐标只在本档有意义，带到别的档里必须能认出来
                    buildNbt.putString("worldUuid", com.xioyim.spatialblueprint.util.WorldIdentity
                            .get(player.serverLevel()).uuid().toString());
                    buildNbt.putString("worldName", com.xioyim.spatialblueprint.util.WorldIdentity.currentName());
                    if (stack.hasTag() && stack.getTag().contains("creatorName"))
                        buildNbt.putString("creatorName", stack.getTag().getString("creatorName"));
                    buildStack.setTag(buildNbt);
                    player.setItemInHand(hand, buildStack);
                    ItemSyncUtil.syncHand(player, hand);
                } else if (isProjection) {
                    // 投影 / 工程蓝图：从材料空间普通池原子扣除整份清单。
                    // FORCE 捕获的蓝图（opBypassCapture）通常跳过校验与扣除，
                    // 但奖励蓝图若 OP 事后设定了耗材，则仍需消耗（不跳过）。
                    com.xioyim.spatialblueprint.util.MaterialList ml =
                            ProjectionBlueprintItem.getMaterialListServer(stack);
                    boolean hasMaterialCost = ml != null && !ml.blockCounts.isEmpty();
                    boolean skipConsume = data.opBypassCapture && !(isReward && hasMaterialCost);
                    if (!skipConsume && !space.consume(player.getUUID(), ml)) {
                        BlueprintLogger.failed(player, BlueprintLogger.Action.PLACE,
                                BlueprintLogger.pos(actualPos), "insufficient_materials");
                        sendResult(player, false, "spatial_blueprint.error.insufficient_materials");
                        return;
                    }
                    S2C_MaterialSpaceSyncMessage.sendFor(player, ml);
                    if (isEngineering || isReward) {
                        // 工程蓝图 / 奖励蓝图：参考空间蓝图，立即移除物品（用后销毁）
                        recordSubProjectCompletion(player, stack);
                        player.setItemInHand(hand, ItemStack.EMPTY);
                        ItemSyncUtil.syncHand(player, hand);
                    } else {
                        // 投影蓝图：转为 BuildBlueprintItem 等待动画完成
                        ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                        CompoundTag buildNbt = new CompoundTag();
                        buildNbt.putString("blueprintName", data.name);
                        buildNbt.putInt("saveMode", data.saveMode.ordinal());
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
                // 创造模式次元蓝图：与生存模式走同一套流程 —— 打「已放置」标记、
                // 转为 BuildBlueprintItem，动画完成后恢复为空置次元蓝图。
                // 唯一差别是不消耗次元袋（创造模式本就不消耗材料，袋子留着供重新储存后复用）。
                //
                // 结构已经还原回世界，蓝图内容就是空的，因此标记必须打上：
                // 不打的话列表里那条仍显示「未放置」且可反复取出，等于无限复制结构。
                markPlacedAndSync(player, blueprintIdForBag);

                BlockPos placedMin = actualPos;
                BlockPos placedMax = (msg.rotation == Rotation.CLOCKWISE_90 || msg.rotation == Rotation.COUNTERCLOCKWISE_90)
                        ? actualPos.offset(data.size.getZ() - 1, data.size.getY() - 1, data.size.getX() - 1)
                        : actualPos.offset(data.size.getX() - 1, data.size.getY() - 1, data.size.getZ() - 1);
                ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                CompoundTag buildNbt = new CompoundTag();
                buildNbt.putString("blueprintName", data.name);
                buildNbt.putInt("saveMode", data.saveMode.ordinal());
                buildNbt.putLong("completionTime",
                        player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDuration));
                buildNbt.putString("sourceType", "void_dimensional");
                // 空置次元蓝图必须携带 blueprintId，否则「重新储存」找不到要覆盖写回的文件，
                // 右键时还会被判定为失效直接换成空白蓝图。
                if (!blueprintIdForBag.isEmpty()) buildNbt.putString("blueprintId", blueprintIdForBag);
                buildNbt.put("captureMin", NbtUtils.writeBlockPos(placedMin));
                buildNbt.put("captureMax", NbtUtils.writeBlockPos(placedMax));
                buildNbt.putDouble("tpX", player.getX());
                buildNbt.putDouble("tpY", player.getY());
                buildNbt.putDouble("tpZ", player.getZ());
                buildNbt.putString("tpDimension", player.serverLevel().dimension().location().toString());
                // 存档身份：空置次元蓝图的坐标只在本档有意义，带到别的档里必须能认出来
                buildNbt.putString("worldUuid", com.xioyim.spatialblueprint.util.WorldIdentity
                        .get(player.serverLevel()).uuid().toString());
                buildNbt.putString("worldName", com.xioyim.spatialblueprint.util.WorldIdentity.currentName());
                if (stack.hasTag() && stack.getTag().contains("creatorName"))
                    buildNbt.putString("creatorName", stack.getTag().getString("creatorName"));
                buildStack.setTag(buildNbt);
                player.setItemInHand(hand, buildStack);
                ItemSyncUtil.syncHand(player, hand);
            } else if (isProjection && (isReward || isEngineering)) {
                // 奖励 / 工程蓝图：用后销毁，不还给玩家。
                // 工程蓝图创造模式原来会转成 BuildBlueprintItem、动画结束后变回一枚**空的**
                // 工程蓝图；现在与生存模式统一为「无论哪个模式，用完都直接消失」——
                // 子工程的完成判定就挂在这一步之前，物品清空后再想读 taskSetId 已经晚了。
                recordSubProjectCompletion(player, stack);
                player.setItemInHand(hand, ItemStack.EMPTY);
                ItemSyncUtil.syncHand(player, hand);
            } else if (isProjection) {
                // 创造模式投影蓝图：转为 BuildBlueprintItem（防止动画期间重复放置）
                ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
                CompoundTag buildNbt = new CompoundTag();
                buildNbt.putString("blueprintName", data.name);
                buildNbt.putInt("saveMode", data.saveMode.ordinal());
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

            // ── 磁盘日志（只记成功）──────────────────────────────────────
            // 位置很关键：必须在领地插件预检（precheckPlacement）与材料扣除之后。
            // 放在前面的话，被领地拦下或材料不足而中止的放置也会被记成一次成功。
            // FORCE 捕获的蓝图内容从未净化，走管理员通道，不混进常规放置日志。
            {
                BlockPos logSize = data.size;
                boolean logSwap = (msg.rotation == Rotation.CLOCKWISE_90
                        || msg.rotation == Rotation.COUNTERCLOCKWISE_90);
                BlockPos logEnd = actualPos.offset(
                        (logSwap ? logSize.getZ() : logSize.getX()) - 1,
                        logSize.getY() - 1,
                        (logSwap ? logSize.getX() : logSize.getZ()) - 1);
                int logBlocks = data.normalBlocks.size() + data.dependentBlocks.size()
                        + data.lateBlocks.size();
                String logDim = player.serverLevel().dimension().location().toString();
                String logCat = data.category == null ? "" : data.category;

                if (data.saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE
                        || data.opBypassCapture) {
                    com.xioyim.spatialblueprint.log.BlueprintFileLogger.forcePlace(
                            player, blueprintIdForBag, data.name, logCat, logDim,
                            actualPos, logEnd, logBlocks);
                } else {
                    com.xioyim.spatialblueprint.log.BlueprintFileLogger.placement(
                            player, blueprintIdForBag, data.name, logCat,
                            data.saveMode == null ? "SAFE" : data.saveMode.name(),
                            logDim, actualPos, logEnd, logBlocks);
                }
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

    /**
     * 子工程放置成功 → 记账。
     *
     * <p>必须在物品被清空之前读 NBT —— 清空之后 taskSetId / entryIndex 就没了。</p>
     */
    private static void recordSubProjectCompletion(ServerPlayer player, ItemStack stack) {
        String taskSetId = EngineeringBlueprintItem.getTaskSetId(stack);
        if (taskSetId == null || taskSetId.isEmpty()) return;   // 不是子工程，直接返回
        int entryIndex = EngineeringBlueprintItem.getEntryIndex(stack);
        if (entryIndex <= 0) return;

        ProjectProgressData progress = ProjectProgressData.get(player.serverLevel());
        if (progress.isCompleted(taskSetId, entryIndex)) return;   // 重复放置不重复触发指令
        progress.markCompleted(taskSetId, entryIndex);
        runCompletionCommand(player, taskSetId, entryIndex);
    }

    /** 项目配置的子工程完成指令：以服务器身份、权限等级 2 执行。 */
    private static void runCompletionCommand(ServerPlayer player, String taskSetId, int entryIndex) {
        ProjectClaimRegistry.Claim claim = ProjectClaimRegistry.get(player.serverLevel())
                .findByTaskSet(taskSetId);
        if (claim == null || claim.projectId == null || claim.projectId.isEmpty()) return;

        ProjectData project;
        try {
            project = ProjectStorage.loadProject(UUID.fromString(claim.projectId));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (project == null || project.onSubProjectComplete == null
                || project.onSubProjectComplete.isBlank()) return;

        String command = project.onSubProjectComplete
                .replace("{player}", player.getGameProfile().getName())
                .replace("{project_id}", claim.projectId)
                .replace("{entry_index}", String.valueOf(entryIndex));
        MinecraftServer server = player.getServer();
        if (server == null) return;

        try {
            CommandSourceStack source = server.createCommandSourceStack()
                    .withPermission(2)
                    .withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, command);
            SpatialBlueprintMod.LOGGER.info(
                    "[Project] Completion command executed taskSet='{}' project='{}' entry={} player='{}' command='{}'",
                    taskSetId, claim.projectId, entryIndex,
                    player.getGameProfile().getName(), command);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn(
                    "[Project] Completion command failed taskSet='{}' project='{}' entry={}: {}",
                    taskSetId, claim.projectId, entryIndex, e.getMessage());
        }
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey)
        );
        if (!success) {
            sendPlacementCancelled(player);
        }
    }

    private static void sendPlacementCancelled(ServerPlayer player) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage(-1f));
    }

    /**
     * 给次元蓝图打上「已放置」标记并把最新列表推给客户端。
     *
     * <p>生存与创造两条放置路径共用 —— 结构已经还原回世界，蓝图内容就是空的，
     * 两种模式下都必须打标记。不打的话列表里那条仍显示「未放置」且 ✔ 取出按钮不置灰，
     * 玩家能取出同一份结构反复放置。</p>
     *
     * <p>推送列表这一步同样不能省：客户端 {@code ClientLibraryCache} 不会自己知道文件变了。</p>
     */
    private static void markPlacedAndSync(ServerPlayer player, String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        BlueprintStorage.setPlaced(blueprintId,
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()),
                player.serverLevel().dimension().location().toString());
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                // H2：过滤后再推，避免放置一次就把全量列表泄一次。
                new S2C_LibrarySyncMessage(C2S_RequestLibraryMessage.buildFilteredSummaries(player)));
        // 同时刷新客户端的 BlueprintData 缓存：玩家可能还持有其他指向同一文件的次元蓝图，
        // 右键时要靠这份数据的 placed 标记在门口拦住（见 SpatialBlueprintItem.openPlacementScreen）。
        // 只推列表是不够的 —— 那只更新 ClientLibraryCache，不更新 ClientBlueprintCache。
        BlueprintData fresh = BlueprintStorage.load(blueprintId);
        if (fresh != null) {
            BlueprintNetwork.sendBlueprintTo(player, blueprintId, fresh);
        }
    }
}
