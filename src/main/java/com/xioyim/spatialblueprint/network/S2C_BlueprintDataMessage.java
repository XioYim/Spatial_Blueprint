package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: Delivers full {@link BlueprintData} to the requesting client.
 *
 * <p>The client stores the data in {@link ClientBlueprintCache}, which is checked by
 * {@link com.xioyim.spatialblueprint.item.SpatialBlueprintItem#getBlueprintData} and
 * {@link com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer} for rendering.</p>
 *
 * <p>Minecraft's built-in ZLIB compression (applied to all packets larger than
 * {@code network-compression-threshold}) makes the wire size of a large blueprint
 * significantly smaller than the raw NBT size.</p>
 */
public class S2C_BlueprintDataMessage {

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
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes));
            return new S2C_BlueprintDataMessage(id, tag != null ? tag : new CompoundTag());
        } catch (java.io.IOException e) {
            throw new io.netty.handler.codec.DecoderException("Failed to decode blueprint NBT", e);
        }
    }

    public static void handle(S2C_BlueprintDataMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2C_BlueprintDataMessage msg) {
        try {
            BlueprintData data = BlueprintData.deserialize(msg.serializedData);
            ClientBlueprintCache.put(msg.blueprintId, data);
        } catch (Exception ignored) {
            // Malformed data — leave cache empty; player can retry by re-clicking download
        }
    }
}
