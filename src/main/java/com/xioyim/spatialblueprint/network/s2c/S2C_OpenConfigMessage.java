package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.gui.SpatialBlueprintConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端 → 客户端：让玩家打开 Spatial Blueprint 游戏内配置主页面（/sb config）。 */
public class S2C_OpenConfigMessage {

    public S2C_OpenConfigMessage() {}

    public static void encode(S2C_OpenConfigMessage msg, FriendlyByteBuf buf) {}

    public static S2C_OpenConfigMessage decode(FriendlyByteBuf buf) {
        return new S2C_OpenConfigMessage();
    }

    public static void handle(S2C_OpenConfigMessage msg, Supplier<NetworkEvent.Context> ctx) {
        // lambda 体内只能调用带 @OnlyIn 的方法，不能直接碰客户端类型 ——
        // 否则 Screen 会进入本类 synthetic 方法的签名，专用服务端注册消息时就会崩
        ctx.get().enqueueWork(() -> openScreen());
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        // 本消息只能由服务端在 /sb config 命令里发出，而那条命令已带 OP 校验 ——
        // 收到即代表服务端认可，不再用客户端的缓存（可能还没刷新）二次拦截，
        // 否则刚被提升 OP 的玩家会因为登录时的旧缓存而打不开。
        mc.setScreen(new SpatialBlueprintConfigScreen(mc.screen));
    }
}
