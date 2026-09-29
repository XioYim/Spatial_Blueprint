package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientLibraryCache {

    @Nullable private static List<BlueprintStorage.BlueprintSummary> cachedList = null;
    private static boolean loading = false;
    /**
     * 每次收到服务端列表自增。蓝图列表界面据此判断是否需要用新数据重建 ——
     * 例如放置次元蓝图后服务端会推来带「已放置」标记的新列表，
     * 界面若不跟随刷新，✔ 取出按钮不会置灰，玩家能取出同一份结构反复放置。
     */
    private static int version = 0;

    /** 列表版本号；变化即表示服务端推来了新数据。 */
    public static int version() {
        return version;
    }

    /** Returns the cached summary list, or {@code null} if not yet fetched. */
    @Nullable
    public static List<BlueprintStorage.BlueprintSummary> get() {
        return cachedList;
    }

    /**
     * 本玩家在该分类下已占用的槽位数；<b>列表还没拉到时返回 -1</b>。
     *
     * <p>直接用同步来的列表算 —— {@code ownerUuid} 已在摘要里，不需要额外网络往返。
     * 无主的旧蓝图不计入，与服务端 {@code BlueprintStorage.countSlots} 保持一致。</p>
     *
     * <p>⚠ 拿不到数据时<b>不能返回 0</b>：0 是一个确定的答案（「一个都没占」），
     * 而「还不知道」是另一回事。以前两者混在一起，界面上就会出现
     * 「明明存了一个次元蓝图，创建页却显示已用 0 个槽位」。
     * 调用方拿到 -1 应当显示「?」并放行，由服务端做最终判定。</p>
     */
    public static int countOwnSlots(String category) {
        List<BlueprintStorage.BlueprintSummary> list = get();
        if (list == null || category == null) return -1;
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return -1;
        String uuid = p.getUUID().toString();
        int n = 0;
        for (BlueprintStorage.BlueprintSummary s : list) {
            if (uuid.equals(s.ownerUuid()) && category.equals(s.category())) n++;
        }
        return n;
    }

    /**
     * 强制重新拉取 —— 无视现有缓存。
     *
     * <p>{@link #requestIfNeeded()} 只在缓存为空时才发请求，缓存一旦建立就再也不刷新。
     * 玩家捕获 / 删除蓝图之后再打开创建页，看到的还是捕获前的旧列表，槽位数是错的。
     * 需要「此刻的准确数字」的界面（创建页）应当用这个。</p>
     */
    public static void forceRefresh() {
        cachedList = null;
        loading = true;
        version++;
        com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL
                .sendToServer(new com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage());
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
        version++;
    }

    /**
     * 断线 / 退出世界时清空，避免把上一个存档的蓝图列表显示在下一个存档里。
     *
     * <p>{@link #requestIfNeeded()} 只在 {@code cachedList == null} 时才发请求，
     * 所以不清的话换服后打开蓝图库看到的仍是上一个服的列表，而且永远不会自己刷新。
     * {@code loading} 一并复位 —— 断线时若正好有请求在飞，那个回包再也不会到，
     * 标志位挂着就把后续所有请求都挡住了。</p>
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        invalidate();
    }

    /**
     * Clears the cache so the next {@link #requestIfNeeded()} forces a fresh fetch.
     * Call this after a write operation (rename / delete) to keep the list consistent.
     */
    public static void invalidate() {
        cachedList = null;
        loading = false;
        version++;
    }
}
