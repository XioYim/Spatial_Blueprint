package com.xioyim.spatialblueprint.server;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of active blueprint preview positions.
 * Updated via {@link com.xioyim.spatialblueprint.network.C2S_PreviewUpdateMessage};
 * broadcasts {@link S2C_PreviewBroadcastMessage} to nearby players.
 */
public class ServerPreviewTracker {

    private static final Map<UUID, PreviewEntry> ENTRIES = new ConcurrentHashMap<>();

    private record PreviewEntry(String playerName, String blueprintId,
                                BlockPos origin, int rotation,
                                int sizeX, int sizeY, int sizeZ) {}

    /**
     * Called when a client reports its current preview state.
     * origin == null clears the entry and broadcasts a clear to nearby players.
     */
    public static void update(ServerPlayer player, BlockPos origin, int rotation,
                               int sizeX, int sizeY, int sizeZ, String blueprintId) {
        UUID uuid = player.getUUID();

        if (origin == null) {
            PreviewEntry old = ENTRIES.remove(uuid);
            if (old != null) broadcast(player, uuid, player.getScoreboardName(),
                    blueprintId, null, rotation, sizeX, sizeY, sizeZ);
            return;
        }

        PreviewEntry prev = ENTRIES.get(uuid);
        // Deduplicate: skip broadcast if nothing changed
        if (prev != null && prev.origin().equals(origin)
                && prev.rotation() == rotation
                && prev.sizeX() == sizeX && prev.sizeY() == sizeY && prev.sizeZ() == sizeZ) {
            return;
        }

        ENTRIES.put(uuid, new PreviewEntry(player.getScoreboardName(), blueprintId,
                origin, rotation, sizeX, sizeY, sizeZ));
        broadcast(player, uuid, player.getScoreboardName(),
                blueprintId, origin, rotation, sizeX, sizeY, sizeZ);
    }

    /** Clears the entry for a player (e.g. on disconnect). */
    public static void clear(ServerPlayer player) {
        PreviewEntry e = ENTRIES.remove(player.getUUID());
        if (e != null) broadcast(player, player.getUUID(), player.getScoreboardName(),
                e.blueprintId(), null, 0, 0, 0, 0);
    }

    private static void broadcast(ServerPlayer source, UUID uuid, String playerName,
                                   String blueprintId, BlockPos origin, int rotation,
                                   int sizeX, int sizeY, int sizeZ) {
        if (!(source.level() instanceof ServerLevel level)) return;
        int radius = BlueprintConfig.PREVIEW_BROADCAST_RADIUS.get();
        S2C_PreviewBroadcastMessage msg = new S2C_PreviewBroadcastMessage(
                uuid, playerName, blueprintId, origin, rotation, sizeX, sizeY, sizeZ);

        double ox = origin != null ? origin.getX() : source.getX();
        double oy = origin != null ? origin.getY() : source.getY();
        double oz = origin != null ? origin.getZ() : source.getZ();

        for (ServerPlayer target : level.players()) {
            if (target.getUUID().equals(uuid)) continue;
            double dx = target.getX() - ox;
            double dy = target.getY() - oy;
            double dz = target.getZ() - oz;
            if (dx * dx + dy * dy + dz * dz <= (double) radius * radius) {
                BlueprintNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> target), msg);
            }
        }
    }
}
