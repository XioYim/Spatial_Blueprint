package com.xioyim.spatialblueprint.network.s2c;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：登录时把「本玩家是否能在服务器上打开/编辑配置」的结论推给客户端。
 *
 * <p>配置界面读写的是服务端配置（OP 专属），客户端自己算不出自己是不是 OP ——
 * 不推这份结论，非 OP 也能点开 Mod 列表里的 Config 按钮，看到一个读着本地 toml、
 * 保存又被服务端拒绝的假页面。真正的拦截仍在服务端（C2S 权限校验），这里只是别让页面出现。</p>
 */
public class S2C_ConfigPermMessage {

    private final boolean allowed;

    public S2C_ConfigPermMessage(boolean allowed) {
        this.allowed = allowed;
    }

    public static void encode(S2C_ConfigPermMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.allowed);
    }

    public static S2C_ConfigPermMessage decode(FriendlyByteBuf buf) {
        return new S2C_ConfigPermMessage(buf.readBoolean());
    }

    public static void handle(S2C_ConfigPermMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2C_ConfigPermMessage msg) {
        com.xioyim.spatialblueprint.client.ClientConfigPermCache.set(msg.allowed);
    }
}
