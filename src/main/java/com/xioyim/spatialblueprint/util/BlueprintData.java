package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

public class BlueprintData {
    public final List<BlockEntry> normalBlocks    = new ArrayList<>();
    public final List<BlockEntry> dependentBlocks = new ArrayList<>();
    /** Force-placed last (round 4, after entities): containers with inventory NBT, etc. */
    public final List<BlockEntry> lateBlocks      = new ArrayList<>();
    public final List<EntityEntry> entities = new ArrayList<>();
    public BlockPos size = BlockPos.ZERO;
    public String name = "";
    /**
     * 读入文件的 {@code MinecraftDataVersion}（根标签，缺键记 0）。
     * 只读不写：serialize 写出去的本来就是它。
     */
    public int dataVersion = 0;
    /** 玩家在捕捉时设定的动画时长（秒）。0 表示未设置，使用配置文件默认值。 */
    public int animDuration = 0;
    /**
     * 上传来源：0 = 非上传（世界捕获/工程等）; 1 = 安全上传; 2 = 强制上传。
     * 与 {@link #saveMode}（捕获模式）正交 —— 强制上传的蓝图即使源 saveMode=SAFE
     * 也应享有与 FORCE 捕获相同的「仅 OP」门槛。
     */
    public int uploadMode = 0;
    /**
     * 蓝图分类：{@code "create"}（空间）、{@code "project"}（投影）、
     * {@code "dimensional"}（次元）、{@code "template"}（投影模板）、
     * {@code "engineering"}（工程）。
     */
    public String category = "";
    /**
     * 所需材料清单，内嵌于 litematic 文件顶层 {@code SpatialBlueprintData} 键下。
     * {@code null} 表示尚未生成材料清单。
     */
    @Nullable
    public MaterialList materialList = null;
    /** 捕捉蓝图的玩家名称（空字符串表示旧格式或未知）。 */
    public String creatorName = "";
    /** 蓝图来源玩家（与 creatorName 初始相同；预留给未来"转让来源"场景独立赋值）。 */
    public String sourceName  = "";
    /**
     * 蓝图归属玩家的 UUID，空字符串表示<b>无主</b>（本字段之前捕获的旧蓝图）。
     *
     * <p>次元蓝图的材料存在按 UUID 分表的次元袋里，归属必须用 UUID 判定而非
     * {@link #creatorName} —— 玩家改名后名字就对不上了。无主蓝图任何人都不能使用，
     * 只能由 OP 归档。</p>
     */
    public String ownerUuid   = "";
    /** 捕捉蓝图的时间戳（毫秒，System.currentTimeMillis()），0 表示旧格式或未知。 */
    public long   createdAt   = 0L;
    /** 保存模式（SAFE/SANITIZE/FORCE），捕获时记录。 */
    public com.xioyim.spatialblueprint.util.CaptureMode saveMode
            = com.xioyim.spatialblueprint.util.CaptureMode.SAFE;
    /** OP 玩家跳过危险内容检测后创建的蓝图，放置时记录普通日志。 */
    public boolean opBypassCapture = false;
    /** Short display note (max 15 chars), empty = no note. */
    public String noteShort = "";
    /** Long tooltip note (max 200 chars), empty = no note. */
    public String noteLong = "";

    // ── 次元蓝图「已放置」标记（category == "dimensional" 时有效）──────────────
    // 次元蓝图放置后不再删除 dat 文件，而是打上此标记：
    //   · 蓝图列表中名称追加「(已放置)」，禁止再次取出，防止同一份结构被反复取出复制
    //   · 玩家手上的空置次元蓝图「重新储存」后覆盖写回本文件并清除标记
    /** 是否已放置。 */
    public boolean placed = false;
    /** 放置时玩家所在坐标（与空置次元蓝图的传送点同源）。 */
    public int placedX = 0;
    public int placedY = 0;
    public int placedZ = 0;
    /** 放置时所在维度 ID（如 {@code minecraft:overworld}），空字符串表示未记录。 */
    public String placedDimension = "";

