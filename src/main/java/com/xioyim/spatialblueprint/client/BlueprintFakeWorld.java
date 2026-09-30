package com.xioyim.spatialblueprint.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;
import java.util.HashMap;

/**
 * Minimal BlockAndTintGetter for ghost-preview rendering.
 * Stores blueprint blocks at relative coords; returns full-bright so the VBO
 * does not depend on the placement origin and never needs light-driven rebuilds.
 */
public class BlueprintFakeWorld implements BlockAndTintGetter {
    private final HashMap<Long, BlockState> blocks;
    private final net.minecraft.world.level.Level realWorld;
    private final net.minecraft.core.BlockPos origin;

    public BlueprintFakeWorld(net.minecraft.world.level.Level realWorld,
                               net.minecraft.core.BlockPos origin,
                               HashMap<Long, BlockState> blocks) {
        this.realWorld = realWorld;
        this.origin = origin;
        this.blocks = blocks;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState s = blocks.get(pos.asLong());
        return s != null ? s : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) { return null; }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }

    @Override
    public int getRawBrightness(BlockPos pos, int skyDarken) { return 15; }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) return 1.0f;
        return switch (direction) {
            case DOWN            -> 0.5f;
            case NORTH, SOUTH   -> 0.8f;
            case WEST, EAST     -> 0.6f;
            default             -> 1.0f;
        };
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return realWorld.getBlockTint(pos.offset(origin), colorResolver);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return realWorld.getLightEngine();
    }

    @Override
    public int getHeight() { return realWorld.getHeight(); }

    @Override
    public int getMinBuildHeight() { return realWorld.getMinBuildHeight(); }
}
