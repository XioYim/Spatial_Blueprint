package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：允许客户端打开空置次元蓝图界面。
 *
 * <p>右键空置次元蓝图时，界面不再由客户端自行打开 —— 服务端先校验对应的蓝图文件是否仍然存在，
 * 有效才发本消息。失效时服务端直接把物品换成空白蓝图并发提示，客户端不会开出界面。</p>
 *
 * <p>早先客户端抢先 openScreen、服务端并行判定，结果失效时界面已经开出来了，
 * 提示横幅只是盖在上面，关掉横幅就露出本该被拦截的界面。</p>
 */
public class S2C_OpenVoidScreenMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_OpenVoidScreenMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_open_void_screen_message"));

    /** 空消息：空 encode / decode（与 1.20.1 同）。unit codec 要求解码返回同一实例，不适用。 */
    public static final StreamCodec<FriendlyByteBuf, S2C_OpenVoidScreenMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_OpenVoidScreenMessage::encode, S2C_OpenVoidScreenMessage::decode);

    @Override
    public CustomPacketPayload.Type<S2C_OpenVoidScreenMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_OpenVoidScreenMessage msg, FriendlyByteBuf buf) {}

    public static S2C_OpenVoidScreenMessage decode(FriendlyByteBuf buf) {
        return new S2C_OpenVoidScreenMessage();
    }

    public static void handle(S2C_OpenVoidScreenMessage msg, IPayloadContext ctx) {
        // SB-TODO(P6): 客户端打开空置次元蓝图界面（VoidDimensionalScreen，主手/副手找物品）
    }
}
