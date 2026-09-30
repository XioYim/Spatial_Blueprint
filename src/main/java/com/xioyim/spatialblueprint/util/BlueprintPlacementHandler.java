package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.SpatialBlueprintRegistry;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import com.xioyim.spatialblueprint.block.RenderBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import javax.annotation.Nullable;
import java.util.*;

@EventBusSubscriber(modid = SpatialBlueprintMod.MODID)
public class BlueprintPlacementHandler {
    private static final List<PendingPlacement> pendingPlacements = new ArrayList<>();

    private static boolean isDbg(@Nullable ServerPlayer player) {
        return player != null && player.isCreative() && player.getTags().contains("DeBug");
    }

    public static class PendingPlacement {
        public final ServerLevel level;
        public final BlockPos origin;
        public final BlockPos size;   // 原始 data.size，旋转/镜像变换依赖完整选区尺寸
        public final List<BlueprintData.BlockEntry> normalBlocks;     // 普通方块（带动画）
        public final List<BlueprintData.BlockEntry> dependentBlocks; // 依赖方块（动画结束后瞬间放置）
        public final List<BlueprintData.BlockEntry> lateBlocks;      // 延迟方块（第④轮，实体后强制放置）
        public final List<BlueprintData.EntityEntry> entities;       // 装饰实体（最后召唤）
        public final Rotation rotation;
        @Nullable public final ServerPlayer player;
        public int currentIndex = 0;
        public final int totalBlocks;
        /** 玩家设定的动画时长（秒）。用于推算每 tick 放置方块数。 */
        public final int durationSeconds;
        /** 放置期间由本放置任务新增的强制加载区块（结束后须解除，原本已强制的区块不记录）。 */
        public final List<ChunkPos> newlyForcedChunks = new ArrayList<>();
        public boolean finished = false;
        public int finishTick = 0;

        public PendingPlacement(ServerLevel level, BlockPos origin, BlockPos size,
                                List<BlueprintData.BlockEntry> normalBlocks,
                                List<BlueprintData.BlockEntry> dependentBlocks,
                                List<BlueprintData.BlockEntry> lateBlocks,
                                List<BlueprintData.EntityEntry> entities,
                                Rotation rotation, @Nullable ServerPlayer player,
                                int durationSeconds) {
            this.level = level;
            this.origin = origin;
            this.size = size;
            this.normalBlocks = normalBlocks;
            this.dependentBlocks = dependentBlocks;
            this.lateBlocks = lateBlocks;
            this.entities = entities;
            this.rotation = rotation;
            this.player = player;
            this.totalBlocks = normalBlocks.size();
            this.durationSeconds = Math.max(1, durationSeconds);
        }
    }

