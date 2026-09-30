package com.xioyim.spatialblueprint.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ForagersInsight（觅食洞察）兼容：启动时关闭它的作物收获经验。
 *
 * <p>病灶：FI 的 {@code FarmingXPEvents.onCropHarvest(BreakEvent)}
 * 监听收菜就发经验，不检查事件是否取消、方块是否真被挖。
 * 而次元捕获 / 空置重存的领地预检要逐格 {@code post BreakEvent}，
 * FI 会把预检当成收菜，全田一次爆发；放下再收可重复刷。</p>
 *
 * <p>做法：只改 {@code config/foragersinsight-common.toml} 里
 * {@code ["XP Source Toggles"]} 下的一行
 * {@code "Crop Harvest XP" = true} → {@code false}，其余不动。
 * 幂等（已是 false / 文件不存在 / 键不存在一律直接返回）。
 * FI 没装的服自然跳过；单机走客户端实例的 config 目录，同一套代码。</p>
 */
public final class ForagersInsightCompat {

    private ForagersInsightCompat() {}

    /** 目标行（大小写按原样、等号两侧空白容忍，只匹配 true，避免误伤 false）。 */
    private static final Pattern CROP_HARVEST_XP_TRUE =
            Pattern.compile("(?m)^([ \\t]*\"Crop Harvest XP\"[ \\t]*=[ \\t]*)true([ \\t]*)$");

    public static void patchCropHarvestXpOff() {
        Path toml;
        try {
            toml = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("foragersinsight-common.toml");
        } catch (Exception e) {
            return;
        }
        if (!Files.isRegularFile(toml)) return; // FI 没装
        String text;
        try {
            text = Files.readString(toml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        Matcher m = CROP_HARVEST_XP_TRUE.matcher(text);
        if (!m.find()) return; // 已是 false，或键名对不上版本 → 不动
        try {
            Files.writeString(toml, m.replaceFirst("$1false$2"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[Compat] ForagersInsight Crop Harvest XP 已关闭（次元捕获不再爆发经验）");
    }
}
