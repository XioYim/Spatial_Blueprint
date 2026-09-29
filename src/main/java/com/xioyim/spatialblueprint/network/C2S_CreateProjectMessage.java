package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：蓝图库「项目蓝图」tab 的 ✚ → 「创建项目蓝图」覆盖层点 [创建] 时发送。
 *
 * <p>只带名字与两条备注，不带选区 —— 项目边界以后另定。服务端重验 OP，
 * 分配 UUID 并写 {@code projects/{uuid}/project.dat}（目录随写自动创建），
 * <b>不发放任何物品</b>。</p>
 */
public class C2S_CreateProjectMessage {

    private final String name;
    private final String noteShort;
    private final String noteLong;

    public C2S_CreateProjectMessage(String name, String noteShort, String noteLong) {
        this.name      = name == null ? "" : name;
        this.noteShort = noteShort == null ? "" : noteShort;
        this.noteLong  = noteLong == null ? "" : noteLong;
    }

    public static void encode(C2S_CreateProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name,      128);
        buf.writeUtf(msg.noteShort,  15);
        buf.writeUtf(msg.noteLong,  200);
    }

    public static C2S_CreateProjectMessage decode(FriendlyByteBuf buf) {
        return new C2S_CreateProjectMessage(buf.readUtf(128), buf.readUtf(15), buf.readUtf(200));
    }

    public static void handle(C2S_CreateProjectMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 客户端按钮对非 OP 只是置灰，这里才是底线
            if (!player.hasPermissions(2)) {
                sendFail(player, "spatial_blueprint.message.project.need_op");
                return;
            }

            String safeName = BlueprintValidator.sanitizeName(msg.name).trim();
            if (safeName.isEmpty()) {
                sendFail(player, "spatial_blueprint.message.project.name_required");
                return;
            }

            UUID projectId = UUID.randomUUID();
            ProjectData data = new ProjectData();
            data.name      = safeName;
            data.noteShort = msg.noteShort.trim();
            data.noteLong  = msg.noteLong.trim();
            data.ownerUuid = player.getUUID().toString();
            data.ownerName = player.getGameProfile().getName();
            data.createdAt = System.currentTimeMillis();
            // 创建时固定维度：规划手柄以后只能在这个维度打开（e37）
            data.dimension = ProjectData.dimensionOf(player.serverLevel());
            ProjectStorage.saveProject(projectId, data);

            if (!ProjectStorage.projectExists(projectId)) {
                // saveProject 内部只记 error 日志不抛异常，这里补一道给玩家的反馈
                sendFail(player, "spatial_blueprint.message.project.create_failed");
                return;
            }

            SpatialBlueprintMod.LOGGER.info("[Project] Created project '{}' id={} dim={} by {}",
                    safeName, projectId, data.dimension, player.getGameProfile().getName());
            // 成功走聊天栏：S2C_OperationResult 的成功分支会顺手关掉别的界面（T9）
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.message.project.created", safeName), false);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendFail(ServerPlayer player, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, key));
    }
}
