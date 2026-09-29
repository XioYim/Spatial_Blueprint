package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.GameData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描注册表，生成"方块 → 材料物品"的替换候选列表，写入 block_substitutions.toml
 * 的 generatedSubstitutions 字段（仅供参考，不参与运行时逻辑）。
 *
 * <h3>算法</h3>
 * <b>Phase 1 — 名字不一致检测</b>：遍历 {@code blockToItemId} 反查表（由 Forge
 * {@code BLOCK_TO_ITEM} slave map 构建，覆盖 SignItem / BannerItem /
 * StandingAndWallBlockItem 等把多个方块变体注册到同一物品的情况）。
 * 凡是方块注册名 ≠ 对应物品注册名的，直接输出 {@code blockId=itemId}。
 * 典型覆盖：所有 wall_* 墙面变体、potted_* 盆栽、crops（carrots/carrot 等）。
 *
 * <b>Phase 2 — 无物品形式推导</b>：对完全不在 {@code blockToItemId} 中的方块
 * （流体、机械扩展状态、藤蔓生长态等），先查 SPECIAL 硬编码表，再对极少数 mod 方块
 * 尝试后缀启发规则（{@code _candle_cake}）。
 */
public final class BlockSubstitutionGenerator {

    /**
     * Phase 2 专用：完全没有物品形式的方块 → 目标物品。
     * Phase 1 已自动覆盖名字不一致的情况（wall_sign/torch/banner/potted_/crops），
     * 这里只保留确实没有 BlockItem 的特殊方块。
     */
    private static final Map<String, String> SPECIAL = new LinkedHashMap<>();
    static {
        // ── 流体 / 无法放置的物理效果 ──────────────────────────────────────
        SPECIAL.put("minecraft:water",        "minecraft:water_bucket");
        SPECIAL.put("minecraft:lava",         "minecraft:lava_bucket");
        SPECIAL.put("minecraft:bubble_column","minecraft:water_bucket");
        SPECIAL.put("minecraft:powder_snow",  "minecraft:powder_snow_bucket");
        SPECIAL.put("minecraft:fire",         "minecraft:flint_and_steel");
        SPECIAL.put("minecraft:soul_fire",    "minecraft:flint_and_steel");
        // ── 机械扩展状态（由游戏逻辑生成，无对应物品） ────────────────────
        SPECIAL.put("minecraft:piston_head",  "minecraft:piston");
        // ── 非 BlockItem 的红石 / 绊线（item.use() 放置，不走 BlockItem） ──
        SPECIAL.put("minecraft:redstone_wire","minecraft:redstone");
        SPECIAL.put("minecraft:tripwire",     "minecraft:string");
        // ── 自动附着的作物状态（由生长逻辑设置，无对应 BlockItem） ─────────
        SPECIAL.put("minecraft:attached_melon_stem",  "minecraft:melon_seeds");
        SPECIAL.put("minecraft:attached_pumpkin_stem","minecraft:pumpkin_seeds");
        // ── 藤蔓 / 海藻的"植物段"（底部/中部，无 BlockItem） ───────────────
        SPECIAL.put("minecraft:kelp_plant",           "minecraft:kelp");
        SPECIAL.put("minecraft:weeping_vines_plant",  "minecraft:weeping_vines");
        SPECIAL.put("minecraft:twisting_vines_plant", "minecraft:twisting_vines");
        SPECIAL.put("minecraft:cave_vines",           "minecraft:glow_berries");
        SPECIAL.put("minecraft:cave_vines_plant",     "minecraft:glow_berries");
        SPECIAL.put("minecraft:big_dripleaf_stem",    "minecraft:big_dripleaf");
        SPECIAL.put("minecraft:bamboo_sapling",       "minecraft:bamboo");
        // ── 蛋糕 + 蜡烛（右键触发，非 BlockItem） ────────────────────────
        SPECIAL.put("minecraft:candle_cake", "minecraft:candle");
    }

    private BlockSubstitutionGenerator() {}

