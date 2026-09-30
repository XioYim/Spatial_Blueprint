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

    /**
     * 越界回退 {@link #SAFE}（最低特权）：读蓝图文件 / 物品组件用。
     *
     * <p>文件与旧物品内联数据里的 saveMode 越界（如 99）时，{@link #fromOrdinal} 抛越界让整份蓝图读不出来（e17 坐实，用户拍板改回退）。
     * C2S 解码仍用 {@link #fromOrdinal}：越界即整包拒收，与 1.20.1 一致。</p>
     */
    public static CaptureMode fromOrdinalOrSafe(int ordinal) {
        CaptureMode[] modes = values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : SAFE;
    }
}
