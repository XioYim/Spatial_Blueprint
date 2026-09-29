package com.xioyim.spatialblueprint.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 世界级项目坐标占用表，记录所有已激活的任务集。 */
public class ProjectClaimRegistry extends SavedData {

    private static final String SAVE_KEY = "sb_project_claims";

    /** 一条已激活的任务集记录：坐标占用 + 归属。 */
    public static class Claim {
        public String taskSetId = "";
        public String projectId = "";
        public String dimension = "";
        public boolean publicProject = false;
        public String ownerUuid = "";
        public String ownerName = "";
        public long createdAt = 0L;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        /** 世界基准点：激活时 = 玩家站位 + (project.anchor − project.origin)；扩建只动盒，不动它。 */
        public int anchorX, anchorY, anchorZ;
        /** 旧数据的锚点没记：false 时由 {@link ProjectClaimRegistry#anchorOf} 补算。 */
        public boolean hasAnchor = false;

        public CompoundTag serialize() {
            CompoundTag tag = new CompoundTag();
            tag.putString("taskSetId", taskSetId);
            tag.putString("projectId", projectId);
            tag.putString("dimension", dimension);
            tag.putBoolean("publicProject", publicProject);
            tag.putString("ownerUuid", ownerUuid);
            tag.putString("ownerName", ownerName);
            tag.putLong("createdAt", createdAt);
            tag.putInt("minX", minX);
            tag.putInt("minY", minY);
            tag.putInt("minZ", minZ);
            tag.putInt("maxX", maxX);
            tag.putInt("maxY", maxY);
            tag.putInt("maxZ", maxZ);
            tag.putInt("anchorX", anchorX);
            tag.putInt("anchorY", anchorY);
            tag.putInt("anchorZ", anchorZ);
            tag.putBoolean("hasAnchor", hasAnchor);
            return tag;
        }

        public static Claim deserialize(CompoundTag tag) {
            Claim claim = new Claim();
            claim.taskSetId = tag.getString("taskSetId");
            claim.projectId = tag.getString("projectId");
            claim.dimension = tag.getString("dimension");
            claim.publicProject = tag.getBoolean("publicProject");
            claim.ownerUuid = tag.getString("ownerUuid");
            claim.ownerName = tag.getString("ownerName");
            claim.createdAt = tag.getLong("createdAt");
            claim.minX = tag.getInt("minX");
            claim.minY = tag.getInt("minY");
            claim.minZ = tag.getInt("minZ");
            claim.maxX = tag.getInt("maxX");
            claim.maxY = tag.getInt("maxY");
            claim.maxZ = tag.getInt("maxZ");
            claim.anchorX = tag.getInt("anchorX");
            claim.anchorY = tag.getInt("anchorY");
            claim.anchorZ = tag.getInt("anchorZ");
            claim.hasAnchor = tag.getBoolean("hasAnchor");
            return claim;
        }
    }

    private final List<Claim> claims = new ArrayList<>();

    public static ProjectClaimRegistry get(ServerLevel level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(ProjectClaimRegistry::load, ProjectClaimRegistry::new, SAVE_KEY);
    }

    public List<Claim> all() {
        return Collections.unmodifiableList(claims);
    }

    /** 同维度内包围盒相交的第一条记录；无冲突返回 null。 */
    @Nullable
    public Claim findOverlap(String dimension,
                             int minX, int minY, int minZ,
                             int maxX, int maxY, int maxZ) {
        for (Claim claim : claims) {
            if (!claim.dimension.equals(dimension)) continue;
            boolean overlaps =
                    claim.minX <= maxX && claim.maxX >= minX
                 && claim.minY <= maxY && claim.maxY >= minY
                 && claim.minZ <= maxZ && claim.maxZ >= minZ;
            if (overlaps) return claim;
        }
        return null;
    }

    /** 与 {@link #findOverlap} 同算法，但跳过 taskSetId == excludeTaskSetId 的那条（扩建时排除自己）。 */
    @Nullable
    public Claim findOverlapExcluding(String excludeTaskSetId, String dimension,
                                      int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ) {
        for (Claim claim : claims) {
            if (claim.taskSetId.equals(excludeTaskSetId)) continue;
            if (!claim.dimension.equals(dimension)) continue;
            boolean overlaps =
                    claim.minX <= maxX && claim.maxX >= minX
                 && claim.minY <= maxY && claim.maxY >= minY
                 && claim.minZ <= maxZ && claim.maxZ >= minZ;
            if (overlaps) return claim;
        }
        return null;
    }

