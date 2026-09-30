package com.xioyim.spatialblueprint.server;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 上传暂存：已解析并扫描过、但还没落盘的蓝图。
 *
 * <p>上传流程是两步的 —— 先传文件让服务端扫描出检测报告，玩家看过报告决定
 * 「净化并上传」或「取消」之后才真正保存。这中间那份数据就存在这里。</p>
 *
 * <p><b>为什么必须在服务端扫描</b>：非法方块/物品列表是服务端配置，客户端本地的 toml
 * 在多人服上可能完全不同。客户端自己扫出「没问题」，传上去照样被拒，报告就成了误导。</p>
 *
 * <p>纯内存，不持久化：服务器重启后未确认的上传作废，玩家重传即可。</p>
 */
public final class PendingUploadRegistry {

    /** 暂存有效期。超时后玩家再点确认会被告知重新上传。 */
    private static final long TTL_MS = 5 * 60 * 1000L;

    /**
     * 一份待确认的上传。
     *
     * @param data      已完成前置校验与字段净化的蓝图数据（size / materialList 已由服务端重算）
     * @param scan      扫描结果，与推给客户端的报告内容一致
     * @param createdAt 入库时刻，用于 TTL
     */
    public record Pending(BlueprintData data, BlueprintSanitizer.UploadScan scan, long createdAt) {

        boolean expired(long now) {
            return now - createdAt > TTL_MS;
        }
    }

    /** 每个玩家同时只能有一份待确认上传 —— 再传一个就顶掉旧的。 */
    private static final Map<UUID, Pending> pending = new HashMap<>();

    private PendingUploadRegistry() {}

    public static synchronized void put(UUID player, BlueprintData data,
                                        BlueprintSanitizer.UploadScan scan) {
        purgeExpired();
        pending.put(player, new Pending(data, scan, System.currentTimeMillis()));
    }

    /**
     * 取出并<b>移除</b>该玩家的待确认上传。
     *
     * <p>取出即消费：确认动作只能生效一次，避免同一份数据被重复保存成多个蓝图。</p>
     *
     * @return 无待确认或已超时时返回 {@code null}
     */
    @Nullable
    public static synchronized Pending take(UUID player) {
        purgeExpired();
        Pending p = pending.remove(player);
        if (p == null || p.expired(System.currentTimeMillis())) return null;
        return p;
    }

    /** 玩家主动取消 / 退出登录时清掉。 */
    public static synchronized void clear(UUID player) {
        pending.remove(player);
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expired(now)) it.remove();
        }
    }
}
