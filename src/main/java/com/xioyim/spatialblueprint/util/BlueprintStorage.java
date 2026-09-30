package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saves and loads blueprints as Litematica-compatible {@code .litematic} NBT files in
 * {@code config/spatial_blueprint/<category>/<uuid>.litematic} (see {@link BlueprintData#serialize()}
 * for the on-disk schema). Filenames follow {@code <category>_<creatorName>_<uuid>.litematic}.
 *
 * <p>Category subdirectories: {@code create/}, {@code projections/}, {@code templates/},
 * {@code dimensional/}, {@code engineering/}.</p>
 *
 * <p>Thread safety: all calls are expected on the server thread.</p>
 */
public class BlueprintStorage {

    /** Known category subdirectory names (store removed — replaced by dimensional). */
    private static final String[] SUBDIRS = {"create", "project", "projections", "templates", "dimensional", "engineering", "reward"};

    /**
     * Maximum allowed blueprint file size in bytes (64 MB).
     * Files larger than this are refused by {@link #load} and skipped by {@link #listSummaries}
     * to prevent OOM from maliciously crafted or corrupted NBT files.
     */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private static Path saveDir;

    public static void init() {
        saveDir = FMLPaths.CONFIGDIR.get().resolve("spatial_blueprint");
        try {
            Files.createDirectories(saveDir);
            // Pre-create category subdirectories
            for (String sub : SUBDIRS) {
                Files.createDirectories(saveDir.resolve(sub));
            }
            SpatialBlueprintMod.LOGGER.info("[BlueprintStorage] Save directory: {}", saveDir);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to create save directory: {}", e.getMessage());
        }
    }

    private static Path getDir() {
        if (saveDir == null) init();
        return saveDir;
    }

    // ── Atomic write helper ───────────────────────────────────────────────────

    /**
     * Writes {@code tag} to {@code target} via a temporary sibling file, then atomically
     * renames it into place. If the write step fails the original file is untouched;
     * if the rename step fails the temporary file is cleaned up and the exception re-thrown.
     *
     * <p>Falls back to a non-atomic replace if the underlying filesystem does not support
     * atomic moves (e.g. cross-device or FAT32).</p>
     */
    private static void writeCompressedAtomic(CompoundTag tag, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(tag, tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** Returns the target subdirectory for a given category string. Root dir if unrecognised. */
    private static Path dirFor(String category) {
        return switch (category == null ? "" : category) {
            case "create"      -> getDir().resolve("create");
            case "project"     -> getDir().resolve("projections");
            case "template"    -> getDir().resolve("templates");
            case "dimensional" -> getDir().resolve("dimensional");
            case "engineering" -> getDir().resolve("engineering");
            case "reward"      -> getDir().resolve("reward");
            default            -> getDir();
        };
    }

    /**
     * Finds the existing {@code .litematic} file for a UUID by searching each
     * category subdirectory and then the root directory.
     * Filename format: {@code <category>_<creatorName>_<uuid>.litematic}.
     *
     * @return the path to the file, or {@code null} if not found anywhere.
     */
    public static Path findFile(String idStr) {
        Path root = getDir();
        List<Path> dirs = new ArrayList<>();
        for (String sub : SUBDIRS) dirs.add(root.resolve(sub));
        dirs.add(root);
        for (Path dir : dirs) {
            File[] candidates = dir.toFile().listFiles(
                    (d, name) -> name.endsWith("_" + idStr + ".litematic"));
            if (candidates != null && candidates.length > 0) return candidates[0].toPath();
        }
        // 子工程存在 projects/{projectId}/ 下，且文件名不带 "<category>_<name>_" 前缀，
        // 是纯 "{engUuid}.litematic" —— 上面那轮的下划线匹配对它无效，单独找一遍
        if (idStr == null) return null;
        try {
            UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Path projectsRoot = root.resolve("projects");
        File[] projectDirs = projectsRoot.toFile().listFiles(File::isDirectory);
        if (projectDirs != null) {
            for (File projectDir : projectDirs) {
                Path candidate = projectDir.toPath().resolve(idStr + ".litematic");
                if (Files.exists(candidate)) return candidate;
            }
        }
        return null;
    }

    /**
     * Strips characters that are unsafe in file names.
     * Keeps alphanumerics and underscore (Minecraft usernames are [A-Za-z0-9_]).
     * Leading/trailing underscores are trimmed to keep filenames tidy.
     */
    private static String sanitizeForFilename(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[^A-Za-z0-9_]", "").replaceAll("^_+|_+$", "");
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    /** Save a blueprint, generating a new UUID, and return that UUID. */
    public static UUID save(BlueprintData data) {
        UUID id = UUID.randomUUID();
        save(id, data);
        return id;
    }

    /** Save a blueprint under an existing UUID (overwrite). Routes to category subdir. */
    public static void save(UUID id, BlueprintData data) {
        invalidateSummaryCache();
        Path dir = dirFor(data.category);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to create subdir {}: {}", dir, e.getMessage());
        }
        String categoryPrefix = sanitizeForFilename(data.category.isEmpty() ? "unknown" : data.category);
        String namePrefix = sanitizeForFilename(data.creatorName);
        if (namePrefix.isEmpty()) namePrefix = "unknown";
        Path file = dir.resolve(categoryPrefix + "_" + namePrefix + "_" + id + ".litematic");
        try {
            writeCompressedAtomic(data.serialize(), file);
            SpatialBlueprintMod.LOGGER.debug("[BlueprintStorage] Saved blueprint '{}' → {}", data.name, file);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to save blueprint {} ('{}'): {}",
                    id, data.name, e.getMessage());
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    /**
     * Load a blueprint by UUID string. Searches subdirs then root for backward compat.
     * Returns {@code null} if not found or on error.
     */
    public static BlueprintData load(String idStr) {
        try {
            UUID.fromString(idStr); // validate
            Path file = findFile(idStr);
            if (file == null) {
                return null; // normal on the client side in multiplayer
            }
            long fileSize = Files.size(file);
            if (fileSize > MAX_FILE_BYTES) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[BlueprintStorage] Refusing to load oversized blueprint '{}': {} bytes (limit {} bytes)",
                        idStr, fileSize, MAX_FILE_BYTES);
                return null;
            }
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            return BlueprintData.deserialize(tag);
        } catch (IllegalArgumentException e) {
            SpatialBlueprintMod.LOGGER.warn("[BlueprintStorage] Invalid blueprint UUID '{}': {}", idStr, e.getMessage());
            return null;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to load blueprint '{}': {}", idStr, e.getMessage());
            return null;
        } catch (Exception e) {
            // BlueprintData.deserialize() or NbtIo may throw non-IO exceptions on corrupt data (L7).
            SpatialBlueprintMod.LOGGER.warn("[BlueprintStorage] Failed to deserialize blueprint '{}': {}", idStr, e.getMessage());
            return null;
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public static void delete(String idStr) {
        invalidateSummaryCache();
        try {
            Path file = findFile(idStr);
            if (file != null) Files.deleteIfExists(file);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn("[BlueprintStorage] Failed to delete blueprint '{}': {}", idStr, e.getMessage());
        }
    }

    /**
     * 把蓝图文件重命名为 {@code .bak}，而不是真正删除。
     *
     * <p>用于 OP 清理尚未放置（{@code placed == false}）的次元蓝图 —— 这类蓝图仍可被玩家
     * 重新领取，误删等于同时丢失结构与材料，因此只做改名。{@code .bak} 后缀不被
     * {@link #collectLitematicFiles} 与 {@link #findFile} 识别，文件会从列表和加载路径中消失，
     * 但仍留在磁盘上，需要手动进服务器目录才能真正删除。</p>
     *
     * @return 改名后的文件名；未找到文件或改名失败时返回 {@code null}
     */
    @Nullable
    public static String renameToBak(String idStr) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return null;
        try {
            String bakName = file.getFileName().toString() + ".bak";
            Path target = file.resolveSibling(bakName);
            // 同名 .bak 已存在时追加时间戳，避免覆盖上一次的备份
            if (Files.exists(target)) {
                bakName = file.getFileName() + "." + System.currentTimeMillis() + ".bak";
                target = file.resolveSibling(bakName);
            }
            Files.move(file, target);
            SpatialBlueprintMod.LOGGER.info(
                    "[BlueprintStorage] Renamed blueprint '{}' → {} (not deleted)", idStr, bakName);
            return bakName;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintStorage] Failed to rename blueprint '{}': {}", idStr, e.getMessage());
            return null;
        }
    }

    /**
     * 写入次元蓝图的「已放置」标记与放置点信息。
     * 只改 {@code SpatialBlueprintData} 下的少数键，不做完整反序列化往返。
     */
    public static boolean setPlaced(String idStr, int x, int y, int z, String dimension) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            CompoundTag mod = tag.getCompound("SpatialBlueprintData");
            mod.putBoolean("placed", true);
            mod.putInt("placedX", x);
            mod.putInt("placedY", y);
            mod.putInt("placedZ", z);
            mod.putString("placedDimension", dimension == null ? "" : dimension);
            tag.put("SpatialBlueprintData", mod);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintStorage] Failed to mark blueprint '{}' as placed: {}", idStr, e.getMessage());
            return false;
        }
    }

    /**
     * Renames a blueprint in-place by rewriting only {@code Metadata.Name}.
     * Avoids a full deserialize/serialize round-trip.
     */
    public static boolean rename(String idStr, String newName) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            tag.getCompound("Metadata").putString("Name", newName);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to rename blueprint '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    public static boolean updateNotes(String idStr, String noteShort, String noteLong) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            CompoundTag mod = tag.getCompound("SpatialBlueprintData");
            if (noteShort.isEmpty()) mod.remove("noteShort");
            else mod.putString("noteShort", noteShort);
            if (noteLong.isEmpty()) mod.remove("noteLong");
            else mod.putString("noteLong", noteLong);
            tag.put("SpatialBlueprintData", mod);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintStorage] Failed to update notes '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    /**
     * Converts a blueprint's category between {@code "create"} (Spatial) and {@code "project"}
     * (Projection). Only these two categories are supported; any other category is a no-op.
     *
     * <p>The file is moved from the old category subdirectory to the new one with an updated
     * filename prefix. Only the {@code SpatialBlueprintData.category} NBT field changes;
     * all other blueprint data (name, creator, timestamps, notes, etc.) is preserved.</p>
     *
     * @return {@code true} if the conversion succeeded, {@code false} on error or unsupported category.
     */
    public static boolean convertCategory(String idStr) {
        invalidateSummaryCache();
        Path oldFile = findFile(idStr);
        if (oldFile == null) return false;
        try {
            CompoundTag tag     = SafeNbtIo.readCompressed(oldFile.toFile());
            CompoundTag sbData  = tag.getCompound("SpatialBlueprintData");
            String oldCategory  = sbData.getString("category");
            String newCategory  = switch (oldCategory) {
                case "create"  -> "project";
                case "project" -> "create";
                default        -> null;
            };
            if (newCategory == null) return false;

            sbData.putString("category", newCategory);
            tag.put("SpatialBlueprintData", sbData);

            String creatorName = sbData.getString("creatorName");
            String safeCreator = sanitizeForFilename(creatorName);
            if (safeCreator.isEmpty()) safeCreator = "unknown";

            Path newDir = dirFor(newCategory);
            Files.createDirectories(newDir);
            Path newFile = newDir.resolve(
                    sanitizeForFilename(newCategory) + "_" + safeCreator + "_" + idStr + ".litematic");

            writeCompressedAtomic(tag, newFile);
            Files.deleteIfExists(oldFile);
            SpatialBlueprintMod.LOGGER.debug(
                    "[BlueprintStorage] Converted blueprint '{}': {} → {}", idStr, oldCategory, newCategory);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintStorage] Failed to convert category for '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    /** Updates only the {@code animDuration} key under {@code SpatialBlueprintData}. */
    public static boolean setAnimDuration(String idStr, int seconds) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            CompoundTag mod = tag.getCompound("SpatialBlueprintData");
            mod.putInt("animDuration", seconds);
            tag.put("SpatialBlueprintData", mod);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to set animDuration for '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    // ── Summary cache ─────────────────────────────────────────────────────────
    /** Cached result of the last {@link #listSummaries()} call. Null when dirty. */
    private static java.util.List<BlueprintSummary> summaryCache = null;

    /**
     * Invalidates the in-process summary cache so the next {@link #listSummaries()} call
     * re-scans disk. Must be called after any operation that creates, modifies, or deletes
     * a blueprint file.
     */
    public static void invalidateSummaryCache() {
        summaryCache = null;
    }

    // ── Blueprint summaries (lightweight scan for library UI) ─────────────────

    /**
     * Lightweight summary of a blueprint, extracted without decoding the block-state array.
     *
     * @param id           UUID string of the .litematic file
     * @param name         Display name stored inside the file
     * @param category     "create", "project", "dimensional", "template", "engineering", or ""
     * @param blockCount   Total block count ({@code Metadata.TotalBlocks})
     * @param animDuration Animation duration in seconds (0 if not set)
     */
    public record BlueprintSummary(String id, String name, String category, int blockCount, int animDuration,
                                   /** 材料清单条目数（SpatialBlueprintData.materialList.entries）；0 = 无材料清单。 */
                                   int materialCount,
                                   String creatorName, long createdAt,
                                   BlockPos fixedStartPos, BlockPos fixedEndPos,
                                   String fixedDimension,
                                   String sourceName,
                                   com.xioyim.spatialblueprint.util.CaptureMode saveMode,
                                   String noteShort,
                                   String noteLong,
                                   boolean placed, int placedX, int placedY, int placedZ,
                                   String placedDimension,
                                   /** 归属玩家 UUID 字符串；空 = 无主（本字段之前捕获的旧蓝图）。 */
                                   String ownerUuid,
                                   /**
                                    * 该蓝图<b>不属于</b>当前存档。扫描时就地判定（服务端能拿到当前存档 UUID、
                                    * 客户端拿不到），所以这里存的是结论而非原始 UUID。
                                    * 未记录存档的旧蓝图恒为 false，不去为难旧数据。
                                    */
                                   boolean foreignWorld,
                                   /** 创建时所在存档名，仅用于界面显示「所属存档」。 */
                                   String worldName,
                                   /** 上传来源：0=非上传 1=安全上传 2=强制上传。 */
                                   int uploadMode,
                                   /**
                                    * 工程蓝图的安全传送坐标（OP 在蓝图库 [TP] 设；存服务端 SBSafeCoordData，
                                    * 不在文件里）。listSummaries 一律填 false/0，C2S_RequestLibraryMessage 下发前合并。
                                    * 放在末尾：摘要 wire 追加字段（T3）。
                                    */
                                   boolean safeCoordSet, int safeX, int safeY, int safeZ) {

        /** 只换安全坐标四个字段，其余原样（record 没有 wither，服务端合并 SavedData 时用）。 */
        public BlueprintSummary withSafeCoord(boolean set, int x, int y, int z) {
            return new BlueprintSummary(id, name, category, blockCount, animDuration, materialCount,
                    creatorName, createdAt, fixedStartPos, fixedEndPos, fixedDimension,
                    sourceName, saveMode, noteShort, noteLong,
                    placed, placedX, placedY, placedZ, placedDimension, ownerUuid, foreignWorld, worldName,
                    uploadMode, set, x, y, z);
        }
        /**
         * 该蓝图产自别的存档，且属于「换个存档就失去意义」的类型。
         *
         * <p>只有工程蓝图与次元蓝图绑定了固定坐标与维度 —— 空间 / 投影 / 模板本来就是
         * 拿到哪儿都能放的，不受存档约束。</p>
         */
        public boolean worldLocked() {
            return foreignWorld
                    && ("engineering".equals(category) || "dimensional".equals(category));
        }
    }

    /**
     * Scans the save directory (root + all category subdirs) and returns a lightweight
     * summary for each blueprint. Only reads top-level NBT keys; block entries are not
     * deserialized (fast scan). Returns an empty list if the directory is unavailable.
     */
    public static List<BlueprintSummary> listSummaries() {
        if (summaryCache != null) return summaryCache;
        List<BlueprintSummary> result = new ArrayList<>();
        Path root = getDir();

        // Collect files from root and all subdirs
        List<File> allFiles = new ArrayList<>();
        collectLitematicFiles(root.toFile(), allFiles);
        for (String sub : SUBDIRS) {
            File subDir = root.resolve(sub).toFile();
            if (subDir.exists()) collectLitematicFiles(subDir, allFiles);
        }

        for (File file : allFiles) {
            try {
                String filename = file.getName();
                // Strip ".litematic" suffix; UUID is the last '_'-separated segment
                String stem = filename.substring(0, filename.length() - ".litematic".length());
                int sep = stem.lastIndexOf('_');
                String id = sep >= 0 ? stem.substring(sep + 1) : stem;
                UUID.fromString(id);  // validate; throws if not a UUID
                if (file.length() > MAX_FILE_BYTES) {
                    SpatialBlueprintMod.LOGGER.warn(
                            "[BlueprintStorage] Skipping oversized blueprint file '{}': {} bytes",
                            filename, file.length());
                    continue;
                }
                CompoundTag tag = SafeNbtIo.readCompressed(file);
                CompoundTag metadata = tag.getCompound("Metadata");
                CompoundTag mod = tag.getCompound("SpatialBlueprintData");

                String name         = metadata.getString("Name");
                String category     = mod.contains("category")    ? mod.getString("category")  : "";
                int animDuration    = mod.contains("animDuration")        ? mod.getInt("animDuration")        : 0;
                String creatorName  = mod.contains("creatorName") ? mod.getString("creatorName") : "";
                String sourceName   = mod.contains("sourceName")  ? mod.getString("sourceName")  : "";
                String noteShort    = mod.contains("noteShort")   ? mod.getString("noteShort")   : "";
                String noteLong     = mod.contains("noteLong")    ? mod.getString("noteLong")    : "";
                long   createdAt    = mod.contains("createdAt")   ? mod.getLong("createdAt")     : 0L;
                int materialCount  = 0;
                if (mod.contains("materialList", 10)) {
                    CompoundTag ml = mod.getCompound("materialList");
                    if (ml.contains("entries", 9)) materialCount = ml.getList("entries", 10).size();
                }
                int blockCount      = metadata.contains("TotalBlocks") ? metadata.getInt("TotalBlocks") : 0;
                BlockPos fixedStartPos = NbtCompat.readBlockPos(mod, "fixedStartPos");
                BlockPos fixedEndPos   = NbtCompat.readBlockPos(mod, "fixedEndPos");
                String fixedDimension = mod.contains("fixedDimension") ? mod.getString("fixedDimension") : "";
                com.xioyim.spatialblueprint.util.CaptureMode saveMode =
                        mod.contains("saveMode")
                                ? com.xioyim.spatialblueprint.util.CaptureMode.fromOrdinalOrSafe(mod.getInt("saveMode"))
                                : com.xioyim.spatialblueprint.util.CaptureMode.SAFE;
                boolean placed  = mod.getBoolean("placed");
                int placedX     = mod.getInt("placedX");
                int placedY     = mod.getInt("placedY");
                int placedZ     = mod.getInt("placedZ");
                String placedDim = mod.contains("placedDimension") ? mod.getString("placedDimension") : "";
                String ownerUuid = mod.contains("ownerUuid") ? mod.getString("ownerUuid") : "";
                String worldUuid = mod.contains("worldUuid") ? mod.getString("worldUuid") : "";
                String worldName = mod.contains("worldName") ? mod.getString("worldName") : "";
                boolean foreignWorld = !WorldIdentity.belongsToCurrentWorld(worldUuid);
                int uploadMode = mod.contains("uploadMode") ? mod.getInt("uploadMode") : 0;
                result.add(new BlueprintSummary(id, name, category, blockCount, animDuration, materialCount,
                        creatorName, createdAt, fixedStartPos, fixedEndPos, fixedDimension,
                        sourceName, saveMode, noteShort, noteLong,
                        placed, placedX, placedY, placedZ, placedDim, ownerUuid, foreignWorld, worldName,
                        uploadMode, false, 0, 0, 0));
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[BlueprintStorage] Skipped unreadable file '{}': {}",
                        file.getName(), e.getMessage());
            }
        }
        summaryCache = result;
        return summaryCache;
    }

    /**
     * 查某蓝图的归属玩家 UUID —— 走摘要缓存，不做完整反序列化。
     *
     * <p>用于右键入口这类只需判归属、不需要方块数据的场景；蓝图不存在或无主时返回空串。</p>
     */
    public static String ownerUuidOf(String blueprintId) {
        if (blueprintId == null || blueprintId.isEmpty()) return "";
        for (BlueprintSummary s : listSummaries()) {
            if (s.id().equals(blueprintId)) {
                return s.ownerUuid() == null ? "" : s.ownerUuid();
            }
        }
        return "";
    }

    /**
     * 统计某玩家在某分类下已占用的槽位数。
     *
     * <p>只数 {@code ownerUuid} 精确匹配的 —— <b>无主的旧蓝图不计入任何人</b>
     * （归属制度加入前捕获的那批，谁都用不了，算进谁的额度都不合理）。
     * 归档成 {@code .bak} 的文件不被 {@code collectLitematicFiles} 识别，天然不计入。</p>
     *
     * <p>已放置的次元蓝图<b>仍然占槽位</b> —— 文件还在，玩家删掉它才释放。</p>
     */
    public static int countSlots(String ownerUuid, String category) {
        if (ownerUuid == null || ownerUuid.isEmpty()) return 0;
        int n = 0;
        for (BlueprintSummary s : listSummaries()) {
            if (ownerUuid.equals(s.ownerUuid()) && category.equals(s.category())) n++;
        }
        return n;
    }

    /** Collects {@code .litematic} files directly inside {@code dir}. */
    private static void collectLitematicFiles(File dir, List<File> out) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".litematic"));
        if (files != null) {
            for (File f : files) out.add(f);
        }
    }
}
