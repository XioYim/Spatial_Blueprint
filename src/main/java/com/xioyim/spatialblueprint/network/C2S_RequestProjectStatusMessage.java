package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** C2S：查询玩家主手项目蓝图的实时条目状态。请求本身不携带任何项目标识。 */
public class C2S_RequestProjectStatusMessage {

    public C2S_RequestProjectStatusMessage() {}

    public static void encode(C2S_RequestProjectStatusMessage msg, FriendlyByteBuf buf) {}

    public static C2S_RequestProjectStatusMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestProjectStatusMessage();
    }

    public static void handle(C2S_RequestProjectStatusMessage msg,
                              Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) sendStatus(player);
        });
        ctx.get().setPacketHandled(true);
    }

    /** 把主手（或副手）项目蓝图的最新状态推给玩家；领取成功后也调用这一入口。 */
    public static void sendStatus(ServerPlayer player) {
        ItemStack held = ProjectBlueprintItem.findHeld(player);
        if (held.isEmpty()) {
            send(player, S2C_ProjectStatusMessage.notFound());
            return;
        }

        String projectIdString = ProjectBlueprintItem.getProjectId(held);
        String taskSetId = ProjectBlueprintItem.getTaskSetId(held);
        ProjectData project;
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(projectIdString == null ? "" : projectIdString);
            project = ProjectStorage.loadProject(projectUuid);
        } catch (IllegalArgumentException e) {
            send(player, S2C_ProjectStatusMessage.notFound(
                    projectIdString == null ? "" : projectIdString));
            return;
        }
        if (project == null) {
            // 项目已被删除：带上 projectId，客户端据此弹「项目已被删除」横幅并拦右键
            send(player, S2C_ProjectStatusMessage.notFound(
                    projectIdString == null ? "" : projectIdString));
            return;
        }

        boolean activated = taskSetId != null;
        ProjectProgressData progress = activated
                ? ProjectProgressData.get(player.serverLevel()) : null;
        long cooldownMs = BlueprintConfig.SUBPROJECT_CLAIM_COOLDOWN.get() * 1000L;
        long now = System.currentTimeMillis();
        List<S2C_ProjectStatusMessage.EntryStatus> entries = new ArrayList<>();
        boolean projectDirty = false;

        // 条目绝对坐标的参考系：已激活 = 该任务集的世界基准点（旧 claim 由 anchorOf 补算），否则 = 作者 anchor
        // （与下面 worldAnchor 同一口径；先算出来给每条 entry 用）
        com.xioyim.spatialblueprint.util.ProjectClaimRegistry.Claim claim = activated
                ? com.xioyim.spatialblueprint.util.ProjectClaimRegistry.get(player.serverLevel()).findByTaskSet(taskSetId)
                : null;
        net.minecraft.core.BlockPos entryBase =
                com.xioyim.spatialblueprint.util.ProjectClaimRegistry.entryBase(claim, project);

        for (ProjectData.EntryData entry : project.entries) {
            boolean completed = activated && progress.isCompleted(taskSetId, entry.index);
            int cooldownRemainSec = 0;
            if (activated && cooldownMs > 0) {
                long lastClaimAt = progress.lastClaimAt(
                        player.getUUID().toString(), taskSetId, entry.index);
                if (lastClaimAt > 0) {
                    long elapsedMs = Math.max(0L, now - lastClaimAt);
                    long remainingMs = Math.max(0L, cooldownMs - elapsedMs);
                    cooldownRemainSec = (int) Math.min(Integer.MAX_VALUE,
                            (remainingMs + 999L) / 1000L);
                }
            }
            boolean prereqMet = activated && (entry.requiresEntry == 0
                    || progress.isCompleted(taskSetId, entry.requiresEntry));
            // 旧数据（没有 blockCount / 元数据键）读一次子工程文件补上，成功落盘；失败后不再重试
            if (entry.blockCount == -1 || !entry.metaLoaded) {
                loadEntryMeta(entry);
                projectDirty = true;
            }
            entries.add(new S2C_ProjectStatusMessage.EntryStatus(
                    entry.index, entry.label == null ? "" : entry.label,
                    entry.note == null ? "" : entry.note,
                    Math.abs(entry.relP2.getX() - entry.relP1.getX()) + 1,
                    Math.abs(entry.relP2.getY() - entry.relP1.getY()) + 1,
                    Math.abs(entry.relP2.getZ() - entry.relP1.getZ()) + 1,
                    entry.blockCount,
                    completed, cooldownRemainSec, entry.requiresEntry, prereqMet,
                    entry.animDuration, entry.creatorName == null ? "" : entry.creatorName, entry.createdAt,
                    entryBase.offset(entry.relP1), entryBase.offset(entry.relP2),
                    entry.color, engIdOf(entry.filename),
                    entry.noteLong == null ? "" : entry.noteLong));
        }
        if (projectDirty) {
            ProjectStorage.saveProject(projectUuid, project);
        }

        // 序号重排的门禁：任何任务集占用（公用 / 私人）都会让进度键与已发蓝图物品的
        // sbEntryIndex 对不上新编号，因此有 claim 就整单禁改
        boolean orderEditable = !com.xioyim.spatialblueprint.util.ProjectClaimRegistry
                .get(player.serverLevel()).hasAnyClaimForProject(projectIdString);

        // 范围：已激活按任务集的实际占用（激活原点），否则按 project.dat 的作者参考系（claim 在上面已查）
        boolean hasRange = false;
        net.minecraft.core.BlockPos origin = net.minecraft.core.BlockPos.ZERO;
        net.minecraft.core.BlockPos size = net.minecraft.core.BlockPos.ZERO;
        if (claim != null) {
            hasRange = true;
            origin = new net.minecraft.core.BlockPos(claim.minX, claim.minY, claim.minZ);
            size = new net.minecraft.core.BlockPos(claim.maxX - claim.minX + 1,
                    claim.maxY - claim.minY + 1, claim.maxZ - claim.minZ + 1);
        } else if (project.size != null
                && project.size.getX() > 0 && project.size.getY() > 0 && project.size.getZ() > 0) {
            hasRange = true;
            origin = project.origin;
            size = project.size;
        }

        // 作者参考系（[规划项目] 预填用，与上面的「显示范围」分开）：size 无效时按 ZERO 发
        net.minecraft.core.BlockPos authorOrigin = net.minecraft.core.BlockPos.ZERO;
        net.minecraft.core.BlockPos authorSize = net.minecraft.core.BlockPos.ZERO;
        if (project.size != null
                && project.size.getX() > 0 && project.size.getY() > 0 && project.size.getZ() > 0) {
            authorOrigin = project.origin == null
                    ? net.minecraft.core.BlockPos.ZERO : project.origin;
            authorSize = project.size;
        }

        // 基准点：与 ProjectData.hasAnchor() 同判据（size 有效即有 anchor）；
        // worldAnchor = 已激活时该任务集的世界基准点（旧 claim 由 anchorOf 补算），否则 = 作者 anchor
        boolean hasAnchor = project.hasAnchor();
        net.minecraft.core.BlockPos authorAnchor = hasAnchor && project.anchor != null
                ? project.anchor : net.minecraft.core.BlockPos.ZERO;
        net.minecraft.core.BlockPos worldAnchor = hasAnchor && claim != null
                ? com.xioyim.spatialblueprint.util.ProjectClaimRegistry.anchorOf(claim, project)
                : authorAnchor;

        // 阶段 / 维度同步到物品 NBT（副手直开门槛用 stage 判「布局中 / 规划中」，右键拦截与编辑态白框
        // 用 projectDim 判「在不在项目维度」；旧数据补齐）。两项合一次 syncHand
        String stageName = project.stage == null ? "" : project.stage.name();
        String dimName = project.dimension == null ? "" : project.dimension;
        boolean nbtChanged = false;
        if (!stageName.equals(ProjectBlueprintItem.getStage(held))) {
            ProjectBlueprintItem.setStage(held, stageName);
            nbtChanged = true;
        }
        if (!dimName.equals(ProjectBlueprintItem.getDimension(held))) {
            ProjectBlueprintItem.setDimension(held, dimName);
            nbtChanged = true;
        }
        if (nbtChanged) {
            net.minecraft.world.InteractionHand hand = ProjectBlueprintItem.findHeldHand(player);
            if (hand != null) {
                com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);
            }
        }

        send(player, new S2C_ProjectStatusMessage(true, projectIdString, project.name,
                activated ? taskSetId : "", activated, entries, hasRange, origin, size,
                authorOrigin, authorSize, hasAnchor, authorAnchor, worldAnchor,
                orderEditable, dimName));
    }

    /**
     * 旧数据补元数据：读一次子工程文件，把方块数（仅当还是 -1）/ 建造时长 / 划定者 / 划定时间写进 EntryData，
     * 调用方落盘避免反复读盘。文件缺失 / 读挂：blockCount 写 -2、其余保持默认（界面显示 "-"），
     * 并同样置 metaLoaded = true —— 只在第一次读状态时走一次，之后直接用落盘值。
     */
    private static void loadEntryMeta(ProjectData.EntryData entry) {
        entry.metaLoaded = true;
        String filename = entry.filename == null ? "" : entry.filename;
        if (!filename.endsWith(".litematic")) {
            if (entry.blockCount == -1) entry.blockCount = -2;
            return;
        }
        String engId = filename.substring(0, filename.length() - ".litematic".length());
        try {
            com.xioyim.spatialblueprint.util.BlueprintData data =
                    com.xioyim.spatialblueprint.util.BlueprintStorage.load(engId);
            if (data == null) {
                if (entry.blockCount == -1) entry.blockCount = -2;
                return;
            }
            if (entry.blockCount == -1) {
                entry.blockCount = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            }
            entry.animDuration = data.animDuration;
            entry.creatorName  = data.creatorName == null ? "" : data.creatorName;
            entry.createdAt    = data.createdAt;
        } catch (Exception e) {
            if (entry.blockCount == -1) entry.blockCount = -2;
        }
    }

    /**
     * 从子工程文件名截出文件 id（客户端下载材料数据用）：{engUuid}.litematic → {engUuid}。
     * 文件名不合规（旧数据 / 缺键）时回空串，客户端据此禁用材料按钮。
     */
    private static String engIdOf(String filename) {
        String f = filename == null ? "" : filename;
        if (!f.endsWith(".litematic")) return "";
        return f.substring(0, f.length() - ".litematic".length());
    }

    private static void send(ServerPlayer player, S2C_ProjectStatusMessage message) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), message);
    }
}
