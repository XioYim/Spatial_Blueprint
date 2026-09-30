package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.CommandNode;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ReloadConfigMessage;
import com.xioyim.spatialblueprint.network.PlaceBlueprintMessage;
import com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage;
import com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenConfigMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintPlacementHandler;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.FluidDisplayMode;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.SBUndoCooldown;
import com.xioyim.spatialblueprint.util.SBUndoData;
import com.xioyim.spatialblueprint.util.TeleportPermissions;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SBN-013 命令与 Reload GameTest（t01～t08）。
 * 命令一律走真实 dispatcher（performPrefixedCommand）；OP 按 K11 写 ServerOpListEntry 等级 4。
 * 放置类断言沿用 C26 布局：自家结构格上方的 virgin 空域，异步 starter 排到 150+。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN013Tests {

    // ── 编解码 / 玩家辅助（同 SBN012 口径）─────────────────────────────────────

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
                new GameProfile(UUID.randomUUID(), "SB13" + UUID.randomUUID().toString().substring(0, 4)));
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

    private static int runCommand(GameTestHelper helper, ServerPlayer as, String command) {
        // 1.21.1 的 Commands#performPrefixedCommand 返回 void；要拿命令返回值走 brigadier 的
        // dispatcher.execute(String, source)（performPrefixedCommand 内部也是 strip 前缀后 execute）。
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher()
                    .execute(command, as.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return -1;
        }
    }

    private static S2C_TeleportPermMessage firstTeleportPerm(List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_TeleportPermMessage msg) return msg;
        }
        return null;
    }

    private static boolean gotMaterialSpace(List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_MaterialSpaceSyncMessage) return true;
        }
        return false;
    }

    private static boolean gotOpenConfig(List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_OpenConfigMessage) return true;
        }
        return false;
    }

    private static Path configToml() {
        return FMLPaths.CONFIGDIR.get().resolve("spatial_blueprint").resolve("spatial_blueprint.toml");
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
                {"capture_area_message", com.xioyim.spatialblueprint.network.CaptureAreaMessage.TYPE.id().getPath()},
                {"capture_projection_message", com.xioyim.spatialblueprint.network.CaptureProjectionMessage.TYPE.id().getPath()},
                {"c2s_void_recapture_message", com.xioyim.spatialblueprint.network.C2S_VoidRecaptureMessage.TYPE.id().getPath()},
                {"c2s_create_engineering_blueprint_message", com.xioyim.spatialblueprint.network.C2S_CreateEngineeringBlueprintMessage.TYPE.id().getPath()},
                {"c2s_create_sub_project_message", com.xioyim.spatialblueprint.network.C2S_CreateSubProjectMessage.TYPE.id().getPath()},
                {"place_blueprint_message", PlaceBlueprintMessage.TYPE.id().getPath()},
                // SBN-013 的 1 个（C2S，与 1.20.1 归类一致）
                {"c2s_reload_config_message", C2S_ReloadConfigMessage.TYPE.id().getPath()},
        };
        Set<String> ids = new HashSet<>();
        for (String[] pair : expected) {
            helper.assertValueEqual(pair[1], pair[0], "TYPE id naming");
            ids.add(pair[1]);
        }
        helper.assertValueEqual(ids.size(), 78, "78 unique payload ids");
        helper.assertValueEqual(C2S_ReloadConfigMessage.TYPE.id().getNamespace(),
                SpatialBlueprintMod.MODID, "namespace");
        helper.succeed();
    }

    // ── t02：空消息编解码（C15）──────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codec(GameTestHelper helper) {
        assertRoundTrip(helper, C2S_ReloadConfigMessage.STREAM_CODEC, new C2S_ReloadConfigMessage());
        helper.succeed();
    }

    // ── t03：配置热加载 ───────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_reload(GameTestHelper helper) {
        FakePlayer op = fakePlayer(helper);
        FakePlayer nonOp = fakePlayer(helper);
        Path toml = configToml();
        boolean opped = false;
        byte[] original = null;
        try {
            helper.assertTrue(Files.exists(toml), "config toml exists");
            try {
                original = Files.readAllBytes(toml);
            } catch (java.io.IOException e) {
                helper.fail("read config failed: " + e.getMessage());
                return;
            }
            grantOp(helper, op);
            opped = true;

            // a. 内存改值不存盘 → OP 发 reload → 回盘上值
            int diskVal = BlueprintConfig.MAX_VOLUME_SPATIAL.get();
            BlueprintConfig.MAX_VOLUME_SPATIAL.set(diskVal + 777);
            helper.assertValueEqual(BlueprintConfig.MAX_VOLUME_SPATIAL.get(), diskVal + 777,
                    "memory override in place");
            C2S_ReloadConfigMessage.handleServer(new C2S_ReloadConfigMessage(), op);
            helper.assertValueEqual(BlueprintConfig.MAX_VOLUME_SPATIAL.get(), diskVal,
                    "OP reload restored disk value");

            // b. 非 OP 同样操作 → 仍是内存新值（没重载）
            BlueprintConfig.MAX_VOLUME_SPATIAL.set(diskVal + 888);
            C2S_ReloadConfigMessage.handleServer(new C2S_ReloadConfigMessage(), nonOp);
            helper.assertValueEqual(BlueprintConfig.MAX_VOLUME_SPATIAL.get(), diskVal + 888,
                    "non-OP reload denied, memory kept");
            BlueprintConfig.MAX_VOLUME_SPATIAL.set(diskVal);

            // c. 改盘生效（写盘 → /sb reload 命令 → 读盘新值 + 派生缓存清）
            //
            // 改盘用直接文本替换：此版本 night-config 的 CommentedFileConfig.set+save 不落盘
            // （独立实验复现：save 后重读仍是旧值），测试手段绕开它；生产 doReload 只读不写、不受影响。
            int onDisk = diskVal + 12345;
            String tomlPath = com.xioyim.spatialblueprint.config.ConfigRegistry.idOf(
                    BlueprintConfig.MAX_VOLUME_SPATIAL);
            String before = new String(original, java.nio.charset.StandardCharsets.UTF_8);
            String after = before.replace("maxVolumeSpatial = " + diskVal,
                    "maxVolumeSpatial = " + onDisk);
            helper.assertTrue(!after.equals(before), "disk value patched in text");
            try {
                Files.write(toml, after.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                helper.fail("write config failed: " + e.getMessage());
                return;
            }
            Set<String> cacheBefore = BlueprintConfig.getBoxEntities();
            int ret = runCommand(helper, op, "sb reload");
            helper.assertValueEqual(ret, 1, "reload command returns 1");
            helper.assertValueEqual(BlueprintConfig.MAX_VOLUME_SPATIAL.get(), onDisk,
                    "disk value applied");
            helper.assertTrue(cacheBefore != BlueprintConfig.getBoxEntities(),
                    "derived cache cleared by reload");
            helper.assertTrue(tomlPath.endsWith("maxVolumeSpatial"), "stable id is toml path");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (original != null) {
                try {
                    Files.write(toml, original);
                } catch (Exception ignored) {
                }
                C2S_ReloadConfigMessage.doReload();
            } else {
                BlueprintConfig.MAX_VOLUME_SPATIAL.set(BlueprintConfig.MAX_VOLUME_SPATIAL.get());
            }
            if (opped) revokeOp(helper, op);
        }
        helper.succeed();
    }

    // ── t04：命令树与 requires 挂载 ───────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_permissionTree(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        boolean opped = false;
        try {
            CommandNode<net.minecraft.commands.CommandSourceStack> sb = helper.getLevel().getServer()
                    .getCommands().getDispatcher().getRoot().getChild("sb");
            helper.assertTrue(sb != null, "sb node exists");
            Set<String> names = new HashSet<>();
            for (CommandNode<net.minecraft.commands.CommandSourceStack> child : sb.getChildren()) {
                names.add(child.getName());
            }
            helper.assertValueEqual(names, Set.of("undo", "config", "reload", "space", "tpperm", "project"),
                    "six subcommands");

            var nonOp = player.createCommandSourceStack();
            // 非 OP：config / reload / tpperm 不可用；undo / space 自身可用
            helper.assertTrue(!sb.getChild("config").canUse(nonOp), "config non-op denied");
            helper.assertTrue(!sb.getChild("reload").canUse(nonOp), "reload non-op denied");
            helper.assertTrue(!sb.getChild("tpperm").canUse(nonOp), "tpperm non-op denied");
            helper.assertTrue(sb.getChild("undo").canUse(nonOp), "undo non-op allowed");
            helper.assertTrue(sb.getChild("space").canUse(nonOp), "space self non-op allowed");
            // space <player> 的 requires 挂在 player 参数节点上
            helper.assertTrue(!sb.getChild("space").getChild("player").canUse(nonOp),
                    "space <player> non-op denied");
            CommandNode<net.minecraft.commands.CommandSourceStack> project = sb.getChild("project");
            helper.assertTrue(!project.getChild("release").canUse(nonOp), "project release non-op denied");
            helper.assertTrue(!project.getChild("list").canUse(nonOp), "project list non-op denied");
            helper.assertTrue(project.getChild("activate").canUse(nonOp), "project activate non-op allowed");
            helper.assertTrue(project.getChild("claim").canUse(nonOp), "project claim non-op allowed");

            // OP：全部可用
            grantOp(helper, player);
            opped = true;
            var opSource = player.createCommandSourceStack();
            for (String name : List.of("undo", "config", "reload", "space", "tpperm", "project")) {
                helper.assertTrue(sb.getChild(name).canUse(opSource), name + " op allowed");
            }
            helper.assertTrue(sb.getChild("space").getChild("player").canUse(opSource),
                    "space <player> op allowed");
            helper.assertTrue(project.getChild("release").canUse(opSource), "project release op allowed");
            helper.assertTrue(project.getChild("list").canUse(opSource), "project list op allowed");
        } finally {
            if (opped) revokeOp(helper, player);
        }
        helper.succeed();
    }

    // ── t05：/sb undo ────────────────────────────────────────────────────────
    //
    // starter@150 放置（创造 → 快照 + 放置冷却），当场断言冷却拦截；checker@260 撤回。

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void t05_undo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final List<String> files = new ArrayList<>();
        BlueprintData data = new BlueprintData();
        data.name = "t05-undo";
        data.category = "create";
        data.size = new BlockPos(2, 1, 1);
        data.animDuration = 1;
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        final String bpId = BlueprintStorage.save(data).toString();
        files.add(bpId);

        final FakePlayer creator = creativePlayer(helper);
        final FakePlayer survivor = fakePlayer(helper);
        final BlockPos target = helper.absolutePos(new BlockPos(1, 4, 1));
        creator.setPos(target.getX() + 2.5, target.getY() + 2.0, target.getZ() + 2.5);
        ItemStack spatial = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
        com.xioyim.spatialblueprint.item.AbstractBlueprintItem.setBlueprintId(spatial, bpId);
        creator.setItemInHand(InteractionHand.MAIN_HAND, spatial);

        helper.runAtTickTime(150, () -> {
            // 创造放置 → 快照记录 + 放置冷却
            PlaceBlueprintMessage.handleServer(new PlaceBlueprintMessage(target, Rotation.NONE,
                    0, 0, 0, true, FluidDisplayMode.SHOW_ALL), creator);
            helper.assertTrue(SBUndoData.get(level).hasHistory(creator.getUUID()),
                    "creative placement recorded undo");
            helper.assertTrue(SBUndoCooldown.isOnCooldown(creator.getUUID()),
                    "placement cooldown on");
            // 冷却内 undo → 被拦（冷却先判）、记录还在
            int inside = runCommand(helper, creator, "sb undo");
            helper.assertValueEqual(inside, 0, "undo inside cooldown returns 0");
            helper.assertTrue(SBUndoData.get(level).hasHistory(creator.getUUID()),
                    "record kept while on cooldown");
        });

        helper.runAtTickTime(260, () -> {
            try {
                // 动画已完成；清掉放置冷却 → undo 成功
                SBUndoCooldown.clearPlayer(creator.getUUID());
                int undone = runCommand(helper, creator, "sb undo");
                helper.assertValueEqual(undone, 1, "undo returns 1");
                helper.assertTrue(!SBUndoData.get(level).hasHistory(creator.getUUID()),
                        "history empty after undo");
                helper.assertTrue(SBUndoCooldown.isOnCooldown(creator.getUUID()),
                        "post-undo cooldown on");
                int[] bbox = BlueprintPlacementHandler.computeWorldBBox(target, data.size, Rotation.NONE);
                for (int y = bbox[1]; y <= bbox[4]; y++) {
                    for (int x = bbox[0]; x <= bbox[3]; x++) {
                        for (int z = bbox[2]; z <= bbox[5]; z++) {
                            helper.assertTrue(level.getBlockState(new BlockPos(x, y, z)).isAir(),
                                    "restored to air at " + x + "," + y + "," + z);
                        }
                    }
                }
                // 撤回后冷却先判：立即再 undo → 0（冷却）；清掉后 → 0（no_history）
                helper.assertValueEqual(runCommand(helper, creator, "sb undo"), 0,
                        "second undo blocked by post-undo cooldown");
                SBUndoCooldown.clearPlayer(creator.getUUID());
                helper.assertValueEqual(runCommand(helper, creator, "sb undo"), 0,
                        "third undo no_history returns 0");
                // 生存 → creative_only、什么都不变。
                // 云端验收补强：生存玩家必须真有一条可撤销记录且不在冷却，否则 no_history 也返回 0，
                // 创造模式判定被短路时测不出来（V6 变异复核发现）。
                com.xioyim.spatialblueprint.util.UndoRecord survivorRecord =
                        BlueprintPlacementHandler.snapshotRegion(level,
                                target.getX(), target.getY(), target.getZ(),
                                target.getX(), target.getY(), target.getZ());
                SBUndoData.get(level).addRecord(survivor.getUUID(), survivorRecord, 10);
                level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                SBUndoCooldown.clearPlayer(survivor.getUUID());
                helper.assertValueEqual(runCommand(helper, survivor, "sb undo"), 0,
                        "survivor undo denied");
                helper.assertTrue(SBUndoData.get(level).hasHistory(survivor.getUUID()),
                        "survivor record kept");
                helper.assertTrue(level.getBlockState(target).is(Blocks.STONE),
                        "world untouched by survivor undo");
            } finally {
                BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
                SBUndoCooldown.clearPlayer(creator.getUUID());
                SBUndoCooldown.clearPlayer(survivor.getUUID());
                while (SBUndoData.get(level).hasHistory(creator.getUUID())) {
                    SBUndoData.get(level).pop(creator.getUUID());
                }
                while (SBUndoData.get(level).hasHistory(survivor.getUUID())) {
                    SBUndoData.get(level).pop(survivor.getUUID());
                }
                level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                for (String fid : files) BlueprintStorage.delete(fid);
            }
            helper.succeed();
        });
    }

    // ── t06：/sb tpperm ─────────────────────────────────────────────────────
    //
    // GameTest 服的 server.getProfileCache() 为 null（离线测试服未初始化用户缓存），
    // 命令的名字参数（GameProfileArgument → profileCache）解析不可达 —— 权限表部分用公开 API
    // 覆盖、包推送走 SBCommands.syncTeleportPerm、list 命令走真实 dispatcher（该路径 null-safe）。

    @GameTest(template = "empty")
    public static void t06_tpperm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer op = fakePlayer(helper);
        FakePlayer target = fakePlayer(helper);
        FakePlayer nonOp = fakePlayer(helper);
        UUID offlineId = UUID.randomUUID();
        List<CustomPacketPayload> received = new ArrayList<>();
        Set<UUID> myUuids = Set.of(op.getUUID(), target.getUUID());
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {
            if (myUuids.contains(p.getUUID())) received.add(payload);
        };
        boolean opped = false;
        TeleportPermissions perms = TeleportPermissions.get(level);
        try {
            grantOp(helper, op);
            opped = true;

            // 权限表：grant / revoke（命令执行体的核心逻辑）
            helper.assertTrue(perms.grant(target.getUUID()), "grant records");
            helper.assertTrue(perms.has(target.getUUID()), "target granted");

            // 权限同步包：直接走 SBCommands.syncTeleportPerm（命令内的在线同步查玩家列表，
            // FakePlayer 不在列表；这条验证「推送路径」本身，见报告六）
            received.clear();
            com.xioyim.spatialblueprint.command.SBCommands.syncTeleportPerm(target);
            S2C_TeleportPermMessage grantedMsg = firstTeleportPerm(received);
            helper.assertTrue(grantedMsg != null && grantedMsg.isAllowed(), "grant pushes allowed=true");

            // list 命令真实执行：非空（>=1：可能含并发测试/历史目录的授权）返回计数；
            // 非 OP 被 requires 拦（解析失败 → -1）
            helper.assertTrue(runCommand(helper, op, "sb tpperm list") >= 1,
                    "tpperm list returns count");
            helper.assertValueEqual(runCommand(helper, nonOp, "sb tpperm list"), -1,
                    "tpperm list non-op rejected by requires");

            // revoke 后推送 false
            helper.assertTrue(perms.revoke(target.getUUID()), "revoke clears");
            helper.assertTrue(!perms.has(target.getUUID()), "target revoked");
            received.clear();
            com.xioyim.spatialblueprint.command.SBCommands.syncTeleportPerm(target);
            S2C_TeleportPermMessage revokedMsg = firstTeleportPerm(received);
            helper.assertTrue(revokedMsg != null && !revokedMsg.isAllowed(), "revoke pushes allowed=false");

            // 离线 GameProfile（UUID 级）同样可预先授权
            helper.assertTrue(perms.grant(offlineId), "offline profile granted");
            helper.assertTrue(perms.has(offlineId), "offline recorded");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            perms.revoke(target.getUUID());
            perms.revoke(offlineId);
            if (opped) revokeOp(helper, op);
        }
        helper.succeed();
    }

    // ── t07：/sb space 与 /sb config ─────────────────────────────────────────
    //
    // /sb space <player> 的名字参数依赖 profileCache（同上，GameTest 不可达），该路径的
    // requires 挂载已在 t04 覆盖；这里跑能真实执行的自查与 config 两条。

    @GameTest(template = "empty")
    public static void t07_spaceAndConfig(GameTestHelper helper) {
        FakePlayer op = fakePlayer(helper);
        FakePlayer nonOp = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        UUID myUuid = op.getUUID();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {
            if (p.getUUID().equals(myUuid)) received.add(payload);
        };
        boolean opped = false;
        try {
            grantOp(helper, op);
            opped = true;

            // /sb space 自查 → S2C_MaterialSpaceSyncMessage
            received.clear();
            helper.assertValueEqual(runCommand(helper, op, "sb space"), 1, "space self returns 1");
            helper.assertTrue(gotMaterialSpace(received), "space self pushed material space");

            // /sb space <他人>：非 OP 被 requires 拦（解析失败 → -1、不发包）
            received.clear();
            helper.assertValueEqual(runCommand(helper, nonOp, "sb space SB13Nobody"), -1,
                    "space <player> non-op rejected by requires");
            helper.assertTrue(!gotMaterialSpace(received), "no push for non-op");

            // /sb config：非 OP 被拦；OP → S2C_OpenConfigMessage
            received.clear();
            helper.assertValueEqual(runCommand(helper, nonOp, "sb config"), -1, "config non-op rejected");
            helper.assertTrue(!gotOpenConfig(received), "no open config for non-op");
            received.clear();
            helper.assertValueEqual(runCommand(helper, op, "sb config"), 1, "config op returns 1");
            helper.assertTrue(gotOpenConfig(received), "open config pushed to op");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, op);
        }
        helper.succeed();
    }

    // ── t08：/sb project activate / claim / release / list ───────────────────

    @GameTest(template = "empty")
    public static void t08_project(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer nonOp = creativePlayer(helper);
        FakePlayer op = creativePlayer(helper);
        UUID projectId = UUID.randomUUID();
        UUID engId = UUID.randomUUID();
        String taskSetId = null;
        String filename = null;
        boolean opped = false;
        ProjectClaimRegistry registry = ProjectClaimRegistry.get(level);
        try {
            // 造项目 + 子工程入口文件（activate 会校验 loadEntry != null）
            ProjectData project = new ProjectData();
            project.name = "t08-project";
            project.dimension = ProjectData.dimensionOf(level);
            BlueprintData entryData = new BlueprintData();
            entryData.name = "t08-entry";
            entryData.category = "create";
            entryData.size = new BlockPos(1, 1, 1);
            entryData.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                    Blocks.STONE.defaultBlockState(), null));
            filename = ProjectStorage.saveEntry(projectId, engId, entryData);
            project.origin = BlockPos.ZERO;
            project.size = new BlockPos(4, 1, 4);
            project.anchor = BlockPos.ZERO;
            project.entries.add(new ProjectData.EntryData(1, filename, "t08-entry-label",
                    new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), 0));
            ProjectStorage.saveProject(projectId, project);

            // 站到远离其它测试的空域（claim 注册在同一维度，避开并发测试的范围）
            long base = 3000000L;
            nonOp.setPos(base + 1.5, 64.0, base + 1.5);
            op.setPos(base + 1.5, 64.0, base + 1.5);

            ItemStack publicStack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(publicStack, projectId);

            // 非 OP（创造）activate public → public_requires_op、无 claim
            nonOp.setItemInHand(InteractionHand.MAIN_HAND, publicStack.copy());
            helper.assertValueEqual(runCommand(helper, nonOp, "sb project activate"), 0,
                    "non-op public activate denied");
            helper.assertTrue(ProjectBlueprintItem.getTaskSetId(nonOp.getMainHandItem()) == null,
                    "non-op item not activated");

            // OP + 创造 → activate 成功
            grantOp(helper, op);
            opped = true;
            ItemStack opStack = publicStack.copy();
            op.setItemInHand(InteractionHand.MAIN_HAND, opStack);
            helper.assertValueEqual(runCommand(helper, op, "sb project activate"), 1,
                    "op activate returns 1");
            taskSetId = ProjectBlueprintItem.getTaskSetId(op.getMainHandItem());
            helper.assertTrue(taskSetId != null, "item activated");
            ProjectClaimRegistry.Claim claim = registry.findByTaskSet(taskSetId);
            helper.assertTrue(claim != null, "claim registered");
            helper.assertValueEqual(claim.projectId, projectId.toString(), "claim project id");
            helper.assertTrue(claim.minX == op.blockPosition().getX()
                    && claim.maxX == op.blockPosition().getX() + 3, "claim bounds");

            // claim 1 → 背包得到工程蓝图（入口文件存在）
            helper.assertValueEqual(runCommand(helper, op, "sb project claim 1"), 1,
                    "claim returns 1");
            ItemStack gained = null;
            for (int i = 0; i < op.getInventory().getContainerSize(); i++) {
                ItemStack s = op.getInventory().getItem(i);
                if (s.getItem() == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get()) gained = s;
            }
            helper.assertTrue(gained != null, "claim gave engineering blueprint");
            helper.assertValueEqual(EngineeringBlueprintItem.getTaskSetId(gained), taskSetId,
                    "sub project task set");
            helper.assertValueEqual(EngineeringBlueprintItem.getEntryIndex(gained), 1,
                    "sub project entry index");

            // release → claim 移除
            helper.assertValueEqual(runCommand(helper, op, "sb project release"), 1,
                    "release returns 1");
            helper.assertTrue(registry.findByTaskSet(taskSetId) == null, "claim released");

            // list → 至少 0（同维度可能还有并发测试的 claim；release 后本测试的不在其中）
            helper.assertTrue(runCommand(helper, op, "sb project list") >= 0,
                    "project list executes");
        } finally {
            if (taskSetId != null) {
                registry.removeByTaskSet(taskSetId);
                ProjectProgressData.get(level).forgetEntry(List.of(taskSetId), 1);
            }
            for (var c : new java.util.ArrayList<>(registry.all())) {
                if (c.projectId.equals(projectId.toString())) registry.removeByTaskSet(c.taskSetId);
            }
            if (filename != null) ProjectStorage.deleteEntryFile(projectId, filename);
            ProjectStorage.deleteProject(projectId);
            if (opped) revokeOp(helper, op);
        }
        helper.succeed();
    }

    // ── t09：MaterialSpace 存盘顺序确定（云端验收补）─────────────────────────
    //
    // 共享存档里玩家先多后少时，HashMap 扩容后不缩容，读回的副本容量小、遍历顺序不同，
    // save 写出的 ListTag 顺序跟着变，save → load → save 不相等（SBN003 t05 在云端因此转红）。

    @GameTest(template = "empty")
    public static void t09_materialSpaceSaveOrder(GameTestHelper helper) {
        net.minecraft.core.HolderLookup.Provider p = helper.getLevel().registryAccess();
        CompoundTag empty = new CompoundTag();
        empty.putInt("DataVersion", com.xioyim.spatialblueprint.util.LegacyDataFixer.current());
        com.xioyim.spatialblueprint.util.MaterialSpace ms =
                com.xioyim.spatialblueprint.util.MaterialSpace.load(empty, p);
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID id = UUID.randomUUID();
            players.add(id);
            ms.deposit(id, "minecraft:stone", i + 1, null);
            ms.bagDeposit(id, "bp-" + i, "minecraft:dirt", i + 1, null);
        }
        for (int i = 0; i < 35; i++) {
            ms.clearPool(players.get(i));
        }
        CompoundTag n1 = ms.save(new CompoundTag(), p);
        CompoundTag n2 = com.xioyim.spatialblueprint.util.MaterialSpace.load(n1, p).save(new CompoundTag(), p);
        helper.assertTrue(n1.equals(n2), "MaterialSpace save order stable after shrink");
        helper.succeed();
    }
}
