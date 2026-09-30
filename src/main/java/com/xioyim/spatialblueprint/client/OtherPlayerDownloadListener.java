package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.util.BlueprintData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending "download for other-player full preview" requests.
 * When {@link ClientBlueprintCache} receives data, call {@link #onDataArrived}
 * so it gets attached to the correct {@link ClientOtherPreviewCache} entry.
 */
public class OtherPlayerDownloadListener {

    /** blueprintId → playerUUID */
    private static final Map<String, UUID> PENDING = new ConcurrentHashMap<>();

    public static void register(String blueprintId, UUID playerUUID) {
        PENDING.put(blueprintId, playerUUID);
    }

    /** Called by ClientBlueprintCache.put() after data is fully assembled. */
    public static void onDataArrived(String blueprintId, BlueprintData data) {
        UUID uuid = PENDING.remove(blueprintId);
        if (uuid != null) {
            ClientOtherPreviewCache.attachFullData(uuid, data);
        }
    }
}
