package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：蓝图区域内检测到危险内容（容器物品、告示牌文字、危险展示框物品），
 * 询问 OP 玩家是否跳过检测、无条件创建蓝图。
 *
 * <p>客户端打开 {@link com.xioyim.spatialblueprint.gui.OpCaptureConfirmScreen}；
 * OP 确认后以相同参数 + {@code opBypass=true} 重新发送 CaptureAreaMessage。</p>
 */
public class S2C_OpCaptureConfirmMessage {

    public final String name;
    public final boolean clearAfterCapture;
    public final int durationSeconds;
    public final FluidCaptureFilter fluidCaptureFilter;

    public S2C_OpCaptureConfirmMessage(String name, boolean clearAfterCapture,
                                       int durationSeconds, FluidCaptureFilter fluidCaptureFilter) {
        this.name = name;
        this.clearAfterCapture = clearAfterCapture;
        this.durationSeconds = durationSeconds;
        this.fluidCaptureFilter = fluidCaptureFilter;
    }

    public static void encode(S2C_OpCaptureConfirmMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, 256);
        buf.writeBoolean(msg.clearAfterCapture);
        buf.writeInt(msg.durationSeconds);
        buf.writeByte(msg.fluidCaptureFilter.toByte());
    }

    public static S2C_OpCaptureConfirmMessage decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(256);
        boolean clear = buf.readBoolean();
        int dur = buf.readInt();
        FluidCaptureFilter filter = FluidCaptureFilter.fromByte(buf.readByte());
        return new S2C_OpCaptureConfirmMessage(name, clear, dur, filter);
    }

    public static void handle(S2C_OpCaptureConfirmMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2C_OpCaptureConfirmMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new com.xioyim.spatialblueprint.gui.OpCaptureConfirmScreen(
                msg.name, msg.clearAfterCapture, msg.durationSeconds, msg.fluidCaptureFilter));
    }
}
