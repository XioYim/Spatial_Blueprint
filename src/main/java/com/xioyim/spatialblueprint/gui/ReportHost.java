package com.xioyim.spatialblueprint.gui;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * 检测报告页的宿主：两点的来源 + 报告确认后的出口，
 * 供 {@link BlueprintReportScreen} 解耦 {@link PointSelectionScreen} 复用：
 * 空白蓝图的选点界面（默认）与「创建子工程蓝图」界面各实现一份，写法对照 {@link PointHost}。
 */
public interface ReportHost {

    @Nullable BlockPos point1();

    @Nullable BlockPos point2();

    /** 报告页确认。mode 来自三个模式按钮；targetCategory 只有 OP 覆盖按钮会传别的值。 */
    void onReportConfirmed(com.xioyim.spatialblueprint.util.CaptureMode mode, String targetCategory);
}