    /** 包含该坐标的记录；无则 null。供 OP 释放指令定位。 */
    @Nullable
    public Claim findAt(String dimension, int x, int y, int z) {
        for (Claim claim : claims) {
            if (!claim.dimension.equals(dimension)) continue;
            if (x >= claim.minX && x <= claim.maxX
                    && y >= claim.minY && y <= claim.maxY
                    && z >= claim.minZ && z <= claim.maxZ) {
                return claim;
            }
        }
        return null;
    }

    @Nullable
    public Claim findByTaskSet(String taskSetId) {
        for (Claim claim : claims) {
            if (claim.taskSetId.equals(taskSetId)) return claim;
        }
        return null;
    }

    /** 该项目的全部任务集（公用最多一条 + 各私人若干）；没有则空表。 */
    public List<Claim> findByProject(String projectId) {
        List<Claim> result = new ArrayList<>();
        for (Claim claim : claims) {
            if (claim.projectId.equals(projectId)) result.add(claim);
        }
        return result;
    }

    /**
     * 任务集的世界基准点：新 claim 用记录值；旧 claim（hasAnchor=false）按
     * 「激活时 min 对应 project.origin」补算 —— 世界 anchor 与 min 的差 = 作者 anchor 与 origin 的差。
     */
    public static net.minecraft.core.BlockPos anchorOf(Claim c, ProjectData p) {
        if (c.hasAnchor) {
            return new net.minecraft.core.BlockPos(c.anchorX, c.anchorY, c.anchorZ);
        }
        net.minecraft.core.BlockPos origin = p.origin == null
                ? net.minecraft.core.BlockPos.ZERO : p.origin;
        net.minecraft.core.BlockPos anchor = p.anchor == null ? origin : p.anchor;
        return new net.minecraft.core.BlockPos(c.minX, c.minY, c.minZ)
                .offset(anchor.subtract(origin));
    }

    /**
     * 条目绝对坐标的参考系基准：有任务集占用 → 该 claim 的世界 anchor，否则 → 作者 anchor。
     * 状态包（{@code C2S_RequestProjectStatusMessage}）与创建子工程的重叠校验必须用同一基准，
     * 两处客户 / 服务端的框才是同一口径。
     */
    public static net.minecraft.core.BlockPos entryBase(@Nullable Claim claim, ProjectData project) {
        if (claim != null) return anchorOf(claim, project);
        return project.anchor == null ? net.minecraft.core.BlockPos.ZERO : project.anchor;
    }

    /**
     * 该项目是否已被任何人（公用或私人）激活。
     * 规划模式的序号重排用它做门禁：进度键 {@code (taskSetId, entryIndex)} 与已发蓝图物品的
     * {@code sbEntryIndex} 都按序号存，重编号会指向别的条目。（范围扩建另有「只扩不缩」规则，不受它锁。）
     */
    public boolean hasAnyClaimForProject(String projectId) {
        for (Claim claim : claims) {
            if (claim.projectId.equals(projectId)) return true;
        }
        return false;
    }

    /** 某项目的全服任务集（公用项目只激活一次，最多一条）；无则 null。 */
    @Nullable
    public Claim findPublicByProject(String projectId) {
        for (Claim claim : claims) {
            if (claim.publicProject && claim.projectId.equals(projectId)) return claim;
        }
        return null;
    }

    /** 某玩家在某项目上自己激活的私人任务集；无则 null。 */
    @Nullable
    public Claim findPrivateByProjectAndOwner(String projectId, String ownerUuid) {
        for (Claim claim : claims) {
            if (!claim.publicProject && claim.projectId.equals(projectId)
                    && claim.ownerUuid.equals(ownerUuid)) return claim;
        }
        return null;
    }

    public void add(Claim claim) {
        claims.add(claim);
        setDirty();
    }

    /** 返回是否真的删掉了一条。 */
    public boolean removeByTaskSet(String taskSetId) {
        boolean removed = claims.removeIf(claim -> claim.taskSetId.equals(taskSetId));
        if (removed) setDirty();
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Claim claim : claims) list.add(claim.serialize());
        tag.put("claims", list);
        return tag;
    }

    private static ProjectClaimRegistry load(CompoundTag tag) {
        ProjectClaimRegistry registry = new ProjectClaimRegistry();
        ListTag list = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            registry.claims.add(Claim.deserialize(list.getCompound(i)));
        }
        return registry;
    }
}