    /**
     * 在扣材料、替换物品和修改蓝图状态之前执行服务端权威预检。
     *
     * @return 可开始放置时返回 {@code true}；被领地保护或结构冲突拦截时已通知客户端并返回
     *         {@code false}
     */
    public static boolean precheckPlacement(ServerLevel level, BlockPos origin, BlueprintData data,
                                            Rotation rotation, @Nullable ServerPlayer player,
                                            boolean clearFirst,
                                            com.xioyim.spatialblueprint.util.FluidDisplayMode fluidMode) {
        // 根据 fluidMode 过滤掉不需要放置的流体方块
        if (fluidMode.affectsBuild()) {
            data = filterFluids(data, fluidMode);
        }

        // 领地保护预检：非创造模式玩家（或开启 denyCreativePlacementInProtectedArea 时的创造模式玩家）
        // 须逐块通过 BlockEvent.EntityPlaceEvent。
        // 只遍历蓝图实际含有的方块（normalBlocks + dependentBlocks + lateBlocks），
        // 而非整个包围盒，将复杂度从 O(size.X × size.Y × size.Z) 降至 O(blockCount)。
        if (player != null && (!player.isCreative()
                || com.xioyim.spatialblueprint.BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA.get())) {
            BlockPos size0 = data.size;
            List<BlueprintData.BlockEntry> allEntries = new ArrayList<>(
                    data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size());
            allEntries.addAll(data.normalBlocks);
            allEntries.addAll(data.dependentBlocks);
            allEntries.addAll(data.lateBlocks);
            for (BlueprintData.BlockEntry entry : allEntries) {
                BlockPos rel = entry.pos;
                BlockPos targetPos = origin.offset(
                        transformX(rel.getX(), rel.getY(), rel.getZ(), size0, rotation),
                        transformY(rel.getX(), rel.getY(), rel.getZ(), size0, rotation),
                        transformZ(rel.getX(), rel.getY(), rel.getZ(), size0, rotation));
                if (!isEntityPlaceEventAllowed(level, targetPos, player)) {
                    if (isDbg(player)) SpatialBlueprintMod.LOGGER.info(
                            "[SpatialBlueprint] Placement blocked by protection at {}", targetPos);
                    sendPlacementFailure(player, "spatial_blueprint.error.placement_protected");
                    return false;
                }
            }
        }
        // ── 结构冲突预检（服务端等价于客户端 PlacementPreValidator.EXTRA_BLOCKS）────────────
        // 仅在"完全清空"模式下执行：清空前需要确认包围盒无不可替换方块。
        // 替换模式（!clearFirst）跳过此检查——语义即"覆盖原有方块"，无需预先清空。
        //
        // ⚠ 只看游戏模式，不看 denyCreativePlacementInProtectedArea ——
        // 那个开关管的是「领地保护对创造模式是否生效」，跟「路上有没有挡道的方块」是两回事。
        // 创造模式本来就能随手拆掉挡路的方块，拿这条拦他没有意义；真正该拦住他的是领地插件。
        if (clearFirst && player != null && !player.isCreative()) {
            BlockPos structCheckSize = data.size;
            for (int y = 0; y < structCheckSize.getY(); y++) {
                for (int x = 0; x < structCheckSize.getX(); x++) {
                    for (int z = 0; z < structCheckSize.getZ(); z++) {
                        BlockPos targetPos = origin.offset(
                                transformX(x, y, z, structCheckSize, rotation),
                                transformY(x, y, z, structCheckSize, rotation),
                                transformZ(x, y, z, structCheckSize, rotation));
                        BlockState existing = level.getBlockState(targetPos);
                        if (!existing.isAir() && !existing.canBeReplaced()) {
                            if (isDbg(player)) SpatialBlueprintMod.LOGGER.info(
                                    "[SpatialBlueprint] Placement blocked (extra blocks) at {}", targetPos);
                            sendPlacementFailure(player,
                                    "spatial_blueprint.error.placement_blocked_extra_blocks");
                            return false;
                        }
                    }
                }
            }
        }

        // 领地保护预检（②破坏阶段）：
        // clearFirst=true：遍历整个包围盒（清空前需要对所有方块有破坏权限）。
        // clearFirst=false：仅遍历蓝图实际方块位置（我们只覆盖这些位置，其余区域不动）。
        if (player != null && (!player.isCreative()
                || com.xioyim.spatialblueprint.BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA.get())) {
            if (clearFirst) {
                BlockPos bboxSize = data.size;
                for (int y = 0; y < bboxSize.getY(); y++) {
                    for (int x = 0; x < bboxSize.getX(); x++) {
                        for (int z = 0; z < bboxSize.getZ(); z++) {
                            BlockPos targetPos = origin.offset(
                                    transformX(x, y, z, bboxSize, rotation),
                                    transformY(x, y, z, bboxSize, rotation),
                                    transformZ(x, y, z, bboxSize, rotation));
                            if (!isEntityBreakEventAllowed(level, targetPos, player)) {
                                if (isDbg(player)) SpatialBlueprintMod.LOGGER.info(
                                        "[SpatialBlueprint] Placement blocked (break protection) at {}", targetPos);
                                sendPlacementFailure(player, "spatial_blueprint.error.placement_protected");
                                return false;
                            }
                        }
                    }
                }
            } else {
                // 替换模式：仅对蓝图方块位置做破坏权限检查（setBlock 会覆盖原有方块）
                BlockPos size0 = data.size;
                List<BlueprintData.BlockEntry> allEntries = new ArrayList<>(
                        data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size());
                allEntries.addAll(data.normalBlocks);
                allEntries.addAll(data.dependentBlocks);
                allEntries.addAll(data.lateBlocks);
                for (BlueprintData.BlockEntry entry : allEntries) {
                    BlockPos rel = entry.pos;
                    BlockPos targetPos = origin.offset(
                            transformX(rel.getX(), rel.getY(), rel.getZ(), size0, rotation),
                            transformY(rel.getX(), rel.getY(), rel.getZ(), size0, rotation),
                            transformZ(rel.getX(), rel.getY(), rel.getZ(), size0, rotation));
                    if (!isEntityBreakEventAllowed(level, targetPos, player)) {
                        if (isDbg(player)) SpatialBlueprintMod.LOGGER.info(
                                "[SpatialBlueprint] Placement blocked (break protection, replace mode) at {}", targetPos);
                        sendPlacementFailure(player, "spatial_blueprint.error.placement_protected");
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * @param player          触发放置的玩家；可为 null（命令方块等触发时）
     * @param durationSeconds 玩家设定的动画时长（秒）；决定每 tick 放置方块数
     * @param clearFirst      true = 先清空整个包围盒再放置（默认行为）；
     *                        false = 仅替换蓝图方块位置，不清空包围盒空白区域
     */
    public static void startPlacement(ServerLevel level, BlockPos origin, BlueprintData data,
                                      Rotation rotation, @Nullable ServerPlayer player,
                                      int durationSeconds, boolean clearFirst,
                                      com.xioyim.spatialblueprint.util.FluidDisplayMode fluidMode) {
        // 根据 fluidMode 过滤掉不需要放置的流体方块
        if (fluidMode.affectsBuild()) {
            data = filterFluids(data, fluidMode);
        }
        if (isDbg(player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Placement starting: origin={} blocks={} size={} rotation={}",
                origin, data.normalBlocks.size() + data.dependentBlocks.size(), data.size, rotation);

        if (player != null) {
            NotifySound.server(player);
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.message.placement_started"), false);
        }

        level.playSound(null, origin, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.0f);

        // 计算放置区域的 AABB（旋转后可能是非轴对齐矩形，取外包 AABB 即可）
        BlockPos size = data.size;
        // 计算旋转后实际占用的最大范围
        int maxX = origin.getX(), maxY = origin.getY() + size.getY() - 1, maxZ = origin.getZ();
        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        for (int cx = 0; cx < size.getX(); cx++) {
            for (int cz = 0; cz < size.getZ(); cz++) {
                int wx = origin.getX() + transformX(cx, 0, cz, size, rotation);
                int wz = origin.getZ() + transformZ(cx, 0, cz, size, rotation);
                minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
            }
        }
        AABB clearBox = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);

        // UPDATE_CLIENTS(2):        同步客户端视图
        // UPDATE_KNOWN_SHAPE(16):   跳过 updateNeighbourShapes，压力板/红石/中继器等"形状依赖"方块不会因底部消失而弹出
        // UPDATE_SUPPRESS_DROPS(32):抑制物品掉落（参考 BuildingGadgets2 flag=48 = 16|32）
        // UPDATE_MOVE_BY_PISTON(64):传 isMoving=true 给 onRemove；DiodeBlock/RedStoneWireBlock 检测到后立即 return，不发出任何邻居更新
        int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_MOVE_BY_PISTON;
        BlockState air = Blocks.AIR.defaultBlockState();

        if (clearFirst) {
            // 第一步：移除区域内所有装饰实体（Entity，setBlock 不会自动移除）
            level.getEntitiesOfClass(Painting.class, clearBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
            level.getEntitiesOfClass(ItemFrame.class, clearBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));

            // 第二步：优先清除所有依赖方块（火把、红石、中继器、比较器、压力板等）
            // 此时它们的支撑方块仍在原位，onRemove 不会因支撑消失而触发掉落或级联更新。
            for (int y = 0; y < size.getY(); y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockPos targetPos = origin.offset(
                                transformX(x, y, z, size, rotation),
                                transformY(x, y, z, size, rotation),
                                transformZ(x, y, z, size, rotation)
                        );
                        BlockState state = level.getBlockState(targetPos);
                        if (!state.isAir() && BlueprintData.isDependent(state)) {
                            level.removeBlockEntity(targetPos);
                            level.setBlock(targetPos, air, clearFlags);
                        }
                    }
                }
            }

            // 第三步：清除剩余普通方块（依赖方块已全部移除，不会再发生级联掉落）
            for (int y = 0; y < size.getY(); y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockPos targetPos = origin.offset(
                                transformX(x, y, z, size, rotation),
                                transformY(x, y, z, size, rotation),
                                transformZ(x, y, z, size, rotation)
                        );
                        if (!level.getBlockState(targetPos).isAir()) {
                            level.removeBlockEntity(targetPos);
                            level.setBlock(targetPos, air, clearFlags);
                        }
                    }
                }
            }
        }

        // Blocks are pre-classified and pre-sorted at capture time
        int safeDuration = Math.max(1, durationSeconds);
        int durationTicks = safeDuration * 20;
        int blocksPerTickEstimate = (data.normalBlocks.isEmpty()) ? 0
                : Math.max(1, (data.normalBlocks.size() + durationTicks - 1) / durationTicks);
        if (isDbg(player)) SpatialBlueprintMod.LOGGER.info(
                "[SpatialBlueprint] Blocks split: {} normal, {} dependent, {} late, {} entities | duration={}s blocksPerTick~{}",
                data.normalBlocks.size(), data.dependentBlocks.size(), data.lateBlocks.size(), data.entities.size(),
                safeDuration, blocksPerTickEstimate);
        // 打乱普通方块顺序，使动画呈现随机散点效果而非按扫描顺序逐层出现
        List<BlueprintData.BlockEntry> shuffled = new ArrayList<>(data.normalBlocks);
        Collections.shuffle(shuffled);
        PendingPlacement placement = new PendingPlacement(level, origin, data.size, shuffled, data.dependentBlocks,
                data.lateBlocks, data.entities, rotation, player, safeDuration);

        // 放置前一次性强制加载结构覆盖的所有区块（旋转后取外包 AABB），
        // 避免动画期间逐格 setBlock 触发区块加载风暴；结束后恢复。
        BlockPos forceSize = data.size;
        int fMinX = origin.getX(), fMaxX = origin.getX(), fMinZ = origin.getZ(), fMaxZ = origin.getZ();
        for (int x = 0; x < forceSize.getX(); x++) {
            for (int z = 0; z < forceSize.getZ(); z++) {
                int wx = origin.getX() + transformX(x, 0, z, forceSize, rotation);
                int wz = origin.getZ() + transformZ(x, 0, z, forceSize, rotation);
                if (wx < fMinX) fMinX = wx;  if (wx > fMaxX) fMaxX = wx;
                if (wz < fMinZ) fMinZ = wz;  if (wz > fMaxZ) fMaxZ = wz;
            }
        }
        placement.newlyForcedChunks.addAll(forceLoadChunks(level, fMinX, fMaxX, fMinZ, fMaxZ));
        if (isDbg(player) && !placement.newlyForcedChunks.isEmpty()) {
            SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Force-loaded {} placement chunk(s) at {}",
                    placement.newlyForcedChunks.size(), origin);
        }
        pendingPlacements.add(placement);
    }

