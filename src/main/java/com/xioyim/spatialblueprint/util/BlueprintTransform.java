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
}
