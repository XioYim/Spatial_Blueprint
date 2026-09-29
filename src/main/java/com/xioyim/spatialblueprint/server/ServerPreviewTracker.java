package com.xioyim.spatialblueprint.server;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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
    /** 速率限制：同一玩家两次广播的最小 tick 间隔（2 tick ≈ 100ms，挡每 tick 刷包，不影响正常拖动）。 */
    private static final long COOLDOWN_TICKS = 2L;
    private static final Map<UUID, Long> LAST_BROADCAST_TICK = new ConcurrentHashMap<>();

    private record PreviewEntry(String playerName, String blueprintId, String category,
                                BlockPos origin, int rotation,
                                int sizeX, int sizeY, int sizeZ) {}

    /**
     * Called when a client reports its current preview state.
     * origin == null clears the entry and broadcasts a clear to nearby players.
     */
    public static void update(ServerPlayer player, BlockPos origin, int rotation,
                               int sizeX, int sizeY, int sizeZ, String blueprintId, String category) {
        UUID uuid = player.getUUID();

        if (origin == null) {
            PreviewEntry old = ENTRIES.remove(uuid);
            if (old != null) broadcast(player, uuid, player.getScoreboardName(),
                    blueprintId, category, null, rotation, sizeX, sizeY, sizeZ);
            return;
        }

        // 1. size 钳位 / 非法值直接丢弃（静默，不回横幅，高频消息）
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return;
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME) return;
        // 单边过大也视为非法（避免 1×1×1.6M 这类畸形，虽然体积已拦，但单边仍可能撑爆渲染）
        int maxSide = BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME;
        if (sizeX > maxSide || sizeY > maxSide || sizeZ > maxSide) return;
        // rotation 仅 0-3 合法
        if (rotation < 0 || rotation > 3) return;

        // 2. origin 距离校验：超过预览距离 + 最大偏移则丢弃
        //    复用放置距离的语义，静默丢弃
        if (!com.xioyim.spatialblueprint.server.BlueprintValidator.validatePlacementDistance(player, origin).valid()) {
            return;
        }

        PreviewEntry prev = ENTRIES.get(uuid);
        // Deduplicate: skip broadcast if nothing changed
        if (prev != null && prev.origin().equals(origin)
                && prev.rotation() == rotation
                && prev.sizeX() == sizeX && prev.sizeY() == sizeY && prev.sizeZ() == sizeZ) {
            return;
        }

        // 3. 速率限制：同一玩家两次广播至少间隔 COOLDOWN_TICKS
        long now = player.serverLevel().getGameTime();
        Long last = LAST_BROADCAST_TICK.get(uuid);
        if (last != null && now - last < COOLDOWN_TICKS) {
            return;
        }
        LAST_BROADCAST_TICK.put(uuid, now);

        ENTRIES.put(uuid, new PreviewEntry(player.getScoreboardName(), blueprintId, category,
                origin, rotation, sizeX, sizeY, sizeZ));
        broadcast(player, uuid, player.getScoreboardName(),
                blueprintId, category, origin, rotation, sizeX, sizeY, sizeZ);
    }

    /** Clears the entry for a player (e.g. on disconnect). */
    public static void clear(ServerPlayer player) {
        UUID id = player.getUUID();
        LAST_BROADCAST_TICK.remove(id);
        PreviewEntry e = ENTRIES.remove(id);
        if (e != null) broadcast(player, id, player.getScoreboardName(),
                e.blueprintId(), e.category(), null, 0, 0, 0, 0);
    }

    private static void broadcast(ServerPlayer source, UUID uuid, String playerName,
                                   String blueprintId, String category, BlockPos origin, int rotation,
                                   int sizeX, int sizeY, int sizeZ) {
        if (!(source.level() instanceof ServerLevel level)) return;
        int radius = BlueprintConfig.PREVIEW_BROADCAST_RADIUS.get();
        S2C_PreviewBroadcastMessage msg = new S2C_PreviewBroadcastMessage(
                uuid, playerName, blueprintId, category, origin, rotation, sizeX, sizeY, sizeZ);

        double ox = origin != null ? origin.getX() : source.getX();
        double oy = origin != null ? origin.getY() : source.getY();
        double oz = origin != null ? origin.getZ() : source.getZ();

        for (ServerPlayer target : level.players()) {
            if (target.getUUID().equals(uuid)) continue;
            double dx = target.getX() - ox;
            double dy = target.getY() - oy;
            double dz = target.getZ() - oz;
            if (dx * dx + dy * dy + dz * dz <= (double) radius * radius) {
                BlueprintNetwork.sendToPlayer(target, msg);
            }
        }
    }
}
