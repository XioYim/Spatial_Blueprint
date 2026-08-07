package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 材料清单：统计蓝图中每种方块的用量。
 *
 * <p>内嵌于 {@link BlueprintData#materialList}，随蓝图一起序列化到 {@code SpatialBlueprintData}
 * 顶层键下（见 {@link BlueprintData#serialize()}），没有单独的文件。</p>
 */
public class MaterialList {

    /** 所属蓝图名称（冗余存储，方便脱离 BlueprintData 单独识别）。 */
    public String blueprintName = "";

    /**
     * 方块注册名 → 数量。
     * 使用 {@link LinkedHashMap} 保持插入顺序，序列化/反序列化后顺序一致。
     */
    public final LinkedHashMap<String, Integer> blockCounts = new LinkedHashMap<>();

    // ── 构建 ──────────────────────────────────────────────────────────────

    /**
     * 从 {@link BlueprintData} 统计所有非空气方块的用量（normal + dependent 两列表）。
     *
     * <p>配置文件中定义的方块替换（{@code blockSubstitutions}）会在此处应用：
     * 例如 {@code minecraft:weeping_vines_plant} → {@code minecraft:weeping_vines}，
     * 使生存玩家知道需要收集哪种可获取的物品。</p>
     */
    public static MaterialList fromBlueprintData(BlueprintData data) {
        MaterialList ml = new MaterialList();
        ml.blueprintName = data.name;
        Map<String, String> subs = BlueprintConfig.getBlockSubstitutions();
        for (BlueprintData.BlockEntry entry : data.normalBlocks) {
            String id     = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock()).toString();
            String mapped = subs.getOrDefault(id, id);
            ml.blockCounts.merge(mapped, 1, Integer::sum);
        }
        for (BlueprintData.BlockEntry entry : data.dependentBlocks) {
            String id     = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock()).toString();
            String mapped = subs.getOrDefault(id, id);
            ml.blockCounts.merge(mapped, 1, Integer::sum);
        }
        for (BlueprintData.BlockEntry entry : data.lateBlocks) {
            String id     = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock()).toString();
            String mapped = subs.getOrDefault(id, id);
            ml.blockCounts.merge(mapped, 1, Integer::sum);
        }
        return ml;
    }

    /** 返回所有方块的总数量。 */
    public int totalCount() {
        return blockCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ── 序列化 ────────────────────────────────────────────────────────────

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", blueprintName);
        ListTag entries = new ListTag();
        for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putString("block", entry.getKey());
            e.putInt("count", entry.getValue());
            entries.add(e);
        }
        tag.put("entries", entries);
        return tag;
    }

    public static MaterialList deserialize(CompoundTag tag) {
        MaterialList ml = new MaterialList();
        ml.blueprintName = tag.getString("name");
        if (tag.contains("entries")) {
            ListTag entries = tag.getList("entries", 10); // 10 = CompoundTag
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag e = entries.getCompound(i);
                ml.blockCounts.put(e.getString("block"), e.getInt("count"));
            }
        }
        return ml;
    }
}