    private static void sendPlacementFailure(ServerPlayer player, String messageKey) {
        BlueprintNetwork.sendToPlayer(player, S2C_OperationResult.failure(messageKey));
        BlueprintNetwork.sendToPlayer(player,
                new com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage(-1f));
    }

    /**
     * 创造模式专用：瞬间将蓝图中所有方块写入世界，不经过 RenderBlock 动画，
     * 不生成 BuildBlueprintItem 冷却物品。
     *
     * <p>流程与 {@link #startPlacement} 的清除阶段相同，但放置阶段直接调用
     * {@link ServerLevel#setBlock}，无需等待 tick。</p>
     */
    public static void placeImmediately(ServerLevel level, BlockPos origin, BlueprintData data,
                                        Rotation rotation, @Nullable ServerPlayer player, boolean clearFirst) {
        if (isDbg(player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Immediate placement: origin={} blocks={} rotation={}",
                origin, data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size(), rotation);

        BlockPos size = data.size;

        // ── 计算放置区域 AABB ────────────────────────────────────────────
        int maxX = origin.getX(), maxY = origin.getY() + size.getY() - 1, maxZ = origin.getZ();
        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        for (int cx = 0; cx < size.getX(); cx++) {
            for (int cz = 0; cz < size.getZ(); cz++) {
                int wx = origin.getX() + transformX(cx, 0, cz, size, rotation);
                int wz = origin.getZ() + transformZ(cx, 0, cz, size, rotation);
                minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
            }
        }
        AABB clearBox = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);

        int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
                | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_MOVE_BY_PISTON;
        BlockState air = Blocks.AIR.defaultBlockState();

        if (clearFirst) {
            // ── 清除区域内实体 ───────────────────────────────────────────────
            level.getEntitiesOfClass(Painting.class, clearBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
            level.getEntitiesOfClass(ItemFrame.class, clearBox, e -> true)
                    .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));

            // ── 清除依赖方块（火把/红石等先移除，避免支撑消失级联掉落）────────
            for (int y = 0; y < size.getY(); y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockPos targetPos = origin.offset(
                                transformX(x, y, z, size, rotation),
                                transformY(x, y, z, size, rotation),
                                transformZ(x, y, z, size, rotation));
                        BlockState state = level.getBlockState(targetPos);
                        if (!state.isAir() && BlueprintData.isDependent(state)) {
                            level.removeBlockEntity(targetPos);
                            level.setBlock(targetPos, air, clearFlags);
                        }
                    }
                }
            }

            // ── 清除普通方块 ─────────────────────────────────────────────────
            for (int y = 0; y < size.getY(); y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockPos targetPos = origin.offset(
                                transformX(x, y, z, size, rotation),
                                transformY(x, y, z, size, rotation),
                                transformZ(x, y, z, size, rotation));
                        if (!level.getBlockState(targetPos).isAir()) {
                            level.removeBlockEntity(targetPos);
                            level.setBlock(targetPos, air, clearFlags);
                        }
                    }
                }
            }
        }

        // ── 放置普通方块 ─────────────────────────────────────────────────
        int placed = 0;

        for (BlueprintData.BlockEntry entry : data.normalBlocks) {
            BlockPos relPos  = entry.pos;
            BlockPos worldPos = origin.offset(
                    transformX(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation),
                    transformY(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation),
                    transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation));
            BlockState state = applyTransform(level, worldPos, entry.state, rotation);
            if (state.isAir()) continue;

            level.setBlock(worldPos, state, 3);
            if (entry.nbt != null && !entry.nbt.isEmpty()) {
                BlockEntity be = level.getBlockEntity(worldPos);
                if (be != null) {
                    try { be.loadWithComponents(entry.nbt, level.registryAccess()); be.setChanged(); }
                    catch (Exception e) { SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] BE load failed at {}: {}", worldPos, e.getMessage()); }
                }
            }
            placed++;
        }

        // ── 放置依赖方块（门、床、双高植物，普通方块已全部到位）────────────
        for (BlueprintData.BlockEntry entry : data.dependentBlocks) {
            BlockPos relPos  = entry.pos;
            BlockPos worldPos = origin.offset(
                    transformX(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation),
                    transformY(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation),
                    transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation));
            BlockState state = applyTransform(level, worldPos, entry.state, rotation);
            if (state.isAir()) continue;

            level.setBlock(worldPos, state, 3);
            if (entry.nbt != null && !entry.nbt.isEmpty()) {
                BlockEntity be = level.getBlockEntity(worldPos);
                if (be != null) {
                    try { be.loadWithComponents(entry.nbt, level.registryAccess()); be.setChanged(); }
                    catch (Exception e) { SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Dependent BE load failed at {}: {}", worldPos, e.getMessage()); }
                }
            }
            placed++;
        }

        if (isDbg(player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Immediate placement done: {}/{} blocks at {}",
                placed, data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size(), origin);

        // ── 召唤装饰实体（画、展示框）────────────────────────────────────
        int entitiesPlaced = 0;
        for (BlueprintData.EntityEntry entry : data.entities) {
            // 盒型实体（超级胶水）的位置在 From/To 角点上，走单独的变换路径
            if (com.xioyim.spatialblueprint.BlueprintConfig.getBoxEntities().contains(entry.type)) {
                if (placeBoxEntity(level, entry, origin, size, rotation)) entitiesPlaced++;
                continue;
            }
            int tileWorldX = origin.getX() + transformX(entry.tileX, entry.tileY, entry.tileZ, size, rotation);
            int tileWorldY = origin.getY() + transformY(entry.tileX, entry.tileY, entry.tileZ, size, rotation);
            int tileWorldZ = origin.getZ() + transformZ(entry.tileX, entry.tileY, entry.tileZ, size, rotation);

            if (!level.hasChunk(tileWorldX >> 4, tileWorldZ >> 4)) continue;

            CompoundTag nbt = entry.nbt.copy();
            nbt.putInt("TileX", tileWorldX);
            nbt.putInt("TileY", tileWorldY);
            nbt.putInt("TileZ", tileWorldZ);

            if (entry.type.equals("minecraft:painting")) {
                Direction rotated = rotation.rotate(Direction.from2DDataValue(entry.facing & 3));
                nbt.putByte("facing", (byte) rotated.get2DDataValue());
            } else {
                Direction rotated = rotation.rotate(Direction.from3DDataValue(entry.facing & 7));
                nbt.putByte("Facing", (byte) rotated.get3DDataValue());
            }
            net.minecraft.nbt.ListTag posList = new net.minecraft.nbt.ListTag();
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(tileWorldX + 0.5));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(entityCenterY(entry, tileWorldY)));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(tileWorldZ + 0.5));
            nbt.put("Pos", posList);

            try {
                java.util.Optional<net.minecraft.world.entity.Entity> entityOpt = EntityType.create(nbt, level);
                if (entityOpt.isPresent()) {
                    level.addFreshEntity(entityOpt.get());
                    entitiesPlaced++;
                }
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Entity spawn failed {} at ({},{},{}): {}",
                        entry.type, tileWorldX, tileWorldY, tileWorldZ, e.getMessage());
            }
        }
        if (!data.entities.isEmpty() && isDbg(player)) {
            SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Immediate entity spawn: {}/{} at {}",
                    entitiesPlaced, data.entities.size(), origin);
        }

        // ── 放置延迟方块（箱子/熔炉等容器，第④轮）──────────────────────────
        if (!data.lateBlocks.isEmpty()) {
            int latePlaced = 0;
            for (BlueprintData.BlockEntry entry : data.lateBlocks) {
                BlockPos relPos  = entry.pos;
                BlockPos worldPos = origin.offset(
                        transformX(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation),
                        transformY(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation),
                        transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), size, rotation));
                BlockState state = applyTransform(level, worldPos, entry.state, rotation);
                if (state.isAir()) continue;

                level.setBlock(worldPos, state, 3);
                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    BlockEntity be = level.getBlockEntity(worldPos);
                    if (be != null) {
                        try { be.loadWithComponents(entry.nbt, level.registryAccess()); be.setChanged(); }
                        catch (Exception e) { SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Late BE load failed at {}: {}", worldPos, e.getMessage()); }
                    }
                }
                latePlaced++;
            }
            if (isDbg(player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Immediate late placement: {}/{} at {}",
                    latePlaced, data.lateBlocks.size(), origin);
        }

        // ── 声音 + 提示 ──────────────────────────────────────────────────
        level.playSound(null, origin, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0f, 1.0f);
        if (player != null) {
            NotifySound.server(player);
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.message.placement_completed"), false);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        applyGlowingToSelectionEntities(event.getServer());
        processPendingGlowTasks(event.getServer());

        Iterator<PendingPlacement> iterator = pendingPlacements.iterator();
        while (iterator.hasNext()) {
            PendingPlacement placement = iterator.next();
            if (!placement.level.isLoaded(placement.origin)) continue;

            if (placement.finished) {
                placement.finishTick++;
                // 动画完成后等待渲染块完成视觉动画再强制替换；等待时长取决于动画速度。
                int waitTicks = Math.max(3, 22 / BlueprintConfig.ANIMATION_SPEED.get());
                if (placement.finishTick >= waitTicks) {
                    if (isDbg(placement.player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Force placing {} blocks at {}",
                            placement.normalBlocks.size() + placement.dependentBlocks.size() + placement.lateBlocks.size(), placement.origin);
                    forcePlaceAllBlocks(placement);
                    iterator.remove();
                }
                continue;
            }

            // 根据玩家设定的动画时长，均匀分配每 tick 的放置数量
            // blocksPerTick = ceil(totalBlocks / (durationSeconds * 20))
            int durationTicks = placement.durationSeconds * 20;
            int blocksPerTick = Math.max(1, (placement.totalBlocks + durationTicks - 1) / durationTicks);
            for (int i = 0; i < blocksPerTick && placement.currentIndex < placement.totalBlocks; i++) {
                placeBlockWithAnimation(placement, placement.normalBlocks.get(placement.currentIndex));
                placement.currentIndex++;
            }

            // 真实放置进度 → 客户端动画边框平滑跟随（0 ~ 1）
            sendPlacementProgress(placement,
                    placement.totalBlocks > 0 ? (float) placement.currentIndex / placement.totalBlocks : 1f);

            if (placement.currentIndex >= placement.totalBlocks) {
                placement.finished = true;
                if (isDbg(placement.player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Placement animation complete: {} blocks at {}",
                        placement.totalBlocks, placement.origin);

                // C1：放置本身照常收尾，只跳过对已离线玩家的通知（否则偶发 warn）
                if (placement.player != null && !placement.player.hasDisconnected()) {
                    NotifySound.server(placement.player);
                    placement.player.displayClientMessage(
                            Component.translatable("spatial_blueprint.message.placement_completed"), false);
                }
                placement.level.playSound(null, placement.origin,
                        SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
        }
    }

    // 服务器关闭时立即完成所有进行中的放置，防止 render_block 残留在世界中
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Server stopping — force completing {} pending placement(s)",
                pendingPlacements.size());
        for (PendingPlacement placement : pendingPlacements) {
            try {
                forcePlaceAllBlocks(placement);
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to force-complete placement at {}: {}",
                        placement.origin, e.getMessage());
            }
        }
        pendingPlacements.clear();
    }

    // 玩家登出时清理冷却记录，防止 lastActionTime map 无限增长
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        com.xioyim.spatialblueprint.server.BlueprintValidator.clearCooldown(event.getEntity().getUUID());
        com.xioyim.spatialblueprint.server.BlueprintValidator.clearUploadRateLimit(event.getEntity().getUUID());
    }

    // 玩家登录时将服务端捕捉体积配置同步到客户端
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        try {
            BlueprintNetwork.sendToPlayer(sp, S2C_ConfigSyncMessage.fromConfig());
        } catch (UnsupportedOperationException e) {
            // 半成品连接走不到发包：GameTest 自建的 mock 玩家（placeNewPlayer 直调）在配置阶段就触发
            // 登录事件，NeoForge 相位检查拒绝 play 包；真玩家此时已是 play 阶段，不受影响。
            // 只接这一种（云端验收收窄），其它异常照常抛出，不静默吞掉
            SpatialBlueprintMod.LOGGER.debug("[SpatialBlueprint] Config sync skipped for {}: {}",
                    sp.getGameProfile().getName(), e.getMessage());
        }
    }

    // render_block 只允许空手瞬间破坏
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!(event.getState().getBlock() instanceof RenderBlock)) return;
        if (!event.getEntity().getMainHandItem().isEmpty()) {
            event.setCanceled(true);
        }
    }

    // 世界卸载时清理属于该世界的放置任务
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        pendingPlacements.removeIf(p -> {
            if (p.level != serverLevel) return false;
            try {
                forcePlaceAllBlocks(p);
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to force-complete placement on level unload at {}: {}",
                        p.origin, e.getMessage());
            }
            return true;
        });
    }

    private static void placeBlockWithAnimation(PendingPlacement placement, BlueprintData.BlockEntry entry) {
        BlockPos relPos = entry.pos;
        BlockPos targetPos = placement.origin.offset(
                transformX(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                transformY(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation)
        );

        BlockState state = applyTransform(placement.level, targetPos, entry.state, placement.rotation);
        if (state.isAir()) return;

        placement.level.setBlockAndUpdate(targetPos, SpatialBlueprintRegistry.RENDER_BLOCK.get().defaultBlockState());
        BlockEntity be = placement.level.getBlockEntity(targetPos);
        if (be instanceof RenderBlockEntity renderBE) {
            renderBE.setRenderData(Blocks.AIR.defaultBlockState(), state);
            if (entry.nbt != null) {
                renderBE.setBlockEntityData(entry.nbt);
            }
        }
    }

    private static void forcePlaceAllBlocks(PendingPlacement placement) {
        int placed = 0;

        // 第一轮：清理未能自替换的残余 RenderBlock（正常情况下 tickServer 已处理完毕）
        for (BlueprintData.BlockEntry entry : placement.normalBlocks) {
            BlockPos relPos = entry.pos;
            BlockPos targetPos = placement.origin.offset(
                    transformX(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                    transformY(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                    transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation)
            );

            // tickServer 动画结束后已自替换，跳过
            if (!(placement.level.getBlockState(targetPos).getBlock() instanceof RenderBlock)) continue;

            BlockState state = applyTransform(placement.level, targetPos, entry.state, placement.rotation);
            if (state.isAir()) {
                placement.level.removeBlock(targetPos, false);
                continue;
            }

            BlockState current = placement.level.getBlockState(targetPos);
            if (placement.level.getBlockEntity(targetPos) instanceof RenderBlockEntity) {
                placement.level.removeBlockEntity(targetPos);
            }
            placement.level.setBlock(targetPos, state, 3);

            if (entry.nbt != null && !entry.nbt.isEmpty()) {
                BlockEntity be = placement.level.getBlockEntity(targetPos);
                if (be != null) {
                    try {
                        be.loadWithComponents(entry.nbt, placement.level.registryAccess());
                        be.setChanged();
                    } catch (Exception e) {
                        SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to restore block entity data at {}: {}",
                                targetPos, e.getMessage());
                    }
                }
            }

            placed++;
        }

        // 第二轮：瞬间放置依赖方块（门/床/双高植物），此时普通方块已全部到位
        for (BlueprintData.BlockEntry entry : placement.dependentBlocks) {
            BlockPos relPos = entry.pos;
            BlockPos targetPos = placement.origin.offset(
                    transformX(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                    transformY(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                    transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation)
            );

            BlockState state = applyTransform(placement.level, targetPos, entry.state, placement.rotation);
            if (state.isAir()) continue;

            placement.level.setBlock(targetPos, state, 3);

            if (entry.nbt != null && !entry.nbt.isEmpty()) {
                BlockEntity be = placement.level.getBlockEntity(targetPos);
                if (be != null) {
                    try {
                        be.loadWithComponents(entry.nbt, placement.level.registryAccess());
                        be.setChanged();
                    } catch (Exception e) {
                        SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to restore dependent block entity data at {}: {}",
                                targetPos, e.getMessage());
                    }
                }
            }

            placed++;
        }

        if (isDbg(placement.player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Force placement done: {}/{} blocks ({} dependent) at {}",
                placed, placement.normalBlocks.size() + placement.dependentBlocks.size() + placement.lateBlocks.size(),
                placement.dependentBlocks.size(), placement.origin);

        // 第三轮：召唤装饰实体（画、展示框）
        int entitiesPlaced = 0;
        for (BlueprintData.EntityEntry entry : placement.entities) {
            // 盒型实体（超级胶水）的位置在 From/To 角点上，走单独的变换路径
            if (com.xioyim.spatialblueprint.BlueprintConfig.getBoxEntities().contains(entry.type)) {
                if (placeBoxEntity(placement.level, entry, placement.origin,
                        placement.size, placement.rotation)) entitiesPlaced++;
                continue;
            }
            int tileWorldX = placement.origin.getX() + transformX(entry.tileX, entry.tileY, entry.tileZ, placement.size, placement.rotation);
            int tileWorldY = placement.origin.getY() + transformY(entry.tileX, entry.tileY, entry.tileZ, placement.size, placement.rotation);
            int tileWorldZ = placement.origin.getZ() + transformZ(entry.tileX, entry.tileY, entry.tileZ, placement.size, placement.rotation);

            if (!placement.level.hasChunk(tileWorldX >> 4, tileWorldZ >> 4)) continue;

            CompoundTag nbt = entry.nbt.copy();
            nbt.putInt("TileX", tileWorldX);
            nbt.putInt("TileY", tileWorldY);
            nbt.putInt("TileZ", tileWorldZ);

            // Painting  使用 Direction.get2DDataValue() 编码（SOUTH=0,WEST=1,NORTH=2,EAST=3），NBT key "facing"（小写）
            // ItemFrame 使用 Direction.get3DDataValue() 编码（DOWN=0,UP=1,NORTH=2,SOUTH=3,WEST=4,EAST=5），NBT key "Facing"（大写）
            // 两者编码不同，必须分开处理，否则 3D 值经 & 3 截断后方向完全错误（如 EAST=5 → UP=1 → 地板）
            if (entry.type.equals("minecraft:painting")) {
                Direction rotated = placement.rotation.rotate(Direction.from2DDataValue(entry.facing & 3));
                nbt.putByte("facing", (byte) rotated.get2DDataValue());
            } else {
                // item_frame / glow_item_frame 及未来可能的其他 HangingEntity 子类
                Direction rotated = placement.rotation.rotate(Direction.from3DDataValue(entry.facing & 7));
                nbt.putByte("Facing", (byte) rotated.get3DDataValue());
            }
            // 关键修复：HangingEntity.readAdditionalSaveData 会检查 TileX/Y/Z 是否在当前 Pos 16 格以内。
            // Pos 仍指向原始捕捉坐标，距离可能远超 16 格导致验证失败。必须同步更新 Pos。
            net.minecraft.nbt.ListTag posList = new net.minecraft.nbt.ListTag();
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(tileWorldX + 0.5));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(entityCenterY(entry, tileWorldY)));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(tileWorldZ + 0.5));
            nbt.put("Pos", posList);

            try {
                java.util.Optional<net.minecraft.world.entity.Entity> entityOpt =
                        EntityType.create(nbt, placement.level);
                if (entityOpt.isPresent()) {
                    placement.level.addFreshEntity(entityOpt.get());
                    entitiesPlaced++;
                } else {
                    SpatialBlueprintMod.LOGGER.warn(
                            "[SpatialBlueprint] EntityType.create returned empty for '{}' at ({},{},{})",
                            entry.type, tileWorldX, tileWorldY, tileWorldZ);
                }
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to summon entity {} at ({},{},{}): {}",
                        entry.type, tileWorldX, tileWorldY, tileWorldZ, e.getMessage());
            }
        }
        if ((entitiesPlaced > 0 || !placement.entities.isEmpty()) && isDbg(placement.player)) {
            SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Summoned {}/{} entities at {}",
                    entitiesPlaced, placement.entities.size(), placement.origin);
        }

        // 第四轮：强制放置延迟方块（箱子/熔炉等容器），确保 NBT 正确写入
        if (!placement.lateBlocks.isEmpty()) {
            int latePlaced = 0;
            for (BlueprintData.BlockEntry entry : placement.lateBlocks) {
                BlockPos relPos = entry.pos;
                BlockPos targetPos = placement.origin.offset(
                        transformX(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                        transformY(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation),
                        transformZ(relPos.getX(), relPos.getY(), relPos.getZ(), placement.size, placement.rotation)
                );

                BlockState state = applyTransform(placement.level, targetPos, entry.state, placement.rotation);
                if (state.isAir()) continue;

                placement.level.setBlock(targetPos, state, 3);

                if (entry.nbt != null && !entry.nbt.isEmpty()) {
                    BlockEntity be = placement.level.getBlockEntity(targetPos);
                    if (be != null) {
                        try {
                            be.loadWithComponents(entry.nbt, placement.level.registryAccess());
                            be.setChanged();
                        } catch (Exception e) {
                            SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Late BE load failed at {}: {}",
                                    targetPos, e.getMessage());
                        }
                    }
                }

                latePlaced++;
            }
            if (isDbg(placement.player)) SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Late placement done: {}/{} blocks at {}",
                    latePlaced, placement.lateBlocks.size(), placement.origin);
        }

        // 放置完成：解除本次新增的强制加载区块（原本已强制的区块不受影响）
        releaseForcedChunks(placement);

        // 放置完成：动画边框立即走满（进度 1.0 → 客户端进入完成态 + 2 秒淡出）
        sendPlacementProgress(placement, 1f);

        // 放置完成：2 秒后对选区内全部掉落物设置发光标记（Glowing NBT），方便玩家查看
        scheduleGlowDrops(placement);
    }

    /** 向触发放置的玩家推送真实放置进度（progress=1.0 表示全部落地完成）。 */
    private static void sendPlacementProgress(PendingPlacement placement, float progress) {
        if (placement.player == null) return;
        try {
            com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(placement.player,
                    new com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage(progress));
        } catch (Exception ignored) {}
    }

    private static final java.util.List<GlowTask> pendingGlowTasks = new ArrayList<>();
    private record GlowTask(net.minecraft.server.level.ServerLevel level, AABB box, long endTick) {}

    /** 延迟 2 秒（40 tick）后给选区内掉落物发光。 */
    private static void scheduleGlowDrops(PendingPlacement placement) {
        if (placement.level == null) return;
        int sx = placement.size.getX();
        int sy = placement.size.getY();
        int sz = placement.size.getZ();
        int extentX = Math.max(sx, sz);
        int extentZ = Math.max(sx, sz);
        AABB box = new AABB(
                placement.origin.getX() - 1, placement.origin.getY() - 1, placement.origin.getZ() - 1,
                placement.origin.getX() + extentX + 1, placement.origin.getY() + sy + 1, placement.origin.getZ() + extentZ + 1);
        pendingGlowTasks.add(new GlowTask(placement.level, box, placement.level.getGameTime() + 40));
    }

    /** 到期后对掉落物施加发光标记。 */
    private static void processPendingGlowTasks(net.minecraft.server.MinecraftServer server) {
        if (server == null || pendingGlowTasks.isEmpty()) return;
        Iterator<GlowTask> it = pendingGlowTasks.iterator();
        while (it.hasNext()) {
            GlowTask t = it.next();
            if (t.level().getGameTime() >= t.endTick()) {
                java.util.Set<String> specialDrops = com.xioyim.spatialblueprint.BlueprintConfig.getSpecialDropItems();
                for (net.minecraft.world.entity.item.ItemEntity item :
                        t.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, t.box(), e -> true)) {
                    item.setGlowingTag(true);
                    // 特殊掉落物：永不自动消失（UnlimitedLifetime NBT）
                    String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(item.getItem().getItem()).toString();
                    if (specialDrops.contains(itemId)) {
                        item.setUnlimitedLifetime();
                    }
                }
                it.remove();
            }
        }
    }

    /**
     * 强制加载指定区块范围（按世界坐标 AABB 取外包区块），返回本次新增强制的区块列表。
     * 原本已强制的区块（isPositionForced == true）不记录、不改动。
     */
    private static List<ChunkPos> forceLoadChunks(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
        List<ChunkPos> added = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.getForcedChunks().contains(ChunkPos.asLong(cx, cz))) {
                    level.setChunkForced(cx, cz, true);
                    added.add(new ChunkPos(cx, cz));
                }
            }
        }
        return added;
    }

    /**
     * 手持空白蓝图时，对选区内的盔甲架/白名单生物施加发光效果（6s），每 5s 刷新一次。
     */
    private static void applyGlowingToSelectionEntities(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            // 每 5 秒（100 tick）刷新一次，保持发光持续
            if (level.getGameTime() % 100 != 0) continue;
            for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof BlankBlueprintItem)) {
                    stack = player.getOffhandItem();
                    if (!(stack.getItem() instanceof BlankBlueprintItem)) continue;
                }
                BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
                BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
                if (p1 == null || p2 == null) continue;
                int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
                int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
                int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
                AABB box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
                for (net.minecraft.world.entity.LivingEntity le :
                        level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                                box, e -> e instanceof net.minecraft.world.entity.LivingEntity
                                        && !(e instanceof net.minecraft.world.entity.player.Player))) {
                    boolean isArmorStand = le instanceof net.minecraft.world.entity.decoration.ArmorStand;
                    boolean isMob = !isArmorStand && com.xioyim.spatialblueprint.util.BlueprintSanitizer.ALLOWED_MOB_ENTITIES
                            .contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString());
                    if (isArmorStand || isMob) {
                        le.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                                net.minecraft.world.effect.MobEffects.GLOWING, 120, 0, false, false));
                    }
                }
            }
        }
    }

    /** 解除放置任务新增的强制加载区块，恢复放置前状态。幂等，可安全重复调用。 */
    private static void releaseForcedChunks(PendingPlacement placement) {
        if (placement.level == null) return;
        for (ChunkPos pos : placement.newlyForcedChunks) {
            try {
                placement.level.setChunkForced(pos.x, pos.z, false);
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to release forced chunk {}: {}",
                        pos, e.getMessage());
            }
        }
        placement.newlyForcedChunks.clear();
    }

    /**
     * 实体放置时的 Y 坐标：画/展示框 方块中心(+0.5)；盔甲架 脚底+0.9875（中心）；生物 脚底(0)。
     */
    private static double entityCenterY(BlueprintData.EntityEntry entry, int tileWorldY) {
        if (entry.type.equals("minecraft:armor_stand")) {
            return tileWorldY + 0.9875;
        }
        if (entry.type.equals("minecraft:painting")
                || entry.type.equals("minecraft:item_frame")
                || entry.type.equals("minecraft:glow_item_frame")) {
            return tileWorldY + 0.5;
        }
        return tileWorldY;
    }

    /**
     * 向 Forge 事件总线投递 BlockEvent.EntityPlaceEvent，供领地保护插件（GriefPrevention、
     * FTB Chunks、Lands 等）拦截。返回 false 表示该位置不允许放置。
     */
    private static boolean isEntityPlaceEventAllowed(ServerLevel level, BlockPos pos,
                                                     net.minecraft.world.entity.player.Player player) {
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                snapshot, Blocks.DIRT.defaultBlockState(), player);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }

    /**
     * 向 Forge 事件总线投递 BlockEvent.BreakEvent，供领地保护插件拦截清除阶段。
     * 若目标位置已经是空气则跳过检测直接返回 true（无需破坏）。
     * 返回 false 表示该位置不允许破坏。
     */
    private static boolean isEntityBreakEventAllowed(ServerLevel level, BlockPos pos,
                                                     ServerPlayer player) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, state, player);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }

    private static BlockState applyTransform(ServerLevel level, BlockPos pos, BlockState state, Rotation rotation) {
        if (rotation != Rotation.NONE) state = state.rotate(level, pos, rotation);
        return state;
    }

    /** 返回过滤了指定流体后的 BlueprintData 浅拷贝（只复制列表，原始对象不可变）。 */
    private static BlueprintData filterFluids(BlueprintData data,
                                               com.xioyim.spatialblueprint.util.FluidDisplayMode mode) {
        BlueprintData filtered = new BlueprintData();
        filtered.name        = data.name;
        filtered.size        = data.size;
        filtered.animDuration = data.animDuration;
        filtered.materialList = data.materialList;
        filtered.category    = data.category;
        filtered.creatorName = data.creatorName;
        filtered.sourceName  = data.sourceName;
        filtered.ownerUuid   = data.ownerUuid;
        filtered.createdAt   = data.createdAt;
        filtered.entities.addAll(data.entities);
        for (BlueprintData.BlockEntry e : data.normalBlocks)
            if (!mode.filtersFromBuild(e.state)) filtered.normalBlocks.add(e);
        for (BlueprintData.BlockEntry e : data.dependentBlocks)
            if (!mode.filtersFromBuild(e.state)) filtered.dependentBlocks.add(e);
        for (BlueprintData.BlockEntry e : data.lateBlocks)
            if (!mode.filtersFromBuild(e.state)) filtered.lateBlocks.add(e);
        return filtered;
    }

    // ── Transform helpers delegated to shared BlueprintTransform util (#12) ──
    /**
     * 放置盒型实体（Create 超级胶水等）—— 位置在 NBT 的 {@code From}/{@code To} 角点上，
     * 不在 {@code Pos} / {@code TileXYZ}。
     *
     * <p>捕获时这对角点已转成相对坐标，这里按旋转变换回世界坐标。三个要点：</p>
     * <ul>
     *   <li>角点走 {@link BlueprintTransform#cornerX}/{@code cornerZ}（连续坐标，公式不带 -1），
     *       用方块版会让整个盒子偏一格；</li>
     *   <li>旋转后 From 不一定还是最小角 —— 90°/180° 会把两个角对调，
     *       所以变换完必须重新按 min/max 规范化，否则 Create 读出一个反向 AABB；</li>
     *   <li>{@code Pos} 补成盒子中心。{@code Entity.load} 先读 Pos 再读 From/To，
     *       Pos 留在原捕获点会让实体先落到很远的地方。</li>
     * </ul>
     *
     * @return 成功放下返回 {@code true}
     */
    private static boolean placeBoxEntity(net.minecraft.server.level.ServerLevel level,
                                          BlueprintData.EntityEntry entry,
                                          BlockPos origin, BlockPos size, Rotation rotation) {
        net.minecraft.nbt.ListTag from = entry.nbt.getList("From", 6);
        net.minecraft.nbt.ListTag to   = entry.nbt.getList("To", 6);
        if (from.size() != 3 || to.size() != 3) return false;

        double fx = BlueprintTransform.cornerX(from.getDouble(0), from.getDouble(2), size, rotation) + origin.getX();
        double fz = BlueprintTransform.cornerZ(from.getDouble(0), from.getDouble(2), size, rotation) + origin.getZ();
        double tx = BlueprintTransform.cornerX(to.getDouble(0),   to.getDouble(2),   size, rotation) + origin.getX();
        double tz = BlueprintTransform.cornerZ(to.getDouble(0),   to.getDouble(2),   size, rotation) + origin.getZ();
        double fy = from.getDouble(1) + origin.getY();
        double ty = to.getDouble(1)   + origin.getY();

        // 旋转把角点对调了，重新规范化成 min/max
        double loX = Math.min(fx, tx), hiX = Math.max(fx, tx);
        double loY = Math.min(fy, ty), hiY = Math.max(fy, ty);
        double loZ = Math.min(fz, tz), hiZ = Math.max(fz, tz);

        if (!level.hasChunk((int) Math.floor(loX) >> 4, (int) Math.floor(loZ) >> 4)) {
            return false;
        }

        CompoundTag nbt = entry.nbt.copy();
        // Create 读盘：bb = (From/To) + Pos，Pos 是盒底中心（resetPositionToBB 的约定）。
        // 所以 From/To 必须写成相对 Pos 的偏移，不是世界坐标 —— 写世界坐标会被再加一次 Pos，
        // 盒子跑到两倍远的地方去。
        double cx = (loX + hiX) / 2.0, cz = (loZ + hiZ) / 2.0;
        nbt.put("Pos",  doubleTriple(cx, loY, cz));
        nbt.put("From", doubleTriple(loX - cx, 0.0,       loZ - cz));
        nbt.put("To",   doubleTriple(hiX - cx, hiY - loY, hiZ - cz));
        // 这几个是给悬挂实体用的，对盒型实体没有意义，留着反而可能被误读
        nbt.remove("TileX");
        nbt.remove("TileY");
        nbt.remove("TileZ");
        nbt.remove("Facing");
        nbt.remove("facing");

        try {
            java.util.Optional<net.minecraft.world.entity.Entity> opt = EntityType.create(nbt, level);
            if (opt.isPresent()) {
                level.addFreshEntity(opt.get());
                return true;
            }
            // Create 没装时这里就是 empty —— 蓝图里带着胶水、目标服务器没有 Create，
            // 属于正常情况，不该刷 warn。
            SpatialBlueprintMod.LOGGER.debug(
                    "[SpatialBlueprint] Box entity '{}' could not be created (mod absent?)", entry.type);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to summon box entity {}: {}",
                    entry.type, e.getMessage());
        }
        return false;
    }

    private static net.minecraft.nbt.ListTag doubleTriple(double a, double b, double c) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(net.minecraft.nbt.DoubleTag.valueOf(a));
        list.add(net.minecraft.nbt.DoubleTag.valueOf(b));
        list.add(net.minecraft.nbt.DoubleTag.valueOf(c));
        return list;
    }

    private static int transformX(int x, int y, int z, BlockPos size, Rotation rotation) {
        return BlueprintTransform.x(x, y, z, size, rotation);
    }
    private static int transformY(int x, int y, int z, BlockPos size, Rotation rotation) {
        return BlueprintTransform.y(x, y, z, size, rotation);
    }
    private static int transformZ(int x, int y, int z, BlockPos size, Rotation rotation) {
        return BlueprintTransform.z(x, y, z, size, rotation);
    }

    // ── Undo 快照 / 还原 ──────────────────────────────────────────────────────

    /**
     * 计算蓝图放置区域的世界空间轴对齐包围盒（与 startPlacement 内的 clearBox 逻辑完全一致）。
     * 返回 [minX, minY, minZ, maxX, maxY, maxZ]。
     */
    public static int[] computeWorldBBox(BlockPos origin, BlockPos size, Rotation rotation) {
        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = origin.getX(), maxY = origin.getY() + size.getY() - 1, maxZ = origin.getZ();
        for (int cx = 0; cx < size.getX(); cx++) {
            for (int cz = 0; cz < size.getZ(); cz++) {
                int wx = origin.getX() + transformX(cx, 0, cz, size, rotation);
                int wz = origin.getZ() + transformZ(cx, 0, cz, size, rotation);
                minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
            }
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * 在放置任何方块前对整个包围盒做快照。
     * 只记录非空气方块（空气通过清空包围盒隐式还原），以及画/展示框实体的完整 NBT。
     */
    public static com.xioyim.spatialblueprint.util.UndoRecord snapshotRegion(
            ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        ResourceLocation dim = level.dimension().location();
        BlockPos minPos = new BlockPos(minX, minY, minZ);
        BlockPos maxPos = new BlockPos(maxX, maxY, maxZ);

        List<com.xioyim.spatialblueprint.util.UndoRecord.SnapshotBlock> blocks = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    CompoundTag beNbt = null;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        try { beNbt = be.saveWithFullMetadata(level.registryAccess()); }
                        catch (Exception e) {
                            SpatialBlueprintMod.LOGGER.warn("[SBUndo] BE save failed at {}: {}", pos, e.getMessage());
                        }
                    }
                    blocks.add(new com.xioyim.spatialblueprint.util.UndoRecord.SnapshotBlock(pos, state, beNbt));
                }
            }
        }

        List<com.xioyim.spatialblueprint.util.UndoRecord.SnapshotEntity> entities = new ArrayList<>();
        AABB aabb = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        for (Painting p : level.getEntitiesOfClass(Painting.class, aabb, e -> true)) {
            CompoundTag nbt = new CompoundTag();
            p.save(nbt);
            entities.add(new com.xioyim.spatialblueprint.util.UndoRecord.SnapshotEntity("minecraft:painting", nbt));
        }
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, aabb, e -> true)) {
            String type = (f instanceof GlowItemFrame) ? "minecraft:glow_item_frame" : "minecraft:item_frame";
            CompoundTag nbt = new CompoundTag();
            f.save(nbt);
            entities.add(new com.xioyim.spatialblueprint.util.UndoRecord.SnapshotEntity(type, nbt));
        }

        if (SpatialBlueprintMod.LOGGER.isDebugEnabled()) SpatialBlueprintMod.LOGGER.debug("[SBUndo] Snapshot: {} blocks, {} entities in [{},{},{}]~[{},{},{}]",
                blocks.size(), entities.size(), minX, minY, minZ, maxX, maxY, maxZ);
        return new com.xioyim.spatialblueprint.util.UndoRecord(dim, minPos, maxPos, blocks, entities);
    }

    /**
     * 瞬间还原 UndoRecord 记录的区域，相当于原版结构方块的加载效果。
     * 流程：清除当前实体 → 两阶段清空方块 → 还原非依赖方块 → 还原依赖方块 → 召唤实体。
     */
    public static void restoreUndo(ServerLevel level, com.xioyim.spatialblueprint.util.UndoRecord record,
                                   @Nullable ServerPlayer player) {
        BlockPos min = record.minPos();
        BlockPos max = record.maxPos();
        AABB aabb = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);

        int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
                | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_MOVE_BY_PISTON;
        BlockState air = Blocks.AIR.defaultBlockState();

        // ① 移除当前区域内的装饰实体
        level.getEntitiesOfClass(Painting.class, aabb, e -> true)
                .forEach(e -> e.remove(Entity.RemovalReason.DISCARDED));
        level.getEntitiesOfClass(ItemFrame.class, aabb, e -> true)
                .forEach(e -> e.remove(Entity.RemovalReason.DISCARDED));

        // ② 先清除依赖方块（火把/红石等），避免支撑消失时级联更新掉落
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && BlueprintData.isDependent(state)) {
                        level.removeBlockEntity(pos);
                        level.setBlock(pos, air, clearFlags);
                    }
                }
            }
        }

        // ③ 清除剩余方块（含 RenderBlock 占位，虽然动画已结束，保险起见）
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.removeBlockEntity(pos);
                        level.setBlock(pos, air, clearFlags);
                    }
                }
            }
        }

        // ④ 还原非依赖方块（先放支撑块）
        for (com.xioyim.spatialblueprint.util.UndoRecord.SnapshotBlock sb : record.blocks()) {
            if (sb.state().isAir() || BlueprintData.isDependent(sb.state())) continue;
            level.setBlock(sb.pos(), sb.state(), 3);
            if (sb.beNbt() != null) {
                BlockEntity be = level.getBlockEntity(sb.pos());
                if (be != null) {
                    try { be.loadWithComponents(sb.beNbt(), level.registryAccess()); be.setChanged(); }
                    catch (Exception e) {
                        SpatialBlueprintMod.LOGGER.warn("[SBUndo] BE restore failed at {}: {}", sb.pos(), e.getMessage());
                    }
                }
            }
        }

        // ⑤ 还原依赖方块（支撑块已到位）
        for (com.xioyim.spatialblueprint.util.UndoRecord.SnapshotBlock sb : record.blocks()) {
            if (sb.state().isAir() || !BlueprintData.isDependent(sb.state())) continue;
            level.setBlock(sb.pos(), sb.state(), 3);
            if (sb.beNbt() != null) {
                BlockEntity be = level.getBlockEntity(sb.pos());
                if (be != null) {
                    try { be.loadWithComponents(sb.beNbt(), level.registryAccess()); be.setChanged(); }
                    catch (Exception e) {
                        SpatialBlueprintMod.LOGGER.warn("[SBUndo] Dependent BE restore failed at {}: {}", sb.pos(), e.getMessage());
                    }
                }
            }
        }

        // ⑥ 召唤实体（画、展示框）
        int entityCount = 0;
        for (com.xioyim.spatialblueprint.util.UndoRecord.SnapshotEntity se : record.entities()) {
            try {
                CompoundTag nbt = se.nbt().copy();
                // 移除旧 UUID 避免与世界内已有实体冲突
                nbt.remove("UUID");
                EntityType.create(nbt, level).ifPresent(e -> {
                    level.addFreshEntity(e);
                });
                entityCount++;
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[SBUndo] Entity restore failed ({}): {}", se.entityType(), e.getMessage());
            }
        }

        if (isDbg(player)) SpatialBlueprintMod.LOGGER.info("[SBUndo] Restored {} blocks, {} entities in {}",
                record.blocks().size(), entityCount, record.dimension());
    }
}
