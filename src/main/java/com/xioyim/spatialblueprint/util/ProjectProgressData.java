package com.xioyim.spatialblueprint.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 世界级项目进度：按任务集记录完成状态，并按玩家/任务集/条目记录领取冷却。 */
public class ProjectProgressData extends SavedData {

    private static final String SAVE_KEY = "sb_project_progress";

    /** 完成状态必须按任务集隔离：公用项目共享一份，私人项目每位玩家各一份。 */
    private final Map<String, Set<Integer>> completedByTaskSet = new HashMap<>();
    /** 同一玩家、同一任务集、同一条目的最后领取时间（epoch millis）。 */
    private final Map<ClaimKey, Long> lastClaimTimes = new HashMap<>();

    private record ClaimKey(String playerUuid, String taskSetId, int entryIndex) {}

    public static ProjectProgressData get(ServerLevel level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(ProjectProgressData::load, ProjectProgressData::new, SAVE_KEY);
    }

    public boolean isCompleted(String taskSetId, int entryIndex) {
        Set<Integer> completed = completedByTaskSet.get(taskSetId);
        return completed != null && completed.contains(entryIndex);
    }

    public void markCompleted(String taskSetId, int entryIndex) {
        if (completedByTaskSet.computeIfAbsent(taskSetId, ignored -> new HashSet<>()).add(entryIndex)) {
            setDirty();
        }
    }

    /** 返回副本的只读视图，调用方不能改动内部进度。 */
    public Set<Integer> completedOf(String taskSetId) {
        Set<Integer> completed = completedByTaskSet.get(taskSetId);
        if (completed == null) return Collections.emptySet();
        return Collections.unmodifiableSet(new HashSet<>(completed));
    }

    public long lastClaimAt(String playerUuid, String taskSetId, int entryIndex) {
        return lastClaimTimes.getOrDefault(new ClaimKey(playerUuid, taskSetId, entryIndex), 0L);
    }

    public void markClaimed(String playerUuid, String taskSetId, int entryIndex) {
        lastClaimTimes.put(new ClaimKey(playerUuid, taskSetId, entryIndex), System.currentTimeMillis());
        setDirty();
    }

    /**
     * 删除子工程时清进度：从每个任务集的完成集合移除该序号，并清掉该序号的全部领取冷却；
     * 有改动才 setDirty。
     */
    public void forgetEntry(java.util.Collection<String> taskSetIds, int entryIndex) {
        boolean dirty = false;
        for (String taskSetId : taskSetIds) {
            Set<Integer> completed = completedByTaskSet.get(taskSetId);
            if (completed != null && completed.remove(entryIndex)) dirty = true;
        }
        if (lastClaimTimes.entrySet().removeIf(e ->
                taskSetIds.contains(e.getKey().taskSetId()) && e.getKey().entryIndex() == entryIndex)) {
            dirty = true;
        }
        if (dirty) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag completedList = new ListTag();
        for (Map.Entry<String, Set<Integer>> taskSet : completedByTaskSet.entrySet()) {
            for (int entryIndex : taskSet.getValue()) {
                CompoundTag completedTag = new CompoundTag();
                completedTag.putString("taskSetId", taskSet.getKey());
                completedTag.putInt("entryIndex", entryIndex);
                completedList.add(completedTag);
            }
        }
        tag.put("completed", completedList);

        ListTag claimList = new ListTag();
        for (Map.Entry<ClaimKey, Long> claim : lastClaimTimes.entrySet()) {
            CompoundTag claimTag = new CompoundTag();
            claimTag.putString("playerUuid", claim.getKey().playerUuid());
            claimTag.putString("taskSetId", claim.getKey().taskSetId());
            claimTag.putInt("entryIndex", claim.getKey().entryIndex());
            claimTag.putLong("lastClaimAt", claim.getValue());
            claimList.add(claimTag);
        }
        tag.put("claimCooldowns", claimList);
        return tag;
    }

    private static ProjectProgressData load(CompoundTag tag) {
        ProjectProgressData data = new ProjectProgressData();

        ListTag completedList = tag.getList("completed", Tag.TAG_COMPOUND);
        for (int i = 0; i < completedList.size(); i++) {
            CompoundTag completedTag = completedList.getCompound(i);
            String taskSetId = completedTag.getString("taskSetId");
            int entryIndex = completedTag.getInt("entryIndex");
            data.completedByTaskSet
                    .computeIfAbsent(taskSetId, ignored -> new HashSet<>())
                    .add(entryIndex);
        }

        ListTag claimList = tag.getList("claimCooldowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimList.size(); i++) {
            CompoundTag claimTag = claimList.getCompound(i);
            ClaimKey key = new ClaimKey(
                    claimTag.getString("playerUuid"),
                    claimTag.getString("taskSetId"),
                    claimTag.getInt("entryIndex"));
            data.lastClaimTimes.put(key, claimTag.getLong("lastClaimAt"));
        }
        return data;
    }
}
