package com.xioyim.spatialblueprint.gametest;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariants;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SBN-002 净化器 GameTest（s01～s11）。
 * 方块实体 / 物品 / 实体 NBT 一律由真实对象序列化得到，不手写形状；
 * 唯一例外：s02(b) 的 components 拼接（被拼内容来自真实物品序列化）与 s04 的旧格式样本（向后兼容证明）。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN002Tests {

    private static HolderLookup.Provider provider(GameTestHelper helper) {
        return helper.getLevel().registryAccess();
    }

    /** 全树扫描：有没有只剩 slot/Slot、没有 id 也没有 item 的列表元素（净化残片）。 */
    private static boolean hasSlotOnlyElement(CompoundTag root) {
        List<Tag> cur = new ArrayList<>();
        cur.add(root);
        while (!cur.isEmpty()) {
            List<Tag> next = new ArrayList<>();
            for (Tag t : cur) {
                if (t instanceof CompoundTag ct) {
                    for (String k : ct.getAllKeys()) next.add(ct.get(k));
                } else if (t instanceof ListTag lt) {
                    for (Tag e : lt) {
                        if (e instanceof CompoundTag ce
                                && (ce.contains("slot") || ce.contains("Slot"))
                                && !ce.contains("id") && !ce.contains("item")) return true;
                        next.add(e);
                    }
                }
            }
            cur = next;
        }
        return false;
    }

    private static ChestBlockEntity chestWith(GameTestHelper helper, ItemStack... stacks) {
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        for (int i = 0; i < stacks.length; i++) chest.setItem(i, stacks[i]);
        return chest;
    }

    /** s01：多层嵌套的刷怪蛋（箱子→潜影盒物品→container 组件→刷怪蛋）。 */
    @GameTest(template = "empty")
    public static void s01_illegalItemDeep(GameTestHelper helper) {
        ItemStack egg = new ItemStack(Items.ZOMBIE_SPAWN_EGG);
        ItemStack inner = new ItemStack(Items.SHULKER_BOX);
        inner.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(egg)));
        CompoundTag nbt = chestWith(helper, inner).saveWithFullMetadata(provider(helper));
        helper.assertTrue(BlueprintSanitizer.containsIllegalItemDeep(nbt), "nested egg found");
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(nbt), "nested egg is dangerous");

        CompoundTag clean = chestWith(helper, new ItemStack(Items.STONE, 5)).saveWithFullMetadata(provider(helper));
        helper.assertFalse(BlueprintSanitizer.containsIllegalItemDeep(clean), "stone clean");
        helper.assertFalse(BlueprintSanitizer.hasDangerousBlockNbt(clean), "stone not dangerous");
        helper.succeed();
    }

    /** s02：容器净化无残片；components 夹带的 container 整条删除；读回不抛异常。 */
    @GameTest(template = "empty")
    public static void s02_containerNoResidue(GameTestHelper helper) {
        ShulkerBoxBlockEntity shulker = new ShulkerBoxBlockEntity(BlockPos.ZERO,
                Blocks.SHULKER_BOX.defaultBlockState());
        shulker.setItem(0, new ItemStack(Items.STONE, 5));
        CompoundTag stripped = BlueprintSanitizer.stripDangerousBlockNbt(
                shulker.saveWithFullMetadata(provider(helper)));
        helper.assertFalse(hasSlotOnlyElement(stripped), "no slot-only residue in shulker");
        ShulkerBoxBlockEntity back = new ShulkerBoxBlockEntity(BlockPos.ZERO,
                Blocks.SHULKER_BOX.defaultBlockState());
        back.loadWithComponents(stripped, provider(helper));
        helper.assertTrue(back.isEmpty(), "shulker empty after strip");

        CompoundTag chestStripped = BlueprintSanitizer.stripDangerousBlockNbt(
                chestWith(helper, new ItemStack(Items.STONE, 5)).saveWithFullMetadata(provider(helper)));
        helper.assertFalse(hasSlotOnlyElement(chestStripped), "no slot-only residue in chest");

        ItemStack shulkerItem = new ItemStack(Items.SHULKER_BOX);
        shulkerItem.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 3))));
        CompoundTag savedItem = (CompoundTag) shulkerItem.save(provider(helper));
        CompoundTag chestNbt = chestWith(helper).saveWithFullMetadata(provider(helper));
        chestNbt.put("components", savedItem.getCompound("components").copy());
        CompoundTag stripped2 = BlueprintSanitizer.stripDangerousBlockNbt(chestNbt);
        helper.assertFalse(stripped2.contains("components")
                && stripped2.getCompound("components").contains("minecraft:container"),
                "spliced container removed");
        ChestBlockEntity back2 = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        back2.loadWithComponents(stripped2, provider(helper));
        helper.assertTrue(back2.isEmpty(), "chest empty after strip");
        helper.succeed();
    }

    /** s03：KeepContents 口径（合法保留 / 混非法整箱倒空）。 */
    @GameTest(template = "empty")
    public static void s03_keepContents(GameTestHelper helper) {
        CompoundTag kept = BlueprintSanitizer.stripDangerousBlockNbtKeepContents(
                chestWith(helper, new ItemStack(Items.STONE, 5)).saveWithFullMetadata(provider(helper)));
        ListTag items = kept.getList("Items", 10);
        helper.assertTrue(items.size() == 1, "legal contents kept");
        helper.assertTrue(items.getCompound(0).getString("id").equals("minecraft:stone")
                && items.getCompound(0).getInt("count") == 5, "id+count intact");

        CompoundTag emptied = BlueprintSanitizer.stripDangerousBlockNbtKeepContents(
                chestWith(helper, new ItemStack(Items.STONE, 5),
                        new ItemStack(Items.ZOMBIE_SPAWN_EGG)).saveWithFullMetadata(provider(helper)));
        helper.assertFalse(emptied.contains("Items"), "mixed chest dumped");
        helper.succeed();
    }

    private static ItemStack writtenBook(GameTestHelper helper, Component... pages) {
        List<Filterable<Component>> list = new ArrayList<>();
        for (Component p : pages) list.add(Filterable.passThrough(p));
        WrittenBookContent content = new WrittenBookContent(Filterable.passThrough("T"), "A", 0, list, false);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    private static Component runCommandLine() {
        return Component.literal("boom").withStyle(
                s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/say hi")));
    }

    /** s04：讲台书（新格式真实成书 + 旧格式手写样本）。 */
    @GameTest(template = "empty")
    public static void s04_lecternBook(GameTestHelper helper) {
        CompoundTag bookNbt = (CompoundTag) writtenBook(helper, runCommandLine(),
                Component.literal("plain")).save(provider(helper));
        CompoundTag nbt = new LecternBlockEntity(BlockPos.ZERO,
                Blocks.LECTERN.defaultBlockState()).saveWithFullMetadata(provider(helper));
        nbt.put("Book", bookNbt);
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(nbt), "command book dangerous");
        helper.assertFalse(BlueprintSanitizer.stripDangerousBlockNbt(nbt).contains("Book"),
                "command book removed");

        CompoundTag plainBook = (CompoundTag) writtenBook(helper,
                Component.literal("just words")).save(provider(helper));
        CompoundTag nbt2 = new LecternBlockEntity(BlockPos.ZERO,
                Blocks.LECTERN.defaultBlockState()).saveWithFullMetadata(provider(helper));
        nbt2.put("Book", plainBook);
        helper.assertFalse(BlueprintSanitizer.hasDangerousBlockNbt(nbt2), "plain book clean");
        helper.assertTrue(BlueprintSanitizer.stripDangerousBlockNbt(nbt2).contains("Book"),
                "plain book kept");

        CompoundTag oldBook = new CompoundTag();
        oldBook.putString("id", "minecraft:written_book");
        oldBook.putByte("Count", (byte) 1);
        CompoundTag oldTag = new CompoundTag();
        ListTag pages = new ListTag();
        pages.add(StringTag.valueOf("{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/say hi\"}}"));
        oldTag.put("pages", pages);
        oldBook.put("tag", oldTag);
        CompoundTag nbtOld = new LecternBlockEntity(BlockPos.ZERO,
                Blocks.LECTERN.defaultBlockState()).saveWithFullMetadata(provider(helper));
        nbtOld.put("Book", oldBook);
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(nbtOld), "old format still caught");

        // (c) 裸字符串页面攻击形状（P1）：把真实成书存出的 pages 换成字符串列表，
        // 内容 = 原页 raw 的 JSON（含 run_command）。拼接允许手写，被拼内容是真实序列化。
        CompoundTag bareBook = (CompoundTag) writtenBook(helper, runCommandLine()).save(provider(helper));
        CompoundTag comps = bareBook.getCompound("components");
        CompoundTag wbc = comps.getCompound("minecraft:written_book_content");
        String rawJson = wbc.getList("pages", 10).getCompound(0).getString("raw");
        ListTag barePages = new ListTag();
        barePages.add(StringTag.valueOf(rawJson));
        wbc.put("pages", barePages);
        comps.put("minecraft:written_book_content", wbc);
        // 可达性：解码器确实接受裸字符串（Filterable.codec 的 withAlternative）
        Optional<ItemStack> reparsed = ItemStack.parse(provider(helper), bareBook);
        helper.assertTrue(reparsed.isPresent(), "bare-string pages decodable");
        WrittenBookContent parsedContent = reparsed.get().get(DataComponents.WRITTEN_BOOK_CONTENT);
        helper.assertTrue(parsedContent != null && parsedContent.pages().size() == 1, "one page parsed");
        Component parsedPage = parsedContent.pages().get(0).raw();
        helper.assertTrue(parsedPage.getStyle().getClickEvent() != null
                        && parsedPage.getStyle().getClickEvent().getAction() == ClickEvent.Action.RUN_COMMAND,
                "parsed page carries run_command");
        // 检测与净化
        CompoundTag bareLectern = new LecternBlockEntity(BlockPos.ZERO,
                Blocks.LECTERN.defaultBlockState()).saveWithFullMetadata(provider(helper));
        bareLectern.put("Book", bareBook);
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(bareLectern), "bare-string command book dangerous");
        helper.assertFalse(BlueprintSanitizer.stripDangerousBlockNbt(bareLectern).contains("Book"),
                "bare-string command book removed");
        helper.succeed();
    }

    /** s05：真实告示牌 run_command 点击事件 + 过滤文本（P2：filtered_messages 也检测、也净化）。 */
    @GameTest(template = "empty")
    public static void s05_signCommand(GameTestHelper helper) {
        SignBlockEntity sign = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
        // setText 会调 level.sendBlockUpdated，未进世界的方块实体 level 为 null，先挂上测试世界（只为调 API，不写世界残留）
        sign.setLevel(helper.getLevel());
        sign.setText(sign.getText(true).setMessage(0, runCommandLine()), true);
        CompoundTag nbt = sign.saveWithFullMetadata(provider(helper));
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(nbt), "sign command dangerous");
        CompoundTag stripped = BlueprintSanitizer.stripDangerousBlockNbt(nbt);
        String msg0 = stripped.getCompound("front_text").getList("messages", 8).getString(0);
        helper.assertTrue(msg0.contains("boom") && !msg0.contains("clickEvent"), "text kept, event gone");

        // 过滤文本路径：正常文本干净，命令只藏在 filtered_messages
        SignBlockEntity sign2 = new SignBlockEntity(BlockPos.ZERO, Blocks.OAK_SIGN.defaultBlockState());
        sign2.setLevel(helper.getLevel());
        sign2.setText(sign2.getText(true).setMessage(0, Component.literal("clean"), runCommandLine()), true);
        CompoundTag nbt2 = sign2.saveWithFullMetadata(provider(helper));
        helper.assertTrue(nbt2.getCompound("front_text").contains("filtered_messages"), "filtered_messages present");
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(nbt2), "filtered command dangerous");
        CompoundTag stripped2 = BlueprintSanitizer.stripDangerousBlockNbt(nbt2);
        String fm0 = stripped2.getCompound("front_text").getList("filtered_messages", 8).getString(0);
        helper.assertTrue(fm0.contains("boom") && !fm0.contains("clickEvent"), "filtered text kept, event gone");
        String m0 = stripped2.getCompound("front_text").getList("messages", 8).getString(0);
        helper.assertTrue(m0.contains("clean"), "clean normal text kept");
        helper.succeed();
    }

    /** s06：展示框（附魔剑重建成 {id,count} 且可解析；刷怪蛋整条删除）。 */
    @GameTest(template = "empty")
    public static void s06_itemFrame(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(provider(helper).lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        ItemFrame frame = new ItemFrame(EntityType.ITEM_FRAME, helper.getLevel(), BlockPos.ZERO, Direction.SOUTH);
        frame.setItem(sword);
        CompoundTag nbt = new CompoundTag();
        helper.assertTrue(frame.save(nbt), "frame saved");
        BlueprintSanitizer.sanitizeItemFrameContentInPlace(nbt);
        CompoundTag item = nbt.getCompound("Item");
        helper.assertTrue(item.getString("id").equals("minecraft:diamond_sword")
                && item.getInt("count") == 1
                && !item.contains("components") && !item.contains("tag"), "rebuilt {id,count}");
        helper.assertTrue(ItemStack.parse(provider(helper), item).isPresent(), "parseable");

        ItemFrame frame2 = new ItemFrame(EntityType.ITEM_FRAME, helper.getLevel(), BlockPos.ZERO, Direction.SOUTH);
        frame2.setItem(new ItemStack(Items.ZOMBIE_SPAWN_EGG));
        CompoundTag nbt2 = new CompoundTag();
        frame2.save(nbt2);
        BlueprintSanitizer.sanitizeItemFrameContentInPlace(nbt2);
        helper.assertFalse(nbt2.contains("Item"), "egg frame item removed");
        helper.succeed();
    }

    /** s07：真实刷怪笼 + 试炼刷怪笼（判危险 + 键清除 + D9 默认禁捕）。 */
    @GameTest(template = "empty")
    public static void s07_spawners(GameTestHelper helper) {
        SpawnerBlockEntity spawner = new SpawnerBlockEntity(BlockPos.ZERO, Blocks.SPAWNER.defaultBlockState());
        spawner.setEntityId(EntityType.ZOMBIE, helper.getLevel().getRandom());
        CompoundTag nbt = spawner.saveWithFullMetadata(provider(helper));
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(nbt), "spawner dangerous");
        CompoundTag stripped = BlueprintSanitizer.stripDangerousBlockNbt(nbt);
        helper.assertFalse(stripped.contains("SpawnData") || stripped.contains("SpawnPotentials"),
                "spawner config cleared");

        TrialSpawnerBlockEntity trial = new TrialSpawnerBlockEntity(BlockPos.ZERO,
                Blocks.TRIAL_SPAWNER.defaultBlockState());
        trial.setEntityId(EntityType.ZOMBIE, helper.getLevel().getRandom());
        CompoundTag tnbt = trial.saveWithFullMetadata(provider(helper));
        helper.assertTrue(BlueprintSanitizer.hasDangerousBlockNbt(tnbt), "trial spawner dangerous");
        CompoundTag tstripped = BlueprintSanitizer.stripDangerousBlockNbt(tnbt);
        helper.assertFalse(tstripped.contains("spawn_data") || tstripped.contains("normal_config")
                || tstripped.contains("ominous_config"), "trial config cleared");

        helper.assertTrue(BlueprintConfig.EXCLUDED_BLOCKS.get().contains("minecraft:trial_spawner")
                && BlueprintConfig.EXCLUDED_BLOCKS.get().contains("minecraft:vault"), "D9 block defaults");
        helper.assertTrue(BlueprintConfig.EXCLUDED_ITEMS.get().contains("minecraft:trial_spawner")
                && BlueprintConfig.EXCLUDED_ITEMS.get().contains("minecraft:vault"), "D9 item defaults");
        helper.assertTrue(BlueprintSanitizer.isIllegalBlockId("minecraft:trial_spawner"), "trial illegal block");
        helper.assertTrue(BlueprintConfig.isExcludedId("minecraft:trial_spawner")
                && BlueprintConfig.isExcludedId("minecraft:vault"), "excluded ids");
        helper.assertTrue(BlueprintSanitizer.isIllegalItemId("minecraft:vault"), "vault illegal item");
        helper.succeed();
    }

    /** s08：真实蜂箱（蜜蜂在 stripItemContents 后保留）。 */
    @GameTest(template = "empty")
    public static void s08_beesKept(GameTestHelper helper) {
        BeehiveBlockEntity hive = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
        hive.addOccupant(new Bee(EntityType.BEE, helper.getLevel()));
        CompoundTag nbt = hive.saveWithFullMetadata(provider(helper));
        helper.assertTrue(nbt.contains("bees") && nbt.getList("bees", 10).size() == 1, "one bee stored");
        CompoundTag stripped = BlueprintSanitizer.stripItemContents(nbt);
        helper.assertTrue(stripped.contains("bees") && stripped.getList("bees", 10).size() == 1, "bees kept");
        helper.succeed();
    }

    /** s09：盔甲架槽位实际形状 + hasEquipment 判定（P4 修正后断言）+ 手持非法判定。 */
    @GameTest(template = "empty")
    public static void s09_armorStandEquipment(GameTestHelper helper) {
        ArmorStand empty = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        CompoundTag n0 = new CompoundTag();
        empty.save(n0);
        int armorSize = n0.getList("ArmorItems", 10).size();
        int handSize = n0.getList("HandItems", 10).size();
        helper.assertTrue(armorSize == 4 && handSize == 2, "slot list shapes 4/2");

        BlueprintData d0 = new BlueprintData();
        d0.entities.add(new BlueprintData.EntityEntry(0, 0, 0, (byte) 9, "minecraft:armor_stand", n0));
        BlueprintSanitizer.UploadScan scan0 = BlueprintSanitizer.scanBlueprintData(d0, 8192L, 8);
        helper.assertTrue(scan0.droppedEntities().size() == 1, "facing 9 dropped");
        helper.assertFalse(scan0.droppedEntities().get(0).hasEquipment(), "empty stand hasEquipment=false");

        ArmorStand armed = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        armed.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        CompoundTag n1 = new CompoundTag();
        armed.save(n1);
        BlueprintData d1 = new BlueprintData();
        d1.entities.add(new BlueprintData.EntityEntry(0, 0, 0, (byte) 9, "minecraft:armor_stand", n1));
        BlueprintSanitizer.UploadScan scan1 = BlueprintSanitizer.scanBlueprintData(d1, 8192L, 8);
        helper.assertTrue(scan1.droppedEntities().size() == 1, "armed facing 9 dropped");
        helper.assertTrue(scan1.droppedEntities().get(0).hasEquipment(), "armed stand hasEquipment=true");

        ArmorStand eggStand = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        eggStand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.ZOMBIE_SPAWN_EGG));
        helper.assertTrue(BlueprintSanitizer.entityHasIllegalItems(eggStand), "egg hand illegal");
        ArmorStand swordStand = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        swordStand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        helper.assertFalse(BlueprintSanitizer.entityHasIllegalItems(swordStand), "sword hand clean");
        helper.succeed();
    }

    /** s10：战利品表（P3：两条 strip 清除、KeepContents 保留、components 夹带路径读回为 null）。 */
    @GameTest(template = "empty")
    public static void s10_lootTableStripped(GameTestHelper helper) {
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.parse("minecraft:chests/simple_dungeon")));
        chest.setLootTableSeed(123L);
        CompoundTag nbt = chest.saveWithFullMetadata(provider(helper));
        helper.assertTrue(nbt.contains("LootTable") && nbt.contains("LootTableSeed"), "loot recorded");

        CompoundTag afterItems = BlueprintSanitizer.stripItemContents(nbt);
        helper.assertFalse(afterItems.contains("LootTable") || afterItems.contains("LootTableSeed"),
                "stripItemContents clears loot");
        CompoundTag afterDanger = BlueprintSanitizer.stripDangerousBlockNbt(nbt);
        helper.assertFalse(afterDanger.contains("LootTable") || afterDanger.contains("LootTableSeed"),
                "stripDangerousBlockNbt clears loot");
        CompoundTag afterKeep = BlueprintSanitizer.stripDangerousBlockNbtKeepContents(nbt);
        helper.assertTrue(afterKeep.contains("LootTable") && afterKeep.contains("LootTableSeed"),
                "keepContents keeps loot");

        // components."minecraft:container_loot" 夹带路径：用真实 API 设置后由 ItemStack.save 序列化出的组件
        ItemStack carrier = new ItemStack(Items.CHEST);
        carrier.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(
                ResourceKey.create(Registries.LOOT_TABLE,
                        ResourceLocation.parse("minecraft:chests/simple_dungeon")), 123L));
        CompoundTag carrierNbt = (CompoundTag) carrier.save(provider(helper));
        helper.assertTrue(carrierNbt.getCompound("components").contains("minecraft:container_loot"),
                "container_loot component serialized");

        CompoundTag spliced = chestWith(helper).saveWithFullMetadata(provider(helper));
        spliced.put("components", carrierNbt.getCompound("components").copy());
        // 可达性：该组件经真实物品放置路径生效（BlockItem 放置方块时走 applyComponentsFromItemStack）
        ChestBlockEntity fromItem = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        fromItem.applyComponentsFromItemStack(carrier);
        helper.assertTrue(fromItem.getLootTable() != null, "component effective via item placement path");
        // loadWithComponents 只解析 components 进缓存，不调 applyImplicitComponents（那是物品放置路径）；
        // 这里读回为 null，仍按 fail-closed 清掉该组件（防其它加载路径与将来版本）。
        ChestBlockEntity reach = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        reach.loadWithComponents(spliced, provider(helper));
        helper.assertTrue(reach.getLootTable() == null, "loadWithComponents does not apply implicit components");

        CompoundTag splicedStripped = BlueprintSanitizer.stripItemContents(spliced);
        helper.assertFalse(splicedStripped.contains("components", 10)
                        && splicedStripped.getCompound("components").contains("minecraft:container_loot"),
                "container_loot removed");
        ChestBlockEntity back = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        back.loadWithComponents(splicedStripped, provider(helper));
        helper.assertTrue(back.getLootTable() == null, "loot table null after strip");
        helper.succeed();
    }

    /** s11：scan + sanitize 端到端（基岩/蛋箱/水/僵尸/画）。 */
    @GameTest(template = "empty")
    public static void s11_uploadScanEndToEnd(GameTestHelper helper) {
        List<String> origB = new ArrayList<>(BlueprintConfig.EXCLUDED_BLOCKS.get());
        List<String> origBulk = new ArrayList<>(GeneratedSubstitutionsConfig.BULK_CONVERSIONS.get());
        try {
            BlueprintConfig.EXCLUDED_BLOCKS.set(new ArrayList<>(List.of("minecraft:bedrock")));
            BlueprintConfig.clearCache();
            GeneratedSubstitutionsConfig.BULK_CONVERSIONS.set(new ArrayList<>(List.of(
                    "minecraft:water=3|minecraft:water_bucket*2",
                    "minecraft:lava=3|minecraft:lava_bucket*2")));
            GeneratedSubstitutionsConfig.invalidateCache();

            BlueprintData data = new BlueprintData();
            data.name = "s11";
            data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                    Blocks.BEDROCK.defaultBlockState(), null));
            CompoundTag chestNbt = chestWith(helper,
                    new ItemStack(Items.ZOMBIE_SPAWN_EGG)).saveWithFullMetadata(provider(helper));
            data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                    Blocks.CHEST.defaultBlockState(), chestNbt));
            for (int i = 0; i < 5; i++) {
                data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(2 + i, 0, 0),
                        Blocks.WATER.defaultBlockState(), null));
            }
            Zombie zombie = new Zombie(EntityType.ZOMBIE, helper.getLevel());
            CompoundTag znbt = new CompoundTag();
            zombie.save(znbt);
            data.entities.add(new BlueprintData.EntityEntry(0, 1, 0, (byte) 0, "minecraft:zombie", znbt));
            Painting painting = new Painting(EntityType.PAINTING, helper.getLevel());
            painting.setVariant(provider(helper).lookupOrThrow(Registries.PAINTING_VARIANT)
                    .getOrThrow(PaintingVariants.KEBAB));
            CompoundTag pnbt = new CompoundTag();
            painting.save(pnbt);
            data.entities.add(new BlueprintData.EntityEntry(2, 1, 0, (byte) 0, "minecraft:painting", pnbt));

            BlueprintSanitizer.UploadScan scan = BlueprintSanitizer.scanBlueprintData(data, 8192L, 8);
            helper.assertTrue(scan.illegalTotal() == 1, "one illegal");
            helper.assertTrue(scan.dangerousTotal() == 1, "one dangerous");
            helper.assertTrue(scan.droppedTotal() == 1, "one dropped");
            helper.assertTrue(scan.conversionsTotal() == 1
                    && scan.conversions().get(0).sourceBlockId().equals("minecraft:water"), "water conversion");

            BlueprintSanitizer.sanitizeForUploadInPlace(data, 8192L, 8);
            helper.assertTrue(data.normalBlocks.stream().noneMatch(
                    e -> e.state.getBlock() == Blocks.BEDROCK), "bedrock gone");
            BlueprintData.BlockEntry chestEntry = data.normalBlocks.stream()
                    .filter(e -> e.state.getBlock() == Blocks.CHEST).findFirst().orElseThrow();
            helper.assertTrue(chestEntry.nbt != null && !chestEntry.nbt.contains("Items"), "chest items gone");
            helper.assertTrue(data.entities.size() == 1
                    && data.entities.get(0).type.equals("minecraft:painting"), "only painting left");
        } finally {
            BlueprintConfig.EXCLUDED_BLOCKS.set(origB);
            BlueprintConfig.clearCache();
            GeneratedSubstitutionsConfig.BULK_CONVERSIONS.set(origBulk);
            GeneratedSubstitutionsConfig.invalidateCache();
        }
        helper.succeed();
    }
}
