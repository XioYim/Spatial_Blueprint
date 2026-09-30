package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S：配置界面打开时向服务端索取当前配置值。
 *
 * <p>仅 OP 可用 —— 配置里有体积上限、槽位上限这类玩家不该看到具体数字的东西，
 * 而且这个界面的下一步就是提交修改。</p>
 */
public class C2S_RequestConfigMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_RequestConfigMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_request_config_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestConfigMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_RequestConfigMessage::encode, C2S_RequestConfigMessage::decode);

    public static void encode(C2S_RequestConfigMessage msg, FriendlyByteBuf buf) {}

    public static C2S_RequestConfigMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestConfigMessage();
    }

    @Override
    public CustomPacketPayload.Type<C2S_RequestConfigMessage> type() {
        return TYPE;
    }

    public static void handle(C2S_RequestConfigMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_RequestConfigMessage msg, ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) return;
        S2C_ConfigValuesMessage.sendTo(player);
    }
}