    // ── 工程蓝图专属字段（category == "engineering" 时有效）──────────────────
    /** 工程蓝图固定维度（如 "minecraft:overworld"），空字符串表示未设置。 */
    public String   fixedDimension = "";
    /** 工程蓝图固定起点坐标（捕捉时预览的 min 角），null 表示未设置。 */
    @Nullable public BlockPos fixedStartPos = null;
    /** 工程蓝图固定终点坐标（捕捉时预览的 max 角），null 表示未设置。 */
    @Nullable public BlockPos fixedEndPos   = null;
    /**
     * 创建该工程蓝图时所在存档的 UUID；空串 = 未记录（本机制加入前创建的）。
     *
     * <p>工程蓝图绑定固定坐标与维度，换个存档那些坐标毫无意义 ——
     * 放出来只会砸坏别人的建筑，所以必须能判断归属。</p>
     */
    public String   worldUuid      = "";
    /** 创建时的存档名，仅用于界面显示「所属存档」。 */
    public String   worldName      = "";
    /** 所属项目 ID；空表示这不是任何项目的子工程。非空即豁免存档归属校验。 */
    public String parentProjectId = "";

    /**
     * 该蓝图是否属于给定玩家。
     *
     * <p>无主蓝图（{@link #ownerUuid} 为空的旧数据）对任何人都返回 {@code false} ——
     * 次元蓝图与玩家的次元袋一一绑定，认错主人会让材料空间和蓝图归属彻底错乱，
     * 宁可让旧蓝图作废由 OP 归档。</p>
     */
    public boolean isOwnedBy(java.util.UUID playerUuid) {
        return playerUuid != null && !ownerUuid.isEmpty() && ownerUuid.equals(playerUuid.toString());
    }

    public static class BlockEntry {
        public final BlockPos pos;
        public final BlockState state;
        public final CompoundTag nbt;

        public BlockEntry(BlockPos pos, BlockState state, CompoundTag nbt) {
            this.pos = pos;
            this.state = state;
            this.nbt = nbt;
        }
    }

    /**
     * 装饰实体（画、展示框）捕捉数据。
     * tileX/Y/Z 为相对于捕捉原点（min corner）的方块附着坐标。
     * Painting  : facing = Direction.get2DDataValue()（0=SOUTH 1=WEST 2=NORTH 3=EAST），NBT key "facing"（小写）
     * ItemFrame : facing = Direction.get3DDataValue()（0-5），NBT key "Facing"（大写）
     * nbt 为完整实体 NBT（包含 "id" 字段，不含 UUID）。
     */
    public static class EntityEntry {
        public final int tileX, tileY, tileZ;
        public final byte facing;
        public final String type;
        public final CompoundTag nbt;

        public EntityEntry(int tileX, int tileY, int tileZ, byte facing, String type, CompoundTag nbt) {
            this.tileX = tileX;
            this.tileY = tileY;
            this.tileZ = tileZ;
            this.facing = facing;
            this.type = type;
            this.nbt = nbt;
        }
    }

