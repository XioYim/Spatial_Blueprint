package com.xioyim.spatialblueprint.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端缓存：RenderBlock 被移除（onRemove）时保存 targetBlock，供破坏粒子使用
 * （levelEvent 2001 到达时 BlockEntity 已被移除，必须提前缓存）。
 *
 * <p>纯逻辑（不引用 net.minecraft.client），GameTest 可测。1.20.1 的静态表从未清过 ——
 * 这里有意纳入断线清缓存（{@link SpatialBlueprintClient#clearAllCaches()} 第 13 个）。</p>
 */
public final class RenderBlockParticleCache {

    /** 位置 → 目标方块（破坏粒子纹理来源）。 */
    private static final Map<BlockPos, BlockState> CACHE = new HashMap<>();

    private RenderBlockParticleCache() {}

    public static void put(BlockPos pos, BlockState target) {
        CACHE.put(pos.immutable(), target);
    }

    /** 取出并移除；没有缓存返回 null。 */
    @Nullable
    public static BlockState remove(BlockPos pos) {
        return CACHE.remove(pos);
    }

    public static void clear() {
        CACHE.clear();
    }
}
