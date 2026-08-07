package com.xioyim.spatialblueprint.util;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 4-state fluid capture filter for the blank blueprint screen.
 * Controls which fluids are excluded from blueprint data at capture time.
 */
public enum FluidCaptureFilter {
    /** ♨ 白 — 捕捉全部流体（默认） */
    ALL,
    /** ♨ 蓝划线 — 跳过水方块 */
    NO_WATER,
    /** ♨ 橙划线 — 跳过岩浆方块 */
    NO_LAVA,
    /** ♨ 红划线 — 跳过全部流体 */
    NONE;

    public FluidCaptureFilter next() {
        FluidCaptureFilter[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    /** 此方块在当前捕捉过滤下是否应跳过（不录入蓝图）。 */
    public boolean shouldSkip(BlockState state) {
        boolean isFluid = !state.getFluidState().isEmpty();
        if (!isFluid) return false;
        return switch (this) {
            case ALL -> false;
            case NO_WATER -> state.getFluidState().is(FluidTags.WATER);
            case NO_LAVA  -> state.getFluidState().is(FluidTags.LAVA);
            case NONE     -> true;
        };
    }

    /** 序列化为 byte 用于网络包。 */
    public byte toByte() { return (byte) ordinal(); }

    public static FluidCaptureFilter fromByte(byte b) {
        FluidCaptureFilter[] v = values();
        int i = b & 0xFF;
        return i < v.length ? v[i] : ALL;
    }
}
