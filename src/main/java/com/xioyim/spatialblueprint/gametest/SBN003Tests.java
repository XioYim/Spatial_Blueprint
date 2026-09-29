package com.xioyim.spatialblueprint.gametest;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.SBSafeCoordData;
import com.xioyim.spatialblueprint.util.SBUndoData;
import com.xioyim.spatialblueprint.util.TeleportPermissions;
import com.xioyim.spatialblueprint.util.UndoRecord;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SBN-003 存储与 SavedData + D8 材料键组件化 GameTest（t01～t07）。
 * NBT 一律由真实对象序列化得到；唯一手写处是 t03 的旧格式 tag 样本（向后兼容证明）。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN003Tests {

    private static HolderLookup.Provider provider(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    private static ItemStack enchantedSword(HolderLookup.Provider p, boolean sharpFirst) {
        ItemStack s = new ItemStack(Items.DIAMOND_SWORD);
        var lookup = p.lookupOrThrow(Registries.ENCHANTMENT);
        if (sharpFirst) {
            s.enchant(lookup.getOrThrow(Enchantments.SHARPNESS), 3);
            s.enchant(lookup.getOrThrow(Enchantments.UNBREAKING), 2);
        } else {
            s.enchant(lookup.getOrThrow(Enchantments.UNBREAKING), 2);
            s.enchant(lookup.getOrThrow(Enchantments.SHARPNESS), 3);
        }
        return s;
    }

    private static ItemStack namedSword(HolderLookup.Provider p) {
        ItemStack s = new ItemStack(Items.DIAMOND_SWORD);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
        return s;
    }

    private static ItemStack pickedUpEmptyShulker(HolderLookup.Provider p) {
        ShulkerBoxBlockEntity be = new ShulkerBoxBlockEntity(BlockPos.ZERO, Blocks.SHULKER_BOX.defaultBlockState());
        ItemStack picked = new ItemStack(Items.SHULKER_BOX);
        be.saveToItem(picked, p);
        return picked;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 对 load 为 private 的 SavedData（ProjectClaimRegistry / ProjectProgressData /
     * TeleportPermissions / WorldIdentity，与 1.20.1 同可见性）：反射调用同一个 load 方法做往返，
     * 不改源码可见性。返回再序列化后的 NBT。
     */
    private static CompoundTag roundTripViaPrivateLoad(Class<?> cls, CompoundTag tag, HolderLookup.Provider p) {
        try {
            var m = cls.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
            m.setAccessible(true);
            net.minecraft.world.level.saveddata.SavedData data =
                    (net.minecraft.world.level.saveddata.SavedData) m.invoke(null, tag, p);
            return data.save(new CompoundTag(), p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflective load failed for " + cls.getSimpleName(), e);
        }
    }

    /** t01：D8 材料键（纯 ID / 摘要 / 顺序无关 / 忽略项 / "!" 移除标记 / 空潜影盒等价）。 */
    @GameTest(template = "empty")
    public static void t01_materialKeyComponents(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);

        helper.assertValueEqual("minecraft:stone", MaterialKey.of(new ItemStack(Items.STONE, 3), p),
                "plain stone -> pure id");

        ItemStack swordA = enchantedSword(p, true);
        String keyA = MaterialKey.of(swordA, p);
        helper.assertTrue(keyA.contains("#"), "enchanted sword has digest suffix");

        helper.assertValueEqual(keyA, MaterialKey.of(enchantedSword(p, false), p),
                "enchant add order independent");

        ItemStack repair5 = enchantedSword(p, true);
        repair5.set(DataComponents.REPAIR_COST, 5);
        ItemStack repair9 = enchantedSword(p, true);
        repair9.set(DataComponents.REPAIR_COST, 9);
        helper.assertValueEqual(keyA, MaterialKey.of(repair5, p), "repair_cost ignored (5)");
        helper.assertValueEqual(keyA, MaterialKey.of(repair9, p), "repair_cost ignored (9)");

        helper.assertFalse(keyA.equals(MaterialKey.of(namedSword(p), p)), "renamed sword differs");

        helper.assertValueEqual(MaterialKey.of(new ItemStack(Items.SHULKER_BOX), p),
                MaterialKey.of(pickedUpEmptyShulker(p), p), "picked empty shulker == crafted");

        ItemStack noFood = new ItemStack(Items.APPLE);
        noFood.remove(DataComponents.FOOD);
        helper.assertFalse(MaterialKey.of(new ItemStack(Items.APPLE), p).equals(MaterialKey.of(noFood, p)),
                "removed default component differs");
        CompoundTag noFoodSaved = (CompoundTag) noFood.save(p);
        CompoundTag noFoodComps = noFoodSaved.getCompound("components");
        boolean hasMarker = noFoodComps.getAllKeys().stream().anyMatch(k -> k.startsWith("!"));
        helper.assertTrue(hasMarker, "removed marker key present in saved components");

        // 开关型组件（SBN-003a）：存在即生效，编码为空 compound，不能被规范化剔掉
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        String pickKey = MaterialKey.of(pick, p);
        helper.assertFalse(MaterialKey.of(new ItemStack(Items.DIAMOND_PICKAXE), p).equals(pickKey),
                "unbreakable pickaxe differs from plain");
        ItemStack pickHidden = new ItemStack(Items.DIAMOND_PICKAXE);
        pickHidden.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        helper.assertFalse(pickKey.equals(MaterialKey.of(pickHidden, p)),
                "hidden-tooltip unbreakable differs from default");
        ItemStack hideStick = new ItemStack(Items.STICK);
        hideStick.set(DataComponents.HIDE_TOOLTIP, Unit.INSTANCE);
        helper.assertFalse(MaterialKey.of(new ItemStack(Items.STICK), p).equals(MaterialKey.of(hideStick, p)),
                "hide_tooltip stick differs from plain");
        helper.succeed();
    }

    /** t02：of(stack, provider) 与 ItemStack.save 的 components 子标签同编码（一致性硬要求）。 */
    @GameTest(template = "empty")
    public static void t02_keyConsistency(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);

        ItemStack repair = enchantedSword(p, true);
        repair.set(DataComponents.REPAIR_COST, 7);
        ItemStack shulkerWithContents = new ItemStack(Items.SHULKER_BOX);
        shulkerWithContents.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 2))));
        ItemStack noFood = new ItemStack(Items.APPLE);
        noFood.remove(DataComponents.FOOD);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        ItemStack pickHidden = new ItemStack(Items.DIAMOND_PICKAXE);
        pickHidden.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        ItemStack hideStick = new ItemStack(Items.STICK);
        hideStick.set(DataComponents.HIDE_TOOLTIP, Unit.INSTANCE);

        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(new ItemStack(Items.STONE, 3));
        stacks.add(enchantedSword(p, true));
        stacks.add(repair);
        stacks.add(namedSword(p));
        stacks.add(pickedUpEmptyShulker(p));
        stacks.add(noFood);
        stacks.add(shulkerWithContents);
        stacks.add(pick);
        stacks.add(pickHidden);
        stacks.add(hideStick);

        for (ItemStack stack : stacks) {
            CompoundTag saved = (CompoundTag) stack.save(p);
            CompoundTag comps = saved.contains("components", 10) ? saved.getCompound("components") : null;
            String expected = MaterialKey.make(idOf(stack), comps);
            String actual = MaterialKey.of(stack, p);
            helper.assertValueEqual(expected, actual, "key consistency for " + idOf(stack));
        }
        helper.succeed();
    }

    /** t03：key + keyTags 里的 tag → buildDisplayStack 往返；旧格式 tag → 普通栈不抛异常。 */
    @GameTest(template = "empty")
    public static void t03_displayStackRoundTrip(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);

        ItemStack unbreakablePick = new ItemStack(Items.DIAMOND_PICKAXE);
        unbreakablePick.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        List<ItemStack> stacks = List.of(enchantedSword(p, true), namedSword(p), unbreakablePick);
        for (ItemStack stack : stacks) {
            CompoundTag saved = (CompoundTag) stack.save(p);
            CompoundTag comps = saved.contains("components", 10) ? saved.getCompound("components") : null;
            CompoundTag norm = MaterialKey.normalize(comps);
            helper.assertTrue(norm != null, "normalized components present");
            String key = MaterialKey.of(stack, p);
            ItemStack display = MaterialList.buildDisplayStack(key, norm, p);
            helper.assertTrue(ItemStack.isSameItemSameComponents(stack, display), "display roundtrip");
        }

        // 旧格式 tag（手写，唯一允许处）：{Enchantments:[…]} → 解析失败 → 无组件普通栈
        CompoundTag old = new CompoundTag();
        ListTag ench = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putString("id", "minecraft:sharpness");
        e.putShort("lvl", (short) 3);
        ench.add(e);
        old.put("Enchantments", ench);
        ItemStack plain = MaterialList.buildDisplayStack("minecraft:diamond_sword#deadbeef", old, p);
        helper.assertTrue(plain.getItem() == Items.DIAMOND_SWORD, "plain item returned");
        helper.assertFalse(plain.isEnchanted(), "old tag not applied");
        helper.succeed();
    }

    /** t04：实体身上的物品与手里的物品算出同一个键。 */
    @GameTest(template = "empty")
    public static void t04_entityItemKey(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        ItemStack sword = enchantedSword(p, true);

        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        stand.setItemSlot(EquipmentSlot.MAINHAND, sword);
        CompoundTag nbt = new CompoundTag();
        stand.save(nbt);

        BlueprintData data = new BlueprintData();
        data.entities.add(new BlueprintData.EntityEntry(0, 0, 0, (byte) 0, "minecraft:armor_stand", nbt));
        MaterialList ml = MaterialList.fromBlueprintData(data);

        String key = MaterialKey.of(sword, p);
        helper.assertTrue(ml.blockCounts.containsKey(key), "entity item key matches of()");
        helper.assertValueEqual(1, ml.blockCounts.get(key), "count is 1");
        helper.succeed();
    }

    /** t05：7 个 SavedData 往返（含 MaterialSpace 真实 computeIfAbsent 接线）。 */
    @GameTest(template = "empty")
    public static void t05_savedDataRoundTrip(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        UUID player = UUID.randomUUID();

        MaterialSpace ms = MaterialSpace.get(helper.getLevel());
        ms.deposit(player, "minecraft:stone", 3, null);
        CompoundTag goldTag = new CompoundTag();
        goldTag.putInt("minecraft:repair_cost", 0);
        ms.bagDeposit(player, "bp-1", "minecraft:gold_ingot#deadbeef", 2, goldTag);
        helper.assertTrue(ms.count(player, "minecraft:stone") == 3, "material space live instance");
        CompoundTag n1 = ms.save(new CompoundTag(), p);
        CompoundTag n2 = MaterialSpace.load(n1, p).save(new CompoundTag(), p);
        helper.assertTrue(n1.equals(n2), "MaterialSpace roundtrip");

        ProjectClaimRegistry cr = ProjectClaimRegistry.get(helper.getLevel());
        ProjectClaimRegistry.Claim claim = new ProjectClaimRegistry.Claim();
        claim.taskSetId = "ts-1";
        claim.projectId = "p-1";
        claim.dimension = "minecraft:overworld";
        claim.publicProject = true;
        claim.ownerUuid = player.toString();
        claim.ownerName = "Tester";
        claim.createdAt = 123L;
        claim.minX = 1; claim.minY = 2; claim.minZ = 3;
        claim.maxX = 4; claim.maxY = 5; claim.maxZ = 6;
        claim.anchorX = 2; claim.anchorY = 3; claim.anchorZ = 4;
        claim.hasAnchor = true;
        cr.add(claim);
        CompoundTag c1 = cr.save(new CompoundTag(), p);
        CompoundTag c2 = roundTripViaPrivateLoad(ProjectClaimRegistry.class, c1, p);
        helper.assertTrue(c1.equals(c2), "ProjectClaimRegistry roundtrip");

        ProjectProgressData pd = ProjectProgressData.get(helper.getLevel());
        pd.markCompleted("ts-1", 2);
        pd.markClaimed(player.toString(), "ts-1", 2);
        CompoundTag d1 = pd.save(new CompoundTag(), p);
        CompoundTag d2 = roundTripViaPrivateLoad(ProjectProgressData.class, d1, p);
        helper.assertTrue(d1.equals(d2), "ProjectProgressData roundtrip");

        SBSafeCoordData sc = SBSafeCoordData.get(helper.getLevel());
        sc.setEntry("bp-1", true, 10, 64, 20);
        CompoundTag s1 = sc.save(new CompoundTag(), p);
        CompoundTag s2 = SBSafeCoordData.load(s1, p).save(new CompoundTag(), p);
        helper.assertTrue(s1.equals(s2), "SBSafeCoordData roundtrip");

        SBUndoData ud = SBUndoData.get(helper.getLevel());
        UndoRecord rec = new UndoRecord(ResourceLocation.parse("minecraft:overworld"),
                new BlockPos(1, 2, 3), new BlockPos(4, 5, 6),
                List.of(new UndoRecord.SnapshotBlock(new BlockPos(1, 2, 3),
                        Blocks.STONE.defaultBlockState(), null)),
                List.of(new UndoRecord.SnapshotEntity("minecraft:painting", new CompoundTag())));
        ud.addRecord(player, rec, 6);
        CompoundTag u1 = ud.save(new CompoundTag(), p);
        CompoundTag u2 = SBUndoData.load(u1, p).save(new CompoundTag(), p);
        helper.assertTrue(u1.equals(u2), "SBUndoData roundtrip");

        TeleportPermissions tp = TeleportPermissions.get(helper.getLevel());
        tp.grant(player);
        CompoundTag t1 = tp.save(new CompoundTag(), p);
        CompoundTag t2 = roundTripViaPrivateLoad(TeleportPermissions.class, t1, p);
        helper.assertTrue(t1.equals(t2), "TeleportPermissions roundtrip");

        WorldIdentity wi = WorldIdentity.get(helper.getLevel());
        CompoundTag w1 = wi.save(new CompoundTag(), p);
        CompoundTag w2 = roundTripViaPrivateLoad(WorldIdentity.class, w1, p);
        helper.assertTrue(w1.equals(w2), "WorldIdentity roundtrip");
        helper.succeed();
    }

    /** t06：BlueprintStorage / ProjectStorage 保存 → 读回 → 删除。 */
    @GameTest(template = "empty")
    public static void t06_storageRoundTrip(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);

        BlueprintData data = new BlueprintData();
        data.name = "t06";
        data.category = "create";
        data.creatorName = "Tester";
        data.size = new BlockPos(2, 1, 1);
        data.fixedStartPos = new BlockPos(10, 64, 10);
        data.fixedEndPos = new BlockPos(11, 64, 10);
        data.fixedDimension = "minecraft:overworld";
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, new ItemStack(Items.STONE, 5));
        data.lateBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                Blocks.CHEST.defaultBlockState(), chest.saveWithFullMetadata(p)));

        UUID id = BlueprintStorage.save(data);
        try {
            helper.assertTrue(BlueprintStorage.findFile(id.toString()) != null, "blueprint file found");
            BlueprintData back = BlueprintStorage.load(id.toString());
            helper.assertTrue(back != null, "blueprint loaded");
            helper.assertValueEqual("t06", back.name, "name");
            helper.assertTrue(back.fixedStartPos != null && back.fixedStartPos.equals(data.fixedStartPos),
                    "fixedStartPos");
            helper.assertTrue(back.fixedEndPos != null && back.fixedEndPos.equals(data.fixedEndPos),
                    "fixedEndPos");
            helper.assertTrue(back.normalBlocks.size() == 1 && back.lateBlocks.size() == 1, "block lists");
            helper.assertTrue(back.lateBlocks.get(0).nbt != null, "chest nbt kept");
        } finally {
            BlueprintStorage.delete(id.toString());
        }
        helper.assertTrue(BlueprintStorage.findFile(id.toString()) == null, "blueprint file gone");

        UUID projectId = UUID.randomUUID();
        ProjectData pd = new ProjectData();
        pd.name = "t06p";
        pd.entries.add(new ProjectData.EntryData(1, "e.litematic", "E",
                new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), 0xFF00FF00));
        ProjectStorage.saveProject(projectId, pd);
        try {
            ProjectData back2 = ProjectStorage.loadProject(projectId);
            helper.assertTrue(back2 != null && back2.name.equals("t06p") && back2.entries.size() == 1,
                    "project roundtrip");
            helper.assertTrue(back2.entries.get(0).relP1.equals(new BlockPos(0, 0, 0)), "entry relP1");
        } finally {
            ProjectStorage.deleteProject(projectId);
        }
        helper.assertFalse(ProjectStorage.projectExists(projectId), "project gone");
        helper.succeed();
    }

    /** t07：旧格式 {X,Y,Z} 坐标经摘要读取路径（listSummaries）读出正确。 */
    @GameTest(template = "empty")
    public static void t07_legacyCoords(GameTestHelper helper) {
        BlueprintData data = new BlueprintData();
        data.name = "t07";
        data.category = "create";
        data.creatorName = "Tester";
        data.size = new BlockPos(1, 1, 1);
        data.fixedStartPos = new BlockPos(123, 64, -45);
        data.fixedEndPos = new BlockPos(124, 64, -45);

        UUID id = BlueprintStorage.save(data);
        try {
            BlueprintStorage.invalidateSummaryCache();
            BlueprintStorage.BlueprintSummary found = null;
            for (BlueprintStorage.BlueprintSummary s : BlueprintStorage.listSummaries()) {
                if (s.id().equals(id.toString())) found = s;
            }
            helper.assertTrue(found != null, "summary found");
            helper.assertTrue(new BlockPos(123, 64, -45).equals(found.fixedStartPos()), "legacy start coords");
            helper.assertTrue(new BlockPos(124, 64, -45).equals(found.fixedEndPos()), "legacy end coords");
        } finally {
            BlueprintStorage.delete(id.toString());
        }
        helper.succeed();
    }
}
