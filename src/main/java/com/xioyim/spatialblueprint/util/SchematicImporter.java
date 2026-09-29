package com.xioyim.spatialblueprint.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Set;
import javax.annotation.Nullable;

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
 * 将 MCEdit / Schematica 格式的 .schematic 文件（gzip 压缩的 NBT）
 * 转换为 {@link BlueprintData}，供 spatial_blueprint mod 直接使用。
 *
 * <p>支持：
 * <ul>
 *   <li>标准 Schematica 格式（Blocks + Data + TileEntities，YZX 顺序）</li>
 *   <li>AddBlocks 扩展（方块 ID 超过 255 的情况）</li>
 *   <li>TileEntity NBT 保留（箱子、熔炉等内容物）</li>
 *   <li>Entities 列表导入（画/展示框等，只搬运、不过滤，过滤交给
 *       {@code BlueprintSanitizer.filterEntitiesInPlace}，与 .nbt 同口径）</li>
 * </ul>
 *
 * <p>方块映射覆盖 1.12 所有常用方块（ID 0–252），未识别方块视为空气并计数输出到日志。
 */
public final class SchematicImporter {

    // ── 遗留方块状态字符串表 ─────────────────────────────────────────────────
    // 索引 = (blockId << 4) | meta，值 = 1.20.1 格式的 "minecraft:name[prop=val]"
    // null 表示未知方块（忽略）
    private static final String[] STATES = buildTable();

    // 懒惰初始化的 BlockState 缓存（注册表加载完成后才能访问 Blocks.*）
    @Nullable
    private static volatile BlockState[] CACHE = null;

    // ── 公共入口 ─────────────────────────────────────────────────────────────


    /**
     * 读取 .schematic 文件并转换为 BlueprintData。
     *
     * @param file       .schematic 文件（gzip-NBT，MCEdit / Schematica 格式）
     * @param name       赋予蓝图的名称，为空时使用文件名
     * @param playerName 创建者名称
     * @return 填充好的 BlueprintData
     * @throws StructureImportException 带上具体失败原因的语言键，供界面显示
     */
    public static BlueprintData readSchematic(File file, String name, String playerName)
            throws StructureImportException {
        if (!file.exists() || !file.isFile()) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.file_missing");
        }

