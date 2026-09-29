package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 项目蓝图的文件 I/O。
 *
 * <p>目录结构：</p>
 * <pre>
 * config/spatial_blueprint/projects/{project-uuid}/
 *   project.dat                     ← ProjectData 序列化（与蓝图格式无关，不受影响）
 *   {eng-uuid}.litematic            ← 子工程（工程蓝图格式，BlueprintData，litematic 兼容 NBT）
 * </pre>
 *
 * <p>所有写操作先写临时文件再原子移动，确保文件不被损坏。</p>
 */
public class ProjectStorage {

    private static Path projectsRoot;

    public static void init() {
        projectsRoot = FMLPaths.CONFIGDIR.get()
                .resolve("spatial_blueprint")
                .resolve("projects");
        try {
            Files.createDirectories(projectsRoot);
            SpatialBlueprintMod.LOGGER.info("[ProjectStorage] Directory: {}", projectsRoot);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to create directory: {}", e.getMessage());
        }
    }

    private static Path root() {
        if (projectsRoot == null) init();
        return projectsRoot;
    }

    /** 返回某项目的目录路径（不保证存在）。 */
    public static Path projectDir(UUID projectId) {
        return root().resolve(projectId.toString());
    }

    /** 返回 project.dat 路径。 */
    public static Path projectDatPath(UUID projectId) {
        return projectDir(projectId).resolve("project.dat");
    }

    /** 返回子工程文件路径：{eng-uuid}.litematic（不带 index，见 saveEntry 注释）。 */
    public static Path entryDatPath(UUID projectId, UUID engId) {
        return projectDir(projectId).resolve(engId + ".litematic");
    }

    // ── 写 project.dat ────────────────────────────────────────────────────────

    public static void saveProject(UUID projectId, ProjectData data) {
        Path dir = projectDir(projectId);
        try {
            Files.createDirectories(dir);
            writeAtomic(data.serialize(), projectDatPath(projectId));
            SpatialBlueprintMod.LOGGER.debug("[ProjectStorage] Saved project '{}' → {}", data.name, projectDatPath(projectId));
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to save project {}: {}", projectId, e.getMessage());
        }
    }

    // ── 读 project.dat ────────────────────────────────────────────────────────

    /** 读取项目元数据，不存在或出错时返回 null。 */
    public static ProjectData loadProject(UUID projectId) {
        Path file = projectDatPath(projectId);
        if (!Files.exists(file)) return null;
        try {
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            return ProjectData.deserialize(tag);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to load project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 判断项目目录是否存在。 */
    public static boolean projectExists(UUID projectId) {
        return Files.exists(projectDatPath(projectId));
    }

    /**
     * 扫 {@code projects/} 下所有带 project.dat 的目录，返回其 UUID。
     * 目录名不是合法 UUID 的（手工拷进来的杂物）跳过不报错。
     */
    public static java.util.List<UUID> listProjectIds() {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        java.io.File[] dirs = root().toFile().listFiles(java.io.File::isDirectory);
        if (dirs == null) return ids;
        for (java.io.File dir : dirs) {
            UUID id;
            try {
                id = UUID.fromString(dir.getName());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (projectExists(id)) ids.add(id);
        }
        return ids;
    }

    // ── 写子工程文件（从工程蓝图 BlueprintData 复制）────────────────────────

    /**
     * 将工程蓝图数据写入项目子工程文件。
     * 文件名格式：{eng-uuid}.litematic
     *
     * @param projectId 目标项目 UUID
     * @param engId     工程蓝图的原始 UUID（用于文件名）
     * @param data      工程蓝图数据
     * @return 实际写入的文件名（不含路径），供存入 EntryData.filename
     */
    public static String saveEntry(UUID projectId, UUID engId, BlueprintData data) {
        // 文件名只用 engUuid，不带 index —— 删除条目后重新编号会让文件名与 index
        // 对不上，index 只存在 EntryData 里。
        String filename = engId + ".litematic";
        Path file = entryDatPath(projectId, engId);
        try {
            Files.createDirectories(projectDir(projectId));
            writeAtomic(data.serialize(), file);
            SpatialBlueprintMod.LOGGER.debug("[ProjectStorage] Saved entry {} → {}", engId, file);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to save entry {}/{}: {}", projectId, engId, e.getMessage());
        }
        return filename;
    }

    /** 读取子工程的 BlueprintData（根据 EntryData.filename）。 */
    public static BlueprintData loadEntry(UUID projectId, String filename) {
        Path file = projectDir(projectId).resolve(filename);
        if (!Files.exists(file)) return null;
        try {
            CompoundTag tag = SafeNbtIo.readCompressed(file.toFile());
            return BlueprintData.deserialize(tag);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to load entry {}/{}: {}", projectId, filename, e.getMessage());
            return null;
        }
    }

    /** 删除子工程文件；失败只记 error 不抛（条目照样移除，避免留个删不掉的鬼条目）。 */
    public static void deleteEntryFile(UUID projectId, String filename) {
        if (filename == null || filename.isEmpty()) return;
        try {
            Files.deleteIfExists(projectDir(projectId).resolve(filename));
            SpatialBlueprintMod.LOGGER.debug("[ProjectStorage] Deleted entry {}", filename);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to delete entry {}/{}: {}",
                    projectId, filename, e.getMessage());
        }
    }

    // ── 删除整个项目（project.dat + 全部子工程文件）─────────────────────────

    /**
     * 删除项目目录及其全部内容。返回删除后目录是否已不存在 ——
     * 目录本来就不在也算成功（目标状态达成）；个别文件删不掉时记 error 并返回 false。
     */
    public static boolean deleteProject(UUID projectId) {
        Path dir = projectDir(projectId);
        if (!Files.exists(dir)) return true;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to walk project {}: {}", projectId, e.getMessage());
            return false;
        }
        boolean gone = !Files.exists(dir);
        if (gone) SpatialBlueprintMod.LOGGER.info("[ProjectStorage] Deleted project {}", projectId);
        return gone;
    }

    // ── 原子写入 ──────────────────────────────────────────────────────────────

    private static void writeAtomic(CompoundTag tag, Path target) throws IOException {
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
}
