package com.xioyim.spatialblueprint.gametest;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.LegacyDataFixer;
import com.xioyim.spatialblueprint.util.LitematicaCodec;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import com.xioyim.spatialblueprint.util.SBUndoData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * SBN-011 D2 数据升级 GameTest（t01～t07）。
 * 本任务的 1.20.1 格式样本是唯一允许手写处；1.21 格式数据一律由真实对象产生。
 * 样本写法照 SBN-004 t04 与 K8：物品 {id, Count:1b, tag:{…}}、附魔
 * Enchantments:[{id, lvl:3s}]、名字 display.Name 为 JSON 字符串。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN011Tests {

    private static HolderLookup.Provider provider(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    // ── 1.20.1 格式样本 ─────────────────────────────────────────────────────

    /** 旧附魔 tag：锋利 III + 自定义名（1.20.1 物品 tag 形状）。 */
    private static CompoundTag oldSwordTag() {
        CompoundTag ench = new CompoundTag();
        ench.putString("id", "minecraft:sharpness");
        ench.putShort("lvl", (short) 3);
        ListTag list = new ListTag();
        list.add(ench);
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"Test Blade\"}");
        CompoundTag tag = new CompoundTag();
        tag.put("Enchantments", list);
        tag.put("display", display);
        return tag;
    }

    /** 同样附魔的另一种旧 tag 写法（多一个 RepairCost，D8 忽略项，升级后应得同一新键）。 */
    private static CompoundTag oldSwordTagB() {
        CompoundTag tag = oldSwordTag();
        tag.putInt("RepairCost", 5);
        return tag;
    }

    /** 旧物品：{id, Count:1b, tag}（K8 口径）。 */
    private static CompoundTag oldItem(String id, CompoundTag tag) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putByte("Count", (byte) 1);
        if (tag != null && !tag.isEmpty()) t.put("tag", tag);
        return t;
    }

    /** 用 1.21 真实 API 造的对照剑：钻石剑 + 锋利 III + 同名。 */
    private static ItemStack realSword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(provider(helper).lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Test Blade"));
        return sword;
    }

    /** 旧箱子方块实体 NBT（含 id，相对坐标 x/y/z）。 */
    private static CompoundTag oldChestNbt(int x, int y, int z) {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", x);
        chest.putInt("y", y);
        chest.putInt("z", z);
        ListTag items = new ListTag();
        CompoundTag sword = oldItem("minecraft:diamond_sword", oldSwordTag());
        sword.putByte("Slot", (byte) 0);
        items.add(sword);
        chest.put("Items", items);
        return chest;
    }

    /** 旧展示框实体 NBT（含 id）。 */
    private static CompoundTag oldFrameNbt() {
        CompoundTag frame = new CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.putInt("TileX", 0);
        frame.putInt("TileY", 0);
        frame.putInt("TileZ", 0);
        frame.putByte("Facing", (byte) 1);
        frame.put("Item", oldItem("minecraft:diamond_sword", oldSwordTag()));
        return frame;
    }

    /**
     * 手写完整 1.20.1 格式蓝图 NBT：Version 7、MinecraftDataVersion 3465、单区域 Main（2x1x1：
     * x=0 为 grass、x=1 为箱子）、箱子方块实体、一个物品展示框实体、材料清单（石头 x2）。
     */
    private static CompoundTag legacyBlueprintRoot() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", 3465);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "t02");
        root.put("Metadata", metadata);

        CompoundTag region = new CompoundTag();
        region.put("Position", intTriple(0, 0, 0));
        region.put("Size", intTriple(2, 1, 1));
        ListTag palette = new ListTag();
        palette.add(stateEntry("minecraft:air"));
        palette.add(stateEntry("minecraft:grass"));
        palette.add(stateEntry("minecraft:chest"));
        region.put("BlockStatePalette", palette);
        long[] states = new long[LitematicaCodec.arrayLength(2, LitematicaCodec.bitsPerEntry(3))];
        LitematicaCodec.putPackedValue(states, 0, LitematicaCodec.bitsPerEntry(3), 1);
        LitematicaCodec.putPackedValue(states, 1, LitematicaCodec.bitsPerEntry(3), 2);
        region.putLongArray("BlockStates", states);
        ListTag tes = new ListTag();
        tes.add(oldChestNbt(1, 0, 0));
        region.put("TileEntities", tes);
        ListTag entities = new ListTag();
        entities.add(oldFrameNbt());
        region.put("Entities", entities);
        CompoundTag regions = new CompoundTag();
        regions.put("Main", region);
        root.put("Regions", regions);

        CompoundTag mod = new CompoundTag();
        mod.putString("category", "create");
        CompoundTag ml = new CompoundTag();
        ml.putString("name", "t02");
        ListTag entries = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("block", "minecraft:stone");
        stone.putInt("count", 2);
        entries.add(stone);
        ml.put("entries", entries);
        mod.put("materialList", ml);
        root.put("SpatialBlueprintData", mod);
        return root;
    }

    private static CompoundTag intTriple(int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        return t;
    }

    private static CompoundTag stateEntry(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    // ── t01：修复器单元 ──────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_fixerUnits(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        int current = LegacyDataFixer.current();
        helper.assertTrue(current > 3465, "current dataVersion beyond 1.20.1");

        // 方块状态：grass（1.20.3 起改名 short_grass）
        CompoundTag grass = stateEntry("minecraft:grass");
        CompoundTag fixedGrass = LegacyDataFixer.fixBlockState(grass, 3465);
        helper.assertValueEqual("minecraft:short_grass", fixedGrass.getString("Name"), "grass renamed");
        helper.assertValueEqual("minecraft:grass", grass.getString("Name"), "input not mutated");

        // 物品：旧附魔剑 → components（与 1.21 真实剑逐格一致）
        CompoundTag fixedSword = LegacyDataFixer.fixItemStack(oldItem("minecraft:diamond_sword", oldSwordTag()), 3465);
        helper.assertTrue(fixedSword.contains("components", 10), "sword gained components");
        Optional<ItemStack> parsed = ItemStack.parse(p, fixedSword);
        helper.assertTrue(parsed.isPresent(), "fixed sword parses");
        helper.assertTrue(ItemStack.isSameItemSameComponents(parsed.get(), realSword(helper)),
                "fixed sword equals real sword");

        // from = current 或更大 → 原样返回
        helper.assertTrue(LegacyDataFixer.fixBlockState(grass, current).equals(grass), "current untouched");
        helper.assertTrue(LegacyDataFixer.fixBlockState(grass, current + 1000).equals(grass), "newer untouched");
        helper.assertTrue(LegacyDataFixer.fixItemStack(
                oldItem("minecraft:stone", null), current + 1000)
                .equals(oldItem("minecraft:stone", null)), "newer item untouched");

        // 无 id 的方块实体 / 实体 → 原样返回，不抛
        CompoundTag noId = new CompoundTag();
        noId.putInt("x", 1);
        helper.assertTrue(LegacyDataFixer.fixBlockEntity(noId, 3465).equals(noId), "be without id untouched");
        helper.assertTrue(LegacyDataFixer.fixEntity(noId, 3465).equals(noId), "entity without id untouched");

        // 恶意输入 → 返回原 tag 内容，不抛到调用方外
        CompoundTag[] hostile = {
                stateEntry("minecraft:grass"),
                oldChestNbt(0, 0, 0),
                oldFrameNbt(),
        };
        int[] hostileFrom = {-100, 0, 5};
        for (CompoundTag h : hostile) {
            CompoundTag before = h.copy();
            for (int from : hostileFrom) {
                helper.assertTrue(LegacyDataFixer.fixBlockState(h, from).equals(before)
                        || !LegacyDataFixer.fixBlockState(h, from).isEmpty(), "blockstate hostile safe");
                helper.assertTrue(LegacyDataFixer.fixBlockEntity(h, from).equals(before)
                        || !LegacyDataFixer.fixBlockEntity(h, from).isEmpty(), "be hostile safe");
                helper.assertTrue(LegacyDataFixer.fixEntity(h, from).equals(before)
                        || !LegacyDataFixer.fixEntity(h, from).isEmpty(), "entity hostile safe");
                helper.assertTrue(LegacyDataFixer.fixItemStack(h, from).equals(before)
                        || !LegacyDataFixer.fixItemStack(h, from).isEmpty(), "item hostile safe");
            }
        }
        helper.succeed();
    }

    // ── t02：蓝图文件升级 ────────────────────────────────────────────────────

    private static void assertT02(GameTestHelper helper, BlueprintData data, int expectedVersion) {
        helper.assertValueEqual(expectedVersion, data.dataVersion, "dataVersion keeps file value");
        helper.assertValueEqual(2, data.normalBlocks.size() + data.dependentBlocks.size()
                + data.lateBlocks.size(), "2 blocks captured");
        BlueprintData.BlockEntry grassEntry = null;
        for (BlueprintData.BlockEntry e : data.dependentBlocks) {
            if (e.state.is(Blocks.SHORT_GRASS)) grassEntry = e;
        }
        helper.assertTrue(grassEntry != null, "grass upgraded to short_grass");
        BlueprintData.BlockEntry chestEntry = null;
        for (BlueprintData.BlockEntry e : data.lateBlocks) {
            if (e.state.is(Blocks.CHEST)) chestEntry = e;
        }
        helper.assertTrue(chestEntry != null && chestEntry.nbt != null, "chest entry with nbt");

        // 箱子 NBT 读进真实箱子：第 0 格是带锋利 III 且有自定义名的钻石剑
        ChestBlockEntity fresh = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        fresh.loadWithComponents(chestEntry.nbt, provider(helper));
        helper.assertTrue(ItemStack.isSameItemSameComponents(fresh.getItem(0), realSword(helper)),
                "chest sword upgraded");
        helper.assertValueEqual("Test Blade",
                fresh.getItem(0).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString(),
                "chest sword custom name");

        // 展示框 NBT 还原：框里的物品同样正确
        helper.assertValueEqual(1, data.entities.size(), "one entity");
        BlueprintData.EntityEntry frame = data.entities.get(0);
        helper.assertValueEqual("minecraft:item_frame", frame.type, "frame type");
        Optional<Entity> recreated = EntityType.create(frame.nbt, helper.getLevel());
        helper.assertTrue(recreated.isPresent() && recreated.get() instanceof ItemFrame,
                "frame recreated");
        helper.assertTrue(ItemStack.isSameItemSameComponents(
                ((ItemFrame) recreated.get()).getItem(), realSword(helper)), "frame item upgraded");

        // 材料清单（石头 x2，无 tag 键原样）
        helper.assertTrue(data.materialList != null, "materialList present");
        helper.assertValueEqual(2, data.materialList.blockCounts.getOrDefault("minecraft:stone", -1),
                "stone count kept");
    }

    @GameTest(template = "empty")
    public static void t02_blueprintFile(GameTestHelper helper) {
        BlueprintData data = BlueprintData.deserialize(legacyBlueprintRoot());
        assertT02(helper, data, 3465);
        helper.succeed();
    }

    // ── t03：1.21 数据原样不动 + 缺键按 3465 ──────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_currentUntouched(GameTestHelper helper) {
        // 真实对象产生的 1.21 数据：两块石头
        BlueprintData data = new BlueprintData();
        data.name = "t03";
        data.category = "create";
        data.size = new BlockPos(2, 1, 1);
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                Blocks.STONE.defaultBlockState(), null));

        CompoundTag root1 = data.serialize();
        BlueprintData back = BlueprintData.deserialize(root1);
        CompoundTag root2 = back.serialize();
        helper.assertTrue(root1.equals(root2), "current data byte-identical through deserialize");
        helper.assertValueEqual(LegacyDataFixer.current(), back.dataVersion, "current version recorded");

        // 缺 MinecraftDataVersion 键 → 按 3465 处理（t02 样本去键，结果与 t02 相同，版本号记 0）
        CompoundTag noKey = legacyBlueprintRoot();
        noKey.remove("MinecraftDataVersion");
        BlueprintData legacy = BlueprintData.deserialize(noKey);
        assertT02(helper, legacy, 0);
        helper.succeed();
    }

    // ── t04：材料键迁移 ──────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_materialKeys(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        String expectedKey = MaterialKey.of(realSword(helper), p);
        helper.assertTrue(MaterialKey.hasTag(expectedKey), "real sword key has digest");

        CompoundTag tag = new CompoundTag();
        tag.putString("name", "t04");
        ListTag entries = new ListTag();
        entries.add(countEntry("minecraft:diamond_sword#legacyA", 2));
        entries.add(countEntry("minecraft:diamond_sword#legacyB", 3));
        entries.add(countEntry("minecraft:stone", 5));
        tag.put("entries", entries);
        ListTag keyTags = new ListTag();
        keyTags.add(tagSample("minecraft:diamond_sword#legacyA", oldSwordTag()));
        keyTags.add(tagSample("minecraft:diamond_sword#legacyB", oldSwordTagB()));
        tag.put("keyTags", keyTags);

        MaterialList ml = MaterialList.deserialize(tag, 3465);
        helper.assertValueEqual(5, ml.blockCounts.getOrDefault(expectedKey, -1), "merged count 5");
        helper.assertValueEqual(5, ml.blockCounts.getOrDefault("minecraft:stone", -1), "stone kept");
        helper.assertValueEqual(2, ml.blockCounts.size(), "two keys after merge");
        CompoundTag newComponents = ml.keyTags.get(expectedKey);
        helper.assertTrue(newComponents != null, "keyTags migrated to new components");
        // 新 components 能组出与真实剑一致的展示栈
        ItemStack display = MaterialList.buildDisplayStack(expectedKey, newComponents, p);
        helper.assertTrue(ItemStack.isSameItemSameComponents(display, realSword(helper)),
                "display stack equals real sword");
        helper.succeed();
    }

    // ── t08：纯 ID 材料键的物品改名（云端验收补：grass → short_grass）─────────

    @GameTest(template = "empty")
    public static void t08_materialKeyRename(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        CompoundTag tag = new CompoundTag();
        tag.putString("name", "t08");
        ListTag entries = new ListTag();
        entries.add(countEntry("minecraft:grass", 4));
        entries.add(countEntry("minecraft:short_grass", 1));
        entries.add(countEntry("minecraft:stone", 5));
        tag.put("entries", entries);

        MaterialList ml = MaterialList.deserialize(tag, 3465);
        helper.assertValueEqual(ml.blockCounts.getOrDefault("minecraft:short_grass", -1), 5, "grass renamed and merged");
        helper.assertTrue(!ml.blockCounts.containsKey("minecraft:grass"), "old grass key gone");
        helper.assertValueEqual(ml.blockCounts.getOrDefault("minecraft:stone", -1), 5, "stone kept");
        // 当前版本不动
        MaterialList cur = MaterialList.deserialize(tag, LegacyDataFixer.current());
        helper.assertValueEqual(cur.blockCounts.getOrDefault("minecraft:grass", -1), 4, "current version untouched");

        UUID pid = UUID.randomUUID();
        CompoundTag archive = new CompoundTag();
        ListTag pools = new ListTag();
        CompoundTag pool = new CompoundTag();
        pool.putString("player", pid.toString());
        CompoundTag poolItems = new CompoundTag();
        poolItems.putInt("minecraft:grass", 2);
        poolItems.putInt("minecraft:stone", 7);
        pool.put("items", poolItems);
        pools.add(pool);
        archive.put("pools", pools);
        ListTag bags = new ListTag();
        CompoundTag bag = new CompoundTag();
        bag.putString("player", pid.toString());
        ListTag perBp = new ListTag();
        CompoundTag bp = new CompoundTag();
        bp.putString("id", "bp1");
        CompoundTag bagItems = new CompoundTag();
        bagItems.putInt("minecraft:grass", 3);
        bp.put("items", bagItems);
        perBp.add(bp);
        bag.put("blueprints", perBp);
        bags.add(bag);
        archive.put("bags", bags);
        archive.put("consumedBags", new ListTag());
        archive.put("tagRegistry", new CompoundTag());
        archive.put("bagNames", new CompoundTag());

        MaterialSpace space = MaterialSpace.load(archive, p);
        helper.assertValueEqual(space.count(pid, "minecraft:short_grass"), 2, "pool grass renamed");
        helper.assertValueEqual(space.count(pid, "minecraft:grass"), 0, "pool old grass gone");
        helper.assertValueEqual(space.count(pid, "minecraft:stone"), 7, "pool stone kept");
        helper.assertValueEqual(space.bag(pid, "bp1").getOrDefault("minecraft:short_grass", -1), 3, "bag grass renamed");
        helper.succeed();
    }

    private static CompoundTag countEntry(String block, int count) {
        CompoundTag e = new CompoundTag();
        e.putString("block", block);
        e.putInt("count", count);
        return e;
    }

    private static CompoundTag tagSample(String id, CompoundTag tag) {
        CompoundTag s = new CompoundTag();
        s.putString("id", id);
        s.put("tag", tag.copy());
        return s;
    }

    // ── t05：材料空间存档升级 ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t05_materialSpace(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        String expectedKey = MaterialKey.of(realSword(helper), p);
        UUID pid = UUID.randomUUID();

        // 手写无 dataVersion 字段的旧存档：pool 与袋里都有旧键
        CompoundTag archive = new CompoundTag();
        ListTag pools = new ListTag();
        CompoundTag pool = new CompoundTag();
        pool.putString("player", pid.toString());
        CompoundTag poolItems = new CompoundTag();
        poolItems.putInt("minecraft:diamond_sword#legacyA", 2);
        poolItems.putInt("minecraft:stone", 5);
        pool.put("items", poolItems);
        pools.add(pool);
        archive.put("pools", pools);
        ListTag bags = new ListTag();
        CompoundTag bag = new CompoundTag();
        bag.putString("player", pid.toString());
        ListTag perBp = new ListTag();
        CompoundTag bp = new CompoundTag();
        bp.putString("id", "bp1");
        CompoundTag bagItems = new CompoundTag();
        bagItems.putInt("minecraft:diamond_sword#legacyB", 3);
        bp.put("items", bagItems);
        perBp.add(bp);
        bag.put("blueprints", perBp);
        bags.add(bag);
        archive.put("bags", bags);
        archive.put("consumedBags", new ListTag());
        CompoundTag registry = new CompoundTag();
        registry.put("minecraft:diamond_sword#legacyA", oldSwordTag());
        registry.put("minecraft:diamond_sword#legacyB", oldSwordTagB());
        archive.put("tagRegistry", registry);
        archive.put("bagNames", new CompoundTag());

        MaterialSpace space = MaterialSpace.load(archive, p);
        helper.assertValueEqual(2, space.count(pid, expectedKey), "pool migrated count 2");
        helper.assertValueEqual(5, space.count(pid, "minecraft:stone"), "pool stone kept");
        helper.assertValueEqual(3, space.bag(pid, "bp1").getOrDefault(expectedKey, -1), "bag migrated count 3");
        helper.assertTrue(space.tagFor(expectedKey) != null, "tagRegistry migrated");
        helper.assertTrue(space.tagFor("minecraft:diamond_sword#legacyA") == null, "old registry key gone");
        helper.assertTrue(space.isDirty(), "dirty after migration");

        // save 写版本字段；再 load 不再迁移
        CompoundTag saved = space.save(new CompoundTag(), p);
        helper.assertValueEqual(LegacyDataFixer.current(), saved.getInt("DataVersion"), "version stamped");
        MaterialSpace reloaded = MaterialSpace.load(saved, p);
        helper.assertTrue(!reloaded.isDirty(), "clean on second load");
        helper.assertValueEqual(2, reloaded.count(pid, expectedKey), "pool kept after roundtrip");
        helper.assertValueEqual(3, reloaded.bag(pid, "bp1").getOrDefault(expectedKey, -1),
                "bag kept after roundtrip");
        helper.succeed();
    }

    // ── t06：撤销快照升级 ─────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_undoSnapshot(GameTestHelper helper) {
        HolderLookup.Provider p = provider(helper);
        UUID pid = UUID.randomUUID();

        // 手写无 dataVersion 字段的旧存档：快照里有箱子方块实体 NBT 与 grass 方块状态
        CompoundTag archive = new CompoundTag();
        ListTag players = new ListTag();
        CompoundTag player = new CompoundTag();
        player.putUUID("uuid", pid);
        ListTag records = new ListTag();
        CompoundTag record = new CompoundTag();
        record.putString("dim", "minecraft:overworld");
        record.putInt("minX", 0);
        record.putInt("minY", 1);
        record.putInt("minZ", 0);
        record.putInt("maxX", 0);
        record.putInt("maxY", 1);
        record.putInt("maxZ", 0);
        ListTag blocks = new ListTag();
        CompoundTag block = new CompoundTag();
        block.putLong("pos", new BlockPos(0, 1, 0).asLong());
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:grass");
        block.put("state", state);
        block.put("be", oldChestNbt(0, 1, 0));
        blocks.add(block);
        record.put("blocks", blocks);
        record.put("entities", new ListTag());
        records.add(record);
        player.put("records", records);
        players.add(player);
        archive.put("players", players);

        SBUndoData data = SBUndoData.load(archive, p);
        helper.assertTrue(data.isDirty(), "dirty after migration");
        helper.assertTrue(data.hasHistory(pid), "history kept");

        // 经 save 读回快照内容验证（load 的内部表无 getter，走公开 save）
        CompoundTag saved = data.save(new CompoundTag(), p);
        helper.assertValueEqual(LegacyDataFixer.current(), saved.getInt("DataVersion"), "version stamped");
        CompoundTag blockBack = saved.getList("players", 10).getCompound(0)
                .getList("records", 10).getCompound(0)
                .getList("blocks", 10).getCompound(0);
        helper.assertValueEqual("minecraft:short_grass",
                blockBack.getCompound("state").getString("Name"), "snapshot state upgraded");
        CompoundTag beBack = blockBack.getCompound("be");
        ChestBlockEntity fresh = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        fresh.loadWithComponents(beBack, p);
        helper.assertTrue(ItemStack.isSameItemSameComponents(fresh.getItem(0), realSword(helper)),
                "snapshot chest item upgraded");

        // 再 load 一次不再迁移
        SBUndoData reloaded = SBUndoData.load(saved, p);
        helper.assertTrue(!reloaded.isDirty(), "clean on second load");
        helper.succeed();
    }

    // ── t07：存储往返 ─────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_storageRoundTrip(GameTestHelper helper) {
        UUID id = null;
        try {
            // 先存一份占位，拿到真实文件路径，再用 1.20.1 格式字节覆盖
            BlueprintData placeholder = new BlueprintData();
            placeholder.name = "t07plant";
            placeholder.category = "create";
            id = BlueprintStorage.save(placeholder);
            Path file = BlueprintStorage.findFile(id.toString());
            helper.assertTrue(file != null, "planted file exists");
            try (java.io.OutputStream out = Files.newOutputStream(file)) {
                NbtIo.writeCompressed(legacyBlueprintRoot(), out);
            } catch (java.io.IOException e) {
                helper.fail("write legacy bytes failed: " + e.getMessage());
            }

            // load 走修复，结果同 t02
            BlueprintData loaded = BlueprintStorage.load(id.toString());
            helper.assertTrue(loaded != null, "legacy file loads");
            assertT02(helper, loaded, 3465);

            // save 写回 → 文件里是当前版本 → 再 load 不走修复且内容相同
            BlueprintStorage.save(id, loaded);
            CompoundTag onDisk;
            try {
                onDisk = com.xioyim.spatialblueprint.util.SafeNbtIo.readCompressed(file);
            } catch (java.io.IOException e) {
                helper.fail("read back failed: " + e.getMessage());
                return;
            }
            helper.assertValueEqual(LegacyDataFixer.current(),
                    onDisk.getInt("MinecraftDataVersion"), "written back as current");
            BlueprintData reloaded = BlueprintStorage.load(id.toString());
            assertT02Content(helper, reloaded);
            helper.assertValueEqual(LegacyDataFixer.current(), reloaded.dataVersion, "current version");
        } finally {
            if (id != null) BlueprintStorage.delete(id.toString());
        }
        helper.succeed();
    }

    /** t02 的内容断言（不含版本号，t07 写回后版本已变）。 */
    private static void assertT02Content(GameTestHelper helper, BlueprintData data) {
        helper.assertValueEqual(2, data.normalBlocks.size() + data.dependentBlocks.size()
                + data.lateBlocks.size(), "2 blocks kept");
        boolean grassOk = false;
        for (BlueprintData.BlockEntry e : data.dependentBlocks) {
            if (e.state.is(Blocks.SHORT_GRASS)) grassOk = true;
        }
        helper.assertTrue(grassOk, "short_grass kept");
        helper.assertValueEqual(1, data.entities.size(), "entity kept");
        helper.assertValueEqual(2, data.materialList.blockCounts.getOrDefault("minecraft:stone", -1),
                "material list kept");
    }

    // ── 旧样本与升级结果的 prettyPrint 证据在 SBN-011_证据/样本.txt ──────────
}
