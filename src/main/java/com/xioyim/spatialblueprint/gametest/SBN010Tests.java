package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
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
import com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SBN-010 捕获（P3c-3）GameTest（t01～t10）。
 * 新格式数据由真实世界 / 真实对象产生；伪造 / 越界包是唯一允许手写处。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN010Tests {

    // ── 编解码辅助 ────────────────────────────────────────────────────────────

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
                new GameProfile(UUID.randomUUID(), "SB10" + UUID.randomUUID().toString().substring(0, 4)));
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

    private static boolean gotCooldown(List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_CaptureCooldownMessage) return true;
        }
        return false;
    }

    /** 主手给空白蓝图并写入选区两点（绝对坐标）。 */
    private static void giveBlankWithPoints(FakePlayer player, BlockPos p1, BlockPos p2) {
        ItemStack blank = new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get());
        BlankBlueprintItem.setPoint1(blank, p1);
        BlankBlueprintItem.setPoint2(blank, p2);
        player.setItemInHand(InteractionHand.MAIN_HAND, blank);
    }

    /** 在世界放箱子并装入物品，返回箱子方块实体。 */
    private static ChestBlockEntity placeChestWith(GameTestHelper helper, BlockPos rel, ItemStack... stacks) {
        helper.setBlock(rel, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(rel));
        if (chest == null) throw new IllegalStateException("chest BE missing at " + rel);
        for (int i = 0; i < stacks.length; i++) chest.setItem(i, stacks[i]);
        return chest;
    }

    private static ItemStack enchantedSword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS), 3);
        return sword;
    }

    private static ArmorStand spawnStand(GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, helper.getLevel());
        stand.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
        helper.getLevel().addFreshEntity(stand);
        return stand;
    }

    private static BlueprintData.BlockEntry findChestEntry(GameTestHelper helper, BlueprintData data) {
        for (BlueprintData.BlockEntry e : data.normalBlocks) {
            if (e.state.is(Blocks.CHEST)) return e;
        }
        for (BlueprintData.BlockEntry e : data.dependentBlocks) {
            if (e.state.is(Blocks.CHEST)) return e;
        }
        for (BlueprintData.BlockEntry e : data.lateBlocks) {
            if (e.state.is(Blocks.CHEST)) return e;
        }
        helper.fail("no chest entry in captured data");
        return null;
    }

    private static int totalBlocks(BlueprintData data) {
        return data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
    }

    // ── t01：注册与命名 ───────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_registration(GameTestHelper helper) {
        String[][] expected = {
                // SBN-005 的 8 个
                {"s2c_operation_result", com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult.TYPE.id().getPath()},
                {"s2c_blueprint_chunk_message", com.xioyim.spatialblueprint.network.S2C_BlueprintChunkMessage.TYPE.id().getPath()},
                {"s2c_blueprint_data_message", com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage.TYPE.id().getPath()},
                {"s2c_blueprint_data_fail_message", com.xioyim.spatialblueprint.network.S2C_BlueprintDataFailMessage.TYPE.id().getPath()},
                {"s2c_open_void_screen_message", com.xioyim.spatialblueprint.network.s2c.S2C_OpenVoidScreenMessage.TYPE.id().getPath()},
                {"s2c_illegal_blocks_list_message", com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.TYPE.id().getPath()},
                {"c2s_request_blueprint_data_message", com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage.TYPE.id().getPath()},
                {"c2s_discard_invalid_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DiscardInvalidBlueprintMessage.TYPE.id().getPath()},
                // SBN-006 的 24 个
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
                // SBN-007 的 21 个
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
                {"s2c_capture_cooldown_message", S2C_CaptureCooldownMessage.TYPE.id().getPath()},
                {"s2c_op_capture_confirm_message", com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage.TYPE.id().getPath()},
                {"c2s_request_project_list_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage.TYPE.id().getPath()},
                {"s2c_project_list_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage.TYPE.id().getPath()},
                {"c2s_request_project_entries_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectEntriesMessage.TYPE.id().getPath()},
                {"s2c_project_entries_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage.TYPE.id().getPath()},
                {"c2s_request_project_status_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage.TYPE.id().getPath()},
                {"s2c_project_status_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage.TYPE.id().getPath()},
                // SBN-008 的 12 个
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
                // SBN-009 的 6 个
                {"c2s_upload_begin_message", com.xioyim.spatialblueprint.network.C2S_UploadBeginMessage.TYPE.id().getPath()},
                {"c2s_upload_chunk_message", com.xioyim.spatialblueprint.network.C2S_UploadChunkMessage.TYPE.id().getPath()},
                {"c2s_upload_projection_template_message", com.xioyim.spatialblueprint.network.C2S_UploadProjectionTemplateMessage.TYPE.id().getPath()},
                {"c2s_confirm_upload_message", com.xioyim.spatialblueprint.network.C2S_ConfirmUploadMessage.TYPE.id().getPath()},
                {"c2s_request_engineering_report_message", com.xioyim.spatialblueprint.network.C2S_RequestEngineeringReportMessage.TYPE.id().getPath()},
                {"s2c_upload_report_message", com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage.TYPE.id().getPath()},
                // SBN-010 的 5 个（全 C2S，与 1.20.1 归类一致）
                {"capture_area_message", CaptureAreaMessage.TYPE.id().getPath()},
                {"capture_projection_message", CaptureProjectionMessage.TYPE.id().getPath()},
                {"c2s_void_recapture_message", C2S_VoidRecaptureMessage.TYPE.id().getPath()},
                {"c2s_create_engineering_blueprint_message", C2S_CreateEngineeringBlueprintMessage.TYPE.id().getPath()},
                {"c2s_create_sub_project_message", C2S_CreateSubProjectMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[1], pair[0], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(ids.size(), 76, "76 unique payload ids");
        helper.assertValueEqual(CaptureAreaMessage.TYPE.id().getNamespace(),
                SpatialBlueprintMod.MODID, "namespace");
        helper.succeed();
    }

    // ── t02：5 个消息编码往返 ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codecRoundTrip(GameTestHelper helper) {
        assertRoundTrip(helper, CaptureAreaMessage.STREAM_CODEC,
                new CaptureAreaMessage("t02-area", false, 5, FluidCaptureFilter.ALL,
                        false, false, CaptureMode.SAFE, "short", "long note"));
        assertRoundTrip(helper, CaptureProjectionMessage.STREAM_CODEC,
                new CaptureProjectionMessage("t02-proj", 7, CaptureMode.SANITIZE, "s", "l", true));
        assertRoundTrip(helper, C2S_VoidRecaptureMessage.STREAM_CODEC,
                new C2S_VoidRecaptureMessage());
        assertRoundTrip(helper, C2S_CreateEngineeringBlueprintMessage.STREAM_CODEC,
                new C2S_CreateEngineeringBlueprintMessage("", "t02-eng", "minecraft:overworld",
                        new BlockPos(1, 2, 3), new BlockPos(4, 5, 6),
                        CaptureMode.SAFE, 9, "s", "l"));
        assertRoundTrip(helper, C2S_CreateSubProjectMessage.STREAM_CODEC,
                new C2S_CreateSubProjectMessage("t02-sub", 6, 2,
                        UUID.randomUUID().toString(), "s", "l"));
        helper.succeed();
    }

    // ── t03：解码守卫 ─────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_decodeGuards(GameTestHelper helper) {
        // CaptureArea 名字超长（上限 MAX_NAME_LENGTH * 2 = 128 字节）
        expectDecoderException(helper, () -> {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeUtf("n".repeat(200));
                CaptureAreaMessage.decode(buf);
            } finally { buf.release(); }
        }, "CaptureArea name over cap");
        // CaptureArea 备注超长（noteLong 上限 200）
        expectDecoderException(helper, () -> {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeUtf("t03");
                buf.writeBoolean(false);
                buf.writeInt(0);
                buf.writeByte(FluidCaptureFilter.ALL.toByte());
                buf.writeBoolean(false);
                buf.writeBoolean(false);
                buf.writeInt(CaptureMode.SAFE.ordinal());
                buf.writeUtf("s", 15);
                buf.writeUtf("l".repeat(300), 300);
                CaptureAreaMessage.decode(buf);
            } finally { buf.release(); }
        }, "CaptureArea noteLong over cap");
        // Engineering dimension 超长（上限 128）
        expectDecoderException(helper, () -> {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeUtf("", 64);
                buf.writeUtf("t03", 64);
                buf.writeUtf("d".repeat(200), 200);
                C2S_CreateEngineeringBlueprintMessage.decode(buf);
            } finally { buf.release(); }
        }, "Engineering dimension over cap");
        // SubProject projectId 超长（上限 64）
        expectDecoderException(helper, () -> {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeUtf("t03", 128);
                buf.writeInt(5);
                buf.writeInt(1);
                buf.writeUtf("p".repeat(100), 100);
                C2S_CreateSubProjectMessage.decode(buf);
            } finally { buf.release(); }
        }, "SubProject projectId over cap");
        // CaptureMode ordinal 越界：fromOrdinal 直取 values()，抛 ArrayIndexOutOfBoundsException（与 1.20.1 同）
        try {
            FriendlyByteBuf buf = rawBuf();
            try {
                buf.writeUtf("t03");
                buf.writeBoolean(false);
                buf.writeInt(0);
                buf.writeByte(FluidCaptureFilter.ALL.toByte());
                buf.writeBoolean(false);
                buf.writeBoolean(false);
                buf.writeInt(99);
                buf.writeUtf("s", 15);
                buf.writeUtf("l", 200);
                CaptureAreaMessage.decode(buf);
            } finally { buf.release(); }
            helper.fail("expected ArrayIndexOutOfBoundsException for CaptureMode ordinal 99");
        } catch (ArrayIndexOutOfBoundsException expected) {
            // ok：与 1.20.1 行为相同，本任务不改
        }
        helper.succeed();
    }

    // ── t04：空间捕获（SAFE，非 OP 创造）──────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_captureSpatial(GameTestHelper helper) {
        FakePlayer player = creativePlayer(helper);
        ServerLevel level = helper.getLevel();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        ArmorStand stand = null;
        try {
            BlockPos relA = new BlockPos(1, 2, 1);
            BlockPos relB = new BlockPos(2, 2, 2);
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(1, 2, 2), Blocks.STONE);
            ChestBlockEntity worldChest = placeChestWith(helper, new BlockPos(2, 2, 2),
                    new ItemStack(Items.STONE, 5), enchantedSword(helper));
            stand = spawnStand(helper, new BlockPos(1, 2, 1));
            BlockPos p1 = helper.absolutePos(relA);
            BlockPos p2 = helper.absolutePos(relB);
            giveBlankWithPoints(player, p1, p2);

            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t04-space", false, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), player);

            // 空白蓝图换成空间蓝图
            ItemStack hand = player.getMainHandItem();
            helper.assertTrue(hand.getItem() == SpatialBlueprintItems.SPATIAL_BLUEPRINT.get(),
                    "blank became spatial blueprint");
            String bpId = SpatialBlueprintItem.getBlueprintId(hand);
            helper.assertTrue(bpId != null && !bpId.isEmpty(), "blueprint_id via accessor");
            files.add(bpId);
            BlueprintData data = BlueprintStorage.load(bpId);
            helper.assertTrue(data != null, "blueprint file saved");
            helper.assertValueEqual(totalBlocks(data), 4, "3 stone + 1 chest captured");
            helper.assertTrue(data.saveMode == CaptureMode.SAFE, "saveMode SAFE");
            helper.assertTrue(!data.opBypassCapture, "non-FORCE opBypassCapture=false");

            // SAFE 非 OP 语义：容器内容被清（防复制），箱子 NBT 本体仍在
            BlueprintData.BlockEntry chestEntry = findChestEntry(helper, data);
            helper.assertTrue(chestEntry.nbt != null, "chest BE nbt captured");
            helper.assertTrue(!chestEntry.nbt.contains("Items"), "SAFE non-OP chest stripped");

            // 往返验证：捕获出的箱子 NBT 能读回新箱子（内容按语义被清）
            ChestBlockEntity fresh = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
            fresh.loadWithComponents(chestEntry.nbt, level.registryAccess());
            helper.assertTrue(fresh.getItem(0).isEmpty() && fresh.getItem(1).isEmpty(),
                    "stripped chest reads back empty");

            // 实体条目存在；实体 NBT 能还原出同类型实体
            BlueprintData.EntityEntry standEntry = null;
            for (BlueprintData.EntityEntry e : data.entities) {
                if ("minecraft:armor_stand".equals(e.type)) standEntry = e;
            }
            helper.assertTrue(standEntry != null, "armor stand entry captured");
            Optional<Entity> recreated = EntityType.create(standEntry.nbt, level);
            helper.assertTrue(recreated.isPresent()
                    && recreated.get().getType() == EntityType.ARMOR_STAND, "entity nbt recreates stand");

            // 源区域不变（未清除）：方块与箱内物品原样
            helper.assertTrue(level.getBlockState(p1).is(Blocks.STONE), "source stone kept");
            helper.assertTrue(level.getBlockState(helper.absolutePos(new BlockPos(2, 2, 2))).is(Blocks.CHEST),
                    "source chest kept");
            helper.assertValueEqual(worldChest.getItem(0).getCount(), 5, "source chest items kept");
            AABB box = new AABB(p1.getX(), p1.getY(), p1.getZ(),
                    p2.getX() + 1.0, p2.getY() + 1.0, p2.getZ() + 1.0);
            helper.assertTrue(!level.getEntitiesOfClass(ArmorStand.class, box, e -> true).isEmpty(),
                    "source stand kept");

            // 冷却通知发出
            helper.assertTrue(gotCooldown(received), "sink got S2C_CaptureCooldownMessage");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(player.getUUID());
            if (stand != null) stand.discard();
            for (String id : files) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t05：次元捕获（生存，清除 + 材料入袋）─────────────────────────────────

    @GameTest(template = "empty")
    public static void t05_captureDimensional(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        ServerLevel level = helper.getLevel();
        MaterialSpace space = MaterialSpace.get(level);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        String bagId = null;
        try {
            // p1/p2 故意写反，验证 min/max 归一化
            BlockPos relA = new BlockPos(2, 2, 2);
            BlockPos relB = new BlockPos(1, 2, 1);
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(1, 2, 2), Blocks.STONE);
            placeChestWith(helper, new BlockPos(2, 2, 2), new ItemStack(Items.STONE, 5));
            BlockPos p1 = helper.absolutePos(relA);
            BlockPos p2 = helper.absolutePos(relB);
            BlockPos expectMin = helper.absolutePos(new BlockPos(1, 2, 1));
            BlockPos expectMax = helper.absolutePos(new BlockPos(2, 2, 2));
            giveBlankWithPoints(player, p1, p2);

            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t05-dim", true, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), player);

            // 选区被清成空气
            for (BlockPos abs : new BlockPos[]{helper.absolutePos(new BlockPos(1, 2, 1)),
                    helper.absolutePos(new BlockPos(2, 2, 1)),
                    helper.absolutePos(new BlockPos(1, 2, 2)),
                    helper.absolutePos(new BlockPos(2, 2, 2))}) {
                helper.assertTrue(level.getBlockState(abs).isAir(), "cleared to air at " + abs);
            }

            // 手里变次元蓝图，三项经访问器读出正确
            ItemStack hand = player.getMainHandItem();
            helper.assertTrue(hand.getItem() == SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get(),
                    "blank became dimensional blueprint");
            helper.assertTrue(expectMin.equals(VoidDimensionalBlueprintItem.getCaptureMin(hand)),
                    "capture_min normalized");
            helper.assertTrue(expectMax.equals(VoidDimensionalBlueprintItem.getCaptureMax(hand)),
                    "capture_max normalized");
            helper.assertValueEqual(VoidDimensionalBlueprintItem.getCreatorName(hand),
                    player.getScoreboardName(), "creator_name");

            // 次元袋按语义入账：3 石头 + 1 箱子
            bagId = SpatialBlueprintItem.getBlueprintId(hand);
            helper.assertTrue(bagId != null && !bagId.isEmpty(), "bag blueprint_id");
            files.add(bpIdOf(hand));
            Map<String, Integer> bag = space.bag(player.getUUID(), bagId);
            helper.assertValueEqual(bag.getOrDefault("minecraft:stone", -1), 3, "bag stone x3");
            helper.assertValueEqual(bag.getOrDefault("minecraft:chest", -1), 1, "bag chest x1");

            // 次元蓝图 KeepContents：箱内物品跟着走（合法内容保留）
            BlueprintData data = BlueprintStorage.load(bagId);
            helper.assertTrue(data != null, "dimensional file saved");
            BlueprintData.BlockEntry chestEntry = findChestEntry(helper, data);
            helper.assertTrue(chestEntry.nbt != null && chestEntry.nbt.contains("Items"),
                    "dimensional chest contents kept");
            helper.assertValueEqual(data.ownerUuid, player.getUUID().toString(), "file ownerUuid");
            helper.assertTrue(gotCooldown(received), "sink got cooldown");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(player.getUUID());
            for (String id : files) BlueprintStorage.delete(id);
            if (bagId != null) space.discardBag(player.getUUID(), bagId);
        }
        helper.succeed();
    }

    private static String bpIdOf(ItemStack stack) {
        String id = SpatialBlueprintItem.getBlueprintId(stack);
        return id == null ? "" : id;
    }

    // ── t06：FORCE 降级 ──────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_forceDowngrade(GameTestHelper helper) {
        FakePlayer plain = creativePlayer(helper);
        FakePlayer op = creativePlayer(helper);
        ServerLevel level = helper.getLevel();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        boolean opped = false;
        try {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            ChestBlockEntity worldChest = placeChestWith(helper, new BlockPos(2, 2, 1),
                    new ItemStack(Items.STONE, 5), enchantedSword(helper));
            BlockPos p1 = helper.absolutePos(new BlockPos(1, 2, 1));
            BlockPos p2 = helper.absolutePos(new BlockPos(2, 2, 1));

            // 非 OP 发 FORCE → 按 SAFE 处理
            giveBlankWithPoints(plain, p1, p2);
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t06-downgrade", false, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.FORCE, "", ""), plain);
            String downgradedId = bpIdOf(plain.getMainHandItem());
            helper.assertTrue(!downgradedId.isEmpty(), "downgraded capture saved");
            files.add(downgradedId);
            BlueprintData downgraded = BlueprintStorage.load(downgradedId);
            helper.assertTrue(!downgraded.opBypassCapture, "non-OP FORCE opBypassCapture=false");
            helper.assertTrue(downgraded.saveMode == CaptureMode.SAFE, "non-OP FORCE mode=SAFE");
            helper.assertTrue(!findChestEntry(helper, downgraded).nbt.contains("Items"),
                    "non-OP FORCE chest stripped per SAFE");

            // OP + 创造发 FORCE → 完整保留
            grantOp(helper, op);
            opped = true;
            giveBlankWithPoints(op, p1, p2);
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t06-force", false, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.FORCE, "", ""), op);
            String forceId = bpIdOf(op.getMainHandItem());
            helper.assertTrue(!forceId.isEmpty(), "OP FORCE capture saved");
            files.add(forceId);
            BlueprintData forced = BlueprintStorage.load(forceId);
            helper.assertTrue(forced.opBypassCapture, "OP FORCE opBypassCapture=true");
            BlueprintData.BlockEntry kept = findChestEntry(helper, forced);
            helper.assertTrue(kept.nbt != null && kept.nbt.contains("Items"), "OP FORCE chest kept");

            // 保留内容的往返验证：读进新箱子，两格与源箱子逐格同物品同组件
            ChestBlockEntity fresh = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
            fresh.loadWithComponents(kept.nbt, level.registryAccess());
            helper.assertTrue(ItemStack.isSameItemSameComponents(fresh.getItem(0), worldChest.getItem(0)),
                    "kept slot0 roundtrip identical");
            helper.assertTrue(ItemStack.isSameItemSameComponents(fresh.getItem(1), worldChest.getItem(1)),
                    "kept slot1 roundtrip identical");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(plain.getUUID());
            BlueprintValidator.clearCooldown(op.getUUID());
            if (opped) revokeOp(helper, op);
            for (String id : files) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t07：领地采样 ─────────────────────────────────────────────────────────

    /** 临时取消全部 BreakEvent 的监听器（t07 用，finally 注销）。 */
    public static class BreakCancelListener {
        @SubscribeEvent
        public void onBreak(BlockEvent.BreakEvent event) {
            event.setCanceled(true);
        }
    }

    @GameTest(template = "empty")
    public static void t07_claimBlocksCapture(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        ServerLevel level = helper.getLevel();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        BreakCancelListener listener = null;
        try {
            BlockPos relA = new BlockPos(1, 2, 1);
            BlockPos relB = new BlockPos(2, 2, 1);
            helper.setBlock(relA, Blocks.STONE);
            helper.setBlock(relB, Blocks.STONE);
            BlockPos p1 = helper.absolutePos(relA);
            BlockPos p2 = helper.absolutePos(relB);
            int beforeFiles = BlueprintStorage.listSummaries().size();
            giveBlankWithPoints(player, p1, p2);

            // 注册取消监听 → 被拒、选区原样、手里仍是空白、无新文件
            listener = new BreakCancelListener();
            NeoForge.EVENT_BUS.register(listener);
            received.clear();
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t07-claim", true, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), player);
            S2C_OperationResult denied = firstOperationResult(helper, received);
            helper.assertValueEqual(denied.messageKey, "spatial_blueprint.error.no_break_permission",
                    "claim denied key");
            helper.assertTrue(level.getBlockState(p1).is(Blocks.STONE)
                    && level.getBlockState(p2).is(Blocks.STONE), "area untouched after deny");
            helper.assertTrue(player.getMainHandItem().getItem()
                    == SpatialBlueprintItems.BLANK_BLUEPRINT.get(), "hand still blank");
            helper.assertValueEqual(BlueprintStorage.listSummaries().size(), beforeFiles,
                    "no new file after deny");

            // 注销监听 → 同一操作成功（与 t05 同口径）
            NeoForge.EVENT_BUS.unregister(listener);
            listener = null;
            BlueprintValidator.clearCooldown(player.getUUID());
            received.clear();
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t07-claim", true, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), player);
            helper.assertTrue(level.getBlockState(p1).isAir() && level.getBlockState(p2).isAir(),
                    "area cleared after unregister");
            String bagId = bpIdOf(player.getMainHandItem());
            helper.assertTrue(!bagId.isEmpty(), "dimensional saved after unregister");
            files.add(bagId);
            MaterialSpace space = MaterialSpace.get(level);
            space.discardBag(player.getUUID(), bagId);
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(player.getUUID());
            if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
            for (String id : files) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t08：投影捕获 ─────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t08_captureProjection(GameTestHelper helper) {
        FakePlayer p1 = fakePlayer(helper);
        FakePlayer p2 = fakePlayer(helper);
        FakePlayer op = creativePlayer(helper);
        ServerLevel level = helper.getLevel();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        boolean opped = false;
        try {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            placeChestWith(helper, new BlockPos(2, 2, 1), new ItemStack(Items.STONE, 5));
            BlockPos p1pos = helper.absolutePos(new BlockPos(1, 2, 1));
            BlockPos p2pos = helper.absolutePos(new BlockPos(2, 2, 1));
            BlockPos chestAbs = helper.absolutePos(new BlockPos(2, 2, 1));

            // 非 FORCE：箱子内容不进蓝图、源区域不清
            giveBlankWithPoints(p1, p1pos, p2pos);
            CaptureProjectionMessage.handleServer(
                    new CaptureProjectionMessage("t08-proj", 5, CaptureMode.SAFE, "", "", false), p1);
            helper.assertTrue(p1.getMainHandItem().getItem()
                    == SpatialBlueprintItems.PROJECTION_BLUEPRINT.get(), "projection item");
            String projId = AbstractBlueprintItem.getBlueprintId(p1.getMainHandItem());
            helper.assertTrue(projId != null && !projId.isEmpty(), "projection blueprint_id");
            files.add(projId);
            BlueprintData proj = BlueprintStorage.load(projId);
            helper.assertValueEqual(proj.category, "project", "projection category");
            helper.assertTrue(!findChestEntry(helper, proj).nbt.contains("Items"),
                    "projection chest stripped (anti-dupe)");
            helper.assertTrue(level.getBlockState(chestAbs).is(Blocks.CHEST), "source not cleared");
            ChestBlockEntity src = (ChestBlockEntity) level.getBlockEntity(chestAbs);
            helper.assertValueEqual(src.getItem(0).getCount(), 5, "source chest items kept");

            // reward = true 但非 OP → 按普通投影
            giveBlankWithPoints(p2, p1pos, p2pos);
            CaptureProjectionMessage.handleServer(
                    new CaptureProjectionMessage("t08-reward-denied", 5, CaptureMode.SAFE, "", "", true), p2);
            String deniedId = AbstractBlueprintItem.getBlueprintId(p2.getMainHandItem());
            files.add(deniedId);
            helper.assertValueEqual(BlueprintStorage.load(deniedId).category, "project",
                    "non-OP reward falls back to project");

            // OP + 创造 + reward → 奖励蓝图（无材料清单）
            grantOp(helper, op);
            opped = true;
            giveBlankWithPoints(op, p1pos, p2pos);
            CaptureProjectionMessage.handleServer(
                    new CaptureProjectionMessage("t08-reward", 5, CaptureMode.SAFE, "", "", true), op);
            helper.assertTrue(op.getMainHandItem().getItem()
                    == SpatialBlueprintItems.REWARD_BLUEPRINT.get(), "reward item");
            String rewardId = AbstractBlueprintItem.getBlueprintId(op.getMainHandItem());
            files.add(rewardId);
            BlueprintData reward = BlueprintStorage.load(rewardId);
            helper.assertValueEqual(reward.category, "reward", "reward category");
            helper.assertTrue(reward.materialList == null
                    || reward.materialList.blockCounts.isEmpty(), "reward has no material list");
            helper.assertTrue(gotCooldown(received), "sink got cooldown");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(p1.getUUID());
            BlueprintValidator.clearCooldown(p2.getUUID());
            BlueprintValidator.clearCooldown(op.getUUID());
            if (opped) revokeOp(helper, op);
            for (String id : files) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t09：空置次元蓝图重新储存 ─────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t09_voidRecapture(GameTestHelper helper) {
        FakePlayer owner = fakePlayer(helper);
        FakePlayer stranger = fakePlayer(helper);
        ServerLevel level = helper.getLevel();
        MaterialSpace space = MaterialSpace.get(level);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        String bagId = null;
        try {
            // 准备：一次次元捕获得到文件与坐标
            BlockPos relA = new BlockPos(1, 2, 1);
            BlockPos relB = new BlockPos(2, 2, 2);
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            BlockPos capMin = helper.absolutePos(new BlockPos(1, 2, 1));
            BlockPos capMax = helper.absolutePos(new BlockPos(2, 2, 2));
            giveBlankWithPoints(owner, capMin, capMax);
            CaptureAreaMessage.handleServer(new CaptureAreaMessage("t09-dim", true, 0,
                    FluidCaptureFilter.ALL, false, false, CaptureMode.SAFE, "", ""), owner);
            String dimId = bpIdOf(owner.getMainHandItem());
            helper.assertTrue(!dimId.isEmpty(), "dimensional prepared");
            files.add(dimId);
            bagId = dimId;
            BlueprintValidator.clearCooldown(owner.getUUID());

            // 模拟已放置：placed 置 true；重摆方块供重新储存捕获
            BlueprintData placed = BlueprintStorage.load(dimId);
            placed.placed = true;
            BlueprintStorage.save(UUID.fromString(dimId), placed);
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);

            // 对应的空置次元蓝图物品（组件经访问器写）
            ItemStack voidStack = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(voidStack, dimId);
            VoidDimensionalBlueprintItem.setBlueprintName(voidStack, "t09-dim");
            VoidDimensionalBlueprintItem.setCaptureRange(voidStack, capMin, capMax);
            VoidDimensionalBlueprintItem.setCreatorName(voidStack, owner.getScoreboardName());
            voidStack.set(SpatialBlueprintComponents.WORLD_UUID.get(), WorldIdentity.currentUuidString());

            // 非主人 → 被拒、文件不变
            ItemStack strangerStack = voidStack.copy();
            stranger.setItemInHand(InteractionHand.MAIN_HAND, strangerStack);
            received.clear();
            C2S_VoidRecaptureMessage.handleServer(new C2S_VoidRecaptureMessage(), stranger);
            helper.assertValueEqual(firstOperationResult(helper, received).messageKey,
                    "spatial_blueprint.message.void_blueprint.not_owner", "non-owner denied");
            helper.assertTrue(BlueprintStorage.load(dimId).placed, "file untouched after deny");

            // world_uuid 为别的存档 → foreign 分支
            ItemStack foreignStack = voidStack.copy();
            foreignStack.set(SpatialBlueprintComponents.WORLD_UUID.get(), "t09-foreign-world");
            owner.setItemInHand(InteractionHand.MAIN_HAND, foreignStack);
            received.clear();
            C2S_VoidRecaptureMessage.handleServer(new C2S_VoidRecaptureMessage(), owner);
            helper.assertValueEqual(firstOperationResult(helper, received).messageKey,
                    "spatial_blueprint.error.foreign_world.discarded", "foreign denied");
            helper.assertTrue(BlueprintStorage.load(dimId).placed, "file untouched after foreign");

            // 主人 → 覆盖写回原文件（blueprint_id 不变）、placed = false
            owner.setItemInHand(InteractionHand.MAIN_HAND, voidStack.copy());
            BlueprintValidator.clearCooldown(owner.getUUID());
            received.clear();
            C2S_VoidRecaptureMessage.handleServer(new C2S_VoidRecaptureMessage(), owner);
            S2C_OperationResult done = firstOperationResult(helper, received);
            helper.assertTrue(done.success, "owner recapture succeeded");
            helper.assertValueEqual(done.messageKey,
                    "spatial_blueprint.message.void_recapture_completed", "recapture done key");
            BlueprintData rewritten = BlueprintStorage.load(dimId);
            helper.assertTrue(!rewritten.placed, "placed cleared after recapture");
            helper.assertValueEqual(totalBlocks(rewritten), 2, "recaptured 2 blocks");
            ItemStack gained = owner.getMainHandItem();
            helper.assertTrue(gained.getItem() == SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get(),
                    "gained dimensional item");
            helper.assertValueEqual(bpIdOf(gained), dimId, "same blueprint_id");
            helper.assertTrue(capMin.equals(VoidDimensionalBlueprintItem.getCaptureMin(gained)),
                    "new item capture_min");
            helper.assertTrue(capMax.equals(VoidDimensionalBlueprintItem.getCaptureMax(gained)),
                    "new item capture_max");
            helper.assertValueEqual(VoidDimensionalBlueprintItem.getCreatorName(gained),
                    owner.getScoreboardName(), "new item creator");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(owner.getUUID());
            BlueprintValidator.clearCooldown(stranger.getUUID());
            for (String id : files) BlueprintStorage.delete(id);
            if (bagId != null) space.discardBag(owner.getUUID(), bagId);
        }
        helper.succeed();
    }

    // ── t10：工程蓝图与子工程 ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t10_engineeringAndSub(GameTestHelper helper) {
        FakePlayer eng = creativePlayer(helper);
        FakePlayer op = fakePlayer(helper);
        ServerLevel level = helper.getLevel();
        String dim = com.xioyim.spatialblueprint.util.ProjectData.dimensionOf(level);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> files = new ArrayList<>();
        List<UUID> projects = new ArrayList<>();
        List<String[]> entries = new ArrayList<>();
        boolean opped = false;
        try {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            placeChestWith(helper, new BlockPos(2, 2, 2), new ItemStack(Items.STONE, 5));
            BlockPos start = helper.absolutePos(new BlockPos(1, 2, 1));
            BlockPos end = helper.absolutePos(new BlockPos(2, 2, 2));

            // 工程蓝图：现场扫描分支（sourceId 为空）
            C2S_CreateEngineeringBlueprintMessage.handleServer(
                    new C2S_CreateEngineeringBlueprintMessage("", "t10-eng", dim, start, end,
                            CaptureMode.SAFE, 0, "s", "l"), eng);
            ItemStack engStack = eng.getMainHandItem();
            helper.assertTrue(engStack.getItem() == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get(),
                    "engineering item gained");
            String engId = AbstractBlueprintItem.getBlueprintId(engStack);
            helper.assertTrue(engId != null && !engId.isEmpty(), "engineering blueprint_id");
            files.add(engId);
            helper.assertValueEqual(EngineeringBlueprintItem.getBoundProjectId(engStack), engId,
                    "eng_project_id bound");
            helper.assertValueEqual(EngineeringBlueprintItem.getBoundDimension(engStack), dim,
                    "eng dimension bound");
            helper.assertTrue(start.equals(EngineeringBlueprintItem.getBoundStartPos(engStack)),
                    "eng_start bound");
            helper.assertTrue(end.equals(EngineeringBlueprintItem.getBoundEndPos(engStack)),
                    "eng_end bound");
            BlueprintData engData = BlueprintStorage.load(engId);
            helper.assertValueEqual(engData.category, "engineering", "engineering category");
            helper.assertValueEqual(totalBlocks(engData), 3, "engineering 3 blocks");

            // 子工程：OP + 项目蓝图（服务端两点）+ 范围内箱子内容被清
            grantOp(helper, op);
            opped = true;
            UUID projectId = UUID.randomUUID();
            ProjectData project = new ProjectData();
            project.name = "t10-project";
            project.dimension = dim;
            // 项目范围必须真包含选区且不与选区完全一致（否则 same_as_project 拦截）
            project.origin = start.offset(-1, 0, -1);
            project.size = new BlockPos(4, 1, 4);
            project.anchor = project.origin;
            ProjectStorage.saveProject(projectId, project);
            projects.add(projectId);
            ItemStack projStack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(projStack, projectId);
            ProjectBlueprintItem.setRangePoint(projStack, 1, start);
            ProjectBlueprintItem.setRangePoint(projStack, 2, end);
            op.setItemInHand(InteractionHand.MAIN_HAND, projStack);

            C2S_CreateSubProjectMessage.handleServer(
                    new C2S_CreateSubProjectMessage("t10-sub", 5, 1, projectId.toString(), "s", "l"), op);
            ProjectData after = ProjectStorage.loadProject(projectId);
            helper.assertValueEqual(after.entries.size(), 1, "one sub entry added");
            ProjectData.EntryData entry = after.entries.get(0);
            helper.assertValueEqual(entry.index, 1, "entry index");
            helper.assertValueEqual(entry.label, "t10-sub", "entry label");
            entries.add(new String[]{projectId.toString(), entry.filename});
            BlueprintData sub = ProjectStorage.loadEntry(projectId, entry.filename);
            helper.assertTrue(sub != null, "sub entry file saved");
            helper.assertValueEqual(sub.category, "subproject", "subproject category");
            helper.assertValueEqual(sub.parentProjectId, projectId.toString(), "parent project id");
            helper.assertTrue(!findChestEntry(helper, sub).nbt.contains("Items"),
                    "sub entry chest stripped");
            helper.assertTrue(new BlockPos(1, 0, 1).equals(entry.relP1), "relP1 relative to anchor");
            helper.assertTrue(new BlockPos(2, 0, 2).equals(entry.relP2), "relP2 relative to anchor");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintValidator.clearCooldown(eng.getUUID());
            BlueprintValidator.clearCooldown(op.getUUID());
            if (opped) revokeOp(helper, op);
            for (String id : files) BlueprintStorage.delete(id);
            for (String[] e : entries) ProjectStorage.deleteEntryFile(UUID.fromString(e[0]), e[1]);
            for (UUID id : projects) ProjectStorage.deleteProject(id);
        }
        helper.succeed();
    }
}
