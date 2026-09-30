package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;

import javax.annotation.Nullable;

/** 客户端缓存：当前主手项目蓝图的实时条目状态。 */
public final class ClientProjectStatusCache {

    private ClientProjectStatusCache() {}

    @Nullable private static S2C_ProjectStatusMessage cached = null;
    private static int version = 0;
    private static long receivedAtMillis = 0L;

    public static void request() {
        cached = null;
        receivedAtMillis = 0L;
        BlueprintNetwork.sendToServer(new C2S_RequestProjectStatusMessage());
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
        // SB-TODO(P5): 预览渲染接线（ClientProjectPreview.onStatus / ClientSubProjectPreview.onStatus，SBN-016 / 017）
    }

    public static void clear() {
        cached = null;
        receivedAtMillis = 0L;
        // SB-TODO(P5): ClientSubProjectPreview.clear()（预览渲染，SBN-017）
    }
}
