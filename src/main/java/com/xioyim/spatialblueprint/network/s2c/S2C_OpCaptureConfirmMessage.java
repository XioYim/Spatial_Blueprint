package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：蓝图区域内检测到危险内容（容器物品、告示牌文字、危险展示框物品），
 * 询问 OP 玩家是否跳过检测、无条件创建蓝图。
 *
 * <p>客户端打开 {@code OpCaptureConfirmScreen}；
 * OP 确认后以相同参数 + {@code opBypass=true} 重新发送 CaptureAreaMessage。</p>
 */
public class S2C_OpCaptureConfirmMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_OpCaptureConfirmMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_op_capture_confirm_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_OpCaptureConfirmMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_OpCaptureConfirmMessage::encode, S2C_OpCaptureConfirmMessage::decode);

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

    @Override
    public CustomPacketPayload.Type<S2C_OpCaptureConfirmMessage> type() {
        return TYPE;
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

    public static void handle(S2C_OpCaptureConfirmMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端打开 OP 捕获确认界面（原 handleClient：mc.setScreen(new gui.OpCaptureConfirmScreen(...))）
    }
}
