package com.xioyim.spatialblueprint.network.s2c;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：玩家登录时同步捕捉体积上限配置。
 * 客户端使用 {@link #clientMaxVolCreative} / {@link #clientMaxVolSurvival}
 * 驱动 PointSelectionScreen 的区域大小校验，替代原来的硬编码 100000。
 */
public class S2C_ConfigSyncMessage {

    /**
     * 客户端缓存 — 登录时由服务端写入；默认值与配置文件默认值一致，
     * 确保在握手包到达之前或单人模式下行为正确。
     */
    public static volatile int clientMaxVolCreative = 120_000;
    public static volatile int clientMaxVolSurvival = 80_000;

    private final int maxVolCreative;
    private final int maxVolSurvival;

    public S2C_ConfigSyncMessage(int maxVolCreative, int maxVolSurvival) {
        this.maxVolCreative = maxVolCreative;
        this.maxVolSurvival = maxVolSurvival;
    }

    public static void encode(S2C_ConfigSyncMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.maxVolCreative);
        buf.writeInt(msg.maxVolSurvival);
    }

    public static S2C_ConfigSyncMessage decode(FriendlyByteBuf buf) {
        return new S2C_ConfigSyncMessage(buf.readInt(), buf.readInt());
    }

    public static void handle(S2C_ConfigSyncMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(S2C_ConfigSyncMessage msg) {
        clientMaxVolCreative = msg.maxVolCreative;
        clientMaxVolSurvival = msg.maxVolSurvival;
    }
}
