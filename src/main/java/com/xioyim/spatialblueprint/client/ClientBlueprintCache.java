package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side in-memory cache for blueprint data downloaded from the server.
 *
 * <p>Blueprint data is no longer stored in item NBT (which would be synced to all
 * nearby clients on every inventory change). Instead, data is fetched on demand via
 * {@link com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage} and
 * cached here for the current session.</p>
 *
 * <p>Lifecycle: populated when {@link com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage}
 * arrives; invalidated when the player cancels placement or is auto-cancelled by distance.</p>
 */
@OnlyIn(Dist.CLIENT)
public class ClientBlueprintCache {

    /**
     * Maximum number of blueprint data objects kept in memory (#6).
     * Blueprints are fetched on demand, so this is bounded by the number of different
     * blueprints a player encounters in a single session.  256 is well above any
     * realistic per-session count while still providing a hard memory ceiling.
     */
    private static final int MAX_CACHE_SIZE = 256;

    /** blueprintId → downloaded BlueprintData */
    private static final Map<String, BlueprintData> CACHE            = new ConcurrentHashMap<>();
    /** blueprintId → received chunk slots (null slot = not yet received) */
    private static final Map<String, byte[][]>      PENDING_CHUNKS   = new ConcurrentHashMap<>();
    /** IDs for which a download request has been sent but no response yet */
    private static final Set<String>                DOWNLOADING      = ConcurrentHashMap.newKeySet();
    /** IDs that failed to download (server returned error or timed out) → failure reason */
    private static final Map<String, String>        FAILED           = new ConcurrentHashMap<>();
    /** Download start timestamps for timeout detection (blueprintId → System.currentTimeMillis) */
    private static final Map<String, Long>          DOWNLOAD_START   = new ConcurrentHashMap<>();

    /** Timeout before a pending download is auto-marked as failed (ms). */
    private static final long DOWNLOAD_TIMEOUT_MS = 8_000L;

    /** Returns cached data for the given ID, or {@code null} if not yet downloaded. */
    public static BlueprintData get(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return null;
        return CACHE.get(blueprintId);
    }

    /**
     * Stores downloaded data and clears the in-progress marker.
     * Called by {@link com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage}.
     *
     * <p>If the cache is at {@link #MAX_CACHE_SIZE} capacity and the incoming ID is new,
     * one arbitrary existing entry is evicted first to keep memory bounded (#6).</p>
     */
    public static void put(String blueprintId, BlueprintData data) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        // Evict an arbitrary entry when the cache is full and this is a brand-new key (#6).
        if (!CACHE.containsKey(blueprintId) && CACHE.size() >= MAX_CACHE_SIZE) {
            String evict = CACHE.keySet().iterator().next();
            CACHE.remove(evict);
            DOWNLOADING.remove(evict);
        }
        CACHE.put(blueprintId, data);
        DOWNLOADING.remove(blueprintId);
        OtherPlayerDownloadListener.onDataArrived(blueprintId, data);
    }

    /**
     * Removes cached data, in-progress download marker, and failure state.
     * Called on player cancel or auto-cancel.
     */
    public static void remove(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        CACHE.remove(blueprintId);
        DOWNLOADING.remove(blueprintId);
        FAILED.remove(blueprintId);
        DOWNLOAD_START.remove(blueprintId);
        PENDING_CHUNKS.remove(blueprintId);
    }

    /**
     * Receives one chunk of a chunked blueprint transfer.
     * When all chunks have arrived, assembles them, deserializes, and calls {@link #put}.
     */
    public static void receiveChunk(String blueprintId, int chunkIndex, int totalChunks, byte[] data) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        byte[][] slots = PENDING_CHUNKS.computeIfAbsent(blueprintId, k -> new byte[totalChunks][]);
        if (chunkIndex < 0 || chunkIndex >= slots.length) return;
        slots[chunkIndex] = data;

        // Check if all chunks have arrived
        for (byte[] slot : slots) {
            if (slot == null) return;
        }
        PENDING_CHUNKS.remove(blueprintId);

        // Reassemble
        int totalLen = 0;
        for (byte[] slot : slots) totalLen += slot.length;
        byte[] full = new byte[totalLen];
        int offset = 0;
        for (byte[] slot : slots) {
            System.arraycopy(slot, 0, full, offset, slot.length);
            offset += slot.length;
        }

        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(full));
            BlueprintData bpData = BlueprintData.deserialize(tag);
            put(blueprintId, bpData);
        } catch (Exception e) {
            markFailed(blueprintId, "spatial_blueprint.error.download_failed");
        }
    }

    /**
     * Returns {@code true} if a download request is in flight for this ID and has not timed out.
     * Auto-marks timed-out downloads as failed.
     */
    public static boolean isDownloading(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return false;
        if (!DOWNLOADING.contains(blueprintId) || CACHE.containsKey(blueprintId)) return false;
        Long start = DOWNLOAD_START.get(blueprintId);
        if (start != null && System.currentTimeMillis() - start > DOWNLOAD_TIMEOUT_MS) {
            markFailed(blueprintId, "spatial_blueprint.error.download_timeout");
            return false;
        }
        return true;
    }

    /** Returns {@code true} if this ID has a known failure (server error or timeout). */
    public static boolean isFailed(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return false;
        return FAILED.containsKey(blueprintId);
    }

    /**
     * Returns the failure reason key/message for display, or {@code null} if not failed.
     */
    public static String getFailReason(String blueprintId) {
        if (blueprintId == null) return null;
        return FAILED.get(blueprintId);
    }

    /**
     * Marks a download as failed (called by S2C fail packet or timeout).
     * Clears the downloading marker so the UI can show a retry button.
     */
    public static void markFailed(String blueprintId, String reason) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        DOWNLOADING.remove(blueprintId);
        DOWNLOAD_START.remove(blueprintId);
        FAILED.put(blueprintId, reason != null ? reason : "spatial_blueprint.error.download_failed");
    }

    /**
     * Clears failure state so the player can retry.
     */
    public static void clearFailed(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        FAILED.remove(blueprintId);
    }

    /**
     * Returns the download progress 0–100 based on chunks received so far.
     * Returns 0 if no chunk metadata is available yet.
     */
    public static int getDownloadProgress(String blueprintId) {
        if (blueprintId == null) return 0;
        byte[][] slots = PENDING_CHUNKS.get(blueprintId);
        if (slots == null || slots.length == 0) return 0;
        int received = 0;
        for (byte[] slot : slots) if (slot != null) received++;
        return received * 100 / slots.length;
    }

    /** Clears ALL cached blueprint data (used by /sbp clearcache). */
    public static void clearAll() {
        CACHE.clear();
        DOWNLOADING.clear();
        FAILED.clear();
        DOWNLOAD_START.clear();
        PENDING_CHUNKS.clear();
    }

    /**
     * Marks an ID as downloading (called just before sending the C2S request,
     * so the UI can immediately show "Downloading…" without waiting for a round trip).
     */
    public static void startDownload(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return;
        FAILED.remove(blueprintId);
        DOWNLOADING.add(blueprintId);
        DOWNLOAD_START.put(blueprintId, System.currentTimeMillis());
    }
}
