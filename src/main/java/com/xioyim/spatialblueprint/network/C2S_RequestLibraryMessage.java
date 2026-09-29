package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S: client requests the full blueprint library summary list.
 *
 * <p>The server reads its local {@link BlueprintStorage#listSummaries()} and replies
 * with an {@link S2C_LibrarySyncMessage}.  Enables the blueprint library screen to
 * display server-side blueprints in multiplayer (#2).</p>
 */
public class C2S_RequestLibraryMessage {

    public C2S_RequestLibraryMessage() {}

    public static void encode(C2S_RequestLibraryMessage msg, FriendlyByteBuf buf) {
        // no payload
    }

    public static C2S_RequestLibraryMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestLibraryMessage();
    }

    public static void handle(C2S_RequestLibraryMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            BlueprintStorage.invalidateSummaryCache();
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(buildFilteredSummaries(player)));
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 归属下沉服务端（H2）：OP 给全量，其余只给自己的。
     *
     * <p>可见性口径与客户端 {@code BlueprintLibraryScreen.isMineForListing} 一致：
     * 有 ownerUuid 按 UUID 判；无主旧数据回退按 creatorName 认，避免改名外的集体消失。
     * 权限判断仍走 {@code BlueprintValidator.canManageBlueprint}（无主一律判否），这里只管看不看得见。</p>
     */
    public static boolean isVisibleTo(BlueprintStorage.BlueprintSummary s, ServerPlayer player) {
        if (player.hasPermissions(2)) return true;
        String owner = s.ownerUuid();
        if (owner != null && !owner.isEmpty()) {
            return owner.equals(player.getUUID().toString());
        }
        return player.getGameProfile().getName().equals(s.creatorName());
    }

    /** 过滤 + 合并工程安全坐标（原 handle 内联逻辑集中到此，各推送点共用，避免漏网）。 */
    public static java.util.List<BlueprintStorage.BlueprintSummary> buildFilteredSummaries(ServerPlayer player) {
        // 安全传送坐标存 SavedData、不在蓝图文件里：下发前按 id 合并进工程蓝图的摘要
        // （只为客户端 [TP] tooltip 显示「当前：x, y, z」与坐标页预填；服务端传送自己查 SavedData）。
        com.xioyim.spatialblueprint.util.SBSafeCoordData safeData =
                com.xioyim.spatialblueprint.util.SBSafeCoordData.get(player.serverLevel());
        java.util.List<BlueprintStorage.BlueprintSummary> summaries = new java.util.ArrayList<>();
        for (BlueprintStorage.BlueprintSummary s : BlueprintStorage.listSummaries()) {
            if (!isVisibleTo(s, player)) continue;
            com.xioyim.spatialblueprint.util.SBSafeCoordData.SafeEntry e =
                    "engineering".equals(s.category()) ? safeData.getEntry(s.id()) : null;
            summaries.add(e != null && e.hasCoord() ? s.withSafeCoord(true, e.x(), e.y(), e.z()) : s);
        }
        return summaries;
    }
}
