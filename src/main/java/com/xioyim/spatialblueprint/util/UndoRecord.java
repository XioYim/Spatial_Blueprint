package com.xioyim.spatialblueprint.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 一次空间蓝图放置前的区域快照，用于 /sb undo 还原。
 * 仅记录创造模式玩家放置空间蓝图时的旧区域状态。
 */
public record UndoRecord(
        ResourceLocation dimension,
        BlockPos minPos,
        BlockPos maxPos,
        List<SnapshotBlock> blocks,
        List<SnapshotEntity> entities
) {
    /** 单个方块快照（仅存非空气方块，空气通过清空包围盒隐式还原）。 */
    public record SnapshotBlock(
            BlockPos pos,
            BlockState state,
            @Nullable CompoundTag beNbt  // BlockEntity NBT，无则为 null
    ) {}

    /** 单个装饰实体快照（画、展示框等）。 */
    public record SnapshotEntity(
            String entityType,          // 注册表 ID，如 "minecraft:painting"
            CompoundTag nbt             // 完整实体 NBT
    ) {}
}
