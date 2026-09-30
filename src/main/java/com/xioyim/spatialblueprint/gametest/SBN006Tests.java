package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintComponents;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ApplyConfigMessage;
import com.xioyim.spatialblueprint.network.C2S_ConvertBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_DeleteBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_MaterialDepositMessage;
import com.xioyim.spatialblueprint.network.C2S_ProjectRangePointMessage;
import com.xioyim.spatialblueprint.network.C2S_RenameBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintMaterialsMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestConfigMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestMaterialSpaceMessage;
import com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage;
import com.xioyim.spatialblueprint.network.ClearPointsMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintChunkMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintDataFailMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage;
import com.xioyim.spatialblueprint.network.S2C_LibrarySyncMessage;
import com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage;
import com.xioyim.spatialblueprint.network.S2C_VoidBagSyncMessage;
import com.xioyim.spatialblueprint.network.SetBlueprintNameMessage;
import com.xioyim.spatialblueprint.network.SetPointMessage;
import com.xioyim.spatialblueprint.network.UpdateOffsetMessage;
import com.xioyim.spatialblueprint.network.UpdateSettingsMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_BlueprintMaterialsMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigPermMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenConfigMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenVoidScreenMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
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
import java.util.Set;
import java.util.UUID;

/**
 * SBN-006 轻量消息（P3b-1）GameTest（t01～t07）。
 * 伪造 / 越界包是唯一允许手写处。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN006Tests {

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

    private static FakePlayer fakePlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SB6" + UUID.randomUUID().toString().substring(0, 4)));
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

    // ── t01：注册与命名 ───────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_registration(GameTestHelper helper) {
        String[][] expected = {
                // SBN-005 的 8 个
                {"s2c_operation_result", S2C_OperationResult.TYPE.id().getPath()},
                {"s2c_blueprint_chunk_message", S2C_BlueprintChunkMessage.TYPE.id().getPath()},
                {"s2c_blueprint_data_message", S2C_BlueprintDataMessage.TYPE.id().getPath()},
                {"s2c_blueprint_data_fail_message", S2C_BlueprintDataFailMessage.TYPE.id().getPath()},
                {"s2c_open_void_screen_message", S2C_OpenVoidScreenMessage.TYPE.id().getPath()},
                {"s2c_illegal_blocks_list_message", S2C_IllegalBlocksListMessage.TYPE.id().getPath()},
                {"c2s_request_blueprint_data_message", com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage.TYPE.id().getPath()},
                {"c2s_discard_invalid_blueprint_message", com.xioyim.spatialblueprint.network.C2S_DiscardInvalidBlueprintMessage.TYPE.id().getPath()},
                // SBN-006 的 24 个
                {"set_point_message", SetPointMessage.TYPE.id().getPath()},
                {"clear_points_message", ClearPointsMessage.TYPE.id().getPath()},
                {"set_blueprint_name_message", SetBlueprintNameMessage.TYPE.id().getPath()},
                {"update_offset_message", UpdateOffsetMessage.TYPE.id().getPath()},
                {"update_settings_message", UpdateSettingsMessage.TYPE.id().getPath()},
                {"c2s_project_range_point_message", C2S_ProjectRangePointMessage.TYPE.id().getPath()},
                {"c2s_request_config_message", C2S_RequestConfigMessage.TYPE.id().getPath()},
                {"c2s_apply_config_message", C2S_ApplyConfigMessage.TYPE.id().getPath()},
                {"s2c_config_sync_message", S2C_ConfigSyncMessage.TYPE.id().getPath()},
                {"s2c_config_values_message", S2C_ConfigValuesMessage.TYPE.id().getPath()},
                {"s2c_config_perm_message", S2C_ConfigPermMessage.TYPE.id().getPath()},
                {"s2c_open_config_message", S2C_OpenConfigMessage.TYPE.id().getPath()},
                {"c2s_request_library_message", C2S_RequestLibraryMessage.TYPE.id().getPath()},
                {"s2c_library_sync_message", S2C_LibrarySyncMessage.TYPE.id().getPath()},
                {"c2s_rename_blueprint_message", C2S_RenameBlueprintMessage.TYPE.id().getPath()},
                {"c2s_convert_blueprint_message", C2S_ConvertBlueprintMessage.TYPE.id().getPath()},
                {"c2s_set_withdraw_property_message", C2S_SetWithdrawPropertyMessage.TYPE.id().getPath()},
                {"c2s_delete_blueprint_message", C2S_DeleteBlueprintMessage.TYPE.id().getPath()},
                {"s2c_void_bag_sync_message", S2C_VoidBagSyncMessage.TYPE.id().getPath()},
                {"c2s_request_material_space_message", C2S_RequestMaterialSpaceMessage.TYPE.id().getPath()},
                {"s2c_material_space_sync_message", S2C_MaterialSpaceSyncMessage.TYPE.id().getPath()},
                {"c2s_material_deposit_message", C2S_MaterialDepositMessage.TYPE.id().getPath()},
                {"c2s_request_blueprint_materials_message", C2S_RequestBlueprintMaterialsMessage.TYPE.id().getPath()},
                {"s2c_blueprint_materials_message", S2C_BlueprintMaterialsMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[0], pair[1], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(32, ids.size(), "32 unique payload ids");
        helper.assertValueEqual(SpatialBlueprintMod.MODID, S2C_ConfigSyncMessage.TYPE.id().getNamespace(), "namespace");
        helper.succeed();
    }

    // ── t02：24 个消息编码往返 ────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codecRoundTrip(GameTestHelper helper) {
        // LibrarySync / VoidBagSync 用真实摘要
        BlueprintData bp = sampleBlueprint("t02");
        UUID bpId = BlueprintStorage.save(bp);
        try {
            BlueprintStorage.BlueprintSummary summary = null;
            for (BlueprintStorage.BlueprintSummary s : BlueprintStorage.listSummaries()) {
                if (s.id().equals(bpId.toString())) summary = s;
            }
            helper.assertTrue(summary != null, "real summary for library sync");

            CompoundTag tag = new CompoundTag();
            tag.putString("k", "v");

            assertRoundTrip(helper, SetPointMessage.STREAM_CODEC, new SetPointMessage(1, new BlockPos(1, 2, 3)));
            assertRoundTrip(helper, ClearPointsMessage.STREAM_CODEC, new ClearPointsMessage());
            assertRoundTrip(helper, SetBlueprintNameMessage.STREAM_CODEC, new SetBlueprintNameMessage("名称A"));
            assertRoundTrip(helper, UpdateOffsetMessage.STREAM_CODEC, new UpdateOffsetMessage(1, 2, 3));
            assertRoundTrip(helper, UpdateSettingsMessage.STREAM_CODEC, new UpdateSettingsMessage(Rotation.CLOCKWISE_90));
            assertRoundTrip(helper, C2S_ProjectRangePointMessage.STREAM_CODEC,
                    new C2S_ProjectRangePointMessage(1, new BlockPos(4, 5, 6)));
            assertRoundTrip(helper, C2S_RequestConfigMessage.STREAM_CODEC, new C2S_RequestConfigMessage());
            assertRoundTrip(helper, C2S_ApplyConfigMessage.STREAM_CODEC,
                    new C2S_ApplyConfigMessage(
                            Map.of("volumeLimits.maxVolumeSpatial", 100),
                            Map.of("largeBlueprintBroadcastEnabled", true),
                            Map.of("excludedBlocks", List.of("minecraft:bedrock"))));
            assertRoundTrip(helper, S2C_ConfigSyncMessage.STREAM_CODEC, S2C_ConfigSyncMessage.fromConfig());
            assertRoundTrip(helper, S2C_ConfigValuesMessage.STREAM_CODEC, S2C_ConfigValuesMessage.snapshot());
            assertRoundTrip(helper, S2C_ConfigPermMessage.STREAM_CODEC, new S2C_ConfigPermMessage(true));
            assertRoundTrip(helper, S2C_OpenConfigMessage.STREAM_CODEC, new S2C_OpenConfigMessage());
            assertRoundTrip(helper, C2S_RequestLibraryMessage.STREAM_CODEC, new C2S_RequestLibraryMessage());
            assertRoundTrip(helper, S2C_LibrarySyncMessage.STREAM_CODEC,
                    new S2C_LibrarySyncMessage(List.of(summary)));
            assertRoundTrip(helper, C2S_RenameBlueprintMessage.STREAM_CODEC,
                    new C2S_RenameBlueprintMessage("id-1", "new", "s", "l"));
            assertRoundTrip(helper, C2S_ConvertBlueprintMessage.STREAM_CODEC, new C2S_ConvertBlueprintMessage("id-1"));
            assertRoundTrip(helper, C2S_SetWithdrawPropertyMessage.STREAM_CODEC,
                    new C2S_SetWithdrawPropertyMessage("id-1", 0, 60));
            assertRoundTrip(helper, C2S_DeleteBlueprintMessage.STREAM_CODEC, new C2S_DeleteBlueprintMessage("id-1"));
            assertRoundTrip(helper, S2C_VoidBagSyncMessage.STREAM_CODEC,
                    new S2C_VoidBagSyncMessage(
                            List.of(new S2C_VoidBagSyncMessage.Entry(null, 5, "bp-1", "lost")), "target"));
            assertRoundTrip(helper, C2S_RequestMaterialSpaceMessage.STREAM_CODEC, new C2S_RequestMaterialSpaceMessage());
            assertRoundTrip(helper, S2C_MaterialSpaceSyncMessage.STREAM_CODEC,
                    new S2C_MaterialSpaceSyncMessage(Map.of("minecraft:stone", 3), Map.of("k", tag),
                            true, "name", "uuid", 3, 100));
            assertRoundTrip(helper, C2S_MaterialDepositMessage.STREAM_CODEC,
                    new C2S_MaterialDepositMessage(2, "minecraft:stone", 5));
            assertRoundTrip(helper, C2S_RequestBlueprintMaterialsMessage.STREAM_CODEC,
                    new C2S_RequestBlueprintMaterialsMessage("id-1"));
            assertRoundTrip(helper, S2C_BlueprintMaterialsMessage.STREAM_CODEC,
                    new S2C_BlueprintMaterialsMessage("id-1", "create", List.of("minecraft:stone"), List.of(3)));
        } finally {
            BlueprintStorage.delete(bpId.toString());
        }
        helper.succeed();
    }

    // ── t03：decode 上限（抛出 / 截断）────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_decodeGuards(GameTestHelper helper) {
        // ConfigValues：ints 计数 4097 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(4097);
                S2C_ConfigValuesMessage.decode(buf);
            } finally { buf.release(); }
        }, "ConfigValues ints=4097");

        // LibrarySync：摘要计数 65537 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(65537);
                S2C_LibrarySyncMessage.decode(buf);
            } finally { buf.release(); }
        }, "LibrarySync summaries=65537");

        // MaterialSpaceSync：counts 计数 65537 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(65537);
                S2C_MaterialSpaceSyncMessage.decode(buf);
            } finally { buf.release(); }
        }, "MaterialSpaceSync counts=65537");

        // VoidBagSync：entries 计数 65537 → DecoderException
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(65537);
                S2C_VoidBagSyncMessage.decode(buf);
            } finally { buf.release(); }
        }, "VoidBagSync entries=65537");

        // ApplyConfig：ints 计数 300 → Math.min 截断到 256（写 256 对，后面 bools/lists 各 0）
        {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeVarInt(300);
                for (int i = 0; i < 256; i++) {
                    buf.writeUtf("key" + i, 128);
                    buf.writeVarInt(i);
                }
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                C2S_ApplyConfigMessage msg = C2S_ApplyConfigMessage.decode(buf);
                // 字段私有：从再编码的字节里读回计数（与网络一致）
                RegistryFriendlyByteBuf out = newBuf(helper);
                try {
                    C2S_ApplyConfigMessage.STREAM_CODEC.encode(out, msg);
                    helper.assertValueEqual(256, out.readVarInt(), "ApplyConfig ints truncated to 256");
                } finally { out.release(); }
            } finally { buf.release(); }
        }

        // BlueprintMaterials：n=400 → Math.min 截断到 300（写 300 对）
        {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("id", 64);
                buf.writeUtf("create", 32);
                buf.writeVarInt(400);
                for (int i = 0; i < 300; i++) {
                    buf.writeUtf("k" + i, 256);
                    buf.writeVarInt(i);
                }
                S2C_BlueprintMaterialsMessage msg = S2C_BlueprintMaterialsMessage.decode(buf);
                // 字段私有：从再编码的字节里读回计数（与网络一致）
                RegistryFriendlyByteBuf out = newBuf(helper);
                try {
                    S2C_BlueprintMaterialsMessage.STREAM_CODEC.encode(out, msg);
                    out.readUtf(64);
                    out.readUtf(32);
                    helper.assertValueEqual(300, out.readVarInt(), "BlueprintMaterials truncated to 300");
                } finally { out.release(); }
            } finally { buf.release(); }
        }
        helper.succeed();
    }

    private static void expectDecoderException(GameTestHelper helper, Runnable action, String what) {
        try {
            action.run();
            helper.fail("expected DecoderException: " + what);
        } catch (DecoderException expected) {
            // ok
        }
    }

    // ── t04：物品状态 handler ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_itemStateHandlers(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        player.setGameMode(GameType.CREATIVE);

        // SetPoint（主手空白蓝图）
        ItemStack blank = new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, blank);
        SetPointMessage.handleServer(new SetPointMessage(1, new BlockPos(10, 64, 10)), player);
        helper.assertTrue(new BlockPos(10, 64, 10).equals(BlankBlueprintItem.getPoint1(player.getMainHandItem())),
                "point1 set");
        SetPointMessage.handleServer(new SetPointMessage(2, new BlockPos(20, 64, 20)), player);
        helper.assertTrue(new BlockPos(20, 64, 20).equals(BlankBlueprintItem.getPoint2(player.getMainHandItem())),
                "point2 set");

        // ClearPoints
        ClearPointsMessage.handleServer(new ClearPointsMessage(), player);
        helper.assertTrue(BlankBlueprintItem.getPoint1(player.getMainHandItem()) == null
                && BlankBlueprintItem.getPoint2(player.getMainHandItem()) == null, "points cleared");

        // SetBlueprintName（sanitize）
        SetBlueprintNameMessage.handleServer(new SetBlueprintNameMessage("名字<script>"), player);
        String safe = BlankBlueprintItem.getBlueprintName(player.getMainHandItem());
        helper.assertTrue(safe.contains("名字") && !safe.contains("<") && !safe.contains(">"), "name sanitized");

        // 负例：非法 pointIndex → 组件不变
        ItemStack blank2 = new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, blank2);
        SetPointMessage.handleServer(new SetPointMessage(3, new BlockPos(1, 1, 1)), player);
        helper.assertTrue(BlankBlueprintItem.getPoint1(player.getMainHandItem()) == null
                && BlankBlueprintItem.getPoint2(player.getMainHandItem()) == null, "invalid index rejected");

        // UpdateOffset / UpdateSettings：有意不写物品数据 → 不变
        ItemStack spatial = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, spatial);
        UpdateOffsetMessage.handleServer(new UpdateOffsetMessage(5, 5, 5), player);
        UpdateSettingsMessage.handleServer(new UpdateSettingsMessage(Rotation.CLOCKWISE_90), player);
        helper.assertValueEqual(0, SpatialBlueprintItem.getXOffset(player.getMainHandItem()), "offset untouched");
        helper.assertTrue(SpatialBlueprintItem.getRotation(player.getMainHandItem()) == Rotation.NONE,
                "rotation untouched");

        // 负例：非 OP 的项目范围点 → 组件不变
        ItemStack project = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, project);
        C2S_ProjectRangePointMessage.handleServer(
                new C2S_ProjectRangePointMessage(1, new BlockPos(1, 2, 3)), player);
        helper.assertTrue(ProjectBlueprintItem.getRangePoint(player.getMainHandItem(), 1) == null,
                "non-op range point rejected");
        helper.succeed();
    }

    // ── t05：ApplyConfig 权限与范围 ───────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t05_applyConfig(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        int orig = BlueprintConfig.MAX_VOLUME_SPATIAL.get();
        // 配置项的稳定 id 就是它在 toml 里的完整路径（含 general 前缀）
        String volumeId = com.xioyim.spatialblueprint.config.ConfigRegistry.idOf(
                BlueprintConfig.MAX_VOLUME_SPATIAL);
        boolean opped = false;
        try {
            // 非 OP → no_permission，配置不变
            received.clear();
            C2S_ApplyConfigMessage.handleServer(new C2S_ApplyConfigMessage(
                    Map.of(volumeId, 12345), Map.of(), Map.of()), player);
            S2C_OperationResult denied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.config.error.no_permission", denied.messageKey,
                    "non-op denied");
            helper.assertValueEqual(orig, BlueprintConfig.MAX_VOLUME_SPATIAL.get(), "config unchanged for non-op");

            // OP 改合法值 → 生效 + 收到 ConfigValues + saved
            // 注意：GameTest 服的 op-permission-level = 0，PlayerList.op() 加进去的条目 level 也是 0，
            // hasPermissions(2) 仍为 false；测试直接写一条 level 4 的 op 记录。
            helper.getLevel().getServer().getPlayerList().getOps().add(
                    new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 4, false));
            opped = true;
            received.clear();
            C2S_ApplyConfigMessage.handleServer(new C2S_ApplyConfigMessage(
                    Map.of(volumeId, 12345), Map.of(), Map.of()), player);
            helper.assertValueEqual(BlueprintConfig.MAX_VOLUME_SPATIAL.get(), 12345, "op applied");
            boolean gotValues = received.stream().anyMatch(p -> p instanceof S2C_ConfigValuesMessage);
            helper.assertTrue(gotValues, "config values pushed back");
            S2C_OperationResult saved = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.config.saved", saved.messageKey, "saved");

            // 越界值 → rejected → saved_partial，值保持
            received.clear();
            C2S_ApplyConfigMessage.handleServer(new C2S_ApplyConfigMessage(
                    Map.of(volumeId, 0), Map.of(), Map.of()), player);
            helper.assertValueEqual(BlueprintConfig.MAX_VOLUME_SPATIAL.get(), 12345, "out-of-range kept old");
            S2C_OperationResult partial = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.config.saved_partial", partial.messageKey, "saved_partial");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            BlueprintConfig.MAX_VOLUME_SPATIAL.set(orig);
            BlueprintConfig.MAX_VOLUME_SPATIAL.save();
        }
        helper.succeed();
    }

    // ── t06：蓝图库归属 ───────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_libraryOwnership(GameTestHelper helper) {
        FakePlayer owner = fakePlayer(helper);
        owner.setGameMode(GameType.CREATIVE);
        FakePlayer other = fakePlayer(helper);
        other.setGameMode(GameType.CREATIVE);

        BlueprintData data = sampleBlueprint("t06-old");
        data.ownerUuid = owner.getUUID().toString();
        UUID id = BlueprintStorage.save(data);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        try {
            // B 改名 → not_owner，名称不变
            received.clear();
            C2S_RenameBlueprintMessage.handleServer(
                    new C2S_RenameBlueprintMessage(id.toString(), "t06-hacked", "", ""), other);
            S2C_OperationResult renameDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.blueprint_not_owner", renameDenied.messageKey,
                    "rename denied");
            helper.assertValueEqual("t06-old", BlueprintStorage.load(id.toString()).name, "name unchanged");

            // A 改名 → 成功
            received.clear();
            C2S_RenameBlueprintMessage.handleServer(
                    new C2S_RenameBlueprintMessage(id.toString(), "t06-renamed", "s", "l"), owner);
            helper.assertValueEqual("t06-renamed", BlueprintStorage.load(id.toString()).name, "name changed");

            // B 删除 → not_owner，文件仍在
            received.clear();
            C2S_DeleteBlueprintMessage.handleServer(new C2S_DeleteBlueprintMessage(id.toString()), other);
            S2C_OperationResult deleteDenied = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.blueprint_not_owner", deleteDenied.messageKey,
                    "delete denied");
            helper.assertTrue(BlueprintStorage.findFile(id.toString()) != null, "file still exists");

            // A 删除 → 成功
            C2S_DeleteBlueprintMessage.handleServer(new C2S_DeleteBlueprintMessage(id.toString()), owner);
            helper.assertTrue(BlueprintStorage.findFile(id.toString()) == null, "file deleted by owner");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintStorage.delete(id.toString());
        }
        helper.succeed();
    }

    // ── t07：材料存入 ─────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_materialDeposit(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        MaterialSpace space = MaterialSpace.get(helper.getLevel());
        UUID pid = player.getUUID();
        int beforeStone = space.count(pid, "minecraft:stone");

        // 手持一份带材料清单的投影蓝图（存入要求：蓝图在手上 + 清单含该键 + 非创造）
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS), 3);
        String swordKey = MaterialKey.of(sword, helper.getLevel().registryAccess());

        BlueprintData bp = sampleBlueprint("t07");
        bp.category = "project";
        bp.materialList = new com.xioyim.spatialblueprint.util.MaterialList();
        bp.materialList.blockCounts.put("minecraft:stone", 5);
        bp.materialList.blockCounts.put(swordKey, 1);
        UUID bpId = BlueprintStorage.save(bp);
        ItemStack blueprintStack = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
        blueprintStack.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), bpId.toString());
        player.setItemInHand(InteractionHand.MAIN_HAND, blueprintStack);

        player.getInventory().add(new ItemStack(Items.STONE, 5));
        player.getInventory().add(sword);

        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        try {
            // 存 3 个石头
            C2S_MaterialDepositMessage.handleServer(new C2S_MaterialDepositMessage(2, "minecraft:stone", 3), player);
            helper.assertValueEqual(space.count(pid, "minecraft:stone"), beforeStone + 3, "stone deposited");
            helper.assertValueEqual(countItem(player, Items.STONE), 2, "stone removed from inventory");

            // 存 1 把附魔剑（复合键）
            C2S_MaterialDepositMessage.handleServer(new C2S_MaterialDepositMessage(2, swordKey, 1), player);
            helper.assertValueEqual(space.count(pid, swordKey), 1, "sword deposited");
            helper.assertValueEqual(countItem(player, Items.DIAMOND_SWORD), 0, "sword removed");

            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_MaterialSpaceSyncMessage),
                    "sync pushed after deposit");

            // 请求材料空间 → 回 sync
            received.clear();
            C2S_RequestMaterialSpaceMessage.handleServer(new C2S_RequestMaterialSpaceMessage(), player);
            helper.assertTrue(received.stream().anyMatch(p -> p instanceof S2C_MaterialSpaceSyncMessage),
                    "sync on request");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintStorage.delete(bpId.toString());
            // 清掉测试存进材料空间的材料（避免污染后续测试世界）
            space.clearPool(pid);
        }
        helper.succeed();
    }

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == item) n += s.getCount();
        }
        return n;
    }
}
