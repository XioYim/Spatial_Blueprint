package com.xioyim.spatialblueprint.gui;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * 「两点」的来源与写回，供 {@link CoordinateEditScreen} 复用：
 * 空白蓝图的选区（默认）与项目蓝图的范围（{@link ProjectRangeScreen}）各实现一份。
 */
public interface PointHost {

    @Nullable BlockPos point1();

    @Nullable BlockPos point2();

    /** 写回一个点（本地 NBT + 发服务端）。index 1 / 2。 */
    void applyPoint(int index, BlockPos pos);

    /** 客户端粗校验用的体积上限（严格判定在服务端）。 */
    long volumeLimit();

    /** [保存] 按钮的 tooltip；null = 用默认的「各类蓝图体积上限」说明。 */
    @Nullable
    default net.minecraft.network.chat.Component saveTooltip() { return null; }
}
