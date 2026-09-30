package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage;
import com.xioyim.spatialblueprint.network.PlaceBlueprintMessage;
import com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage;
import com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage;
import com.xioyim.spatialblueprint.network.S2C_WorldIdentityMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigPermMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ForagersInsightCompat;
import com.xioyim.spatialblueprint.util.FluidDisplayMode;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import com.xioyim.spatialblueprint.util.SBUndoCooldown;
import com.xioyim.spatialblueprint.util.SBUndoData;
import com.xioyim.spatialblueprint.util.TeleportPermissions;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.server.ServerPreviewTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SBN-014 登录 / 登出 / 切模式事件 + ForagersInsightCompat + 旧物品内联数据回退（t01～t08）。
 * 事件一律经 {@code NeoForge.EVENT_BUS.post(...)} 真实投递（主类与 BlueprintPlacementHandler 的监听都会收到）；
 * OP 按 K11 写 ServerOpListEntry 等级 4；配置类测试照 C28 原字节备份 / 写回。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN014Tests {

    private static final String LOST_NOTICE_KEY = "spatial_blueprint.message.void_blueprint.lost_notice";

    // ── 编解码 / 玩家辅助（同 SBN013 口径）─────────────────────────────────────

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

    private static FakePlayer fakePlayer(GameTestHelper helper) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SB14" + UUID.randomUUID().toString().substring(0, 4)));
        player.setGameMode(GameType.SURVIVAL);
        return player;
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

    /**
     * 建一个真正在场、连接可用的服务端玩家（与 SBN007Tests.mockPlayerInLevel 同构造）：
     * 它的连接仍停在配置相位，登录事件经由 placeNewPlayer 或手工 post 触发时，发 play 包会抛
     * UnsupportedOperationException（K21 测的就是主类的逐步兜底），不会污染别的测试。
     */
    private static ServerPlayer mockPlayerInLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(),
                "SB14Mock" + UUID.randomUUID().toString().substring(0, 4));
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static int countLostNotices(List<CustomPacketPayload> received) {
        int n = 0;
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_OperationResult result
                    && LOST_NOTICE_KEY.equals(result.messageKey)) {
                n++;
            }
        }
        return n;
    }

    private static int countPayload(List<CustomPacketPayload> received, Class<?> cls) {
        int n = 0;
        for (CustomPacketPayload payload : received) {
            if (cls.isInstance(payload)) n++;
        }
        return n;
    }

    /** 1.20.1 内联蓝图数据（SBN-011 手写样本）装进 custom_data.blueprintData。 */
    private static void setInlineData(ItemStack stack, CompoundTag blueprintRoot) {
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        custom.put("blueprintData", blueprintRoot);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
    }

    private static ItemStack inlineProjectionStack() {
        ItemStack stack = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
        setInlineData(stack, SBN011Tests.legacyBlueprintRoot());
        return stack;
    }

    private static ItemStack namedSword(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Test Blade"));
        return sword;
    }

    private static BlueprintData.BlockEntry findBlock(BlueprintData data, net.minecraft.world.level.block.Block block) {
        for (BlueprintData.BlockEntry e : data.allBlocks()) {
            if (e.state.is(block)) return e;
        }
        return null;
    }

    /** SBN-011 t02 的缩写：旧数据经修复器后应是 short_grass + 箱内锋利 III 有名钻石剑。 */
    private static void assertLegacyUpgraded(GameTestHelper helper, BlueprintData data) {
        helper.assertTrue(data != null, "inline data deserialized");
        helper.assertTrue(findBlock(data, Blocks.SHORT_GRASS) != null, "old grass upgraded to short_grass");
        BlueprintData.BlockEntry chestEntry = findBlock(data, Blocks.CHEST);
        helper.assertTrue(chestEntry != null && chestEntry.nbt != null, "chest entry with nbt");
        ChestBlockEntity fresh = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        fresh.loadWithComponents(chestEntry.nbt, helper.getLevel().registryAccess());
        helper.assertTrue(ItemStack.isSameItemSameComponents(fresh.getItem(0), namedSword(helper)),
                "chest sword upgraded");
        helper.assertValueEqual(fresh.getItem(0).get(DataComponents.CUSTOM_NAME).getString(),
                "Test Blade", "chest sword custom name");
    }

    // ── t01：登录四步与登录包 ─────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_login(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fakePlayer(helper);
        UUID myId = player.getUUID();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {
            if (p.getUUID().equals(myId)) received.add(payload);
        };
        boolean opped = false;
        try {
            // 非 OP：TeleportPerm(false) → WorldIdentity → ConfigPerm(false)，ConfigSync 恰好一次
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            List<CustomPacketPayload> main = new ArrayList<>();
            for (CustomPacketPayload payload : received) {
                if (payload instanceof S2C_TeleportPermMessage
                        || payload instanceof S2C_WorldIdentityMessage
                        || payload instanceof S2C_ConfigPermMessage) {
                    main.add(payload);
                }
            }
            helper.assertValueEqual(countPayload(received, S2C_ConfigSyncMessage.class), 1,
                    "config sync exactly once (placement handler)");
            helper.assertValueEqual(main.size(), 3, "three main login packets");
            helper.assertTrue(main.get(0) instanceof S2C_TeleportPermMessage, "1st is teleport perm");
            helper.assertTrue(!((S2C_TeleportPermMessage) main.get(0)).isAllowed(), "non-op denied");
            helper.assertTrue(main.get(1) instanceof S2C_WorldIdentityMessage, "2nd is world identity");
            S2C_WorldIdentityMessage idMsg = (S2C_WorldIdentityMessage) main.get(1);
            helper.assertValueEqual(idMsg.getUuid(), WorldIdentity.get(level).uuid().toString(),
                    "identity uuid = WorldIdentity");
            helper.assertValueEqual(idMsg.getName(), level.getServer().getWorldData().getLevelName(),
                    "identity name = save name");
            helper.assertTrue(main.get(2) instanceof S2C_ConfigPermMessage, "3rd is config perm");
            byte[] permBytes = encodeToBytes(S2C_ConfigPermMessage.STREAM_CODEC,
                    (S2C_ConfigPermMessage) main.get(2), helper);
            helper.assertValueEqual(permBytes[0], (byte) 0, "config perm false for non-op");

            // OP（K11）→ TeleportPerm(true) + ConfigPerm(true)
            grantOp(helper, player);
            opped = true;
            received.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertValueEqual(countPayload(received, S2C_ConfigSyncMessage.class), 1,
                    "config sync still exactly once for op");
            boolean opTeleport = false;
            boolean opConfig = false;
            for (CustomPacketPayload payload : received) {
                if (payload instanceof S2C_TeleportPermMessage tp) opTeleport = tp.isAllowed();
                if (payload instanceof S2C_ConfigPermMessage cp) {
                    opConfig = encodeToBytes(S2C_ConfigPermMessage.STREAM_CODEC, cp, helper)[0] == (byte) 1;
                }
            }
            helper.assertTrue(opTeleport, "op teleport allowed");
            helper.assertTrue(opConfig, "op config perm true");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, player);
        }
        helper.succeed();
    }

    // ── t02：孤儿次元袋提示 ───────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_orphanNotice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fakePlayer(helper);
        UUID myId = player.getUUID();
        MaterialSpace space = MaterialSpace.get(level);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {
            if (p.getUUID().equals(myId)) received.add(payload);
        };
        String orphanId = "sbn014-orphan-" + UUID.randomUUID();
        String presentId = null;
        String emptyId = "sbn014-empty-" + UUID.randomUUID();
        try {
            // a. 文件不存在 + total > 0 → lost_notice
            space.bagDeposit(myId, orphanId, "minecraft:stone", 1, null);
            helper.assertTrue(BlueprintStorage.findFile(orphanId) == null, "orphan bag file missing");
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertValueEqual(countLostNotices(received), 1, "orphan bag triggers lost notice");
            space.discardBag(myId, orphanId);

            // b. 文件存在 → 不提示
            BlueprintData fileData = new BlueprintData();
            fileData.name = "sbn014-present";
            fileData.category = "create";
            fileData.size = new BlockPos(1, 1, 1);
            fileData.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                    Blocks.STONE.defaultBlockState(), null));
            presentId = BlueprintStorage.save(fileData).toString();
            space.bagDeposit(myId, presentId, "minecraft:stone", 2, null);
            received.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertValueEqual(countLostNotices(received), 0, "existing file does not trigger notice");

            // c. total = 0 → 不提示（bagIds 里有条目但一件材料都没有；无公开 API 造空袋，反射注入）
            injectEmptyBag(helper, space, myId, emptyId);
            helper.assertValueEqual(space.bagTotal(myId, emptyId), 0, "injected bag total is zero");
            received.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertValueEqual(countLostNotices(received), 0, "zero-total bag does not trigger notice");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            space.discardBag(myId, orphanId);
            if (presentId != null) {
                space.discardBag(myId, presentId);
                BlueprintStorage.delete(presentId);
            }
            removeInjectedBag(helper, space, myId, emptyId);
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static void injectEmptyBag(GameTestHelper helper, MaterialSpace space, UUID player, String bpId) {
        try {
            java.lang.reflect.Field f = MaterialSpace.class.getDeclaredField("bags");
            f.setAccessible(true);
            Map<UUID, Map<String, Map<String, Integer>>> bags =
                    (Map<UUID, Map<String, Map<String, Integer>>>) f.get(space);
            bags.computeIfAbsent(player, k -> new java.util.LinkedHashMap<>())
                    .put(bpId, new java.util.LinkedHashMap<>());
        } catch (ReflectiveOperationException e) {
            helper.fail("inject empty bag failed: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeInjectedBag(GameTestHelper helper, MaterialSpace space, UUID player, String bpId) {
        try {
            java.lang.reflect.Field f = MaterialSpace.class.getDeclaredField("bags");
            f.setAccessible(true);
            Map<UUID, Map<String, Map<String, Integer>>> bags =
                    (Map<UUID, Map<String, Map<String, Integer>>>) f.get(space);
            Map<String, Map<String, Integer>> byBp = bags.get(player);
            if (byBp != null) {
                byBp.remove(bpId);
                if (byBp.isEmpty()) bags.remove(player);
            }
        } catch (ReflectiveOperationException e) {
            helper.fail("remove injected bag failed: " + e);
        }
    }

    // ── t03：配置相位 mock 玩家的登录（K21 逐步兜底）─────────────────────────

    @GameTest(template = "empty")
    public static void t03_mockLogin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MaterialSpace space = MaterialSpace.get(level);
        ServerPlayer mock = mockPlayerInLevel(helper);
        String orphanId = "sbn014-mock-orphan-" + UUID.randomUUID();
        try {
            // 给 mock 玩家一个孤儿袋：notifyLostDimensionalBlueprints 的发包会抛（配置相位），
            // 但逐步兜底后 orphanBagsDetected 的文件日志必须照写。
            space.bagDeposit(mock.getUUID(), orphanId, "minecraft:dirt", 1, null);
            boolean threw = false;
            try {
                NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(mock));
            } catch (Throwable t) {
                threw = true;
                SpatialBlueprintMod.LOGGER.error("[SBN014Tests] login post threw: {}", t.toString());
            }
            helper.assertTrue(!threw, "config-phase login post does not throw");
            Path adminLog = FMLPaths.CONFIGDIR.get().resolve("spatial_blueprint").resolve("logs")
                    .resolve("admin_" + java.time.LocalDate.now() + ".log");
            helper.assertTrue(Files.exists(adminLog), "admin log exists");
            String content = Files.readString(adminLog, StandardCharsets.UTF_8);
            helper.assertTrue(content.contains("ORPHAN_BAGS_DETECTED"), "orphan detection logged");
            helper.assertTrue(content.contains(mock.getScoreboardName()), "mock player in admin log");
        } catch (java.io.IOException e) {
            helper.fail("admin log read failed: " + e.getMessage());
        } finally {
            space.discardBag(mock.getUUID(), orphanId);
            level.getServer().getPlayerList().remove(mock);
        }
        helper.succeed();
    }

    // ── t04：切模式重推传送权限（必须用 newGameMode）──────────────────────────

    @GameTest(template = "empty")
    public static void t04_gameModeChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fakePlayer(helper);
        player.setGameMode(GameType.SURVIVAL);
        UUID myId = player.getUUID();
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {
            if (p.getUUID().equals(myId)) received.add(payload);
        };
        TeleportPermissions perms = TeleportPermissions.get(level);
        boolean opped = false;
        try {
            // 非 OP、无权限：实际模式仍是生存，事件说「正在切进创造」→ 必须放行（用的是 newGameMode）
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangeGameModeEvent(
                    player, GameType.SURVIVAL, GameType.CREATIVE));
            assertTeleportPerm(helper, received, true, "survival->creative");
            helper.assertValueEqual(player.gameMode.getGameModeForPlayer(), GameType.SURVIVAL,
                    "current mode unchanged during post");

            received.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangeGameModeEvent(
                    player, GameType.CREATIVE, GameType.SURVIVAL));
            assertTeleportPerm(helper, received, false, "creative->survival");

            // 授权后 (→生存) → true
            helper.assertTrue(perms.grant(myId), "grant records");
            received.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangeGameModeEvent(
                    player, GameType.CREATIVE, GameType.SURVIVAL));
            assertTeleportPerm(helper, received, true, "granted player");
            perms.revoke(myId);

            // OP（K11）→ true
            grantOp(helper, player);
            opped = true;
            received.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangeGameModeEvent(
                    player, GameType.CREATIVE, GameType.SURVIVAL));
            assertTeleportPerm(helper, received, true, "op player");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            perms.revoke(myId);
            if (opped) revokeOp(helper, player);
        }
        helper.succeed();
    }

    private static S2C_TeleportPermMessage firstTeleportPerm(List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_TeleportPermMessage msg) return msg;
        }
        return null;
    }

    private static void assertTeleportPerm(GameTestHelper helper, List<CustomPacketPayload> received,
                                            boolean expected, String what) {
        S2C_TeleportPermMessage msg = firstTeleportPerm(received);
        helper.assertTrue(msg != null, what + ": teleport perm packet present");
        helper.assertValueEqual(msg.isAllowed(), expected, what);
    }

    // ── t05：登出清理（撤回冷却 / 项目列表缓存 / 预览条目）────────────────────

    @GameTest(template = "empty")
    public static void t05_logout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = fakePlayer(helper);
        FakePlayer watcher = fakePlayer(helper);
        player.setPos(1000.5, 64.0, 1000.5);
        watcher.setPos(1005.5, 64.0, 1005.5);
        level.players().add(player);
        level.players().add(watcher);
        UUID myId = player.getUUID();
        List<CustomPacketPayload> received = new ArrayList<>();
        Map<UUID, List<CustomPacketPayload>> byTarget = new java.util.HashMap<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {
            byTarget.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(payload);
            if (p.getUUID().equals(myId)) received.add(payload);
        };
        try {
            // 撤回冷却
            SBUndoCooldown.setPlacementCooldown(myId, 5);
            helper.assertTrue(SBUndoCooldown.isOnCooldown(myId), "placement cooldown set");

            // 项目列表缓存：首次请求发包、立即再请求被冷却静默丢弃
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE), player);
            helper.assertTrue(!received.isEmpty(), "first project list request sent");
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE), player);
            helper.assertTrue(received.isEmpty(), "second request inside cooldown dropped");

            // 预览条目：watcher 收到带 origin 的广播
            ServerPreviewTracker.update(player, new BlockPos(1002, 64, 1002), 0, 3, 3, 3,
                    "sbn014-bp", "create");
            List<CustomPacketPayload> watched = byTarget.getOrDefault(watcher.getUUID(), List.of());
            helper.assertTrue(watched.stream().anyMatch(p -> p instanceof S2C_PreviewBroadcastMessage b
                    && b.origin != null), "preview broadcast sent to nearby player");

            // 登出 → 三者全清
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            helper.assertTrue(!SBUndoCooldown.isOnCooldown(myId), "logout clears cooldown");
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE), player);
            helper.assertTrue(!received.isEmpty(), "logout clears project list cache");
            watched = byTarget.getOrDefault(watcher.getUUID(), List.of());
            helper.assertTrue(watched.stream().anyMatch(p -> p instanceof S2C_PreviewBroadcastMessage b
                    && b.origin == null), "logout broadcasts preview clear");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            SBUndoCooldown.clearPlayer(myId);
            C2S_RequestProjectListMessage.forget(myId);
            ServerPreviewTracker.clear(player);
            level.players().remove(player);
            level.players().remove(watcher);
        }
        helper.succeed();
    }

    // ── t06：ForagersInsightCompat 一行开关（幂等 / 只匹配 true / C28）─────────

    @GameTest(template = "empty")
    public static void t06_foragersCompat(GameTestHelper helper) {
        Path toml = FMLPaths.CONFIGDIR.get().resolve("foragersinsight-common.toml");
        byte[] original = null;
        boolean existed = false;
        try {
            if (Files.isRegularFile(toml)) {
                existed = true;
                original = Files.readAllBytes(toml);
            }
            String sample = "[\"XP Source Toggles\"]\n"
                    + "\"Crop Harvest XP\" = true\n"
                    + "\"Other XP\" = true\n"
                    + "# keep this comment\n";
            Files.writeString(toml, sample, StandardCharsets.UTF_8);
            ForagersInsightCompat.patchCropHarvestXpOff();
            String expected = sample.replace("\"Crop Harvest XP\" = true", "\"Crop Harvest XP\" = false");
            helper.assertValueEqual(Files.readString(toml, StandardCharsets.UTF_8), expected,
                    "only the crop harvest line flipped");

            // 幂等：再调一次，文件不变
            byte[] once = Files.readAllBytes(toml);
            ForagersInsightCompat.patchCropHarvestXpOff();
            helper.assertTrue(java.util.Arrays.equals(once, Files.readAllBytes(toml)), "patch is idempotent");

            // 已是 false → 不变
            String alreadyFalse = "[\"XP Source Toggles\"]\n\"Crop Harvest XP\" = false\n";
            Files.writeString(toml, alreadyFalse, StandardCharsets.UTF_8);
            ForagersInsightCompat.patchCropHarvestXpOff();
            helper.assertValueEqual(Files.readString(toml, StandardCharsets.UTF_8), alreadyFalse,
                    "already false untouched");

            // 无文件 → 不抛
            Files.deleteIfExists(toml);
            ForagersInsightCompat.patchCropHarvestXpOff();
        } catch (java.io.IOException e) {
            helper.fail("foragersinsight test io failed: " + e.getMessage());
        } finally {
            try {
                if (existed && original != null) {
                    Files.write(toml, original);
                } else {
                    Files.deleteIfExists(toml);
                }
            } catch (java.io.IOException ignored) {
            }
        }
        helper.succeed();
    }

    // ── t07：旧物品内联 blueprintData 只读回退 ────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_inlineFallback(GameTestHelper helper) {
        List<String> files = new ArrayList<>();
        try {
            // a. 无 BLUEPRINT_ID + 内联 → 读到升级后的数据；组件逐项不变
            ItemStack inline = inlineProjectionStack();
            CompoundTag before = (CompoundTag) inline.save(helper.getLevel().registryAccess());
            assertLegacyUpgraded(helper, AbstractBlueprintItem.getBlueprintDataServer(inline));
            helper.assertTrue(before.equals(inline.save(helper.getLevel().registryAccess())),
                    "inline read is read-only");

            // b. BLUEPRINT_ID 指向不存在文件 + 内联 → 用内联
            ItemStack missing = inlineProjectionStack();
            AbstractBlueprintItem.setBlueprintId(missing, UUID.randomUUID().toString());
            CompoundTag beforeMissing = (CompoundTag) missing.save(helper.getLevel().registryAccess());
            assertLegacyUpgraded(helper, AbstractBlueprintItem.getBlueprintDataServer(missing));
            helper.assertTrue(beforeMissing.equals(missing.save(helper.getLevel().registryAccess())),
                    "missing-file fallback is read-only");

            // c. BLUEPRINT_ID 指向存在文件 + 内联 → 用文件
            BlueprintData fileData = new BlueprintData();
            fileData.name = "sbn014-file-wins";
            fileData.category = "create";
            fileData.size = new BlockPos(1, 1, 1);
            fileData.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                    Blocks.GOLD_BLOCK.defaultBlockState(), null));
            String fileId = BlueprintStorage.save(fileData).toString();
            files.add(fileId);
            ItemStack withFile = inlineProjectionStack();
            AbstractBlueprintItem.setBlueprintId(withFile, fileId);
            CompoundTag beforeFile = (CompoundTag) withFile.save(helper.getLevel().registryAccess());
            BlueprintData fromFile = AbstractBlueprintItem.getBlueprintDataServer(withFile);
            helper.assertTrue(fromFile != null, "file data loads");
            helper.assertValueEqual(fromFile.name, "sbn014-file-wins", "file wins over inline");
            helper.assertTrue(beforeFile.equals(withFile.save(helper.getLevel().registryAccess())),
                    "file read is read-only");

            // d. 内联是坏数据 → 不抛、物品不被改。
            //    e18 之前 saveMode = 99 让 deserialize 抛、readInlineData 的 catch 返回 null（K26）；
            //    越界回退 SAFE 后 deserialize 已无已知抛出入口（空 BlockStates 跳过、坏方块读成空气、空 Regions 返回空蓝图），
            //    catch 只剩防御作用。这里用「区域有尺寸但 BlockStates 为空」验证坏数据也只读、不抛。
            ItemStack bad = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            CompoundTag badRoot = SBN011Tests.legacyBlueprintRoot();
            CompoundTag badRegions = badRoot.getCompound("Regions");
            for (String rk : badRegions.getAllKeys()) {
                badRegions.getCompound(rk).putLongArray("BlockStates", new long[0]);
            }
            setInlineData(bad, badRoot);
            CompoundTag beforeBad = (CompoundTag) bad.save(helper.getLevel().registryAccess());
            BlueprintData badData = AbstractBlueprintItem.getBlueprintDataServer(bad);
            helper.assertTrue(badData == null || badData.normalBlocks.isEmpty(),
                    "bad inline data yields no blocks");
            helper.assertTrue(beforeBad.equals(bad.save(helper.getLevel().registryAccess())),
                    "bad inline read is read-only");

            // e. SpatialBlueprintItem 同样的内联物品 → null（1.20.1 只认文件）
            ItemStack spatialInline = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            setInlineData(spatialInline, SBN011Tests.legacyBlueprintRoot());
            helper.assertTrue(SpatialBlueprintItem.getBlueprintDataServer(spatialInline) == null,
                    "spatial blueprint item is file-only");
        } finally {
            for (String fid : files) BlueprintStorage.delete(fid);
        }
        helper.succeed();
    }

    // ── t08：内联旧物品端到端放置（handleServer 全路径）───────────────────────

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void t08_inlinePlace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 与 SBN012 t04 同布局：源区上空 virgin 空域，短草垫泥土防被邻居更新弹掉
        helper.setBlock(new BlockPos(1, 3, 1), Blocks.DIRT);
        helper.setBlock(new BlockPos(2, 3, 1), Blocks.DIRT);
        final BlockPos target = helper.absolutePos(new BlockPos(1, 4, 1));
        final FakePlayer placer = creativePlayer(helper);
        placer.setPos(target.getX() + 2.5, target.getY() + 2.0, target.getZ() + 2.5);
        placer.setItemInHand(InteractionHand.MAIN_HAND, inlineProjectionStack());
        BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
        BlueprintValidator.clearCooldown(placer.getUUID());
        helper.runAtTickTime(150, () -> PlaceBlueprintMessage.handleServer(
                new PlaceBlueprintMessage(target, Rotation.NONE, 0, 0, 0, true,
                        FluidDisplayMode.SHOW_ALL), placer));
        helper.runAtTickTime(450, () -> {
            try {
                // 旧 grass 落为 short_grass
                helper.assertTrue(level.getBlockState(target).is(Blocks.SHORT_GRASS),
                        "inline legacy grass placed as short_grass");
                // 箱子第 0 格是带锋利 III 且有名字的钻石剑
                ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(target.offset(1, 0, 0));
                helper.assertTrue(chest != null, "inline legacy chest placed");
                helper.assertTrue(ItemStack.isSameItemSameComponents(chest.getItem(0), namedSword(helper)),
                        "inline legacy chest sword upgraded");
                helper.assertValueEqual(chest.getItem(0).get(DataComponents.CUSTOM_NAME).getString(),
                        "Test Blade", "inline legacy sword name");
            } finally {
                clearPlacedArea(helper, target);
                BlueprintValidator.clearCooldown(placer.getUUID());
                SBUndoCooldown.clearPlayer(placer.getUUID());
                while (SBUndoData.get(level).hasHistory(placer.getUUID())) {
                    SBUndoData.get(level).pop(placer.getUUID());
                }
            }
            helper.succeed();
        });
    }

    private static void clearPlacedArea(GameTestHelper helper, BlockPos target) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x <= 1; x++) {
            level.setBlockAndUpdate(target.offset(x, 0, 0), Blocks.AIR.defaultBlockState());
        }
        AABB box = new AABB(target.getX(), target.getY(), target.getZ(),
                target.getX() + 2.0, target.getY() + 1.0, target.getZ() + 1.0);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> true)) {
            e.discard();
        }
    }

    // ── t09：SavedData 存盘顺序确定（云端验收补，K25）────────────────────────
    //
    // SBUndoData.pop 不删空队列、save 跳过空队列：活实例的表比读回副本大，HashMap 扩容不缩容，
    // 遍历顺序不同 → save → load → save 的 ListTag 顺序不同（SBN003 t05「SBUndoData roundtrip」在云端因此转红）。

    @GameTest(template = "empty")
    public static void t09_savedDataSaveOrder(GameTestHelper helper) {
        net.minecraft.core.HolderLookup.Provider p = helper.getLevel().registryAccess();
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        net.minecraft.core.BlockPos at = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));

        net.minecraft.nbt.CompoundTag empty = new net.minecraft.nbt.CompoundTag();
        empty.putInt("DataVersion", com.xioyim.spatialblueprint.util.LegacyDataFixer.current());
        com.xioyim.spatialblueprint.util.SBUndoData undo =
                com.xioyim.spatialblueprint.util.SBUndoData.load(empty, p);
        java.util.List<java.util.UUID> players = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            java.util.UUID id = java.util.UUID.randomUUID();
            players.add(id);
            undo.addRecord(id, com.xioyim.spatialblueprint.util.BlueprintPlacementHandler.snapshotRegion(
                    level, at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ()), 10);
        }
        for (int i = 0; i < 35; i++) {
            undo.pop(players.get(i));
        }
        net.minecraft.nbt.CompoundTag u1 = undo.save(new net.minecraft.nbt.CompoundTag(), p);
        net.minecraft.nbt.CompoundTag u2 = com.xioyim.spatialblueprint.util.SBUndoData.load(u1, p)
                .save(new net.minecraft.nbt.CompoundTag(), p);
        helper.assertTrue(u1.equals(u2), "SBUndoData save order stable after pops");

        com.xioyim.spatialblueprint.util.SBSafeCoordData safe =
                com.xioyim.spatialblueprint.util.SBSafeCoordData.load(new net.minecraft.nbt.CompoundTag(), p);
        for (int i = 0; i < 40; i++) safe.setEntry("bp-" + i, i % 2 == 0, i, 64, -i);
        net.minecraft.nbt.CompoundTag s1 = safe.save(new net.minecraft.nbt.CompoundTag(), p);
        net.minecraft.nbt.CompoundTag s2 = com.xioyim.spatialblueprint.util.SBSafeCoordData.load(s1, p)
                .save(new net.minecraft.nbt.CompoundTag(), p);
        helper.assertTrue(s1.equals(s2), "SBSafeCoordData save order stable");
        helper.succeed();
    }

    // ── t10：saveMode 越界回退 SAFE（e18，用户拍板）───────────────────────────
    //
    // 蓝图文件 / 旧物品内联数据里 saveMode 越界（如 99）原来让整份蓝图读不出来（e17 坐实）；
    // 现在按物品组件同口径回退 SAFE（最低特权），其余内容照常读出。文件读取与蓝图库列表两条路径都测。

    @GameTest(template = "empty")
    public static void t10_saveModeOutOfRange(GameTestHelper helper) {
        List<String> files = new ArrayList<>();
        try {
            // a. 内联：越界 saveMode 的旧蓝图照常读出，模式 SAFE、内容仍是升级后的数据
            ItemStack inline = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            CompoundTag root = SBN011Tests.legacyBlueprintRoot();
            root.getCompound("SpatialBlueprintData").putInt("saveMode", 99);
            setInlineData(inline, root);
            BlueprintData fromInline = AbstractBlueprintItem.getBlueprintDataServer(inline);
            helper.assertTrue(fromInline != null, "out-of-range saveMode inline still loads");
            helper.assertValueEqual(fromInline.saveMode, com.xioyim.spatialblueprint.util.CaptureMode.SAFE,
                    "inline saveMode falls back to SAFE");
            helper.assertTrue(fromInline.normalBlocks.size() + fromInline.dependentBlocks.size()
                    + fromInline.lateBlocks.size() > 0, "inline blocks intact");

            // b. 文件：真实蓝图落盘后把 saveMode 改成 -1 / 99 → load 与 listSummaries 都回退 SAFE
            for (int bad : new int[]{-1, 99}) {
                BlueprintData data = BlueprintData.deserialize(SBN011Tests.legacyBlueprintRoot());
                data.name = "sbn014-t10-" + bad;
                String id = BlueprintStorage.save(data).toString();
                files.add(id);
                java.nio.file.Path path = BlueprintStorage.findFile(id);
                helper.assertTrue(path != null, "saved file found");
                CompoundTag raw = com.xioyim.spatialblueprint.util.SafeNbtIo.readCompressed(path);
                raw.getCompound("SpatialBlueprintData").putInt("saveMode", bad);
                net.minecraft.nbt.NbtIo.writeCompressed(raw, path);
                BlueprintStorage.invalidateSummaryCache();

                BlueprintData loaded = BlueprintStorage.load(id);
                helper.assertTrue(loaded != null, "file with saveMode " + bad + " still loads");
                helper.assertValueEqual(loaded.saveMode, com.xioyim.spatialblueprint.util.CaptureMode.SAFE,
                        "file saveMode " + bad + " falls back to SAFE");
                boolean listed = false;
                for (BlueprintStorage.BlueprintSummary sm : BlueprintStorage.listSummaries()) {
                    if (sm.id().equals(id)) {
                        listed = true;
                        helper.assertValueEqual(sm.saveMode(), com.xioyim.spatialblueprint.util.CaptureMode.SAFE,
                                "summary saveMode " + bad + " falls back to SAFE");
                    }
                }
                helper.assertTrue(listed, "file with saveMode " + bad + " still listed");
            }
        } catch (java.io.IOException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("io: " + e.getMessage());
        } finally {
            for (String fid : files) BlueprintStorage.delete(fid);
            BlueprintStorage.invalidateSummaryCache();
        }
        helper.succeed();
    }
}
