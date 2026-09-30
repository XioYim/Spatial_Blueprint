package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.SpatialBlueprintRegistry;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.BuildBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintComponents;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_CreateEngineeringBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_CreateSubProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidRecaptureMessage;
import com.xioyim.spatialblueprint.network.CaptureAreaMessage;
import com.xioyim.spatialblueprint.network.CaptureProjectionMessage;
import com.xioyim.spatialblueprint.network.PlaceBlueprintMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintPlacementHandler;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.BlueprintTransform;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import com.xioyim.spatialblueprint.util.FluidDisplayMode;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import com.xioyim.spatialblueprint.util.SBUndoData;
import com.xioyim.spatialblueprint.util.UndoRecord;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SBN-012 放置引擎（P4）GameTest（t01～t08）。
 * 新格式数据由真实捕获产生；1.20.1 旧数据用 SBN011Tests 的手写样本；伪造 / 越界包是唯一允许手写处。
 * t05 / t06 的放置是逐 tick 动画：starter 与 checker 都排在后面（150+ / 300+ tick），
 * 避开批次开头各测试 tick-0 的 sink 快照窗口，不污染别的测试。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN012Tests {

    // ── 编解码 / 玩家辅助（同 SBN010 口径）─────────────────────────────────────

    private static RegistryFriendlyByteBuf newBuf(GameTestHelper helper) {
        return RegistryFriendlyByteBuf
                .decorator(helper.getLevel().registryAccess(), ConnectionType.OTHER)
                .apply(Unpooled.buffer());
    }

    private static <T extends CustomPacketPayload> byte[] encodeToBytes(
            StreamCodec<FriendlyByteBuf, T> codec, T msg, GameTestHelper helper) {
        RegistryFriendlyByteBuf buf = newBuf(helper);
        try {
            codec.encode(buf, msg);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static <T extends CustomPacketPayload> T decodeFromBytes(
            StreamCodec<FriendlyByteBuf, T> codec, byte[] bytes, GameTestHelper helper) {
        RegistryFriendlyByteBuf buf = newBuf(helper);
        try {
            buf.writeBytes(bytes);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static void assertRoundTrip(GameTestHelper helper,
                                        StreamCodec<FriendlyByteBuf, ?> codec, CustomPacketPayload msg) {
        @SuppressWarnings("unchecked")
        StreamCodec<FriendlyByteBuf, CustomPacketPayload> c = (StreamCodec<FriendlyByteBuf, CustomPacketPayload>) codec;
        byte[] once = encodeToBytes(c, msg, helper);
        CustomPacketPayload back = decodeFromBytes(c, once, helper);
        byte[] twice = encodeToBytes(c, back, helper);
        helper.assertTrue(Arrays.equals(once, twice), "byte-identical roundtrip: " + msg.getClass().getSimpleName());
    }

    private static void expectDecoderException(GameTestHelper helper, Runnable action, String what) {
        try {
            action.run();
            helper.fail("expected DecoderException: " + what);
        } catch (DecoderException expected) {
            // ok
        }
    }

    private static FriendlyByteBuf rawBuf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static FakePlayer fakePlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SB12" + UUID.randomUUID().toString().substring(0, 4)));
    }

    private static FakePlayer creativePlayer(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        player.setGameMode(GameType.CREATIVE);
        return player;
    }

    private static void grantOp(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().getOps().add(
                new ServerOpListEntry(player.getGameProfile(), 4, false));
    }

    private static void revokeOp(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
    }

    private static S2C_OperationResult firstOperationResult(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_OperationResult result) return result;
        }
        helper.fail("expected S2C_OperationResult");
        return null;
    }

    private static void giveBlankWithPoints(FakePlayer player, BlockPos p1, BlockPos p2) {
        ItemStack blank = new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get());
        BlankBlueprintItem.setPoint1(blank, p1);
        BlankBlueprintItem.setPoint2(blank, p2);
        player.setItemInHand(InteractionHand.MAIN_HAND, blank);
    }

    private static ChestBlockEntity placeChestWith(GameTestHelper helper, BlockPos rel, ItemStack... stacks) {
        helper.setBlock(rel, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(rel));
        if (chest == null) throw new IllegalStateException("chest BE missing at " + rel);
        for (int i = 0; i < stacks.length; i++) chest.setItem(i, stacks[i]);
        return chest;
    }

    private static ItemStack namedSword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Test Blade"));
        return sword;
    }

    private static ArmorStand spawnStand(GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        stand.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
        helper.getLevel().addFreshEntity(stand);
        return stand;
    }

    private static int totalBlocks(BlueprintData data) {
        return data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
    }

    private static String bpIdOf(ItemStack stack) {
        String id = AbstractBlueprintItem.getBlueprintId(stack);
        return id == null ? "" : id;
    }

    // ── 展示结构（t03 / t05 共用）：楼梯、箱子（剑）、告示牌、展示框、画、盔甲架 ──
    //
    // 选区 rel (1,1,1)-(4,3,3)。墙体计入选区（展示框 / 画的附着块）。

    private static void buildShowcase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.OAK_STAIRS);
        helper.setBlock(new BlockPos(1, 2, 2), Blocks.OAK_SIGN);
        SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(helper.absolutePos(new BlockPos(1, 2, 2)));
        if (sign == null) throw new IllegalStateException("sign BE missing");
        sign.setText(sign.getText(true).setMessage(0, Component.literal("Hello Blueprint")), true);
        placeChestWith(helper, new BlockPos(2, 2, 2), new ItemStack(Items.STONE, 5), namedSword(helper));
        helper.setBlock(new BlockPos(3, 2, 1), Blocks.STONE);
        ItemFrame frame = new ItemFrame(EntityType.ITEM_FRAME, level,
                helper.absolutePos(new BlockPos(3, 2, 1)), Direction.SOUTH);
        frame.setItem(new ItemStack(Items.DIAMOND));
        level.addFreshEntity(frame);
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        BlockPos paintTile = helper.absolutePos(new BlockPos(4, 2, 2));
        CompoundTag pnbt = new CompoundTag();
        pnbt.putString("id", "minecraft:painting");
        pnbt.putInt("TileX", paintTile.getX());
        pnbt.putInt("TileY", paintTile.getY());
        pnbt.putInt("TileZ", paintTile.getZ());
        pnbt.putByte("facing", (byte) Direction.NORTH.get2DDataValue());
        pnbt.putString("variant", "minecraft:kebab");
        net.minecraft.nbt.ListTag posList = new net.minecraft.nbt.ListTag();
        posList.add(net.minecraft.nbt.DoubleTag.valueOf(paintTile.getX() + 0.5));
        posList.add(net.minecraft.nbt.DoubleTag.valueOf(paintTile.getY() + 0.5));
        posList.add(net.minecraft.nbt.DoubleTag.valueOf(paintTile.getZ() + 0.5));
        pnbt.put("Pos", posList);
        Optional<Entity> painting = EntityType.create(pnbt, level);
        if (painting.isEmpty() || !(painting.get() instanceof Painting)) {
            throw new IllegalStateException("painting NBT did not create a painting");
        }
        level.addFreshEntity(painting.get());
        spawnStand(helper, new BlockPos(3, 2, 2));
    }

    private static void discardShowcaseEntities(GameTestHelper helper, BlockPos relMin, BlockPos relMax) {
        ServerLevel level = helper.getLevel();
        BlockPos absMin = helper.absolutePos(relMin);
        BlockPos absMax = helper.absolutePos(relMax);
        AABB box = new AABB(absMin.getX(), absMin.getY(), absMin.getZ(),
                absMax.getX() + 1.0, absMax.getY() + 1.0, absMax.getZ() + 1.0);
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, box, e -> true)) f.discard();
        for (Painting p : level.getEntitiesOfClass(Painting.class, box, e -> true)) p.discard();
        for (ArmorStand s : level.getEntitiesOfClass(ArmorStand.class, box, e -> true)) s.discard();
    }

    /** OP + 创造 + FORCE 捕获展示结构（不清源区），返回蓝图文件 id。 */
    private static String captureShowcase(GameTestHelper helper, String name) {
        FakePlayer op = creativePlayer(helper);
        boolean opped = false;
        try {
            grantOp(helper, op);
            opped = true;
            buildShowcase(helper);
            giveBlankWithPoints(op,
                    helper.absolutePos(new BlockPos(1, 1, 1)),
                    helper.absolutePos(new BlockPos(4, 3, 3)));
            CaptureAreaMessage.handleServer(new CaptureAreaMessage(name, false, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.FORCE, "", ""), op);
            ItemStack hand = op.getMainHandItem();
            if (hand.getItem() != SpatialBlueprintItems.SPATIAL_BLUEPRINT.get()) {
                throw new IllegalStateException("capture did not yield spatial blueprint");
            }
            String id = bpIdOf(hand);
            BlueprintValidator.clearCooldown(op.getUUID());
            return id;
        } finally {
            if (opped) revokeOp(helper, op);
            BlueprintValidator.clearCooldown(op.getUUID());
        }
    }

    /** 放置结果与蓝图逐项比对（方块状态、方块实体 NBT 去 x/y/z、实体类型与相对位置）。 */
    private static void verifyPlacement(GameTestHelper helper, BlueprintData data, BlockPos origin, Rotation rotation) {
        ServerLevel level = helper.getLevel();
        var provider = level.registryAccess();
        List<BlueprintData.BlockEntry> all = new ArrayList<>();
        all.addAll(data.normalBlocks);
        all.addAll(data.dependentBlocks);
        all.addAll(data.lateBlocks);
        for (BlueprintData.BlockEntry e : all) {
            BlockPos worldPos = origin.offset(
                    BlueprintTransform.x(e.pos.getX(), e.pos.getY(), e.pos.getZ(), data.size, rotation),
                    BlueprintTransform.y(e.pos.getX(), e.pos.getY(), e.pos.getZ(), data.size, rotation),
                    BlueprintTransform.z(e.pos.getX(), e.pos.getY(), e.pos.getZ(), data.size, rotation));
            BlockState expected = rotation == Rotation.NONE
                    ? e.state : e.state.rotate(level, worldPos, rotation);
            helper.assertTrue(level.getBlockState(worldPos).equals(expected), "state at " + worldPos);
            if (e.nbt != null && !e.nbt.isEmpty()) {
                BlockEntity be = level.getBlockEntity(worldPos);
                helper.assertTrue(be != null, "be present at " + worldPos);
                CompoundTag got = be.saveWithFullMetadata(provider);
                CompoundTag want = e.nbt.copy();
                for (String k : new String[]{"x", "y", "z"}) {
                    got.remove(k);
                    want.remove(k);
                }
                // SAFE 捕获会整体清掉容器内容（want 里没有 Items 键）：此时只比对剩余键，
                // 另断言放出的容器确实是空的；FORCE 保留内容的走逐字相等
                if (!want.contains("Items")) {
                    got.remove("Items");
                    helper.assertTrue(got.equals(want), "be nbt (stripped) at " + worldPos);
                } else {
                    helper.assertTrue(got.equals(want), "be nbt at " + worldPos);
                }
            }
        }
        for (BlueprintData.EntityEntry e : data.entities) {
            if (BlueprintConfig.getBoxEntities().contains(e.type)) continue; // 胶水需 Create，本任务不断言
            int wx = origin.getX() + BlueprintTransform.x(e.tileX, e.tileY, e.tileZ, data.size, rotation);
            int wy = origin.getY() + BlueprintTransform.y(e.tileX, e.tileY, e.tileZ, data.size, rotation);
            int wz = origin.getZ() + BlueprintTransform.z(e.tileX, e.tileY, e.tileZ, data.size, rotation);
            AABB cell = new AABB(wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0);
            if ("minecraft:painting".equals(e.type)) {
                List<Painting> found = level.getEntitiesOfClass(Painting.class, cell, x -> true);
                helper.assertTrue(!found.isEmpty(), "painting placed at " + wx + "," + wy + "," + wz + " ; entities near: "
                        + level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, cell.inflate(3), x -> true)
                                .stream().map(x -> x.getType().toString() + "@" + x.blockPosition()).toList());
                CompoundTag pn = new CompoundTag();
                found.get(0).save(pn);
                helper.assertTrue(pn.getInt("TileX") == wx && pn.getInt("TileY") == wy
                        && pn.getInt("TileZ") == wz, "painting tile");
                Direction expFacing = rotation.rotate(Direction.from2DDataValue(e.facing & 3));
                helper.assertTrue(pn.getByte("facing") == (byte) expFacing.get2DDataValue(), "painting facing");
                helper.assertTrue("minecraft:kebab".equals(pn.getString("variant")), "painting variant");
            } else if ("minecraft:item_frame".equals(e.type) || "minecraft:glow_item_frame".equals(e.type)) {
                List<ItemFrame> found = level.getEntitiesOfClass(ItemFrame.class, cell, x -> true);
                helper.assertTrue(!found.isEmpty(), "frame placed at " + wx + "," + wy + "," + wz);
                CompoundTag fn = new CompoundTag();
                found.get(0).save(fn);
                helper.assertTrue(fn.getInt("TileX") == wx && fn.getInt("TileY") == wy
                        && fn.getInt("TileZ") == wz, "frame tile");
                Direction expFacing = rotation.rotate(Direction.from3DDataValue(e.facing & 7));
                helper.assertTrue(fn.getByte("Facing") == (byte) expFacing.get3DDataValue(), "frame facing");
            } else if ("minecraft:armor_stand".equals(e.type)) {
                List<ArmorStand> found = level.getEntitiesOfClass(ArmorStand.class, cell, x -> true);
                helper.assertTrue(!found.isEmpty(), "stand placed at " + wx + "," + wy + "," + wz);
                helper.assertTrue(found.get(0).blockPosition().equals(new BlockPos(wx, wy, wz)), "stand tile");
            } else {
                helper.fail("unexpected entity type in showcase data: " + e.type);
            }
        }
    }

    // ── t01：注册与命名 ───────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_registration(GameTestHelper helper) {
        String[][] expected = {
                {"s2c_operation_result", com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult.TYPE.id().getPath()},
                {"s2c_blueprint_chunk_message", com.xioyim.spatialblueprint.network.S2C_BlueprintChunkMessage.TYPE.id().getPath()},
                {"s2c_blueprint_data_message", com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage.TYPE.id().getPath()},
                {"s2c_blueprint_data_fail_message", com.xioyim.spatialblueprint.network.S2C_BlueprintDataFailMessage.TYPE.id().getPath()},
                {"s2c_open_void_screen_message", com.xioyim.spatialblueprint.network.s2c.S2C_OpenVoidScreenMessage.TYPE.id().getPath()},
                {"s2c_illegal_blocks_list_message", com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.TYPE.id().getPath()},
                {"c2s_request_blueprint_data_message", com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage.TYPE.id().getPath()},
                {"c2s_discard_invalid_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DiscardInvalidBlueprintMessage.TYPE.id().getPath()},
                {"set_point_message", com.xioyim.spatialblueprint.network.SetPointMessage.TYPE.id().getPath()},
                {"clear_points_message", com.xioyim.spatialblueprint.network.ClearPointsMessage.TYPE.id().getPath()},
                {"set_blueprint_name_message", com.xioyim.spatialblueprint.network.SetBlueprintNameMessage.TYPE.id().getPath()},
                {"update_offset_message", com.xioyim.spatialblueprint.network.UpdateOffsetMessage.TYPE.id().getPath()},
                {"update_settings_message", com.xioyim.spatialblueprint.network.UpdateSettingsMessage.TYPE.id().getPath()},
                {"c2s_project_range_point_message", com.xioyim.spatialblueprint.network.C2S_ProjectRangePointMessage.TYPE.id().getPath()},
                {"c2s_request_config_message", com.xioyim.spatialblueprint.network.C2S_RequestConfigMessage.TYPE.id().getPath()},
                {"c2s_apply_config_message", com.xioyim.spatialblueprint.network.C2S_ApplyConfigMessage.TYPE.id().getPath()},
                {"s2c_config_sync_message", com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage.TYPE.id().getPath()},
                {"s2c_config_values_message", com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage.TYPE.id().getPath()},
                {"s2c_config_perm_message", com.xioyim.spatialblueprint.network.s2c.S2C_ConfigPermMessage.TYPE.id().getPath()},
                {"s2c_open_config_message", com.xioyim.spatialblueprint.network.s2c.S2C_OpenConfigMessage.TYPE.id().getPath()},
                {"c2s_request_library_message", com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage.TYPE.id().getPath()},
                {"s2c_library_sync_message", com.xioyim.spatialblueprint.network.S2C_LibrarySyncMessage.TYPE.id().getPath()},
                {"c2s_rename_blueprint_message", com.xioyim.spatialblueprint.network.C2S_RenameBlueprintMessage.TYPE.id().getPath()},
                {"c2s_convert_blueprint_message", com.xioyim.spatialblueprint.network.C2S_ConvertBlueprintMessage.TYPE.id().getPath()},
                {"c2s_set_withdraw_property_message", com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage.TYPE.id().getPath()},
                {"c2s_delete_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DeleteBlueprintMessage.TYPE.id().getPath()},
                {"s2c_void_bag_sync_message", com.xioyim.spatialblueprint.network.S2C_VoidBagSyncMessage.TYPE.id().getPath()},
                {"c2s_request_material_space_message", com.xioyim.spatialblueprint.network.C2S_RequestMaterialSpaceMessage.TYPE.id().getPath()},
                {"s2c_material_space_sync_message", com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage.TYPE.id().getPath()},
                {"c2s_material_deposit_message", com.xioyim.spatialblueprint.network.C2S_MaterialDepositMessage.TYPE.id().getPath()},
                {"c2s_request_blueprint_materials_message", com.xioyim.spatialblueprint.network.C2S_RequestBlueprintMaterialsMessage.TYPE.id().getPath()},
                {"s2c_blueprint_materials_message", com.xioyim.spatialblueprint.network.s2c.S2C_BlueprintMaterialsMessage.TYPE.id().getPath()},
                {"s2c_teleport_perm_message", com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage.TYPE.id().getPath()},
                {"s2c_world_identity_message", com.xioyim.spatialblueprint.network.S2C_WorldIdentityMessage.TYPE.id().getPath()},
                {"c2s_request_void_bags_message", com.xioyim.spatialblueprint.network.C2S_RequestVoidBagsMessage.TYPE.id().getPath()},
                {"c2s_discard_orphan_bag_message", com.xioyim.spatialblueprint.network.C2S_DiscardOrphanBagMessage.TYPE.id().getPath()},
                {"c2s_destroy_force_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DestroyForceBlueprintMessage.TYPE.id().getPath()},
                {"c2s_discard_foreign_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DiscardForeignBlueprintMessage.TYPE.id().getPath()},
                {"c2s_void_reset_message", com.xioyim.spatialblueprint.network.C2S_VoidResetMessage.TYPE.id().getPath()},
                {"c2s_void_teleport_message", com.xioyim.spatialblueprint.network.C2S_VoidTeleportMessage.TYPE.id().getPath()},
                {"c2s_engineering_teleport_message", com.xioyim.spatialblueprint.network.C2S_EngineeringTeleportMessage.TYPE.id().getPath()},
                {"c2s_safe_coord_message", com.xioyim.spatialblueprint.network.C2S_SafeCoordMessage.TYPE.id().getPath()},
                {"c2s_preview_update_message", com.xioyim.spatialblueprint.network.C2S_PreviewUpdateMessage.TYPE.id().getPath()},
                {"s2c_preview_broadcast_message", com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage.TYPE.id().getPath()},
                {"s2c_placement_progress_message", com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage.TYPE.id().getPath()},
                {"s2c_capture_cooldown_message", com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage.TYPE.id().getPath()},
                {"s2c_op_capture_confirm_message", com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage.TYPE.id().getPath()},
                {"c2s_request_project_list_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage.TYPE.id().getPath()},
                {"s2c_project_list_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage.TYPE.id().getPath()},
                {"c2s_request_project_entries_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectEntriesMessage.TYPE.id().getPath()},
                {"s2c_project_entries_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage.TYPE.id().getPath()},
                {"c2s_request_project_status_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage.TYPE.id().getPath()},
                {"s2c_project_status_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage.TYPE.id().getPath()},
                {"c2s_create_project_message", com.xioyim.spatialblueprint.network.C2S_CreateProjectMessage.TYPE.id().getPath()},
                {"c2s_delete_project_message", com.xioyim.spatialblueprint.network.C2S_DeleteProjectMessage.TYPE.id().getPath()},
                {"c2s_take_project_message", com.xioyim.spatialblueprint.network.C2S_TakeProjectMessage.TYPE.id().getPath()},
                {"c2s_submit_project_order_message", com.xioyim.spatialblueprint.network.C2S_SubmitProjectOrderMessage.TYPE.id().getPath()},
                {"c2s_submit_project_changes_message", com.xioyim.spatialblueprint.network.C2S_SubmitProjectChangesMessage.TYPE.id().getPath()},
                {"c2s_delete_sub_project_message", com.xioyim.spatialblueprint.network.C2S_DeleteSubProjectMessage.TYPE.id().getPath()},
                {"c2s_save_project_range_message", com.xioyim.spatialblueprint.network.C2S_SaveProjectRangeMessage.TYPE.id().getPath()},
                {"c2s_claim_sub_project_message", com.xioyim.spatialblueprint.network.C2S_ClaimSubProjectMessage.TYPE.id().getPath()},
                {"c2s_apply_blueprint_message", com.xioyim.spatialblueprint.network.C2S_ApplyBlueprintMessage.TYPE.id().getPath()},
                {"c2s_save_material_list_message", com.xioyim.spatialblueprint.network.C2S_SaveMaterialListMessage.TYPE.id().getPath()},
                {"c2s_reward_materials_message", com.xioyim.spatialblueprint.network.C2S_RewardMaterialsMessage.TYPE.id().getPath()},
                {"c2s_request_illegal_blocks_message", com.xioyim.spatialblueprint.network.C2S_RequestIllegalBlocksMessage.TYPE.id().getPath()},
                {"c2s_upload_begin_message", com.xioyim.spatialblueprint.network.C2S_UploadBeginMessage.TYPE.id().getPath()},
                {"c2s_upload_chunk_message", com.xioyim.spatialblueprint.network.C2S_UploadChunkMessage.TYPE.id().getPath()},
                {"c2s_upload_projection_template_message", com.xioyim.spatialblueprint.network.C2S_UploadProjectionTemplateMessage.TYPE.id().getPath()},
                {"c2s_confirm_upload_message", com.xioyim.spatialblueprint.network.C2S_ConfirmUploadMessage.TYPE.id().getPath()},
                {"c2s_request_engineering_report_message", com.xioyim.spatialblueprint.network.C2S_RequestEngineeringReportMessage.TYPE.id().getPath()},
                {"s2c_upload_report_message", com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage.TYPE.id().getPath()},
                {"capture_area_message", CaptureAreaMessage.TYPE.id().getPath()},
                {"capture_projection_message", CaptureProjectionMessage.TYPE.id().getPath()},
                {"c2s_void_recapture_message", C2S_VoidRecaptureMessage.TYPE.id().getPath()},
                {"c2s_create_engineering_blueprint_message", C2S_CreateEngineeringBlueprintMessage.TYPE.id().getPath()},
                {"c2s_create_sub_project_message", C2S_CreateSubProjectMessage.TYPE.id().getPath()},
                // SBN-012 的 1 个（C2S，与 1.20.1 归类一致）
                {"place_blueprint_message", PlaceBlueprintMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[1], pair[0], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(ids.size(), 77, "77 unique payload ids");
        helper.assertValueEqual(PlaceBlueprintMessage.TYPE.id().getNamespace(),
                SpatialBlueprintMod.MODID, "namespace");
        helper.succeed();
    }

    // ── t02：编解码与解码守卫 ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codec(GameTestHelper helper) {
        assertRoundTrip(helper, PlaceBlueprintMessage.STREAM_CODEC,
                new PlaceBlueprintMessage(new BlockPos(10, 64, -30), Rotation.CLOCKWISE_90,
                        5, -512, 512, true, FluidDisplayMode.HIDE_VANILLA_FILTER));
        // rotation 字符串超 32 字节 → 解码拒收（1.20.1 readUtf 上限语义）
        expectDecoderException(helper, () -> {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeBlockPos(BlockPos.ZERO);
                buf.writeUtf("R".repeat(40));
                PlaceBlueprintMessage.decode(buf);
            } finally {
                buf.release();
            }
        }, "rotation over 32 bytes");
        // 未知 rotation 名 → NONE
        {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeBlockPos(BlockPos.ZERO);
                buf.writeUtf("SPIN", 32);
                buf.writeInt(0);
                buf.writeInt(0);
                buf.writeInt(0);
                buf.writeBoolean(true);
                buf.writeByte(FluidDisplayMode.SHOW_ALL.toByte());
                PlaceBlueprintMessage back = PlaceBlueprintMessage.decode(buf);
                helper.assertTrue(back.rotation == Rotation.NONE, "unknown rotation falls back to NONE");
            } finally {
                buf.release();
            }
        }
        // fluidMode 非法字节 → fromByte 回退 SHOW_ALL
        {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeBlockPos(BlockPos.ZERO);
                buf.writeUtf("NONE", 32);
                buf.writeInt(0);
                buf.writeInt(0);
                buf.writeInt(0);
                buf.writeBoolean(true);
                buf.writeByte((byte) 99);
                PlaceBlueprintMessage back = PlaceBlueprintMessage.decode(buf);
                helper.assertTrue(back.fluidMode == FluidDisplayMode.SHOW_ALL, "bad fluidMode falls back");
            } finally {
                buf.release();
            }
        }
        helper.succeed();
    }

    // ── t03：新格式往返（真实捕获 → placeImmediately）──────────────────────────

    @GameTest(template = "empty")
    public static void t03_roundTripNew(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> files = new ArrayList<>();
        try {
            String id = captureShowcase(helper, "t03-show");
            files.add(id);
            BlueprintData data = BlueprintStorage.load(id);
            helper.assertTrue(data != null && totalBlocks(data) > 0, "showcase captured");
            helper.assertTrue(data.entities.size() == 3, "frame + painting + stand captured: "
                    + data.entities.stream().map(en -> en.type + "@" + en.tileX + "," + en.tileY + "," + en.tileZ).toList());
            // 源区装饰实体先清：NONE 直接盖在源区上放，clearFirst 不清盔甲架，避免新旧两只
            discardShowcaseEntities(helper, new BlockPos(1, 1, 1), new BlockPos(4, 3, 3));

            BlockPos t1 = helper.absolutePos(new BlockPos(1, 1, 1));
            BlueprintPlacementHandler.placeImmediately(level, t1, data, Rotation.NONE, null, true);
            verifyPlacement(helper, data, t1, Rotation.NONE);
            // 楼梯朝向：NONE 保持北（楼梯在数据坐标 (1,1,0)）
            BlockPos stairsT1 = t1.offset(
                    BlueprintTransform.x(1, 1, 0, data.size, Rotation.NONE),
                    BlueprintTransform.y(1, 1, 0, data.size, Rotation.NONE),
                    BlueprintTransform.z(1, 1, 0, data.size, Rotation.NONE));
            helper.assertTrue(level.getBlockState(stairsT1).getValue(StairBlock.FACING) == Direction.NORTH,
                    "stairs still north after NONE");

            // 90° 放在同一原点：clearFirst 先清掉 NONE 的产物，旋转后包围盒仍在自家格内
            BlueprintPlacementHandler.placeImmediately(level, t1, data, Rotation.CLOCKWISE_90, null, true);
            verifyPlacement(helper, data, t1, Rotation.CLOCKWISE_90);
            // 楼梯朝向按 rotate 结果：北 → 东
            BlockPos stairsT2 = t1.offset(
                    BlueprintTransform.x(1, 1, 0, data.size, Rotation.CLOCKWISE_90),
                    BlueprintTransform.y(1, 1, 0, data.size, Rotation.CLOCKWISE_90),
                    BlueprintTransform.z(1, 1, 0, data.size, Rotation.CLOCKWISE_90));
            helper.assertTrue(level.getBlockState(stairsT2).getValue(StairBlock.FACING) == Direction.EAST,
                    "stairs east after CLOCKWISE_90");
        } finally {
            discardShowcaseEntities(helper, new BlockPos(1, 1, 1), new BlockPos(4, 3, 3));
            for (String id : files) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t04：旧格式往返（手写 1.20.1 蓝图 → placeImmediately）──────────────────

    @GameTest(template = "empty")
    public static void t04_roundTripLegacy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlueprintData data = BlueprintData.deserialize(SBN011Tests.legacyBlueprintRoot());
        // 旧样本 2x1x1 放在源区正上方 virgin 空域（同柱必加载、无邻居干扰）；
        // 短草下方垫泥土：相邻箱子放下时的邻居更新会把无支撑植被弹出
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.DIRT);
        helper.setBlock(new BlockPos(2, 3, 1), Blocks.DIRT);
        BlockPos target = helper.absolutePos(new BlockPos(1, 4, 1));
        BlueprintPlacementHandler.placeImmediately(level, target, data, Rotation.NONE, null, true);
        // 旧 grass 落为 short_grass
        helper.assertTrue(level.getBlockState(target).is(Blocks.SHORT_GRASS), "legacy grass is short_grass");
        // 箱子第 0 格是带锋利 III 且有名字的钻石剑
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(target.offset(1, 0, 0));
        helper.assertTrue(chest != null, "legacy chest placed");
        helper.assertTrue(ItemStack.isSameItemSameComponents(chest.getItem(0), namedSword(helper)),
                "legacy chest sword upgraded");
        helper.assertValueEqual(chest.getItem(0).get(DataComponents.CUSTOM_NAME).getString(),
                "Test Blade", "legacy sword name");
        // 展示框物品正确
        AABB box = new AABB(target.getX(), target.getY(), target.getZ(),
                target.getX() + 2.0, target.getY() + 1.0, target.getZ() + 1.0);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, box, f -> true);
        helper.assertTrue(!frames.isEmpty(), "legacy frame placed");
        helper.assertTrue(ItemStack.isSameItemSameComponents(frames.get(0).getItem(), namedSword(helper)),
                "legacy frame item upgraded");
        helper.succeed();
    }

    // ── t05：动画放置（生存假玩家走 handleServer 全路径）───────────────────────
    //
    // starter@150 做放置（避开批次开头各测试的 tick-0 sink 窗口），checker@260 断言。

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void t05_animated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final List<String> files = new ArrayList<>();
        final Map<UUID, List<CustomPacketPayload>> byTarget = new HashMap<>();
        String id = captureShowcase(helper, "t05-show");
        files.add(id);
        BlueprintData data = BlueprintStorage.load(id);
        if (data == null) {
            helper.fail("showcase capture failed");
            return;
        }
        final BlueprintData shared = data;
        // 源区装饰实体先清：starter 在源区原点做动画放置，handleServer 的实体阻挡检查不允许 LivingEntity
        discardShowcaseEntities(helper, new BlockPos(1, 1, 1), new BlockPos(4, 3, 3));
        FakePlayer survivor = fakePlayer(helper);
        // 源区正上方 virgin 空域（rel y≥4：没有任何测试在该高度施工，同柱必加载）
        final BlockPos target = helper.absolutePos(new BlockPos(1, 4, 1));
        survivor.setPos(target.getX() + 2.5, target.getY() + 2.0, target.getZ() + 2.5);
        // 强加载基线：测试服框架本来就强制加载测试区块（放置的 newlyForcedChunks 为空），
        // 正确口径是放置前后强制集不变（无泄漏），而不是集合为空
        final Set<Long> forcedBefore = new HashSet<>();
        survivor.setPos(target.getX() + 2.5, target.getY() + 2.0, target.getZ() + 2.5);
        ItemStack spatial = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
        AbstractBlueprintItem.setBlueprintId(spatial, id);
        survivor.setItemInHand(InteractionHand.MAIN_HAND, spatial);
        final FakePlayer placed = survivor;

        helper.runAtTickTime(150, () -> {
            BlueprintNetwork.fakePlayerSink = (p, payload) ->
                    byTarget.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(payload);
            level.getForcedChunks().forEach((long v) -> forcedBefore.add(v));
            BlueprintValidator.clearCooldown(placed.getUUID());
            PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(target, Rotation.NONE,
                    0, 0, 0, true, FluidDisplayMode.SHOW_ALL), placed);
            ItemStack hand = placed.getMainHandItem();
            helper.assertTrue(hand.isEmpty(), "survival spatial consumed");
            S2C_OperationResult ok = null;
            for (CustomPacketPayload payload : byTarget.getOrDefault(placed.getUUID(), List.of())) {
                if (payload instanceof S2C_OperationResult r) ok = r;
            }
            helper.assertTrue(ok != null && ok.success, "placement accepted");
        });

        helper.runAtTickTime(260, () -> {
            try {
                // 进度包先查：p = 1 到过 = forcePlaceAllBlocks 跑完（含释放强加载）
                boolean mid = false;
                boolean done = false;
                for (CustomPacketPayload payload : byTarget.getOrDefault(placed.getUUID(), List.of())) {
                    if (payload instanceof S2C_PlacementProgressMessage pm) {
                        if (pm.progress > 0f && pm.progress < 1f) mid = true;
                        if (pm.progress == 1f) done = true;
                    }
                }
                helper.assertTrue(mid, "saw 0 < p < 1 progress");
                helper.assertTrue(done, "saw p = 1 progress");
                verifyPlacement(helper, shared, target, Rotation.NONE);
                // render_block 无残留
                int[] bbox = BlueprintPlacementHandler.computeWorldBBox(target, shared.size, Rotation.NONE);
                for (int y = bbox[1]; y <= bbox[4]; y++) {
                    for (int x = bbox[0]; x <= bbox[3]; x++) {
                        for (int z = bbox[2]; z <= bbox[5]; z++) {
                            helper.assertTrue(!level.getBlockState(new BlockPos(x, y, z))
                                    .is(SpatialBlueprintRegistry.RENDER_BLOCK.get()), "no render block at "
                                    + x + "," + y + "," + z);
                        }
                    }
                }
                // 本次放置无强加载泄漏：前后强制集一致（框架基线本身非空，不断言为空）
                Set<Long> forcedAfter = new HashSet<>();
                level.getForcedChunks().forEach((long v) -> forcedAfter.add(v));
                helper.assertTrue(forcedAfter.equals(forcedBefore), "no forced-chunk leak");
            } finally {
                BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
                BlueprintValidator.clearCooldown(placed.getUUID());
                discardShowcaseEntities(helper, new BlockPos(1, 1, 1), new BlockPos(4, 3, 3));
                for (String fid : files) BlueprintStorage.delete(fid);
            }
            helper.succeed();
        });
    }

    // ── t06：放置消息各分支 ────────────────────────────────────────────────────
    //
    // tick-0 只准备（捕获 / 注资 / 摆物品），starter@300 统一走 handleServer，
    // checker@420 断言世界。避开批次开头的 sink 窗口。

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void t06_placeMessage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MaterialSpace space = MaterialSpace.get(level);
        final List<String> files = new ArrayList<>();
        final Map<UUID, List<CustomPacketPayload>> byTarget = new HashMap<>();

        // —— 准备：空间蓝图（OP FORCE 捕获，不清源） ——. pairing region rel (1,2,1)-(2,2,2)
        FakePlayer op = creativePlayer(helper);
        boolean opped = false;
        try {
            grantOp(helper, op);
            opped = true;
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(1, 2, 2), Blocks.STONE);
            placeChestWith(helper, new BlockPos(2, 2, 2), new ItemStack(Items.STONE, 3));
            giveBlankWithPoints(op,
                    helper.absolutePos(new BlockPos(1, 2, 1)),
                    helper.absolutePos(new BlockPos(2, 2, 2)));
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t06-space", false, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.FORCE, "", ""), op);
            String spatialId = bpIdOf(op.getMainHandItem());
            if (spatialId.isEmpty()) {
                helper.fail("spatial capture failed");
                return;
            }
            files.add(spatialId);
            final BlueprintData dataA = BlueprintStorage.load(spatialId);
            final String sharedSpatialId = spatialId;

            // —— 准备：投影蓝图（生存捕获） ——
            FakePlayer survivorP = fakePlayer(helper);
            giveBlankWithPoints(survivorP,
                    helper.absolutePos(new BlockPos(1, 2, 1)),
                    helper.absolutePos(new BlockPos(2, 2, 2)));
            CaptureProjectionMessage.handleServer(
                    new CaptureProjectionMessage("t06-proj", 5, CaptureMode.SAFE, "", "", false), survivorP);
            String projId = bpIdOf(survivorP.getMainHandItem());
            if (projId.isEmpty()) {
                helper.fail("projection capture failed");
                return;
            }
            files.add(projId);
            final BlueprintData projData = BlueprintStorage.load(projId);
            final MaterialList projMl = projData.materialList;
            if (projMl == null || projMl.blockCounts.isEmpty()) {
                helper.fail("projection has no material list");
                return;
            }
            final String sharedProjId = projId;
            final CompoundTag projBytes = projData.serialize();

            // —— 准备：次元蓝图（生存捕获，清源入袋） ——
            FakePlayer owner = fakePlayer(helper);
            giveBlankWithPoints(owner,
                    helper.absolutePos(new BlockPos(1, 2, 1)),
                    helper.absolutePos(new BlockPos(2, 2, 2)));
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t06-dim", true, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), owner);
            String dimId = bpIdOf(owner.getMainHandItem());
            if (dimId.isEmpty()) {
                helper.fail("dimensional capture failed");
                return;
            }
            files.add(dimId);
            final String sharedDimId = dimId;
            final CompoundTag dimBytes = BlueprintStorage.load(dimId).serialize();
            final int bagBefore = space.bagTotal(owner.getUUID(), dimId);
            if (bagBefore <= 0) {
                helper.fail("dimensional bag not funded");
                return;
            }

            // —— 摆物品与站位（全在自家格内：源区 x1-2/z1-2，放置点 x1-5/z1-6） ——
            FakePlayer survivorS = fakePlayer(helper);
            ItemStack spatialS = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(spatialS, sharedSpatialId);
            survivorS.setItemInHand(InteractionHand.MAIN_HAND, spatialS);
            final BlockPos targetA = helper.absolutePos(new BlockPos(3, 2, 1));
            survivorS.setPos(targetA.getX() + 1.5, targetA.getY() + 2.0, targetA.getZ() + 1.5);

            final BlockPos targetB = helper.absolutePos(new BlockPos(3, 2, 3));
            survivorP.setPos(targetB.getX() + 1.5, targetB.getY() + 2.0, targetB.getZ() + 1.5);

            FakePlayer stranger = fakePlayer(helper);
            ItemStack strangerStack = new ItemStack(SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(strangerStack, sharedDimId);
            stranger.setItemInHand(InteractionHand.MAIN_HAND, strangerStack);
            final BlockPos targetC = helper.absolutePos(new BlockPos(1, 8, 1));
            stranger.setPos(targetC.getX() + 1.5, targetC.getY() + 2.0, targetC.getZ() + 1.5);

            FakePlayer opStranger = fakePlayer(helper);
            ItemStack opStrangerStack = new ItemStack(SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(opStrangerStack, sharedDimId);
            opStranger.setItemInHand(InteractionHand.MAIN_HAND, opStrangerStack);
            opStranger.setPos(targetC.getX() + 1.5, targetC.getY() + 2.0, targetC.getZ() + 1.5);

            final BlockPos targetD = helper.absolutePos(new BlockPos(1, 4, 1));
            owner.setPos(targetD.getX() + 1.5, targetD.getY() + 2.0, targetD.getZ() + 1.5);

            FakePlayer creative = creativePlayer(helper);
            ItemStack spatialC = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(spatialC, sharedSpatialId);
            creative.setItemInHand(InteractionHand.MAIN_HAND, spatialC);
            final BlockPos targetE = helper.absolutePos(new BlockPos(1, 6, 1));
            creative.setPos(targetE.getX() + 1.5, targetE.getY() + 2.0, targetE.getZ() + 1.5);

            final FakePlayer fSurvivorS = survivorS;
            final FakePlayer fSurvivorP = survivorP;
            final FakePlayer fStranger = stranger;
            final FakePlayer fOpStranger = opStranger;
            final FakePlayer fOwner = owner;
            final FakePlayer fCreative = creative;

            helper.runAtTickTime(300, () -> {
                BlueprintNetwork.fakePlayerSink = (p, payload) ->
                        byTarget.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(payload);

                // B1 生存空间蓝图 → 结构放出、主手清空
                BlueprintValidator.clearCooldown(fSurvivorS.getUUID());
                PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetA, Rotation.NONE,
                        0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fSurvivorS);
                S2C_OperationResult b1 = firstOperationResult(helper,
                        byTarget.getOrDefault(fSurvivorS.getUUID(), List.of()));
                helper.assertTrue(b1.success, "B1 spatial accepted");
                helper.assertTrue(fSurvivorS.getMainHandItem().isEmpty(), "B1 hand cleared");

                // B2a 生存投影蓝图、材料不够 → insufficient_materials，四样不变
                BlueprintValidator.clearCooldown(fSurvivorP.getUUID());
                helper.assertTrue(space.poolTotal(fSurvivorP.getUUID()) == 0, "B2a pool starts empty");
                PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetB, Rotation.NONE,
                        0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fSurvivorP);
                S2C_OperationResult b2a = firstOperationResult(helper,
                        byTarget.getOrDefault(fSurvivorP.getUUID(), List.of()));
                helper.assertTrue(!b2a.success, "B2a denied");
                helper.assertValueEqual(b2a.messageKey,
                        "spatial_blueprint.error.insufficient_materials", "B2a key");
                helper.assertValueEqual(sharedProjId, bpIdOf(fSurvivorP.getMainHandItem()), "B2a hand kept");
                helper.assertTrue(space.poolTotal(fSurvivorP.getUUID()) == 0, "B2a pool untouched");
                helper.assertTrue(BlueprintStorage.load(sharedProjId).serialize().equals(projBytes),
                        "B2a file untouched");
                helper.assertTrue(level.getBlockState(targetB).isAir(), "B2a world untouched");

                // 注资 → B2b 材料够 → 扣除、主手变建造蓝图
                for (Map.Entry<String, Integer> e : projMl.blockCounts.entrySet()) {
                    int accepted = space.deposit(fSurvivorP.getUUID(), e.getKey(), e.getValue(),
                            projMl.keyTags.get(e.getKey()));
                    helper.assertTrue(accepted == e.getValue(), "B2b funded " + e.getKey());
                }
                Map<String, Integer> beforePool = new HashMap<>(space.pool(fSurvivorP.getUUID()));
                BlueprintValidator.clearCooldown(fSurvivorP.getUUID());
                PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetB, Rotation.NONE,
                        0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fSurvivorP);
                S2C_OperationResult b2b = null;
                for (CustomPacketPayload payload : byTarget.getOrDefault(fSurvivorP.getUUID(), List.of())) {
                    if (payload instanceof S2C_OperationResult r && r.success) b2b = r;
                }
                helper.assertTrue(b2b != null, "B2b projection accepted");
                ItemStack buildB = fSurvivorP.getMainHandItem();
                helper.assertTrue(buildB.getItem() == SpatialBlueprintItems.BUILD_BLUEPRINT.get(),
                        "B2b hand became build blueprint");
                helper.assertValueEqual(buildB.get(SpatialBlueprintComponents.SOURCE_TYPE.get()),
                        "project", "B2b source project");
                helper.assertValueEqual(sharedProjId,
                        buildB.get(SpatialBlueprintComponents.BLUEPRINT_ID.get()), "B2b id carried");
                for (Map.Entry<String, Integer> e : projMl.blockCounts.entrySet()) {
                    int spent = beforePool.getOrDefault(e.getKey(), 0)
                            - space.pool(fSurvivorP.getUUID()).getOrDefault(e.getKey(), 0);
                    helper.assertTrue(spent == e.getValue(), "B2b deducted " + e.getKey());
                }
                ItemStack restoredB = BuildBlueprintItem.restore(buildB);
                helper.assertTrue(restoredB.getItem() == SpatialBlueprintItems.PROJECTION_BLUEPRINT.get(),
                        "B2b restores to projection");
                helper.assertValueEqual(sharedProjId, bpIdOf(restoredB), "B2b restored id");

                // B3a 次元蓝图非主人（含 B3b OP）→ not_owner，四样不变
                BlueprintValidator.clearCooldown(fStranger.getUUID());
                PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetC, Rotation.NONE,
                        0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fStranger);
                S2C_OperationResult b3a = firstOperationResult(helper,
                        byTarget.getOrDefault(fStranger.getUUID(), List.of()));
                helper.assertValueEqual(b3a.messageKey,
                        "spatial_blueprint.message.void_blueprint.not_owner", "B3a key");
                helper.assertTrue(level.getBlockState(targetC).isAir(), "B3a world untouched");
                helper.assertValueEqual(sharedDimId, bpIdOf(fStranger.getMainHandItem()), "B3a hand kept");
                helper.assertTrue(space.bagTotal(fOwner.getUUID(), sharedDimId) == bagBefore, "B3a bag kept");
                helper.assertTrue(BlueprintStorage.load(sharedDimId).serialize().equals(dimBytes),
                        "B3a file untouched");

                grantOp(helper, fOpStranger);
                try {
                    BlueprintValidator.clearCooldown(fOpStranger.getUUID());
                    PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetC, Rotation.NONE,
                            0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fOpStranger);
                    S2C_OperationResult b3b = firstOperationResult(helper,
                            byTarget.getOrDefault(fOpStranger.getUUID(), List.of()));
                    helper.assertValueEqual(b3b.messageKey,
                            "spatial_blueprint.message.void_blueprint.not_owner", "B3b OP still not owner");
                    helper.assertTrue(space.bagTotal(fOwner.getUUID(), sharedDimId) == bagBefore, "B3b bag kept");
                } finally {
                    revokeOp(helper, fOpStranger);
                }

                // B4 主人生存 → 次元袋消耗、文件 placed、主手变建造蓝图
                BlueprintValidator.clearCooldown(fOwner.getUUID());
                PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetD, Rotation.NONE,
                        0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fOwner);
                S2C_OperationResult b4 = null;
                for (CustomPacketPayload payload : byTarget.getOrDefault(fOwner.getUUID(), List.of())) {
                    if (payload instanceof S2C_OperationResult r && r.success) b4 = r;
                }
                helper.assertTrue(b4 != null, "B4 dimensional accepted");
                helper.assertTrue(space.bagTotal(fOwner.getUUID(), sharedDimId) == 0, "B4 bag consumed");
                helper.assertTrue(BlueprintStorage.load(sharedDimId).placed, "B4 file marked placed");
                ItemStack buildD = fOwner.getMainHandItem();
                helper.assertTrue(buildD.getItem() == SpatialBlueprintItems.BUILD_BLUEPRINT.get(),
                        "B4 hand became build blueprint");
                helper.assertValueEqual(buildD.get(SpatialBlueprintComponents.SOURCE_TYPE.get()),
                        "void_dimensional", "B4 source void_dimensional");
                ItemStack restoredD = BuildBlueprintItem.restore(buildD);
                helper.assertTrue(restoredD.getItem() == SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get(),
                        "B4 restores to void dimensional");
                assertVoidTwelve(helper, buildD, restoredD);

                // B5 创造空间蓝图 → SBUndoData 多一条记录
                BlueprintValidator.clearCooldown(fCreative.getUUID());
                helper.assertTrue(!SBUndoData.get(level).hasHistory(fCreative.getUUID()),
                        "B5 no history before");
                PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(targetE, Rotation.NONE,
                        0, 0, 0, true, FluidDisplayMode.SHOW_ALL), fCreative);
                helper.assertTrue(SBUndoData.get(level).hasHistory(fCreative.getUUID()),
                        "B5 history recorded");
                helper.assertValueEqual(sharedSpatialId, bpIdOf(fCreative.getMainHandItem()),
                        "B5 creative hand kept");
            });

            helper.runAtTickTime(420, () -> {
                try {
                    verifyPlacement(helper, dataA, targetA, Rotation.NONE);
                    verifyPlacement(helper, projData, targetB, Rotation.NONE);
                    verifyPlacement(helper, BlueprintStorage.load(sharedDimId), targetD, Rotation.NONE);
                    verifyPlacement(helper, dataA, targetE, Rotation.NONE);
                    helper.assertTrue(level.getBlockState(targetC).isAir(), "C still air");
                    // restoreUndo 把创造分支的世界恢复到放置前
                    UndoRecord rec = SBUndoData.get(level).pop(fCreative.getUUID());
                    helper.assertTrue(rec != null, "B5 record popped");
                    BlueprintPlacementHandler.restoreUndo(level, rec, null);
                    for (int y = rec.maxPos().getY(); y >= rec.minPos().getY(); y--) {
                        for (int x = rec.minPos().getX(); x <= rec.maxPos().getX(); x++) {
                            for (int z = rec.minPos().getZ(); z <= rec.maxPos().getZ(); z++) {
                                helper.assertTrue(level.getBlockState(new BlockPos(x, y, z)).isAir(),
                                        "undone at " + x + "," + y + "," + z);
                            }
                        }
                    }
                } finally {
                    BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
                    BlueprintValidator.clearCooldown(fSurvivorS.getUUID());
                    BlueprintValidator.clearCooldown(fSurvivorP.getUUID());
                    BlueprintValidator.clearCooldown(fStranger.getUUID());
                    BlueprintValidator.clearCooldown(fOpStranger.getUUID());
                    BlueprintValidator.clearCooldown(fOwner.getUUID());
                    BlueprintValidator.clearCooldown(fCreative.getUUID());
                    space.clearPool(fSurvivorP.getUUID());
                    space.discardBag(fOwner.getUUID(), sharedDimId);
                    for (String fid : files) BlueprintStorage.delete(fid);
                }
                helper.succeed();
            });
        } finally {
            if (opped) revokeOp(helper, op);
            BlueprintValidator.clearCooldown(op.getUUID());
        }
    }

    /** void_dimensional 建造蓝图的 12 个原样组件逐项断言。 */
    private static void assertVoidTwelve(GameTestHelper helper, ItemStack build, ItemStack restored) {
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.BLUEPRINT_NAME.get()),
                restored.get(SpatialBlueprintComponents.BLUEPRINT_NAME.get())), "void name carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.SAVE_MODE.get()),
                restored.get(SpatialBlueprintComponents.SAVE_MODE.get())), "void save_mode carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.BLUEPRINT_ID.get()),
                restored.get(SpatialBlueprintComponents.BLUEPRINT_ID.get())), "void id carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.CAPTURE_MIN.get()),
                restored.get(SpatialBlueprintComponents.CAPTURE_MIN.get())), "void capture_min carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.CAPTURE_MAX.get()),
                restored.get(SpatialBlueprintComponents.CAPTURE_MAX.get())), "void capture_max carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.TP_X.get()),
                restored.get(SpatialBlueprintComponents.TP_X.get())), "void tp_x carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.TP_Y.get()),
                restored.get(SpatialBlueprintComponents.TP_Y.get())), "void tp_y carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.TP_Z.get()),
                restored.get(SpatialBlueprintComponents.TP_Z.get())), "void tp_z carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.TP_DIMENSION.get()),
                restored.get(SpatialBlueprintComponents.TP_DIMENSION.get())), "void tp_dimension carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.CREATOR_NAME.get()),
                restored.get(SpatialBlueprintComponents.CREATOR_NAME.get())), "void creator carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.WORLD_UUID.get()),
                restored.get(SpatialBlueprintComponents.WORLD_UUID.get())), "void world_uuid carried");
        helper.assertTrue(Objects.equals(
                build.get(SpatialBlueprintComponents.WORLD_NAME.get()),
                restored.get(SpatialBlueprintComponents.WORLD_NAME.get())), "void world_name carried");
        helper.assertTrue(!bpIdOf(restored).isEmpty(), "void restored keeps blueprint_id");
        helper.assertValueEqual(WorldIdentity.currentUuidString(),
                restored.get(SpatialBlueprintComponents.WORLD_UUID.get()), "void world is current");
    }

    // ── t07：领地预检先于一切状态修改 ─────────────────────────────────────────

    /** 临时取消全部 EntityPlaceEvent 的监听器（t07 用，finally 注销）。 */
    public static class PlaceCancelListener {
        @SubscribeEvent
        public void onPlace(BlockEvent.EntityPlaceEvent event) {
            event.setCanceled(true);
        }
    }

    @GameTest(template = "empty")
    public static void t07_claimPrecheck(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MaterialSpace space = MaterialSpace.get(level);
        FakePlayer survivor = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        PlaceCancelListener listener = null;
        try {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            giveBlankWithPoints(survivor,
                    helper.absolutePos(new BlockPos(1, 2, 1)),
                    helper.absolutePos(new BlockPos(2, 2, 2)));
            CaptureProjectionMessage.handleServer(
                    new CaptureProjectionMessage("t07-proj", 5, CaptureMode.SAFE, "", "", false), survivor);
            String projId = bpIdOf(survivor.getMainHandItem());
            helper.assertTrue(!projId.isEmpty(), "projection prepared");
            files.add(projId);
            MaterialList ml = BlueprintStorage.load(projId).materialList;
            // 材料够（否则连预检都到不了要证明的东西）
            for (Map.Entry<String, Integer> e : ml.blockCounts.entrySet()) {
                space.deposit(survivor.getUUID(), e.getKey(), e.getValue(), ml.keyTags.get(e.getKey()));
            }
            helper.assertTrue(space.canAfford(survivor.getUUID(), ml), "pool funded");
            CompoundTag fileBefore = BlueprintStorage.load(projId).serialize();
            Map<String, Integer> poolBefore = new HashMap<>(space.pool(survivor.getUUID()));
            // 源区正上方 virgin 空域：领地预检的拒绝只能来自监听器，不可能来自地形
            BlockPos target = helper.absolutePos(new BlockPos(1, 4, 1));
            survivor.setPos(target.getX() + 1.5, target.getY() + 2.0, target.getZ() + 1.5);

            listener = new PlaceCancelListener();
            NeoForge.EVENT_BUS.register(listener);
            BlueprintValidator.clearCooldown(survivor.getUUID());
            received.clear();
            PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(target, Rotation.NONE,
                    0, 0, 0, true, FluidDisplayMode.SHOW_ALL), survivor);
            S2C_OperationResult denied = firstOperationResult(helper, received);
            helper.assertValueEqual(denied.messageKey,
                    "spatial_blueprint.error.placement_protected", "claim denied key");
            boolean cancelled = false;
            for (CustomPacketPayload payload : received) {
                if (payload instanceof S2C_PlacementProgressMessage pm && pm.progress == -1f) cancelled = true;
            }
            helper.assertTrue(cancelled, "placement cancelled progress sent");
            // 领地预检先于一切状态修改：材料、物品、世界、文件都不变
            helper.assertTrue(space.pool(survivor.getUUID()).equals(poolBefore), "pool untouched");
            helper.assertValueEqual(projId, bpIdOf(survivor.getMainHandItem()), "hand untouched");
            helper.assertTrue(level.getBlockState(target).isAir(), "world untouched");
            helper.assertTrue(BlueprintStorage.load(projId).serialize().equals(fileBefore), "file untouched");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(survivor.getUUID());
            if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
            space.clearPool(survivor.getUUID());
            for (String id : files) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t08：已放置与外档 ─────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t08_foreignAndPlaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MaterialSpace space = MaterialSpace.get(level);
        FakePlayer owner = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        String bagId = null;
        try {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            giveBlankWithPoints(owner,
                    helper.absolutePos(new BlockPos(1, 2, 1)),
                    helper.absolutePos(new BlockPos(2, 2, 2)));
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t08-dim", true, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), owner);
            String dimId = bpIdOf(owner.getMainHandItem());
            helper.assertTrue(!dimId.isEmpty(), "dimensional prepared");
            files.add(dimId);
            bagId = dimId;
            int bagBefore = space.bagTotal(owner.getUUID(), dimId);
            helper.assertTrue(bagBefore > 0, "bag funded");
            BlockPos target = helper.absolutePos(new BlockPos(1, 4, 1));
            owner.setPos(target.getX() + 1.5, target.getY() + 2.0, target.getZ() + 1.5);

            // 已放置的次元蓝图 → blueprint_already_placed，四样不变
            BlueprintData placed = BlueprintStorage.load(dimId);
            placed.placed = true;
            BlueprintStorage.save(UUID.fromString(dimId), placed);
            CompoundTag placedBytes = BlueprintStorage.load(dimId).serialize();
            BlueprintValidator.clearCooldown(owner.getUUID());
            received.clear();
            PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(target, Rotation.NONE,
                    0, 0, 0, true, FluidDisplayMode.SHOW_ALL), owner);
            helper.assertValueEqual(firstOperationResult(helper, received).messageKey,
                    "spatial_blueprint.error.blueprint_already_placed", "placed key");
            helper.assertTrue(space.bagTotal(owner.getUUID(), dimId) == bagBefore, "bag kept after placed");
            helper.assertValueEqual(dimId, bpIdOf(owner.getMainHandItem()), "hand kept after placed");
            helper.assertTrue(BlueprintStorage.load(dimId).serialize().equals(placedBytes),
                    "file kept after placed");
            helper.assertTrue(level.getBlockState(target).isAir(), "world kept after placed");

            // worldUuid 为别的存档 → foreign_world，四样不变
            BlueprintData foreign = BlueprintStorage.load(dimId);
            foreign.placed = false;
            foreign.worldUuid = "t08-foreign-world";
            BlueprintStorage.save(UUID.fromString(dimId), foreign);
            CompoundTag foreignBytes = BlueprintStorage.load(dimId).serialize();
            BlueprintValidator.clearCooldown(owner.getUUID());
            received.clear();
            PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(target, Rotation.NONE,
                    0, 0, 0, true, FluidDisplayMode.SHOW_ALL), owner);
            helper.assertValueEqual(firstOperationResult(helper, received).messageKey,
                    "spatial_blueprint.error.foreign_world", "foreign key");
            helper.assertTrue(space.bagTotal(owner.getUUID(), dimId) == bagBefore, "bag kept after foreign");
            helper.assertValueEqual(dimId, bpIdOf(owner.getMainHandItem()), "hand kept after foreign");
            helper.assertTrue(BlueprintStorage.load(dimId).serialize().equals(foreignBytes),
                    "file kept after foreign");
            helper.assertTrue(level.getBlockState(target).isAir(), "world kept after foreign");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(owner.getUUID());
            for (String id : files) BlueprintStorage.delete(id);
            if (bagId != null) space.discardBag(owner.getUUID(), bagId);
        }
        helper.succeed();
    }
}
