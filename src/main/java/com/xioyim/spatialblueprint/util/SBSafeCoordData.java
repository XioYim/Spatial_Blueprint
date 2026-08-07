package com.xioyim.spatialblueprint.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-world persistent storage for engineering blueprint safe teleport coordinates.
 * Keyed by blueprint ID (UUID string). Stored in world's saveddata as "sb_safe_coord".
 */
public class SBSafeCoordData extends SavedData {

    private static final String SAVE_KEY = "sb_safe_coord";

    public record SafeEntry(boolean hasCoord, int x, int y, int z, boolean allowSurvival) {}

    private final Map<String, SafeEntry> entries = new HashMap<>();

    public static SBSafeCoordData get(ServerLevel level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(SBSafeCoordData::load, SBSafeCoordData::new, SAVE_KEY);
    }

    private SBSafeCoordData() {}

    public void setEntry(String blueprintId, boolean hasCoord, int x, int y, int z, boolean allowSurvival) {
        entries.put(blueprintId, new SafeEntry(hasCoord, x, y, z, allowSurvival));
        setDirty();
    }

    @Nullable
    public SafeEntry getEntry(String blueprintId) {
        return entries.get(blueprintId);
    }

    // ── NBT persistence ──────────────────────────────────────────────────────

    public static SBSafeCoordData load(CompoundTag tag) {
        SBSafeCoordData data = new SBSafeCoordData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            String id = e.getString("id");
            boolean hasCoord = e.getBoolean("hasCoord");
            int x = e.getInt("x");
            int y = e.getInt("y");
            int z = e.getInt("z");
            boolean allowSurvival = e.getBoolean("allowSurvival");
            data.entries.put(id, new SafeEntry(hasCoord, x, y, z, allowSurvival));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, SafeEntry> e : entries.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", e.getKey());
            entry.putBoolean("hasCoord", e.getValue().hasCoord());
            entry.putInt("x", e.getValue().x());
            entry.putInt("y", e.getValue().y());
            entry.putInt("z", e.getValue().z());
            entry.putBoolean("allowSurvival", e.getValue().allowSurvival());
            list.add(entry);
        }
        tag.put("entries", list);
        return tag;
    }
}
