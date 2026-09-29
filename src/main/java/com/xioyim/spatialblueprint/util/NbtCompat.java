package com.xioyim.spatialblueprint.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;

/**
 * NBT 坐标兼容：1.20.5 起 {@code NbtUtils.writeBlockPos} 写成长度 3 的 IntArrayTag，
 * {@code NbtUtils.readBlockPos(CompoundTag, String)} 只认那种格式；
 * 而 1.20.1 线存盘的 fixedStartPos / fixedEndPos / relP1 / relP2 / origin / size / anchor
 * 全是 {@code {X:, Y:, Z:}} 复合标签。
 *
 * <p>本类读写一律用 1.20.1 的 {@code {X, Y, Z}} 复合标签格式（两条线的文件格式保持一致，
 * 也不用等 P4 的 DataFixer），读的时候同时接受 int 数组格式。</p>
 */
public final class NbtCompat {

    private NbtCompat() {}

    /** 写 1.20.1 的 {@code {X, Y, Z}} 复合标签。 */
    public static CompoundTag writeBlockPos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    /**
     * 从父标签读坐标，同时接受 {@code {X, Y, Z}} 复合标签与长度 3 的 int 数组。
     *
     * @return 缺键或格式不对时返回 {@code null}（调用方原来的缺键语义不变：
     *         凡原代码依赖 1.20.1 的缺键回 {@code (0,0,0)}，调用方写
     *         {@code Objects.requireNonNullElse(..., BlockPos.ZERO)} 保持原行为）
     */
    @Nullable
    public static BlockPos readBlockPos(CompoundTag parent, String key) {
        if (!parent.contains(key)) return null;
        Tag tag = parent.get(key);
        if (tag instanceof IntArrayTag ints) {
            int[] arr = ints.getAsIntArray();
            if (arr.length == 3) return new BlockPos(arr[0], arr[1], arr[2]);
            return null;
        }
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("X") && compound.contains("Y") && compound.contains("Z")) {
                return new BlockPos(compound.getInt("X"), compound.getInt("Y"), compound.getInt("Z"));
            }
            return null;
        }
        return null;
    }
}
