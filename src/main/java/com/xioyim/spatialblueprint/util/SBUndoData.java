package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * World SavedData：按玩家 UUID 存储空间蓝图撤回历史。
 * 保存在 overworld，与蓝图所在维度无关（记录内含维度信息）。
 */
public class SBUndoData extends SavedData {
    private static final String DATA_NAME = "sb_undo_history";

    private final Map<UUID, ArrayDeque<UndoRecord>> history = new HashMap<>();

    public static SBUndoData get(ServerLevel anyLevel) {
        return anyLevel.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(SBUndoData::new, SBUndoData::load, null), DATA_NAME);
    }

    /** 压入一条撤回记录；若超过 maxHistory 则丢弃最旧的。 */
    public void addRecord(UUID playerUUID, UndoRecord record, int maxHistory) {
        ArrayDeque<UndoRecord> deque = history.computeIfAbsent(playerUUID, k -> new ArrayDeque<>());
        deque.addLast(record);
        while (deque.size() > maxHistory) deque.removeFirst();
        setDirty();
    }

    /** 弹出最近一条（LIFO），无则返回 null。 */
    @Nullable
    public UndoRecord pop(UUID playerUUID) {
        ArrayDeque<UndoRecord> deque = history.get(playerUUID);
        if (deque == null || deque.isEmpty()) return null;
        UndoRecord r = deque.removeLast();
        setDirty();
        return r;
    }

    public boolean hasHistory(UUID playerUUID) {
        ArrayDeque<UndoRecord> deque = history.get(playerUUID);
        return deque != null && !deque.isEmpty();
    }

    // ── NBT 序列化 ────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("DataVersion", LegacyDataFixer.current());
        ListTag playersTag = new ListTag();
        for (Map.Entry<UUID, ArrayDeque<UndoRecord>> entry : history.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("uuid", entry.getKey());
            ListTag recordsTag = new ListTag();
            for (UndoRecord record : entry.getValue()) {
                recordsTag.add(serializeRecord(record));
            }
            playerTag.put("records", recordsTag);
            playersTag.add(playerTag);
        }
        tag.put("players", playersTag);
        return tag;
    }

    public static SBUndoData load(CompoundTag tag, HolderLookup.Provider provider) {
        SBUndoData data = new SBUndoData();
        // D2（SBN-011）：读不到版本字段视为 1.20.1 存档，快照按旧格式升级
        int from = LegacyDataFixer.effectiveFrom(
                tag.contains("DataVersion", Tag.TAG_INT) ? tag.getInt("DataVersion") : 0);
        boolean fix = LegacyDataFixer.needsFix(from);
        int[] unupgraded = new int[1];
        ListTag playersTag = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < playersTag.size(); i++) {
            CompoundTag playerTag = playersTag.getCompound(i);
            UUID uuid = playerTag.getUUID("uuid");
            ArrayDeque<UndoRecord> deque = new ArrayDeque<>();
            ListTag recordsTag = playerTag.getList("records", Tag.TAG_COMPOUND);
            for (int j = 0; j < recordsTag.size(); j++) {
                try {
                    UndoRecord r = deserializeRecord(recordsTag.getCompound(j), from, fix, unupgraded);
                    if (r != null) deque.addLast(r);
                } catch (Exception e) {
                    SpatialBlueprintMod.LOGGER.warn("[SBUndo] Failed to deserialize undo record: {}", e.getMessage());
                }
            }
            if (!deque.isEmpty()) data.history.put(uuid, deque);
        }
        if (fix) {
            data.setDirty();
            if (unupgraded[0] > 0) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[SBUndo] {} snapshot entries could not be upgraded from dataVersion {}",
                        unupgraded[0], from);
            }
        }
        return data;
    }

    private static CompoundTag serializeRecord(UndoRecord r) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", r.dimension().toString());
        tag.putInt("minX", r.minPos().getX());
        tag.putInt("minY", r.minPos().getY());
        tag.putInt("minZ", r.minPos().getZ());
        tag.putInt("maxX", r.maxPos().getX());
        tag.putInt("maxY", r.maxPos().getY());
        tag.putInt("maxZ", r.maxPos().getZ());

        ListTag blocksTag = new ListTag();
        for (UndoRecord.SnapshotBlock sb : r.blocks()) {
            CompoundTag bt = new CompoundTag();
            bt.putLong("pos", sb.pos().asLong());
            bt.put("state", NbtUtils.writeBlockState(sb.state()));
            if (sb.beNbt() != null) bt.put("be", sb.beNbt().copy());
            blocksTag.add(bt);
        }
        tag.put("blocks", blocksTag);

        ListTag entitiesTag = new ListTag();
        for (UndoRecord.SnapshotEntity se : r.entities()) {
            CompoundTag et = new CompoundTag();
            et.putString("type", se.entityType());
            et.put("nbt", se.nbt().copy());
            entitiesTag.add(et);
        }
        tag.put("entities", entitiesTag);
        return tag;
    }

    @Nullable
    private static UndoRecord deserializeRecord(CompoundTag tag, int from, boolean fix, int[] unupgraded) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
        if (dim == null) return null;

        BlockPos min = new BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ"));
        BlockPos max = new BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"));

        List<UndoRecord.SnapshotBlock> blocks = new ArrayList<>();
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag bt = blocksTag.getCompound(i);
            BlockPos pos = BlockPos.of(bt.getLong("pos"));
            CompoundTag stateTag = bt.getCompound("state");
            if (fix) {
                LegacyDataFixer.FixResult r = LegacyDataFixer.tryFixBlockState(stateTag, from);
                if (!r.ok()) unupgraded[0]++;
                stateTag = r.tag();
            }
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTag);
            CompoundTag beNbt = bt.contains("be") ? bt.getCompound("be") : null;
            if (fix && beNbt != null) {
                LegacyDataFixer.FixResult r = LegacyDataFixer.tryFixBlockEntity(beNbt, from);
                if (!r.ok()) unupgraded[0]++;
                beNbt = r.tag();
            }
            blocks.add(new UndoRecord.SnapshotBlock(pos, state, beNbt));
        }

        List<UndoRecord.SnapshotEntity> entities = new ArrayList<>();
        ListTag entitiesTag = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entitiesTag.size(); i++) {
            CompoundTag et = entitiesTag.getCompound(i);
            CompoundTag entityNbt = et.getCompound("nbt");
            if (fix) {
                LegacyDataFixer.FixResult r = LegacyDataFixer.tryFixEntity(entityNbt, from);
                if (!r.ok()) unupgraded[0]++;
                entityNbt = r.tag();
            }
            entities.add(new UndoRecord.SnapshotEntity(et.getString("type"), entityNbt));
        }

        return new UndoRecord(dim, min, max, blocks, entities);
    }
}
