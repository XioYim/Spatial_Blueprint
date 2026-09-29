package com.xioyim.spatialblueprint.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端持有的<b>服务端配置</b>镜像 —— 配置界面读写的是它，不是客户端本地的 toml。
 *
 * <p>配置是 COMMON 型的，两端各有一份文件。以前 {@code /sb config} 打开的界面直接读写
 * 客户端那份，服主在服务器上改了半天，服务器完全不知道。现在流程是：</p>
 *
 * <pre>
 *   打开界面 → C2S_RequestConfigMessage
 *            ← S2C_ConfigValuesMessage（服务端当前值）→ 存进本镜像
 *   编辑     → 只改镜像里的 pending
 *   点保存   → C2S_ApplyConfigMessage（只发改过的项）
 *            ← 服务端校验 OP、按 spec 夹范围、落盘、热重载，再回推一次新值
 * </pre>
 *
 * <p>没收到快照之前（单机刚进世界、或服务端不带本 mod）读取会退回本地配置值，
 * 界面不会因为一次网络延迟就显示成一堆 0。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientConfigMirror {

    private ClientConfigMirror() {}

    /** 服务端当前值（收到快照后填充）。 */
    private static final Map<String, Integer>        serverInts  = new HashMap<>();
    private static final Map<String, Boolean>        serverBools = new HashMap<>();
    private static final Map<String, List<String>>   serverLists = new HashMap<>();
    /** 玩家改过、尚未提交的值。 */
    private static final Map<String, Integer>        pendingInts  = new HashMap<>();
    private static final Map<String, Boolean>        pendingBools = new HashMap<>();
    private static final Map<String, List<String>>   pendingLists = new HashMap<>();

    private static boolean received = false;
    /** 每次收到快照自增 —— UI 用它检测快照是否刚到、需要刷新显示。 */
    private static int snapshotVersion = 0;

    /** 是否已经拿到服务端快照。没拿到时界面应显示为只读或退回本地值。 */
    public static boolean hasSnapshot() {
        return received;
    }

    /** 当前快照版本号；每次 {@link #onReceive} 调用后自增。 */
    public static int snapshotVersion() {
        return snapshotVersion;
    }

    public static void onReceive(Map<String, Integer> ints, Map<String, Boolean> bools,
                                  Map<String, List<String>> lists) {
        serverInts.clear();
        serverInts.putAll(ints);
        serverBools.clear();
        serverBools.putAll(bools);
        serverLists.clear();
        serverLists.putAll(lists);
        // 快照是权威值：以它为准丢掉本地未提交的编辑
        pendingInts.clear();
        pendingBools.clear();
        pendingLists.clear();
        received = true;
        snapshotVersion++;
    }

    /** 断开连接时清空 —— 免得把 A 服的配置带到 B 服的界面里。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        serverInts.clear();
        serverBools.clear();
        serverLists.clear();
        pendingInts.clear();
        pendingBools.clear();
        pendingLists.clear();
        received = false;
        snapshotVersion = 0;
    }

    // ── 读 ────────────────────────────────────────────────────────────────

    /** 取值优先级：未提交的编辑 → 服务端快照 → 本地配置（兜底）。 */
    public static int getInt(String id, int localFallback) {
        Integer p = pendingInts.get(id);
        if (p != null) return p;
        Integer s = serverInts.get(id);
        return s != null ? s : localFallback;
    }

    public static boolean getBool(String id, boolean localFallback) {
        Boolean p = pendingBools.get(id);
        if (p != null) return p;
        Boolean s = serverBools.get(id);
        return s != null ? s : localFallback;
    }

    /** 取值优先级：pending → 服务端快照 → 本地配置（兜底）。 */
    public static List<String> getList(String id, List<String> localFallback) {
        List<String> p = pendingLists.get(id);
        if (p != null) return new ArrayList<>(p);
        List<String> s = serverLists.get(id);
        return s != null ? new ArrayList<>(s) : localFallback;
    }

    // ── 写（只进 pending，点保存才提交）──────────────────────────────────

    public static void setInt(String id, int value) {
        pendingInts.put(id, value);
    }

    public static void setBool(String id, boolean value) {
        pendingBools.put(id, value);
    }

    public static void setList(String id, List<String> value) {
        pendingLists.put(id, new ArrayList<>(value));
    }

    public static boolean hasPending() {
        return !pendingInts.isEmpty() || !pendingBools.isEmpty() || !pendingLists.isEmpty();
    }

    public static Map<String, Integer> pendingInts() {
        return new HashMap<>(pendingInts);
    }

    public static Map<String, Boolean> pendingBools() {
        return new HashMap<>(pendingBools);
    }

    public static Map<String, List<String>> pendingLists() {
        return new HashMap<>(pendingLists);
    }

    /** 提交之后清空待提交集合 —— 服务端随后会回推一份新快照。 */
    public static void clearPending() {
        pendingInts.clear();
        pendingBools.clear();
        pendingLists.clear();
    }
}