    /** 扫描注册表并写入独立 toml。仅当尚无内容时生成一次；已有内容不扫描覆盖。 */
    public static void generateAndSave() {
        try {
            if (!GeneratedSubstitutionsConfig.GENERATED.get().isEmpty()) {
                SpatialBlueprintMod.LOGGER.info(
                        "[SpatialBlueprint] block_substitutions.toml already has content, skipping scan");
                return;
            }
            List<String> list = scanCandidates();
            GeneratedSubstitutionsConfig.GENERATED.set(list);
            GeneratedSubstitutionsConfig.GENERATED.save();
            SpatialBlueprintMod.LOGGER.info(
                    "[SpatialBlueprint] Generated {} block substitution candidates -> block_substitutions.toml",
                    list.size());
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Failed to generate substitutions: {}", e.toString());
        }
    }

    /**
     * 直接扫描当前注册表，返回全量方块替换候选（{@code source=target}）。不写配置、不过滤已有内容。
     */
    public static List<String> scanCandidates() {
        List<String> list = new ArrayList<>();
        try {
            // ── 构建综合 Block→ItemId 反查表 ──────────────────────────────
            Map<Block, ResourceLocation> blockToItemId = new HashMap<>();
            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                if (entry.getValue() instanceof BlockItem bi) {
                    blockToItemId.putIfAbsent(bi.getBlock(), entry.getKey().location());
                }
            }
            for (var entry : GameData.getBlockItemMap().entrySet()) {
                if (blockToItemId.containsKey(entry.getKey())) continue;
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getValue());
                if (itemId != null) blockToItemId.put(entry.getKey(), itemId);
            }

            // ── 遍历所有方块，按两个 phase 生成候选 ──────────────────────
            Map<String, String> result = new LinkedHashMap<>();
            for (ResourceLocation blockId : BuiltInRegistries.BLOCK.keySet()) {
                Block block = BuiltInRegistries.BLOCK.get(blockId);
                if (block == null || block == Blocks.AIR) continue;

                ResourceLocation itemId = blockToItemId.get(block);
                if (itemId != null) {
                    if (!itemId.equals(blockId)) {
                        result.put(blockId.toString(), itemId.toString());
                    }
                } else {
                    String target = inferNoItemTarget(blockId, block, blockToItemId);
                    if (target != null) result.put(blockId.toString(), target);
                }
            }

            result.forEach((k, v) -> list.add(k + "=" + v));
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] scanCandidates failed: {}", e.toString());
        }
        return list;
    }

    /**
     * Phase 2：为完全没有 BlockItem 的方块推导目标物品。
     * SPECIAL 表覆盖大多数原版情况；启发规则处理少数模式化命名（如彩色蜡烛蛋糕）。
     */
    private static String inferNoItemTarget(ResourceLocation id, Block block,
                                            Map<Block, ResourceLocation> blockToItemId) {
        // SPECIAL 表优先
        String special = SPECIAL.get(id.toString());
        if (special != null) {
            return special.isEmpty() ? null : validate(special);
        }

        // 流体（mod 流体，无桶）→ 丢弃
        BlockState state = block.defaultBlockState();
        if (!state.getFluidState().isEmpty()) return null;

        String ns   = id.getNamespace();
        String path = id.getPath();

        // *_candle_cake → *_candle（彩色蜡烛蛋糕，右键放置，非 BlockItem）
        if (path.endsWith("_candle_cake")) {
            String candleName = path.substring(0, path.length() - "_cake".length()); // *_candle
            return resolveStripped(ns, candleName, blockToItemId);
        }

        // 其余无法推导（mod 专有流体等）
        return null;
    }

    /**
     * 把剥离后的 path 解析为物品 ID：先直接查 ITEM 注册表，失败则经 blockToItemId 反查。
     */
    private static String resolveStripped(String ns, String strippedPath,
                                          Map<Block, ResourceLocation> blockToItemId) {
        String direct = validate(ns + ":" + strippedPath);
        if (direct != null) return direct;

        ResourceLocation blockRl = ResourceLocation.tryParse(ns + ":" + strippedPath);
        if (blockRl == null) return null;
        Block targetBlock = BuiltInRegistries.BLOCK.get(blockRl);
        if (targetBlock == null || targetBlock == Blocks.AIR) return null;
        ResourceLocation itemRl = blockToItemId.get(targetBlock);
        return itemRl != null ? itemRl.toString() : null;
    }

    private static String validate(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) ? itemId : null;
    }
}
