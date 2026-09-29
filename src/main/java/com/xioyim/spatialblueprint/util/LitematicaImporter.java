package com.xioyim.spatialblueprint.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;

/**
 * 将 Litematica MOD 的 {@code .litematic} 文件（gzip 压缩 NBT）转换为 {@link BlueprintData}。
 *
 * <p>与老式 {@code .schematic}（MCEdit/Schematica）完全是两种不同格式，不能共用同一套解析逻辑：
 * <ul>
 *   <li>方块状态直接以 {@code Name}+{@code Properties} 存在调色板（palette）里，
 *       与原版结构方块 NBT 格式一致，可直接用 {@link NbtUtils#readBlockState}</li>
 *   <li>方块索引以变长位压缩打包进 {@code BlockStates} 长整型数组，条目允许跨越 long 边界
 *       （不是 1.16+ 版本区块那种"从不跨边界"的打包方式）——具体编解码见 {@link LitematicaCodec}，
 *       {@link BlueprintData} 的原生存储格式也共用这一套编解码</li>
 *   <li>支持多区域（Regions），每个区域有独立的 Position/Size（Size 分量可以是负数，
 *       表示该轴方向从 Position 往负方向延伸），需要合并计算整体包围盒</li>
 * </ul>
 *
 * <p>实体（画/展示框等）随区域的 {@code Entities} 列表导入，与 .nbt 同口径
 * （只搬运、不过滤，过滤统一交给 {@code BlueprintSanitizer.filterEntitiesInPlace}）。
 * 实体坐标按区域局部坐标理解，与方块走同一套 区域→全局 换算。</p>
 */
public final class LitematicaImporter {

    private LitematicaImporter() {}


    /** 单个区域解析结果的中间态（第一遍扫描 Position/Size/调色板/位压缩数组，尚未落到全局坐标）。 */
    private static final class Region {
        CompoundTag tag; // 区域原始 NBT（第二遍读 Entities 用）
        int posX, posY, posZ;
        int signX, signY, signZ;
        int sizeX, sizeY, sizeZ; // 绝对值（格子数）
        List<BlockState> palette;
        long[] states;
        int bitsPerEntry;
        HashMap<Long, CompoundTag> tileMap; // 区域局部坐标 (x,y,z) → 方块实体 NBT

        int minX() { return Math.min(posX, posX + (sizeX - 1) * signX); }
        int maxX() { return Math.max(posX, posX + (sizeX - 1) * signX); }
        int minY() { return Math.min(posY, posY + (sizeY - 1) * signY); }
        int maxY() { return Math.max(posY, posY + (sizeY - 1) * signY); }
        int minZ() { return Math.min(posZ, posZ + (sizeZ - 1) * signZ); }
        int maxZ() { return Math.max(posZ, posZ + (sizeZ - 1) * signZ); }
    }

    /**
     * 读取 .litematic 文件并转换为 BlueprintData。
     *
     * @param file       .litematic 文件（gzip-NBT，Litematica MOD 格式）
     * @param name       赋予蓝图的名称，为空时使用文件名
     * @param playerName 创建者名称
     * @return 填充好的 BlueprintData
     * @throws StructureImportException 带上具体失败原因的语言键，供界面显示
     */
    public static BlueprintData readLitematic(File file, String name, String playerName)
            throws StructureImportException {
        if (!file.exists() || !file.isFile()) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.file_missing");
        }

