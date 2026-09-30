package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class S2C_OperationResult implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_OperationResult> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_operation_result"));

    public static final StreamCodec<FriendlyByteBuf, S2C_OperationResult> STREAM_CODEC =
            StreamCodec.ofMember(S2C_OperationResult::encode, S2C_OperationResult::decode);

    public final boolean success;
    public final String messageKey;
    public final String detail;
    public final Object[] detailArgs;

    /** A3：decode 参数个数上限（正常只有几个；发包方是服务端，纯纵深防御）。 */
    private static final int MAX_DETAIL_ARGS = 16;

    /**
     * A3：读列表计数，超限整包拒收（与 {@code S2C_BlueprintChunkMessage} /
     * {@code S2C_BlueprintDataMessage} 的 {@code DecoderException} 同一口径）。
     */
    public static int checkedCount(FriendlyByteBuf buf, int cap, String what) {
        int n = buf.readVarInt();
        if (n < 0 || n > cap) {
            throw new io.netty.handler.codec.DecoderException(
                    "S2C list count out of range: " + what + " = " + n);
        }
        return n;
    }

    public S2C_OperationResult(boolean success, String messageKey) {
        this(success, messageKey, "", null);
    }

    public S2C_OperationResult(boolean success, String messageKey, String detail) {
        this(success, messageKey, detail, null);
    }

    public S2C_OperationResult(boolean success, String messageKey, String detail, Object[] detailArgs) {
        this.success = success;
        this.messageKey = messageKey != null ? messageKey : "";
        this.detail = detail != null ? detail : "";
        this.detailArgs = detailArgs != null ? detailArgs : new Object[0];
    }

    public static S2C_OperationResult success() {
        return new S2C_OperationResult(true, "");
    }

    public static S2C_OperationResult failure(String messageKey) {
        return new S2C_OperationResult(false, messageKey);
    }

    public static S2C_OperationResult failure(String messageKey, String detail) {
        return new S2C_OperationResult(false, messageKey, detail);
    }

    @Override
    public CustomPacketPayload.Type<S2C_OperationResult> type() {
        return TYPE;
    }

    public static void encode(S2C_OperationResult msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.messageKey, 256);
        buf.writeUtf(msg.detail, 256);
        buf.writeVarInt(msg.detailArgs.length);
        for (Object arg : msg.detailArgs) {
            buf.writeUtf(String.valueOf(arg), 64);
        }
    }

    public static S2C_OperationResult decode(FriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        String messageKey = buf.readUtf(256);
        String detail = buf.readUtf(256);
        // A3 纵深防御：异常计数整包拒收，见 checkedCount。
        int argCount = checkedCount(buf, MAX_DETAIL_ARGS, "detailArgs");
        Object[] detailArgs = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            detailArgs[i] = buf.readUtf(64);
        }
        return new S2C_OperationResult(success, messageKey, detail, detailArgs);
    }

    public static void handle(S2C_OperationResult msg, IPayloadContext ctx) {
        // SB-TODO(P6): 客户端横幅 / 聊天提示（成功绿色、失败 ErrorToastScreen，含 detail 精细着色与界面关闭）
    }
}
