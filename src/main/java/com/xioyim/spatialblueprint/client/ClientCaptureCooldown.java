package com.xioyim.spatialblueprint.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端侧的捕获冷却计时。
 *
 * <p>冷却<b>只由服务端在一次真正成功的创建之后</b>推过来（见
 * {@code S2C_CaptureCooldownMessage}），客户端自己不再猜。取消创建、体积超限、
 * 槽位已满都收不到这个包，因此不会误进冷却；OP 服务端根本不发。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientCaptureCooldown {

    private ClientCaptureCooldown() {}

    /** 冷却结束时刻（客户端毫秒时间戳）；0 表示不在冷却中。 */
    private static long endMs = 0L;

    public static void start(int seconds) {
        endMs = System.currentTimeMillis() + Math.max(0, seconds) * 1000L;
    }

    /** 断开连接时清掉，免得换服 / 换档之后还挂着上一局的冷却。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        endMs = 0L;
    }

    /** 剩余毫秒；不在冷却中返回 0 或负数。 */
    public static long remainingMs() {
        return endMs - System.currentTimeMillis();
    }

    public static boolean onCooldown() {
        return remainingMs() > 0;
    }

    /** 剩余秒数（向上取整），供界面显示。 */
    public static int remainingSeconds() {
        return (int) Math.ceil(remainingMs() / 1000.0);
    }
}
