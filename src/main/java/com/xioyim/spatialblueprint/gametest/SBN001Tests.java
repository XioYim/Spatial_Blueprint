package com.xioyim.spatialblueprint.gametest;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.config.ConfigRegistry;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintTransform;
import com.xioyim.spatialblueprint.util.LitematicaCodec;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.NbtCompat;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * SBN-001 纯逻辑层 GameTest（g01～g08）。
 * 全部用空模板（{@code data/spatial_blueprint/structure/empty.nbt}），不写世界残留。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN001Tests {

    /** g01：调色板位宽 + 跨 long 边界的位压缩往返。 */
    @GameTest(template = "empty")
    public static void g01_litematicaCodecRoundTrip(GameTestHelper helper) {
        helper.assertTrue(LitematicaCodec.bitsPerEntry(1) == 2, "bits(1)=2");
        helper.assertTrue(LitematicaCodec.bitsPerEntry(2) == 2, "bits(2)=2");
        helper.assertTrue(LitematicaCodec.bitsPerEntry(3) == 2, "bits(3)=2");
        helper.assertTrue(LitematicaCodec.bitsPerEntry(5) == 3, "bits(5)=3");
        helper.assertTrue(LitematicaCodec.bitsPerEntry(17) == 5, "bits(17)=5");

        Random rng = new Random(12345L);
        int n = 200;
        int bits = 5;
        long[] arr = new long[LitematicaCodec.arrayLength(n, bits)];
        long[] vals = new long[n];
        for (int i = 0; i < n; i++) {
            vals[i] = rng.nextInt(32);
            LitematicaCodec.putPackedValue(arr, i, bits, vals[i]);
        }
        for (int i = 0; i < n; i++) {
            helper.assertTrue(LitematicaCodec.getPackedValue(arr, i, bits) == vals[i], "roundtrip " + i);
        }
        helper.succeed();
    }

    /** g02：旋转变换（方块模型连转 4 次回原点；角点模型类注释例子）。 */
    @GameTest(template = "empty")
    public static void g02_transformRotations(GameTestHelper helper) {
        BlockPos size = new BlockPos(3, 1, 2);
        int x = 1;
        int y = 0;
        int z = 1;
        BlockPos cur = size;
        for (int i = 0; i < 4; i++) {
            int nx = BlueprintTransform.x(x, y, z, cur, Rotation.CLOCKWISE_90);
            int nz = BlueprintTransform.z(x, y, z, cur, Rotation.CLOCKWISE_90);
            x = nx;
            z = nz;
            cur = new BlockPos(cur.getZ(), cur.getY(), cur.getX());
        }
        helper.assertTrue(x == 1 && z == 1, "4x90 returns to start coord");
        helper.assertTrue(cur.equals(size), "4x90 restores size");

        BlockPos size3 = new BlockPos(3, 1, 3);
        double cx = BlueprintTransform.cornerX(0.0, 0.0, size3, Rotation.CLOCKWISE_90);
        double cz = BlueprintTransform.cornerZ(0.0, 0.0, size3, Rotation.CLOCKWISE_90);
        helper.assertTrue(Math.abs(cx - 3.0) < 1e-9 && Math.abs(cz - 0.0) < 1e-9, "corner 0.0 CW90 -> 3.0");
        helper.succeed();
    }

    /** g03：BlueprintData 序列化往返（含 normal / dependent / late 归类、实体、版本）。 */
    @GameTest(template = "empty")
    public static void g03_blueprintDataRoundTrip(GameTestHelper helper) {
        List<String> origLate = new ArrayList<>(BlueprintConfig.LATE_PLACE_BLOCKS.get());
        try {
            BlueprintConfig.LATE_PLACE_BLOCKS.set(new ArrayList<>(List.of("minecraft:chest")));
            BlueprintConfig.clearCache();

            BlueprintData data = new BlueprintData();
            data.size = new BlockPos(3, 1, 1);
            data.name = "g03";
            data.category = "create";
            data.creatorName = "t";
            data.sourceName = "s";
            data.ownerUuid = "11111111-2222-3333-4444-555555555555";
            data.createdAt = 123456789L;
            data.opBypassCapture = true;
            data.noteShort = "n";
            data.noteLong = "long note";
            data.placed = true;
            data.placedX = 10;
            data.placedY = 64;
            data.placedZ = -5;
            data.placedDimension = "minecraft:overworld";
            data.animDuration = 9;
            data.uploadMode = 1;
            data.fixedDimension = "minecraft:overworld";
            data.fixedStartPos = new BlockPos(1, 2, 3);
            data.fixedEndPos = new BlockPos(4, 5, 6);
            data.worldUuid = "w";
            data.worldName = "wn";
            data.parentProjectId = "p";
            data.normalBlocks.add(new BlueprintData.BlockEntry(
                    new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), null));
            data.dependentBlocks.add(new BlueprintData.BlockEntry(
                    new BlockPos(1, 0, 0), Blocks.TORCH.defaultBlockState(), null));
            CompoundTag chestNbt = new CompoundTag();
            chestNbt.putString("id", "minecraft:chest");
            data.lateBlocks.add(new BlueprintData.BlockEntry(
                    new BlockPos(2, 0, 0), Blocks.CHEST.defaultBlockState(), chestNbt));
            CompoundTag paintNbt = new CompoundTag();
            paintNbt.putString("id", "minecraft:painting");
            data.entities.add(new BlueprintData.EntityEntry(0, 1, 0, (byte) 2, "minecraft:painting", paintNbt));

            CompoundTag root = data.serialize();
            int version = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            helper.assertTrue(root.getInt("MinecraftDataVersion") == version, "root data version is current");

            BlueprintData back = BlueprintData.deserialize(root);
            helper.assertTrue(back.dataVersion == version, "dataVersion read back");
            helper.assertTrue(back.name.equals("g03"), "name");
            helper.assertTrue(back.category.equals("create"), "category");
            helper.assertTrue(back.ownerUuid.equals("11111111-2222-3333-4444-555555555555"), "ownerUuid");
            helper.assertTrue(back.fixedStartPos.equals(new BlockPos(1, 2, 3)), "fixedStartPos");
            helper.assertTrue(back.fixedEndPos.equals(new BlockPos(4, 5, 6)), "fixedEndPos");
            helper.assertTrue(back.placed && back.placedX == 10 && back.placedY == 64
                    && back.placedZ == -5 && back.placedDimension.equals("minecraft:overworld"), "placed");
            helper.assertTrue(back.normalBlocks.size() == 1
                    && back.normalBlocks.get(0).state.getBlock() == Blocks.STONE, "stone is normal");
            helper.assertTrue(back.dependentBlocks.size() == 1
                    && back.dependentBlocks.get(0).state.getBlock() == Blocks.TORCH, "torch is dependent");
            helper.assertTrue(back.lateBlocks.size() == 1
                    && back.lateBlocks.get(0).state.getBlock() == Blocks.CHEST, "chest is late");
            helper.assertTrue(back.lateBlocks.get(0).nbt != null
                    && back.lateBlocks.get(0).nbt.getString("id").equals("minecraft:chest"), "chest nbt kept");
            helper.assertTrue(back.entities.size() == 1
                    && back.entities.get(0).type.equals("minecraft:painting")
                    && back.entities.get(0).facing == (byte) 2, "painting entity");
            helper.assertTrue(back.materialList == null, "no material list");
        } finally {
            BlueprintConfig.LATE_PLACE_BLOCKS.set(origLate);
            BlueprintConfig.clearCache();
        }
        helper.succeed();
    }

    /** g04：NbtCompat 两种坐标格式互读、缺键 null、写出复合标签。 */
    @GameTest(template = "empty")
    public static void g04_nbtCompatBlockPos(GameTestHelper helper) {
        CompoundTag parent = new CompoundTag();
        CompoundTag xyz = new CompoundTag();
        xyz.putInt("X", 1);
        xyz.putInt("Y", 2);
        xyz.putInt("Z", 3);
        parent.put("a", xyz);
        helper.assertTrue(new BlockPos(1, 2, 3).equals(NbtCompat.readBlockPos(parent, "a")), "compound form");

        CompoundTag parent2 = new CompoundTag();
        parent2.putIntArray("b", new int[]{1, 2, 3});
        helper.assertTrue(new BlockPos(1, 2, 3).equals(NbtCompat.readBlockPos(parent2, "b")), "int-array form");
        helper.assertTrue(NbtCompat.readBlockPos(parent2, "missing") == null, "missing key is null");

        CompoundTag written = NbtCompat.writeBlockPos(new BlockPos(4, 5, 6));
        Tag asTag = written;
        helper.assertTrue(written.getInt("X") == 4 && written.getInt("Y") == 5 && written.getInt("Z") == 6
                && !(asTag instanceof IntArrayTag), "writes {X,Y,Z} compound");
        helper.succeed();
    }

    /** g05：ConfigRegistry 越界守卫（越界拒收、界内生效、原值恢复）。 */
    @GameTest(template = "empty")
    public static void g05_configRangeGuard(GameTestHelper helper) {
        String id = ConfigRegistry.idOf(BlueprintConfig.MAX_VOLUME_SPATIAL);
        int orig = BlueprintConfig.MAX_VOLUME_SPATIAL.get();
        Integer rejected = ConfigRegistry.applyInt(id, 0);
        helper.assertTrue(rejected != null && rejected == orig, "out-of-range rejected");
        helper.assertTrue(BlueprintConfig.MAX_VOLUME_SPATIAL.get() == orig, "config unchanged after reject");
        Integer accepted = ConfigRegistry.applyInt(id, 12345);
        helper.assertTrue(accepted != null && accepted == 12345, "in-range accepted");
        helper.assertTrue(BlueprintConfig.MAX_VOLUME_SPATIAL.get() == 12345, "config changed");
        BlueprintConfig.MAX_VOLUME_SPATIAL.set(orig);
        helper.assertTrue(BlueprintConfig.MAX_VOLUME_SPATIAL.get() == orig, "restored");
        helper.succeed();
    }

    /** g06：默认批量转换（5 格水固定 2 桶；2 格水回落 1:1）。 */
    @GameTest(template = "empty")
    public static void g06_bulkAndMaterialList(GameTestHelper helper) {
        List<String> origBulk = new ArrayList<>(GeneratedSubstitutionsConfig.BULK_CONVERSIONS.get());
        List<String> origSubs = new ArrayList<>(GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS.get());
        try {
            GeneratedSubstitutionsConfig.BULK_CONVERSIONS.set(new ArrayList<>(List.of(
                    "minecraft:water=3|minecraft:water_bucket*2",
                    "minecraft:lava=3|minecraft:lava_bucket*2")));
            GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS.set(
                    new ArrayList<>(BlueprintConfig.DEFAULT_BLOCK_SUBSTITUTIONS));
            GeneratedSubstitutionsConfig.invalidateCache();

            BlueprintData data5 = new BlueprintData();
            data5.size = new BlockPos(5, 1, 1);
            for (int i = 0; i < 5; i++) {
                data5.normalBlocks.add(new BlueprintData.BlockEntry(
                        new BlockPos(i, 0, 0), Blocks.WATER.defaultBlockState(), null));
            }
            MaterialList ml5 = MaterialList.fromBlueprintData(data5);
            helper.assertTrue(Integer.valueOf(2).equals(ml5.blockCounts.get("minecraft:water_bucket")),
                    "5 water -> 2 buckets");
            helper.assertFalse(ml5.blockCounts.containsKey("minecraft:water"), "no raw water key");

            BlueprintData data2 = new BlueprintData();
            data2.size = new BlockPos(2, 1, 1);
            for (int i = 0; i < 2; i++) {
                data2.normalBlocks.add(new BlueprintData.BlockEntry(
                        new BlockPos(i, 0, 0), Blocks.WATER.defaultBlockState(), null));
            }
            MaterialList ml2 = MaterialList.fromBlueprintData(data2);
            helper.assertTrue(Integer.valueOf(2).equals(ml2.blockCounts.get("minecraft:water_bucket")),
                    "2 water -> 1:1 fallback gives 2");
        } finally {
            GeneratedSubstitutionsConfig.BULK_CONVERSIONS.set(origBulk);
            GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS.set(origSubs);
            GeneratedSubstitutionsConfig.invalidateCache();
        }
        helper.succeed();
    }

    /** g07：MaterialKey 规范化（空 tag、忽略键、顺序无关、空潜影盒）。 */
    @GameTest(template = "empty")
    public static void g07_materialKeyNormalize(GameTestHelper helper) {
        helper.assertTrue(MaterialKey.make("minecraft:stone", null).equals("minecraft:stone"), "null tag -> pure id");
        List<String> origIgnored = new ArrayList<>(BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS.get());
        try {
            BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS.set(new ArrayList<>(List.of("RepairCost")));
            CompoundTag repair = new CompoundTag();
            repair.putInt("RepairCost", 5);
            helper.assertTrue(MaterialKey.make("minecraft:diamond_sword", repair).equals("minecraft:diamond_sword"),
                    "RepairCost-only -> pure id");

            CompoundTag t1 = new CompoundTag();
            t1.putInt("a", 1);
            t1.putInt("b", 2);
            CompoundTag t2 = new CompoundTag();
            t2.putInt("b", 2);
            t2.putInt("a", 1);
            helper.assertTrue(MaterialKey.make("minecraft:stone", t1).equals(MaterialKey.make("minecraft:stone", t2)),
                    "insertion order independent");
            helper.assertTrue(MaterialKey.hasTag(MaterialKey.make("minecraft:stone", t1)), "tag suffix present");

            CompoundTag shulker = new CompoundTag();
            CompoundTag bet = new CompoundTag();
            bet.put("Items", new ListTag());
            bet.putString("id", "minecraft:shulker_box");
            shulker.put("BlockEntityTag", bet);
            helper.assertTrue(MaterialKey.make("minecraft:shulker_box", shulker).equals("minecraft:shulker_box"),
                    "empty shulker -> pure id");
        } finally {
            BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS.set(origIgnored);
        }
        helper.succeed();
    }

    /** g08：ProjectData 往返 + 缺 anchor 回 origin。 */
    @GameTest(template = "empty")
    public static void g08_projectDataRoundTrip(GameTestHelper helper) {
        ProjectData d = new ProjectData();
        d.name = "p";
        d.kind = ProjectData.Kind.PUBLIC;
        d.stage = ProjectData.Stage.LAYOUT;
        d.noteShort = "s";
        d.noteLong = "l";
        d.ownerUuid = "11111111-2222-3333-4444-555555555555";
        d.ownerName = "n";
        d.createdAt = 987654321L;
        d.dimension = "minecraft:the_nether";
        d.origin = new BlockPos(10, 20, 30);
        d.size = new BlockPos(5, 6, 7);
        d.anchor = new BlockPos(12, 23, 33);
        d.rotation = Rotation.CLOCKWISE_90;
        d.onSubProjectComplete = "say {player}";
        ProjectData.EntryData e1 = new ProjectData.EntryData(1, "a.litematic", "A",
                new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), 0xFF4FC3F7);
        e1.note = "n1";
        e1.noteLong = "nl1";
        e1.blockCount = 42;
        e1.animDuration = 8;
        e1.creatorName = "c";
        e1.createdAt = 111L;
        e1.metaLoaded = true;
        ProjectData.EntryData e2 = new ProjectData.EntryData(2, "b.litematic", "B",
                new BlockPos(3, 0, 0), new BlockPos(4, 4, 4), 0xFF81C784);
        d.entries.add(e1);
        d.entries.add(e2);

        ProjectData back = ProjectData.deserialize(d.serialize());
        helper.assertTrue(back.name.equals("p"), "name");
        helper.assertTrue(back.kind == ProjectData.Kind.PUBLIC, "kind");
        helper.assertTrue(back.stage == ProjectData.Stage.LAYOUT, "stage");
        helper.assertTrue(back.ownerUuid.equals("11111111-2222-3333-4444-555555555555"), "owner");
        helper.assertTrue(back.dimension.equals("minecraft:the_nether"), "dimension");
        helper.assertTrue(back.origin.equals(new BlockPos(10, 20, 30)), "origin");
        helper.assertTrue(back.size.equals(new BlockPos(5, 6, 7)), "size");
        helper.assertTrue(back.anchor.equals(new BlockPos(12, 23, 33)), "anchor");
        helper.assertTrue(back.rotation == Rotation.CLOCKWISE_90, "rotation");
        helper.assertTrue(back.entries.size() == 2, "two entries");
        ProjectData.EntryData r1 = back.entries.get(0);
        helper.assertTrue(r1.index == 1 && r1.filename.equals("a.litematic") && r1.label.equals("A"), "entry identity");
        helper.assertTrue(r1.relP1.equals(new BlockPos(0, 0, 0)) && r1.relP2.equals(new BlockPos(2, 2, 2)), "rel pos");
        helper.assertTrue(r1.color == 0xFF4FC3F7 && r1.blockCount == 42 && r1.animDuration == 8, "entry meta");
        helper.assertTrue(r1.creatorName.equals("c") && r1.createdAt == 111L && r1.metaLoaded, "entry provenance");

        CompoundTag noAnchor = d.serialize();
        noAnchor.remove("anchor");
        ProjectData back2 = ProjectData.deserialize(noAnchor);
        helper.assertTrue(back2.anchor.equals(back2.origin), "missing anchor -> origin");
        helper.succeed();
    }
}
