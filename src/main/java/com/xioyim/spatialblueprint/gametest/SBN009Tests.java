package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ConfirmUploadMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestEngineeringReportMessage;
import com.xioyim.spatialblueprint.network.C2S_UploadBeginMessage;
import com.xioyim.spatialblueprint.network.C2S_UploadChunkMessage;
import com.xioyim.spatialblueprint.network.C2S_UploadProjectionTemplateMessage;
import com.xioyim.spatialblueprint.network.S2C_LibrarySyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage;
import com.xioyim.spatialblueprint.server.PendingUploadRegistry;
import com.xioyim.spatialblueprint.server.UploadStagingRegistry;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * SBN-009 上传（P3c-2）GameTest（t01～t08）。
 * 伪造 / 越界包、解压炸弹是唯一允许手写处。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN009Tests {

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
                new GameProfile(UUID.randomUUID(), "SB9" + UUID.randomUUID().toString().substring(0, 4)));
    }

    private static FakePlayer creativePlayer(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        player.setGameMode(GameType.CREATIVE);
        return player;
    }

    private static BlueprintData uploadSample(String name) {
        BlueprintData data = new BlueprintData();
        data.name = name;
        data.category = "create";
        data.creatorName = "Tester";
        data.size = new BlockPos(2, 1, 1);
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), null));
        // 一个真实箱子方块实体（含一件普通物品）：走完扫描 / 净化 / 落盘全链路
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putByte("Slot", (byte) 0);
        stack.putString("id", "minecraft:stone");
        stack.putByte("Count", (byte) 1);
        items.add(stack);
        chest.put("Items", items);
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), chest));
        return data;
    }

    private static byte[] gzipBytes(byte[] raw) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(raw);
            gzip.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static S2C_OperationResult firstOperationResult(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_OperationResult result) return result;
        }
        helper.fail("expected S2C_OperationResult");
        return null;
    }

    private static S2C_UploadReportMessage firstUploadReport(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_UploadReportMessage report) return report;
        }
        helper.fail("expected S2C_UploadReportMessage");
        return null;
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
                {"s2c_library_sync_message", S2C_LibrarySyncMessage.TYPE.id().getPath()},
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
                {"c2s_upload_begin_message", C2S_UploadBeginMessage.TYPE.id().getPath()},
                {"c2s_upload_chunk_message", C2S_UploadChunkMessage.TYPE.id().getPath()},
                {"c2s_upload_projection_template_message", C2S_UploadProjectionTemplateMessage.TYPE.id().getPath()},
                {"c2s_confirm_upload_message", C2S_ConfirmUploadMessage.TYPE.id().getPath()},
                {"c2s_request_engineering_report_message", C2S_RequestEngineeringReportMessage.TYPE.id().getPath()},
                {"s2c_upload_report_message", S2C_UploadReportMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[0], pair[1], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(71, ids.size(), "71 unique payload ids");
        helper.assertValueEqual(SpatialBlueprintMod.MODID, S2C_UploadReportMessage.TYPE.id().getNamespace(), "namespace");
        helper.succeed();
    }

    // ── t02：6 个消息编码往返 ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codecRoundTrip(GameTestHelper helper) {
        BlueprintData data = uploadSample("t02");
        byte[] compressed = C2S_UploadProjectionTemplateMessage.compressPayload(data);
        UUID uploadId = UUID.randomUUID();

        BlueprintSanitizer.UploadScan scan = BlueprintSanitizer.scanBlueprintData(
                data, BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(), BlueprintConfig.MAX_NBT_DEPTH.get());
        BlueprintSanitizer.NbtBudgetResult budget = BlueprintSanitizer.checkNbtBudget(
                data, BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(), BlueprintConfig.MAX_TOTAL_NBT_BYTES.get());
        int totalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();

        assertRoundTrip(helper, C2S_UploadBeginMessage.STREAM_CODEC,
                new C2S_UploadBeginMessage(uploadId, 2, compressed.length, "project", true));
        assertRoundTrip(helper, C2S_UploadChunkMessage.STREAM_CODEC,
                new C2S_UploadChunkMessage(uploadId, 0, Arrays.copyOf(compressed, Math.min(64, compressed.length))));
        assertRoundTrip(helper, C2S_UploadProjectionTemplateMessage.STREAM_CODEC,
                C2S_UploadProjectionTemplateMessage.fromCompressed(compressed, "project", false));
        assertRoundTrip(helper, C2S_ConfirmUploadMessage.STREAM_CODEC, new C2S_ConfirmUploadMessage(true));
        assertRoundTrip(helper, C2S_RequestEngineeringReportMessage.STREAM_CODEC,
                new C2S_RequestEngineeringReportMessage("src-1"));
        assertRoundTrip(helper, S2C_UploadReportMessage.STREAM_CODEC,
                new S2C_UploadReportMessage(data.name, totalBlocks, true,
                        data.size.getX(), data.size.getY(), data.size.getZ(),
                        compressed.length, budget.totalBytes(), budget.maxSingle(), 0,
                        scan.illegalBlocks(), scan.dangerousBlocks(), scan.oversizedNbt(),
                        scan.deepNbt(), scan.droppedEntities(), scan.conversions(), scan.freeBlocks(),
                        scan.illegalTotal(), scan.dangerousTotal(), scan.oversizedTotal(),
                        scan.deepNbtTotal(), scan.droppedTotal(), scan.conversionsTotal(), scan.freeTotal(),
                        "project", 100000,
                        BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                        BlueprintConfig.MAX_TOTAL_NBT_BYTES.get()));
        helper.succeed();
    }

    // ── t03：decode 上限（C20 口径 / K13）─────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_decodeGuards(GameTestHelper helper) {
        // UploadChunk：负载 30001 字节 → 拒收
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUUID(UUID.randomUUID());
                buf.writeVarInt(0);
                buf.writeByteArray(new byte[30001]);
                C2S_UploadChunkMessage.decode(buf);
            } finally { buf.release(); }
        }, "UploadChunk payload 30001");

        // UploadProjectionTemplate：负载 30001 字节 → 拒收（三.2 的新上限）
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeByteArray(new byte[30001]);
                buf.writeUtf("project", 32);
                buf.writeBoolean(false);
                C2S_UploadProjectionTemplateMessage.decode(buf);
            } finally { buf.release(); }
        }, "UploadProjectionTemplate payload 30001");

        // UploadProjectionTemplate：负载 30000 字节 → 解码成功
        {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeByteArray(new byte[30000]);
                buf.writeUtf("project", 32);
                buf.writeBoolean(true);
                C2S_UploadProjectionTemplateMessage msg = C2S_UploadProjectionTemplateMessage.decode(buf);
                RegistryFriendlyByteBuf out = newBuf(helper);
                try {
                    C2S_UploadProjectionTemplateMessage.STREAM_CODEC.encode(out, msg);
                    helper.assertValueEqual(30000, out.readByteArray().length, "30000-byte payload kept");
                } finally { out.release(); }
            } finally { buf.release(); }
        }

        // UploadReport：scanEntries 计数 4097 → DecoderException（C20 口径）
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("name", 128);
                buf.writeVarInt(1);
                buf.writeBoolean(false);
                buf.writeVarInt(1);
                buf.writeVarInt(1);
                buf.writeVarInt(1);
                buf.writeVarInt(1);
                buf.writeVarLong(1L);
                buf.writeVarLong(1L);
                buf.writeVarInt(0);
                buf.writeVarInt(4097);
                S2C_UploadReportMessage.decode(buf);
            } finally { buf.release(); }
        }, "UploadReport scanEntries=4097");
        helper.succeed();
    }

    // ── t04：单包上传全链路 ──────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_singleUpload(GameTestHelper helper) {
        FakePlayer player = creativePlayer(helper);
        FakePlayer survivor = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<String> cleanupIds = new ArrayList<>();
        try {
            // 非创造玩家传 create 分类 → 被拒
            byte[] compressed = C2S_UploadProjectionTemplateMessage.compressPayload(uploadSample("t04-create"));
            received.clear();
            C2S_UploadProjectionTemplateMessage.handleServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(compressed, "create", false), survivor);
            S2C_OperationResult creativeDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.creative_only",
                    creativeDenied.messageKey, "create upload denied for non-creative");

            // 创造玩家单包上传 → 收到报告、暂存建立
            received.clear();
            C2S_UploadProjectionTemplateMessage.handleServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(compressed, "project", false), player);
            S2C_UploadReportMessage report = firstUploadReport(helper, received);
            helper.assertValueEqual("t04-create", report.getBlueprintName(), "report name");
            helper.assertValueEqual(2, report.getTotalBlocks(), "report total blocks");
            helper.assertValueEqual("project", report.getCategory(), "report category");

            // 确认（净化路径）→ 落盘、收到蓝图库同步
            received.clear();
            C2S_ConfirmUploadMessage.handleServer(new C2S_ConfirmUploadMessage(false), player);
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_LibrarySyncMessage),
                    "library sync after confirm");

            BlueprintStorage.BlueprintSummary uploaded = null;
            for (BlueprintStorage.BlueprintSummary s : BlueprintStorage.listSummaries()) {
                if (s.uploadMode() == 1 && player.getUUID().toString().equals(s.ownerUuid())) uploaded = s;
            }
            helper.assertTrue(uploaded != null, "uploaded blueprint saved");
            cleanupIds.add(uploaded.id());
            helper.assertValueEqual("t04-create", uploaded.name(), "uploaded name");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            PendingUploadRegistry.clear(player.getUUID());
            PendingUploadRegistry.clear(survivor.getUUID());
            for (String id : cleanupIds) BlueprintStorage.delete(id);
        }
        helper.succeed();
    }

    // ── t05：分块上传与登记表语义 ────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t05_chunkedUpload(GameTestHelper helper) {
        FakePlayer a = creativePlayer(helper);
        FakePlayer b = creativePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        List<UUID> uploadIds = new ArrayList<>();
        try {
            byte[] compressed = C2S_UploadProjectionTemplateMessage.compressPayload(uploadSample("t05"));
            int half = compressed.length / 2;
            byte[] part0 = Arrays.copyOfRange(compressed, 0, half);
            byte[] part1 = Arrays.copyOfRange(compressed, half, compressed.length);

            UUID uploadId = UUID.randomUUID();
            uploadIds.add(uploadId);
            C2S_UploadBeginMessage.handleServer(
                    new C2S_UploadBeginMessage(uploadId, 2, compressed.length, "project", false), a);

            // 越界 index → staging_rejected
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(uploadId, 5, part0), a);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "bad index rejected");

            // 第一块：OK（无回包）
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(uploadId, 0, part0), a);
            helper.assertTrue(received.isEmpty(), "first chunk accepted silently");

            // 重复块 → staging_rejected
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(uploadId, 0, part0), a);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "duplicate chunk rejected");

            // 单块 30001 字节 → staging_rejected
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(uploadId, 1, new byte[30001]), a);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "oversize chunk rejected");

            // 真实最后一块 → COMPLETE → 报告
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(uploadId, 1, part1), a);
            firstUploadReport(helper, received);

            // 他人玩家发 Chunk → WRONG_PLAYER 语义
            UUID wrongId = UUID.randomUUID();
            uploadIds.add(wrongId);
            C2S_UploadBeginMessage.handleServer(
                    new C2S_UploadBeginMessage(wrongId, 2, compressed.length, "project", false), a);
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(wrongId, 0, part0), b);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "wrong player rejected");
            UploadStagingRegistry.cancel(wrongId, a.getUUID());

            // Begin totalChunks = 257 → 被拒且未建组包；随后的块得到 UNKNOWN_ID
            UUID tooMany = UUID.randomUUID();
            uploadIds.add(tooMany);
            received.clear();
            C2S_UploadBeginMessage.handleServer(
                    new C2S_UploadBeginMessage(tooMany, 257, compressed.length, "project", false), a);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "257 chunks rejected");
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(tooMany, 0, part0), a);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "no staging created for 257-chunk begin");

            // Begin totalBytes 超配置上限 → 被拒且未建组包
            UUID tooLarge = UUID.randomUUID();
            uploadIds.add(tooLarge);
            received.clear();
            C2S_UploadBeginMessage.handleServer(
                    new C2S_UploadBeginMessage(tooLarge, 2,
                            BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get() + 1L, "project", false), a);
            helper.assertValueEqual("spatial_blueprint.error.upload_too_large",
                    firstOperationResult(helper, received).messageKey, "oversize total bytes rejected");
            received.clear();
            C2S_UploadChunkMessage.handleServer(new C2S_UploadChunkMessage(tooLarge, 0, part0), a);
            helper.assertValueEqual("spatial_blueprint.upload.staging_rejected",
                    firstOperationResult(helper, received).messageKey, "no staging created for oversize begin");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            for (UUID id : uploadIds) {
                UploadStagingRegistry.cancel(id, a.getUUID());
                UploadStagingRegistry.cancel(id, b.getUUID());
            }
            PendingUploadRegistry.clear(a.getUUID());
            PendingUploadRegistry.clear(b.getUUID());
        }
        helper.succeed();
    }

    // ── t06：文件大小上限与解压炸弹 ──────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_limits(GameTestHelper helper) {
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        int origMax = BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get();
        try {
            // 临时把 MAX_UPLOAD_FILE_BYTES 调小 → 超限上传被拒（不动磁盘配置）
            BlueprintConfig.MAX_UPLOAD_FILE_BYTES.set(1024);
            FakePlayer oversize = creativePlayer(helper);
            received.clear();
            C2S_UploadProjectionTemplateMessage.handleServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(new byte[2048], "project", false), oversize);
            S2C_OperationResult tooLarge = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.upload_file_too_large",
                    tooLarge.messageKey, "oversize upload rejected");
            PendingUploadRegistry.clear(oversize.getUUID());

            // 解压炸弹：小压缩体、解压后超 NbtAccounter 不可信上限 → 被拒且不抛到测试外。
            // 载荷 = 合法 NBT 前缀（Compound + 一个声明 0x7FFFFFFF 长的 IntArray），
            // 解压读取在计数/EOF 处就被截断，不需要真的产出 64 MiB。
            BlueprintConfig.MAX_UPLOAD_FILE_BYTES.set(origMax);
            FakePlayer bomb = creativePlayer(helper);
            byte[] bombRaw = new byte[] {
                    0x0A, 0x00, 0x00,              // TAG_Compound, 空名
                    0x0B, 0x00, 0x00,              // TAG_Int_Array, 空名
                    0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF  // 声明长度 0x7FFFFFFF
            };
            byte[] bombGzip = gzipBytes(bombRaw);
            received.clear();
            C2S_UploadProjectionTemplateMessage.handleServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(bombGzip, "project", false), bomb);
            S2C_OperationResult bombDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.invalid_blueprint_data",
                    bombDenied.messageKey, "decompression bomb rejected");
            PendingUploadRegistry.clear(bomb.getUUID());
        } finally {
            BlueprintConfig.MAX_UPLOAD_FILE_BYTES.set(origMax);
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
        }
        helper.succeed();
    }

    // ── t07：工程报告请求 ────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_engineeringReport(GameTestHelper helper) {
        FakePlayer survivor = fakePlayer(helper);
        FakePlayer op = creativePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        UUID savedId = null;
        try {
            BlueprintData data = uploadSample("t07-eng");
            savedId = BlueprintStorage.save(data);

            // 非创造 → 无回包
            received.clear();
            C2S_RequestEngineeringReportMessage.handleServer(
                    new C2S_RequestEngineeringReportMessage(savedId.toString()), survivor);
            helper.assertTrue(received.isEmpty(), "no report for non-creative");

            // 创造 → 收到报告
            received.clear();
            C2S_RequestEngineeringReportMessage.handleServer(
                    new C2S_RequestEngineeringReportMessage(savedId.toString()), op);
            S2C_UploadReportMessage report = firstUploadReport(helper, received);
            helper.assertValueEqual("engineering", report.getCategory(), "engineering category");
            helper.assertValueEqual("t07-eng", report.getBlueprintName(), "engineering report name");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            PendingUploadRegistry.clear(survivor.getUUID());
            PendingUploadRegistry.clear(op.getUUID());
            if (savedId != null) BlueprintStorage.delete(savedId.toString());
        }
        helper.succeed();
    }

    // ── t08：上传限流 ────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t08_rateLimit(GameTestHelper helper) {
        FakePlayer player = creativePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        try {
            byte[] compressed = C2S_UploadProjectionTemplateMessage.compressPayload(uploadSample("t08"));

            // 第一次：放行（拿到报告）
            received.clear();
            C2S_UploadProjectionTemplateMessage.handleServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(compressed, "project", false), player);
            firstUploadReport(helper, received);

            // 同一 tick 紧接着第二次：限流窗口内 → 被拒
            received.clear();
            C2S_UploadProjectionTemplateMessage.handleServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(compressed, "project", false), player);
            S2C_OperationResult limited = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.upload_rate_limited",
                    limited.messageKey, "second upload rate limited");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            PendingUploadRegistry.clear(player.getUUID());
        }
        helper.succeed();
    }
}
