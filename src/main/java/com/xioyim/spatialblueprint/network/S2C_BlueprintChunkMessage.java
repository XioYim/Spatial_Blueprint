package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: One chunk of a large blueprint's compressed NBT payload.
 *
 * <p>Large blueprints are split into 500 KB chunks server-side
 * ({@link BlueprintNetwork#splitIntoChunks}) and reassembled by the client cache.</p>
 */
public class S2C_BlueprintChunkMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_BlueprintChunkMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_blueprint_chunk_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_BlueprintChunkMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_BlueprintChunkMessage::encode, S2C_BlueprintChunkMessage::decode);

    /** Safe chunk size well below Minecraft's 1 MB custom-payload cap. */
    public static final int CHUNK_SIZE = 512_000;

    /**
     * Maximum accepted chunk count (~125 MB in total), comfortably above the 64 MB
     * {@code BlueprintStorage.MAX_FILE_BYTES} ceiling. Guards {@link #decode} and the
     * client cache against a packet that declares a huge chunk count purely to force
     * a large allocation.
     */
    public static final int MAX_TOTAL_CHUNKS = 256;

    private final String blueprintId;
    private final int    chunkIndex;
    private final int    totalChunks;
    private final byte[] data;

    public S2C_BlueprintChunkMessage(String blueprintId, int chunkIndex, int totalChunks, byte[] data) {
        this.blueprintId = blueprintId;
        this.chunkIndex  = chunkIndex;
        this.totalChunks = totalChunks;
        this.data        = data;
    }

    @Override
    public CustomPacketPayload.Type<S2C_BlueprintChunkMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_BlueprintChunkMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeVarInt(msg.chunkIndex);
        buf.writeVarInt(msg.totalChunks);
        buf.writeVarInt(msg.data.length);
        buf.writeBytes(msg.data);
    }

    public static S2C_BlueprintChunkMessage decode(FriendlyByteBuf buf) {
        String id          = buf.readUtf(64);
        int    chunkIndex  = buf.readVarInt();
        int    totalChunks = buf.readVarInt();
        int    length      = buf.readVarInt();
        if (totalChunks < 1 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new io.netty.handler.codec.DecoderException(
                    "Blueprint chunk count out of range: " + totalChunks);
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new io.netty.handler.codec.DecoderException(
                    "Blueprint chunk index out of range: " + chunkIndex + "/" + totalChunks);
        }
        if (length < 0 || length > CHUNK_SIZE || length > buf.readableBytes()) {
            throw new io.netty.handler.codec.DecoderException(
                    "Blueprint chunk length out of range: " + length);
        }
        byte[] data        = new byte[length];
        buf.readBytes(data);
        return new S2C_BlueprintChunkMessage(id, chunkIndex, totalChunks, data);
    }

    public static void handle(S2C_BlueprintChunkMessage msg, IPayloadContext ctx) {
        com.xioyim.spatialblueprint.client.ClientBlueprintCache.receiveChunk(
                msg.blueprintId, msg.chunkIndex, msg.totalChunks, msg.data);
    }
}
