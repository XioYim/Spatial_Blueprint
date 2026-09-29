package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量材料转换：捕获的源方块达到阈值后，整组固定产出目标物品（而非 1:1）。
 *
 * <p>配置格式（{@code block_substitutions.toml} 的 {@code bulkMaterialConversions}）：<br>
 * {@code sourceBlockId=threshold|item1*count1+item2*count2}<br>
 * 例：{@code minecraft:water=10|minecraft:water_bucket*2} → 捕获 ≥10 格水即产出 2 桶
 * （再多的水也固定 2 桶，不按比例）；不足阈值时回落普通 {@code blockSubstitutions} 的 1:1。</p>
 */
public final class BulkMaterialConverter {

    /** 一条批量转换规则。 */
    public record BulkEntry(int threshold, LinkedHashMap<String, Integer> targets) {}

    private BulkMaterialConverter() {}

    /** 解析配置为 source → 规则。无效条目跳过。 */
    public static Map<String, BulkEntry> parse() {
        Map<String, BulkEntry> result = new LinkedHashMap<>();
        for (String entry : GeneratedSubstitutionsConfig.BULK_CONVERSIONS.get()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String source = entry.substring(0, eq).trim();
            if (source.isEmpty()) continue;
            String rest = entry.substring(eq + 1).trim();
            int pipe = rest.indexOf('|');
            if (pipe <= 0) continue;
            int threshold;
            try {
                threshold = Integer.parseInt(rest.substring(0, pipe).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (threshold < 0) continue; // 负数非法
            // threshold=0 表示免费（不计材料），直接写入 map，无需解析目标
            if (threshold == 0) {
                result.put(source, new BulkEntry(0, new LinkedHashMap<>()));
                continue;
            }
            LinkedHashMap<String, Integer> targets = new LinkedHashMap<>();
            for (String part : rest.substring(pipe + 1).split("\\+")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int star = part.indexOf('*');
                String item;
                int count = 1;
                if (star > 0) {
                    item = part.substring(0, star).trim();
                    try { count = Integer.parseInt(part.substring(star + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                } else {
                    item = part;
                }
                if (!item.isEmpty()) targets.put(item, Math.max(1, count));
            }
            if (!targets.isEmpty()) result.put(source, new BulkEntry(threshold, targets));
        }
        return result;
    }

    /**
     * 从方块计数中挑出实际会生效的批量转换与免费方块。
     *
     * <p>免费有两个来源：批量转换表里 {@code threshold == 0} 的项，以及方块替换表里目标为空
     * （{@code source=}）的项 —— 两处语义相同，都是「转成空气、不计入材料」。批量表优先，
     * 因为放置逻辑先查它。</p>
     */
    public static void collectConversions(Map<String, Integer> blockCounts,
                                          List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions,
                                          List<String> freeBlocks) {
        Map<String, BulkEntry> bulk = parse();
        Set<String> freeSeen = new LinkedHashSet<>();

        for (var e : bulk.entrySet()) {
            int captured = blockCounts.getOrDefault(e.getKey(), 0);
            if (captured <= 0) continue;
            var rule = e.getValue();
            if (rule.threshold() == 0) {
                freeSeen.add(e.getKey());
            } else if (captured >= rule.threshold()) {
                List<S2C_IllegalBlocksListMessage.TargetItem> targets = new ArrayList<>();
                for (var t : rule.targets().entrySet()) {
                    targets.add(new S2C_IllegalBlocksListMessage.TargetItem(t.getKey(), t.getValue()));
                }
                conversions.add(new S2C_IllegalBlocksListMessage.ConversionEntry(
                        e.getKey(), captured, rule.threshold(), targets));
            }
        }

        for (var e : GeneratedSubstitutionsConfig.getBlockSubstitutions().entrySet()) {
            if (e.getValue().isEmpty() && blockCounts.getOrDefault(e.getKey(), 0) > 0) {
                freeSeen.add(e.getKey());
            }
        }

        freeBlocks.addAll(freeSeen);
    }
}
