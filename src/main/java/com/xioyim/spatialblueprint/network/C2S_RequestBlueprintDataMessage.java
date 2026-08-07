package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * C2S: Client requests the full {@link BlueprintData} for a given blueprintId.
 *
 * <p>The server loads the data from the file store and replies with
 * {@link S2C_BlueprintDataMessage}. If the file is not found no response is sent.</p>
 *
 * <p>Rate-limited to one request per player per {@value #COOLDOWN_MS} ms (#7).</p>
 */
public class C2S_RequestBlueprintDataMessage {

    /** Minimum milliseconds between accepted requests from the same player. */
    private static final long COOLDOWN_MS = 500L;

    /** Tracks the last accepted request timestamp per player UUID. */
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private final String blueprintId;

    public C2S_RequestBlueprintDataMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    public static void encode(C2S_RequestBlueprintDataMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_RequestBlueprintDataMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestBlueprintDataMessage(buf.readUtf(64));
    }

    public static void handle(C2S_RequestBlueprintDataMessage msg,
                               Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || msg.blueprintId.isEmpty()) return;

            // #7: rate-limit — reject if the same player sent a request too recently
            long now = System.currentTimeMillis();
            boolean[] pass = {false};
            LAST_REQUEST.compute(player.getUUID(), (id, last) -> {
                if (last == null || now - last >= COOLDOWN_MS) {
                    pass[0] = true;
                    return now;
                }
                return last;
            });
            // Rate-limited: send fail so client doesn't time out silently
            if (!pass[0]) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_BlueprintDataFailMessage(msg.blueprintId,
                                "spatial_blueprint.error.request_rate_limited"));
                return;
            }

            try {
                BlueprintData data = BlueprintStorage.load(msg.blueprintId);
                if (data == null) {
                    BlueprintNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new S2C_BlueprintDataFailMessage(msg.blueprintId,
                                    "spatial_blueprint.error.blueprint_not_found"));
                    return;
                }
                // Serialize to compressed bytes and split into chunks to stay under
                // Minecraft's 1 MB custom-payload limit per packet.
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(data.serialize(), baos);
                byte[] full        = baos.toByteArray();
                int    chunkSize   = S2C_BlueprintChunkMessage.CHUNK_SIZE;
                int    totalChunks = (full.length + chunkSize - 1) / chunkSize;
                for (int i = 0; i < totalChunks; i++) {
                    int start  = i * chunkSize;
                    int end    = Math.min(start + chunkSize, full.length);
                    byte[] chunk = java.util.Arrays.copyOfRange(full, start, end);
                    BlueprintNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new S2C_BlueprintChunkMessage(msg.blueprintId, i, totalChunks, chunk));
                }
            } catch (Exception e) {
                com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.error(
                        "[BlueprintData] Failed to load blueprint {}: {}", msg.blueprintId, e.getMessage());
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_BlueprintDataFailMessage(msg.blueprintId,
                                "spatial_blueprint.error.blueprint_load_error"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
