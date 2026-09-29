package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * C2S：导入项目界面请求某个 tab 的项目清单。
 *
 * <p>tab：{@value #TAB_PUBLIC} 共用 / {@value #TAB_PRIVATE} 自用 / {@value #TAB_STATION} 蓝图站。
 * 每玩家 {@value #COOLDOWN_MS} ms 一次，超频<b>静默丢弃</b>（客户端同样有 2 秒冷却，
 * 正常操作不会撞到这道；撞到的是改包刷包）。蓝图站 tab 非 OP 请求也静默丢。</p>
 */
public class C2S_RequestProjectListMessage {

    public static final String TAB_PUBLIC  = "public";
    public static final String TAB_PRIVATE = "private";
    public static final String TAB_STATION = "station";

    public static final long COOLDOWN_MS = 2000L;
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private final String tab;

    public C2S_RequestProjectListMessage(String tab) {
        this.tab = tab == null ? "" : tab;
    }

    public static void encode(C2S_RequestProjectListMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.tab, 16);
    }

    public static C2S_RequestProjectListMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestProjectListMessage(buf.readUtf(16));
    }

    public static void handle(C2S_RequestProjectListMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ProjectData.Kind kind = kindOf(msg.tab);
            if (kind == null) return;
            if (kind == ProjectData.Kind.STATION && !player.hasPermissions(2)) return;

            long now = System.currentTimeMillis();
            boolean[] pass = {false};
            LAST_REQUEST.compute(player.getUUID(), (id, last) -> {
                if (last == null || now - last >= COOLDOWN_MS) {
                    pass[0] = true;
                    return now;
                }
                return last;
            });
            if (!pass[0]) return;

            sendList(player, msg.tab);
        });
        ctx.get().setPacketHandled(true);
    }

    /** 主动推某 tab 的最新清单（删除项目等写操作后刷新界面用）；不走冷却。 */
    public static void sendList(ServerPlayer player, String tab) {
        ProjectData.Kind kind = kindOf(tab);
        if (kind == null) return;
        if (kind == ProjectData.Kind.STATION && !player.hasPermissions(2)) return;
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_ProjectListMessage(tab, buildList(player, kind)));
    }

    /** 玩家下线后把冷却记录清掉，避免 map 随玩家来去无限长。 */
    public static void forget(UUID playerId) {
        LAST_REQUEST.remove(playerId);
    }

    private static ProjectData.Kind kindOf(String tab) {
        return switch (tab) {
            case TAB_PUBLIC  -> ProjectData.Kind.PUBLIC;
            case TAB_PRIVATE -> ProjectData.Kind.PRIVATE;
            case TAB_STATION -> ProjectData.Kind.STATION;
            default          -> null;
        };
    }

    /** 扫全部项目、按 kind 过滤；进度按「谁在看」取对应的任务集。 */
    private static List<S2C_ProjectListMessage.ProjectSummary> buildList(ServerPlayer player,
                                                                         ProjectData.Kind kind) {
        List<S2C_ProjectListMessage.ProjectSummary> out = new ArrayList<>();
        ProjectClaimRegistry registry = ProjectClaimRegistry.get(player.serverLevel());
        ProjectProgressData progress = ProjectProgressData.get(player.serverLevel());
        String viewer = player.getUUID().toString();

        for (UUID projectId : ProjectStorage.listProjectIds()) {
            ProjectData data = ProjectStorage.loadProject(projectId);
            if (data == null || data.kind != kind) continue;
            // 项目版 H2：PRIVATE 只给主人看（OP 看全量）。可见性回退与蓝图库一致：
            // 无主旧项目按 ownerName 认，避免集体消失；权限判断不放宽。
            if (kind == ProjectData.Kind.PRIVATE && !player.hasPermissions(2) && !isPrivateVisibleTo(data, player)) continue;

            String pid = projectId.toString();
            int completed = -1;
            boolean activated = false;
            if (kind == ProjectData.Kind.PUBLIC) {
                ProjectClaimRegistry.Claim claim = registry.findPublicByProject(pid);
                if (claim != null) {
                    activated = true;
                    completed = progress.completedOf(claim.taskSetId).size();
                }
            } else if (kind == ProjectData.Kind.PRIVATE) {
                ProjectClaimRegistry.Claim claim = registry.findPrivateByProjectAndOwner(pid, viewer);
                if (claim != null) completed = progress.completedOf(claim.taskSetId).size();
            }

            out.add(new S2C_ProjectListMessage.ProjectSummary(
                    pid, data.name, data.kind, data.stage,
                    data.noteShort, data.noteLong,
                    data.ownerName, data.createdAt,
                    data.entries.size(), completed, activated));
        }
        // 新的在上，与蓝图库一致
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    /** PRIVATE 可见性：有 ownerUuid 按 UUID 判，无主旧项目回退按 ownerName 认。 */
    private static boolean isPrivateVisibleTo(ProjectData data, ServerPlayer player) {
        if (data.ownerUuid != null && !data.ownerUuid.isEmpty()) {
            return data.ownerUuid.equals(player.getUUID().toString());
        }
        return data.ownerName != null && data.ownerName.equals(player.getGameProfile().getName());
    }

    /** PRIVATE 数据权：主人或 OP；无主一律判否（T5，权限判断不许放宽）。 */
    public static boolean canAccessPrivate(ProjectData project, ServerPlayer player) {
        if (project == null || player == null) return false;
        if (player.hasPermissions(2)) return true;
        return project.ownerUuid != null && !project.ownerUuid.isEmpty()
                && project.ownerUuid.equals(player.getUUID().toString());
    }
}
