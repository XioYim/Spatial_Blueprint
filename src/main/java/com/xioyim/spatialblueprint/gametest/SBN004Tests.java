package com.xioyim.spatialblueprint.gametest;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.SpatialBlueprintRegistry;
import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.BuildBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintComponents;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SBN-004 数据组件、物品、渲染方块、旧物品迁移 GameTest（t01～t07）。
 * 新格式 NBT 一律由真实 ItemStack / 方块实体存出；旧格式物品 NBT 是唯一允许手写处（它就是 1.20.1 的存档格式）。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN004Tests {

    private static HolderLookup.Provider provider(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    // ── t01：注册 ─────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_registration(GameTestHelper helper) {
        String[] items = {"blank_blueprint", "spatial_blueprint", "build_blueprint", "projection_blueprint",
                "dimensional_blueprint", "void_dimensional_blueprint", "engineering_blueprint", "reward_blueprint",
                "public_project_blueprint", "private_project_blueprint"};
        for (String name : items) {
            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("spatial_blueprint:" + name)),
                    "item registered: " + name);
        }
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse("spatial_blueprint:render_block")),
                "render_block registered");
        helper.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(
                ResourceLocation.parse("spatial_blueprint:render_block")), "render_block BE registered");

        int count = 0;
        for (var entry : SpatialBlueprintComponents.COMPONENTS.getEntries()) {
            DataComponentType<?> type = entry.get();
            helper.assertTrue(type.codec() != null, "codec non-null: " + entry.getId());
            helper.assertTrue(type.streamCodec() != null, "streamCodec non-null: " + entry.getId());
            count++;
        }
        helper.assertValueEqual(39, count, "39 components registered");
        helper.succeed();
    }

    // ── t02 / t03 共用样本 ────────────────────────────────────────────────────

    private static List<ItemStack> sampleStacks() {
        List<ItemStack> out = new ArrayList<>();

        ItemStack spatial = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
        spatial.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "11111111-2222-3333-4444-555555555555");
        SpatialBlueprintItem.setBlueprintName(spatial, "S");
        SpatialBlueprintItem.setSaveMode(spatial, CaptureMode.FORCE);
        SpatialBlueprintItem.setAnimDuration(spatial, 5);
        SpatialBlueprintItem.setRotation(spatial, Rotation.CLOCKWISE_90);
        SpatialBlueprintItem.setXOffset(spatial, 1);
        SpatialBlueprintItem.setYOffset(spatial, 2);
        SpatialBlueprintItem.setZOffset(spatial, 3);
        SpatialBlueprintItem.setAlignModeStr(spatial, "CENTER");
        SpatialBlueprintItem.setLocked(spatial, true);
        SpatialBlueprintItem.setLockedPos(spatial, new BlockPos(1, 2, 3));
        SpatialBlueprintItem.setClearFirst(spatial, true);
        out.add(spatial);

        ItemStack blank = new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get());
        BlankBlueprintItem.setPoint1(blank, new BlockPos(4, 5, 6));
        BlankBlueprintItem.setPoint2(blank, new BlockPos(7, 8, 9));
        BlankBlueprintItem.setBlueprintName(blank, "B");
        out.add(blank);

        ItemStack build = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
        BuildBlueprintItem.setCompletionTime(build, 12345L);
        build.set(SpatialBlueprintComponents.SOURCE_TYPE.get(), "project");
        build.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "BB");
        SpatialBlueprintItem.setSaveMode(build, CaptureMode.SANITIZE);
        out.add(build);

        ItemStack voidDim = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
        VoidDimensionalBlueprintItem.setCaptureRange(voidDim, new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));
        voidDim.set(SpatialBlueprintComponents.TP_X.get(), 1.5);
        voidDim.set(SpatialBlueprintComponents.TP_Y.get(), 64.0);
        voidDim.set(SpatialBlueprintComponents.TP_Z.get(), 2.5);
        voidDim.set(SpatialBlueprintComponents.TP_DIMENSION.get(), "minecraft:overworld");
        voidDim.set(SpatialBlueprintComponents.WORLD_UUID.get(), "w-uuid");
        voidDim.set(SpatialBlueprintComponents.WORLD_NAME.get(), "wn");
        VoidDimensionalBlueprintItem.setCreatorName(voidDim, "c");
        voidDim.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "id-v");
        VoidDimensionalBlueprintItem.setBlueprintName(voidDim, "V");
        out.add(voidDim);

        ItemStack eng = new ItemStack(SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get());
        EngineeringBlueprintItem.setBoundProject(eng, "p1", "PN", "minecraft:overworld",
                new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));
        EngineeringBlueprintItem.setTaskSetId(eng, "ts");
        EngineeringBlueprintItem.setEntryIndex(eng, 3);
        out.add(eng);

        ItemStack project = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
        ProjectBlueprintItem.setProjectId(project, UUID.fromString("11111111-2222-3333-4444-555555555555"));
        ProjectBlueprintItem.setTaskSetId(project, UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        ProjectBlueprintItem.setProjectName(project, "P");
        ProjectBlueprintItem.setStage(project, "LAYOUT");
        ProjectBlueprintItem.setDimension(project, "minecraft:overworld");
        ProjectBlueprintItem.setRangePoint(project, 1, new BlockPos(1, 2, 3));
        ProjectBlueprintItem.setRangePoint(project, 2, new BlockPos(4, 5, 6));
        out.add(project);

        return out;
    }

    private static ItemStack diskRoundTrip(GameTestHelper helper, ItemStack stack, HolderLookup.Provider p) {
        CompoundTag saved = (CompoundTag) stack.save(p);
        Optional<ItemStack> parsed = ItemStack.parse(p, saved);
        helper.assertTrue(parsed.isPresent(), "disk parse ok: " + stack.getItem());
        return parsed.orElseThrow();
    }

    private static ItemStack netRoundTrip(ItemStack stack, GameTestHelper helper) {
        RegistryFriendlyByteBuf buf = RegistryFriendlyByteBuf
                .decorator(helper.getLevel().registryAccess(), ConnectionType.OTHER)
                .apply(Unpooled.buffer());
        try {
            ItemStack.STREAM_CODEC.encode(buf, stack);
            return ItemStack.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    @GameTest(template = "empty")
    public static void t02_diskRoundTrip(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        List<ItemStack> stacks = sampleStacks();
        helper.assertValueEqual(6, stacks.size(), "six root stacks");

        for (ItemStack stack : stacks) {
            ItemStack back = diskRoundTrip(helper, stack, p);
            helper.assertTrue(ItemStack.isSameItemSameComponents(stack, back),
                    "disk roundtrip: " + stack.getItem());
        }

        // 逐类型 getter 断言
        ItemStack spatial = stacks.get(0);
        helper.assertValueEqual("11111111-2222-3333-4444-555555555555", SpatialBlueprintItem.getBlueprintId(spatial), "id");
        helper.assertValueEqual("S", SpatialBlueprintItem.getBlueprintName(spatial), "name");
        helper.assertTrue(SpatialBlueprintItem.getSaveMode(spatial) == CaptureMode.FORCE, "save mode");
        helper.assertValueEqual(5, SpatialBlueprintItem.getAnimDuration(spatial, 99), "anim");
        helper.assertTrue(SpatialBlueprintItem.getRotation(spatial) == Rotation.CLOCKWISE_90, "rotation");
        helper.assertValueEqual(1, SpatialBlueprintItem.getXOffset(spatial), "xOffset");
        helper.assertValueEqual("CENTER", SpatialBlueprintItem.getAlignModeStr(spatial), "align");
        helper.assertTrue(SpatialBlueprintItem.isLocked(spatial), "locked");
        helper.assertTrue(new BlockPos(1, 2, 3).equals(SpatialBlueprintItem.getLockedPos(spatial)), "lockedPos");
        helper.assertTrue(SpatialBlueprintItem.getClearFirst(spatial), "clearFirst");

        ItemStack blank = stacks.get(1);
        helper.assertTrue(new BlockPos(4, 5, 6).equals(BlankBlueprintItem.getPoint1(blank)), "point1");
        helper.assertTrue(new BlockPos(7, 8, 9).equals(BlankBlueprintItem.getPoint2(blank)), "point2");

        ItemStack build = stacks.get(2);
        helper.assertValueEqual(12345L, BuildBlueprintItem.getCompletionTime(build), "completion");
        helper.assertValueEqual("project", build.get(SpatialBlueprintComponents.SOURCE_TYPE.get()), "sourceType");

        ItemStack voidDim = stacks.get(3);
        helper.assertTrue(new BlockPos(1, 2, 3).equals(VoidDimensionalBlueprintItem.getCaptureMin(voidDim)), "capMin");
        helper.assertTrue(1.5 == VoidDimensionalBlueprintItem.getTpX(voidDim), "tpX");
        helper.assertValueEqual("minecraft:overworld", VoidDimensionalBlueprintItem.getTpDimension(voidDim), "tpDim");
        helper.assertValueEqual("w-uuid", VoidDimensionalBlueprintItem.getWorldUuid(voidDim), "worldUuid");

        ItemStack eng = stacks.get(4);
        helper.assertValueEqual("p1", EngineeringBlueprintItem.getBoundProjectId(eng), "engId");
        helper.assertTrue(new BlockPos(1, 2, 3).equals(EngineeringBlueprintItem.getBoundStartPos(eng)), "engStart");
        helper.assertTrue(new BlockPos(4, 5, 6).equals(EngineeringBlueprintItem.getBoundEndPos(eng)), "engEnd");
        helper.assertValueEqual("ts", EngineeringBlueprintItem.getTaskSetId(eng), "taskSet");
        helper.assertValueEqual(3, EngineeringBlueprintItem.getEntryIndex(eng), "entryIndex");

        ItemStack project = stacks.get(5);
        helper.assertValueEqual("11111111-2222-3333-4444-555555555555", ProjectBlueprintItem.getProjectId(project), "projectId");
        helper.assertTrue(ProjectBlueprintItem.isActivated(project), "activated");
        helper.assertValueEqual("LAYOUT", ProjectBlueprintItem.getStage(project), "stage");
        helper.assertTrue(new BlockPos(4, 5, 6).equals(ProjectBlueprintItem.getRangePoint(project, 2)), "rangeP2");

        // BlockPos setter 的 immutable()：改传入的 MutableBlockPos 后组件值不变
        ItemStack mutableTest = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(10, 20, 30);
        SpatialBlueprintItem.setLockedPos(mutableTest, mp);
        mp.set(99, 99, 99);
        helper.assertTrue(new BlockPos(10, 20, 30).equals(SpatialBlueprintItem.getLockedPos(mutableTest)),
                "lockedPos immutable copy");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void t03_networkRoundTrip(GameTestHelper helper) {
        for (ItemStack stack : sampleStacks()) {
            ItemStack back = netRoundTrip(stack, helper);
            helper.assertTrue(ItemStack.isSameItemSameComponents(stack, back),
                    "network roundtrip: " + stack.getItem());
        }
        helper.succeed();
    }

    // ── t04 / t05：旧物品迁移 ─────────────────────────────────────────────────

    private static CompoundTag legacyItem(String id, CompoundTag tag) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putByte("Count", (byte) 1);
        if (tag != null && !tag.isEmpty()) t.put("tag", tag);
        return t;
    }

    private static Tag fix(Tag oldTag) {
        return DataFixers.getDataFixer().update(References.ITEM_STACK,
                        new Dynamic<>(NbtOps.INSTANCE, oldTag),
                        3465, SharedConstants.getCurrentVersion().getDataVersion().getVersion())
                .getValue();
    }

    private static ItemStack migrate(GameTestHelper helper, CompoundTag oldTag, HolderLookup.Provider p) {
        Tag fixed = fix(oldTag);
        Optional<ItemStack> parsed = ItemStack.parse(p, fixed);
        helper.assertTrue(parsed.isPresent(), "migrated parse ok");
        return parsed.orElseThrow();
    }

    private static CompoundTag lowerPos(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        return t;
    }

    /** 1.20.1 的 {@code NbtUtils.writeBlockPos} 写的是大写 {@code {X,Y,Z}} 复合标签。 */
    private static CompoundTag upperPos(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("X", x);
        t.putInt("Y", y);
        t.putInt("Z", z);
        return t;
    }

    @GameTest(template = "empty")
    public static void t04_legacyMigration(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);

        // 1) 空间蓝图
        CompoundTag spatialTag = new CompoundTag();
        spatialTag.putString("blueprintId", "11111111-2222-3333-4444-555555555555");
        spatialTag.putString("blueprintName", "OldSpatial");
        spatialTag.putInt("saveMode", 2);
        spatialTag.putString("rotation", "CLOCKWISE_90");
        spatialTag.putInt("xOffset", 3);
        spatialTag.putBoolean("locked", true);
        spatialTag.put("lockedPos", lowerPos(1, 2, 3));
        spatialTag.putString("alignMode", "CENTER");
        spatialTag.putBoolean("clearFirst", true);
        spatialTag.putInt("animDuration", 7);
        ItemStack spatial = migrate(helper, legacyItem("spatial_blueprint:spatial_blueprint", spatialTag), p);
        helper.assertValueEqual("11111111-2222-3333-4444-555555555555", SpatialBlueprintItem.getBlueprintId(spatial), "id");
        helper.assertValueEqual("OldSpatial", SpatialBlueprintItem.getBlueprintName(spatial), "name");
        helper.assertTrue(SpatialBlueprintItem.getSaveMode(spatial) == CaptureMode.FORCE, "saveMode 2 = FORCE");
        helper.assertTrue(SpatialBlueprintItem.getRotation(spatial) == Rotation.CLOCKWISE_90, "rotation");
        helper.assertValueEqual(3, SpatialBlueprintItem.getXOffset(spatial), "xOffset");
        helper.assertTrue(SpatialBlueprintItem.isLocked(spatial), "locked");
        helper.assertTrue(new BlockPos(1, 2, 3).equals(SpatialBlueprintItem.getLockedPos(spatial)), "lockedPos");
        helper.assertValueEqual("CENTER", SpatialBlueprintItem.getAlignModeStr(spatial), "align");
        helper.assertTrue(SpatialBlueprintItem.getClearFirst(spatial), "clearFirst");
        helper.assertValueEqual(7, SpatialBlueprintItem.getAnimDuration(spatial, 99), "anim");
        helper.assertFalse(spatial.has(DataComponents.CUSTOM_DATA), "spatial custom_data cleared");

        // 2) 空白蓝图
        CompoundTag blankTag = new CompoundTag();
        blankTag.put("point1", lowerPos(4, 5, 6));
        blankTag.put("point2", lowerPos(7, 8, 9));
        ItemStack blank = migrate(helper, legacyItem("spatial_blueprint:blank_blueprint", blankTag), p);
        helper.assertTrue(new BlockPos(4, 5, 6).equals(BlankBlueprintItem.getPoint1(blank)), "point1");
        helper.assertTrue(new BlockPos(7, 8, 9).equals(BlankBlueprintItem.getPoint2(blank)), "point2");
        helper.assertFalse(blank.has(DataComponents.CUSTOM_DATA), "blank custom_data cleared");

        // 3) 工程蓝图
        CompoundTag engTag = new CompoundTag();
        engTag.putString("engProjectId", "p1");
        engTag.putString("engProjectName", "PN");
        engTag.putString("engDimension", "minecraft:overworld");
        engTag.putInt("engStartX", 1); engTag.putInt("engStartY", 2); engTag.putInt("engStartZ", 3);
        engTag.putInt("engEndX", 4); engTag.putInt("engEndY", 5); engTag.putInt("engEndZ", 6);
        engTag.putString("sbTaskSetId", "ts");
        engTag.putInt("sbEntryIndex", 3);
        ItemStack eng = migrate(helper, legacyItem("spatial_blueprint:engineering_blueprint", engTag), p);
        helper.assertValueEqual("p1", EngineeringBlueprintItem.getBoundProjectId(eng), "engId");
        helper.assertTrue(new BlockPos(1, 2, 3).equals(EngineeringBlueprintItem.getBoundStartPos(eng)), "engStart");
        helper.assertTrue(new BlockPos(4, 5, 6).equals(EngineeringBlueprintItem.getBoundEndPos(eng)), "engEnd");
        helper.assertValueEqual("ts", EngineeringBlueprintItem.getTaskSetId(eng), "taskSet");
        helper.assertValueEqual(3, EngineeringBlueprintItem.getEntryIndex(eng), "entryIndex");
        helper.assertFalse(eng.has(DataComponents.CUSTOM_DATA), "eng custom_data cleared");

        // 4) 空置次元蓝图（captureMin/Max 是 1.20.1 NbtUtils.writeBlockPos 的 {X,Y,Z}）
        CompoundTag voidTag = new CompoundTag();
        voidTag.put("captureMin", upperPos(1, 2, 3));
        voidTag.put("captureMax", upperPos(4, 5, 6));
        voidTag.putDouble("tpX", 1.5);
        voidTag.putDouble("tpY", 64.0);
        voidTag.putDouble("tpZ", 2.5);
        voidTag.putString("tpDimension", "minecraft:overworld");
        voidTag.putString("worldUuid", "w-uuid");
        voidTag.putString("worldName", "wn");
        voidTag.putString("creatorName", "c");
        ItemStack voidDim = migrate(helper, legacyItem("spatial_blueprint:void_dimensional_blueprint", voidTag), p);
        helper.assertTrue(new BlockPos(1, 2, 3).equals(VoidDimensionalBlueprintItem.getCaptureMin(voidDim)), "capMin");
        helper.assertTrue(new BlockPos(4, 5, 6).equals(VoidDimensionalBlueprintItem.getCaptureMax(voidDim)), "capMax");
        helper.assertTrue(1.5 == VoidDimensionalBlueprintItem.getTpX(voidDim), "tpX");
        helper.assertValueEqual("minecraft:overworld", VoidDimensionalBlueprintItem.getTpDimension(voidDim), "tpDim");
        helper.assertValueEqual("w-uuid", VoidDimensionalBlueprintItem.getWorldUuid(voidDim), "worldUuid");
        helper.assertValueEqual("c", VoidDimensionalBlueprintItem.getCreatorName(voidDim), "creator");
        helper.assertFalse(voidDim.has(DataComponents.CUSTOM_DATA), "void custom_data cleared");

        // 5) 项目蓝图
        CompoundTag projTag = new CompoundTag();
        projTag.putString("projectId", "pid");
        projTag.putString("taskSetId", "tid");
        projTag.putString("projectName", "P");
        projTag.putString("projectStage", "LAYOUT");
        projTag.putString("projectDim", "minecraft:overworld");
        projTag.put("rangeP1", upperPos(1, 2, 3));
        projTag.put("rangeP2", upperPos(4, 5, 6));
        ItemStack project = migrate(helper, legacyItem("spatial_blueprint:public_project_blueprint", projTag), p);
        helper.assertValueEqual("pid", ProjectBlueprintItem.getProjectId(project), "projectId");
        helper.assertValueEqual("tid", ProjectBlueprintItem.getTaskSetId(project), "taskSetId");
        helper.assertValueEqual("P", ProjectBlueprintItem.getProjectName(project), "projectName");
        helper.assertValueEqual("LAYOUT", ProjectBlueprintItem.getStage(project), "stage");
        helper.assertTrue(new BlockPos(1, 2, 3).equals(ProjectBlueprintItem.getRangePoint(project, 1)), "rangeP1");
        helper.assertFalse(project.has(DataComponents.CUSTOM_DATA), "project custom_data cleared");

        // 6) 建造蓝图：forceCancelled 是废键，迁移后 custom_data 里没有它
        CompoundTag buildTag = new CompoundTag();
        buildTag.putLong("completionTime", 999L);
        buildTag.putString("sourceType", "project");
        buildTag.putBoolean("forceCancelled", true);
        ItemStack build = migrate(helper, legacyItem("spatial_blueprint:build_blueprint", buildTag), p);
        helper.assertValueEqual(999L, BuildBlueprintItem.getCompletionTime(build), "completion");
        helper.assertValueEqual("project", build.get(SpatialBlueprintComponents.SOURCE_TYPE.get()), "sourceType");
        helper.assertFalse(build.has(DataComponents.CUSTOM_DATA), "build custom_data cleared (forceCancelled dropped)");

        // 7) blueprintData 与外来键保留在 custom_data
        CompoundTag inlineTag = new CompoundTag();
        inlineTag.putString("blueprintName", "WithInline");
        CompoundTag inline = new CompoundTag();
        inline.putString("k", "v");
        inlineTag.put("blueprintData", inline);
        inlineTag.putString("foo", "bar");
        ItemStack inlineStack = migrate(helper, legacyItem("spatial_blueprint:projection_blueprint", inlineTag), p);
        helper.assertValueEqual("WithInline", com.xioyim.spatialblueprint.item.AbstractBlueprintItem.getBlueprintName(inlineStack), "name");
        CustomData cd = inlineStack.get(DataComponents.CUSTOM_DATA);
        helper.assertTrue(cd != null, "custom data remains");
        CompoundTag left = cd.copyTag();
        helper.assertTrue(left.contains("blueprintData") && left.contains("foo") && left.size() == 2,
                "only blueprintData + foo left");

        // 8) 非法 rotation：不迁、不抛、旧键已删
        CompoundTag badTag = new CompoundTag();
        badTag.putString("rotation", "SIDEWAYS");
        ItemStack bad = migrate(helper, legacyItem("spatial_blueprint:spatial_blueprint", badTag), p);
        helper.assertFalse(bad.has(SpatialBlueprintComponents.ROTATION.get()), "invalid rotation not migrated");
        helper.assertFalse(bad.has(DataComponents.CUSTOM_DATA), "invalid rotation key deleted");

        // 9) 原版物品不动
        CompoundTag stoneTag = new CompoundTag();
        stoneTag.putString("blueprintId", "x");
        ItemStack stone = migrate(helper, legacyItem("minecraft:stone", stoneTag), p);
        helper.assertFalse(stone.has(SpatialBlueprintComponents.BLUEPRINT_ID.get()), "stone not migrated");
        CustomData stoneCd = stone.get(DataComponents.CUSTOM_DATA);
        helper.assertTrue(stoneCd != null && stoneCd.copyTag().contains("blueprintId"), "stone custom_data intact");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void t05_migrationIdempotent(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        CompoundTag tag = new CompoundTag();
        tag.putString("blueprintId", "11111111-2222-3333-4444-555555555555");
        tag.putString("blueprintName", "Idem");
        tag.putInt("saveMode", 1);
        ItemStack migrated = migrate(helper, legacyItem("spatial_blueprint:spatial_blueprint", tag), p);

        ItemStack copy = migrated.copy();
        copy.applyComponents(DataComponentPatch.EMPTY);
        helper.assertTrue(ItemStack.isSameItemSameComponents(migrated, copy), "applyComponents EMPTY no-op");

        ItemStack again = diskRoundTrip(helper, copy, p);
        helper.assertTrue(ItemStack.isSameItemSameComponents(copy, again), "re-save/re-parse no-op");
        helper.succeed();
    }

    // ── t06：BuildBlueprintItem.restore ───────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_buildRestore(GameTestHelper helper) {
        // void_dimensional：只带 12 个组件，其它组件（rotation）不带
        ItemStack voidSrc = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
        voidSrc.set(SpatialBlueprintComponents.SOURCE_TYPE.get(), "void_dimensional");
        voidSrc.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "V");
        voidSrc.set(SpatialBlueprintComponents.SAVE_MODE.get(), CaptureMode.SAFE);
        voidSrc.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "id-v");
        voidSrc.set(SpatialBlueprintComponents.CAPTURE_MIN.get(), new BlockPos(1, 2, 3));
        voidSrc.set(SpatialBlueprintComponents.CAPTURE_MAX.get(), new BlockPos(4, 5, 6));
        voidSrc.set(SpatialBlueprintComponents.TP_X.get(), 1.5);
        voidSrc.set(SpatialBlueprintComponents.TP_Y.get(), 64.0);
        voidSrc.set(SpatialBlueprintComponents.TP_Z.get(), 2.5);
        voidSrc.set(SpatialBlueprintComponents.TP_DIMENSION.get(), "minecraft:overworld");
        voidSrc.set(SpatialBlueprintComponents.CREATOR_NAME.get(), "c");
        voidSrc.set(SpatialBlueprintComponents.WORLD_UUID.get(), "w");
        voidSrc.set(SpatialBlueprintComponents.WORLD_NAME.get(), "wn");
        voidSrc.set(SpatialBlueprintComponents.ROTATION.get(), Rotation.CLOCKWISE_90);
        ItemStack voidRestored = BuildBlueprintItem.restore(voidSrc);
        helper.assertTrue(voidRestored.getItem() == SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get(), "void type");
        helper.assertValueEqual("V", VoidDimensionalBlueprintItem.getBlueprintName(voidRestored), "void name");
        helper.assertTrue(new BlockPos(1, 2, 3).equals(VoidDimensionalBlueprintItem.getCaptureMin(voidRestored)), "void capMin");
        helper.assertValueEqual("w", VoidDimensionalBlueprintItem.getWorldUuid(voidRestored), "void worldUuid");
        helper.assertFalse(voidRestored.has(SpatialBlueprintComponents.ROTATION.get()), "void drops other components");

        // project：整份带过去，completion_time / source_type 被删
        ItemStack projSrc = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
        projSrc.set(SpatialBlueprintComponents.SOURCE_TYPE.get(), "project");
        projSrc.set(SpatialBlueprintComponents.COMPLETION_TIME.get(), 123L);
        projSrc.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "id-p");
        projSrc.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "P");
        ItemStack projRestored = BuildBlueprintItem.restore(projSrc);
        helper.assertTrue(projRestored.getItem() == SpatialBlueprintItems.PROJECTION_BLUEPRINT.get(), "project type");
        helper.assertValueEqual("id-p", com.xioyim.spatialblueprint.item.AbstractBlueprintItem.getBlueprintId(projRestored), "project id kept");
        helper.assertFalse(projRestored.has(SpatialBlueprintComponents.COMPLETION_TIME.get()), "completion removed");
        helper.assertFalse(projRestored.has(SpatialBlueprintComponents.SOURCE_TYPE.get()), "sourceType removed");

        // blank / engineering：空物品
        ItemStack blankSrc = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
        blankSrc.set(SpatialBlueprintComponents.SOURCE_TYPE.get(), "blank");
        blankSrc.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "X");
        ItemStack blankRestored = BuildBlueprintItem.restore(blankSrc);
        helper.assertTrue(blankRestored.getItem() == SpatialBlueprintItems.BLANK_BLUEPRINT.get(), "blank type");
        helper.assertTrue(blankRestored.getComponentsPatch().isEmpty(), "blank no components");

        ItemStack engSrc = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
        engSrc.set(SpatialBlueprintComponents.SOURCE_TYPE.get(), "engineering");
        engSrc.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "id-e");
        ItemStack engRestored = BuildBlueprintItem.restore(engSrc);
        helper.assertTrue(engRestored.getItem() == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get(), "eng type");
        helper.assertTrue(engRestored.getComponentsPatch().isEmpty(), "eng no components");

        // 缺省 → 空间蓝图
        ItemStack otherSrc = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
        otherSrc.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "id-s");
        ItemStack otherRestored = BuildBlueprintItem.restore(otherSrc);
        helper.assertTrue(otherRestored.getItem() == SpatialBlueprintItems.SPATIAL_BLUEPRINT.get(), "default type");
        helper.assertValueEqual("id-s", SpatialBlueprintItem.getBlueprintId(otherRestored), "default keeps components");
        helper.succeed();
    }

    // ── t07：RenderBlockEntity ────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_renderBlockEntity(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);

        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, SpatialBlueprintRegistry.RENDER_BLOCK.get());
        RenderBlockEntity rbe = helper.getBlockEntity(rel);
        helper.assertTrue(rbe != null, "render block entity created");

        // setRenderData 两次（石头→空气，空气→橡木原木）
        rbe.setRenderData(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState());
        rbe.setRenderData(Blocks.AIR.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState());
        helper.assertTrue(rbe.renderBlock == Blocks.OAK_LOG.defaultBlockState(), "renderBlock = oak log");
        helper.assertTrue(rbe.targetBlock == Blocks.OAK_LOG.defaultBlockState(), "targetBlock = oak log");
        helper.assertFalse(rbe.shrinking, "not shrinking");
        helper.assertFalse(rbe.exchanging, "not exchanging");
        helper.assertValueEqual((byte) 0, rbe.drawSize, "drawSize reset");

        // setBlockEntityData：真实箱子存出的 NBT（含一格物品）
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, new ItemStack(Items.STONE, 5));
        CompoundTag chestNbt = chest.saveWithFullMetadata(p);
        rbe.setBlockEntityData(chestNbt);

        // 存盘 → loadStatic → 字段逐个相等
        CompoundTag saved = rbe.saveWithFullMetadata(p);
        BlockEntity loaded = BlockEntity.loadStatic(helper.absolutePos(rel), rbe.getBlockState(), saved, p);
        helper.assertTrue(loaded instanceof RenderBlockEntity, "loadStatic type");
        RenderBlockEntity loadedRbe = (RenderBlockEntity) loaded;
        helper.assertTrue(loadedRbe.renderBlock == Blocks.OAK_LOG.defaultBlockState(), "loaded renderBlock");
        helper.assertTrue(loadedRbe.targetBlock == Blocks.OAK_LOG.defaultBlockState(), "loaded targetBlock");
        helper.assertTrue(loadedRbe.blockEntityData != null && loadedRbe.blockEntityData.equals(chestNbt),
                "loaded blockEntityData");

        // getUpdateTag(provider) 经真实报文处理路径读回同样相等
        CompoundTag updateTag = rbe.getUpdateTag(p);
        RenderBlockEntity fromUpdate = new RenderBlockEntity(helper.absolutePos(rel), rbe.getBlockState());
        fromUpdate.handleUpdateTag(updateTag, p);
        helper.assertTrue(fromUpdate.blockEntityData != null && fromUpdate.blockEntityData.equals(chestNbt),
                "update tag blockEntityData");

        // setRealBlock：变成真箱子，箱内物品一致
        BlockPos rel2 = new BlockPos(3, 1, 1);
        helper.setBlock(rel2, SpatialBlueprintRegistry.RENDER_BLOCK.get());
        RenderBlockEntity rbe2 = helper.getBlockEntity(rel2);
        rbe2.setBlockEntityData(chestNbt);
        rbe2.setRealBlock(Blocks.CHEST.defaultBlockState());
        BlockEntity nowThere = helper.getLevel().getBlockEntity(helper.absolutePos(rel2));
        helper.assertTrue(nowThere instanceof ChestBlockEntity, "real chest placed");
        ChestBlockEntity realChest = (ChestBlockEntity) nowThere;
        helper.assertTrue(realChest.getItem(0).getCount() == 5
                && realChest.getItem(0).getItem() == Items.STONE, "chest item restored");
        helper.succeed();
    }
}
