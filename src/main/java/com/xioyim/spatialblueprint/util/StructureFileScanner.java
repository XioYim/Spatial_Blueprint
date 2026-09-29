package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code .minecraft/schematics/} 目录的统一扫描入口。
 *
 * <p>这个目录不是我们挑的 —— Litematica 与 Create 都默认用它
 * （{@code CreatePaths.SCHEMATICS_DIR = GAMEDIR.resolve("schematics")}）。
 * 三个 mod 共用一个目录意味着玩家什么都不用配，存在哪儿的结构文件都能在上传列表里看到。</p>
 *
 * <p>各格式的解析器只管「怎么读一个文件」，「目录里有什么」集中在这里 ——
 * 否则每加一种格式都要在界面刷新逻辑里多拼一次列表。</p>
 */
public final class StructureFileScanner {

    private StructureFileScanner() {}

    /** 支持的扩展名（小写，含点）。顺序即界面上的展示优先级。 */
    public static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".litematic", ".nbt", ".schem", ".schematic");

    /**
     * 分类判定时最大愿意解压的文件字节数。
     *
     * <p>判分类要读文件顶层的 {@code SpatialBlueprintData.category}，而 NBT 没有部分读取，
     * 只能整份解压。超过这个尺寸的直接归入「其他」，不为了一个字段去解压几十兆。</p>
     */
    private static final long MAX_CLASSIFY_BYTES = 16L * 1024 * 1024;

    /** 结构文件所在目录：{@code .minecraft/schematics/}。 */
    public static File dir() {
        return new File(FMLPaths.GAMEDIR.get().toFile(), "schematics");
    }

    /**
     * 目录不存在就建出来。
     *
     * <p>启动时调用一次：空目录也比「没有这个文件夹」强 —— 玩家至少知道该往哪儿放文件，
     * 而不是打开上传界面看到一片空白，也不知道是没文件还是路径不对。</p>
     */
    public static void ensureDir() {
        File d = dir();
        if (d.isDirectory()) return;
        try {
            Files.createDirectories(d.toPath());
            SpatialBlueprintMod.LOGGER.info("[StructureFiles] 已创建结构文件目录: {}", d);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.warn("[StructureFiles] 创建目录失败 {}: {}", d, e.getMessage());
        }
    }

    /**
     * 扫描目录第一层，返回全部受支持的结构文件（按修改时间降序）。
     *
     * <p>只扫第一层，与 Litematica / Create 的行为一致 —— Create 把服务端上传的副本放在
     * {@code schematics/uploaded/<玩家名>/} 子目录，不进子目录天然就不会误抓。</p>
     */
    public static List<File> listAll() {
        File d = dir();
        List<File> result = new ArrayList<>();
        if (!d.isDirectory()) return result;
        File[] files = d.listFiles(f -> f.isFile() && isSupported(f.getName()));
        if (files == null) return result;
        for (File f : files) result.add(f);
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    /** 文件名的扩展名是否在支持列表里（大小写不敏感）。 */
    public static boolean isSupported(String fileName) {
        return !extensionOf(fileName).isEmpty();
    }

    /** 小写扩展名（含点）；不受支持时返回空串。 */
    public static String extensionOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) return ext;
        }
        return "";
    }

    // ── 分类判定 ──────────────────────────────────────────────────────────

    /**
     * 该文件属于哪个蓝图分类。
     *
     * <p>判据是文件顶层 {@code SpatialBlueprintData.category} —— <b>只有本 mod 自己导出的
     * 蓝图才有这个字段</b>。别人做的 .litematic、以及 .nbt / .schem / .schematic
     * 这些外来格式一律没有，归入「其他结构文件」。</p>
     *
     * @return {@code "create"} / {@code "project"}，其余一切情况返回空串
     */
    public static String categoryOf(File file) {
        return read(file).category;
    }

    /**
     * 文件内部记录的蓝图名（Litematica 标准 {@code Metadata.Name}）。
     *
     * <p>「空间 / 投影」两个上传 tab 只收本 mod 自己导出的 .litematic，这类文件一定带这个字段，
     * 列表里应显示它而不是文件名。读不到（外来格式 / 文件损坏 / 超大）时返回空串，
     * 调用方再退回文件名。</p>
     */
    public static String nameOf(File file) {
        return read(file).name;
    }

    /**
     * 该文件是不是<b>强制捕获（FORCE）</b>出来的蓝图。
     *
     * <p>FORCE 跳过了非法方块检测与 NBT 净化，内容完全没被审过 —— 这种文件不允许上传，
     * 否则等于给了一条「本地造一个 FORCE 蓝图再传上服务器」的绕过检测的路。
     * 外来格式没有这个字段，一律返回 {@code false}。</p>
     */
    public static boolean isForceCaptured(File file) {
        return read(file).saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE.ordinal();
    }

    /** 读文件顶层的 mod 字段（带缓存）；读不到时返回一份「什么都没有」的结果。 */
    private static Cached read(File file) {
        // 只有 .litematic 可能带我们的字段：另外三种格式的规范里没有放它的地方
        if (!".litematic".equals(extensionOf(file.getName()))) return Cached.EMPTY;
        if (file.length() > MAX_CLASSIFY_BYTES) return Cached.EMPTY;

        Cached hit = CACHE.get(file.getAbsolutePath());
        if (hit != null && hit.matches(file)) return hit;

        String category = "", creatorName = "", sourceName = "";
        String noteShort = "", noteLong = "", name = "";
        int saveMode = -1, animDuration = 0, materialCount = 0;
        long createdAt = 0L;
        try (FileInputStream fis = new FileInputStream(file)) {
            CompoundTag root = SafeNbtIo.readCompressed(fis);
            // Litematica 标准字段：蓝图真正的名字
            if (root.contains("Metadata", 10)) {
                name = root.getCompound("Metadata").getString("Name");
            }
            if (root.contains("SpatialBlueprintData", 10)) {
                CompoundTag mod = root.getCompound("SpatialBlueprintData");
                category    = mod.getString("category");
                if (mod.contains("saveMode"))    saveMode     = mod.getInt("saveMode");
                if (mod.contains("animDuration")) animDuration = mod.getInt("animDuration");
                creatorName = mod.getString("creatorName");
                sourceName  = mod.getString("sourceName");
                createdAt   = mod.getLong("createdAt");
                noteShort   = mod.getString("noteShort");
                noteLong    = mod.getString("noteLong");
                // 材料条数 = materialList.entries 的长度
                if (mod.contains("materialList", 10)) {
                    CompoundTag ml = mod.getCompound("materialList");
                    if (ml.contains("entries", 9)) materialCount = ml.getList("entries", 10).size();
                }
            }
        } catch (Exception e) {
            // 读不动就当外来文件 —— 真正的解析错误留给上传时的 Importer 报，这里不打扰玩家
            category = ""; creatorName = ""; sourceName = "";
            noteShort = ""; noteLong = ""; name = "";
            saveMode = -1; animDuration = 0; materialCount = 0; createdAt = 0L;
        }
        Cached fresh = new Cached(file.lastModified(), file.length(),
                category, saveMode, name, materialCount, animDuration,
                creatorName, sourceName, createdAt, noteShort, noteLong);
        CACHE.put(file.getAbsolutePath(), fresh);
        return fresh;
    }

    /**
     * 文件顶层 mod 字段的缓存。
     *
     * <p>切 tab、点刷新、改搜索词都会重新扫一遍目录，每次都去解压一遍所有 .litematic
     * 显然不行。按「路径 + 修改时间 + 大小」判定是否还有效，文件被换过就自动失效。</p>
     */
    private static final Map<String, Cached> CACHE = new HashMap<>();

    private record Cached(long lastModified, long length,
                          String category, int saveMode, String name,
                          int materialCount, int animDuration,
                          String creatorName, String sourceName, long createdAt,
                          String noteShort, String noteLong) {
        /** 外来文件 / 读不动的文件共用的空结果，不进缓存。 */
        static final Cached EMPTY = new Cached(0L, 0L, "", -1, "", 0, 0, "", "", 0L, "", "");

        boolean matches(File f) {
            return f.lastModified() == lastModified && f.length() == length;
        }
    }

    /** 手动刷新时清空缓存，让玩家「点了刷新还是旧的」不会发生。 */
    public static void invalidateCache() {
        CACHE.clear();
    }

    /** 一个 .litematic 源文件携带的顶层 mod 元数据视图（供上传列表 tooltip 展示）。 */
    public record FileMeta(String category, int saveMode, String name,
                           int materialCount, int animDuration,
                           String creatorName, String sourceName, long createdAt,
                           String noteShort, String noteLong) {

        /** 是否本 mod 自己导出的蓝图（有 mod 字段）。外来结构文件恒 false。 */
        public boolean isOwnModFile() {
            return !category.isEmpty() || saveMode >= 0 || !creatorName.isEmpty();
        }

        public boolean isForce() {
            return saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE.ordinal();
        }
    }

    /** 读取文件携带的完整顶层元数据（带缓存）；外来格式 / 读不动时返回空元数据。 */
    public static FileMeta metaOf(File file) {
        Cached c = read(file);
        return new FileMeta(c.category(), c.saveMode(), c.name(),
                c.materialCount(), c.animDuration(),
                c.creatorName(), c.sourceName(), c.createdAt(),
                c.noteShort(), c.noteLong());
    }
}
