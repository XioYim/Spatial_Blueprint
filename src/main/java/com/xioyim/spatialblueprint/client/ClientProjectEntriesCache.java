package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectEntriesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * 客户端缓存：项目条目清单（序号 + 标签 + 作者参考系），供「创建子工程蓝图」页
 * 自动填序号与即时查重。
 *
 * <p>用法：打开子工程页时 {@link #requestFor}，随后界面跟随 {@link #version()}
 * 重建（与 {@link ClientLibraryCache} 同一套模式）；断开连接时清空。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientProjectEntriesCache {

    private ClientProjectEntriesCache() {}

    /** 最近一次收到的回复；{@code null} = 尚未收到。 */
    @Nullable private static S2C_ProjectEntriesMessage cached = null;
    /** 每次收到回复自增 —— 界面据此检测数据到达并重建控件。 */
    private static int version = 0;

    /** 版本号；变化即表示服务端推来了新数据。 */
    public static int version() {
        return version;
    }

    /** 向服务端请求某项目的最新条目清单。 */
    public static void requestFor(String projectId) {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestProjectEntriesMessage(projectId));
    }

    /**
     * 取缓存；缓存里是<b>别的项目</b>（或还没收到过）时返回 {@code null}。
     */
    @Nullable
    public static S2C_ProjectEntriesMessage getFor(String projectId) {
        if (cached == null || projectId == null) return null;
        return projectId.equals(cached.projectId) ? cached : null;
    }

    /** 由 {@code S2C_ProjectEntriesMessage} 写入。 */
    public static void onReceive(S2C_ProjectEntriesMessage msg) {
        cached = msg;
        version++;
    }

    /** 断开连接时清掉，免得把 A 服的项目条目带到 B 服的界面里。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cached = null;
    }
}
