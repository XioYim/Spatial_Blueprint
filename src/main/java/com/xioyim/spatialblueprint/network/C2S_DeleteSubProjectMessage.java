package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：项目界面每行的 ✕ —— 删除一个子工程（仅 OP）。
 *
 * <p>三清（顺序固定）：① 进度（每个任务集的完成记录 + 该序号的全部领取冷却，见
 * {@link ProjectProgressData#forgetEntry}）；② 文件（{engUuid}.litematic，见
 * {@link ProjectStorage#deleteEntryFile}）；③ 条目本身（不重排其它条目的 index / color）。
 * 有其它条目以它为前置时整单拒绝。</p>
 *
 * <p>玩家手里已领取的该子工程蓝图不追回：放置时文件已删会走「缺少蓝图数据」横幅，
 * 领取时条目已删会走「没有该序号的子工程」。</p>
 */
public class C2S_DeleteSubProjectMessage {

    private final String projectId;
    private final int entryIndex;

    public C2S_DeleteSubProjectMessage(String projectId, int entryIndex) {
        this.projectId = projectId == null ? "" : projectId;
        this.entryIndex = entryIndex;
    }

    public static void encode(C2S_DeleteSubProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.projectId, 64);
        buf.writeVarInt(msg.entryIndex);
    }

    public static C2S_DeleteSubProjectMessage decode(FriendlyByteBuf buf) {
        return new C2S_DeleteSubProjectMessage(buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(C2S_DeleteSubProjectMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 1. 仅 OP
            if (!player.hasPermissions(2)) {
                sendFail(player, "spatial_blueprint.message.subproject.need_op");
                return;
            }
            // 2. 主手（或副手，副手直开流程）必须是该项目绑定的项目蓝图
            ItemStack main = ProjectBlueprintItem.findHeld(player);
            if (main.isEmpty()
                    || !msg.projectId.equals(ProjectBlueprintItem.getProjectId(main))) {
                sendFail(player, "spatial_blueprint.message.subproject.need_item");
                return;
            }
            // 3. 项目与条目都必须存在
            UUID projectUuid;
            ProjectData project;
            try {
                projectUuid = UUID.fromString(msg.projectId);
                project = ProjectStorage.loadProject(projectUuid);
            } catch (IllegalArgumentException e) {
                projectUuid = null;
                project = null;
            }
            if (project == null) {
                sendFail(player, "spatial_blueprint.message.subproject.project_missing");
                return;
            }
            ProjectData.EntryData entry = null;
            for (ProjectData.EntryData candidate : project.entries) {
                if (candidate.index == msg.entryIndex) {
                    entry = candidate;
                    break;
                }
            }
            if (entry == null) {
                // no_such_entry 的正文带 %s：按 C2S_ClaimSubProjectMessage.sendResult 的既有约定，
                // 主键用无参的通用动作词、原键 + 参数放 detail —— 否则 %s 会原样画在横幅上
                sendFail(player, "spatial_blueprint.gui.placement.context.delete",
                        "spatial_blueprint.detail.claim.no_such_entry",
                        new Object[]{msg.entryIndex});
                return;
            }
            // 4. 依赖检查：其它条目以它为前置则拒绝
            List<String> dependents = new ArrayList<>();
            for (ProjectData.EntryData candidate : project.entries) {
                if (candidate.index != msg.entryIndex && candidate.requiresEntry == msg.entryIndex) {
                    dependents.add(String.valueOf(candidate.index));
                }
            }
            if (!dependents.isEmpty()) {
                sendFail(player, "spatial_blueprint.message.subproject.delete_has_dependents",
                        "spatial_blueprint.detail.subproject.delete_has_dependents",
                        new Object[]{String.join(", ", dependents)});
                return;
            }
            // 5. 三清（顺序固定）：进度 → 文件 → 条目；不重排其它条目的 index / color
            ProjectClaimRegistry registry = ProjectClaimRegistry.get(player.serverLevel());
            List<String> taskSetIds = new ArrayList<>();
            for (ProjectClaimRegistry.Claim claim : registry.findByProject(msg.projectId)) {
                taskSetIds.add(claim.taskSetId);
            }
            ProjectProgressData.get(player.serverLevel()).forgetEntry(taskSetIds, msg.entryIndex);
            ProjectStorage.deleteEntryFile(projectUuid, entry.filename);
            project.entries.remove(entry);
            ProjectStorage.saveProject(projectUuid, project);

            player.displayClientMessage(Component.translatable(
                    "spatial_blueprint.message.subproject.deleted",
                    entry.label == null ? "" : entry.label, msg.entryIndex), false);
            C2S_RequestProjectStatusMessage.sendStatus(player);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendFail(ServerPlayer player, String key) {
        sendFail(player, key, "", null);
    }

    /** messageKey 必须无参数；带参数的说明放在 detail 键里，由客户端用 args 渲染。 */
    private static void sendFail(ServerPlayer player, String key, String detailKey, Object[] args) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, key, detailKey, args));
    }
}
