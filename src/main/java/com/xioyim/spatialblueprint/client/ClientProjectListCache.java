package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端缓存：导入项目界面三个 tab 各一份项目清单。
 *
 * <p>每次点 tab 都 {@link #request}，但同一 tab 2 秒内只发一次（服务端另有同样的冷却，
 * 这里挡住的是正常点击，那边挡住的是刷包）；冷却内切 tab 直接显示缓存。
 * 界面跟随 {@link #version()} 重建；登出时整份清空（T4）。</p>
 */
public final class ClientProjectListCache {

    private ClientProjectListCache() {}

    /** 与服务端 {@link C2S_RequestProjectListMessage#COOLDOWN_MS} 一致。 */
    private static final long COOLDOWN_MS = C2S_RequestProjectListMessage.COOLDOWN_MS;

    private static final Map<String, List<S2C_ProjectListMessage.ProjectSummary>> cached = new HashMap<>();
    private static final Map<String, Long> lastRequestAt = new HashMap<>();
    /** 正在等回包的 tab；回包到达或登出后清掉。 */
    private static final Map<String, Boolean> loading = new HashMap<>();
    private static int version = 0;

    public static int version() {
        return version;
    }

    /**
     * 请求某 tab 的清单。
     *
     * @return true = 真的发了包；false = 还在冷却里，界面沿用缓存
     */
    public static boolean request(String tab) {
        long now = System.currentTimeMillis();
        Long last = lastRequestAt.get(tab);
        if (last != null && now - last < COOLDOWN_MS) return false;
        lastRequestAt.put(tab, now);
        loading.put(tab, Boolean.TRUE);
        BlueprintNetwork.sendToServer(new C2S_RequestProjectListMessage(tab));
        return true;
    }

    /** 距该 tab 下次可请求还有多少毫秒；0 = 现在就能发。 */
    public static long cooldownRemaining(String tab) {
        Long last = lastRequestAt.get(tab);
        if (last == null) return 0L;
        return Math.max(0L, COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    /** 该 tab 的缓存；null = 从没收到过。 */
    @Nullable
    public static List<S2C_ProjectListMessage.ProjectSummary> get(String tab) {
        return cached.get(tab);
    }

    public static boolean isLoading(String tab) {
        return Boolean.TRUE.equals(loading.get(tab));
    }

    public static void onReceive(S2C_ProjectListMessage msg) {
        cached.put(msg.tab, msg.projects);
        loading.remove(msg.tab);
        version++;
    }

    /** 换服后不能带着上个服的清单；冷却时刻一并清，否则新服头两秒点 tab 会被自己挡住。 */
    public static void clear() {
        cached.clear();
        lastRequestAt.clear();
        loading.clear();
        version++;
    }
}
