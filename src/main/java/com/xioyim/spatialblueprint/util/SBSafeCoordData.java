package com.xioyim.spatialblueprint.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工程蓝图的「安全传送坐标」：按 blueprintId 存一个可选的 XYZ，[Tp] 有它就传到这个点，
 * 没有才传到起点 / 终点里离玩家近的那个。世界级 SavedData（键 {@code sb_safe_coord}），
 * 不写进蓝图文件 —— 传送时 O(1) 查，不用为一个坐标加载整份蓝图。
 *
 * <p>只有坐标。传送权限完全归 {@code /sb tpperm}（{@code TeleportPermissions}），
 * 这里不再有按蓝图的放行开关；SB-009 前旧存档里的 {@code allowSurvival} 键读时忽略。</p>
 */
public class SBSafeCoordData extends SavedData {

    private static final String SAVE_KEY = "sb_safe_coord";

    public record SafeEntry(boolean hasCoord, int x, int y, int z) {}

    // 存盘写成 ListTag：用 LinkedHashMap 保证 save → load → save 逐字相同（K25，SBN-014 验收）
    private final Map<String, SafeEntry> entries = new LinkedHashMap<>();

    public static SBSafeCoordData get(ServerLevel level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(SBSafeCoordData::new, SBSafeCoordData::load, null), SAVE_KEY);
    }

    private SBSafeCoordData() {}

    public void setEntry(String blueprintId, boolean hasCoord, int x, int y, int z) {
        entries.put(blueprintId, new SafeEntry(hasCoord, x, y, z));
        setDirty();
    }

    @Nullable
    public SafeEntry getEntry(String blueprintId) {
        return entries.get(blueprintId);
    }

    // ── NBT persistence ──────────────────────────────────────────────────────

    public static SBSafeCoordData load(CompoundTag tag, HolderLookup.Provider provider) {
        SBSafeCoordData data = new SBSafeCoordData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            String id = e.getString("id");
            boolean hasCoord = e.getBoolean("hasCoord");
            data.entries.put(id, new SafeEntry(hasCoord, e.getInt("x"), e.getInt("y"), e.getInt("z")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<String, SafeEntry> e : entries.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", e.getKey());
            entry.putBoolean("hasCoord", e.getValue().hasCoord());
            entry.putInt("x", e.getValue().x());
            entry.putInt("y", e.getValue().y());
            entry.putInt("z", e.getValue().z());
            list.add(entry);
        }
        tag.put("entries", list);
        return tag;
    }
}
