package com.xioyim.spatialblueprint.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端侧的当前存档身份缓存。
 *
 * <p>存档 UUID 只有服务端算得出（它存在世界数据里），客户端在登录时收到一份。
 * 工程 / 次元蓝图右键时靠它就地判断「这张蓝图是不是本档的」，
 * 不用等一个来回的网络请求 —— 服务端那边还有一道权威校验兜底。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientWorldIdentity {

    private ClientWorldIdentity() {}

    private static String uuid = "";
    private static String name = "";

    public static void set(String worldUuid, String worldName) {
        uuid = worldUuid == null ? "" : worldUuid;
        name = worldName == null ? "" : worldName;
    }

    /** 断开连接时清掉，避免上一个存档的身份被下一个存档误用。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        uuid = "";
        name = "";
    }

    public static String uuid() { return uuid; }

    public static String name() { return name; }

    /**
     * 该蓝图记录的存档 UUID 是否属于当前存档。
     *
     * <p>蓝图没记 UUID（本机制加入前创建的）时返回 {@code true} —— 不去为难旧数据。
     * 还没收到服务端推送时同样返回 {@code true}，宁可漏判也不能凭空销毁玩家的蓝图。</p>
     */
    public static boolean belongsToCurrentWorld(String worldUuid) {
        if (worldUuid == null || worldUuid.isEmpty()) return true;
        if (uuid.isEmpty()) return true;
        // 同服务端版：逐蓝图调用，曾经的调试 INFO 已删，防刷屏
        return uuid.equals(worldUuid);
    }
}
