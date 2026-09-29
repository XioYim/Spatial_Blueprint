package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/** 客户端缓存：当前主手项目蓝图的实时条目状态。 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientProjectStatusCache {

    private ClientProjectStatusCache() {}

    @Nullable private static S2C_ProjectStatusMessage cached = null;
    private static int version = 0;
    private static long receivedAtMillis = 0L;

    public static void request() {
        cached = null;
        receivedAtMillis = 0L;
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestProjectStatusMessage());
    }

    @Nullable
    public static S2C_ProjectStatusMessage get() {
        return cached;
    }

    public static int version() {
        return version;
    }

    public static long receivedAtMillis() {
        return receivedAtMillis;
    }

    public static void onReceive(S2C_ProjectStatusMessage msg) {
        cached = msg;
        receivedAtMillis = System.currentTimeMillis();
        version++;
        ClientProjectPreview.onStatus(msg);
        ClientSubProjectPreview.onStatus(msg);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cached = null;
        receivedAtMillis = 0L;
        ClientSubProjectPreview.clear();
    }
}
