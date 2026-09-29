package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：「设定项目蓝图范围」界面点 [保存]。
 *
 * <p>不带载荷：两点从主手项目蓝图的临时 NBT 读（服务端自己写的，不信任客户端再发一遍）。
 * 校验：OP、主手项目蓝图、两点齐全、项目存在、体积上限
 * （{@code maxVolumeFor("projectset")}，0 = 不限；硬顶 {@link BlueprintConfig#ABSOLUTE_MAX_CAPTURE_VOLUME}）。</p>
 *
 * <p>两条路：<b>首次</b>（size 任一轴 ≤ 0）→ origin/size 照写，<b>anchor = 两点中点</b>（{@link ProjectData#centerOf}）；
 * <b>已有范围</b> → 激活后也允许改，但<b>只扩不缩</b>（新范围必须完整包含旧范围），且扩建后
 * 每个任务集的新盒不得与别人的占用重叠（排除自己的 taskSetId）—— 全过才写，
 * anchor / entries / 进度一概不动，只改 project.origin/size 与各 claim 的 min/max。</p>
 *
 * <p>成功后清掉临时两点并推一份最新状态。</p>
 */
public class C2S_SaveProjectRangeMessage {

    public C2S_SaveProjectRangeMessage() {}

    public static void encode(C2S_SaveProjectRangeMessage msg, FriendlyByteBuf buf) {}

    public static C2S_SaveProjectRangeMessage decode(FriendlyByteBuf buf) {
        return new C2S_SaveProjectRangeMessage();
    }

    public static void handle(C2S_SaveProjectRangeMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!player.hasPermissions(2)) {
                sendFail(player, "spatial_blueprint.message.project.range.need_op");
                return;
            }
            ItemStack stack = ProjectBlueprintItem.findHeld(player);
            if (stack.isEmpty()) {
                sendFail(player, "spatial_blueprint.message.project.range.need_item");
                return;
            }
            BlockPos p1 = ProjectBlueprintItem.getRangePoint(stack, 1);
            BlockPos p2 = ProjectBlueprintItem.getRangePoint(stack, 2);
            if (p1 == null || p2 == null) {
                sendFail(player, "spatial_blueprint.message.project.range.missing_points");
                return;
            }

            String projectIdString = ProjectBlueprintItem.getProjectId(stack);
            UUID projectId;
            ProjectData project;
            try {
                projectId = UUID.fromString(projectIdString == null ? "" : projectIdString);
                project = ProjectStorage.loadProject(projectId);
            } catch (IllegalArgumentException e) {
                projectId = null;
                project = null;
            }
            if (project == null) {
                sendFail(player, "spatial_blueprint.message.project.take.missing");
                return;
            }
            // 项目维度创建时固定：范围只能在那个维度里设（客户端已拦右键，这里是底线；e37）
            if (!project.isInDimension(player.serverLevel())) {
                sendFail(player, "spatial_blueprint.message.project.wrong_dimension_blocked",
                        "spatial_blueprint.detail.project.wrong_dimension",
                        project.dimension, ProjectData.dimensionOf(player.serverLevel()));
                return;
            }

            long volume = BlueprintValidator.volume(p1, p2);
            int limit = BlueprintConfig.getMaxVolumeFor("projectset");
            if ((limit > 0 && volume > limit) || volume > BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME) {
                sendFail(player, "spatial_blueprint.message.project.range.too_large",
                        "spatial_blueprint.detail.project.range.too_large",
                        volume, limit > 0 ? limit : BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME);
                return;
            }

            BlockPos newOrigin = new BlockPos(
                    Math.min(p1.getX(), p2.getX()),
                    Math.min(p1.getY(), p2.getY()),
                    Math.min(p1.getZ(), p2.getZ()));
            BlockPos newSize = new BlockPos(
                    Math.abs(p2.getX() - p1.getX()) + 1,
                    Math.abs(p2.getY() - p1.getY()) + 1,
                    Math.abs(p2.getZ() - p1.getZ()) + 1);

            ProjectClaimRegistry registry = ProjectClaimRegistry.get(player.serverLevel());
            boolean firstRange = project.size == null
                    || project.size.getX() <= 0 || project.size.getY() <= 0 || project.size.getZ() <= 0;

            if (firstRange) {
                // 首次保存：写范围并定基准点（anchor = 两点中点，从此只读；界面预告用的是同一个 centerOf）
                project.origin = newOrigin;
                project.size = newSize;
                project.anchor = ProjectData.centerOf(p1, p2);
            } else {
                BlockPos oldOrigin = project.origin == null ? BlockPos.ZERO : project.origin;
                BlockPos oldSize = project.size;
                int oldMaxX = oldOrigin.getX() + oldSize.getX() - 1;
                int oldMaxY = oldOrigin.getY() + oldSize.getY() - 1;
                int oldMaxZ = oldOrigin.getZ() + oldSize.getZ() - 1;
                // i. 只扩不缩：新范围必须完整包含旧范围
                if (newOrigin.getX() > oldOrigin.getX() || newOrigin.getY() > oldOrigin.getY()
                        || newOrigin.getZ() > oldOrigin.getZ()
                        || newOrigin.getX() + newSize.getX() - 1 < oldMaxX
                        || newOrigin.getY() + newSize.getY() - 1 < oldMaxY
                        || newOrigin.getZ() + newSize.getZ() - 1 < oldMaxZ) {
                    sendFail(player, "spatial_blueprint.message.project.range.shrink_not_allowed");
                    return;
                }
                BlockPos authorAnchor = project.anchor == null ? oldOrigin : project.anchor;
                List<ProjectClaimRegistry.Claim> projectClaims = registry.findByProject(projectIdString);
                // 先算每个 claim 的世界基准点（对旧 claim 依赖 project.origin，改之前算好）
                List<BlockPos> worldAnchors = new ArrayList<>(projectClaims.size());
                for (ProjectClaimRegistry.Claim claim : projectClaims) {
                    worldAnchors.add(ProjectClaimRegistry.anchorOf(claim, project));
                }
                // ii. 扩建后每个任务集的新盒不得与别人的占用重叠（排除自己的 taskSetId）
                for (int i = 0; i < projectClaims.size(); i++) {
                    ProjectClaimRegistry.Claim claim = projectClaims.get(i);
                    BlockPos w = worldAnchors.get(i);
                    int cMinX = w.getX() + (newOrigin.getX() - authorAnchor.getX());
                    int cMinY = w.getY() + (newOrigin.getY() - authorAnchor.getY());
                    int cMinZ = w.getZ() + (newOrigin.getZ() - authorAnchor.getZ());
                    ProjectClaimRegistry.Claim conflict = registry.findOverlapExcluding(
                            claim.taskSetId, claim.dimension,
                            cMinX, cMinY, cMinZ,
                            cMinX + newSize.getX() - 1,
                            cMinY + newSize.getY() - 1,
                            cMinZ + newSize.getZ() - 1);
                    if (conflict != null) {
                        sendFail(player, "spatial_blueprint.message.project.range.claim_conflict",
                                "spatial_blueprint.detail.project.range.claim_conflict",
                                claim.ownerName, conflict.ownerName,
                                conflict.minX, conflict.minY, conflict.minZ);
                        return;
                    }
                }
                // iii. 全过再写：盒改、每个 claim 的盒跟着改；anchor / entries / 进度都不动
                project.origin = newOrigin;
                project.size = newSize;
                for (int i = 0; i < projectClaims.size(); i++) {
                    ProjectClaimRegistry.Claim claim = projectClaims.get(i);
                    BlockPos w = worldAnchors.get(i);
                    claim.minX = w.getX() + (newOrigin.getX() - authorAnchor.getX());
                    claim.minY = w.getY() + (newOrigin.getY() - authorAnchor.getY());
                    claim.minZ = w.getZ() + (newOrigin.getZ() - authorAnchor.getZ());
                    claim.maxX = claim.minX + newSize.getX() - 1;
                    claim.maxY = claim.minY + newSize.getY() - 1;
                    claim.maxZ = claim.minZ + newSize.getZ() - 1;
                }
                registry.setDirty();
            }

            project.rotation = Rotation.NONE;
            // 设定好范围 = 「布局中」；已经推进到规划中的项目改范围不倒退
            if (project.stage == ProjectData.Stage.PREPARING) project.stage = ProjectData.Stage.LAYOUT;
            ProjectStorage.saveProject(projectId, project);

            ProjectBlueprintItem.clearRangePoints(stack);
            // 阶段可能推进（准备中 → 布局中）：同步到物品 NBT，副手直开门槛用它；维度顺手补齐
            ProjectBlueprintItem.setStage(stack,
                    project.stage == null ? "" : project.stage.name());
            ProjectBlueprintItem.setDimension(stack, project.dimension);
            InteractionHand syncHand = ProjectBlueprintItem.findHeldHand(player);
            if (syncHand != null) ItemSyncUtil.syncHand(player, syncHand);

            SpatialBlueprintMod.LOGGER.info("[Project] Range saved project='{}' id={} origin={} size={} anchor={} by {}",
                    project.name, projectId, project.origin, project.size, project.anchor,
                    player.getGameProfile().getName());
            player.displayClientMessage(Component.translatable(
                    "spatial_blueprint.message.project.range.saved",
                    project.name, project.size.getX(), project.size.getY(), project.size.getZ()), false);
            // 项目界面的预览要用新范围
            C2S_RequestProjectStatusMessage.sendStatus(player);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendFail(ServerPlayer player, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, key));
    }

    /** 参数走 detail，让客户端按自己的语言渲染（专服没加载 lang）。 */
    private static void sendFail(ServerPlayer player, String key, String detailKey, Object... args) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, key, detailKey, args));
    }
}
