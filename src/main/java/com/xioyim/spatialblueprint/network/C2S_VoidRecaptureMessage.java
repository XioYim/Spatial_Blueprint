package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Collections;
import java.util.Set;

/** 空置次元蓝图 → [储存] 按钮：在原坐标重新捕捉并创建次元蓝图。 */
public class C2S_VoidRecaptureMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_VoidRecaptureMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_void_recapture_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_VoidRecaptureMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_VoidRecaptureMessage::encode, C2S_VoidRecaptureMessage::decode);

    @Override
    public CustomPacketPayload.Type<C2S_VoidRecaptureMessage> type() {
        return TYPE;
    }


    public static void encode(C2S_VoidRecaptureMessage msg, FriendlyByteBuf buf) {}

    public static C2S_VoidRecaptureMessage decode(FriendlyByteBuf buf) {
        return new C2S_VoidRecaptureMessage();
    }

    public static void handle(C2S_VoidRecaptureMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_VoidRecaptureMessage msg, ServerPlayer player) {
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

        // 存档归属：不属于本档的空置次元蓝图不得重新储存 ——
        // 那会把本档的方块覆盖写进另一个存档的蓝图文件里。右键入口已经拦过，这里防改包。
        if (!com.xioyim.spatialblueprint.util.WorldIdentity.belongsToCurrentWorld(
                VoidDimensionalBlueprintItem.getWorldUuid(stack))) {
            sendResult(player, false, "spatial_blueprint.error.foreign_world.discarded");
            return;
        }

        // ── 原文件是否还在 ────────────────────────────────────────────
        // 必须排在归属校验前面：文件被归档(.bak)或删除后 ownerUuidOf() 查不到记录、
        // 一律返回空串，先判归属就会把「文件没了」误报成「这不是你的次元蓝图」。
        //
        // 不新建一份顶替：那会凭空造出一个已被管理员清理掉的蓝图，绕过归档的意义，
        // 而且新文件的 UUID 与玩家次元袋里记的对不上，材料归属会彻底错乱。
        // 直接把这枚已经没有归宿的空置蓝图换成空白蓝图，物品不白丢。
        String existingId = AbstractBlueprintItem.getBlueprintId(stack);
        if (existingId == null) existingId = "";
        if (existingId.isEmpty() || BlueprintStorage.findFile(existingId) == null) {
            player.setItemInHand(hand, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);
            sendResult(player, false, "spatial_blueprint.error.void_blueprint_orphaned");
            return;
        }

        // 归属校验（服务端兜底，右键入口已拦过一道）：只有主人能覆盖写回自己的蓝图文件
        if (!player.getUUID().toString().equals(BlueprintStorage.ownerUuidOf(existingId))) {
            sendResult(player, false, "spatial_blueprint.message.void_blueprint.not_owner");
            return;
        }

        ServerLevel level = player.serverLevel();

        // 体积校验（统计非空气方块，不含空气）
        // 重新储存的产物仍是次元蓝图，按次元蓝图上限判；OP 豁免
        long effectiveLimit = player.hasPermissions(2)
                ? BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME
                : BlueprintConfig.getMaxVolumeFor("dimensional");
        long nonAir = com.xioyim.spatialblueprint.server.BlueprintValidator.countNonAir(
                level, captureMin, captureMax);
        if (nonAir > effectiveLimit) {
            int sx = Math.abs(captureMin.getX() - captureMax.getX()) + 1;
            int sy = Math.abs(captureMin.getY() - captureMax.getY()) + 1;
            int sz = Math.abs(captureMin.getZ() - captureMax.getZ()) + 1;
            long bbox = (long) sx * sy * sz;
            sendResult(player, false, "spatial_blueprint.error.area_too_large",
                    "spatial_blueprint.detail.area_too_large",
                    new Object[]{nonAir, effectiveLimit, sx, sy, sz, bbox});
            return;
        }

        // 非法方块 / 非法物品预扫 —— 与主捕获路径的次元蓝图口径一致：任何人（含 OP）
        // 任一命中即整单拒收，不入库、不清世界。重新储存期间玩家可能往箱子、展示框、
        // 盔甲架上塞过东西，这些要跟主路径一样过一遍。
        for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
            for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (BlueprintConfig.isExcluded(state.getBlock())) {
                        sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                        return;
                    }
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null && com.xioyim.spatialblueprint.util.BlueprintSanitizer
                            .hasDangerousBlockNbt(be.saveWithFullMetadata(level.registryAccess()))) {
                        sendResult(player, false, "spatial_blueprint.error.dimensional_illegal_items");
                        return;
                    }
                }
            }
        }
        {
            AABB preBox = new AABB(
                    captureMin.getX(), captureMin.getY(), captureMin.getZ(),
                    captureMax.getX() + 1.0, captureMax.getY() + 1.0, captureMax.getZ() + 1.0);
            if (com.xioyim.spatialblueprint.util.BlueprintSanitizer
                    .findEntityWithIllegalItems(level, preBox) != null) {
                sendResult(player, false, "spatial_blueprint.error.dimensional_illegal_items");
                return;
            }
        }

        // 验证区域内每个方块的破坏权限
        // 兼容 FTB Chunks / 其他领地保护 mod（通过 BlockEvent.BreakEvent 取消机制）
        //
        // 创造模式是否也受检，跟放置侧 / 主捕获路径同一个开关
        // （denyCreativePlacementInProtectedArea，默认 true）。
        if (!player.isCreative()
                || BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA.get()) {
            for (int x = captureMin.getX(); x <= captureMax.getX(); x++) {
                for (int y = captureMin.getY(); y <= captureMax.getY(); y++) {
                    for (int z = captureMin.getZ(); z <= captureMax.getZ(); z++) {
                        BlockPos checkPos = new BlockPos(x, y, z);
                        BlockState bs = level.getBlockState(checkPos);
                        if (bs.isAir()) continue;
                        BlockEvent.BreakEvent evt =
                                new BlockEvent.BreakEvent(level, checkPos, bs, player);
                        NeoForge.EVENT_BUS.post(evt);
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
                    CompoundTag nbt = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
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
        //
        // 实体身上的物品：上面的预扫已经保证选区里没有非法物品（任何人一样），
        // 这里的整体倒空只是兜底，正常永远不会触发。不再按 OP 豁免 ——
        // 次元蓝图对所有人一个口径。
        // 生物 / 拴绳结是否捕获：与主路径 SAFE 分支一致按创造模式判定
        // （生存本来就不捕生物，重新储存不必补）。捕获与清除必须用同一个门槛。
        final boolean captureMobs = player.isCreative();
        final boolean stripEntityBad = true;
        AABB captureBox = new AABB(
                captureMin.getX(), captureMin.getY(), captureMin.getZ(),
                captureMax.getX() + 1.0, captureMax.getY() + 1.0, captureMax.getZ() + 1.0);
        level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                .forEach(entity -> captureHangingEntity(entity, captureMin, data, stripEntityBad));
        level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                .forEach(entity -> captureHangingEntity(entity, captureMin, data, stripEntityBad));
        // 盔甲架：与主捕获路径保持一致 —— 次元蓝图会清空整片区域，
        // 若只清方块不带走盔甲架，装备会留在原地，等于凭空多出一份
        level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, captureBox, e -> true)
                .forEach(entity -> {
                    CompoundTag nbt = new CompoundTag();
                    if (!entity.save(nbt)) return;
                    nbt.remove("UUID");
                    if (stripEntityBad) {
                        com.xioyim.spatialblueprint.util.BlueprintSanitizer
                                .sanitizeEntityItemsInPlace(nbt);
                    }
                    CaptureAreaMessage.addEntityEntry(nbt, entity, captureMin, data);
                });
        // 白名单生物：与主捕获路径 SAFE 分支同口径，仅创造模式捕获。
        // 门槛 captureMobs 与下面的清除阶段一一对应 —— 捕了就必须删、没捕就不删，
        // 否则要么复制（捕了不删）要么凭空消失（删了没捕）。
        if (captureMobs) {
            for (net.minecraft.world.entity.LivingEntity le :
                    level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            captureBox, e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
                String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                if (com.xioyim.spatialblueprint.util.BlueprintSanitizer.ALLOWED_MOB_ENTITIES.contains(mobId)) {
                    CompoundTag nbt = new CompoundTag();
                    if (!le.save(nbt)) continue;
                    nbt.remove("UUID");
                    // 上面的预扫已保证无非法物品，这里只是与主路径同款的兜底
                    com.xioyim.spatialblueprint.util.BlueprintSanitizer.sanitizeMobInventoryInPlace(nbt);
                    CaptureAreaMessage.addEntityEntry(nbt, le, captureMin, data);
                }
            }
            // 拴绳结（LeashFenceKnotEntity 非 LivingEntity，需单独捕获）
            level.getEntitiesOfClass(net.minecraft.world.entity.decoration.LeashFenceKnotEntity.class,
                            captureBox, e -> true)
                    .forEach(entity -> {
                        CompoundTag nbt = new CompoundTag();
                        if (!entity.save(nbt)) return;
                        nbt.remove("UUID");
                        CaptureAreaMessage.addEntityEntry(nbt, entity, captureMin, data);
                    });
        }
        // 盒型实体（Create 超级胶水等）：位置由 From/To 角点描述。
        // 主路径同样不按 captureMobs 门禁，这里保持一致。
        CaptureAreaMessage.captureBoxEntities(level, captureBox, captureMin, data);

        int voidTotalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
        data.animDuration = BlueprintConfig.getAutoDuration(voidTotalBlocks);
        data.materialList = MaterialList.fromBlueprintData(data);
        data.category     = "dimensional";
        // 保留原始创建者；若空置蓝图未记录则退回当前玩家
        String originalCreator = VoidDimensionalBlueprintItem.getCreatorName(stack);
        data.creatorName  = originalCreator.isEmpty() ? player.getScoreboardName() : originalCreator;
        data.sourceName   = player.getScoreboardName();
        // 重新储存是主人把自己放出去的结构收回来，归属不变（空置蓝图的归属校验在右键入口）
        data.ownerUuid    = player.getUUID().toString();
        com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, player.serverLevel());
        data.createdAt    = System.currentTimeMillis();
        data.saveMode     = SpatialBlueprintItem.getSaveMode(stack);
        data.opBypassCapture = data.saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE;

        // ── 覆盖写回原蓝图文件 ────────────────────────────────────────
        // 空置次元蓝图携带原文件的 blueprintId：重新储存是「把之前放置出去的结构收回来」，
        // 应当写回同一个文件并清除「已放置」标记，而不是新建一份 —— 后者会让列表里
        // 堆积同名蓝图，原文件则永远停在已放置状态无人回收。
        // 标记通过 data.placed = false 落盘（serialize 时 placed 为 false 就不写这些键）。
        data.placed          = false;
        data.placedX         = 0;
        data.placedY         = 0;
        data.placedZ         = 0;
        data.placedDimension = "";

        UUID blueprintId = UUID.fromString(existingId);
        BlueprintStorage.save(blueprintId, data);
        BlueprintStorage.invalidateSummaryCache();
        // 必须把刷新后的数据推给客户端：重新储存复用了同一个 blueprintId，客户端缓存里
        // 可能还留着放置时推送的旧数据（placed=true）。不推的话，第一次右键新蓝图会被
        // SpatialBlueprintItem.openPlacementScreen 误判为「已放置」并弹出「非法蓝图」。
        BlueprintNetwork.sendBlueprintTo(player, blueprintId.toString(), data);

        // 创建次元蓝图物品。
        // 注意：不要调用 setBlueprintData —— 它内部会再 save() 一次并生成第二个 UUID，
        // 导致磁盘上出现两个内容相同、UUID 不同的蓝图文件。上面已经存过，
        // blueprintId 也在下面显式写入，这里只需建物品本身。
        ItemStack newStack = new ItemStack(SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get());
        SpatialBlueprintItem.setBlueprintName(newStack, blueprintName);
        SpatialBlueprintItem.setAnimDuration(newStack, data.animDuration);
        SpatialBlueprintItem.setSaveMode(newStack, data.saveMode);
            AbstractBlueprintItem.setBlueprintId(newStack, blueprintId.toString());
            VoidDimensionalBlueprintItem.setCaptureRange(newStack, captureMin, captureMax);
            VoidDimensionalBlueprintItem.setCreatorName(newStack, data.creatorName);
        // 方块已从世界清除，材料存入该玩家材料空间的「次元袋」，与蓝图 ID 绑定。
        // 袋子只能被同 ID 的次元蓝图放置时消耗，玩家无法取出，不受普通池上限约束。
        // 只有 FORCE 捕获（OP 特权，挖走不给料、放置也不要料）不入袋。
        // 创造模式同样入袋 —— 否则切到生存后放置校验会因无袋而失败，蓝图作废。
        if (!data.opBypassCapture
                && data.materialList != null && !data.materialList.blockCounts.isEmpty()) {
            com.xioyim.spatialblueprint.util.MaterialSpace.get(player.serverLevel())
                    .bagDepositAll(player.getUUID(), blueprintId.toString(), data.materialList,
                            data.name);
        }

        player.setItemInHand(hand, newStack);
        com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);

        // 清除原区域方块（次元蓝图行为）
        int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
                | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_MOVE_BY_PISTON;
        BlockState air = Blocks.AIR.defaultBlockState();

        // 移除已捕获的实体。必须与上面的捕获范围一一对应，
        // 否则实体（及其装备）会被复制：蓝图存一份、世界留一份。
        level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, captureBox, e -> true)
                .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        // 白名单生物与拴绳结：仅在捕获阶段真的捕了它们时才移除（与捕获同一个 captureMobs）
        if (captureMobs) {
            for (net.minecraft.world.entity.LivingEntity le :
                    level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            captureBox, e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
                String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                if (com.xioyim.spatialblueprint.util.BlueprintSanitizer.ALLOWED_MOB_ENTITIES.contains(mobId)) {
                    le.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                }
            }
            level.getEntitiesOfClass(net.minecraft.world.entity.decoration.LeashFenceKnotEntity.class,
                            captureBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        }
        // 盒型实体（胶水）：整盒在内 / 跨界的都删，与主捕获路径一致
        CaptureAreaMessage.removeBoxEntities(level, captureBox);

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
    }

    private static void captureHangingEntity(net.minecraft.world.entity.Entity entity,
                                              BlockPos min, BlueprintData data,
                                              boolean stripEntityBad) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.save(nbt)) return;
        nbt.remove("UUID");
        // 展示框里的物品：只在非法时整体倒（画没有物品，走这里是空操作）
        if (stripEntityBad) {
            com.xioyim.spatialblueprint.util.BlueprintSanitizer.sanitizeEntityItemsInPlace(nbt);
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        int relTileX = nbt.getInt("TileX") - min.getX();
        int relTileY = nbt.getInt("TileY") - min.getY();
        int relTileZ = nbt.getInt("TileZ") - min.getZ();
        byte facing = type.equals("minecraft:painting") ? nbt.getByte("facing") : nbt.getByte("Facing");
        data.entities.add(new BlueprintData.EntityEntry(relTileX, relTileY, relTileZ, facing, type, nbt));
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        sendResult(player, success, messageKey, "", null);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey, String detailKey, Object[] detailArgs) {
        BlueprintNetwork.sendToPlayer(player,
                new S2C_OperationResult(success, messageKey, detailKey, detailArgs));
    }
}