        CompoundTag root;
        try (FileInputStream fis = new FileInputStream(file)) {
            root = SafeNbtIo.readCompressed(fis);
        } catch (IOException e) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.read_failed", e);
        }

        if (!root.contains("Regions", 10) || root.getCompound("Regions").isEmpty()) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_litematic");
        }

        CompoundTag regionsTag = root.getCompound("Regions");

        // ── 第一遍：解析每个区域的几何信息 + 调色板 + 位压缩数组，计算整体包围盒 ──
        List<Region> regions = new ArrayList<>();
        int globalMinX = Integer.MAX_VALUE, globalMinY = Integer.MAX_VALUE, globalMinZ = Integer.MAX_VALUE;
        int globalMaxX = Integer.MIN_VALUE, globalMaxY = Integer.MIN_VALUE, globalMaxZ = Integer.MIN_VALUE;

        for (String regionName : regionsTag.getAllKeys()) {
            CompoundTag regionTag = regionsTag.getCompound(regionName);
            if (!regionTag.contains("Position", 10) || !regionTag.contains("Size", 10)
                    || !regionTag.contains("BlockStatePalette", 9) || !regionTag.contains("BlockStates")) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[LitematicaImport] 区域 '{}' 缺少必要字段，已跳过: {}", regionName, file);
                continue;
            }

            CompoundTag posTag  = regionTag.getCompound("Position");
            CompoundTag sizeTag = regionTag.getCompound("Size");
            int rawSizeX = sizeTag.getInt("x");
            int rawSizeY = sizeTag.getInt("y");
            int rawSizeZ = sizeTag.getInt("z");
            if (rawSizeX == 0 || rawSizeY == 0 || rawSizeZ == 0) continue; // 空区域

            Region region = new Region();
            region.tag = regionTag;
            region.posX = posTag.getInt("x");
            region.posY = posTag.getInt("y");
            region.posZ = posTag.getInt("z");
            region.signX = rawSizeX < 0 ? -1 : 1;
            region.signY = rawSizeY < 0 ? -1 : 1;
            region.signZ = rawSizeZ < 0 ? -1 : 1;
            region.sizeX = Math.abs(rawSizeX);
            region.sizeY = Math.abs(rawSizeY);
            region.sizeZ = Math.abs(rawSizeZ);

            // ── 调色板：Name + Properties，与原版结构方块 NBT 格式一致 ──
            ListTag paletteTag = regionTag.getList("BlockStatePalette", 10);
            List<BlockState> palette = new ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                palette.add(parsePaletteEntry(paletteTag.getCompound(i)));
            }
            if (palette.isEmpty()) palette.add(Blocks.AIR.defaultBlockState());
            region.palette = palette;
            region.bitsPerEntry = LitematicaCodec.bitsPerEntry(palette.size());
            region.states = regionTag.getLongArray("BlockStates");

            // ── 方块实体：区域局部坐标 (x,y,z) → NBT ──
            HashMap<Long, CompoundTag> tileMap = new HashMap<>();
            if (regionTag.contains("TileEntities", 9)) {
                ListTag teList = regionTag.getList("TileEntities", 10);
                for (int i = 0; i < teList.size(); i++) {
                    CompoundTag te = teList.getCompound(i);
                    int tx = te.getInt("x"), ty = te.getInt("y"), tz = te.getInt("z");
                    tileMap.put(LitematicaCodec.packXYZ(tx, ty, tz), te);
                }
            }
            region.tileMap = tileMap;

            regions.add(region);

            globalMinX = Math.min(globalMinX, region.minX());
            globalMinY = Math.min(globalMinY, region.minY());
            globalMinZ = Math.min(globalMinZ, region.minZ());
            globalMaxX = Math.max(globalMaxX, region.maxX());
            globalMaxY = Math.max(globalMaxY, region.maxY());
            globalMaxZ = Math.max(globalMaxZ, region.maxZ());
        }

        if (regions.isEmpty()) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_litematic");
        }

        final int W = globalMaxX - globalMinX + 1;
        final int H = globalMaxY - globalMinY + 1;
        final int L = globalMaxZ - globalMinZ + 1;

        BlueprintData data = new BlueprintData();
        data.name        = name.isEmpty() ? file.getName().replace(".litematic", "") : name;
        data.size        = new BlockPos(W, H, L);
        data.creatorName = playerName;
        data.sourceName  = playerName;
        data.createdAt   = System.currentTimeMillis();
        data.category    = "template";

        Set<String> lateIds = BlueprintConfig.getLatePlaceBlocks();
        int countIgnored = 0, countNormal = 0, countDep = 0, countLate = 0;

        // ── 第二遍：按每个区域的局部坐标展开方块，换算成蓝图整体的相对坐标 ──
        for (Region region : regions) {
            final int sx = region.sizeX, sy = region.sizeY, sz = region.sizeZ;
            final int bits = region.bitsPerEntry;
            final long[] states = region.states;
            final List<BlockState> palette = region.palette;

            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        final int localIndex = (y * sz + z) * sx + x;
                        final int paletteIndex = (int) LitematicaCodec.getPackedValue(states, localIndex, bits);
                        if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                            countIgnored++;
                            continue;
                        }
                        BlockState state = palette.get(paletteIndex);
                        if (state == null || state.isAir()) continue;

                        final int absX = region.posX + x * region.signX;
                        final int absY = region.posY + y * region.signY;
                        final int absZ = region.posZ + z * region.signZ;
                        BlockPos relPos = new BlockPos(
                                absX - globalMinX, absY - globalMinY, absZ - globalMinZ);

                        CompoundTag teNbt = region.tileMap.get(LitematicaCodec.packXYZ(x, y, z));
                        if (teNbt != null) {
                            teNbt = teNbt.copy();
                            teNbt.putInt("x", relPos.getX());
                            teNbt.putInt("y", relPos.getY());
                            teNbt.putInt("z", relPos.getZ());
                        }

                        BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(relPos, state, teNbt);
                        String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

                        if (lateIds.contains(blockKey)) {
                            data.lateBlocks.add(entry);
                            countLate++;
                        } else if (BlueprintData.isDependent(state)) {
                            data.dependentBlocks.add(entry);
                            countDep++;
                        } else {
                            data.normalBlocks.add(entry);
                            countNormal++;
                        }
                    }
                }
            }
        }

        // ── 实体：各区域 Entities 列表，坐标按区域局部理解，与方块同一套换算 ──
        int countEntity = 0;
        for (Region region : regions) {
            countEntity += importRegionEntities(region, data,
                    globalMinX, globalMinY, globalMinZ, W, H, L);
        }

        data.materialList = MaterialList.fromBlueprintData(data);

        SpatialBlueprintMod.LOGGER.info(
                "[LitematicaImport] '{}' 导入完成: {}×{}×{} | regions={} normal={} dep={} late={} entity={} 忽略={}",
                data.name, W, H, L, regions.size(), countNormal, countDep, countLate,
                countEntity, countIgnored);

        return data;
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 导入单个区域的 {@code Entities} 列表。
     *
     * <p>实体坐标按区域局部坐标理解（与该区域 TileEntities 的局部查找一致），先按
     * {@code abs = pos + local * sign} 还原绝对坐标，再换算成蓝图整体相对坐标；
     * 落在包围盒外的条目跳过。只搬运、不过滤 ——
     * 过滤统一交给 {@code BlueprintSanitizer.filterEntitiesInPlace}，
     * 这样上传检测报告里「将被丢弃的实体」一栏才有东西可显示。</p>
     */
    private static int importRegionEntities(Region region, BlueprintData data,
                                            int globalMinX, int globalMinY, int globalMinZ,
                                            int w, int h, int l) {
        if (!region.tag.contains("Entities", 9)) return 0;
        ListTag entities = region.tag.getList("Entities", 10);
        int n = 0;
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag nbt = entities.getCompound(i).copy();
            String type = nbt.getString("id");
            if (type.isEmpty()) continue;
            int lx, ly, lz;
            if (nbt.contains("TileX")) {
                lx = nbt.getInt("TileX");
                ly = nbt.getInt("TileY");
                lz = nbt.getInt("TileZ");
            } else if (nbt.contains("Pos", 9)) {
                ListTag pos = nbt.getList("Pos", 6);
                if (pos.size() < 3) continue;
                lx = (int) Math.floor(pos.getDouble(0));
                ly = (int) Math.floor(pos.getDouble(1));
                lz = (int) Math.floor(pos.getDouble(2));
            } else {
                continue;
            }
            int rx = region.posX + lx * region.signX - globalMinX;
            int ry = region.posY + ly * region.signY - globalMinY;
            int rz = region.posZ + lz * region.signZ - globalMinZ;
            if (rx < 0 || ry < 0 || rz < 0 || rx >= w || ry >= h || rz >= l) continue;
            nbt.remove("UUID");   // 复制出来的实体必须换新 UUID，否则与原实体撞号
            data.entities.add(new BlueprintData.EntityEntry(
                    rx, ry, rz, StructureNbtImporter.readFacing(nbt), type, nbt));
            n++;
        }
        return n;
    }

    /** 调色板条目（Name + 可选 Properties）解析为 BlockState，解析失败时返回空气。 */
    private static BlockState parsePaletteEntry(CompoundTag entry) {
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }
}
