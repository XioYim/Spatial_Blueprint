package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.MaterialList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 空置次元蓝图 → [储存] 按钮：在原坐标重新捕捉并创建次元蓝图。 */
public class C2S_VoidRecaptureMessage {

    public static void encode(C2S_VoidRecaptureMessage msg, FriendlyByteBuf buf) {}

    public static C2S_VoidRecaptureMessage decode(FriendlyByteBuf buf) {
        return new C2S_VoidRecaptureMessage();
    }

    public static void handle(C2S_VoidRecaptureMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 操作冷却检查（防止客户端绕过发包刷新捕捉）
            if (!BlueprintValidator.checkAndUpdateCooldown(player)) return;

            // 检查主手是否持有空置次元蓝图
            ItemStack stack = null;
            InteractionHand hand = null;
            if (player.getMainHandItem().getItem() instanceof VoidDimensionalBlueprintItem) {
                stack = player.getMainHandItem(); hand = InteractionHand.MAIN_HAND;
            } else if (player.getOffhandItem().getItem() instanceof VoidDimensionalBlueprintItem) {
                stack = player.getOffhandItem(); hand = InteractionHand.OFF_HAND;
            }
            if (stack == null || hand == null) return;

            BlockPos captureMin = VoidDimensionalBlueprintItem.getCaptureMin(stack);
            BlockPos captureMax = VoidDimensionalBlueprintItem.getCaptureMax(stack);
            String blueprintName = VoidDimensionalBlueprintItem.getBlueprintName(stack);
            if (captureMin == null || captureMax == null || blueprintName.isEmpty()) {
                sendResult(player, false, "spatial_blueprint.error.no_blueprint_data");
                return;
            }

            ServerLevel level = player.serverLevel();

            // 体积校验（统计非空气方块，不含空气）
            long effectiveLimit;
            if (player.hasPermissions(2)) {
                effectiveLimit = BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME;
            } else {
                effectiveLimit = Math.min(
                        player.isCreative() ? BlueprintConfig.MAX_CAPTURE_VOLUME_CREATIVE.get()
                                            : BlueprintConfig.MAX_CAPTURE_VOLUME_SURVIVAL.get(),
                        BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME);
            }
            long nonAir = com.xioyim.spatialblueprint.server.BlueprintValidator.countNonAir(
                    level, captureMin, captureMax);
            if (nonAir > effectiveLimit) {
                int sx = Math.abs(captureMin.getX() - captureMax.getX()) + 1;
                int sy = Math.abs(captureMin.getY() - captureMax.getY()) + 1;
                int sz = Math.abs(captureMin.getZ() - captureMax.getZ()) + 1;
                long bbox = (long) sx * sy * sz;
                String detail = "方块数: " + nonAir + "，上限: " + effectiveLimit
                        + "\n空间盒: " + sx + "×" + sy + "×" + sz + " = " + bbox + " 格";
                sendResult(player, false, "spatial_blueprint.error.area_too_large", detail);
                return;
            }

            // 非法方块检查
            for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
                for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                    for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                        BlockState state = level.getBlockState(new BlockPos(x, y, z));
                        if (!state.isAir() && BlueprintConfig.isExcluded(state.getBlock())) {
                            sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                            return;
                        }
                    }
                }
            }

            // 生存/冒险玩家：验证区域内每个方块的破坏权限
            // 兼容 FTB Chunks / 其他领地保护 mod（通过 BlockEvent.BreakEvent 取消机制）
            if (!player.isCreative()) {
                for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
                    for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                        for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                            BlockPos checkPos = new BlockPos(x, y, z);
                            BlockState bs = level.getBlockState(checkPos);
                            if (bs.isAir()) continue;
                            BlockEvent.BreakEvent evt =
                                    new BlockEvent.BreakEvent(level, checkPos, bs, player);
                            MinecraftForge.EVENT_BUS.post(evt);
                            if (evt.isCanceled()) {
                                sendResult(player, false, "spatial_blueprint.error.no_break_permission");
                                return;
                            }
                        }
                    }
                }
            }

            // 扫描方块
            BlueprintData data = new BlueprintData();
            data.name = blueprintName;
            data.size = new BlockPos(
                    captureMax.getX() - captureMin.getX() + 1,
                    captureMax.getY() - captureMin.getY() + 1,
                    captureMax.getZ() - captureMin.getZ() + 1);

            Set<String> latePlaceIds = BlueprintConfig.getLatePlaceBlocks();
            for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
                for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                    for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;
                        BlockEntity be = level.getBlockEntity(pos);
                        CompoundTag nbt = be != null ? be.saveWithFullMetadata() : null;
                        BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(pos.subtract(captureMin), state, nbt);
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

            // 捕捉装饰实体
            AABB captureBox = new AABB(
                    captureMin.getX(), captureMin.getY(), captureMin.getZ(),
                    captureMax.getX() + 1.0, captureMax.getY() + 1.0, captureMax.getZ() + 1.0);
            level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                    .forEach(entity -> captureHangingEntity(entity, captureMin, data));
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                    .forEach(entity -> captureHangingEntity(entity, captureMin, data));

            int voidTotalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            data.animDuration = BlueprintConfig.getAutoDuration(voidTotalBlocks);
            data.materialList = MaterialList.fromBlueprintData(data);
            data.category     = "dimensional";
            // 保留原始创建者；若空置蓝图未记录则退回当前玩家
            String originalCreator = VoidDimensionalBlueprintItem.getCreatorName(stack);
            data.creatorName  = originalCreator.isEmpty() ? player.getScoreboardName() : originalCreator;
            data.sourceName   = player.getScoreboardName();
            data.createdAt    = System.currentTimeMillis();

            // 保存到服务器蓝图文件（与其他蓝图类型一致；结构数据也一并保存）
            UUID blueprintId = BlueprintStorage.save(data);
            BlueprintStorage.invalidateSummaryCache();

            // 创建次元蓝图物品
            ItemStack newStack = new ItemStack(SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get());
            SpatialBlueprintItem.setBlueprintData(newStack, data);
            SpatialBlueprintItem.setBlueprintName(newStack, blueprintName);
            SpatialBlueprintItem.setAnimDuration(newStack, data.animDuration);
            newStack.getOrCreateTag().putString("blueprintId", blueprintId.toString());
            newStack.getOrCreateTag().put("captureMin", NbtUtils.writeBlockPos(captureMin));
            newStack.getOrCreateTag().put("captureMax", NbtUtils.writeBlockPos(captureMax));
            newStack.getOrCreateTag().putString("creatorName", data.creatorName);
            // 方块已从世界清除，材料视为已由玩家支付，自动标记为全部存入
            if (data.materialList != null && !data.materialList.blockCounts.isEmpty()) {
                CompoundTag depTag = new CompoundTag();
                for (Map.Entry<String, Integer> matEntry : data.materialList.blockCounts.entrySet()) {
                    depTag.putInt(matEntry.getKey(), matEntry.getValue());
                }
                newStack.getOrCreateTag().put("depositedMaterials", depTag);
            }

            player.setItemInHand(hand, newStack);
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);

            // 清除原区域方块（次元蓝图行为）
            int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_MOVE_BY_PISTON;
            BlockState air = Blocks.AIR.defaultBlockState();

            level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));

            for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
                    for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (!state.isAir() && BlueprintData.isDependent(state)) {
                            level.removeBlockEntity(pos);
                            level.setBlock(pos, air, clearFlags);
                        }
                    }
                }
            }
            for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
                    for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.removeBlockEntity(pos);
                            level.setBlock(pos, air, clearFlags);
                        }
                    }
                }
            }

            sendResult(player, true, "spatial_blueprint.message.void_recapture_completed");
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
        byte facing = type.equals("minecraft:painting") ? nbt.getByte("facing") : nbt.getByte("Facing");
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
