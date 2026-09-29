package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BulkMaterialConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求选区内的非法方块列表（供检测报告展示）。
 * 服务端扫描后以 {@link S2C_IllegalBlocksListMessage} 返回。
 */
public class C2S_RequestIllegalBlocksMessage {

    /** 单次请求允许扫描的最大体积，防止恶意超大范围卡服。 */
    private static final long MAX_SCAN_VOLUME = 4_000_000L;
    /** 领地采样最多 post 多少次 BreakEvent（= 区块数上限）；超出的区块不采样，只是预览少几个红框。 */
    private static final int MAX_CLAIM_SAMPLES = 4096;

    private final BlockPos min;
    private final BlockPos max;

    public C2S_RequestIllegalBlocksMessage(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
    }

    public static void encode(C2S_RequestIllegalBlocksMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.min);
        buf.writeBlockPos(msg.max);
    }

    public static C2S_RequestIllegalBlocksMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestIllegalBlocksMessage(buf.readBlockPos(), buf.readBlockPos());
    }

    public static void handle(C2S_RequestIllegalBlocksMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();

            int minX = Math.min(msg.min.getX(), msg.max.getX());
            int maxX = Math.max(msg.min.getX(), msg.max.getX());
            int minY = Math.min(msg.min.getY(), msg.max.getY());
            int maxY = Math.max(msg.min.getY(), msg.max.getY());
            int minZ = Math.min(msg.min.getZ(), msg.max.getZ());
            int maxZ = Math.max(msg.min.getZ(), msg.max.getZ());

            // 越界 / 超范围防护
            if (minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight()) return;
            long vol = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (vol > MAX_SCAN_VOLUME) return;

            List<S2C_IllegalBlocksListMessage.IllegalBlock> found = new ArrayList<>();
            List<S2C_IllegalBlocksListMessage.IllegalBlock> dangerous = new ArrayList<>();
            List<S2C_IllegalBlocksListMessage.SpecialEntity> special = new ArrayList<>();
            net.minecraft.world.phys.AABB scanBox = new net.minecraft.world.phys.AABB(
                    minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
            // 选区内每种方块的出现次数，用于判定批量转换规则是否达到阈值
            java.util.Map<String, Integer> blockCounts = new java.util.HashMap<>();
            // 领地采样：每个区块取第一格非空气方块 post 一次 BreakEvent，被取消 = 该区块无破坏权限。
            // 口径与次元捕获的逐格 BreakEvent 一致（含创造模式开关），只是从逐格降到逐区块 ——
            // FTB Chunks 按区块划地，一格能代表整个区块；预览只是提示，真正拦截仍在捕获侧逐格判。
            boolean sampleClaims = !player.isCreative()
                    || com.xioyim.spatialblueprint.BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA.get();
            java.util.Set<Long> sampledChunks = new java.util.HashSet<>();
            List<S2C_IllegalBlocksListMessage.ProtectedChunk> protectedChunks = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        pos.set(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (!state.isAir()) {
                            blockCounts.merge(
                                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
                            if (sampleClaims && sampledChunks.size() < MAX_CLAIM_SAMPLES
                                    && sampledChunks.add(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4))) {
                                net.minecraftforge.event.level.BlockEvent.BreakEvent evt =
                                        new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                                                level, pos.immutable(), state, player);
                                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(evt);
                                if (evt.isCanceled()) {
                                    protectedChunks.add(new S2C_IllegalBlocksListMessage.ProtectedChunk(x >> 4, z >> 4));
                                }
                            }
                        }
                        if (BlueprintSanitizer.isIllegalBlock(state)) {
                            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            found.add(new S2C_IllegalBlocksListMessage.IllegalBlock(x, y, z, id, ""));
                        }
                        // 危险 NBT 方块：容器物品 / 告示牌命令 / 刷怪笼生成数据
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) {
                            CompoundTag nbt = be.saveWithFullMetadata();
                            if (BlueprintSanitizer.hasDangerousBlockNbt(nbt)) {
                                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                                dangerous.add(new S2C_IllegalBlocksListMessage.IllegalBlock(x, y, z, id, ""));
                            }
                        }
                    }
                }
            }
            // ── 特殊实体扫描 ──────────────────────────────────────────────
            // 盔甲架：身上任意层级含非法物品 → 危险列表；有装备但合法 → 特殊实体；无装备 → 跳过
            // 判定走 entityHasIllegalItems（整份 NBT 递归），与次元蓝图捕获预扫同口径。
            // 改前是 findDangerousEquipmentItem：只看槽位物品 id、还只查方块表 ——
            // 手里拿刷怪蛋报「注意」不报「危险」，拿装满刷怪蛋的潜影盒更是看不见。
            for (net.minecraft.world.entity.decoration.ArmorStand stand :
                    level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                            scanBox, e -> true)) {
                boolean hasEq = armorStandHasEquipment(stand);
                net.minecraft.core.BlockPos bp = stand.blockPosition();
                if (BlueprintSanitizer.entityHasIllegalItems(stand)) {
                    dangerous.add(new S2C_IllegalBlocksListMessage.IllegalBlock(
                            bp.getX(), bp.getY(), bp.getZ(), "minecraft:armor_stand",
                            findDangerousEquipmentItem(stand)));
                } else if (hasEq) {
                    special.add(new S2C_IllegalBlocksListMessage.SpecialEntity(
                            bp.getX(), bp.getY(), bp.getZ(), "armor_stand", "minecraft:armor_stand", "", true));
                }
            }
            // 白名单生物：驮兽背包 / 装备含非法物品 → 危险列表；否则 → 特殊实体
            // 改前生物一律只标「特殊」，驴背上箱子里有什么从来没查过。
            for (net.minecraft.world.entity.LivingEntity le :
                    level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                            scanBox, e -> e instanceof net.minecraft.world.entity.LivingEntity
                                    && !(e instanceof net.minecraft.world.entity.player.Player))) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                if (BlueprintSanitizer.ALLOWED_MOB_ENTITIES.contains(id)) {
                    net.minecraft.core.BlockPos bp = le.blockPosition();
                    if (BlueprintSanitizer.entityHasIllegalItems(le)) {
                        dangerous.add(new S2C_IllegalBlocksListMessage.IllegalBlock(
                                bp.getX(), bp.getY(), bp.getZ(), id, ""));
                    } else {
                        special.add(new S2C_IllegalBlocksListMessage.SpecialEntity(
                                bp.getX(), bp.getY(), bp.getZ(), "mob", id, "", false));
                    }
                }
            }
            // 物品展示框：内嵌非法物品 → 危险列表；有物品但非非法 → 特殊实体；无物品 → 跳过
            for (net.minecraft.world.entity.decoration.ItemFrame frame :
                    level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                            scanBox, e -> true)) {
                net.minecraft.nbt.CompoundTag nbt = new net.minecraft.nbt.CompoundTag();
                frame.save(nbt);
                String type = (frame instanceof net.minecraft.world.entity.decoration.GlowItemFrame)
                        ? "minecraft:glow_item_frame" : "minecraft:item_frame";
                String itemId = nbt.getCompound("Item").getString("id");
                net.minecraft.core.BlockPos bp = frame.blockPosition();
                if (BlueprintSanitizer.isItemFrameItemDangerous(nbt)) {
                    dangerous.add(new S2C_IllegalBlocksListMessage.IllegalBlock(
                            bp.getX(), bp.getY(), bp.getZ(), type, itemId));
                } else if (!itemId.isBlank()) {
                    special.add(new S2C_IllegalBlocksListMessage.SpecialEntity(
                            bp.getX(), bp.getY(), bp.getZ(), "item_frame", type, itemId, false));
                }
            }

            List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions = new ArrayList<>();
            List<String> freeBlocks = new ArrayList<>();
            BulkMaterialConverter.collectConversions(blockCounts, conversions, freeBlocks);

            // 盒型实体（Create 超级胶水）：只报包围盒与「是否整盒在内」。inside 的六个比较
            // 与 CaptureAreaMessage.captureBoxEntities 完全一致（等边算在内），预览才不会与捕获打架。
            List<S2C_IllegalBlocksListMessage.BoxEntity> boxEntities = new ArrayList<>();
            java.util.Set<String> boxIds = com.xioyim.spatialblueprint.BlueprintConfig.getBoxEntities();
            if (!boxIds.isEmpty()) {
                for (net.minecraft.world.entity.Entity entity :
                        level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, scanBox, e -> true)) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                    if (!boxIds.contains(type)) continue;
                    net.minecraft.world.phys.AABB bb = entity.getBoundingBox();
                    boolean inside = !(bb.minX < scanBox.minX || bb.maxX > scanBox.maxX
                            || bb.minY < scanBox.minY || bb.maxY > scanBox.maxY
                            || bb.minZ < scanBox.minZ || bb.maxZ > scanBox.maxZ);
                    boxEntities.add(new S2C_IllegalBlocksListMessage.BoxEntity(
                            bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, inside));
                }
            }

            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_IllegalBlocksListMessage(found, dangerous, special, conversions, freeBlocks, boxEntities,
                            protectedChunks,
                            // 非空气方块总数（子工程新页的自动耗时预填 / 报告参考用）；blockCounts 只统计非空气
                            blockCounts.values().stream().mapToInt(Integer::intValue).sum()));
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 盔甲架装备槽里第一个非法物品的 ID，仅用于报告里给玩家显示「是哪个」。
     * 判定本身不靠它 —— 那是 {@code entityHasIllegalItems} 的事（递归整份 NBT）。
     * 非法物品藏在手持潜影盒里时槽位层看不到，返回空串，报告只标位置不标物品名。
     */
    private static String findDangerousEquipmentItem(net.minecraft.world.entity.decoration.ArmorStand stand) {
        for (net.minecraft.world.item.ItemStack s : stand.getArmorSlots()) {
            if (!s.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                if (BlueprintSanitizer.isIllegalItemId(id)) return id;
            }
        }
        for (net.minecraft.world.item.ItemStack s : stand.getHandSlots()) {
            if (!s.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                if (BlueprintSanitizer.isIllegalItemId(id)) return id;
            }
        }
        return "";
    }

    /** 盔甲架是否带装备。 */
    private static boolean armorStandHasEquipment(net.minecraft.world.entity.decoration.ArmorStand stand) {
        for (net.minecraft.world.item.ItemStack s : stand.getArmorSlots()) if (!s.isEmpty()) return true;
        for (net.minecraft.world.item.ItemStack s : stand.getHandSlots()) if (!s.isEmpty()) return true;
        return false;
    }
}
