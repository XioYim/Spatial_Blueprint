package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraftforge.fml.loading.FMLPaths;

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
 *   {index}_{eng-uuid}.litematic    ← 子工程（工程蓝图格式，BlueprintData，litematic 兼容 NBT）
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

    /** 返回子工程文件路径：{index}_{eng-uuid}.litematic */
    public static Path entryDatPath(UUID projectId, int index, UUID engId) {
        return projectDir(projectId).resolve(index + "_" + engId + ".litematic");
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
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
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

    // ── 写子工程文件（从工程蓝图 BlueprintData 复制）────────────────────────

    /**
     * 将工程蓝图数据写入项目子工程文件。
     * 文件名格式：{index}_{eng-uuid}.litematic
     *
     * @param projectId 目标项目 UUID
     * @param index     子工程序号（1-based）
     * @param engId     工程蓝图的原始 UUID（用于文件名）
     * @param data      工程蓝图数据
     * @return 实际写入的文件名（不含路径），供存入 EntryData.filename
     */
    public static String saveEntry(UUID projectId, int index, UUID engId, BlueprintData data) {
        String filename = index + "_" + engId + ".litematic";
        Path file = projectDir(projectId).resolve(filename);
        try {
            Files.createDirectories(projectDir(projectId));
            writeAtomic(data.serialize(), file);
            SpatialBlueprintMod.LOGGER.debug("[ProjectStorage] Saved entry {} → {}", index, file);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to save entry {}/{}: {}", projectId, index, e.getMessage());
        }
        return filename;
    }

    /** 读取子工程的 BlueprintData（根据 EntryData.filename）。 */
    public static BlueprintData loadEntry(UUID projectId, String filename) {
        Path file = projectDir(projectId).resolve(filename);
        if (!Files.exists(file)) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return BlueprintData.deserialize(tag);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.error("[ProjectStorage] Failed to load entry {}/{}: {}", projectId, filename, e.getMessage());
            return null;
        }
    }

    // ── 原子写入 ──────────────────────────────────────────────────────────────

    private static void writeAtomic(CompoundTag tag, Path target) throws IOException {
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
}
