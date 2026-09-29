package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：导入项目界面选中一行后点 [导入项目] / [规划项目]。
 *
 * <p>两种模式都造一枚绑定 {@code projectId} 的项目蓝图物品，交付方式不同：</p>
 * <ul>
 *   <li>{@link #MODE_IMPORT} 导入：共用 → 公用项目蓝图，带全服 taskSetId（未激活则拒）；
 *       自用 → 私人项目蓝图，未激活，玩家自己激活；蓝图站 → 拒。<b>给一枚新的</b>到背包（满了掉脚下）。</li>
 *   <li>{@link #MODE_PLAN} 规划：仅 OP，三个 tab 都行，未激活的公用项目蓝图当工作手柄。
 *       <b>替换手上的物品</b>（与蓝图库 ✔ 应用同一规则：{@link C2S_ApplyBlueprintMessage#replaceTargetHand}）。</li>
 * </ul>
 */
public class C2S_TakeProjectMessage {

    public static final int MODE_IMPORT = 0;
    public static final int MODE_PLAN   = 1;

    private final String projectId;
    private final int mode;

    public C2S_TakeProjectMessage(String projectId, int mode) {
        this.projectId = projectId == null ? "" : projectId;
        this.mode = mode;
    }

    public static void encode(C2S_TakeProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.projectId, 64);
        buf.writeVarInt(msg.mode);
    }

    public static C2S_TakeProjectMessage decode(FriendlyByteBuf buf) {
        return new C2S_TakeProjectMessage(buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(C2S_TakeProjectMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            UUID projectId;
            ProjectData project;
            try {
                projectId = UUID.fromString(msg.projectId);
                project = ProjectStorage.loadProject(projectId);
            } catch (IllegalArgumentException e) {
                project = null;
                projectId = null;
            }
            if (project == null) {
                sendFail(player, "spatial_blueprint.message.project.take.missing");
                return;
            }

            ItemStack stack;
            if (msg.mode == MODE_PLAN) {
                if (!player.hasPermissions(2)) {
                    sendFail(player, "spatial_blueprint.message.project.take.plan_need_op");
                    return;
                }
                stack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            } else if (msg.mode == MODE_IMPORT) {
                switch (project.kind) {
                    case PUBLIC -> {
                        ProjectClaimRegistry.Claim claim = ProjectClaimRegistry
                                .get(player.serverLevel()).findPublicByProject(msg.projectId);
                        if (claim == null) {
                            sendFail(player, "spatial_blueprint.message.project.take.not_activated");
                            return;
                        }
                        stack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
                        ProjectBlueprintItem.setTaskSetId(stack, UUID.fromString(claim.taskSetId));
                    }
                    case PRIVATE -> {
                        // 项目版 H2：私人项目只有主人或 OP 能导入（无主非 OP 一律拒，T5）。
                        if (!C2S_RequestProjectListMessage.canAccessPrivate(project, player)) {
                            sendFail(player, "spatial_blueprint.message.project.take.not_activated");
                            return;
                        }
                        stack = new ItemStack(SpatialBlueprintItems.PRIVATE_PROJECT_BLUEPRINT.get());
                    }
                    default -> {
                        // 蓝图站的项目只能规划，不能导入
                        sendFail(player, "spatial_blueprint.message.project.take.station_no_import");
                        return;
                    }
                }
            } else {
                return;
            }

            ProjectBlueprintItem.setProjectId(stack, projectId);
            ProjectBlueprintItem.setProjectName(stack, project.name);
            ProjectBlueprintItem.setStage(stack, project.stage == null ? "" : project.stage.name());
            ProjectBlueprintItem.setDimension(stack, project.dimension);
            if (msg.mode == MODE_PLAN) {
                // 规划：换手上的物品（与蓝图库 ✔ 应用同一规则），不再往背包塞一枚新的 ——
                // 项目浏览界面只能从手持蓝图的界面打开，规划手柄就该顶掉手里那枚。
                net.minecraft.world.InteractionHand hand = C2S_ApplyBlueprintMessage.replaceTargetHand(player);
                player.setItemInHand(hand, stack);
                com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);
            } else if (!player.getInventory().add(stack)) {
                // 导入：仍是给一枚新的（生存玩家领任务集用，手里不一定拿着蓝图）
                player.drop(stack, false);
            }

            SpatialBlueprintMod.LOGGER.info("[Project] {} project '{}' id={} -> {}",
                    msg.mode == MODE_PLAN ? "Plan" : "Import", project.name, projectId,
                    player.getGameProfile().getName());
            player.displayClientMessage(Component.translatable(msg.mode == MODE_PLAN
                    ? "spatial_blueprint.message.project.take.planned"
                    : "spatial_blueprint.message.project.take.imported", project.name), false);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendFail(ServerPlayer player, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, key));
    }
}