    /**
     * 序列化为 Litematica {@code .litematic} 兼容的 NBT 结构（单区域，命名固定为 {@code "Main"}，
     * Position 恒为原点，Size 恒为正）。mod 自身专属字段（category/materialList/creatorName 等）
     * 放在顶层 {@code SpatialBlueprintData} 键下，与标准的 {@code Version}/{@code Metadata}/
     * {@code Regions} 平级——真正的 Litematica 读到这个多出来的键会直接忽略，不影响它正常打开
     * 这个文件；反过来我们自己的 {@link #deserialize} 也不依赖 Metadata 之外的任何 Litematica
     * 专属字段，两边各取所需。
     *
     * <p>方块状态去重进调色板（不含 NBT），每个格子（含空气）在 {@code BlockStates} 长整型数组
     * 里占一个位压缩索引，位打包算法见 {@link LitematicaCodec}。方块自带的 NBT（容器/告示牌等）
     * 单独放进 {@code TileEntities} 列表，与调色板分离——这也是 Litematica 自己的做法，
     * 同一个方块状态的多个实例（比如两个朝向相同的箱子）可以共享一个调色板条目。</p>
     */
    public CompoundTag serialize() {
        List<BlockEntry> all = allBlocks();

        final int W = Math.max(size.getX(), 1);
        final int H = Math.max(size.getY(), 1);
        final int L = Math.max(size.getZ(), 1);
        final long totalCells = (long) W * H * L;

        // ── 调色板：方块状态去重，空气固定占索引 0 ──
        BlockState air = Blocks.AIR.defaultBlockState();
        Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        List<BlockState> paletteList = new ArrayList<>();
        paletteIndex.put(air, 0);
        paletteList.add(air);
        for (BlockEntry entry : all) {
            paletteIndex.computeIfAbsent(entry.state, s -> {
                paletteList.add(s);
                return paletteList.size() - 1;
            });
        }

        final int bits = LitematicaCodec.bitsPerEntry(paletteList.size());
        final long[] states = new long[LitematicaCodec.arrayLength((int) totalCells, bits)];

        ListTag tileEntities = new ListTag();
        for (BlockEntry entry : all) {
            int localIndex = (entry.pos.getY() * L + entry.pos.getZ()) * W + entry.pos.getX();
            int paletteIdx = paletteIndex.get(entry.state);
            LitematicaCodec.putPackedValue(states, localIndex, bits, paletteIdx);
            if (entry.nbt != null) {
                CompoundTag te = entry.nbt.copy();
                te.putInt("x", entry.pos.getX());
                te.putInt("y", entry.pos.getY());
                te.putInt("z", entry.pos.getZ());
                tileEntities.add(te);
            }
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : paletteList) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }

        CompoundTag region = new CompoundTag();
        region.put("Position", writeIntTriple(0, 0, 0));
        region.put("Size", writeIntTriple(W, H, L));
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", states);
        if (!tileEntities.isEmpty()) {
            region.put("TileEntities", tileEntities);
        }
        if (!entities.isEmpty()) {
            ListTag entitiesTag = new ListTag();
            for (EntityEntry entry : entities) {
                entitiesTag.add(serializeEntity(entry));
            }
            region.put("Entities", entitiesTag);
        }

        CompoundTag regionsTag = new CompoundTag();
        regionsTag.put("Main", region);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", name);
        metadata.putString("Author", creatorName);
        metadata.putString("Description", "");
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", createdAt);
        metadata.putLong("TimeModified", createdAt);
        metadata.putInt("TotalBlocks", all.size());
        metadata.putInt("TotalVolume", (int) Math.min(totalCells, Integer.MAX_VALUE));
        metadata.put("EnclosingSize", writeIntTriple(W, H, L));

        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        root.put("Metadata", metadata);
        root.put("Regions", regionsTag);
        root.put("SpatialBlueprintData", serializeModData());

