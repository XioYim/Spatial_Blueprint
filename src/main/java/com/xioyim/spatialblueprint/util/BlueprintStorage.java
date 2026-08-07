package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraftforge.fml.loading.FMLPaths;

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
    private static final String[] SUBDIRS = {"create", "project", "projections", "templates", "dimensional", "engineering"};

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
            NbtIo.writeCompressed(tag, tmp.toFile());
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
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
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
     * Renames a blueprint in-place by rewriting only {@code Metadata.Name}.
     * Avoids a full deserialize/serialize round-trip.
     */
    public static boolean rename(String idStr, String newName) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            tag.getCompound("Metadata").putString("Name", newName);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to rename blueprint '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    /** Updates only the {@code customWithdrawXp} key under {@code SpatialBlueprintData}. Pass -1 to remove the key. */
    public static boolean setCustomWithdrawXp(String idStr, int xp) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            CompoundTag mod = tag.getCompound("SpatialBlueprintData");
            if (xp < 0) {
                mod.remove("customWithdrawXp");
            } else {
                mod.putInt("customWithdrawXp", xp);
            }
            tag.put("SpatialBlueprintData", mod);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to set customWithdrawXp for '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    /** Updates only the {@code customWithdrawLimit} key under {@code SpatialBlueprintData}. Pass -1 to remove the key. */
    public static boolean setCustomWithdrawLimit(String idStr, int limit) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            CompoundTag mod = tag.getCompound("SpatialBlueprintData");
            if (limit < 0) {
                mod.remove("customWithdrawLimit");
            } else {
                mod.putInt("customWithdrawLimit", limit);
            }
            tag.put("SpatialBlueprintData", mod);
            writeCompressedAtomic(tag, file);
            return true;
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintStorage] Failed to set customWithdrawLimit for '{}': {}", idStr, e.getMessage());
            return false;
        }
    }

    /** Updates only the {@code animDuration} key under {@code SpatialBlueprintData}. */
    public static boolean setAnimDuration(String idStr, int seconds) {
        invalidateSummaryCache();
        Path file = findFile(idStr);
        if (file == null) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
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
                                   int customWithdrawXp, int customWithdrawLimit,
                                   String creatorName, long createdAt,
                                   BlockPos fixedStartPos, BlockPos fixedEndPos, int maxDistance,
                                   String fixedDimension,
                                   boolean safeCoordSet, int safeX, int safeY, int safeZ,
                                   boolean safeAllowSurvival,
                                   String sourceName) {}

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
                CompoundTag tag = NbtIo.readCompressed(file);
                CompoundTag metadata = tag.getCompound("Metadata");
                CompoundTag mod = tag.getCompound("SpatialBlueprintData");

                String name         = metadata.getString("Name");
                String category     = mod.contains("category")    ? mod.getString("category")  : "";
                int animDuration    = mod.contains("animDuration")        ? mod.getInt("animDuration")        : 0;
                int cwXp            = mod.contains("customWithdrawXp")    ? mod.getInt("customWithdrawXp")    : -1;
                int cwLimit         = mod.contains("customWithdrawLimit") ? mod.getInt("customWithdrawLimit") : -1;
                String creatorName  = mod.contains("creatorName") ? mod.getString("creatorName") : "";
                String sourceName   = mod.contains("sourceName")  ? mod.getString("sourceName")  : "";
                long   createdAt    = mod.contains("createdAt")   ? mod.getLong("createdAt")     : 0L;
                int blockCount      = metadata.contains("TotalBlocks") ? metadata.getInt("TotalBlocks") : 0;
                BlockPos fixedStartPos = mod.contains("fixedStartPos", 10)
                        ? NbtUtils.readBlockPos(mod.getCompound("fixedStartPos")) : null;
                BlockPos fixedEndPos   = mod.contains("fixedEndPos",   10)
                        ? NbtUtils.readBlockPos(mod.getCompound("fixedEndPos"))   : null;
                int maxDistance = mod.contains("maxDistance") ? mod.getInt("maxDistance") : -1;
                String fixedDimension = mod.contains("fixedDimension") ? mod.getString("fixedDimension") : "";
                result.add(new BlueprintSummary(id, name, category, blockCount, animDuration, cwXp, cwLimit,
                        creatorName, createdAt, fixedStartPos, fixedEndPos, maxDistance, fixedDimension,
                        false, 0, 0, 0, false, sourceName));
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn("[BlueprintStorage] Skipped unreadable file '{}': {}",
                        file.getName(), e.getMessage());
            }
        }
        summaryCache = result;
        return summaryCache;
    }

    /** Collects {@code .litematic} files directly inside {@code dir}. */
    private static void collectLitematicFiles(File dir, List<File> out) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".litematic"));
        if (files != null) {
            for (File f : files) out.add(f);
        }
    }
}
