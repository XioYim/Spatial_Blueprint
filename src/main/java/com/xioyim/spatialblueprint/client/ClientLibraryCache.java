package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Client-side cache for the blueprint library list fetched from the server in multiplayer.
 *
 * <p>In single-player the library screen reads local files directly via
 * {@link BlueprintStorage#listSummaries()}.  In multiplayer the client has no access to
 * the server's filesystem, so the screen sends a
 * {@link com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage} and the server
 * replies with a {@link com.xioyim.spatialblueprint.network.S2C_LibrarySyncMessage}
 * which populates this cache.</p>
 *
 * <p>The cache is invalidated whenever a mutating operation completes (rename / delete),
 * causing the next {@link #requestIfNeeded()} call to re-fetch.</p>
 */
@OnlyIn(Dist.CLIENT)
public class ClientLibraryCache {

    @Nullable private static List<BlueprintStorage.BlueprintSummary> cachedList = null;
    private static boolean loading = false;

    /** Returns the cached summary list, or {@code null} if not yet fetched. */
    @Nullable
    public static List<BlueprintStorage.BlueprintSummary> get() {
        return cachedList;
    }

    /** Returns {@code true} while a request is in-flight (no response yet). */
    public static boolean isLoading() {
        return loading;
    }

    /**
     * Sends a {@link com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage}
     * if no cached data is present and no request is already in-flight.
     */
    public static void requestIfNeeded() {
        if (cachedList == null && !loading) {
            loading = true;
            com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL
                    .sendToServer(new com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage());
        }
    }

    /**
     * Stores the list received from the server and clears the loading flag.
     * Called by {@link com.xioyim.spatialblueprint.network.S2C_LibrarySyncMessage}.
     */
    public static void onReceive(List<BlueprintStorage.BlueprintSummary> list) {
        cachedList = list;
        loading = false;
    }

    /**
     * Clears the cache so the next {@link #requestIfNeeded()} forces a fresh fetch.
     * Call this after a write operation (rename / delete) to keep the list consistent.
     */
    public static void invalidate() {
        cachedList = null;
        loading = false;
    }
}
