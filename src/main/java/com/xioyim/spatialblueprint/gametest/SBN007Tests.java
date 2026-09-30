package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintComponents;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_DiscardForeignBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_DiscardOrphanBagMessage;
import com.xioyim.spatialblueprint.network.C2S_DestroyForceBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_EngineeringTeleportMessage;
import com.xioyim.spatialblueprint.network.C2S_PreviewUpdateMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectEntriesMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestVoidBagsMessage;
import com.xioyim.spatialblueprint.network.C2S_SafeCoordMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidResetMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidTeleportMessage;
import com.xioyim.spatialblueprint.network.S2C_LibrarySyncMessage;
import com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage;
import com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage;
import com.xioyim.spatialblueprint.network.S2C_VoidBagSyncMessage;
import com.xioyim.spatialblueprint.network.S2C_WorldIdentityMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.TeleportPermissions;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SBN-007 轻量消息（P3b-2）GameTest（t01～t08）。
 * 伪造 / 越界包是唯一允许手写处。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN007Tests {

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

    private static FakePlayer fakePlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SB7" + UUID.randomUUID().toString().substring(0, 4)));
    }

    /**
     * 建一个真正在场、连接可用的服务端玩家（与 {@code GameTestHelper.makeMockServerPlayerInLevel}
     * 同构造，但该方法已被标记移除；这里直接走公开 API）。FakePlayer 的 net handler 把 teleport 覆写成
     * 空操作，验证「服务端真的移动了玩家」必须用它。
     */
    private static ServerPlayer mockPlayerInLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "SB7Mock");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static BlueprintData sampleBlueprint(String name) {
        BlueprintData data = new BlueprintData();
        data.name = name;
        data.category = "create";
        data.creatorName = "Tester";
        data.size = new BlockPos(2, 1, 1);
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        return data;
    }

    private static S2C_OperationResult firstOperationResult(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_OperationResult result) return result;
        }
        helper.fail("expected S2C_OperationResult");
        return null;
    }

    private static void assertPos(GameTestHelper helper, ServerPlayer player,
                                  double x, double y, double z, String what) {
        helper.assertTrue(Math.abs(player.getX() - x) < 1e-6
                && Math.abs(player.getY() - y) < 1e-6
                && Math.abs(player.getZ() - z) < 1e-6,
                what + " (at " + player.getX() + "," + player.getY() + "," + player.getZ() + ")");
    }

    private static ItemStack voidBlueprintWithTp(String dimension, double x, double y, double z) {
        ItemStack stack = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
        stack.set(SpatialBlueprintComponents.TP_DIMENSION.get(), dimension);
        stack.set(SpatialBlueprintComponents.TP_X.get(), x);
        stack.set(SpatialBlueprintComponents.TP_Y.get(), y);
        stack.set(SpatialBlueprintComponents.TP_Z.get(), z);
        return stack;
    }

    private static ItemStack boundEngineeringBlueprint(GameTestHelper helper, BlockPos start, BlockPos end) {
        ItemStack stack = new ItemStack(SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get());
        EngineeringBlueprintItem.setBoundProject(stack, UUID.randomUUID().toString(), "sbn007-eng",
                helper.getLevel().dimension().location().toString(), start, end);
        return stack;
    }

    // ── t01：注册与命名 ───────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_registration(GameTestHelper helper) {
        String[][] expected = {
                // SBN-005 的 8 个
                {"s2c_operation_result", S2C_OperationResult.TYPE.id().getPath()},
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
                {"s2c_library_sync_message", S2C_LibrarySyncMessage.TYPE.id().getPath()},
                {"c2s_rename_blueprint_message", com.xioyim.spatialblueprint.network.C2S_RenameBlueprintMessage.TYPE.id().getPath()},
                {"c2s_convert_blueprint_message", com.xioyim.spatialblueprint.network.C2S_ConvertBlueprintMessage.TYPE.id().getPath()},
                {"c2s_set_withdraw_property_message", com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage.TYPE.id().getPath()},
                {"c2s_delete_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DeleteBlueprintMessage.TYPE.id().getPath()},
                {"s2c_void_bag_sync_message", S2C_VoidBagSyncMessage.TYPE.id().getPath()},
                {"c2s_request_material_space_message", com.xioyim.spatialblueprint.network.C2S_RequestMaterialSpaceMessage.TYPE.id().getPath()},
                {"s2c_material_space_sync_message", com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage.TYPE.id().getPath()},
                {"c2s_material_deposit_message", com.xioyim.spatialblueprint.network.C2S_MaterialDepositMessage.TYPE.id().getPath()},
                {"c2s_request_blueprint_materials_message", com.xioyim.spatialblueprint.network.C2S_RequestBlueprintMaterialsMessage.TYPE.id().getPath()},
                {"s2c_blueprint_materials_message", com.xioyim.spatialblueprint.network.s2c.S2C_BlueprintMaterialsMessage.TYPE.id().getPath()},
                // SBN-007 的 21 个
                {"s2c_teleport_perm_message", S2C_TeleportPermMessage.TYPE.id().getPath()},
                {"s2c_world_identity_message", S2C_WorldIdentityMessage.TYPE.id().getPath()},
                {"c2s_request_void_bags_message", C2S_RequestVoidBagsMessage.TYPE.id().getPath()},
                {"c2s_discard_orphan_bag_message", C2S_DiscardOrphanBagMessage.TYPE.id().getPath()},
                {"c2s_destroy_force_blueprint_message", C2S_DestroyForceBlueprintMessage.TYPE.id().getPath()},
                {"c2s_discard_foreign_blueprint_message", C2S_DiscardForeignBlueprintMessage.TYPE.id().getPath()},
                {"c2s_void_reset_message", C2S_VoidResetMessage.TYPE.id().getPath()},
                {"c2s_void_teleport_message", C2S_VoidTeleportMessage.TYPE.id().getPath()},
                {"c2s_engineering_teleport_message", C2S_EngineeringTeleportMessage.TYPE.id().getPath()},
                {"c2s_safe_coord_message", C2S_SafeCoordMessage.TYPE.id().getPath()},
                {"c2s_preview_update_message", C2S_PreviewUpdateMessage.TYPE.id().getPath()},
                {"s2c_preview_broadcast_message", S2C_PreviewBroadcastMessage.TYPE.id().getPath()},
                {"s2c_placement_progress_message", S2C_PlacementProgressMessage.TYPE.id().getPath()},
                {"s2c_capture_cooldown_message", S2C_CaptureCooldownMessage.TYPE.id().getPath()},
                {"s2c_op_capture_confirm_message", S2C_OpCaptureConfirmMessage.TYPE.id().getPath()},
                {"c2s_request_project_list_message", C2S_RequestProjectListMessage.TYPE.id().getPath()},
                {"s2c_project_list_message", S2C_ProjectListMessage.TYPE.id().getPath()},
                {"c2s_request_project_entries_message", C2S_RequestProjectEntriesMessage.TYPE.id().getPath()},
                {"s2c_project_entries_message", S2C_ProjectEntriesMessage.TYPE.id().getPath()},
                {"c2s_request_project_status_message", C2S_RequestProjectStatusMessage.TYPE.id().getPath()},
                {"s2c_project_status_message", S2C_ProjectStatusMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[0], pair[1], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(53, ids.size(), "53 unique payload ids");
        helper.assertValueEqual(SpatialBlueprintMod.MODID, S2C_ProjectStatusMessage.TYPE.id().getNamespace(), "namespace");
        helper.succeed();
    }

    // ── t02：21 个消息编码往返 ────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codecRoundTrip(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        S2C_ProjectStatusMessage.EntryStatus entry = new S2C_ProjectStatusMessage.EntryStatus(
                1, "入口", "备注", 3, 4, 5, 60,
                false, 0, 0, true,
                -1, "Builder", 123L,
                new BlockPos(1, 2, 3), new BlockPos(4, 6, 8),
                0xFF00FF00, "eng-1", "详细备注");
        S2C_ProjectStatusMessage status = new S2C_ProjectStatusMessage(
                true, playerId.toString(), "项目", "ts-1", true, List.of(entry),
                true, new BlockPos(10, 64, 10), new BlockPos(8, 4, 8),
                new BlockPos(9, 63, 9), new BlockPos(8, 4, 8),
                true, new BlockPos(13, 65, 13), new BlockPos(13, 65, 13),
                false, "minecraft:overworld");

        assertRoundTrip(helper, S2C_TeleportPermMessage.STREAM_CODEC, new S2C_TeleportPermMessage(true));
        assertRoundTrip(helper, S2C_WorldIdentityMessage.STREAM_CODEC,
                new S2C_WorldIdentityMessage("uuid-1", "存档一"));
        assertRoundTrip(helper, C2S_RequestVoidBagsMessage.STREAM_CODEC,
                new C2S_RequestVoidBagsMessage(playerId.toString(), "玩家甲"));
        assertRoundTrip(helper, C2S_DiscardOrphanBagMessage.STREAM_CODEC,
                new C2S_DiscardOrphanBagMessage("bp-1"));
        assertRoundTrip(helper, C2S_DestroyForceBlueprintMessage.STREAM_CODEC,
                new C2S_DestroyForceBlueprintMessage());
        assertRoundTrip(helper, C2S_DiscardForeignBlueprintMessage.STREAM_CODEC,
                new C2S_DiscardForeignBlueprintMessage());
        assertRoundTrip(helper, C2S_VoidResetMessage.STREAM_CODEC, new C2S_VoidResetMessage());
        assertRoundTrip(helper, C2S_VoidTeleportMessage.STREAM_CODEC, new C2S_VoidTeleportMessage());
        assertRoundTrip(helper, C2S_EngineeringTeleportMessage.STREAM_CODEC,
                new C2S_EngineeringTeleportMessage());
        assertRoundTrip(helper, C2S_SafeCoordMessage.STREAM_CODEC,
                new C2S_SafeCoordMessage("bp-1", true, 1, 64, -3));
        assertRoundTrip(helper, C2S_PreviewUpdateMessage.STREAM_CODEC,
                new C2S_PreviewUpdateMessage(new BlockPos(1, 2, 3), 1, 4, 5, 6, "bp-1", "create"));
        assertRoundTrip(helper, C2S_PreviewUpdateMessage.STREAM_CODEC,
                new C2S_PreviewUpdateMessage(null, 0, 1, 1, 1, "", ""));
        assertRoundTrip(helper, S2C_PreviewBroadcastMessage.STREAM_CODEC,
                new S2C_PreviewBroadcastMessage(UUID.randomUUID(), "玩家乙", "bp-1", "create",
                        new BlockPos(7, 8, 9), 3, 2, 3, 4));
        assertRoundTrip(helper, S2C_PreviewBroadcastMessage.STREAM_CODEC,
                new S2C_PreviewBroadcastMessage(UUID.randomUUID(), "玩家乙", "", "", null, 0, 0, 0, 0));
        assertRoundTrip(helper, S2C_PlacementProgressMessage.STREAM_CODEC,
                new S2C_PlacementProgressMessage(0.5f));
        assertRoundTrip(helper, S2C_CaptureCooldownMessage.STREAM_CODEC,
                new S2C_CaptureCooldownMessage(15));
        assertRoundTrip(helper, S2C_OpCaptureConfirmMessage.STREAM_CODEC,
                new S2C_OpCaptureConfirmMessage("名称", true, 30, FluidCaptureFilter.NO_WATER));
        assertRoundTrip(helper, C2S_RequestProjectListMessage.STREAM_CODEC,
                new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_STATION));
        assertRoundTrip(helper, S2C_ProjectListMessage.STREAM_CODEC,
                new S2C_ProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE,
                        List.of(new S2C_ProjectListMessage.ProjectSummary(
                                playerId.toString(), "项目", ProjectData.Kind.PRIVATE, ProjectData.Stage.LAYOUT,
                                "短备注", "长备注", "玩家甲", 123L, 3, 1, false))));
        assertRoundTrip(helper, C2S_RequestProjectEntriesMessage.STREAM_CODEC,
                new C2S_RequestProjectEntriesMessage(playerId.toString()));
        assertRoundTrip(helper, S2C_ProjectEntriesMessage.STREAM_CODEC,
                new S2C_ProjectEntriesMessage(playerId.toString(), true, "项目",
                        new BlockPos(1, 2, 3), new BlockPos(4, 5, 6),
                        List.of(new S2C_ProjectEntriesMessage.Entry(1, "入口"))));
        assertRoundTrip(helper, C2S_RequestProjectStatusMessage.STREAM_CODEC,
                new C2S_RequestProjectStatusMessage());
        assertRoundTrip(helper, S2C_ProjectStatusMessage.STREAM_CODEC, status);
        helper.succeed();
    }

    // ── t03：decode 上限（抛出 / 截断）────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_decodeGuards(GameTestHelper helper) {
        // ProjectList：projects 计数 4097 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("public", 16);
                buf.writeVarInt(4097);
                S2C_ProjectListMessage.decode(buf);
            } finally { buf.release(); }
        }, "ProjectList projects=4097");

        // ProjectEntries：entries 计数 4097 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("project-1", 64);
                buf.writeBoolean(true);
                buf.writeUtf("项目", 128);
                buf.writeBlockPos(BlockPos.ZERO);
                buf.writeBlockPos(BlockPos.ZERO);
                buf.writeVarInt(4097);
                S2C_ProjectEntriesMessage.decode(buf);
            } finally { buf.release(); }
        }, "ProjectEntries entries=4097");

        // ProjectStatus：entryStatuses 计数 4097 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeBoolean(true);
                buf.writeUtf("project-1", 64);
                buf.writeUtf("项目", 128);
                buf.writeUtf("ts-1", 64);
                buf.writeBoolean(true);
                buf.writeVarInt(4097);
                S2C_ProjectStatusMessage.decode(buf);
            } finally { buf.release(); }
        }, "ProjectStatus entryStatuses=4097");

        // 字符串长度上限样本 1：RequestVoidBags 的 targetUuid 上限 64 → 手写 193 字节 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(193);
                for (int i = 0; i < 193; i++) buf.writeByte('a');
                C2S_RequestVoidBagsMessage.decode(buf);
            } finally { buf.release(); }
        }, "RequestVoidBags targetUuid cap 64 (193 bytes)");

        // 字符串长度上限样本 2：PreviewBroadcast 的 playerName 上限 40 → 手写 121 字节 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUUID(UUID.randomUUID());
                buf.writeVarInt(121);
                for (int i = 0; i < 121; i++) buf.writeByte('a');
                S2C_PreviewBroadcastMessage.decode(buf);
            } finally { buf.release(); }
        }, "PreviewBroadcast playerName cap 40 (121 bytes)");

        helper.succeed();
    }

    // ── t04：次元袋请求（查别人需 OP）─────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_voidBags(GameTestHelper helper) {
        FakePlayer a = fakePlayer(helper);
        FakePlayer b = fakePlayer(helper);
        FakePlayer op = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        boolean opped = false;
        try {
            // B（非 OP）请求 A 的次元袋 → 无回包
            received.clear();
            C2S_RequestVoidBagsMessage.handleServer(
                    new C2S_RequestVoidBagsMessage(a.getUUID().toString(), "A"), b);
            helper.assertTrue(received.isEmpty(), "non-op viewing others gets no reply");

            // A 请求自己 → 收到 S2C_VoidBagSyncMessage
            received.clear();
            C2S_RequestVoidBagsMessage.handleServer(
                    new C2S_RequestVoidBagsMessage(a.getUUID().toString(), "A"), a);
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_VoidBagSyncMessage),
                    "self request gets sync");

            // OP 请求 A 的 → 收到（K11：直接写 ServerOpListEntry 等级 4）
            helper.getLevel().getServer().getPlayerList().getOps().add(
                    new net.minecraft.server.players.ServerOpListEntry(op.getGameProfile(), 4, false));
            opped = true;
            received.clear();
            C2S_RequestVoidBagsMessage.handleServer(
                    new C2S_RequestVoidBagsMessage(a.getUUID().toString(), "A"), op);
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_VoidBagSyncMessage),
                    "op viewing others gets sync");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) helper.getLevel().getServer().getPlayerList().deop(op.getGameProfile());
        }
        helper.succeed();
    }

    // ── t05：空置次元蓝图重置（归属 / 存档归属）──────────────────────────────

    @GameTest(template = "empty")
    public static void t05_voidReset(GameTestHelper helper) {
        FakePlayer a = fakePlayer(helper);
        FakePlayer b = fakePlayer(helper);
        String currentWorld = WorldIdentity.get(helper.getLevel()).uuid().toString();

        BlueprintData mine = sampleBlueprint("t05-mine");
        mine.category = "dimensional";
        mine.ownerUuid = a.getUUID().toString();
        mine.worldUuid = currentWorld;
        UUID mineId = BlueprintStorage.save(mine);

        BlueprintData foreign = sampleBlueprint("t05-foreign");
        foreign.category = "dimensional";
        foreign.ownerUuid = a.getUUID().toString();
        foreign.worldUuid = UUID.randomUUID().toString();
        UUID foreignId = BlueprintStorage.save(foreign);

        ItemStack mineItem = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
        mineItem.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), mineId.toString());
        mineItem.set(SpatialBlueprintComponents.WORLD_UUID.get(), currentWorld);
        a.setItemInHand(InteractionHand.MAIN_HAND, mineItem);
        b.setItemInHand(InteractionHand.MAIN_HAND, mineItem.copy());

        ItemStack foreignItem = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
        foreignItem.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), foreignId.toString());
        foreignItem.set(SpatialBlueprintComponents.WORLD_UUID.get(), foreign.worldUuid);

        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        try {
            // B 持同一枚（非主人）→ 被拒，文件不变、物品不变
            received.clear();
            C2S_VoidResetMessage.handleServer(new C2S_VoidResetMessage(), b);
            S2C_OperationResult denied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.void_blueprint.not_owner",
                    denied.messageKey, "non-owner denied");
            helper.assertTrue(BlueprintStorage.findFile(mineId.toString()) != null, "file kept after denial");
            helper.assertTrue(b.getMainHandItem().getItem()
                            == SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get(),
                    "non-owner item untouched");

            // A（主人）→ 重置成功：文件删除、物品换成空白蓝图、收到列表与成功回包
            received.clear();
            C2S_VoidResetMessage.handleServer(new C2S_VoidResetMessage(), a);
            helper.assertTrue(BlueprintStorage.findFile(mineId.toString()) == null, "file deleted by owner");
            helper.assertTrue(a.getMainHandItem().getItem() == SpatialBlueprintItems.BLANK_BLUEPRINT.get(),
                    "owner item replaced by blank");
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_LibrarySyncMessage),
                    "library sync pushed after reset");
            S2C_OperationResult ok = firstOperationResult(helper, received);
            helper.assertTrue(ok.success, "reset reported success");

            // 别的存档的空置次元蓝图 → foreign 分支，文件不变
            a.setItemInHand(InteractionHand.MAIN_HAND, foreignItem);
            received.clear();
            C2S_VoidResetMessage.handleServer(new C2S_VoidResetMessage(), a);
            S2C_OperationResult foreignFail = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.gui.library.foreign_world",
                    foreignFail.messageKey, "foreign world refused");
            helper.assertTrue(BlueprintStorage.findFile(foreignId.toString()) != null, "foreign file kept");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintStorage.delete(mineId.toString());
            BlueprintStorage.delete(foreignId.toString());
        }
        helper.succeed();
    }

    // ── t06：传送（权限 / 冷却 / 实际移动）────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_teleport(GameTestHelper helper) {
        String dim = helper.getLevel().dimension().location().toString();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        TeleportPermissions perms = TeleportPermissions.get(helper.getLevel());
        List<UUID> granted = new ArrayList<>();
        List<ServerPlayer> mockPlayers = new ArrayList<>();
        try {
            // 1) 不允许的玩家 → 不移动 + 拒绝回包
            FakePlayer denied = fakePlayer(helper);
            denied.setGameMode(GameType.SURVIVAL);
            denied.setPos(5.0, 64.0, 5.0);
            denied.setItemInHand(InteractionHand.MAIN_HAND, voidBlueprintWithTp(dim, 30.0, 70.0, -30.0));
            received.clear();
            C2S_VoidTeleportMessage.handleServer(new C2S_VoidTeleportMessage(), denied);
            S2C_OperationResult noPerm = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.teleport.no_permission",
                    noPerm.messageKey, "void teleport denied without permission");
            assertPos(helper, denied, 5.0, 64.0, 5.0, "denied player did not move");

            // 2) 允许的玩家（假玩家不能真正 teleportTo → 用在场 mock 玩家验证实际移动）
            ServerPlayer mover = mockPlayerInLevel(helper);
            mockPlayers.add(mover);
            mover.setPos(0.0, 64.0, 0.0);
            mover.setItemInHand(InteractionHand.MAIN_HAND, voidBlueprintWithTp(dim, 30.0, 70.0, -30.0));
            perms.grant(mover.getUUID());
            granted.add(mover.getUUID());
            helper.assertTrue(TeleportPermissions.canTeleport(mover), "granted can teleport");
            C2S_VoidTeleportMessage.handleServer(new C2S_VoidTeleportMessage(), mover);
            assertPos(helper, mover, 30.5, 70.0, -29.5, "void teleport moved to tp coord");

            // 3) 冷却：同一玩家紧接着再发 → 冷却拒绝（用假玩家观察回包；改目标以便区分）
            FakePlayer spam = fakePlayer(helper);
            spam.setItemInHand(InteractionHand.MAIN_HAND, voidBlueprintWithTp(dim, 40.0, 70.0, -40.0));
            perms.grant(spam.getUUID());
            granted.add(spam.getUUID());
            received.clear();
            C2S_VoidTeleportMessage.handleServer(new C2S_VoidTeleportMessage(), spam);
            helper.assertTrue(received.isEmpty(), "first allowed teleport sends no rejection");
            received.clear();
            C2S_VoidTeleportMessage.handleServer(new C2S_VoidTeleportMessage(), spam);
            S2C_OperationResult cooldown = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.teleport.cooldown",
                    cooldown.messageKey, "second teleport within cooldown rejected");
            helper.assertTrue(TeleportPermissions.tryAcquireTeleport(spam) > 0, "cooldown still active");

            // 4) 工程传送：不允许 → 拒绝
            FakePlayer engDenied = fakePlayer(helper);
            engDenied.setGameMode(GameType.SURVIVAL);
            engDenied.setItemInHand(InteractionHand.MAIN_HAND,
                    boundEngineeringBlueprint(helper, new BlockPos(10, 64, 10), new BlockPos(20, 64, 20)));
            received.clear();
            C2S_EngineeringTeleportMessage.handleServer(new C2S_EngineeringTeleportMessage(), engDenied);
            S2C_OperationResult engNoPerm = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.teleport.no_permission",
                    engNoPerm.messageKey, "engineering teleport denied without permission");

            // 5) 工程传送：允许 → 实际移动到近的那一端；再发 → 冷却拒绝
            ServerPlayer engMover = mockPlayerInLevel(helper);
            mockPlayers.add(engMover);
            engMover.setPos(0.0, 64.0, 0.0);
            engMover.setItemInHand(InteractionHand.MAIN_HAND,
                    boundEngineeringBlueprint(helper, new BlockPos(10, 64, 10), new BlockPos(40, 64, 40)));
            perms.grant(engMover.getUUID());
            granted.add(engMover.getUUID());
            C2S_EngineeringTeleportMessage.handleServer(new C2S_EngineeringTeleportMessage(), engMover);
            assertPos(helper, engMover, 10.5, 64.0, 10.5, "engineering teleport moved to bound start");

            FakePlayer engSpam = fakePlayer(helper);
            engSpam.setItemInHand(InteractionHand.MAIN_HAND,
                    boundEngineeringBlueprint(helper, new BlockPos(10, 64, 10), new BlockPos(40, 64, 40)));
            perms.grant(engSpam.getUUID());
            granted.add(engSpam.getUUID());
            received.clear();
            C2S_EngineeringTeleportMessage.handleServer(new C2S_EngineeringTeleportMessage(), engSpam);
            helper.assertTrue(received.isEmpty(), "first allowed engineering teleport sends no rejection");
            received.clear();
            C2S_EngineeringTeleportMessage.handleServer(new C2S_EngineeringTeleportMessage(), engSpam);
            S2C_OperationResult engCooldown = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.teleport.cooldown",
                    engCooldown.messageKey, "second engineering teleport within cooldown rejected");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            for (UUID id : granted) perms.revoke(id);
            for (ServerPlayer player : mockPlayers) {
                helper.getLevel().getServer().getPlayerList().remove(player);
            }
        }
        helper.succeed();
    }

    // ── t07：项目只读（STATION 需 OP / PRIVATE 归属 / 不存在项目）──────────────

    @GameTest(template = "empty")
    public static void t07_projectReadOnly(GameTestHelper helper) {
        FakePlayer a = fakePlayer(helper);
        FakePlayer b = fakePlayer(helper);
        FakePlayer looker = fakePlayer(helper);

        ProjectData station = new ProjectData();
        station.name = "sbn007-station";
        station.kind = ProjectData.Kind.STATION;
        station.stage = ProjectData.Stage.PREPARING;
        UUID stationId = UUID.randomUUID();
        ProjectStorage.saveProject(stationId, station);

        ProjectData priv = new ProjectData();
        priv.name = "sbn007-private";
        priv.kind = ProjectData.Kind.PRIVATE;
        priv.stage = ProjectData.Stage.PREPARING;
        priv.ownerUuid = a.getUUID().toString();
        priv.ownerName = a.getScoreboardName();
        priv.createdAt = 1000L;
        UUID privId = UUID.randomUUID();
        ProjectStorage.saveProject(privId, priv);

        ItemStack statusProbe = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
        statusProbe.set(SpatialBlueprintComponents.PROJECT_ID.get(), UUID.randomUUID().toString());
        looker.setItemInHand(InteractionHand.MAIN_HAND, statusProbe);

        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        try {
            // 非 OP 请求 STATION → 无回包
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_STATION), b);
            helper.assertTrue(received.isEmpty(), "station list denied for non-op");

            // A（主人、非 OP）请求 PRIVATE → 列表含该项目
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE), a);
            S2C_ProjectListMessage mine = firstProjectList(helper, received);
            helper.assertTrue(containsProject(mine, privId.toString()), "owner sees private project");

            // B 请求 PRIVATE → 列表不含
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE), b);
            S2C_ProjectListMessage others = firstProjectList(helper, received);
            helper.assertTrue(!containsProject(others, privId.toString()), "non-owner does not see private project");

            // 请求不存在的项目状态 → found = false
            received.clear();
            C2S_RequestProjectStatusMessage.handleServer(new C2S_RequestProjectStatusMessage(), looker);
            S2C_ProjectStatusMessage status = firstProjectStatus(helper, received);
            helper.assertTrue(!status.found, "missing project reports found=false");
            helper.assertValueEqual(statusProbe.get(SpatialBlueprintComponents.PROJECT_ID.get()),
                    status.projectId, "status carries the requested project id");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            ProjectStorage.deleteProject(stationId);
            ProjectStorage.deleteProject(privId);
        }
        helper.succeed();
    }

    private static S2C_ProjectListMessage firstProjectList(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_ProjectListMessage list) return list;
        }
        helper.fail("expected S2C_ProjectListMessage");
        return null;
    }

    private static boolean containsProject(S2C_ProjectListMessage msg, String projectId) {
        return msg.projects.stream().anyMatch(p -> p.projectId().equals(projectId));
    }

    private static S2C_ProjectStatusMessage firstProjectStatus(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_ProjectStatusMessage status) return status;
        }
        helper.fail("expected S2C_ProjectStatusMessage");
        return null;
    }

    // ── t08：预览广播（范围内 / 范围外）──────────────────────────────────────

    @GameTest(template = "empty")
    public static void t08_previewBroadcast(GameTestHelper helper) {
        FakePlayer a = fakePlayer(helper);
        FakePlayer b = fakePlayer(helper);
        FakePlayer c = fakePlayer(helper);
        a.setPos(100.5, 64.0, 100.5);
        b.setPos(110.5, 64.0, 110.5);
        c.setPos(100.5, 64.0, 100.5);
        helper.getLevel().players().add(a);
        helper.getLevel().players().add(b);
        helper.getLevel().players().add(c);

        Map<UUID, List<CustomPacketPayload>> byTarget = new HashMap<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) ->
                byTarget.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(payload);
        try {
            // A 发预览（origin 在 A 附近、B 在广播半径内）→ B 收到广播
            C2S_PreviewUpdateMessage.handleServer(
                    new C2S_PreviewUpdateMessage(new BlockPos(105, 64, 105), 0, 3, 3, 3, "bp-1", "create"), a);
            List<CustomPacketPayload> gotB = byTarget.getOrDefault(b.getUUID(), List.of());
            helper.assertValueEqual(1, gotB.size(), "B received one broadcast");
            helper.assertTrue(gotB.get(0) instanceof S2C_PreviewBroadcastMessage, "broadcast type");
            S2C_PreviewBroadcastMessage broadcast = (S2C_PreviewBroadcastMessage) gotB.get(0);
            helper.assertValueEqual(a.getUUID(), broadcast.playerUUID, "broadcast carries sender uuid");

            // B 移到广播半径外；换个来源玩家（C）再发（同一玩家 2 tick 内会被速率限制丢弃）
            b.setPos(2000.5, 64.0, 2000.5);
            C2S_PreviewUpdateMessage.handleServer(
                    new C2S_PreviewUpdateMessage(new BlockPos(106, 64, 106), 0, 3, 3, 3, "bp-1", "create"), c);
            helper.assertValueEqual(1, byTarget.getOrDefault(b.getUUID(), List.of()).size(),
                    "B out of range receives nothing new");
            // 正对照：范围内的 A 收到了 C 的广播
            helper.assertTrue(byTarget.getOrDefault(a.getUUID(), List.of()).stream()
                            .anyMatch(p -> p instanceof S2C_PreviewBroadcastMessage),
                    "in-range A still receives C's broadcast");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            helper.getLevel().players().remove(a);
            helper.getLevel().players().remove(b);
            helper.getLevel().players().remove(c);
        }
        helper.succeed();
    }
}
