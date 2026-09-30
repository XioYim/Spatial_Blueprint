package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家材料空间：世界级持久化存储，按玩家 UUID 分表。存于 saveddata 的 {@code sb_material_space}。
 *
 * <p>分为两个互相隔离的分区：</p>
 * <ul>
 *   <li><b>普通池</b>（{@link #pools}）—— 玩家手动存入，供投影 / 工程蓝图放置时消耗。
 *       受 {@link BlueprintConfig#materialSpaceMaxCount()} 总件数上限约束。</li>
 *   <li><b>次元袋</b>（{@link #bags}）—— 次元蓝图捕获时自动写入，按蓝图 ID 分袋，
 *       只能被同 ID 的次元蓝图放置时消耗，玩家无法取出，不设上限。</li>
 * </ul>
 *
 * <p>两个分区的材料不可互相调用：普通池的料不能给次元蓝图用，次元袋的料也不能给
 * 投影 / 工程蓝图用。</p>
 *
 * <p>键为 {@link MaterialKey} 复合键；带 NBT 的键其规范化 tag 存放在 {@link #tagRegistry}，
 * 供 GUI 重建完整 ItemStack（附魔光效、耐久、完整 tooltip）。</p>
 *
 * <p>线程安全：所有调用均应在服务端主线程。</p>
 */
public class MaterialSpace extends SavedData {

    private static final String SAVE_KEY = "sb_material_space";

    /** 玩家 UUID → (复合键 → 数量)。 */
    // 写成 ListTag 的三张表用 LinkedHashMap：HashMap 扩容不缩容，读回副本遍历顺序不同，存盘不确定（SBN-013 验收，t09）
    private final Map<UUID, Map<String, Integer>> pools = new LinkedHashMap<>();
    /** 玩家 UUID → (蓝图 ID → (复合键 → 数量))。 */
    private final Map<UUID, Map<String, Map<String, Integer>>> bags = new LinkedHashMap<>();
    /**
     * 蓝图 ID → 名字快照。蓝图 ID 全局唯一，因此不按玩家分表。
     *
     * <p>蓝图文件被删除/归档后名字就查不到了，只有这份快照能让材料空间界面显示出
     * 「这袋材料原本属于哪个建筑」，而不是一串 UUID。</p>
     */
    private final Map<String, String> bagNames = new HashMap<>();
    /** 玩家 UUID → 已放置并消耗材料的次元蓝图 ID。用于材料空间继续显示已放置条目。 */
    private final Map<UUID, Set<String>> consumedBagIds = new LinkedHashMap<>();
    /** 复合键 → 规范化 tag。仅带 NBT 的键有条目。 */
    private final Map<String, CompoundTag> tagRegistry = new HashMap<>();

    public static MaterialSpace get(ServerLevel level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(MaterialSpace::new, MaterialSpace::load, null), SAVE_KEY);
    }

    private MaterialSpace() {}

    // ── 普通池 ────────────────────────────────────────────────────────────

    /** 该玩家普通池中某材料的数量。 */
    public int count(UUID player, String materialKey) {
        return pools.getOrDefault(player, Map.of()).getOrDefault(materialKey, 0);
    }

    /** 普通池的只读视图（保持插入顺序）。 */
    public Map<String, Integer> pool(UUID player) {
        Map<String, Integer> p = pools.get(player);
        return p == null ? Map.of() : Collections.unmodifiableMap(p);
    }

    /** 普通池当前总件数。 */
    public int poolTotal(UUID player) {
        int sum = 0;
        for (int v : pools.getOrDefault(player, Map.of()).values()) sum += v;
        return sum;
    }

    /** 普通池剩余可存件数。 */
    public int poolRemainingCapacity(UUID player) {
        return Math.max(0, BlueprintConfig.materialSpaceMaxCount() - poolTotal(player));
    }

    /**
     * 向普通池存入材料，受总件数上限约束。
     *
     * @param tag 该材料的规范化 tag（无 NBT 时传 {@code null}），用于登记到 {@link #tagRegistry}
     * @return 实际存入的数量（可能因触顶而少于请求量，甚至为 0）
     */
    public int deposit(UUID player, String materialKey, int amount, @Nullable CompoundTag tag) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, poolRemainingCapacity(player));
        if (accepted <= 0) return 0;
        pools.computeIfAbsent(player, k -> new LinkedHashMap<>())
             .merge(materialKey, accepted, Integer::sum);
        registerTag(materialKey, tag);
        setDirty();
        return accepted;
    }

    /** 清空该玩家的普通池（不返还物品）。次元袋不受影响。 */
    public void clearPool(UUID player) {
        if (pools.remove(player) != null) setDirty();
    }

    /** 普通池是否满足整份材料清单。 */
    public boolean canAfford(UUID player, @Nullable MaterialList ml) {
        if (ml == null || ml.blockCounts.isEmpty()) return true;
        Map<String, Integer> p = pools.getOrDefault(player, Map.of());
        for (Map.Entry<String, Integer> e : ml.blockCounts.entrySet()) {
            if (p.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /**
     * 原子扣除整份材料清单：先校验全部条目，任一不足则整体放弃、不做任何改动。
     *
     * @return 是否成功扣除
     */
    public boolean consume(UUID player, @Nullable MaterialList ml) {
        if (ml == null || ml.blockCounts.isEmpty()) return true;
        if (!canAfford(player, ml)) return false;
        Map<String, Integer> p = pools.get(player);
        for (Map.Entry<String, Integer> e : ml.blockCounts.entrySet()) {
            int left = p.get(e.getKey()) - e.getValue();
            if (left > 0) p.put(e.getKey(), left);
            else p.remove(e.getKey());
        }
        setDirty();
        return true;
    }

    // ── 次元袋 ────────────────────────────────────────────────────────────

    /** 该玩家当前持有的所有次元袋对应的蓝图 ID（保持插入顺序）。 */
    public java.util.Set<String> bagIds(UUID player) {
        Map<String, Map<String, Integer>> byBp = bags.get(player);
        return byBp == null ? java.util.Set.of()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(byBp.keySet()));
    }

    /** 该玩家的全部次元蓝图材料记录，包括当前袋与已放置后消耗的袋。 */
    public Set<String> dimensionalBlueprintIds(UUID player) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Map<String, Map<String, Integer>> byBp = bags.get(player);
        if (byBp != null) ids.addAll(byBp.keySet());
        ids.addAll(consumedBagIds.getOrDefault(player, Set.of()));
        return Collections.unmodifiableSet(ids);
    }

    /** 指定蓝图袋的总件数；无袋时返回 0。 */
    public int bagTotal(UUID player, String blueprintId) {
        int sum = 0;
        for (int v : bag(player, blueprintId).values()) sum += v;
        return sum;
    }

    /** 指定蓝图袋的只读视图；无袋时返回空表。 */
    public Map<String, Integer> bag(UUID player, String blueprintId) {
        Map<String, Map<String, Integer>> byBp = bags.get(player);
        if (byBp == null) return Map.of();
        Map<String, Integer> b = byBp.get(blueprintId);
        return b == null ? Map.of() : Collections.unmodifiableMap(b);
    }

    /** 向指定蓝图袋写入材料。次元分区不设上限。 */
    public void bagDeposit(UUID player, String blueprintId, String materialKey,
                           int amount, @Nullable CompoundTag tag) {
        if (amount <= 0) return;
        clearConsumedBagId(player, blueprintId);
        bags.computeIfAbsent(player, k -> new LinkedHashMap<>())
            .computeIfAbsent(blueprintId, k -> new LinkedHashMap<>())
            .merge(materialKey, amount, Integer::sum);
        registerTag(materialKey, tag);
        setDirty();
    }

    /**
     * 把整份材料清单<b>覆盖</b>写入指定蓝图袋。
     *
     * <p>先清掉旧袋再写 —— 「重新储存」是「把这片结构重新收进同一个蓝图」，袋子内容应当等于
     * 本次扫描的结果，而不是与上一次累加。生存模式下放置已经消耗掉袋子、旧袋本就为空；
     * 但创造模式放置不消耗袋子，若在这里累加，反复「放置 → 重新储存」会让袋子成倍膨胀。</p>
     */
    public void bagDepositAll(UUID player, String blueprintId, MaterialList ml) {
        bagDepositAll(player, blueprintId, ml, null);
    }

    /**
     * 同上，并记录蓝图名字快照。
     *
     * <p>{@code blueprintName} 是给「蓝图已丢失」那种情况准备的 —— 文件一旦被删除/归档，
     * 名字就再也查不到了，材料空间界面只能显示一串 UUID。存一份快照才能让玩家认出
     * 这袋材料原本属于哪个建筑。</p>
     */
    public void bagDepositAll(UUID player, String blueprintId, MaterialList ml,
                              @Nullable String blueprintName) {
        if (ml == null || ml.blockCounts.isEmpty()) return;
        removeBag(player, blueprintId);
        clearConsumedBagId(player, blueprintId);
        for (Map.Entry<String, Integer> e : ml.blockCounts.entrySet()) {
            bagDeposit(player, blueprintId, e.getKey(), e.getValue(), ml.tagFor(e.getKey()));
        }
        if (blueprintName != null && !blueprintName.isEmpty()) {
            bagNames.put(blueprintId, blueprintName);
            setDirty();
        }
    }

    /** 该蓝图袋记录的名字快照；没有记录时返回空串。 */
    public String bagName(String blueprintId) {
        return bagNames.getOrDefault(blueprintId, "");
    }

    /**
     * 玩家主动丢弃一个「蓝图已丢失」的孤儿袋 —— 材料<b>永久消失</b>，不返还。
     *
     * <p>对应蓝图文件已被删除或归档，这袋材料再也没有任何东西能消耗它，
     * 留着只会永久占用材料空间。删不删由玩家自己决定（万一是误删，管理员还能恢复文件）。</p>
     *
     * @return {@code true} 表示确实删掉了一个袋子
     */
    public boolean discardBag(UUID player, String blueprintId) {
        boolean removed = removeBag(player, blueprintId);
        if (removed) {
            clearConsumedBagId(player, blueprintId);
            bagNames.remove(blueprintId);
            setDirty();
        }
        return removed;
    }

    /**
     * 消耗整个蓝图袋（次元蓝图放置）。袋子本身就是需求清单 —— 有多少扣多少、扣完即删，
     * 因此不受配置变更导致的材料清单重算影响。
     *
     * @return 袋子存在并被消耗时返回 {@code true}；无袋时返回 {@code false}
     */
    public boolean bagConsume(UUID player, String blueprintId) {
        if (!removeBag(player, blueprintId)) return false;
        consumedBagIds.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(blueprintId);
        setDirty();
        return true;
    }

    private boolean removeBag(UUID player, String blueprintId) {
        Map<String, Map<String, Integer>> byBp = bags.get(player);
        if (byBp == null || byBp.remove(blueprintId) == null) return false;
        if (byBp.isEmpty()) bags.remove(player);
        return true;
    }

    private boolean clearConsumedBagId(UUID player, String blueprintId) {
        Set<String> ids = consumedBagIds.get(player);
        if (ids == null || !ids.remove(blueprintId)) return false;
        if (ids.isEmpty()) consumedBagIds.remove(player);
        return true;
    }

    /**
     * 跨玩家清除指定蓝图 ID 的次元袋。
     *
     * <p>删除次元蓝图文件时调用 —— 袋子按「玩家 UUID + 蓝图 ID」存放，而删文件的一方
     * 并不知道袋子属于谁，因此这里遍历所有玩家。不清的话袋子会成为永久占用材料空间的孤儿。</p>
     *
     * @return 是否真的清掉了某个袋子
     */
    public boolean removeBagEverywhere(String blueprintId) {
        boolean removed = false;
        var it = bags.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().remove(blueprintId) != null) removed = true;
            if (entry.getValue().isEmpty()) it.remove();
        }
        var consumedIt = consumedBagIds.entrySet().iterator();
        while (consumedIt.hasNext()) {
            var entry = consumedIt.next();
            if (entry.getValue().remove(blueprintId)) removed = true;
            if (entry.getValue().isEmpty()) consumedIt.remove();
        }
        if (removed) setDirty();
        return removed;
    }

    // ── 显示辅助 ──────────────────────────────────────────────────────────

    /** 复合键对应的规范化 tag；无 NBT 的键返回 {@code null}。 */
    @Nullable
    public CompoundTag tagFor(String materialKey) {
        return tagRegistry.get(materialKey);
    }

    /** 构建用于 GUI 显示的 ItemStack（含 NBT，附魔光效与完整 tooltip 可用）。 */
    public ItemStack displayStack(String materialKey, HolderLookup.Provider provider) {
        return MaterialList.buildDisplayStack(materialKey, tagFor(materialKey), provider);
    }

    private void registerTag(String materialKey, @Nullable CompoundTag tag) {
        if (tag != null && MaterialKey.hasTag(materialKey) && !tagRegistry.containsKey(materialKey)) {
            tagRegistry.put(materialKey, tag.copy());
        }
    }

    // ── NBT 持久化 ────────────────────────────────────────────────────────

    public static MaterialSpace load(CompoundTag tag, HolderLookup.Provider provider) {
        MaterialSpace data = new MaterialSpace();
        // D2（SBN-011）：读不到版本字段视为 1.20.1 存档，按旧材料键迁移
        int from = LegacyDataFixer.effectiveFrom(
                tag.contains("DataVersion", Tag.TAG_INT) ? tag.getInt("DataVersion") : 0);
        boolean fix = LegacyDataFixer.needsFix(from);
        int unupgraded = 0;

        ListTag poolList = tag.getList("pools", Tag.TAG_COMPOUND);
        for (int i = 0; i < poolList.size(); i++) {
            CompoundTag e = poolList.getCompound(i);
            UUID id = parseUuid(e.getString("player"));
            if (id == null) continue;
            data.pools.put(id, readCounts(e.getCompound("items")));
        }

        ListTag bagList = tag.getList("bags", Tag.TAG_COMPOUND);
        for (int i = 0; i < bagList.size(); i++) {
            CompoundTag e = bagList.getCompound(i);
            UUID id = parseUuid(e.getString("player"));
            if (id == null) continue;
            Map<String, Map<String, Integer>> byBp = new LinkedHashMap<>();
            ListTag perBp = e.getList("blueprints", Tag.TAG_COMPOUND);
            for (int j = 0; j < perBp.size(); j++) {
                CompoundTag b = perBp.getCompound(j);
                String bpId = b.getString("id");
                if (bpId.isEmpty()) continue;
                byBp.put(bpId, readCounts(b.getCompound("items")));
            }
            if (!byBp.isEmpty()) data.bags.put(id, byBp);
        }

        ListTag consumedList = tag.getList("consumedBags", Tag.TAG_COMPOUND);
        for (int i = 0; i < consumedList.size(); i++) {
            CompoundTag e = consumedList.getCompound(i);
            UUID id = parseUuid(e.getString("player"));
            if (id == null) continue;
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            ListTag perBp = e.getList("blueprints", Tag.TAG_COMPOUND);
            for (int j = 0; j < perBp.size(); j++) {
                String bpId = perBp.getCompound(j).getString("id");
                if (!bpId.isEmpty()) ids.add(bpId);
            }
            if (!ids.isEmpty()) data.consumedBagIds.put(id, ids);
        }

        CompoundTag tags = tag.getCompound("tagRegistry");
        for (String key : tags.getAllKeys()) {
            data.tagRegistry.put(key, tags.getCompound(key).copy());
        }

        CompoundTag names = tag.getCompound("bagNames");
        for (String key : names.getAllKeys()) {
            data.bagNames.put(key, names.getString(key));
        }

        if (fix) {
            unupgraded = data.migrateLegacyKeys(from);
            data.setDirty();
            if (unupgraded > 0) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[MaterialSpace] {} material keys could not be upgraded from dataVersion {}",
                        unupgraded, from);
            }
        }
        return data;
    }

    /**
     * D2 旧键迁移：先由 tagRegistry（旧键 → 旧 tag）算出旧键 → 新键映射，
     * 再把 pool / 次元袋里的键按映射重写（同一新键合并数量），tagRegistry 换键并换成新 components。
     * registry 里没有对应 tag 的键（纯 ID 键、或缺 tag 的摘要键）只做物品改名（grass → short_grass）。
     *
     * @return 未能升级条目数
     */
    private int migrateLegacyKeys(int from) {
        int unupgraded = 0;
        Map<String, String> keyMap = new LinkedHashMap<>();
        Map<String, CompoundTag> newRegistry = new LinkedHashMap<>();
        java.util.Set<String> bareKeys = new java.util.LinkedHashSet<>();
        for (Map<String, Integer> pool : pools.values()) bareKeys.addAll(pool.keySet());
        for (Map<String, Map<String, Integer>> byBp : bags.values()) {
            for (Map<String, Integer> bag : byBp.values()) bareKeys.addAll(bag.keySet());
        }
        bareKeys.removeAll(tagRegistry.keySet());
        for (String key : bareKeys) {
            String renamed = LegacyDataFixer.migrateMaterialKey(key, null, from).key();
            if (!renamed.equals(key)) keyMap.put(key, renamed);
        }
        for (Map.Entry<String, CompoundTag> e : tagRegistry.entrySet()) {
            LegacyDataFixer.MigratedKey m =
                    LegacyDataFixer.migrateMaterialKey(e.getKey(), e.getValue(), from);
            if (!m.ok()) {
                unupgraded++;
                newRegistry.putIfAbsent(e.getKey(), e.getValue());
            } else {
                keyMap.put(e.getKey(), m.key());
                if (m.components() != null) newRegistry.putIfAbsent(m.key(), m.components());
            }
        }
        if (!keyMap.isEmpty()) {
            for (Map<String, Integer> pool : pools.values()) {
                remapKeys(pool, keyMap);
            }
            for (Map<String, Map<String, Integer>> byBp : bags.values()) {
                for (Map<String, Integer> bag : byBp.values()) {
                    remapKeys(bag, keyMap);
                }
            }
        }
        tagRegistry.clear();
        tagRegistry.putAll(newRegistry);
        return unupgraded;
    }

    /** 按映射重写计数表的键（命中映射的才动，同一新键合并数量）。 */
    private static void remapKeys(Map<String, Integer> map, Map<String, String> keyMap) {
        if (map.isEmpty()) return;
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            merged.merge(keyMap.getOrDefault(e.getKey(), e.getKey()), e.getValue(), Integer::sum);
        }
        map.clear();
        map.putAll(merged);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("DataVersion", LegacyDataFixer.current());
        ListTag poolList = new ListTag();
        for (Map.Entry<UUID, Map<String, Integer>> e : pools.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("player", e.getKey().toString());
            entry.put("items", writeCounts(e.getValue()));
            poolList.add(entry);
        }
        tag.put("pools", poolList);

        ListTag bagList = new ListTag();
        for (Map.Entry<UUID, Map<String, Map<String, Integer>>> e : bags.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("player", e.getKey().toString());
            ListTag perBp = new ListTag();
            for (Map.Entry<String, Map<String, Integer>> b : e.getValue().entrySet()) {
                if (b.getValue().isEmpty()) continue;
                CompoundTag bp = new CompoundTag();
                bp.putString("id", b.getKey());
                bp.put("items", writeCounts(b.getValue()));
                perBp.add(bp);
            }
            entry.put("blueprints", perBp);
            bagList.add(entry);
        }
        tag.put("bags", bagList);

        CompoundTag names = new CompoundTag();
        for (Map.Entry<String, String> e : bagNames.entrySet()) {
            names.putString(e.getKey(), e.getValue());
        }
        tag.put("bagNames", names);

        ListTag consumedList = new ListTag();
        for (Map.Entry<UUID, Set<String>> e : consumedBagIds.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString("player", e.getKey().toString());
            ListTag perBp = new ListTag();
            for (String id : e.getValue()) {
                CompoundTag bp = new CompoundTag();
                bp.putString("id", id);
                perBp.add(bp);
            }
            entry.put("blueprints", perBp);
            consumedList.add(entry);
        }
        tag.put("consumedBags", consumedList);

        CompoundTag tags = new CompoundTag();
        for (Map.Entry<String, CompoundTag> e : tagRegistry.entrySet()) {
            tags.put(e.getKey(), e.getValue().copy());
        }
        tag.put("tagRegistry", tags);
        return tag;
    }

    private static Map<String, Integer> readCounts(CompoundTag items) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String key : items.getAllKeys()) {
            int v = items.getInt(key);
            if (v > 0) map.put(key, v);
        }
        return map;
    }

    private static CompoundTag writeCounts(Map<String, Integer> map) {
        CompoundTag items = new CompoundTag();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getValue() > 0) items.putInt(e.getKey(), e.getValue());
        }
        return items;
    }

    @Nullable
    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
