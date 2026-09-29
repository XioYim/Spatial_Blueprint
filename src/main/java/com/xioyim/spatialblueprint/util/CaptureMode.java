package com.xioyim.spatialblueprint.util;

/**
 * 蓝图捕获模式：
 * <ul>
 *   <li>{@link #SAFE}    普通安全模式：普通/创造玩家拒绝非法方块、净化危险 NBT，OP 放行</li>
 *   <li>{@link #SANITIZE} 净化模式：所有玩家移除非法方块、净化危险 NBT、移除盔甲架装备与展示框物品</li>
 *   <li>{@link #FORCE}    强制捕获模式（仅 OP）：跳过净化完整捕获，受硬编码上限约束</li>
 * </ul>
 */
public enum CaptureMode {
    SAFE,
    SANITIZE,
    FORCE;

    public static CaptureMode fromOrdinal(int ordinal) {
        return values()[ordinal];
    }
}
