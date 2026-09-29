package com.xioyim.spatialblueprint.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存冷却追踪，不需要持久化（服务器重启后动画必然已结束）。
 *
 * <p>两种冷却：
 * <ol>
 *   <li>放置冷却：放置蓝图时设为 animDuration + 2 秒，防止在动画进行中撤回。</li>
 *   <li>撤回后冷却：成功执行 /sb undo 后设为 5 秒，防止连续撤回。</li>
 * </ol>
 */
public final class SBUndoCooldown {
    private static final Map<UUID, Long> cooldownUntilMs = new ConcurrentHashMap<>();

    private SBUndoCooldown() {}

    /** 放置蓝图时调用：冷却 = animDurationSeconds + 2 秒。 */
    public static void setPlacementCooldown(UUID playerUUID, int animDurationSeconds) {
        cooldownUntilMs.put(playerUUID, System.currentTimeMillis() + (animDurationSeconds + 2) * 1000L);
    }

    /** 成功撤回后调用：固定 5 秒冷却。 */
    public static void setPostUndoCooldown(UUID playerUUID) {
        cooldownUntilMs.put(playerUUID, System.currentTimeMillis() + 5_000L);
    }

    public static boolean isOnCooldown(UUID playerUUID) {
        Long until = cooldownUntilMs.get(playerUUID);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 返回剩余冷却秒数（向上取整），冷却结束返回 0。 */
    public static int getRemainingSeconds(UUID playerUUID) {
        Long until = cooldownUntilMs.get(playerUUID);
        if (until == null) return 0;
        long remaining = until - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 1000.0);
    }

    /** 玩家登出时清理，防止 map 无限增长。 */
    public static void clearPlayer(UUID playerUUID) {
        cooldownUntilMs.remove(playerUUID);
    }
}
