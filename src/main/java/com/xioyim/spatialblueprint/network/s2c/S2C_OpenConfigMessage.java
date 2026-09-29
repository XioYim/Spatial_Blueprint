package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端 → 客户端：让玩家打开 Spatial Blueprint 游戏内配置主页面（/sb config）。 */
public class S2C_OpenConfigMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_OpenConfigMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_open_config_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_OpenConfigMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_OpenConfigMessage::encode, S2C_OpenConfigMessage::decode);

    public S2C_OpenConfigMessage() {}

    public static void encode(S2C_OpenConfigMessage msg, FriendlyByteBuf buf) {}

    public static S2C_OpenConfigMessage decode(FriendlyByteBuf buf) {
        return new S2C_OpenConfigMessage();
    }

    @Override
    public CustomPacketPayload.Type<S2C_OpenConfigMessage> type() {
        return TYPE;
    }

    public static void handle(S2C_OpenConfigMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端打开 Spatial Blueprint 游戏内配置主页面（SpatialBlueprintConfigScreen，/sb config）
    }
}
