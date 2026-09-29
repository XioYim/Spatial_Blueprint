package com.xioyim.spatialblueprint.util;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 4-state fluid display/filter mode for the placement screen.
 * Cycle: SHOW_ALL → HIDE_VANILLA → HIDE_VANILLA_FILTER → HIDE_ALL_FILTER
 */
public enum FluidDisplayMode {
    /** ⛖ 白 — 显示全部流体，不影响构建 */
    SHOW_ALL,
    /** ⛗ 白 — 隐藏水+岩浆（原版流体），不影响构建 */
    HIDE_VANILLA,
    /** ⛖ 红 — 隐藏水+岩浆，放置时跳过水+岩浆 */
    HIDE_VANILLA_FILTER,
    /** ♨ 红 — 隐藏全部流体（含 mod 流体），放置时跳过全部流体 */
    HIDE_ALL_FILTER;

    public FluidDisplayMode next() {
        FluidDisplayMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public boolean hidesFluid(BlockState state) {
        boolean isVanilla = state.getFluidState().is(FluidTags.WATER)
                         || state.getFluidState().is(FluidTags.LAVA);
        boolean isAnyFluid = !state.getFluidState().isEmpty();
        return switch (this) {
            case SHOW_ALL -> false;
            case HIDE_VANILLA, HIDE_VANILLA_FILTER -> isVanilla;
            case HIDE_ALL_FILTER -> isAnyFluid;
        };
    }

    public boolean filtersFromBuild(BlockState state) {
        if (!affectsBuild()) return false;
        boolean isVanilla = state.getFluidState().is(FluidTags.WATER)
                         || state.getFluidState().is(FluidTags.LAVA);
        boolean isAnyFluid = !state.getFluidState().isEmpty();
        return switch (this) {
            case HIDE_ALL_FILTER -> isAnyFluid;
            case HIDE_VANILLA_FILTER -> isVanilla;
            default -> false;
        };
    }

    public boolean affectsBuild() {
        return this == HIDE_VANILLA_FILTER || this == HIDE_ALL_FILTER;
    }

    public byte toByte() { return (byte) ordinal(); }

    public static FluidDisplayMode fromByte(byte b) {
        FluidDisplayMode[] v = values();
        int i = b & 0xFF;
        return i < v.length ? v[i] : SHOW_ALL;
    }
}
