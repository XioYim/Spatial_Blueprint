package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：导入项目界面 [删除项目] —— OP 删除选中的项目（project.dat 与全部子工程文件一起删）。
 *
 * <p>载荷带当前 tab：成功后服务端按同一个 tab 主动推一份新清单，界面即时刷新（不走请求冷却）。
 * <b>项目有任何任务集占用时整单拒</b> —— 进度与已发蓝图物品都按 projectId / 序号记，
 * 删了会对不上；与子工程删除的前置检查同一原则。</p>
 */
public class C2S_DeleteProjectMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_DeleteProjectMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_delete_project_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_DeleteProjectMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_DeleteProjectMessage::encode, C2S_DeleteProjectMessage::decode);

    private final String projectId;
    private final String tab;

    public C2S_DeleteProjectMessage(String projectId, String tab) {
        this.projectId = projectId == null ? "" : projectId;
        this.tab = tab == null ? "" : tab;
    }

    @Override
    public CustomPacketPayload.Type<C2S_DeleteProjectMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_DeleteProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.projectId, 64);
        buf.writeUtf(msg.tab, 16);
    }

    public static C2S_DeleteProjectMessage decode(FriendlyByteBuf buf) {
        return new C2S_DeleteProjectMessage(buf.readUtf(64), buf.readUtf(16));
    }

    public static void handle(C2S_DeleteProjectMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_DeleteProjectMessage msg, ServerPlayer player) {
        if (player == null) return;

        // 1. 仅 OP（界面按钮同样只给 OP，这里是服务端兜底）
        if (!player.hasPermissions(2)) {
            sendFail(player, "spatial_blueprint.message.project.delete.need_op");
            return;
        }
        // 2. 项目必须存在
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
        // 3. 有任何任务集占用 → 拒（进度 / 已发蓝图物品都按 projectId + 序号记，删了全对不上）
        if (ProjectClaimRegistry.get(player.serverLevel()).hasAnyClaimForProject(msg.projectId)) {
            sendFail(player, "spatial_blueprint.message.project.delete.activated");
            return;
        }
        // 4. 删目录（project.dat + 全部子工程文件）
        if (!ProjectStorage.deleteProject(projectUuid)) {
            sendFail(player, "spatial_blueprint.message.project.delete.failed");
            return;
        }
        SpatialBlueprintMod.LOGGER.info("[Project] Deleted project='{}' id={} by {}",
                project.name, msg.projectId, player.getGameProfile().getName());
        player.displayClientMessage(Component.translatable(
                "spatial_blueprint.message.project.deleted",
                project.name == null ? "" : project.name), false);
        // 5. 同 tab 推一份新清单，界面即时刷新
        C2S_RequestProjectListMessage.sendList(player, msg.tab);
    }

    private static void sendFail(ServerPlayer player, String key) {
        BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(false, key));
    }
}
