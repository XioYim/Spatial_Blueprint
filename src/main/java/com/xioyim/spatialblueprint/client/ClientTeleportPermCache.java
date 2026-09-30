package com.xioyim.spatialblueprint.client;

import net.minecraft.network.chat.Component;

/**
 * 客户端缓存：本玩家能否使用蓝图传送。
 *
 * <p>由 {@code S2C_TeleportPermMessage} 在登录、权限变更、切换游戏模式时推送。
 * 界面只用它决定按钮置灰与 tooltip 文案；真正的拦截在服务端，改包也没用。</p>
 */
public final class ClientTeleportPermCache {

    /**
     * 默认 {@code false}：同步包到达前按「无权限」显示。
     *
     * <p>登录时服务端就会推一次，玩家打开界面时通常早已到位。宁可短暂显示为不可传送，
     * 也不要给出一个点了没反应的按钮。</p>
     */
    private static boolean allowed = false;

    /**
     * 传送冷却：点下 [Tp] 起算 6 秒，工程 / 次元两类蓝图共用（都是把玩家挪走，防连点刷包）。
     *
     * <p>e77 由 3 秒加到 6 秒：落地保护效果持续 5 秒，冷却必须比效果长，否则连点就能刷出常驻。
     * 只在客户端置灰按钮；真正的拦截在服务端同口径强制（见 TeleportPermissions），改包也没用。</p>
     */
    private static final long COOLDOWN_MS = 6000L;
    private static long cooldownUntil = 0L;

    private ClientTeleportPermCache() {}

    public static boolean canTeleport() {
        return allowed;
    }

    public static void set(boolean value) {
        allowed = value;
    }

    /** 发出传送请求那一刻调用，开始 6 秒冷却。 */
    public static void markTeleportRequested() {
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
    }

    /** 剩余冷却毫秒；0 = 不在冷却。 */
    public static long cooldownRemainingMs() {
        return Math.max(0L, cooldownUntil - System.currentTimeMillis());
    }

    /** 冷却中的 [Tp] tooltip：「冷却中：N 秒」，N 向上取整；两个界面共用一处文案。 */
    public static Component cooldownTooltip() {
        long secs = (cooldownRemainingMs() + 999L) / 1000L;
        return Component.translatable("spatial_blueprint.gui.teleport.cooldown", secs);
    }

    /**
     * 断线 / 退出世界时复位，避免把上一个存档的权限带到下一个。
     *
     * <p>不复位的话，换服后传送按钮仍是亮的，点下去要等服务端拒绝才知道没权限。</p>
     */
    public static void clear() {
        allowed = false;
        cooldownUntil = 0L;
    }
}
