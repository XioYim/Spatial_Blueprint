package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.C2S_ApplyBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_ClaimSubProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_CreateProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_DeleteProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_DeleteSubProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_RewardMaterialsMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestIllegalBlocksMessage;
import com.xioyim.spatialblueprint.network.C2S_SaveMaterialListMessage;
import com.xioyim.spatialblueprint.network.C2S_SaveProjectRangeMessage;
import com.xioyim.spatialblueprint.network.C2S_SubmitProjectChangesMessage;
import com.xioyim.spatialblueprint.network.C2S_SubmitProjectOrderMessage;
import com.xioyim.spatialblueprint.network.C2S_TakeProjectMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SBN-008 项目写操作 / 蓝图应用 / 材料清单 / 非法方块查询（P3c-1）GameTest（t01～t09）。
 * 伪造 / 越界包是唯一允许手写处。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN008Tests {

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
                new GameProfile(UUID.randomUUID(), "SB8" + UUID.randomUUID().toString().substring(0, 4)));
    }

    private static void grantOp(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().getOps().add(
                new ServerOpListEntry(player.getGameProfile(), 4, false));
    }

    private static void revokeOp(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
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

    private static S2C_IllegalBlocksListMessage firstIllegalBlocks(
            GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_IllegalBlocksListMessage list) return list;
        }
        helper.fail("expected S2C_IllegalBlocksListMessage");
        return null;
    }

    private static ProjectClaimRegistry.Claim addClaim(GameTestHelper helper, String projectId,
                                                       boolean publicProject, String ownerUuid,
                                                       int minX, int minY, int minZ,
                                                       int maxX, int maxY, int maxZ) {
        ProjectClaimRegistry.Claim claim = new ProjectClaimRegistry.Claim();
        claim.taskSetId = UUID.randomUUID().toString();
        claim.projectId = projectId;
        claim.dimension = ProjectData.dimensionOf(helper.getLevel());
        claim.publicProject = publicProject;
        claim.ownerUuid = ownerUuid == null ? "" : ownerUuid;
        claim.ownerName = "Tester";
        claim.minX = minX; claim.minY = minY; claim.minZ = minZ;
        claim.maxX = maxX; claim.maxY = maxY; claim.maxZ = maxZ;
        ProjectClaimRegistry.get(helper.getLevel()).add(claim);
        return claim;
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
                {"s2c_illegal_blocks_list_message", S2C_IllegalBlocksListMessage.TYPE.id().getPath()},
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
                {"s2c_capture_cooldown_message", com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage.TYPE.id().getPath()},
                {"s2c_op_capture_confirm_message", com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage.TYPE.id().getPath()},
                {"c2s_request_project_list_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage.TYPE.id().getPath()},
                {"s2c_project_list_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage.TYPE.id().getPath()},
                {"c2s_request_project_entries_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectEntriesMessage.TYPE.id().getPath()},
                {"s2c_project_entries_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage.TYPE.id().getPath()},
                {"c2s_request_project_status_message", com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage.TYPE.id().getPath()},
                {"s2c_project_status_message", com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage.TYPE.id().getPath()},
                // SBN-008 的 12 个
                {"c2s_create_project_message", C2S_CreateProjectMessage.TYPE.id().getPath()},
                {"c2s_delete_project_message", C2S_DeleteProjectMessage.TYPE.id().getPath()},
                {"c2s_take_project_message", C2S_TakeProjectMessage.TYPE.id().getPath()},
                {"c2s_submit_project_order_message", C2S_SubmitProjectOrderMessage.TYPE.id().getPath()},
                {"c2s_submit_project_changes_message", C2S_SubmitProjectChangesMessage.TYPE.id().getPath()},
                {"c2s_delete_sub_project_message", C2S_DeleteSubProjectMessage.TYPE.id().getPath()},
                {"c2s_save_project_range_message", C2S_SaveProjectRangeMessage.TYPE.id().getPath()},
                {"c2s_claim_sub_project_message", C2S_ClaimSubProjectMessage.TYPE.id().getPath()},
                {"c2s_apply_blueprint_message", C2S_ApplyBlueprintMessage.TYPE.id().getPath()},
                {"c2s_save_material_list_message", C2S_SaveMaterialListMessage.TYPE.id().getPath()},
                {"c2s_reward_materials_message", C2S_RewardMaterialsMessage.TYPE.id().getPath()},
                {"c2s_request_illegal_blocks_message", C2S_RequestIllegalBlocksMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[0], pair[1], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(65, ids.size(), "65 unique payload ids");
        helper.assertValueEqual(SpatialBlueprintMod.MODID, C2S_RequestIllegalBlocksMessage.TYPE.id().getNamespace(), "namespace");
        helper.succeed();
    }

    // ── t02：12 个消息编码往返 ────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codecRoundTrip(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        tag.putString("k", "v");

        MaterialList ml = new MaterialList();
        ml.blockCounts.put("minecraft:stone", 5);
        ml.keyTags.put("minecraft:stone", tag);
        Map<Integer, MaterialList> materials = new LinkedHashMap<>();
        materials.put(1, ml);

        assertRoundTrip(helper, C2S_CreateProjectMessage.STREAM_CODEC,
                new C2S_CreateProjectMessage("项目名", "短备注", "长备注"));
        assertRoundTrip(helper, C2S_DeleteProjectMessage.STREAM_CODEC,
                new C2S_DeleteProjectMessage("pid-1", "public"));
        assertRoundTrip(helper, C2S_TakeProjectMessage.STREAM_CODEC,
                new C2S_TakeProjectMessage("pid-1", C2S_TakeProjectMessage.MODE_PLAN));
        assertRoundTrip(helper, C2S_SubmitProjectOrderMessage.STREAM_CODEC,
                new C2S_SubmitProjectOrderMessage(List.of(3, 1, 2)));
        assertRoundTrip(helper, C2S_SubmitProjectChangesMessage.STREAM_CODEC,
                new C2S_SubmitProjectChangesMessage("pid-1", List.of(2, 3), List.of(1), materials));
        assertRoundTrip(helper, C2S_DeleteSubProjectMessage.STREAM_CODEC,
                new C2S_DeleteSubProjectMessage("pid-1", 2));
        assertRoundTrip(helper, C2S_SaveProjectRangeMessage.STREAM_CODEC, new C2S_SaveProjectRangeMessage());
        assertRoundTrip(helper, C2S_ClaimSubProjectMessage.STREAM_CODEC, new C2S_ClaimSubProjectMessage(3));
        assertRoundTrip(helper, C2S_ApplyBlueprintMessage.STREAM_CODEC, new C2S_ApplyBlueprintMessage("bid-1"));
        assertRoundTrip(helper, C2S_SaveMaterialListMessage.STREAM_CODEC,
                new C2S_SaveMaterialListMessage("bid-1",
                        Map.of("minecraft:stone", 3), Map.of("minecraft:stone", tag)));
        assertRoundTrip(helper, C2S_RewardMaterialsMessage.STREAM_CODEC,
                new C2S_RewardMaterialsMessage("bid-1", C2S_RewardMaterialsMessage.MODE_GENERATE));
        assertRoundTrip(helper, C2S_RequestIllegalBlocksMessage.STREAM_CODEC,
                new C2S_RequestIllegalBlocksMessage(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6)));
        helper.succeed();
    }

    // ── t03：decode 上限（C20 口径）──────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_decodeGuards(GameTestHelper helper) {
        // SubmitProjectOrder：count 1025 > MAX_ORDER → 1.20.1 语义是「返回空列表」而非抛
        {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(1025);
                C2S_SubmitProjectOrderMessage msg = C2S_SubmitProjectOrderMessage.decode(buf);
                RegistryFriendlyByteBuf out = newBuf(helper);
                try {
                    C2S_SubmitProjectOrderMessage.STREAM_CODEC.encode(out, msg);
                    helper.assertValueEqual(0, out.readVarInt(), "order over MAX_ORDER degrades to empty");
                } finally { out.release(); }
            } finally { buf.release(); }
        }
        // SubmitProjectOrder：负数 count 同样回空列表（1.20.1 语义）
        {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(-1);
                C2S_SubmitProjectOrderMessage msg = C2S_SubmitProjectOrderMessage.decode(buf);
                RegistryFriendlyByteBuf out = newBuf(helper);
                try {
                    C2S_SubmitProjectOrderMessage.STREAM_CODEC.encode(out, msg);
                    helper.assertValueEqual(0, out.readVarInt(), "order negative count degrades to empty");
                } finally { out.release(); }
            } finally { buf.release(); }
        }
        // CreateProject：noteShort 上限 15 → 手写 46 字节 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("n", 128);
                buf.writeVarInt(46);
                for (int i = 0; i < 46; i++) buf.writeByte('a');
                C2S_CreateProjectMessage.decode(buf);
            } finally { buf.release(); }
        }, "CreateProject noteShort cap 15 (46 bytes)");
        // SubmitProjectChanges：材料键上限 128 → 手写 385 字节 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("pid-1", 64);
                buf.writeVarInt(0);   // order
                buf.writeVarInt(0);   // deletes
                buf.writeVarInt(1);   // materials
                buf.writeVarInt(1);   // entryIndex
                buf.writeVarInt(1);   // blockCounts size
                buf.writeVarInt(385);
                for (int i = 0; i < 385; i++) buf.writeByte('a');
                C2S_SubmitProjectChangesMessage.decode(buf);
            } finally { buf.release(); }
        }, "SubmitProjectChanges key cap 128 (385 bytes)");
        helper.succeed();
    }

    // ── t04：项目创建 / 删除（OP 判定）──────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_projectLifecycle(GameTestHelper helper) {
        FakePlayer nonOp = fakePlayer(helper);
        FakePlayer op = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<UUID> cleanup = new ArrayList<>();
        boolean opped = false;
        try {
            Set<UUID> before = new HashSet<>(ProjectStorage.listProjectIds());

            // 非 OP CreateProject → 拒绝、无新项目
            received.clear();
            C2S_CreateProjectMessage.handleServer(new C2S_CreateProjectMessage("t04-denied", "", ""), nonOp);
            S2C_OperationResult denied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.project.need_op", denied.messageKey, "create denied");
            helper.assertValueEqual(before.size(), ProjectStorage.listProjectIds().size(), "no project created");

            // OP CreateProject → 建出、ownerUuid = 该玩家
            grantOp(helper, op);
            opped = true;
            C2S_CreateProjectMessage.handleServer(new C2S_CreateProjectMessage("t04-created", "短", "长"), op);
            UUID created = null;
            for (UUID id : ProjectStorage.listProjectIds()) {
                if (!before.contains(id)) created = id;
            }
            helper.assertTrue(created != null, "op created a project");
            cleanup.add(created);
            ProjectData data = ProjectStorage.loadProject(created);
            helper.assertValueEqual(op.getUUID().toString(), data.ownerUuid, "owner uuid");
            helper.assertValueEqual("t04-created", data.name, "project name");

            // OP DeleteProject（无任务集占用）→ 删除
            C2S_DeleteProjectMessage.handleServer(new C2S_DeleteProjectMessage(created.toString(), "public"), op);
            helper.assertTrue(!ProjectStorage.projectExists(created), "op deleted project");
            cleanup.remove(created);

            // 非 OP DeleteProject → 拒绝、文件仍在；OP → 删除
            UUID direct = UUID.randomUUID();
            ProjectData directData = new ProjectData();
            directData.name = "t04-direct";
            directData.dimension = ProjectData.dimensionOf(helper.getLevel());
            ProjectStorage.saveProject(direct, directData);
            cleanup.add(direct);
            received.clear();
            C2S_DeleteProjectMessage.handleServer(
                    new C2S_DeleteProjectMessage(direct.toString(), "public"), nonOp);
            S2C_OperationResult deleteDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.project.delete.need_op",
                    deleteDenied.messageKey, "delete denied for non-op");
            helper.assertTrue(ProjectStorage.projectExists(direct), "file kept after denied delete");
            C2S_DeleteProjectMessage.handleServer(
                    new C2S_DeleteProjectMessage(direct.toString(), "public"), op);
            helper.assertTrue(!ProjectStorage.projectExists(direct), "op deleted direct project");
            cleanup.remove(direct);
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, op);
            for (UUID id : cleanup) ProjectStorage.deleteProject(id);
        }
        helper.succeed();
    }

    // ── t05：蓝图应用 / 项目取用 ─────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t05_applyAndTake(GameTestHelper helper) {
        FakePlayer a = fakePlayer(helper);
        FakePlayer b = fakePlayer(helper);
        FakePlayer op = fakePlayer(helper);
        String dim = ProjectData.dimensionOf(helper.getLevel());
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<UUID> bpFiles = new ArrayList<>();
        List<UUID> projects = new ArrayList<>();
        String claimTaskSetId = null;
        boolean opped = false;
        try {
            // ── 次元蓝图：仅主人可应用 ────────────────────────────────
            BlueprintData dimData = sampleBlueprint("t05-dim");
            dimData.category = "dimensional";
            dimData.ownerUuid = a.getUUID().toString();
            dimData.animDuration = 5;
            dimData.saveMode = CaptureMode.FORCE;
            UUID dimId = BlueprintStorage.save(dimData);
            bpFiles.add(dimId);

            a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));

            received.clear();
            C2S_ApplyBlueprintMessage.handleServer(new C2S_ApplyBlueprintMessage(dimId.toString()), b);
            S2C_OperationResult dimDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.void_blueprint.not_owner",
                    dimDenied.messageKey, "non-owner apply denied");
            helper.assertTrue(b.getMainHandItem().getItem() == SpatialBlueprintItems.BLANK_BLUEPRINT.get(),
                    "non-owner hand untouched");

            received.clear();
            C2S_ApplyBlueprintMessage.handleServer(new C2S_ApplyBlueprintMessage(dimId.toString()), a);
            ItemStack applied = a.getMainHandItem();
            helper.assertTrue(applied.getItem() == SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get(),
                    "dimensional item applied");
            helper.assertValueEqual(dimId.toString(), SpatialBlueprintItem.getBlueprintId(applied), "blueprint_id");
            helper.assertValueEqual("t05-dim", SpatialBlueprintItem.getBlueprintName(applied), "blueprint_name");
            helper.assertValueEqual(5, SpatialBlueprintItem.getAnimDuration(applied, -1), "anim_duration");
            helper.assertTrue(SpatialBlueprintItem.getSaveMode(applied) == CaptureMode.FORCE, "save_mode");
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_OperationResult r && r.success),
                    "apply success reported");

            // ── 工程蓝图：固定坐标绑定 ────────────────────────────────
            BlueprintData engData = sampleBlueprint("t05-eng");
            engData.category = "engineering";
            engData.fixedDimension = dim;
            engData.fixedStartPos = new BlockPos(10, 64, 10);
            engData.fixedEndPos = new BlockPos(20, 64, 20);
            UUID engId = BlueprintStorage.save(engData);
            bpFiles.add(engId);

            C2S_ApplyBlueprintMessage.handleServer(new C2S_ApplyBlueprintMessage(engId.toString()), a);
            ItemStack eng = a.getMainHandItem();
            helper.assertTrue(eng.getItem() == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get(),
                    "engineering item applied");
            helper.assertValueEqual(engId.toString(), EngineeringBlueprintItem.getBoundProjectId(eng), "eng_project_id");
            helper.assertValueEqual(dim, EngineeringBlueprintItem.getBoundDimension(eng), "eng_dimension");
            helper.assertTrue(new BlockPos(10, 64, 10).equals(EngineeringBlueprintItem.getBoundStartPos(eng)),
                    "eng_start");
            helper.assertTrue(new BlockPos(20, 64, 20).equals(EngineeringBlueprintItem.getBoundEndPos(eng)),
                    "eng_end");

            // ── TakeProject MODE_PLAN：换手 + project_id ─────────────
            UUID planProject = UUID.randomUUID();
            ProjectData planData = new ProjectData();
            planData.name = "t05-plan";
            planData.dimension = dim;
            ProjectStorage.saveProject(planProject, planData);
            projects.add(planProject);
            grantOp(helper, op);
            opped = true;
            op.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            C2S_TakeProjectMessage.handleServer(
                    new C2S_TakeProjectMessage(planProject.toString(), C2S_TakeProjectMessage.MODE_PLAN), op);
            ItemStack planItem = op.getMainHandItem();
            helper.assertTrue(planItem.getItem() == SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get(),
                    "plan replaces hand with public project blueprint");
            helper.assertValueEqual(planProject.toString(), ProjectBlueprintItem.getProjectId(planItem), "project_id");

            // ── TakeProject MODE_IMPORT PUBLIC：带 task_set_id ───────
            UUID importProject = UUID.randomUUID();
            ProjectData importData = new ProjectData();
            importData.name = "t05-import";
            importData.kind = ProjectData.Kind.PUBLIC;
            importData.dimension = dim;
            ProjectStorage.saveProject(importProject, importData);
            projects.add(importProject);
            ProjectClaimRegistry.Claim claim = addClaim(helper, importProject.toString(), true,
                    op.getUUID().toString(), 0, 60, 0, 9, 70, 9);
            claimTaskSetId = claim.taskSetId;

            C2S_TakeProjectMessage.handleServer(
                    new C2S_TakeProjectMessage(importProject.toString(), C2S_TakeProjectMessage.MODE_IMPORT), op);
            ItemStack imported = null;
            for (int i = 0; i < op.getInventory().getContainerSize(); i++) {
                ItemStack s = op.getInventory().getItem(i);
                if (s.getItem() == SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get()
                        && importProject.toString().equals(ProjectBlueprintItem.getProjectId(s))) {
                    imported = s;
                }
            }
            helper.assertTrue(imported != null, "import gave a project blueprint");
            helper.assertValueEqual(claim.taskSetId, ProjectBlueprintItem.getTaskSetId(imported), "task_set_id");
            helper.assertValueEqual(importProject.toString(),
                    ProjectBlueprintItem.getProjectId(imported), "import project_id");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, op);
            if (claimTaskSetId != null) {
                ProjectClaimRegistry.get(helper.getLevel()).removeByTaskSet(claimTaskSetId);
            }
            for (UUID id : projects) ProjectStorage.deleteProject(id);
            for (UUID id : bpFiles) BlueprintStorage.delete(id.toString());
        }
        helper.succeed();
    }

    // ── t06：项目编辑（范围 / 排序 / 删子工程）───────────────────────────────

    @GameTest(template = "empty")
    public static void t06_projectEdits(GameTestHelper helper) {
        FakePlayer nonOp = fakePlayer(helper);
        FakePlayer op = fakePlayer(helper);
        String dim = ProjectData.dimensionOf(helper.getLevel());
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<UUID> projects = new ArrayList<>();
        List<String> taskSets = new ArrayList<>();
        boolean opped = false;
        try {
            grantOp(helper, op);
            opped = true;

            // ── SaveProjectRange：非 OP 拒 / OP 成功 ─────────────────
            UUID rangeProject = UUID.randomUUID();
            ProjectData rangeData = new ProjectData();
            rangeData.name = "t06-range";
            rangeData.dimension = dim;
            ProjectStorage.saveProject(rangeProject, rangeData);
            projects.add(rangeProject);

            ItemStack rangeStack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(rangeStack, rangeProject);
            ProjectBlueprintItem.setRangePoint(rangeStack, 1, new BlockPos(0, 64, 0));
            ProjectBlueprintItem.setRangePoint(rangeStack, 2, new BlockPos(9, 64, 9));
            nonOp.setItemInHand(InteractionHand.MAIN_HAND, rangeStack.copy());
            received.clear();
            C2S_SaveProjectRangeMessage.handleServer(new C2S_SaveProjectRangeMessage(), nonOp);
            S2C_OperationResult rangeDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.project.range.need_op",
                    rangeDenied.messageKey, "range denied for non-op");

            op.setItemInHand(InteractionHand.MAIN_HAND, rangeStack);
            C2S_SaveProjectRangeMessage.handleServer(new C2S_SaveProjectRangeMessage(), op);
            ProjectData rangeAfter = ProjectStorage.loadProject(rangeProject);
            helper.assertTrue(new BlockPos(0, 64, 0).equals(rangeAfter.origin), "range origin saved");
            helper.assertTrue(new BlockPos(10, 1, 10).equals(rangeAfter.size), "range size saved");
            helper.assertTrue(rangeAfter.stage == ProjectData.Stage.LAYOUT, "stage advanced to layout");
            helper.assertTrue(ProjectBlueprintItem.getRangePoint(op.getMainHandItem(), 1) == null
                    && ProjectBlueprintItem.getRangePoint(op.getMainHandItem(), 2) == null,
                    "range points cleared");

            // ── SaveProjectRange：扩建与别的任务集重叠 → 拒绝 ─────────
            ProjectClaimRegistry.Claim ownClaim = addClaim(helper, rangeProject.toString(), true,
                    op.getUUID().toString(), 0, 64, 0, 9, 64, 9);
            taskSets.add(ownClaim.taskSetId);
            UUID otherProject = UUID.randomUUID();
            ProjectData otherData = new ProjectData();
            otherData.name = "t06-other";
            otherData.dimension = dim;
            ProjectStorage.saveProject(otherProject, otherData);
            projects.add(otherProject);
            ProjectClaimRegistry.Claim otherClaim = addClaim(helper, otherProject.toString(), true,
                    op.getUUID().toString(), 5, 64, 5, 15, 64, 15);
            taskSets.add(otherClaim.taskSetId);

            ProjectBlueprintItem.setRangePoint(op.getMainHandItem(), 1, new BlockPos(0, 64, 0));
            ProjectBlueprintItem.setRangePoint(op.getMainHandItem(), 2, new BlockPos(20, 64, 20));
            received.clear();
            C2S_SaveProjectRangeMessage.handleServer(new C2S_SaveProjectRangeMessage(), op);
            S2C_OperationResult conflict = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.project.range.claim_conflict",
                    conflict.messageKey, "overlap with other claim refused");
            helper.assertTrue(new BlockPos(10, 1, 10).equals(
                    ProjectStorage.loadProject(rangeProject).size), "range unchanged after conflict");

            // ── SubmitProjectOrder：非 OP 拒 / OP 重排成功 ────────────
            UUID orderProject = UUID.randomUUID();
            ProjectData orderData = new ProjectData();
            orderData.name = "t06-order";
            orderData.dimension = dim;
            orderData.entries.add(new ProjectData.EntryData(1, "a.litematic", "L1",
                    new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), 0));
            orderData.entries.add(new ProjectData.EntryData(2, "b.litematic", "L2",
                    new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), 0));
            orderData.entries.add(new ProjectData.EntryData(3, "c.litematic", "L3",
                    new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), 0));
            orderData.entries.get(1).requiresEntry = 1;
            ProjectStorage.saveProject(orderProject, orderData);
            projects.add(orderProject);

            ItemStack orderStack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(orderStack, orderProject);
            nonOp.setItemInHand(InteractionHand.MAIN_HAND, orderStack.copy());
            received.clear();
            C2S_SubmitProjectOrderMessage.handleServer(
                    new C2S_SubmitProjectOrderMessage(List.of(3, 2, 1)), nonOp);
            S2C_OperationResult orderDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.subproject.need_op",
                    orderDenied.messageKey, "order denied for non-op");

            op.setItemInHand(InteractionHand.MAIN_HAND, orderStack);
            C2S_SubmitProjectOrderMessage.handleServer(
                    new C2S_SubmitProjectOrderMessage(List.of(3, 2, 1)), op);
            ProjectData orderAfter = ProjectStorage.loadProject(orderProject);
            helper.assertValueEqual(3, orderAfter.entries.size(), "entries kept");
            helper.assertValueEqual("L3", orderAfter.entries.get(0).label, "reordered first = L3");
            helper.assertValueEqual(1, orderAfter.entries.get(0).index, "reindexed first = 1");
            helper.assertValueEqual("L1", orderAfter.entries.get(2).label, "reordered last = L1");
            helper.assertValueEqual(3, orderAfter.entries.get(1).requiresEntry,
                    "requiresEntry remapped 1 -> 3");

            // ── DeleteSubProject：非 OP 拒 / 依赖拒 / OP 成功 ─────────
            UUID delProject = UUID.randomUUID();
            ProjectData delData = new ProjectData();
            delData.name = "t06-del";
            delData.dimension = dim;
            delData.entries.add(new ProjectData.EntryData(1, "a.litematic", "L1",
                    new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), 0));
            delData.entries.add(new ProjectData.EntryData(2, "b.litematic", "L2",
                    new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), 0));
            delData.entries.get(1).requiresEntry = 1;
            ProjectStorage.saveProject(delProject, delData);
            projects.add(delProject);

            ItemStack delStack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(delStack, delProject);
            nonOp.setItemInHand(InteractionHand.MAIN_HAND, delStack.copy());
            received.clear();
            C2S_DeleteSubProjectMessage.handleServer(
                    new C2S_DeleteSubProjectMessage(delProject.toString(), 2), nonOp);
            S2C_OperationResult delDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.subproject.need_op",
                    delDenied.messageKey, "delete denied for non-op");
            helper.assertValueEqual(2, ProjectStorage.loadProject(delProject).entries.size(),
                    "delete denied keeps entry");

            op.setItemInHand(InteractionHand.MAIN_HAND, delStack);
            // 负例：条目 2 以条目 1 为前置 → 删 1 被拒
            received.clear();
            C2S_DeleteSubProjectMessage.handleServer(
                    new C2S_DeleteSubProjectMessage(delProject.toString(), 1), op);
            S2C_OperationResult depDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.subproject.delete_has_dependents",
                    depDenied.messageKey, "delete refused while referenced");
            helper.assertValueEqual(2, ProjectStorage.loadProject(delProject).entries.size(),
                    "entry 1 kept");
            // OP 删条目 2 → 成功
            C2S_DeleteSubProjectMessage.handleServer(
                    new C2S_DeleteSubProjectMessage(delProject.toString(), 2), op);
            helper.assertValueEqual(1, ProjectStorage.loadProject(delProject).entries.size(),
                    "op deleted entry 2");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, op);
            for (String taskSet : taskSets) {
                ProjectClaimRegistry.get(helper.getLevel()).removeByTaskSet(taskSet);
            }
            for (UUID id : projects) ProjectStorage.deleteProject(id);
        }
        helper.succeed();
    }

    // ── t07：领取子工程 ──────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_claimSubProject(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        String dim = ProjectData.dimensionOf(helper.getLevel());
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        String taskSetId = UUID.randomUUID().toString();
        UUID projectId = UUID.randomUUID();
        String engUuid = "00000000-0000-0000-0000-0000000000aa";
        try {
            ProjectData project = new ProjectData();
            project.name = "t07";
            project.dimension = dim;
            project.origin = BlockPos.ZERO;
            project.size = new BlockPos(10, 10, 10);
            project.anchor = BlockPos.ZERO;
            project.entries.add(new ProjectData.EntryData(1, engUuid + ".litematic", "子工程一",
                    new BlockPos(0, 0, 0), new BlockPos(4, 4, 4), 0));
            ProjectStorage.saveProject(projectId, project);

            ProjectClaimRegistry.Claim claim = new ProjectClaimRegistry.Claim();
            claim.taskSetId = taskSetId;
            claim.projectId = projectId.toString();
            claim.dimension = dim;
            claim.publicProject = true;
            claim.ownerUuid = player.getUUID().toString();
            claim.ownerName = player.getScoreboardName();
            claim.minX = 0; claim.minY = 0; claim.minZ = 0;
            claim.maxX = 9; claim.maxY = 9; claim.maxZ = 9;
            claim.hasAnchor = true;
            claim.anchorX = 0; claim.anchorY = 0; claim.anchorZ = 0;
            ProjectClaimRegistry.get(helper.getLevel()).add(claim);

            ItemStack held = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(held, projectId);
            ProjectBlueprintItem.setTaskSetId(held, UUID.fromString(taskSetId));
            player.setItemInHand(InteractionHand.MAIN_HAND, held);

            received.clear();
            C2S_ClaimSubProjectMessage.handleServer(new C2S_ClaimSubProjectMessage(1), player);

            ItemStack gained = null;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.getItem() == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get()) gained = s;
            }
            helper.assertTrue(gained != null, "claim gave an engineering blueprint");
            helper.assertValueEqual(taskSetId, EngineeringBlueprintItem.getTaskSetId(gained), "sb_task_set_id");
            helper.assertValueEqual(1, EngineeringBlueprintItem.getEntryIndex(gained), "sb_entry_index");
            helper.assertValueEqual(projectId.toString(),
                    EngineeringBlueprintItem.getBoundProjectId(gained), "eng project binding");
            helper.assertTrue(ProjectProgressData.get(helper.getLevel())
                            .lastClaimAt(player.getUUID().toString(), taskSetId, 1) > 0,
                    "claim recorded in progress data");
            helper.assertTrue(ProjectClaimRegistry.get(helper.getLevel()).findByTaskSet(taskSetId) != null,
                    "claim still registered");
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_OperationResult r && r.success),
                    "claim success reported");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            ProjectProgressData.get(helper.getLevel()).forgetEntry(List.of(taskSetId), 1);
            ProjectClaimRegistry.get(helper.getLevel()).removeByTaskSet(taskSetId);
            ProjectStorage.deleteProject(projectId);
        }
        helper.succeed();
    }

    // ── t08：材料清单写入 ────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t08_materialList(GameTestHelper helper) {
        FakePlayer nonOp = fakePlayer(helper);
        FakePlayer op = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<UUID> files = new ArrayList<>();
        boolean opped = false;
        try {
            BlueprintData data = sampleBlueprint("t08");
            UUID id = BlueprintStorage.save(data);
            files.add(id);

            // 非 OP 非创造 → SaveMaterialList 静默拒（无回包）、文件不变
            received.clear();
            C2S_SaveMaterialListMessage.handleServer(new C2S_SaveMaterialListMessage(id.toString(),
                    Map.of("minecraft:stone", 3), Map.of()), nonOp);
            helper.assertTrue(received.isEmpty(), "save material list silent for non-op");
            helper.assertTrue(BlueprintStorage.load(id.toString()).materialList == null,
                    "material list untouched for non-op");

            // 非 OP 非创造 → RewardMaterials 拒绝（need_op）
            received.clear();
            C2S_RewardMaterialsMessage.handleServer(new C2S_RewardMaterialsMessage(id.toString(),
                    C2S_RewardMaterialsMessage.MODE_CLEAR), nonOp);
            S2C_OperationResult rewardDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.message.reward_materials.need_op",
                    rewardDenied.messageKey, "reward materials denied for non-op");

            // OP + 创造 → 写入，读回与发送内容一致
            grantOp(helper, op);
            opped = true;
            op.setGameMode(GameType.CREATIVE);
            CompoundTag tag = new CompoundTag();
            tag.putString("k", "v");
            C2S_SaveMaterialListMessage.handleServer(new C2S_SaveMaterialListMessage(id.toString(),
                    Map.of("minecraft:stone", 3, "minecraft:diamond_sword", 1),
                    Map.of("minecraft:diamond_sword", tag)), op);
            BlueprintData written = BlueprintStorage.load(id.toString());
            helper.assertTrue(written.materialList != null, "material list written");
            helper.assertValueEqual(3, written.materialList.blockCounts.get("minecraft:stone"), "stone count");
            helper.assertValueEqual(1, written.materialList.blockCounts.get("minecraft:diamond_sword"), "sword count");
            CompoundTag back = written.materialList.keyTags.get("minecraft:diamond_sword");
            helper.assertTrue(back != null && "v".equals(back.getString("k")), "tag table written");

            // OP + 创造 + reward 分类 → RewardMaterials 清空写盘
            BlueprintData reward = sampleBlueprint("t08-reward");
            reward.category = "reward";
            reward.materialList = new MaterialList();
            reward.materialList.blockCounts.put("minecraft:stone", 9);
            UUID rewardId = BlueprintStorage.save(reward);
            files.add(rewardId);
            C2S_RewardMaterialsMessage.handleServer(new C2S_RewardMaterialsMessage(rewardId.toString(),
                    C2S_RewardMaterialsMessage.MODE_CLEAR), op);
            helper.assertTrue(BlueprintStorage.load(rewardId.toString()).materialList.blockCounts.isEmpty(),
                    "reward materials cleared");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, op);
            for (UUID id : files) BlueprintStorage.delete(id.toString());
        }
        helper.succeed();
    }

    // ── t09：非法方块查询（含领地采样）───────────────────────────────────────

    /** 临时取消本测试维度全部 BreakEvent 的监听器（t09 用，finally 注销）。 */
    public static class BreakCancelListener {
        @SubscribeEvent
        public void onBreak(BlockEvent.BreakEvent event) {
            event.setCanceled(true);
        }
    }

    @GameTest(template = "empty")
    public static void t09_illegalBlocks(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);   // 生存、非 OP → 采样领地
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        BreakCancelListener listener = null;
        try {
            BlockPos relBedrock = new BlockPos(2, 2, 2);
            BlockPos relStone = new BlockPos(3, 2, 2);
            helper.setBlock(relBedrock, Blocks.BEDROCK);
            helper.setBlock(relStone, Blocks.STONE);
            BlockPos min = helper.absolutePos(relBedrock);
            BlockPos max = helper.absolutePos(relStone);

            // 无监听 → 非法方块清单含基岩、不含石头；无受保护区块
            received.clear();
            C2S_RequestIllegalBlocksMessage.handleServer(
                    new C2S_RequestIllegalBlocksMessage(min, max), player);
            S2C_IllegalBlocksListMessage result = firstIllegalBlocks(helper, received);
            // 纯静态缓存、零客户端类引用，直接喂给 handle 读回清单
            S2C_IllegalBlocksListMessage.handle(result, null);
            List<S2C_IllegalBlocksListMessage.IllegalBlock> illegal =
                    S2C_IllegalBlocksListMessage.clientIllegalBlocks;
            List<S2C_IllegalBlocksListMessage.ProtectedChunk> protectedChunks =
                    S2C_IllegalBlocksListMessage.clientProtectedChunks;
            helper.assertTrue(illegal.stream().anyMatch(b ->
                            b.x() == min.getX() && b.y() == min.getY() && b.z() == min.getZ()
                                    && "minecraft:bedrock".equals(b.blockId())),
                    "bedrock reported illegal");
            helper.assertTrue(illegal.stream().noneMatch(b ->
                            b.x() == max.getX() && b.y() == max.getY() && b.z() == max.getZ()),
                    "stone not reported illegal");
            helper.assertTrue(protectedChunks.isEmpty(), "no protected chunks without listener");

            // 注册取消监听 → 受保护区块含测试区所在区块
            listener = new BreakCancelListener();
            NeoForge.EVENT_BUS.register(listener);
            received.clear();
            C2S_RequestIllegalBlocksMessage.handleServer(
                    new C2S_RequestIllegalBlocksMessage(min, max), player);
            S2C_IllegalBlocksListMessage guarded = firstIllegalBlocks(helper, received);
            S2C_IllegalBlocksListMessage.handle(guarded, null);
            helper.assertTrue(S2C_IllegalBlocksListMessage.clientProtectedChunks.stream().anyMatch(c ->
                            c.cx() == (min.getX() >> 4) && c.cz() == (min.getZ() >> 4)),
                    "protected chunk sampled with listener");

            // 注销监听 → 恢复为空
            NeoForge.EVENT_BUS.unregister(listener);
            listener = null;
            received.clear();
            C2S_RequestIllegalBlocksMessage.handleServer(
                    new C2S_RequestIllegalBlocksMessage(min, max), player);
            S2C_IllegalBlocksListMessage restored = firstIllegalBlocks(helper, received);
            S2C_IllegalBlocksListMessage.handle(restored, null);
            helper.assertTrue(S2C_IllegalBlocksListMessage.clientProtectedChunks.isEmpty(),
                    "protected chunks empty after unregister");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    // ── t10：C2S 计数预分配（SBN-008 验收修正）───────────────────────────────
    // decode 在服务端、权限判定之前执行；按客户端给的计数预分配 ArrayList，一个 5 字节 VarInt
    // 就能让服务端尝试分配 20 亿元素的数组。越界计数必须整包拒收（DecoderException）。
    // 手写恶意包是唯一允许处（证明该形状可达）。

    @GameTest(template = "empty")
    public static void t10_submitChangesCountGuard(GameTestHelper helper) {
        helper.assertTrue(decodeRejected(buf -> {
            buf.writeUtf("p");
            buf.writeVarInt(Integer.MAX_VALUE);          // orderCount
        }), "huge orderCount rejected with DecoderException");
        helper.assertTrue(decodeRejected(buf -> {
            buf.writeUtf("p");
            buf.writeVarInt(0);                          // orderCount
            buf.writeVarInt(Integer.MAX_VALUE);          // deleteCount
        }), "huge deleteCount rejected with DecoderException");
        helper.assertTrue(decodeRejected(buf -> {
            buf.writeUtf("p");
            buf.writeVarInt(-5);                         // 负计数
        }), "negative orderCount rejected with DecoderException");
        helper.succeed();
    }

    private static boolean decodeRejected(java.util.function.Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            writer.accept(buf);
            C2S_SubmitProjectChangesMessage.decode(buf);
            return false;
        } catch (Throwable t) {
            return t instanceof io.netty.handler.codec.DecoderException;
        } finally {
            buf.release();
        }
    }
}
