package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：请求某项目的条目清单（序号 + 标签）与作者参考系（origin / size）。
 *
 * <p>供「创建子工程蓝图」页自动填序号与即时查重。只读操作，不做权限校验 ——
 * 玩家手上的项目蓝图本身就带着这个 id，读了也越不了权。</p>
 */
public class C2S_RequestProjectEntriesMessage {

    private final String projectId;

    public C2S_RequestProjectEntriesMessage(String projectId) {
        this.projectId = projectId == null ? "" : projectId;
    }

    public static void encode(C2S_RequestProjectEntriesMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.projectId, 64);
    }

    public static C2S_RequestProjectEntriesMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestProjectEntriesMessage(buf.readUtf(64));
    }

    public static void handle(C2S_RequestProjectEntriesMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            UUID projectUuid;
            try {
                projectUuid = UUID.fromString(msg.projectId);
            } catch (Exception e) {
                return;   // 非法 id：静默丢弃
            }

            ProjectData project = ProjectStorage.loadProject(projectUuid);
            if (project == null) {
                // 项目不存在也回一个 found=false 的空包，让客户端停止等待
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_ProjectEntriesMessage(msg.projectId, false, "", null, null, List.of()));
                return;
            }
            // 项目版 H2：STATION 要 OP；PRIVATE 要主人或 OP（无主非 OP 一律拒，T5）。
            if (project.kind == ProjectData.Kind.STATION && !player.hasPermissions(2)) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_ProjectEntriesMessage(msg.projectId, false, "", null, null, List.of()));
                return;
            }
            if (project.kind == ProjectData.Kind.PRIVATE
                    && !C2S_RequestProjectListMessage.canAccessPrivate(project, player)) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_ProjectEntriesMessage(msg.projectId, false, "", null, null, List.of()));
                return;
            }

            List<S2C_ProjectEntriesMessage.Entry> entries = new ArrayList<>();
            for (ProjectData.EntryData e : project.entries) {
                entries.add(new S2C_ProjectEntriesMessage.Entry(e.index, e.label));
            }
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_ProjectEntriesMessage(msg.projectId, true, project.name,
                            project.origin, project.size, entries));
        });
        ctx.get().setPacketHandled(true);
    }
}
