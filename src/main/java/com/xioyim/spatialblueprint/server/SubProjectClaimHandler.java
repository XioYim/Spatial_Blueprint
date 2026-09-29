package com.xioyim.spatialblueprint.server;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class SubProjectClaimHandler {

    private SubProjectClaimHandler() {}

    /** 领取结果：成功与否 + 要显示的 lang key + 参数。 */
    public record Result(boolean success, String messageKey, Object[] args,
                         boolean alreadyDone) {}

    /**
     * 领取一个子工程。玩家必须<b>主手</b>持有已激活的项目蓝图。
     * 指令与界面共用这一份逻辑 —— 两边各写一份必然走样。
     */
    public static Result claim(ServerPlayer player, int entryIndex) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ProjectBlueprintItem)) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.need_item", new Object[0], false);
        }

        String taskSetId = ProjectBlueprintItem.getTaskSetId(held);
        if (taskSetId == null) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.not_activated", new Object[0], false);
        }

        String projectIdString = ProjectBlueprintItem.getProjectId(held);
        UUID projectId;
        ProjectData project;
        try {
            projectId = UUID.fromString(projectIdString == null ? "" : projectIdString);
            project = ProjectStorage.loadProject(projectId);
        } catch (IllegalArgumentException e) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.project_missing", new Object[0], false);
        }
        if (project == null) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.project_missing", new Object[0], false);
        }

        ProjectClaimRegistry registry = ProjectClaimRegistry.get(player.serverLevel());
        ProjectClaimRegistry.Claim claim = registry.findByTaskSet(taskSetId);
        if (claim == null || !projectIdString.equals(claim.projectId)) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.claim_missing", new Object[0], false);
        }

        ProjectData.EntryData entry = null;
        for (ProjectData.EntryData candidate : project.entries) {
            if (candidate.index == entryIndex) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.no_such_entry",
                    new Object[]{entryIndex}, false);
        }

        ProjectProgressData progress = ProjectProgressData.get(player.serverLevel());
        if (entry.requiresEntry != 0
                && !progress.isCompleted(taskSetId, entry.requiresEntry)) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.prerequisite",
                    new Object[]{entry.requiresEntry}, false);
        }

        long cooldownMs = BlueprintConfig.SUBPROJECT_CLAIM_COOLDOWN.get() * 1000L;
        long now = System.currentTimeMillis();
        long lastClaimAt = progress.lastClaimAt(player.getUUID().toString(), taskSetId, entryIndex);
        if (cooldownMs > 0 && lastClaimAt > 0 && now - lastClaimAt < cooldownMs) {
            long remainingMs = cooldownMs - Math.max(0L, now - lastClaimAt);
            long remainingSeconds = (remainingMs + 999L) / 1000L;
            return new Result(false,
                    "spatial_blueprint.command.project.claim.cooldown",
                    new Object[]{remainingSeconds}, false);
        }

        // 已完成不是拒绝条件：仍允许领取重建，只额外提示会再次消耗材料
        boolean alreadyDone = progress.isCompleted(taskSetId, entryIndex);

        String filename = entry.filename == null ? "" : entry.filename;
        if (!filename.endsWith(".litematic")) {
            return new Result(false,
                    "spatial_blueprint.command.project.claim.project_missing", new Object[0], alreadyDone);
        }
        String engUuid = filename.substring(0, filename.length() - ".litematic".length());

        // 绝对坐标必须从任务集的基准点算（anchor 永不改；扩建只动盒与 claim 的 min/max）
        BlockPos activationOrigin = ProjectClaimRegistry.anchorOf(claim, project);
        BlockPos absP1 = activationOrigin.offset(entry.relP1);
        BlockPos absP2 = activationOrigin.offset(entry.relP2);

        ItemStack stack = new ItemStack(SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get());
        AbstractBlueprintItem.setBlueprintId(stack, engUuid);
        AbstractBlueprintItem.setBlueprintName(stack, entry.label == null ? "" : entry.label);
        EngineeringBlueprintItem.setBoundProject(stack, projectIdString,
                entry.label == null ? "" : entry.label,
                claim.dimension, absP1, absP2);
        EngineeringBlueprintItem.setTaskSetId(stack, taskSetId);
        EngineeringBlueprintItem.setEntryIndex(stack, entryIndex);

        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        progress.markClaimed(player.getUUID().toString(), taskSetId, entryIndex);

        String label = entry.label == null ? "" : entry.label;
        return new Result(true,
                "spatial_blueprint.command.project.claim.success",
                new Object[]{label, entryIndex}, alreadyDone);
    }
}
