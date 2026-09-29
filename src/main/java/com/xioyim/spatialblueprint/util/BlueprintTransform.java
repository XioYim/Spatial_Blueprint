package com.xioyim.spatialblueprint.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/**
 * Shared coordinate-transform helpers for blueprint rotation.
 *
 * <p>These functions were previously duplicated in
 * {@code BlueprintPlacementHandler}, {@code BlueprintSelectionRenderer},
 * and {@code PlacementPreValidator} with identical logic.  Centralising them
 * here means a single place to update if the rotation model ever changes.</p>
 *
 * <p>All three methods mirror the standard Minecraft 90°/180°/270° clockwise
 * rotation about the Y-axis applied to a region of dimensions
 * {@code size.x × size.y × size.z}.</p>
 */
public final class BlueprintTransform {

    private BlueprintTransform() {} // utility class

    /**
     * Transforms the X coordinate of a block position within a blueprint region.
     *
     * @param x        source X (relative to blueprint origin)
     * @param y        source Y (unused — present for signature symmetry)
     * @param z        source Z (relative to blueprint origin)
     * @param size     bounding size of the blueprint region (in blocks)
     * @param rotation rotation to apply
     * @return rotated X offset
     */
    public static int x(int x, int y, int z, BlockPos size, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90        -> size.getZ() - 1 - z;
            case CLOCKWISE_180       -> size.getX() - 1 - x;
            case COUNTERCLOCKWISE_90 -> z;
            default                  -> x;
        };
    }

    /**
     * Transforms the Y coordinate — always identity (rotation is about the Y-axis).
     *
     * @param y source Y
     * @return {@code y} unchanged
     */
    public static int y(int x, int y, int z, BlockPos size, Rotation rotation) {
        return y;
    }

    /**
     * Transforms the Z coordinate of a block position within a blueprint region.
     *
     * @param x        source X (relative to blueprint origin)
     * @param y        source Y (unused — present for signature symmetry)
     * @param z        source Z (relative to blueprint origin)
     * @param size     bounding size of the blueprint region (in blocks)
     * @param rotation rotation to apply
     * @return rotated Z offset
     */
    public static int z(int x, int y, int z, BlockPos size, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90        -> x;
            case CLOCKWISE_180       -> size.getZ() - 1 - z;
            case COUNTERCLOCKWISE_90 -> size.getX() - 1 - x;
            default                  -> z;
        };
    }

    // ── 角点（连续坐标）变换 ──────────────────────────────────────────────────
    //
    // 上面三个是**方块中心**模型：方块索引跑 0..size-1，所以镜像公式带 -1。
    // 盒型实体（Create 超级胶水）的 From/To 是**方块边界上的角点**，坐标跑 0..size，
    // 镜像公式**不带 -1** —— 照抄方块版会让整个盒子偏移一格。
    //
    // 例：size=3 时方块 0 的中心 0.5，转 90° 后应落在 2.5（= 3-1-0 + 0.5）；
    //     而角点 0.0 转 90° 后应落在 3.0（= 3-0），不是 2.0。

    /** 角点 X 变换。{@code size}/{@code rotation} 语义同 {@link #x}，但按连续坐标计算。 */
    public static double cornerX(double x, double z, BlockPos size, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90        -> size.getZ() - z;
            case CLOCKWISE_180       -> size.getX() - x;
            case COUNTERCLOCKWISE_90 -> z;
            default                  -> x;
        };
    }

    /** 角点 Z 变换。 */
    public static double cornerZ(double x, double z, BlockPos size, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90        -> x;
            case CLOCKWISE_180       -> size.getZ() - z;
            case COUNTERCLOCKWISE_90 -> size.getX() - x;
            default                  -> z;
        };
    }
}
