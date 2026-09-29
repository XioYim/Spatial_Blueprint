package com.xioyim.spatialblueprint.network.s2c;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：允许客户端打开空置次元蓝图界面。
 *
 * <p>右键空置次元蓝图时，界面不再由客户端自行打开 —— 服务端先校验对应的蓝图文件是否仍然存在，
 * 有效才发本消息。失效时服务端直接把物品换成空白蓝图并发提示，客户端不会开出界面。</p>
 *
 * <p>早先客户端抢先 openScreen、服务端并行判定，结果失效时界面已经开出来了，
 * 提示横幅只是盖在上面，关掉横幅就露出本该被拦截的界面。</p>
 */
public class S2C_OpenVoidScreenMessage {

    public static void encode(S2C_OpenVoidScreenMessage msg, FriendlyByteBuf buf) {}

    public static S2C_OpenVoidScreenMessage decode(FriendlyByteBuf buf) {
        return new S2C_OpenVoidScreenMessage();
    }

    public static void handle(S2C_OpenVoidScreenMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) openScreen();
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        net.minecraft.world.item.ItemStack stack = mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem)) {
            stack = mc.player.getOffhandItem();
        }
        if (!(stack.getItem() instanceof com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem)) return;
        mc.setScreen(new com.xioyim.spatialblueprint.gui.VoidDimensionalScreen(stack));
    }
}
