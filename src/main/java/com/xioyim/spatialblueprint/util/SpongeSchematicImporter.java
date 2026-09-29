package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 将 Sponge Schematic 的 {@code .schem} 文件转换为 {@link BlueprintData}。
 *
 * <p>这是 1.13+ 之后最主流的分享格式 —— WorldEdit 与各建筑站导出的都是它，
 * 玩家从网上下的建筑大概率就是 .schem。</p>
 *
 * <p>难点全在<b>版本分裂</b>上，三个版本的结构不同，必须先读 Version 再分派：</p>
 * <ul>
 *   <li><b>v1 / v2</b>：内容直接在根标签下
 *       （{@code Width}/{@code Height}/{@code Length}/{@code Palette}/{@code BlockData}/{@code BlockEntities}）</li>
 *   <li><b>v3</b>：外面套一层 {@code Schematic}，方块相关挪进 {@code Blocks} 子标签
 *       （{@code Blocks.Palette} / {@code Blocks.Data} / {@code Blocks.BlockEntities}）</li>
 * </ul>
 *
 * <p>与 litematic / 原版 .nbt 的两处关键差异：</p>
 * <ul>
 *   <li>{@code Palette} 的 key 是<b>完整 blockstate 字符串</b>
 *       （{@code "minecraft:oak_stairs[facing=north,half=bottom]"}），
 *       不是 NBT 的 Name+Properties 结构 —— {@code NbtUtils.readBlockState} 用不了，
 *       改用原版的 {@link BlockStateParser}</li>
 *   <li>{@code BlockData} 是 <b>varint 字节流</b>（每个方块 1–5 字节，按 YZX 顺序），
 *       不是长整型位压缩数组 —— {@link LitematicaCodec} 用不上，另写一个 varint 解码</li>
 * </ul>
 */
public final class SpongeSchematicImporter {

    private SpongeSchematicImporter() {}

    /** 读取 .schem 文件并转换为 BlueprintData。 */
    public static BlueprintData readSchem(File file, String name, String playerName)
            throws StructureImportException {
        if (!file.exists() || !file.isFile()) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.file_missing");
        }

