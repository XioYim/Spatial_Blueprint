package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.SafeNbtIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: Delivers full {@link BlueprintData} to the requesting client (single-packet path).
 *
 * <p>The client stores the data in its blueprint cache, which is checked by
 * {@code SpatialBlueprintItem.getBlueprintDataClient} and the renderer.</p>
 */
public class S2C_BlueprintDataMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_BlueprintDataMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_blueprint_data_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_BlueprintDataMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_BlueprintDataMessage::encode, S2C_BlueprintDataMessage::decode);

    /**
     * Maximum accepted compressed payload size (1 MiB), matching Minecraft's custom-payload
     * cap for this un-chunked path. Guards {@link #decode} against a hostile or corrupt packet
     * whose declared length would trigger a huge allocation. Blueprints too large for a single
     * packet are delivered through {@link S2C_BlueprintChunkMessage} instead.
     */
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final String      blueprintId;
    private final CompoundTag serializedData;

    /** Called server-side to construct and send the packet. */
    public S2C_BlueprintDataMessage(String blueprintId, BlueprintData data) {
        this.blueprintId    = blueprintId;
        this.serializedData = data.serialize();
    }

    /** Private — used only by {@link #decode}. */
    private S2C_BlueprintDataMessage(String blueprintId, CompoundTag serializedData) {
        this.blueprintId    = blueprintId;
        this.serializedData = serializedData;
    }

    @Override
    public CustomPacketPayload.Type<S2C_BlueprintDataMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_BlueprintDataMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        // writeNbt() has a 1 MB hard limit — serialize as compressed bytes instead
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(msg.serializedData, baos);
            byte[] bytes = baos.toByteArray();
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        } catch (java.io.IOException e) {
            throw new io.netty.handler.codec.EncoderException("Failed to encode blueprint NBT", e);
        }
    }

    public static S2C_BlueprintDataMessage decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        try {
            int length = buf.readInt();
            if (length < 0 || length > MAX_PAYLOAD_BYTES || length > buf.readableBytes()) {
                throw new io.netty.handler.codec.DecoderException(
                        "Blueprint payload length out of range: " + length);
            }
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            CompoundTag tag = SafeNbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes));
            return new S2C_BlueprintDataMessage(id, tag != null ? tag : new CompoundTag());
        } catch (java.io.IOException e) {
            throw new io.netty.handler.codec.DecoderException("Failed to decode blueprint NBT", e);
        }
    }

    public static void handle(S2C_BlueprintDataMessage msg, IPayloadContext ctx) {
        try {
            com.xioyim.spatialblueprint.util.BlueprintData data =
                    com.xioyim.spatialblueprint.util.BlueprintData.deserialize(msg.serializedData);
            com.xioyim.spatialblueprint.client.ClientBlueprintCache.put(msg.blueprintId, data);
        } catch (Exception ignored) {
            // Malformed data — leave cache empty; player can retry by re-clicking download
        }
    }
}
