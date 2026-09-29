package com.xioyim.spatialblueprint;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立的材料相关配置（{@code spatial_blueprint/block_substitutions.toml}），与主配置分离。
 *
 * <ul>
 *   <li>{@code blockSubstitutions}：方块替换表（原主配置项迁移至此）</li>
 *   <li>{@code generatedSubstitutions}：启动扫描注册表生成的候选（仅参考）</li>
 *   <li>{@code bulkMaterialConversions}：批量材料转换（参与材料清单逻辑）</li>
 * </ul>
 */
public class GeneratedSubstitutionsConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    /** 方块替换表：source block → target block/item（迁移自主配置）。 */
    public static final ForgeConfigSpec.ConfigValue<List<String>> BLOCK_SUBSTITUTIONS;

    /** 生成的候选映射（每行 {@code sourceBlockId=targetItemId}）。 */
    public static final ForgeConfigSpec.ConfigValue<List<String>> GENERATED;

    /** 批量材料转换（每行 {@code sourceBlockId=threshold|item1*count1+item2*count2}）。 */
    public static final ForgeConfigSpec.ConfigValue<List<String>> BULK_CONVERSIONS;

    static {
        BLOCK_SUBSTITUTIONS = BUILDER
                .comment("Block substitution map for the material list.\n"
                        + "When a blueprint captures the left-side block, it is recorded in the material\n"
                        + "list as the right-side block/item instead. This affects both the material count\n"
                        + "and the displayed icon, so survival players know which obtainable item to gather.\n"
                        + "\n"
                        + "Format: one entry per line as \"sourceBlockId=targetBlockOrItemId\".\n"
                        + "A target may list multiple items with '+', each counted once\n"
                        + "(\"a+b\" or \"a*2+b\"), e.g. a water cauldron needs a cauldron AND a water bucket.\n"
                        + "An empty target (\"source=\") marks the block as FREE (converts to air, not counted).\n"
                        + "Example: minecraft:weeping_vines_plant=minecraft:weeping_vines\n"
                        + "         minecraft:water=minecraft:water_bucket\n"
                        + "         minecraft:water_cauldron=minecraft:cauldron+minecraft:water_bucket\n"
                        + "\n"
                        + "The defaults cover all vanilla blocks that have no direct item form or whose\n"
                        + "block registry name differs from the obtainable item (wall signs, crop stages,\n"
                        + "plant variants, liquid blocks, potted plants, etc.).")
                .define("blockSubstitutions", (List<String>) new ArrayList<>(BlueprintConfig.DEFAULT_BLOCK_SUBSTITUTIONS));

        GENERATED = BUILDER
                .comment("Automatically generated block substitution candidates.\n"
                        + "Scanned from the block registry at startup. For reference only - "
                        + "copy entries you want into 'blockSubstitutions' above.")
                .define("generatedSubstitutions", (List<String>) new ArrayList<String>());

        BULK_CONVERSIONS = BUILDER
                .comment("Bulk material conversions: when a captured source block count reaches the\n"
                        + "threshold, it is converted to a FIXED set of target items instead of 1:1.\n"
                        + "Format per line: sourceBlockId=threshold|item1*count1+item2*count2\n"
                        + "Example: minecraft:water=10|minecraft:water_bucket*2  -> capturing >=10 water\n"
                        + "yields 2 water buckets (any larger count still yields 2, not proportional).\n"
                        + "Below the threshold the normal 'blockSubstitutions' 1:1 applies.\n"
                        + "A threshold of 0 (\"source=0|\") marks the block as FREE (not counted).")
                .define("bulkMaterialConversions", (List<String>) new ArrayList<>(List.of(
                        "minecraft:water=3|minecraft:water_bucket*2",
                        "minecraft:lava=3|minecraft:lava_bucket*2")));

        SPEC = BUILDER.build();
    }

    private static Map<String, String> cachedSubstitutions = null;

    /**
     * Returns the block-substitution map: source block registry ID → target block/item registry ID.
     * Applied at material-list creation time so the recorded ID is the obtainable target.
     */
    public static void invalidateCache() {
        cachedSubstitutions = null;
    }

    public static Map<String, String> getBlockSubstitutions() {
        if (cachedSubstitutions == null) {
            cachedSubstitutions = new LinkedHashMap<>();
            for (String entry : BLOCK_SUBSTITUTIONS.get()) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    cachedSubstitutions.put(
                            entry.substring(0, eq).trim(),
                            entry.substring(eq + 1).trim());
                }
            }
        }
        return cachedSubstitutions;
    }
}
