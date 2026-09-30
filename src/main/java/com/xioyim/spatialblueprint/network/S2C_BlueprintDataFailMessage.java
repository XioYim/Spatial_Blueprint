package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: Notifies the client that a blueprint data request failed (rate-limited, not found, load error).
 * The client uses this to transition out of the "downloading" state and show an error with retry.
 */
public class S2C_BlueprintDataFailMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_BlueprintDataFailMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_blueprint_data_fail_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_BlueprintDataFailMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_BlueprintDataFailMessage::encode, S2C_BlueprintDataFailMessage::decode);

    private final String blueprintId;
    private final String reason;

    public S2C_BlueprintDataFailMessage(String blueprintId, String reason) {
        this.blueprintId = blueprintId;
        this.reason      = reason;
    }

    @Override
    public CustomPacketPayload.Type<S2C_BlueprintDataFailMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_BlueprintDataFailMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.reason,      128);
    }

    public static S2C_BlueprintDataFailMessage decode(FriendlyByteBuf buf) {
        String id     = buf.readUtf(64);
        String reason = buf.readUtf(128);
        return new S2C_BlueprintDataFailMessage(id, reason);
    }

    public static void handle(S2C_BlueprintDataFailMessage msg, IPayloadContext ctx) {
        com.xioyim.spatialblueprint.client.ClientBlueprintCache.markFailed(msg.blueprintId, msg.reason);
    }
}