        CompoundTag root;
        try (FileInputStream fis = new FileInputStream(file)) {
            root = SafeNbtIo.readCompressed(fis);
        } catch (Exception e) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.read_failed");
        }

        // v3 把一切套进 "Schematic" 里；v1/v2 直接就是根标签
        CompoundTag body = root.contains("Schematic", 10) ? root.getCompound("Schematic") : root;
        int version = body.contains("Version") ? body.getInt("Version") : 1;

        // 方块数据的所在层：v3 在 Blocks 子标签下，v1/v2 与尺寸平级
        CompoundTag blocksTag = body.contains("Blocks", 10) ? body.getCompound("Blocks") : body;

        int width  = readDim(body, "Width");
        int height = readDim(body, "Height");
        int length = readDim(body, "Length");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_schem");
        }

        // ── 调色板：blockstate 字符串 → 索引 ───────────────────────────────
        // v1/v2 是 Palette，v3 也叫 Palette 但在 Blocks 下面；两处都试
        CompoundTag paletteTag = blocksTag.contains("Palette", 10)
                ? blocksTag.getCompound("Palette")
                : body.getCompound("Palette");
        if (paletteTag.isEmpty()) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_schem");
        }

        int maxIndex = 0;
        for (String key : paletteTag.getAllKeys()) {
            maxIndex = Math.max(maxIndex, paletteTag.getInt(key));
        }
        BlockState[] palette = new BlockState[maxIndex + 1];
        int unknownStates = 0;
        for (String key : paletteTag.getAllKeys()) {
            int idx = paletteTag.getInt(key);
            if (idx < 0 || idx >= palette.length) continue;
            BlockState st = parseBlockState(key);
            if (st == null) {
                st = Blocks.AIR.defaultBlockState();
                unknownStates++;
            }
            palette[idx] = st;
        }
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) palette[i] = Blocks.AIR.defaultBlockState();
        }

        // ── 方块数据：v1/v2 叫 BlockData，v3 叫 Data ────────────────────────
        byte[] blockData = blocksTag.contains("Data", Tag.TAG_BYTE_ARRAY)
                ? blocksTag.getByteArray("Data")
                : blocksTag.getByteArray("BlockData");
        if (blockData.length == 0) {
            blockData = body.getByteArray("BlockData");
        }
        if (blockData.length == 0) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_schem");
        }

        // ── 方块实体：先按坐标建索引，遍历时挂上去 ─────────────────────────
        Map<Long, CompoundTag> tileMap = readBlockEntities(blocksTag, body);

        BlueprintData data = new BlueprintData();
        data.name = (name == null || name.isEmpty()) ? file.getName() : name;
        data.size = new BlockPos(width, height, length);

        Set<String> lateIds = BlueprintConfig.getLatePlaceBlocks();

        // ── 遍历 varint 流：按 YZX 顺序，一个方块一个变长整数 ───────────────
        VarIntReader reader = new VarIntReader(blockData);
        int countNormal = 0, countDep = 0, countLate = 0, countIgnored = 0;
        outer:
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    if (!reader.hasNext()) break outer;   // 数据比尺寸短，剩下的当空气
                    int idx = reader.next();
                    if (idx < 0 || idx >= palette.length) { countIgnored++; continue; }
                    BlockState state = palette[idx];
                    if (state.isAir()) continue;

                    BlockPos rel = new BlockPos(x, y, z);
                    CompoundTag teNbt = tileMap.get(packXYZ(x, y, z));
                    if (teNbt != null) {
                        teNbt = teNbt.copy();
                        teNbt.putInt("x", x);
                        teNbt.putInt("y", y);
                        teNbt.putInt("z", z);
                    }

                    BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(rel, state, teNbt);
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

        data.materialList = MaterialList.fromBlueprintData(data);

        int countEntity = importEntities(blocksTag, body, data, width, height, length);

        SpatialBlueprintMod.LOGGER.info(
                "[SchemImport] {} 导入完成: v{} {}x{}x{} | normal={} dep={} late={} entity={} 忽略={} 未识别方块={}",
                data.name, version, width, height, length,
                countNormal, countDep, countLate, countEntity, countIgnored, unknownStates);

        return data;
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    /**
     * 导入 {@code Entities} 列表（v3 在 Blocks 下，v1/v2 在根；都没有就没有实体）。
     *
     * <p>条目可能是 {@code Id + Pos + Data} 三件套（v3 风格），也可能是
     * {@code id + Pos + 数据平铺}（v1/v2 风格）——两层都试。类型没有就跳过；
     * 坐标 floor({@code Pos}) 即相对原点（Sponge 的 Pos 本就是相对原点）。
     * 只搬运、不过滤（见 {@link StructureNbtImporter} 的同名方法注释）。</p>
     */
    private static int importEntities(CompoundTag blocksTag, CompoundTag body,
                                      BlueprintData data, int w, int h, int l) {
        ListTag list = blocksTag.getList("Entities", Tag.TAG_COMPOUND);
        if (list.isEmpty()) list = body.getList("Entities", Tag.TAG_COMPOUND);
        if (list.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            // v3 把实体数据包在 Data 子标签里；v1/v2 平铺
            CompoundTag nbt = entry.contains("Data", 10) ? entry.getCompound("Data").copy() : entry.copy();
            String type = nbt.getString("id");
            if (type.isEmpty()) type = nbt.getString("Id");
            if (type.isEmpty()) type = entry.getString("Id");
            if (type.isEmpty()) continue;
            nbt.putString("id", type);
            nbt.remove("Id");
            ListTag pos = entry.contains("Pos", 9)
                    ? entry.getList("Pos", Tag.TAG_DOUBLE)
                    : nbt.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() < 3) continue;
            int tx = (int) Math.floor(pos.getDouble(0));
            int ty = (int) Math.floor(pos.getDouble(1));
            int tz = (int) Math.floor(pos.getDouble(2));
            if (tx < 0 || ty < 0 || tz < 0 || tx >= w || ty >= h || tz >= l) continue;
            nbt.remove("UUID");   // 复制出来的实体必须换新 UUID，否则与原实体撞号
            // Pos 留着（放置/预览可能要用原始精确坐标），tile 坐标走条目字段
            data.entities.add(new BlueprintData.EntityEntry(
                    tx, ty, tz, StructureNbtImporter.readFacing(nbt), type, nbt));
            n++;
        }
        return n;
    }

    /** 尺寸字段在不同版本里可能是 short 也可能是 int，统一按 int 读。 */
    private static int readDim(CompoundTag tag, String key) {
        if (tag.contains(key, Tag.TAG_SHORT)) return tag.getShort(key) & 0xFFFF;
        return tag.getInt(key);
    }

    /**
     * 解析完整 blockstate 字符串（{@code "minecraft:oak_stairs[facing=north]"}）。
     *
     * <p>解析失败返回 {@code null}，由调用方回落成空气并计数 —— 跨版本的旧方块名
     * 认不出来是常态，不该因此拒收整个文件。</p>
     */
    private static BlockState parseBlockState(String str) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), str, false)
                    .blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** 方块实体列表：v3 在 Blocks.BlockEntities，v1/v2 在根的 BlockEntities（v1 叫 TileEntities）。 */
    private static Map<Long, CompoundTag> readBlockEntities(CompoundTag blocksTag, CompoundTag body) {
        Map<Long, CompoundTag> map = new HashMap<>();
        ListTag list = blocksTag.getList("BlockEntities", Tag.TAG_COMPOUND);
        if (list.isEmpty()) list = body.getList("BlockEntities", Tag.TAG_COMPOUND);
        if (list.isEmpty()) list = body.getList("TileEntities", Tag.TAG_COMPOUND);

        for (int i = 0; i < list.size(); i++) {
            CompoundTag be = list.getCompound(i);
            int[] pos = be.getIntArray("Pos");
            if (pos.length < 3) continue;
            // v3 把方块实体自己的数据放在 Data 子标签下；v1/v2 是平铺在条目里
            CompoundTag payload = be.contains("Data", 10) ? be.getCompound("Data").copy() : be.copy();
            payload.remove("Pos");
            // Sponge 用 Id 记方块实体类型，原版读的是 id
            if (payload.contains("Id") && !payload.contains("id")) {
                payload.putString("id", payload.getString("Id"));
                payload.remove("Id");
            }
            map.put(packXYZ(pos[0], pos[1], pos[2]), payload);
        }
        return map;
    }

    private static long packXYZ(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0x3FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * varint 字节流读取器。
     *
     * <p>Sponge 的 {@code BlockData} 每个方块占 1–5 字节：低 7 位是数据，
     * 最高位为 1 表示还有后续字节。与 Minecraft 网络协议的 VarInt 编码一致。</p>
     */
    private static final class VarIntReader {
        private final byte[] buf;
        private int cursor = 0;

        VarIntReader(byte[] buf) {
            this.buf = buf;
        }

        boolean hasNext() {
            return cursor < buf.length;
        }

        int next() {
            int value = 0;
            int shift = 0;
            while (cursor < buf.length) {
                int b = buf[cursor++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
                if (shift > 35) break;   // 超过 5 字节就是坏数据，别无限读下去
            }
            return value;
        }
    }
}
