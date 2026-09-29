package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：配置界面打开时向服务端索取当前配置值。
 *
 * <p>仅 OP 可用 —— 配置里有体积上限、槽位上限这类玩家不该看到具体数字的东西，
 * 而且这个界面的下一步就是提交修改。</p>
 */
public class C2S_RequestConfigMessage {

    public static void encode(C2S_RequestConfigMessage msg, FriendlyByteBuf buf) {}

    public static C2S_RequestConfigMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestConfigMessage();
    }

    public static void handle(C2S_RequestConfigMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;
            S2C_ConfigValuesMessage.sendTo(player);
        });
        ctx.get().setPacketHandled(true);
    }
}
