package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of other players' blueprint preview states received via
 * {@link com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage}.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientOtherPreviewCache {

    public static final class Entry {
        public final UUID    playerUUID;
        public final String  playerName;
        public final String  blueprintId;
        /** 蓝图分类，决定预览框颜色。 */
        public String        category = "";
        public BlockPos      origin;
        public int           rotation;
        public int           sizeX, sizeY, sizeZ;
        /** Non-null only after the user explicitly downloads full data via /sbp download. */
        @Nullable public BlueprintData fullData;

        Entry(UUID playerUUID, String playerName, String blueprintId,
              BlockPos origin, int rotation, int sizeX, int sizeY, int sizeZ) {
            this.playerUUID  = playerUUID;
            this.playerName  = playerName;
            this.blueprintId = blueprintId;
            this.origin      = origin;
            this.rotation    = rotation;
            this.sizeX       = sizeX;
            this.sizeY       = sizeY;
            this.sizeZ       = sizeZ;
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    public static void receive(S2C_PreviewBroadcastMessage msg) {
        if (!ClientPreviewSettings.isEnabled()) return;
        if (ClientPreviewSettings.isBlocked(msg.playerName)) return;

        if (msg.origin == null) {
            ENTRIES.remove(msg.playerUUID);
            return;
        }
        ENTRIES.compute(msg.playerUUID, (id, existing) -> {
            if (existing == null) {
                Entry created = new Entry(msg.playerUUID, msg.playerName, msg.blueprintId,
                        msg.origin, msg.rotation, msg.sizeX, msg.sizeY, msg.sizeZ);
                created.category = msg.category;
                return created;
            }
            // Update position; keep fullData if already downloaded
            existing.origin   = msg.origin;
            existing.rotation = msg.rotation;
            existing.sizeX    = msg.sizeX;
            existing.sizeY    = msg.sizeY;
            existing.sizeZ    = msg.sizeZ;
            existing.category = msg.category;
            // If blueprintId changed, discard stale full data
            if (!existing.blueprintId.equals(msg.blueprintId)) existing.fullData = null;
            return existing;
        });
    }

    /** Called when full blueprint data finishes downloading for another player's preview. */
    public static void attachFullData(UUID playerUUID, BlueprintData data) {
        Entry e = ENTRIES.get(playerUUID);
        if (e != null) e.fullData = data;
    }

    public static Collection<Entry> all() {
        return ENTRIES.values();
    }

    public static boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    /**
     * 断线 / 退出世界时清空。
     *
     * <p>条目按玩家 UUID 存，正常情况下靠服务端发 origin=null 的广播来撤销。
     * 断线时那个广播不会来，残留的条目会在换服后继续画出一个不存在的他人预览框，
     * 而且因为源玩家已经不在，永远没人来清它。</p>
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static void remove(UUID playerUUID) {
        ENTRIES.remove(playerUUID);
    }
}