        CompoundTag nbt;
        try (FileInputStream fis = new FileInputStream(file)) {
            nbt = SafeNbtIo.readCompressed(fis);
        } catch (IOException e) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.read_failed", e);
        }

        // ── 检查必要字段 ─────────────────────────────────────────────────────
        if (!nbt.contains("Width") || !nbt.contains("Height") || !nbt.contains("Length")
                || !nbt.contains("Blocks") || !nbt.contains("Data")) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_schematic");
        }

        final int W = nbt.getShort("Width")  & 0xFFFF;
        final int H = nbt.getShort("Height") & 0xFFFF;
        final int L = nbt.getShort("Length") & 0xFFFF;

        final byte[] blockIds = nbt.getByteArray("Blocks");
        final byte[] metaArr  = nbt.getByteArray("Data");
        final int expected    = W * H * L;

        if (blockIds.length != expected) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_schematic");
        }

        // AddBlocks：高位半字节扩展 ID 超过 255
        final byte[] addBlocks = nbt.contains("AddBlocks") ? nbt.getByteArray("AddBlocks") : null;

        // ── 构建 TileEntity 快速查找表 (x,y,z) → CompoundTag ─────────────────
        java.util.HashMap<Long, CompoundTag> tileMap = new java.util.HashMap<>();
        if (nbt.contains("TileEntities")) {
            ListTag teList = nbt.getList("TileEntities", 10 /* TAG_Compound */);
            for (int i = 0; i < teList.size(); i++) {
                CompoundTag te = teList.getCompound(i);
                int tx = te.getInt("x"), ty = te.getInt("y"), tz = te.getInt("z");
                tileMap.put(packXYZ(tx, ty, tz), te);
            }
        }

        // ── 确保 BlockState 缓存就绪（首次调用时初始化）─────────────────────
        ensureCache();

        // ── 构建 BlueprintData ────────────────────────────────────────────────
        BlueprintData data = new BlueprintData();
        data.name        = name.isEmpty() ? file.getName().replace(".schematic", "") : name;
        data.size        = new BlockPos(W, H, L);
        data.creatorName = playerName;
        data.sourceName  = playerName;
        data.createdAt   = System.currentTimeMillis();
        int schTotalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
        data.animDuration = BlueprintConfig.getAutoDuration(schTotalBlocks);
        data.category     = "template";

        Set<String> lateIds = BlueprintConfig.getLatePlaceBlocks();
        int countIgnored = 0, countNormal = 0, countDep = 0, countLate = 0;

        // YZX 顺序：index = (y × L + z) × W + x
        for (int y = 0; y < H; y++) {
            for (int z = 0; z < L; z++) {
                for (int x = 0; x < W; x++) {
                    final int idx  = (y * L + z) * W + x;
                    int blockId    = blockIds[idx] & 0xFF;
                    final int meta = metaArr[idx]  & 0x0F;

                    // AddBlocks 半字节扩展
                    if (addBlocks != null) {
                        final int addIdx    = idx / 2;
                        final int addNibble = (idx % 2 == 0)
                                ? (addBlocks[addIdx] >> 4) & 0x0F
                                : addBlocks[addIdx] & 0x0F;
                        blockId |= (addNibble << 8);
                    }

                    if (blockId == 0) continue; // 空气

                    BlockState state = lookupState(blockId, meta);
                    if (state == null || state.isAir()) {
                        countIgnored++;
                        continue;
                    }

                    BlockPos relPos = new BlockPos(x, y, z);

                    // TileEntity NBT：将绝对坐标改为相对坐标
                    CompoundTag teNbt = tileMap.get(packXYZ(x, y, z));
                    if (teNbt != null) {
                        teNbt = teNbt.copy();
                        teNbt.putInt("x", x);
                        teNbt.putInt("y", y);
                        teNbt.putInt("z", z);
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

        // ── 实体：老格式单盒子、无区域，原点即相对原点，直接落相对坐标 ──
        int countEntity = importEntities(nbt, data, W, H, L);

        data.materialList = MaterialList.fromBlueprintData(data);

        SpatialBlueprintMod.LOGGER.info(
                "[SchematicImport] '{}' 导入完成: {}×{}×{} | normal={} dep={} late={} entity={} 忽略={}",
                data.name, W, H, L, countNormal, countDep, countLate, countEntity, countIgnored);

        return data;
    }

    /**
     * 导入 {@code Entities} 列表。
     *
     * <p>类型取 {@code id}（没有就试 {@code Id}）；坐标优先 {@code TileX/Y/Z}，
     * 没有就 floor({@code Pos})。只搬运、不过滤（见
     * {@link StructureNbtImporter} 的同名方法注释）。</p>
     */
    private static int importEntities(CompoundTag nbt, BlueprintData data, int w, int h, int l) {
        if (!nbt.contains("Entities", 9)) return 0;
        ListTag entities = nbt.getList("Entities", 10);
        int count = 0;
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag e = entities.getCompound(i).copy();
            String type = e.getString("id");
            if (type.isEmpty()) type = e.getString("Id");
            if (type.isEmpty()) continue;
            int tx, ty, tz;
            if (e.contains("TileX")) {
                tx = e.getInt("TileX"); ty = e.getInt("TileY"); tz = e.getInt("TileZ");
            } else if (e.contains("Pos", 9)) {
                ListTag pos = e.getList("Pos", 6);
                if (pos.size() < 3) continue;
                tx = (int) Math.floor(pos.getDouble(0));
                ty = (int) Math.floor(pos.getDouble(1));
                tz = (int) Math.floor(pos.getDouble(2));
            } else {
                continue;
            }
            if (tx < 0 || ty < 0 || tz < 0 || tx >= w || ty >= h || tz >= l) continue;
            e.remove("UUID");
            data.entities.add(new BlueprintData.EntityEntry(
                    tx, ty, tz, StructureNbtImporter.readFacing(e), type, e));
            count++;
        }
        return count;
    }

    // ── 内部：BlockState 缓存 ────────────────────────────────────────────────

    @Nullable
    private static BlockState lookupState(int blockId, int meta) {
        final BlockState[] cache = CACHE;
        if (cache == null) return null;
        final int idx = (blockId << 4) | (meta & 0xF);
        return (idx >= 0 && idx < cache.length) ? cache[idx] : null;
    }

    private static void ensureCache() {
        if (CACHE != null) return;
        synchronized (SchematicImporter.class) {
            if (CACHE != null) return;
            final BlockState[] cache = new BlockState[STATES.length];
            for (int i = 0; i < STATES.length; i++) {
                if (STATES[i] != null) {
                    cache[i] = parseState(STATES[i]);
                }
            }
            CACHE = cache;
        }
    }

    /** 将 "minecraft:name[prop=val,...]" 字符串解析为 BlockState。 */
    private static BlockState parseState(String str) {
        try {
            final int bracket   = str.indexOf('[');
            final String bName  = bracket < 0 ? str : str.substring(0, bracket);
            final CompoundTag nbt = new CompoundTag();
            nbt.putString("Name", bName);

            if (bracket >= 0) {
                final String propsStr = str.substring(bracket + 1, str.length() - 1);
                final CompoundTag props = new CompoundTag();
                for (String kv : propsStr.split(",")) {
                    final int eq = kv.indexOf('=');
                    if (eq > 0) props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
                nbt.put("Properties", props);
            }

            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), nbt);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static long packXYZ(int x, int y, int z) {
        return ((long)(y & 0xFFFF) << 32) | ((long)(z & 0xFFFF) << 16) | (x & 0xFFFF);
    }

    // ── 方块状态映射表（1.12 numeric ID+meta → 1.20.1 state string）─────────

    @SuppressWarnings("UnusedAssignment")
    private static String[] buildTable() {
        // 覆盖 ID 0–255（含 AddBlocks 扩展时最多到 0xFFF，但绝大多数 schematic 不超过 255）
        final String[] t = new String[256 * 16];

        // 颜色名称（meta 0-15，wool/glass/terracotta/concrete/carpet 通用）
        final String[] COLOR = {
            "white","orange","magenta","light_blue","yellow","lime","pink","gray",
            "light_gray","cyan","purple","blue","brown","green","red","black"
        };

        // ── ID 1: Stone 及变种 ───────────────────────────────────────────────
        s(t,1,0,"minecraft:stone");
        s(t,1,1,"minecraft:granite");
        s(t,1,2,"minecraft:polished_granite");
        s(t,1,3,"minecraft:diorite");
        s(t,1,4,"minecraft:polished_diorite");
        s(t,1,5,"minecraft:andesite");
        s(t,1,6,"minecraft:polished_andesite");
        fill(t,1,7,15,"minecraft:stone");

        // ── ID 2: Grass block ────────────────────────────────────────────────
        fill(t,2,0,15,"minecraft:grass_block[snowy=false]");

        // ── ID 3: Dirt ───────────────────────────────────────────────────────
        s(t,3,0,"minecraft:dirt");
        s(t,3,1,"minecraft:coarse_dirt");
        s(t,3,2,"minecraft:podzol[snowy=false]");
        fill(t,3,3,15,"minecraft:dirt");

        // ── ID 4: Cobblestone ────────────────────────────────────────────────
        fill(t,4,0,15,"minecraft:cobblestone");

        // ── ID 5: Wood planks ────────────────────────────────────────────────
        s(t,5,0,"minecraft:oak_planks"); s(t,5,1,"minecraft:spruce_planks");
        s(t,5,2,"minecraft:birch_planks"); s(t,5,3,"minecraft:jungle_planks");
        s(t,5,4,"minecraft:acacia_planks"); s(t,5,5,"minecraft:dark_oak_planks");
        fill(t,5,6,15,"minecraft:oak_planks");

        // ── ID 6: Saplings ───────────────────────────────────────────────────
        String[] sap = {"minecraft:oak_sapling","minecraft:spruce_sapling","minecraft:birch_sapling",
                        "minecraft:jungle_sapling","minecraft:acacia_sapling","minecraft:dark_oak_sapling"};
        for (int i = 0; i < 6; i++) { s(t,6,i,sap[i]+"[stage=0]"); s(t,6,i+8,sap[i]+"[stage=1]"); }

        // ── ID 7: Bedrock ────────────────────────────────────────────────────
        fill(t,7,0,15,"minecraft:bedrock");

        // ── ID 8/9: Water ────────────────────────────────────────────────────
        s(t,8,0,"minecraft:water[level=0]");
        for (int m = 1; m <= 7;  m++) s(t,8,m,"minecraft:water[level="+m+"]");
        for (int m = 8; m <= 15; m++) s(t,8,m,"minecraft:water[level=8]");
        fill(t,9,0,15,"minecraft:water[level=0]");

        // ── ID 10/11: Lava ───────────────────────────────────────────────────
        s(t,10,0,"minecraft:lava[level=0]");
        for (int m = 1; m <= 7;  m++) s(t,10,m,"minecraft:lava[level="+m+"]");
        for (int m = 8; m <= 15; m++) s(t,10,m,"minecraft:lava[level=8]");
        fill(t,11,0,15,"minecraft:lava[level=0]");

        // ── ID 12: Sand ──────────────────────────────────────────────────────
        s(t,12,0,"minecraft:sand"); s(t,12,1,"minecraft:red_sand");
        fill(t,12,2,15,"minecraft:sand");

        // ── ID 13: Gravel ────────────────────────────────────────────────────
        fill(t,13,0,15,"minecraft:gravel");

        // ── ID 14-16: Ores ───────────────────────────────────────────────────
        fill(t,14,0,15,"minecraft:gold_ore");
        fill(t,15,0,15,"minecraft:iron_ore");
        fill(t,16,0,15,"minecraft:coal_ore");

        // ── ID 17: Oak/Spruce/Birch/Jungle Log ───────────────────────────────
        // meta bits 0-1 = wood type, bits 2-3 = axis (0=Y, 4=X, 8=Z, 12=all bark)
        final String[] LOG1  = {"minecraft:oak_log","minecraft:spruce_log","minecraft:birch_log","minecraft:jungle_log"};
        final String[] WOOD1 = {"minecraft:oak_wood","minecraft:spruce_wood","minecraft:birch_wood","minecraft:jungle_wood"};
        for (int i = 0; i < 4; i++) {
            s(t,17,i,    LOG1[i]+"[axis=y]");
            s(t,17,i+4,  LOG1[i]+"[axis=x]");
            s(t,17,i+8,  LOG1[i]+"[axis=z]");
            s(t,17,i+12, WOOD1[i]+"[axis=y]");
        }

        // ── ID 18: Oak/Spruce/Birch/Jungle Leaves ────────────────────────────
        // meta bits 0-1 = leaf type, bit 2 = player-placed (persistent=true), bit 3 = decaying
        final String[] LEAF1 = {"minecraft:oak_leaves","minecraft:spruce_leaves","minecraft:birch_leaves","minecraft:jungle_leaves"};
        for (int i = 0; i < 4; i++) {
            s(t,18,i,    LEAF1[i]+"[persistent=false,distance=7]");
            s(t,18,i+4,  LEAF1[i]+"[persistent=true,distance=1]");
            s(t,18,i+8,  LEAF1[i]+"[persistent=false,distance=7]");
            s(t,18,i+12, LEAF1[i]+"[persistent=true,distance=1]");
        }

        // ── ID 19: Sponge ────────────────────────────────────────────────────
        s(t,19,0,"minecraft:sponge"); s(t,19,1,"minecraft:wet_sponge");

        // ── ID 20: Glass ─────────────────────────────────────────────────────
        fill(t,20,0,15,"minecraft:glass");

        // ── ID 21-22: Lapis ──────────────────────────────────────────────────
        fill(t,21,0,15,"minecraft:lapis_ore");
        fill(t,22,0,15,"minecraft:lapis_block");

        // ── ID 23: Dispenser ─────────────────────────────────────────────────
        final String[] DIR6 = {"down","up","north","south","west","east"};
        for (int f = 0; f < 6; f++) {
            s(t,23,f,  "minecraft:dispenser[facing="+DIR6[f]+",triggered=false]");
            s(t,23,f+8,"minecraft:dispenser[facing="+DIR6[f]+",triggered=true]");
        }
        fill(t,23,6,7,"minecraft:dispenser[facing=north,triggered=false]");

        // ── ID 24: Sandstone ─────────────────────────────────────────────────
        s(t,24,0,"minecraft:sandstone");
        s(t,24,1,"minecraft:chiseled_sandstone");
        s(t,24,2,"minecraft:cut_sandstone");
        fill(t,24,3,15,"minecraft:sandstone");

        // ── ID 25: Note block ────────────────────────────────────────────────
        fill(t,25,0,15,"minecraft:note_block[instrument=harp,note=0,powered=false]");

        // ── ID 26: Bed (white) ───────────────────────────────────────────────
        fill(t,26,0,15,"minecraft:white_bed[facing=south,occupied=false,part=foot]");

        // ── ID 27: Powered rail ──────────────────────────────────────────────
        final String[] RAIL_SHAPE = {"north_south","east_west","ascending_east","ascending_west","ascending_north","ascending_south"};
        for (int i = 0; i < 6; i++) {
            s(t,27,i,  "minecraft:powered_rail[powered=false,shape="+RAIL_SHAPE[i]+",waterlogged=false]");
            s(t,27,i+8,"minecraft:powered_rail[powered=true,shape="+RAIL_SHAPE[i]+",waterlogged=false]");
        }
        fill(t,27,6,7,"minecraft:powered_rail[powered=false,shape=north_south,waterlogged=false]");

        // ── ID 28: Detector rail ─────────────────────────────────────────────
        for (int i = 0; i < 6; i++) {
            s(t,28,i,  "minecraft:detector_rail[powered=false,shape="+RAIL_SHAPE[i]+",waterlogged=false]");
            s(t,28,i+8,"minecraft:detector_rail[powered=true,shape="+RAIL_SHAPE[i]+",waterlogged=false]");
        }
        fill(t,28,6,7,"minecraft:detector_rail[powered=false,shape=north_south,waterlogged=false]");

        // ── ID 29: Sticky piston ─────────────────────────────────────────────
        for (int f = 0; f < 6; f++) {
            s(t,29,f,  "minecraft:sticky_piston[extended=false,facing="+DIR6[f]+"]");
            s(t,29,f+8,"minecraft:sticky_piston[extended=true,facing="+DIR6[f]+"]");
        }
        fill(t,29,6,7,"minecraft:sticky_piston[extended=false,facing=north]");

        // ── ID 30: Cobweb ────────────────────────────────────────────────────
        fill(t,30,0,15,"minecraft:cobweb");

        // ── ID 31: Tall grass / fern ─────────────────────────────────────────
        s(t,31,0,"minecraft:dead_bush");
        s(t,31,1,"minecraft:short_grass");
        s(t,31,2,"minecraft:fern");
        fill(t,31,3,15,"minecraft:short_grass");

        // ── ID 32: Dead bush ─────────────────────────────────────────────────
        fill(t,32,0,15,"minecraft:dead_bush");

        // ── ID 33: Piston ────────────────────────────────────────────────────
        for (int f = 0; f < 6; f++) {
            s(t,33,f,  "minecraft:piston[extended=false,facing="+DIR6[f]+"]");
            s(t,33,f+8,"minecraft:piston[extended=true,facing="+DIR6[f]+"]");
        }
        fill(t,33,6,7,"minecraft:piston[extended=false,facing=north]");

        // ── ID 35: Wool ──────────────────────────────────────────────────────
        for (int i = 0; i < 16; i++) s(t,35,i,"minecraft:"+COLOR[i]+"_wool");

        // ── ID 37: Dandelion ─────────────────────────────────────────────────
        fill(t,37,0,15,"minecraft:dandelion");

        // ── ID 38: Flowers ───────────────────────────────────────────────────
        final String[] FLOWER = {"minecraft:poppy","minecraft:blue_orchid","minecraft:allium",
            "minecraft:azure_bluet","minecraft:red_tulip","minecraft:orange_tulip",
            "minecraft:white_tulip","minecraft:pink_tulip","minecraft:oxeye_daisy"};
        for (int i = 0; i < FLOWER.length; i++) s(t,38,i,FLOWER[i]);
        fill(t,38,9,15,"minecraft:poppy");

        // ── ID 39-40: Mushrooms ──────────────────────────────────────────────
        fill(t,39,0,15,"minecraft:brown_mushroom");
        fill(t,40,0,15,"minecraft:red_mushroom");

        // ── ID 41-42: Metal blocks ───────────────────────────────────────────
        fill(t,41,0,15,"minecraft:gold_block");
        fill(t,42,0,15,"minecraft:iron_block");

        // ── ID 43: Double stone slabs ────────────────────────────────────────
        final String[] SLAB1 = {
            "minecraft:smooth_stone_slab","minecraft:sandstone_slab","minecraft:oak_slab",
            "minecraft:cobblestone_slab","minecraft:brick_slab","minecraft:stone_brick_slab",
            "minecraft:nether_brick_slab","minecraft:quartz_slab"};
        for (int i = 0; i < 8; i++) {
            s(t,43,i,  SLAB1[i]+"[type=double]");
            s(t,43,i+8,SLAB1[i]+"[type=double]");
        }

        // ── ID 44: Stone slabs ───────────────────────────────────────────────
        for (int i = 0; i < 8; i++) {
            s(t,44,i,  SLAB1[i]+"[type=bottom,waterlogged=false]");
            s(t,44,i+8,SLAB1[i]+"[type=top,waterlogged=false]");
        }

        // ── ID 45-49: Stone blocks ───────────────────────────────────────────
        fill(t,45,0,15,"minecraft:bricks");
        fill(t,46,0,15,"minecraft:tnt[unstable=false]");
        fill(t,47,0,15,"minecraft:bookshelf");
        fill(t,48,0,15,"minecraft:mossy_cobblestone");
        fill(t,49,0,15,"minecraft:obsidian");

        // ── ID 50: Torch ─────────────────────────────────────────────────────
        fill(t,50,0,0,"minecraft:torch");
        s(t,50,1,"minecraft:wall_torch[facing=east]");
        s(t,50,2,"minecraft:wall_torch[facing=west]");
        s(t,50,3,"minecraft:wall_torch[facing=south]");
        s(t,50,4,"minecraft:wall_torch[facing=north]");
        s(t,50,5,"minecraft:torch");
        fill(t,50,6,15,"minecraft:torch");

        // ── ID 51: Fire ──────────────────────────────────────────────────────
        for (int m = 0; m <= 15; m++) s(t,51,m,"minecraft:fire[age="+m+"]");

        // ── ID 52: Spawner ───────────────────────────────────────────────────
        fill(t,52,0,15,"minecraft:spawner");

        // ── ID 53: Oak stairs ────────────────────────────────────────────────
        stairsMeta(t,53,"minecraft:oak_stairs");

        // ── ID 54: Chest ─────────────────────────────────────────────────────
        final String[] CHEST_DIR = {null,null,"north","south","west","east"};
        for (int f = 2; f <= 5; f++) s(t,54,f,"minecraft:chest[facing="+CHEST_DIR[f]+",type=single,waterlogged=false]");
        fill(t,54,0,1,"minecraft:chest[facing=north,type=single,waterlogged=false]");
        fill(t,54,6,15,"minecraft:chest[facing=north,type=single,waterlogged=false]");

        // ── ID 55: Redstone wire ─────────────────────────────────────────────
        for (int m = 0; m <= 15; m++) s(t,55,m,"minecraft:redstone_wire[power="+m+"]");

        // ── ID 56-58: Diamond / crafting ─────────────────────────────────────
        fill(t,56,0,15,"minecraft:diamond_ore");
        fill(t,57,0,15,"minecraft:diamond_block");
        fill(t,58,0,15,"minecraft:crafting_table");

        // ── ID 59: Wheat ─────────────────────────────────────────────────────
        for (int m = 0; m <= 7; m++) s(t,59,m,"minecraft:wheat[age="+m+"]");
        fill(t,59,8,15,"minecraft:wheat[age=7]");

        // ── ID 60: Farmland ──────────────────────────────────────────────────
        for (int m = 0; m <= 7; m++) s(t,60,m,"minecraft:farmland[moisture="+m+"]");
        fill(t,60,8,15,"minecraft:farmland[moisture=0]");

        // ── ID 61-62: Furnace ────────────────────────────────────────────────
        final String[] FURN_DIR = {null,null,"north","south","west","east"};
        for (int f = 2; f <= 5; f++) {
            s(t,61,f,"minecraft:furnace[facing="+FURN_DIR[f]+",lit=false]");
            s(t,62,f,"minecraft:furnace[facing="+FURN_DIR[f]+",lit=true]");
        }
        fill(t,61,0,1,"minecraft:furnace[facing=north,lit=false]");
        fill(t,62,0,1,"minecraft:furnace[facing=north,lit=true]");

        // ── ID 63: Standing sign ─────────────────────────────────────────────
        for (int m = 0; m <= 15; m++) s(t,63,m,"minecraft:oak_sign[rotation="+m+",waterlogged=false]");

        // ── ID 64: Oak door ──────────────────────────────────────────────────
        doorMeta(t,64,"minecraft:oak_door");

        // ── ID 65: Ladder ────────────────────────────────────────────────────
        s(t,65,2,"minecraft:ladder[facing=north,waterlogged=false]");
        s(t,65,3,"minecraft:ladder[facing=south,waterlogged=false]");
        s(t,65,4,"minecraft:ladder[facing=west,waterlogged=false]");
        s(t,65,5,"minecraft:ladder[facing=east,waterlogged=false]");
        fill(t,65,0,1,"minecraft:ladder[facing=north,waterlogged=false]");
        fill(t,65,6,15,"minecraft:ladder[facing=north,waterlogged=false]");

        // ── ID 66: Rail ──────────────────────────────────────────────────────
        final String[] RAIL_FULL = {"north_south","east_west","ascending_east","ascending_west",
            "ascending_north","ascending_south","south_east","south_west","north_west","north_east"};
        for (int i = 0; i < 10; i++) s(t,66,i,"minecraft:rail[shape="+RAIL_FULL[i]+",waterlogged=false]");
        fill(t,66,10,15,"minecraft:rail[shape=north_south,waterlogged=false]");

        // ── ID 67: Cobblestone stairs ────────────────────────────────────────
        stairsMeta(t,67,"minecraft:cobblestone_stairs");

        // ── ID 68: Wall sign ─────────────────────────────────────────────────
        s(t,68,2,"minecraft:oak_wall_sign[facing=north,waterlogged=false]");
        s(t,68,3,"minecraft:oak_wall_sign[facing=south,waterlogged=false]");
        s(t,68,4,"minecraft:oak_wall_sign[facing=west,waterlogged=false]");
        s(t,68,5,"minecraft:oak_wall_sign[facing=east,waterlogged=false]");
        fill(t,68,0,1,"minecraft:oak_wall_sign[facing=north,waterlogged=false]");
        fill(t,68,6,15,"minecraft:oak_wall_sign[facing=north,waterlogged=false]");

        // ── ID 69: Lever ─────────────────────────────────────────────────────
        s(t,69,0,"minecraft:lever[face=ceiling,facing=north,powered=false]");
        s(t,69,1,"minecraft:lever[face=wall,facing=east,powered=false]");
        s(t,69,2,"minecraft:lever[face=wall,facing=west,powered=false]");
        s(t,69,3,"minecraft:lever[face=wall,facing=south,powered=false]");
        s(t,69,4,"minecraft:lever[face=wall,facing=north,powered=false]");
        s(t,69,5,"minecraft:lever[face=floor,facing=north,powered=false]");
        s(t,69,6,"minecraft:lever[face=floor,facing=west,powered=false]");
        s(t,69,7,"minecraft:lever[face=ceiling,facing=east,powered=false]");
        for (int m = 8; m <= 15; m++) {
            String base = t[(69 << 4) | (m - 8)];
            if (base != null) s(t,69,m,base.replace("powered=false","powered=true"));
        }

        // ── ID 70-72: Pressure plates ────────────────────────────────────────
        s(t,70,0,"minecraft:stone_pressure_plate[powered=false]");
        s(t,70,1,"minecraft:stone_pressure_plate[powered=true]");
        fill(t,70,2,15,"minecraft:stone_pressure_plate[powered=false]");
        doorMeta(t,71,"minecraft:iron_door");
        s(t,72,0,"minecraft:oak_pressure_plate[powered=false]");
        s(t,72,1,"minecraft:oak_pressure_plate[powered=true]");
        fill(t,72,2,15,"minecraft:oak_pressure_plate[powered=false]");

        // ── ID 73-74: Redstone ore ───────────────────────────────────────────
        fill(t,73,0,15,"minecraft:redstone_ore[lit=false]");
        fill(t,74,0,15,"minecraft:redstone_ore[lit=true]");

        // ── ID 75-76: Redstone torches ───────────────────────────────────────
        fill(t,75,0,0,"minecraft:redstone_torch[lit=false]");
        s(t,75,1,"minecraft:redstone_wall_torch[facing=east,lit=false]");
        s(t,75,2,"minecraft:redstone_wall_torch[facing=west,lit=false]");
        s(t,75,3,"minecraft:redstone_wall_torch[facing=south,lit=false]");
        s(t,75,4,"minecraft:redstone_wall_torch[facing=north,lit=false]");
        s(t,75,5,"minecraft:redstone_torch[lit=false]");
        fill(t,76,0,0,"minecraft:redstone_torch[lit=true]");
        s(t,76,1,"minecraft:redstone_wall_torch[facing=east,lit=true]");
        s(t,76,2,"minecraft:redstone_wall_torch[facing=west,lit=true]");
        s(t,76,3,"minecraft:redstone_wall_torch[facing=south,lit=true]");
        s(t,76,4,"minecraft:redstone_wall_torch[facing=north,lit=true]");
        s(t,76,5,"minecraft:redstone_torch[lit=true]");

        // ── ID 77: Stone button ──────────────────────────────────────────────
        s(t,77,0,"minecraft:stone_button[face=ceiling,facing=north,powered=false]");
        s(t,77,1,"minecraft:stone_button[face=wall,facing=east,powered=false]");
        s(t,77,2,"minecraft:stone_button[face=wall,facing=west,powered=false]");
        s(t,77,3,"minecraft:stone_button[face=wall,facing=south,powered=false]");
        s(t,77,4,"minecraft:stone_button[face=wall,facing=north,powered=false]");
        s(t,77,5,"minecraft:stone_button[face=floor,facing=north,powered=false]");
        s(t,77,6,"minecraft:stone_button[face=floor,facing=west,powered=false]");
        s(t,77,7,"minecraft:stone_button[face=ceiling,facing=east,powered=false]");
        for (int m = 8; m <= 15; m++) {
            String base = t[(77 << 4) | (m - 8)];
            if (base != null) s(t,77,m,base.replace("powered=false","powered=true"));
        }

        // ── ID 78: Snow layers ───────────────────────────────────────────────
        for (int m = 0; m <= 7; m++) s(t,78,m,"minecraft:snow[layers="+(m+1)+"]");
        fill(t,78,8,15,"minecraft:snow[layers=8]");

        // ── ID 79-80: Ice / Snow block ───────────────────────────────────────
        fill(t,79,0,15,"minecraft:ice");
        fill(t,80,0,15,"minecraft:snow_block");

        // ── ID 81: Cactus ────────────────────────────────────────────────────
        for (int m = 0; m <= 15; m++) s(t,81,m,"minecraft:cactus[age="+m+"]");

        // ── ID 82-83: Clay / Sugar cane ──────────────────────────────────────
        fill(t,82,0,15,"minecraft:clay");
        for (int m = 0; m <= 15; m++) s(t,83,m,"minecraft:sugar_cane[age="+m+"]");

        // ── ID 84-86: Jukebox / Fence / Pumpkin ─────────────────────────────
        fill(t,84,0,15,"minecraft:jukebox[has_record=false]");
        fill(t,85,0,15,"minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        fill(t,86,0,15,"minecraft:pumpkin");

        // ── ID 87-89: Nether blocks ──────────────────────────────────────────
        fill(t,87,0,15,"minecraft:netherrack");
        fill(t,88,0,15,"minecraft:soul_sand");
        fill(t,89,0,15,"minecraft:glowstone");

        // ── ID 90: Nether portal ─────────────────────────────────────────────
        s(t,90,1,"minecraft:nether_portal[axis=x]");
        s(t,90,2,"minecraft:nether_portal[axis=z]");
        fill(t,90,0,0,"minecraft:nether_portal[axis=x]");
        fill(t,90,3,15,"minecraft:nether_portal[axis=x]");

        // ── ID 91: Jack o'lantern ────────────────────────────────────────────
        s(t,91,0,"minecraft:jack_o_lantern[facing=south]");
        s(t,91,1,"minecraft:jack_o_lantern[facing=west]");
        s(t,91,2,"minecraft:jack_o_lantern[facing=north]");
        s(t,91,3,"minecraft:jack_o_lantern[facing=east]");
        fill(t,91,4,15,"minecraft:jack_o_lantern[facing=south]");

        // ── ID 92: Cake ──────────────────────────────────────────────────────
        for (int m = 0; m <= 6; m++) s(t,92,m,"minecraft:cake[bites="+m+"]");
        fill(t,92,7,15,"minecraft:cake[bites=6]");

        // ── ID 93-94: Repeater ───────────────────────────────────────────────
        repeaterMeta(t,93,false);
        repeaterMeta(t,94,true);

        // ── ID 95: Stained glass ─────────────────────────────────────────────
        for (int i = 0; i < 16; i++) s(t,95,i,"minecraft:"+COLOR[i]+"_stained_glass");

        // ── ID 96: Oak trapdoor ──────────────────────────────────────────────
        trapdoorMeta(t,96,"minecraft:oak_trapdoor");

        // ── ID 97: Infested stone ────────────────────────────────────────────
        s(t,97,0,"minecraft:infested_stone"); s(t,97,1,"minecraft:infested_cobblestone");
        s(t,97,2,"minecraft:infested_stone_bricks"); s(t,97,3,"minecraft:infested_mossy_stone_bricks");
        s(t,97,4,"minecraft:infested_cracked_stone_bricks"); s(t,97,5,"minecraft:infested_chiseled_stone_bricks");
        fill(t,97,6,15,"minecraft:infested_stone");

        // ── ID 98: Stone bricks ──────────────────────────────────────────────
        s(t,98,0,"minecraft:stone_bricks"); s(t,98,1,"minecraft:mossy_stone_bricks");
        s(t,98,2,"minecraft:cracked_stone_bricks"); s(t,98,3,"minecraft:chiseled_stone_bricks");
        fill(t,98,4,15,"minecraft:stone_bricks");

        // ── ID 99-100: Mushroom blocks ───────────────────────────────────────
        fill(t,99,0,15,"minecraft:brown_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]");
        s(t,99,0,"minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]");
        fill(t,100,0,15,"minecraft:red_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]");
        s(t,100,0,"minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]");

        // ── ID 101-102: Iron bars / Glass pane ──────────────────────────────
        fill(t,101,0,15,"minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]");
        fill(t,102,0,15,"minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]");

        // ── ID 103-105: Melon / stems ────────────────────────────────────────
        fill(t,103,0,15,"minecraft:melon");
        for (int m = 0; m <= 7; m++) s(t,104,m,"minecraft:pumpkin_stem[age="+m+"]");
        fill(t,104,8,15,"minecraft:attached_pumpkin_stem[facing=south]");
        for (int m = 0; m <= 7; m++) s(t,105,m,"minecraft:melon_stem[age="+m+"]");
        fill(t,105,8,15,"minecraft:attached_melon_stem[facing=south]");

        // ── ID 106: Vine ─────────────────────────────────────────────────────
        for (int m = 0; m < 16; m++) {
            boolean vs=(m&1)!=0,vw=(m&2)!=0,vn=(m&4)!=0,ve=(m&8)!=0;
            s(t,106,m,"minecraft:vine[east="+ve+",north="+vn+",south="+vs+",up=false,west="+vw+"]");
        }

        // ── ID 107: Oak fence gate ───────────────────────────────────────────
        fenceGateMeta(t,107,"minecraft:oak_fence_gate");

        // ── ID 108-109: Stairs ───────────────────────────────────────────────
        stairsMeta(t,108,"minecraft:brick_stairs");
        stairsMeta(t,109,"minecraft:stone_brick_stairs");

        // ── ID 110-111: Mycelium / Lily pad ─────────────────────────────────
        fill(t,110,0,15,"minecraft:mycelium[snowy=false]");
        fill(t,111,0,15,"minecraft:lily_pad");

        // ── ID 112-115: Nether blocks ────────────────────────────────────────
        fill(t,112,0,15,"minecraft:nether_bricks");
        fill(t,113,0,15,"minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        stairsMeta(t,114,"minecraft:nether_brick_stairs");
        for (int m = 0; m <= 3; m++) s(t,115,m,"minecraft:nether_wart[age="+m+"]");
        fill(t,115,4,15,"minecraft:nether_wart[age=3]");

        // ── ID 116-118: Enchanting / Brewing / Cauldron ──────────────────────
        fill(t,116,0,15,"minecraft:enchanting_table");
        fill(t,117,0,15,"minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]");
        s(t,118,0,"minecraft:cauldron");
        for (int m = 1; m <= 3; m++) s(t,118,m,"minecraft:water_cauldron[level="+m+"]");
        fill(t,118,4,15,"minecraft:cauldron");

        // ── ID 119-122: End blocks ───────────────────────────────────────────
        fill(t,119,0,15,"minecraft:end_portal");
        fill(t,120,0,3,"minecraft:end_portal_frame[eye=false,facing=south]");
        s(t,120,0,"minecraft:end_portal_frame[eye=false,facing=south]");
        s(t,120,1,"minecraft:end_portal_frame[eye=false,facing=west]");
        s(t,120,2,"minecraft:end_portal_frame[eye=false,facing=north]");
        s(t,120,3,"minecraft:end_portal_frame[eye=false,facing=east]");
        s(t,120,4,"minecraft:end_portal_frame[eye=true,facing=south]");
        s(t,120,5,"minecraft:end_portal_frame[eye=true,facing=west]");
        s(t,120,6,"minecraft:end_portal_frame[eye=true,facing=north]");
        s(t,120,7,"minecraft:end_portal_frame[eye=true,facing=east]");
        fill(t,121,0,15,"minecraft:end_stone");
        fill(t,122,0,15,"minecraft:dragon_egg");

        // ── ID 123-124: Redstone lamp ────────────────────────────────────────
        fill(t,123,0,15,"minecraft:redstone_lamp[lit=false]");
        fill(t,124,0,15,"minecraft:redstone_lamp[lit=true]");

        // ── ID 125-126: Double/single wood slab ─────────────────────────────
        final String[] WSLAB = {"oak","spruce","birch","jungle","acacia","dark_oak"};
        for (int i = 0; i < 6; i++) {
            s(t,125,i,  "minecraft:"+WSLAB[i]+"_slab[type=double,waterlogged=false]");
            s(t,125,i+8,"minecraft:"+WSLAB[i]+"_slab[type=double,waterlogged=false]");
            s(t,126,i,  "minecraft:"+WSLAB[i]+"_slab[type=bottom,waterlogged=false]");
            s(t,126,i+8,"minecraft:"+WSLAB[i]+"_slab[type=top,waterlogged=false]");
        }
        fill(t,125,6,7,"minecraft:oak_slab[type=double,waterlogged=false]");
        fill(t,126,6,7,"minecraft:oak_slab[type=bottom,waterlogged=false]");

        // ── ID 127: Cocoa ────────────────────────────────────────────────────
        final String[] COCO_DIR = {"south","west","north","east"};
        for (int age = 0; age <= 2; age++) {
            for (int d = 0; d < 4; d++) {
                s(t,127,(age<<2)|d,"minecraft:cocoa[age="+age+",facing="+COCO_DIR[d]+"]");
            }
        }

        // ── ID 128-129: Sandstone stairs / Emerald ore ──────────────────────
        stairsMeta(t,128,"minecraft:sandstone_stairs");
        fill(t,129,0,15,"minecraft:emerald_ore");

        // ── ID 130: Ender chest ──────────────────────────────────────────────
        for (int f = 2; f <= 5; f++) s(t,130,f,"minecraft:ender_chest[facing="+CHEST_DIR[f]+",waterlogged=false]");
        fill(t,130,0,1,"minecraft:ender_chest[facing=north,waterlogged=false]");
        fill(t,130,6,15,"minecraft:ender_chest[facing=north,waterlogged=false]");

        // ── ID 131-133: Tripwire hook / Wire / Emerald block ─────────────────
        final String[] TH_DIR = {"south","west","north","east"};
        for (int f = 0; f < 4; f++) s(t,131,f,"minecraft:tripwire_hook[attached=false,facing="+TH_DIR[f]+",powered=false]");
        fill(t,131,4,15,"minecraft:tripwire_hook[attached=false,facing=south,powered=false]");
        fill(t,132,0,15,"minecraft:tripwire[attached=false,disarmed=false,east=false,north=false,powered=false,south=false,west=false]");
        fill(t,133,0,15,"minecraft:emerald_block");

        // ── ID 134-136: Wood stairs ──────────────────────────────────────────
        stairsMeta(t,134,"minecraft:spruce_stairs");
        stairsMeta(t,135,"minecraft:birch_stairs");
        stairsMeta(t,136,"minecraft:jungle_stairs");

        // ── ID 137-138: Command block / Beacon ──────────────────────────────
        fill(t,137,0,15,"minecraft:command_block[conditional=false,facing=north]");
        fill(t,138,0,15,"minecraft:beacon");

        // ── ID 139: Cobblestone wall ─────────────────────────────────────────
        s(t,139,0,"minecraft:cobblestone_wall");
        s(t,139,1,"minecraft:mossy_cobblestone_wall");
        fill(t,139,2,15,"minecraft:cobblestone_wall");

        // ── ID 140: Flower pot ───────────────────────────────────────────────
        fill(t,140,0,15,"minecraft:flower_pot");

        // ── ID 141-142: Crops ────────────────────────────────────────────────
        for (int m = 0; m <= 7; m++) s(t,141,m,"minecraft:carrots[age="+m+"]");
        fill(t,141,8,15,"minecraft:carrots[age=7]");
        for (int m = 0; m <= 7; m++) s(t,142,m,"minecraft:potatoes[age="+m+"]");
        fill(t,142,8,15,"minecraft:potatoes[age=7]");

        // ── ID 143: Oak button ───────────────────────────────────────────────
        s(t,143,0,"minecraft:oak_button[face=ceiling,facing=north,powered=false]");
        s(t,143,1,"minecraft:oak_button[face=wall,facing=east,powered=false]");
        s(t,143,2,"minecraft:oak_button[face=wall,facing=west,powered=false]");
        s(t,143,3,"minecraft:oak_button[face=wall,facing=south,powered=false]");
        s(t,143,4,"minecraft:oak_button[face=wall,facing=north,powered=false]");
        s(t,143,5,"minecraft:oak_button[face=floor,facing=north,powered=false]");
        s(t,143,6,"minecraft:oak_button[face=floor,facing=west,powered=false]");
        s(t,143,7,"minecraft:oak_button[face=ceiling,facing=east,powered=false]");
        for (int m = 8; m <= 15; m++) {
            String base = t[(143 << 4) | (m - 8)];
            if (base != null) s(t,143,m,base.replace("powered=false","powered=true"));
        }

        // ── ID 144: Skull ────────────────────────────────────────────────────
        fill(t,144,0,15,"minecraft:skeleton_skull[powered=false]");

        // ── ID 145: Anvil ────────────────────────────────────────────────────
        final String[] ANV_DIR = {"south","west","north","east"};
        for (int f = 0; f < 4; f++) {
            s(t,145,f,  "minecraft:anvil[facing="+ANV_DIR[f]+"]");
            s(t,145,f+4,"minecraft:chipped_anvil[facing="+ANV_DIR[f]+"]");
            s(t,145,f+8,"minecraft:damaged_anvil[facing="+ANV_DIR[f]+"]");
        }
        fill(t,145,12,15,"minecraft:anvil[facing=south]");

        // ── ID 146: Trapped chest ────────────────────────────────────────────
        for (int f = 2; f <= 5; f++) s(t,146,f,"minecraft:trapped_chest[facing="+CHEST_DIR[f]+",type=single,waterlogged=false]");
        fill(t,146,0,1,"minecraft:trapped_chest[facing=north,type=single,waterlogged=false]");
        fill(t,146,6,15,"minecraft:trapped_chest[facing=north,type=single,waterlogged=false]");

        // ── ID 147-148: Weighted pressure plates ────────────────────────────
        for (int m = 0; m <= 15; m++) {
            s(t,147,m,"minecraft:light_weighted_pressure_plate[power="+m+"]");
            s(t,148,m,"minecraft:heavy_weighted_pressure_plate[power="+m+"]");
        }

        // ── ID 149-150: Comparator ───────────────────────────────────────────
        comparatorMeta(t,149,false);
        comparatorMeta(t,150,true);

        // ── ID 151-152: Daylight detector / Redstone block ───────────────────
        for (int m = 0; m <= 15; m++) s(t,151,m,"minecraft:daylight_detector[inverted=false,power="+m+"]");
        fill(t,152,0,15,"minecraft:redstone_block");

        // ── ID 153-154: Nether quartz ore / Hopper ───────────────────────────
        fill(t,153,0,15,"minecraft:nether_quartz_ore");
        s(t,154,0,"minecraft:hopper[enabled=true,facing=down]");
        s(t,154,2,"minecraft:hopper[enabled=true,facing=north]");
        s(t,154,3,"minecraft:hopper[enabled=true,facing=south]");
        s(t,154,4,"minecraft:hopper[enabled=true,facing=west]");
        s(t,154,5,"minecraft:hopper[enabled=true,facing=east]");
        s(t,154,8,"minecraft:hopper[enabled=false,facing=down]");
        s(t,154,10,"minecraft:hopper[enabled=false,facing=north]");
        s(t,154,11,"minecraft:hopper[enabled=false,facing=south]");
        s(t,154,12,"minecraft:hopper[enabled=false,facing=west]");
        s(t,154,13,"minecraft:hopper[enabled=false,facing=east]");
        fill(t,154,1,1,"minecraft:hopper[enabled=true,facing=down]");
        fill(t,154,6,7,"minecraft:hopper[enabled=true,facing=down]");
        fill(t,154,9,9,"minecraft:hopper[enabled=false,facing=down]");

        // ── ID 155: Quartz ───────────────────────────────────────────────────
        s(t,155,0,"minecraft:quartz_block");
        s(t,155,1,"minecraft:chiseled_quartz_block");
        s(t,155,2,"minecraft:quartz_pillar[axis=y]");
        s(t,155,3,"minecraft:quartz_pillar[axis=x]");
        s(t,155,4,"minecraft:quartz_pillar[axis=z]");
        fill(t,155,5,15,"minecraft:quartz_block");

        // ── ID 156: Quartz stairs ────────────────────────────────────────────
        stairsMeta(t,156,"minecraft:quartz_stairs");

        // ── ID 157: Activator rail ───────────────────────────────────────────
        for (int i = 0; i < 6; i++) {
            s(t,157,i,  "minecraft:activator_rail[powered=false,shape="+RAIL_SHAPE[i]+",waterlogged=false]");
            s(t,157,i+8,"minecraft:activator_rail[powered=true,shape="+RAIL_SHAPE[i]+",waterlogged=false]");
        }
        fill(t,157,6,7,"minecraft:activator_rail[powered=false,shape=north_south,waterlogged=false]");

        // ── ID 158: Dropper ──────────────────────────────────────────────────
        for (int f = 0; f < 6; f++) {
            s(t,158,f,  "minecraft:dropper[facing="+DIR6[f]+",triggered=false]");
            s(t,158,f+8,"minecraft:dropper[facing="+DIR6[f]+",triggered=true]");
        }
        fill(t,158,6,7,"minecraft:dropper[facing=north,triggered=false]");

        // ── ID 159: Stained terracotta ───────────────────────────────────────
        for (int i = 0; i < 16; i++) s(t,159,i,"minecraft:"+COLOR[i]+"_terracotta");

        // ── ID 160: Stained glass pane ───────────────────────────────────────
        for (int i = 0; i < 16; i++)
            s(t,160,i,"minecraft:"+COLOR[i]+"_stained_glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]");

        // ── ID 161: Acacia / Dark oak leaves ─────────────────────────────────
        final String[] LEAF2 = {"minecraft:acacia_leaves","minecraft:dark_oak_leaves"};
        for (int i = 0; i < 2; i++) {
            s(t,161,i,    LEAF2[i]+"[persistent=false,distance=7]");
            s(t,161,i+4,  LEAF2[i]+"[persistent=true,distance=1]");
            s(t,161,i+8,  LEAF2[i]+"[persistent=false,distance=7]");
            s(t,161,i+12, LEAF2[i]+"[persistent=true,distance=1]");
        }
        fill(t,161,2,3,"minecraft:acacia_leaves[persistent=false,distance=7]");

        // ── ID 162: Acacia / Dark oak log ────────────────────────────────────
        final String[] LOG2  = {"minecraft:acacia_log","minecraft:dark_oak_log"};
        final String[] WOOD2 = {"minecraft:acacia_wood","minecraft:dark_oak_wood"};
        for (int i = 0; i < 2; i++) {
            s(t,162,i,    LOG2[i]+"[axis=y]");
            s(t,162,i+4,  LOG2[i]+"[axis=x]");
            s(t,162,i+8,  LOG2[i]+"[axis=z]");
            s(t,162,i+12, WOOD2[i]+"[axis=y]");
        }
        fill(t,162,2,3,"minecraft:acacia_log[axis=y]");

        // ── ID 163-164: Acacia / Dark oak stairs ─────────────────────────────
        stairsMeta(t,163,"minecraft:acacia_stairs");
        stairsMeta(t,164,"minecraft:dark_oak_stairs");

        // ── ID 165-167: Slime / Barrier / Iron trapdoor ──────────────────────
        fill(t,165,0,15,"minecraft:slime_block");
        fill(t,166,0,15,"minecraft:barrier");
        trapdoorMeta(t,167,"minecraft:iron_trapdoor");

        // ── ID 168: Prismarine ───────────────────────────────────────────────
        s(t,168,0,"minecraft:prismarine"); s(t,168,1,"minecraft:dark_prismarine");
        s(t,168,2,"minecraft:prismarine_bricks"); fill(t,168,3,15,"minecraft:prismarine");

        // ── ID 169-170: Sea lantern / Hay bale ───────────────────────────────
        fill(t,169,0,15,"minecraft:sea_lantern");
        s(t,170,0,"minecraft:hay_block[axis=y]"); fill(t,170,1,3,"minecraft:hay_block[axis=y]");
        s(t,170,4,"minecraft:hay_block[axis=x]"); fill(t,170,5,7,"minecraft:hay_block[axis=x]");
        s(t,170,8,"minecraft:hay_block[axis=z]"); fill(t,170,9,15,"minecraft:hay_block[axis=z]");

        // ── ID 171-172: Carpet / Hardened clay ───────────────────────────────
        for (int i = 0; i < 16; i++) s(t,171,i,"minecraft:"+COLOR[i]+"_carpet");
        fill(t,172,0,15,"minecraft:terracotta");

        // ── ID 173-174: Coal block / Packed ice ──────────────────────────────
        fill(t,173,0,15,"minecraft:coal_block");
        fill(t,174,0,15,"minecraft:packed_ice");

        // ── ID 175: Double plants ────────────────────────────────────────────
        final String[] DPLANT = {"minecraft:sunflower","minecraft:lilac","minecraft:tall_grass",
            "minecraft:large_fern","minecraft:rose_bush","minecraft:peony"};
        for (int i = 0; i < 6; i++) {
            s(t,175,i,  DPLANT[i]+"[half=lower]");
            s(t,175,i+8,DPLANT[i]+"[half=upper]");
        }
        fill(t,175,6,7,"minecraft:tall_grass[half=lower]");
        fill(t,175,14,15,"minecraft:tall_grass[half=upper]");

        // ── ID 176-177: Banners ──────────────────────────────────────────────
        for (int m = 0; m <= 15; m++) s(t,176,m,"minecraft:white_banner[rotation="+m+"]");
        s(t,177,2,"minecraft:white_wall_banner[facing=north]");
        s(t,177,3,"minecraft:white_wall_banner[facing=south]");
        s(t,177,4,"minecraft:white_wall_banner[facing=west]");
        s(t,177,5,"minecraft:white_wall_banner[facing=east]");
        fill(t,177,0,1,"minecraft:white_wall_banner[facing=north]");
        fill(t,177,6,15,"minecraft:white_wall_banner[facing=north]");

        // ── ID 178: Inverted daylight detector ───────────────────────────────
        for (int m = 0; m <= 15; m++) s(t,178,m,"minecraft:daylight_detector[inverted=true,power="+m+"]");

        // ── ID 179-182: Red sandstone ────────────────────────────────────────
        s(t,179,0,"minecraft:red_sandstone"); s(t,179,1,"minecraft:chiseled_red_sandstone");
        s(t,179,2,"minecraft:cut_red_sandstone"); fill(t,179,3,15,"minecraft:red_sandstone");
        stairsMeta(t,180,"minecraft:red_sandstone_stairs");
        fill(t,181,0,15,"minecraft:red_sandstone_slab[type=double,waterlogged=false]");
        for (int m = 0; m < 8;  m++) s(t,182,m,"minecraft:red_sandstone_slab[type=bottom,waterlogged=false]");
        for (int m = 8; m < 16; m++) s(t,182,m,"minecraft:red_sandstone_slab[type=top,waterlogged=false]");

        // ── ID 183-187: Fence gates ──────────────────────────────────────────
        fenceGateMeta(t,183,"minecraft:spruce_fence_gate");
        fenceGateMeta(t,184,"minecraft:birch_fence_gate");
        fenceGateMeta(t,185,"minecraft:jungle_fence_gate");
        fenceGateMeta(t,186,"minecraft:dark_oak_fence_gate");
        fenceGateMeta(t,187,"minecraft:acacia_fence_gate");

        // ── ID 188-192: Wood fences ──────────────────────────────────────────
        final String noFence = "[east=false,north=false,south=false,waterlogged=false,west=false]";
        fill(t,188,0,15,"minecraft:spruce_fence"+noFence);
        fill(t,189,0,15,"minecraft:birch_fence"+noFence);
        fill(t,190,0,15,"minecraft:jungle_fence"+noFence);
        fill(t,191,0,15,"minecraft:dark_oak_fence"+noFence);
        fill(t,192,0,15,"minecraft:acacia_fence"+noFence);

        // ── ID 193-197: Wood doors ───────────────────────────────────────────
        doorMeta(t,193,"minecraft:spruce_door");
        doorMeta(t,194,"minecraft:birch_door");
        doorMeta(t,195,"minecraft:jungle_door");
        doorMeta(t,196,"minecraft:acacia_door");
        doorMeta(t,197,"minecraft:dark_oak_door");

        // ── ID 198: End rod ──────────────────────────────────────────────────
        for (int f = 0; f < 6; f++) s(t,198,f,"minecraft:end_rod[facing="+DIR6[f]+"]");
        fill(t,198,6,15,"minecraft:end_rod[facing=up]");

        // ── ID 199-200: Chorus plant / flower ────────────────────────────────
        fill(t,199,0,15,"minecraft:chorus_plant");
        for (int m = 0; m <= 5; m++) s(t,200,m,"minecraft:chorus_flower[age="+m+"]");
        fill(t,200,6,15,"minecraft:chorus_flower[age=5]");

        // ── ID 201-205: Purpur ───────────────────────────────────────────────
        fill(t,201,0,15,"minecraft:purpur_block");
        s(t,202,0,"minecraft:purpur_pillar[axis=y]"); fill(t,202,1,3,"minecraft:purpur_pillar[axis=y]");
        s(t,202,4,"minecraft:purpur_pillar[axis=x]"); fill(t,202,5,7,"minecraft:purpur_pillar[axis=x]");
        s(t,202,8,"minecraft:purpur_pillar[axis=z]"); fill(t,202,9,15,"minecraft:purpur_pillar[axis=z]");
        stairsMeta(t,203,"minecraft:purpur_stairs");
        fill(t,204,0,15,"minecraft:purpur_slab[type=double,waterlogged=false]");
        for (int m = 0; m < 8;  m++) s(t,205,m,"minecraft:purpur_slab[type=bottom,waterlogged=false]");
        for (int m = 8; m < 16; m++) s(t,205,m,"minecraft:purpur_slab[type=top,waterlogged=false]");

        // ── ID 206-208: End stone bricks / Beetroots / Dirt path ─────────────
        fill(t,206,0,15,"minecraft:end_stone_bricks");
        for (int m = 0; m <= 3; m++) s(t,207,m,"minecraft:beetroots[age="+m+"]");
        fill(t,207,4,15,"minecraft:beetroots[age=3]");
        fill(t,208,0,15,"minecraft:dirt_path");

        // ── ID 209-211: End gateway / Command blocks ─────────────────────────
        fill(t,209,0,15,"minecraft:end_gateway");
        fill(t,210,0,15,"minecraft:repeating_command_block[conditional=false,facing=north]");
        fill(t,211,0,15,"minecraft:chain_command_block[conditional=false,facing=north]");

        // ── ID 212-216: Misc ─────────────────────────────────────────────────
        for (int m = 0; m <= 3; m++) s(t,212,m,"minecraft:frosted_ice[age="+m+"]");
        fill(t,212,4,15,"minecraft:frosted_ice[age=3]");
        fill(t,213,0,15,"minecraft:magma_block");
        fill(t,214,0,15,"minecraft:nether_wart_block");
        fill(t,215,0,15,"minecraft:red_nether_bricks");
        s(t,216,0,"minecraft:bone_block[axis=y]"); fill(t,216,1,3,"minecraft:bone_block[axis=y]");
        s(t,216,4,"minecraft:bone_block[axis=x]"); fill(t,216,5,7,"minecraft:bone_block[axis=x]");
        s(t,216,8,"minecraft:bone_block[axis=z]"); fill(t,216,9,15,"minecraft:bone_block[axis=z]");

        // ── ID 217-218: Structure void / Observer ────────────────────────────
        fill(t,217,0,15,"minecraft:structure_void");
        for (int f = 0; f < 6; f++) {
            s(t,218,f,  "minecraft:observer[facing="+DIR6[f]+",powered=false]");
            s(t,218,f+8,"minecraft:observer[facing="+DIR6[f]+",powered=true]");
        }
        fill(t,218,6,7,"minecraft:observer[facing=north,powered=false]");

        // ── ID 219-234: Shulker boxes ────────────────────────────────────────
        for (int i = 0; i < 16; i++) fill(t,219+i,0,15,"minecraft:"+COLOR[i]+"_shulker_box[facing=up]");

        // ── ID 235-250: Glazed terracotta ────────────────────────────────────
        final String[] GT_DIR = {"south","west","north","east"};
        for (int i = 0; i < 16; i++) {
            for (int d = 0; d < 4; d++) s(t,235+i,d,"minecraft:"+COLOR[i]+"_glazed_terracotta[facing="+GT_DIR[d]+"]");
            fill(t,235+i,4,15,"minecraft:"+COLOR[i]+"_glazed_terracotta[facing=south]");
        }

        // ── ID 251-252: Concrete / Powder ────────────────────────────────────
        for (int i = 0; i < 16; i++) {
            s(t,251,i,"minecraft:"+COLOR[i]+"_concrete");
            s(t,252,i,"minecraft:"+COLOR[i]+"_concrete_powder");
        }

        // ── ID 255: Structure block ──────────────────────────────────────────
        fill(t,255,0,15,"minecraft:structure_block[mode=save]");

        return t;
    }

    // ── 映射表辅助方法 ───────────────────────────────────────────────────────

    private static void s(String[] t, int id, int meta, String state) {
        final int idx = (id << 4) | meta;
        if (idx >= 0 && idx < t.length) t[idx] = state;
    }

    private static void fill(String[] t, int id, int from, int to, String state) {
        for (int m = from; m <= to; m++) s(t, id, m, state);
    }

    /** 楼梯：meta 0-3 = 东/西/南/北（底部），meta 4-7 = 同（顶部）。 */
    private static void stairsMeta(String[] t, int id, String name) {
        final String[] F = {"east","west","south","north"};
        for (int i = 0; i < 4; i++) {
            s(t,id,i,  name+"[facing="+F[i]+",half=bottom,shape=straight,waterlogged=false]");
            s(t,id,i+4,name+"[facing="+F[i]+",half=top,shape=straight,waterlogged=false]");
        }
    }

    /** 门：低半部 meta 0-7，高半部 meta 8-15。 */
    private static void doorMeta(String[] t, int id, String name) {
        final String[] F = {"east","south","west","north"};
        for (int f = 0; f < 4; f++) {
            s(t,id,f,  name+"[facing="+F[f]+",half=lower,hinge=left,open=false,powered=false]");
            s(t,id,f+4,name+"[facing="+F[f]+",half=lower,hinge=left,open=true,powered=false]");
        }
        s(t,id, 8, name+"[facing=east,half=upper,hinge=left,open=false,powered=false]");
        s(t,id, 9, name+"[facing=east,half=upper,hinge=right,open=false,powered=false]");
        s(t,id,10, name+"[facing=east,half=upper,hinge=left,open=false,powered=true]");
        s(t,id,11, name+"[facing=east,half=upper,hinge=right,open=false,powered=true]");
        fill(t,id,12,15,name+"[facing=east,half=upper,hinge=left,open=false,powered=false]");
    }

    /** 活板门：meta bits 0-1=facing，bit2=open，bit3=top。 */
    private static void trapdoorMeta(String[] t, int id, String name) {
        final String[] F = {"north","south","west","east"};
        for (int f = 0; f < 4; f++) {
            s(t,id,f,   name+"[facing="+F[f]+",half=bottom,open=false,powered=false,waterlogged=false]");
            s(t,id,f+4, name+"[facing="+F[f]+",half=bottom,open=true,powered=false,waterlogged=false]");
            s(t,id,f+8, name+"[facing="+F[f]+",half=top,open=false,powered=false,waterlogged=false]");
            s(t,id,f+12,name+"[facing="+F[f]+",half=top,open=true,powered=false,waterlogged=false]");
        }
    }

    /** 栅栏门：meta bits 0-1=facing，bit2=open，bit3=powered。 */
    private static void fenceGateMeta(String[] t, int id, String name) {
        final String[] F = {"south","west","north","east"};
        for (int f = 0; f < 4; f++) {
            s(t,id,f,   name+"[facing="+F[f]+",in_wall=false,open=false,powered=false]");
            s(t,id,f+4, name+"[facing="+F[f]+",in_wall=false,open=true,powered=false]");
            s(t,id,f+8, name+"[facing="+F[f]+",in_wall=false,open=false,powered=true]");
            s(t,id,f+12,name+"[facing="+F[f]+",in_wall=false,open=true,powered=true]");
        }
    }

    /** 中继器：meta bits 0-1=facing，bits 2-3=(delay-1)。 */
    private static void repeaterMeta(String[] t, int id, boolean powered) {
        final String[] F = {"south","west","north","east"};
        final String pw = powered ? "true" : "false";
        for (int delay = 1; delay <= 4; delay++) {
            for (int f = 0; f < 4; f++) {
                s(t,id,(delay-1)*4+f,
                  "minecraft:repeater[delay="+delay+",facing="+F[f]+",locked=false,powered="+pw+"]");
            }
        }
    }

    /** 比较器：meta bits 0-1=facing，bit2=subtract，bit3=powered。 */
    private static void comparatorMeta(String[] t, int id, boolean powered) {
        final String[] F = {"south","west","north","east"};
        final String pw = powered ? "true" : "false";
        for (int f = 0; f < 4; f++) {
            s(t,id,f,   "minecraft:comparator[facing="+F[f]+",mode=compare,powered="+pw+"]");
            s(t,id,f+4, "minecraft:comparator[facing="+F[f]+",mode=subtract,powered="+pw+"]");
            s(t,id,f+8, "minecraft:comparator[facing="+F[f]+",mode=compare,powered="+pw+"]");
            s(t,id,f+12,"minecraft:comparator[facing="+F[f]+",mode=subtract,powered="+pw+"]");
        }
    }

    private SchematicImporter() {}
}
