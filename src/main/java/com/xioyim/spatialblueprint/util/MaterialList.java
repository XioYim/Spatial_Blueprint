package com.xioyim.spatialblueprint.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
     * {@link MaterialKey} 复合键 → 数量。
     *
     * <p>无 NBT 的方块其键就是纯注册名（{@code "minecraft:stone"}）；带 NBT 的物品键会附带
     * 摘要后缀（{@code "minecraft:diamond_sword#a3f2c1d4e5b6"}），因此不同附魔、不同耐久的
     * 同种物品各自独立成条，GUI 也会自然分行显示。</p>
     *
     * <p>使用 {@link LinkedHashMap} 保持插入顺序，序列化/反序列化后顺序一致。</p>
     */
    public final LinkedHashMap<String, Integer> blockCounts = new LinkedHashMap<>();

    /**
     * 复合键 → 规范化后的 item {@code tag}（附魔、耐久、自定义名等）。
     * 仅带 NBT 的键有条目；用于 GUI 重建完整 ItemStack（附魔光效 + 完整 tooltip）。
     */
    public final LinkedHashMap<String, CompoundTag> keyTags = new LinkedHashMap<>();

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
        Map<String, String> subs = com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig.getBlockSubstitutions();
        // 先统计各源方块原始数量（normal/dependent/late 三列表合并）
        java.util.LinkedHashMap<String, Integer> rawCounts = new java.util.LinkedHashMap<>();
        for (BlueprintData.BlockEntry entry : data.normalBlocks) {
            rawCounts.merge(BuiltInRegistries.BLOCK.getKey(entry.state.getBlock()).toString(), 1, Integer::sum);
        }
        for (BlueprintData.BlockEntry entry : data.dependentBlocks) {
            rawCounts.merge(BuiltInRegistries.BLOCK.getKey(entry.state.getBlock()).toString(), 1, Integer::sum);
        }
        for (BlueprintData.BlockEntry entry : data.lateBlocks) {
            rawCounts.merge(BuiltInRegistries.BLOCK.getKey(entry.state.getBlock()).toString(), 1, Integer::sum);
        }
        // 批量转换：达到阈值 → 固定产出目标；否则回落 blockSubstitutions 的 1:1；免费（阈值 0/空目标）→ 跳过不计
        Map<String, BulkMaterialConverter.BulkEntry> bulk = BulkMaterialConverter.parse();
        for (java.util.Map.Entry<String, Integer> e : rawCounts.entrySet()) {
            String source = e.getKey();
            int count = e.getValue();
            BulkMaterialConverter.BulkEntry be = bulk.get(source);
            if (be != null) {
                if (be.threshold() == 0) continue; // 免费（转化为空气）→ 该源不计材料
                if (count >= be.threshold()) {
                    for (java.util.Map.Entry<String, Integer> t : be.targets().entrySet()) {
                        ml.blockCounts.merge(t.getKey(), t.getValue(), Integer::sum);
                    }
                    continue;
                }
            }
            String mapped = subs.get(source);
            if (mapped == null) mapped = source;
            // 空目标 = 免费方块：无论是否存在批量规则，均不计入材料清单
            if (mapped.isEmpty()) continue;
            mergeSubstituted(ml, mapped, count);
        }
        // 实体本身 + 装备/物品计入材料清单
        for (BlueprintData.EntityEntry entry : data.entities) {
            // 实体本身（玩家需准备画/展示框/盔甲架/栓绳来放置；生物蛋不计入）
            switch (entry.type) {
                case "minecraft:painting",
                     "minecraft:item_frame",
                     "minecraft:glow_item_frame",
                     "minecraft:armor_stand" -> ml.blockCounts.merge(entry.type, 1, Integer::sum);
                case "minecraft:leash_knot"  -> ml.blockCounts.merge("minecraft:lead", 1, Integer::sum);
                default -> {}
            }
            // 盔甲架装备（ArmorItems/HandItems）、展示框物品（Item）
            if ("minecraft:armor_stand".equals(entry.type)) {
                CompoundTag nbt = entry.nbt;
                if (nbt.contains("ArmorItems", 9)) {
                    for (net.minecraft.nbt.Tag t : nbt.getList("ArmorItems", 10)) {
                        if (t instanceof CompoundTag item) mergeItemCount(ml, item);
                    }
                }
                if (nbt.contains("HandItems", 9)) {
                    for (net.minecraft.nbt.Tag t : nbt.getList("HandItems", 10)) {
                        if (t instanceof CompoundTag item) mergeItemCount(ml, item);
                    }
                }
            } else if ("minecraft:item_frame".equals(entry.type)
                    || "minecraft:glow_item_frame".equals(entry.type)) {
                if (entry.nbt.contains("Item", 10)) {
                    mergeItemCount(ml, entry.nbt.getCompound("Item"));
                }
            }
        }
        return ml;
    }

    private static void mergeItemCount(MaterialList ml, CompoundTag item) {
        String id = item.getString("id");
        if (id.isBlank()) return;
        int count = item.contains("count") ? item.getInt("count")
                : (item.contains("Count") ? item.getByte("Count") : 1);
        // 复合键：同 ID 不同组件的物品各自独立成条（1.21 components；旧格式 tag 兜底）
        CompoundTag rawTag = item.contains("components", 10) ? item.getCompound("components")
                : (item.contains("tag", 10) ? item.getCompound("tag") : null);
        CompoundTag norm   = MaterialKey.normalize(rawTag);
        String key         = MaterialKey.make(id, rawTag);
        ml.blockCounts.merge(key, Math.max(1, count), Integer::sum);
        if (norm != null) ml.keyTags.putIfAbsent(key, norm);
    }

    /** 该复合键对应的规范化 tag；无 NBT 的键返回 {@code null}。 */
    @javax.annotation.Nullable
    public CompoundTag tagFor(String materialKey) {
        return keyTags.get(materialKey);
    }

    /**
     * 构建用于 GUI 显示的 ItemStack（含 {@link #keyTags} 中的 tag，若有）。
     * 附魔物品会显示附魔光效和完整 tooltip；普通物品返回普通 ItemStack；物品不存在时返回 EMPTY。
     */
    public ItemStack getDisplayStack(String materialKey, net.minecraft.core.HolderLookup.Provider provider) {
        return buildDisplayStack(materialKey, keyTags.get(materialKey), provider);
    }

    /**
     * 由复合键 + tag 构建显示用 ItemStack。供 {@link MaterialSpace} 复用 —— 材料空间保存的是
     * 复合键与 tag，不持有 MaterialList 实例。
     *
     * <p>tag 是组件补丁 NBT（与 {@code ItemStack.save} 的 components 子标签同编码），
     * 按 {@link net.minecraft.core.component.DataComponentPatch#CODEC} 解析后应用。</p>
     */
    public static ItemStack buildDisplayStack(String materialKey, @javax.annotation.Nullable CompoundTag tag,
                                              net.minecraft.core.HolderLookup.Provider provider) {
        ResourceLocation rl = ResourceLocation.tryParse(MaterialKey.idOf(materialKey));
        if (rl == null) return ItemStack.EMPTY;
        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(rl);
        Item item;
        if (itemOpt.isPresent() && itemOpt.get() != Items.AIR) {
            item = itemOpt.get();
        } else {
            Optional<net.minecraft.world.level.block.Block> blockOpt =
                    BuiltInRegistries.BLOCK.getOptional(rl);
            if (blockOpt.isEmpty()) return ItemStack.EMPTY;
            item = blockOpt.get().asItem();
            if (item == Items.AIR) return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (tag != null) {
            var parsed = net.minecraft.core.component.DataComponentPatch.CODEC.parse(
                    provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag);
            if (parsed.result().isPresent()) {
                stack.applyComponents(parsed.result().get());
            } else {
                // 旧键已在载入时迁移（SBN-011），这里只兜底：解析失败就返回无组件的普通栈
            }
        }
        return stack;
    }

    /**
     * 将替换目标写入材料清单。替换目标可含多个物品：用 '+' 分隔，每项计 1；
     * 每项也可用 '*' 指定份数（如 {@code cauldron+water_bucket} 表示 1 个炼药锅 + 1 个水桶）。
     * 例如配置 {@code minecraft:water_cauldron=minecraft:cauldron+minecraft:water_bucket}，
     * 则每 1 格水炼药锅计为 1 炼药锅 + 1 水桶。
     */
    private static void mergeSubstituted(MaterialList ml, String mapped, int count) {
        for (String part : mapped.split("\\+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int star = part.indexOf('*');
            if (star > 0) {
                String id = part.substring(0, star).trim();
                int per = 1;
                try { per = Integer.parseInt(part.substring(star + 1).trim()); } catch (NumberFormatException ignored) {}
                ml.blockCounts.merge(id, Math.max(1, per) * count, Integer::sum);
            } else {
                ml.blockCounts.merge(part, count, Integer::sum);
            }
        }
    }

    /** 返回所有方块的总数量。 */
    public int totalCount() {
        return blockCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 返回一份独立副本，供客户端界面暂存未保存的编辑。
     *
     * <p>{@code PlacementScreen} 的 {@code data} 来自 {@code ClientBlueprintCache} 的共享实例，
     * 直接改它的清单会污染缓存、进而影响其他界面。编辑前先拷一份即可脱离缓存。</p>
     */
    public MaterialList copy() {
        MaterialList ml = new MaterialList();
        ml.blockCounts.putAll(this.blockCounts);
        for (Map.Entry<String, CompoundTag> e : this.keyTags.entrySet()) {
            ml.keyTags.put(e.getKey(), e.getValue().copy());
        }
        return ml;
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
        if (!keyTags.isEmpty()) {
            ListTag samples = new ListTag();
            for (Map.Entry<String, CompoundTag> e : keyTags.entrySet()) {
                CompoundTag s = new CompoundTag();
                s.putString("id", e.getKey());
                s.put("tag", e.getValue().copy());
                samples.add(s);
            }
            tag.put("keyTags", samples);
        }
        return tag;
    }

    public static MaterialList deserialize(CompoundTag tag) {
        return deserialize(tag, LegacyDataFixer.current(), null);
    }

    /**
     * 带数据版本的反序列化（D2，SBN-011）：旧版本时把按旧物品 tag 算摘要的键
     * 迁移为按新 components 算的键（同一新键合并数量，keyTags 换成新 components）；
     * 无 tag 的键原样。from 为当前版本时与 {@link #deserialize(CompoundTag)} 逐字相同。
     *
     * @param unupgraded 为 null 不计数，否则累计未能升级条目数（由调用方记 WARN）
     */
    public static MaterialList deserialize(CompoundTag tag, int from, @javax.annotation.Nullable int[] unupgraded) {
        MaterialList ml = new MaterialList();
        ml.blueprintName = tag.getString("name");
        if (!LegacyDataFixer.needsFix(from)) {
            if (tag.contains("entries")) {
                ListTag entries = tag.getList("entries", 10); // 10 = CompoundTag
                for (int i = 0; i < entries.size(); i++) {
                    CompoundTag e = entries.getCompound(i);
                    ml.blockCounts.put(e.getString("block"), e.getInt("count"));
                }
            }
            if (tag.contains("keyTags", 9)) {
                ListTag samples = tag.getList("keyTags", 10);
                for (int i = 0; i < samples.size(); i++) {
                    CompoundTag s = samples.getCompound(i);
                    String key = s.getString("id");
                    if (!key.isBlank() && s.contains("tag", 10)) {
                        ml.keyTags.put(key, s.getCompound("tag").copy());
                    }
                }
            }
            return ml;
        }
        java.util.LinkedHashMap<String, CompoundTag> tags = new java.util.LinkedHashMap<>();
        if (tag.contains("keyTags", 9)) {
            ListTag samples = tag.getList("keyTags", 10);
            for (int i = 0; i < samples.size(); i++) {
                CompoundTag s = samples.getCompound(i);
                String key = s.getString("id");
                if (!key.isBlank() && s.contains("tag", 10)) {
                    tags.put(key, s.getCompound("tag").copy());
                }
            }
        }
        if (tag.contains("entries")) {
            ListTag entries = tag.getList("entries", 10); // 10 = CompoundTag
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag e = entries.getCompound(i);
                String oldKey = e.getString("block");
                LegacyDataFixer.MigratedKey m =
                        LegacyDataFixer.migrateMaterialKey(oldKey, tags.get(oldKey), from);
                if (!m.ok()) {
                    // 迁移失败：保留原键与原 tag（与迁移前行为一致），计数
                    if (unupgraded != null) unupgraded[0]++;
                    ml.blockCounts.merge(oldKey, e.getInt("count"), Integer::sum);
                    CompoundTag oldTag = tags.get(oldKey);
                    if (oldTag != null) ml.keyTags.putIfAbsent(oldKey, oldTag);
                } else {
                    // 同一新键合并数量；新 components 进 keyTags
                    ml.blockCounts.merge(m.key(), e.getInt("count"), Integer::sum);
                    if (m.components() != null) ml.keyTags.putIfAbsent(m.key(), m.components());
                }
            }
        }
        return ml;
    }

    public static MaterialList deserialize(CompoundTag tag, int from) {
        return deserialize(tag, from, null);
    }
}
