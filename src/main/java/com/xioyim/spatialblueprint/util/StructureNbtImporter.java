package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 将原版结构方块的 {@code .nbt}（{@code StructureTemplate} 格式，gzip 压缩）转换为
 * {@link BlueprintData}。
 *
 * <p><b>Create 的蓝图就是这个格式</b> —— 它的 {@code SchematicExport} 直接调
 * {@code StructureTemplate.fillFromWorld()} + {@code NbtIo.writeCompressed()}，
 * 文件里没有任何 Create 私有字段。因此支持 .nbt 等于顺带支持了 Create 蓝图，
 * 而且两个 mod 共用 {@code .minecraft/schematics/}，玩家零配置。</p>
 *
 * <p>结构比 litematic 简单得多：</p>
 * <ul>
 *   <li>{@code palette} 与 litematic <b>同构</b>（{@code Name} + {@code Properties}），
 *       可直接用 {@link NbtUtils#readBlockState}</li>
 *   <li>{@code blocks} 是<b>稀疏列表</b>，一个方块一条记录 —— 不需要 {@link LitematicaCodec}
 *       那套变长位解包，也不存在「包围盒铺满」的内存风险</li>
 *   <li>坐标本来就相对选区最小角，与 {@link BlueprintData} 的语义一致，不用平移换算</li>
 * </ul>
 *
 * <p>这也是四种格式里<b>唯一能把实体导进来</b>的一种：{@code entities} 是标准原版格式
 * （{@code pos} + {@code blockPos} + 完整实体 NBT）。</p>
 */
public final class StructureNbtImporter {

    private StructureNbtImporter() {}

    /**
     * 读取 .nbt 文件并转换为 BlueprintData。
     *
     * @throws StructureImportException 带上具体失败原因的语言键，供界面显示
     */
    public static BlueprintData readStructureNbt(File file, String name, String playerName)
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

        if (!root.contains("size", 9) || !root.contains("palette", 9) || !root.contains("blocks", 9)) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_structure_nbt");
        }

        ListTag sizeTag = root.getList("size", Tag.TAG_INT);
        if (sizeTag.size() < 3) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_structure_nbt");
        }
        int sx = sizeTag.getInt(0), sy = sizeTag.getInt(1), sz = sizeTag.getInt(2);
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new StructureImportException("spatial_blueprint.gui.upload.error.not_structure_nbt");
        }

        // ── 调色板 ────────────────────────────────────────────────────────
        // 跨版本时旧方块名会解析失败（Create 读自己的文件也不走 DataFixer 升级链）。
        // 逐条回落成空气并计数，不因为几个方块认不出来就整份拒收。
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        int unknownStates = 0;
        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag entry = paletteTag.getCompound(i);
            BlockState st;
            try {
                st = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
            } catch (Exception e) {
                st = Blocks.AIR.defaultBlockState();
            }
            if (st == null) st = Blocks.AIR.defaultBlockState();
            if (st.isAir() && !isAirEntry(entry)) unknownStates++;
            palette.add(st);
        }

        BlueprintData data = new BlueprintData();
        data.name = (name == null || name.isEmpty()) ? file.getName() : name;
        data.size = new BlockPos(sx, sy, sz);

        Set<String> lateIds = BlueprintConfig.getLatePlaceBlocks();

        // ── 方块 ──────────────────────────────────────────────────────────
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        int countNormal = 0, countDep = 0, countLate = 0, countIgnored = 0;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag posTag = b.getList("pos", Tag.TAG_INT);
            if (posTag.size() < 3) { countIgnored++; continue; }
            int idx = b.getInt("state");
            if (idx < 0 || idx >= palette.size()) { countIgnored++; continue; }
            BlockState state = palette.get(idx);
            if (state.isAir()) continue;   // 空气不入库（原版本来也不写普通空气）

            BlockPos rel = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));

            CompoundTag teNbt = null;
            if (b.contains("nbt", 10)) {
                teNbt = b.getCompound("nbt").copy();
                // 方块实体 NBT 里的 x/y/z 是世界坐标，必须改写成蓝图内相对坐标
                teNbt.putInt("x", rel.getX());
                teNbt.putInt("y", rel.getY());
                teNbt.putInt("z", rel.getZ());
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

        // ── 实体 ──────────────────────────────────────────────────────────
        int countEntity = importEntities(root, data);

        data.materialList = MaterialList.fromBlueprintData(data);

        SpatialBlueprintMod.LOGGER.info(
                "[StructureNbtImport] {} 导入完成: {}x{}x{} | normal={} dep={} late={} entity={} 忽略={} 未识别方块={}",
                data.name, sx, sy, sz, countNormal, countDep, countLate,
                countEntity, countIgnored, unknownStates);

        return data;
    }

    /**
     * 导入 {@code entities} 列表。
     *
     * <p>只把数据搬进 {@link BlueprintData#entities}，<b>不在这里做白名单过滤</b> ——
     * 过滤统一交给 {@code BlueprintSanitizer.filterEntitiesInPlace}，这样上传检测报告里
     * 「将被丢弃的实体」那一栏才有东西可显示。之前两个 Importer 在解析阶段就把实体全丢了，
     * 那一栏永远是空的。</p>
     */
    private static int importEntities(CompoundTag root, BlueprintData data) {
        if (!root.contains("entities", 9)) return 0;
        ListTag entities = root.getList("entities", Tag.TAG_COMPOUND);
        int n = 0;
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag e = entities.getCompound(i);
            if (!e.contains("nbt", 10)) continue;
            CompoundTag nbt = e.getCompound("nbt").copy();
            nbt.remove("UUID");   // 复制出来的实体必须换新 UUID，否则与原实体撞号
            String type = nbt.getString("id");
            if (type.isEmpty()) continue;

            // blockPos 是相对选区最小角的整数方块坐标，正是我们要的 tile 坐标
            int tx = 0, ty = 0, tz = 0;
            if (e.contains("blockPos", 9)) {
                ListTag bp = e.getList("blockPos", Tag.TAG_INT);
                if (bp.size() >= 3) { tx = bp.getInt(0); ty = bp.getInt(1); tz = bp.getInt(2); }
            }

            data.entities.add(new BlueprintData.EntityEntry(tx, ty, tz, readFacing(nbt), type, nbt));
            n++;
        }
        return n;
    }

    /** 展示框用大写 {@code Facing}（0-5），画用小写 {@code facing}（0-3）；都没有就给 0。 */
    static byte readFacing(CompoundTag nbt) {
        if (nbt.contains("Facing")) return nbt.getByte("Facing");
        if (nbt.contains("facing")) return nbt.getByte("facing");
        return 0;
    }

    /** 该调色板条目本来写的就是空气吗（用来区分「真空气」与「没认出来退化成的空气」）。 */
    private static boolean isAirEntry(CompoundTag entry) {
        String n = entry.getString("Name");
        return n.isEmpty() || n.endsWith("air");
    }
}