        return root;
    }

    /** mod 专属字段，放在自定义的顶层键下（不与标准 litematic 字段混在一起）。 */
    private CompoundTag serializeModData() {
        CompoundTag mod = new CompoundTag();
        if (!category.isEmpty()) mod.putString("category", category);
        if (materialList != null) mod.put("materialList", materialList.serialize());
        if (!creatorName.isEmpty()) mod.putString("creatorName", creatorName);
        if (!sourceName.isEmpty()) mod.putString("sourceName", sourceName);
        if (!ownerUuid.isEmpty()) mod.putString("ownerUuid", ownerUuid);
        if (!worldUuid.isEmpty()) mod.putString("worldUuid", worldUuid);
        if (!worldName.isEmpty()) mod.putString("worldName", worldName);
        if (!parentProjectId.isEmpty()) mod.putString("parentProjectId", parentProjectId);
        if (createdAt > 0L) mod.putLong("createdAt", createdAt);
        if (saveMode != null) mod.putInt("saveMode", saveMode.ordinal());
        if (opBypassCapture) mod.putBoolean("opBypassCapture", true);
        if (!noteShort.isEmpty()) mod.putString("noteShort", noteShort);
        if (!noteLong.isEmpty()) mod.putString("noteLong", noteLong);
        if (placed) {
            mod.putBoolean("placed", true);
            mod.putInt("placedX", placedX);
            mod.putInt("placedY", placedY);
            mod.putInt("placedZ", placedZ);
            if (!placedDimension.isEmpty()) mod.putString("placedDimension", placedDimension);
        }
        if (animDuration > 0) mod.putInt("animDuration", animDuration);
        if (uploadMode > 0) mod.putInt("uploadMode", uploadMode);
        if (!fixedDimension.isEmpty()) mod.putString("fixedDimension", fixedDimension);
        if (fixedStartPos != null) mod.put("fixedStartPos", NbtCompat.writeBlockPos(fixedStartPos));
        if (fixedEndPos != null) mod.put("fixedEndPos", NbtCompat.writeBlockPos(fixedEndPos));
        return mod;
    }

    /**
     * 装饰实体写成标准 litematic Entities 条目：完整实体 NBT（含 id），
     * Pos/TileX-Y-Z/facing 一律改写成相对蓝图原点的坐标——原 NBT 里的这些字段是捕捉时的
     * 世界坐标，早已过期（放置时也是整段覆盖重写，见 BlueprintPlacementHandler），
     * 写自己的格式时干脆生成一份自洽的相对坐标，顺便让文件在真正的 Litematica 里也能正常显示。
     */
    private static CompoundTag serializeEntity(EntityEntry entry) {
        CompoundTag nbt = entry.nbt.copy();
        nbt.remove("UUID");
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(entry.tileX + 0.5));
        pos.add(DoubleTag.valueOf(entry.tileY + 0.5));
        pos.add(DoubleTag.valueOf(entry.tileZ + 0.5));
        nbt.put("Pos", pos);
        nbt.putInt("TileX", entry.tileX);
        nbt.putInt("TileY", entry.tileY);
        nbt.putInt("TileZ", entry.tileZ);
        if ("minecraft:painting".equals(entry.type)) {
            nbt.putByte("facing", entry.facing);
        } else {
            nbt.putByte("Facing", entry.facing);
        }
        return nbt;
    }

    private static CompoundTag writeIntTriple(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        return t;
    }

    public static BlueprintData deserialize(CompoundTag tag) {
        BlueprintData data = new BlueprintData();

        // D2（SBN-011）：按 dataVersion 走原版 DataFixer 升级旧数据；dataVersion 字段仍记原值。
        data.dataVersion = tag.contains("MinecraftDataVersion") ? tag.getInt("MinecraftDataVersion") : 0;
        final int from = LegacyDataFixer.effectiveFrom(data.dataVersion);
        final boolean fix = LegacyDataFixer.needsFix(from);
        final int[] unupgraded = new int[1];

        CompoundTag metadata = tag.getCompound("Metadata");
        data.name = metadata.getString("Name");

        CompoundTag mod = tag.getCompound("SpatialBlueprintData");
        if (mod.contains("category")) data.category = mod.getString("category");
        if (mod.contains("materialList")) data.materialList = MaterialList.deserialize(mod.getCompound("materialList"), from, unupgraded);
        if (mod.contains("creatorName")) data.creatorName = mod.getString("creatorName");
        if (mod.contains("sourceName")) data.sourceName = mod.getString("sourceName");
        if (mod.contains("ownerUuid")) data.ownerUuid = mod.getString("ownerUuid");
        if (mod.contains("worldUuid")) data.worldUuid = mod.getString("worldUuid");
        if (mod.contains("worldName")) data.worldName = mod.getString("worldName");
        data.parentProjectId = mod.contains("parentProjectId")
                ? mod.getString("parentProjectId") : "";
        if (mod.contains("createdAt")) data.createdAt = mod.getLong("createdAt");
        if (mod.contains("saveMode")) data.saveMode = com.xioyim.spatialblueprint.util.CaptureMode.fromOrdinalOrSafe(mod.getInt("saveMode"));
        if (mod.contains("opBypassCapture")) data.opBypassCapture = mod.getBoolean("opBypassCapture");
        if (mod.contains("noteShort")) data.noteShort = mod.getString("noteShort");
        if (mod.contains("noteLong")) data.noteLong = mod.getString("noteLong");
        if (mod.contains("placed")) {
            data.placed          = mod.getBoolean("placed");
            data.placedX         = mod.getInt("placedX");
            data.placedY         = mod.getInt("placedY");
            data.placedZ         = mod.getInt("placedZ");
            data.placedDimension = mod.getString("placedDimension");
        }
        if (mod.contains("animDuration")) data.animDuration = mod.getInt("animDuration");
        if (mod.contains("uploadMode")) data.uploadMode = mod.getInt("uploadMode");
        if (mod.contains("fixedDimension")) data.fixedDimension = mod.getString("fixedDimension");
        if (mod.contains("fixedStartPos")) data.fixedStartPos = NbtCompat.readBlockPos(mod, "fixedStartPos");
        if (mod.contains("fixedEndPos")) data.fixedEndPos = NbtCompat.readBlockPos(mod, "fixedEndPos");

        CompoundTag regionsTag = tag.getCompound("Regions");
        if (regionsTag.isEmpty()) return data; // 空文件，返回空蓝图而不是抛异常

        CompoundTag region = regionsTag.getCompound(regionsTag.getAllKeys().iterator().next());

        CompoundTag posTag  = region.getCompound("Position");
        CompoundTag sizeTag = region.getCompound("Size");
        final int baseX = posTag.getInt("x"), baseY = posTag.getInt("y"), baseZ = posTag.getInt("z");
        final int W = sizeTag.getInt("x"), H = sizeTag.getInt("y"), L = sizeTag.getInt("z");
        data.size = new BlockPos(W, H, L);

        ListTag paletteTag = region.getList("BlockStatePalette", 10);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag stateTag = paletteTag.getCompound(i);
            if (fix) {
                LegacyDataFixer.FixResult r = LegacyDataFixer.tryFixBlockState(stateTag, from);
                if (!r.ok()) unupgraded[0]++;
                stateTag = r.tag();
            }
            palette.add(readPaletteEntry(stateTag));
        }
        if (palette.isEmpty()) palette.add(Blocks.AIR.defaultBlockState());
        final int bits = LitematicaCodec.bitsPerEntry(palette.size());
        final long[] states = region.getLongArray("BlockStates");

        HashMap<Long, CompoundTag> tileMap = new HashMap<>();
        if (region.contains("TileEntities", 9)) {
            ListTag teList = region.getList("TileEntities", 10);
            for (int i = 0; i < teList.size(); i++) {
                CompoundTag te = teList.getCompound(i);
                if (fix) {
                    LegacyDataFixer.FixResult r = LegacyDataFixer.tryFixBlockEntity(te, from);
                    if (!r.ok()) unupgraded[0]++;
                    te = r.tag();
                }
                tileMap.put(LitematicaCodec.packXYZ(te.getInt("x"), te.getInt("y"), te.getInt("z")), te);
            }
        }

        Set<String> lateIds = BlueprintConfig.getLatePlaceBlocks();
        for (int y = 0; y < H; y++) {
            for (int z = 0; z < L; z++) {
                for (int x = 0; x < W; x++) {
                    final int localIndex = (y * L + z) * W + x;
                    final int paletteIdx = (int) LitematicaCodec.getPackedValue(states, localIndex, bits);
                    if (paletteIdx < 0 || paletteIdx >= palette.size()) continue;
                    BlockState state = palette.get(paletteIdx);
                    if (state == null || state.isAir()) continue;

                    BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                    CompoundTag teNbt = tileMap.get(LitematicaCodec.packXYZ(x, y, z));
                    if (teNbt != null) {
                        teNbt = teNbt.copy();
                        teNbt.putInt("x", pos.getX());
                        teNbt.putInt("y", pos.getY());
                        teNbt.putInt("z", pos.getZ());
                    }

                    BlockEntry entry = new BlockEntry(pos, state, teNbt);
                    String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (lateIds.contains(blockKey)) {
                        data.lateBlocks.add(entry);
                    } else if (isDependent(state)) {
                        data.dependentBlocks.add(entry);
                    } else {
                        data.normalBlocks.add(entry);
                    }
                }
            }
        }
        data.dependentBlocks.sort((a, b) -> {
            boolean aDown = isDownwardGrowing(a.state);
            boolean bDown = isDownwardGrowing(b.state);
            if (aDown != bDown) return Boolean.compare(aDown, bDown);
            if (aDown) return Integer.compare(b.pos.getY(), a.pos.getY());
            return Integer.compare(a.pos.getY(), b.pos.getY());
        });

        if (region.contains("Entities", 9)) {
            ListTag entitiesTag = region.getList("Entities", 10);
            for (int i = 0; i < entitiesTag.size(); i++) {
                CompoundTag entityTag = entitiesTag.getCompound(i);
                if (fix) {
                    LegacyDataFixer.FixResult r = LegacyDataFixer.tryFixEntity(entityTag, from);
                    if (!r.ok()) unupgraded[0]++;
                    entityTag = r.tag();
                }
                EntityEntry entry = deserializeEntity(entityTag);
                if (entry != null) data.entities.add(entry);
            }
        }

        if (unupgraded[0] > 0) {
            SpatialBlueprintMod.LOGGER.warn(
                    "[BlueprintData] {} entries could not be upgraded from dataVersion {} (blueprint='{}')",
                    unupgraded[0], data.dataVersion, data.name);
        }

        return data;
    }

    /** 调色板条目（Name + 可选 Properties）解析为 BlockState，解析失败时返回空气。 */
    private static BlockState readPaletteEntry(CompoundTag entry) {
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    @Nullable
    private static EntityEntry deserializeEntity(CompoundTag rawNbt) {
        CompoundTag nbt = rawNbt.copy();
        String type = nbt.getString("id");
        if (type.isBlank()) return null;

        int tileX, tileY, tileZ;
        if (nbt.contains("TileX")) {
            tileX = nbt.getInt("TileX");
            tileY = nbt.getInt("TileY");
            tileZ = nbt.getInt("TileZ");
        } else if (nbt.contains("Pos", 9)) {
            ListTag pos = nbt.getList("Pos", 6);
            if (pos.size() < 3) return null;
            tileX = (int) Math.floor(pos.getDouble(0));
            tileY = (int) Math.floor(pos.getDouble(1));
            tileZ = (int) Math.floor(pos.getDouble(2));
        } else {
            return null;
        }

        byte facing = "minecraft:painting".equals(type) ? nbt.getByte("facing") : nbt.getByte("Facing");
        nbt.remove("UUID");
        return new EntityEntry(tileX, tileY, tileZ, facing, type, nbt);
    }

    /** Returns all blocks (normal + dependent + late) as a single list. */
    public List<BlockEntry> allBlocks() {
        List<BlockEntry> all = new ArrayList<>(normalBlocks.size() + dependentBlocks.size() + lateBlocks.size());
        all.addAll(normalBlocks);
        all.addAll(dependentBlocks);
        all.addAll(lateBlocks);
        return all;
    }

    // ── Block classification ─────────────────────────────────────────────────

    // Caches the isDependent result per concrete Block class (first call does instanceof walk;
    // all subsequent calls are a single HashMap lookup — O(1) vs O(30+) per block).
    // Bounded to MAX_DEPENDENT_CACHE_SIZE entries: in practice the number of distinct Block
    // classes is fixed at startup (≤ a few thousand), but the cap prevents unbounded growth
    // in pathological modpack configurations.
    private static final ConcurrentHashMap<Class<?>, Boolean> DEPENDENT_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_DEPENDENT_CACHE_SIZE = 4096;

    public static boolean isDependent(BlockState state) {
        Block block = state.getBlock();
        Class<?> cls = block.getClass();
        Boolean cached = DEPENDENT_CACHE.get(cls);
        if (cached != null) return cached;
        boolean result = isDependentUncached(block);
        if (DEPENDENT_CACHE.size() < MAX_DEPENDENT_CACHE_SIZE) {
            DEPENDENT_CACHE.put(cls, result);
        }
        return result;
    }

    private static boolean isDependentUncached(Block block) {
        // ── 树叶：必须在树干（原木）之后放置，否则无相邻原木会凋零 ──
        if (block instanceof net.minecraft.world.level.block.LeavesBlock) return true;
        if (block.defaultBlockState().is(net.minecraft.tags.BlockTags.LEAVES)) return true;
        // ── 多格方块 ──
        if (block instanceof DoorBlock) return true;
        if (block instanceof BedBlock) return true;
        if (block instanceof DoublePlantBlock) return true;
        // ── 重力方块 ──
        if (block instanceof FallingBlock) return true;
        if (block instanceof AnvilBlock) return true;
        // ── 依附方块 ──
        if (block instanceof TorchBlock) return true;
        if (block instanceof WallTorchBlock) return true;
        if (block instanceof LanternBlock) return true;
        if (block instanceof BaseRailBlock) return true;
        if (block instanceof RedStoneWireBlock) return true;
        if (block instanceof DiodeBlock) return true;
        if (block instanceof BasePressurePlateBlock) return true;
        if (block instanceof SignBlock) return true;
        if (block instanceof WallSignBlock) return true;
        if (block instanceof CeilingHangingSignBlock) return true;
        if (block instanceof WallHangingSignBlock) return true;
        if (block instanceof BellBlock) return true;
        if (block instanceof LeverBlock) return true;
        if (block instanceof ButtonBlock) return true;
        if (block instanceof TripWireHookBlock) return true;
        if (block instanceof AbstractBannerBlock) return true;
        if (block instanceof SkullBlock) return true;
        if (block instanceof WallSkullBlock) return true;
        if (block instanceof CandleBlock) return true;
        if (block instanceof CandleCakeBlock) return true;
        if (block instanceof AmethystClusterBlock) return true;
        if (block instanceof GlowLichenBlock) return true;
        if (block instanceof SnowLayerBlock) return true;
        if (block instanceof ScaffoldingBlock) return true;
        if (block instanceof TurtleEggBlock) return true;
        if (block instanceof FrogspawnBlock) return true;
        if (block instanceof CarpetBlock) return true;
        if (block instanceof PinkPetalsBlock) return true;
        // ── 滴水石 ──
        if (block instanceof PointedDripstoneBlock) return true;
        // ── 藤蔓类 ──
        if (block instanceof WeepingVinesBlock) return true;
        if (block instanceof WeepingVinesPlantBlock) return true;
        if (block instanceof TwistingVinesBlock) return true;
        if (block instanceof TwistingVinesPlantBlock) return true;
        if (block instanceof CaveVinesBlock) return true;
        if (block instanceof CaveVinesPlantBlock) return true;
        if (block instanceof VineBlock) return true;
        if (block instanceof SporeBlossomBlock) return true;
        if (block instanceof HangingRootsBlock) return true;
        if (block instanceof BigDripleafBlock) return true;
        if (block instanceof BigDripleafStemBlock) return true;
        if (block instanceof ChorusPlantBlock) return true;
        if (block instanceof ChorusFlowerBlock) return true;
        // ── 植物（BushBlock 涵盖：花、草、农作物、蘑菇、树苗、睡莲等）──
        if (block instanceof BushBlock) return true;
        if (block instanceof CactusBlock) return true;
        if (block instanceof SugarCaneBlock) return true;
        if (block instanceof BambooStalkBlock) return true;
        if (block instanceof BambooSaplingBlock) return true;
        if (block instanceof CoralFanBlock) return true;
        return false;
    }

    /** 向下生长的方块需 Y 降序放置（从上往下），确保上半先于下半到位。 */
    public static boolean isDownwardGrowing(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock) return true;
        if (block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) return true;
        if (block instanceof PointedDripstoneBlock
                && state.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
                && state.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN) return true;
        return false;
    }
}
