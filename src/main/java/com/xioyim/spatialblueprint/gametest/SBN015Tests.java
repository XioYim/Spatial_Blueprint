package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.client.ClientCaptureCooldown;
import com.xioyim.spatialblueprint.client.ClientConfigMirror;
import com.xioyim.spatialblueprint.client.ClientConfigPermCache;
import com.xioyim.spatialblueprint.client.ClientLibraryCache;
import com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache;
import com.xioyim.spatialblueprint.client.ClientOtherPreviewCache;
import com.xioyim.spatialblueprint.client.ClientProjectEntriesCache;
import com.xioyim.spatialblueprint.client.ClientProjectListCache;
import com.xioyim.spatialblueprint.client.ClientProjectStatusCache;
import com.xioyim.spatialblueprint.client.ClientTeleportPermCache;
import com.xioyim.spatialblueprint.client.ClientWorldIdentity;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.OtherPlayerDownloadListener;
import com.xioyim.spatialblueprint.client.RenderBlockParticleCache;
import com.xioyim.spatialblueprint.client.SpatialBlueprintClient;
import com.xioyim.spatialblueprint.config.ConfigRegistry;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestConfigMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectEntriesMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectStatusMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintChunkMessage;
import com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage;
import com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SBN-015 P5a 客户端入口与缓存 GameTest（t01～t07）。
 * 只测纯逻辑缓存（不引用 net.minecraft.client 的类）；引用客户端类的四个类不进 GameTest（见报告）。
 * 断线清理直接调入口类 clearAllCaches()（不 post 客户端事件）。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN015Tests {

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private static FakePlayer fakePlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "SB15" + UUID.randomUUID().toString().substring(0, 4)));
    }

    private static void grantOp(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().getOps().add(
                new ServerOpListEntry(player.getGameProfile(), 4, false));
    }

    private static void revokeOp(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
    }

    private static byte[] toCompressedBytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static BlueprintData sampleData(String name) {
        BlueprintData data = new BlueprintData();
        data.name = name;
        data.category = "create";
        data.size = new BlockPos(2, 1, 1);
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                Blocks.GOLD_BLOCK.defaultBlockState(), null));
        return data;
    }

    /** 读 S2C_BlueprintChunkMessage 的私有字段（消息类无 getter；只加读取用，不改生产类）。 */
    private static String chunkId(S2C_BlueprintChunkMessage msg) {
        return (String) readField(msg, "blueprintId");
    }

    private static int chunkInt(S2C_BlueprintChunkMessage msg, String name) {
        return (int) readField(msg, name);
    }

    private static byte[] chunkData(S2C_BlueprintChunkMessage msg) {
        return (byte[]) readField(msg, "data");
    }

    private static Object readField(Object msg, String name) {
        try {
            java.lang.reflect.Field f = S2C_BlueprintChunkMessage.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(msg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean invokeBoolean(GameTestHelper helper, Class<?> owner, String method, ItemStack stack) {
        try {
            java.lang.reflect.Method m = owner.getDeclaredMethod(method, ItemStack.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, stack);
        } catch (ReflectiveOperationException e) {
            helper.fail("reflection " + method + " failed: " + e);
            return false;
        }
    }

    private static void setInlineData(ItemStack stack, CompoundTag blueprintRoot) {
        CompoundTag custom = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        custom.put("blueprintData", blueprintRoot);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
    }

    private static S2C_ProjectStatusMessage statusFound(String pid, String dimension, BlockPos authorSize) {
        return new S2C_ProjectStatusMessage(true, pid, "sbn015-status", "", false, List.of(),
                true, BlockPos.ZERO, authorSize, BlockPos.ZERO, authorSize,
                false, BlockPos.ZERO, BlockPos.ZERO, true, dimension);
    }

    private static boolean cacheHasGrass(BlueprintData data) {
        for (BlueprintData.BlockEntry e : data.allBlocks()) {
            if (e.state.is(Blocks.SHORT_GRASS)) return true;
        }
        return false;
    }

    // ── t01：ClientBlueprintCache 分块重组 ───────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_blueprintCache(GameTestHelper helper) {
        try {
            // a. 真实 BlueprintData → 与服务端 sendBlueprintTo 相同的序列化 / 切块（splitIntoChunks）
            BlueprintData data = sampleData("sbn015-t01");
            CompoundTag root = data.serialize();
            String id = UUID.randomUUID().toString();
            List<S2C_BlueprintChunkMessage> chunks =
                    BlueprintNetwork.splitIntoChunks(id, toCompressedBytes(root));
            helper.assertTrue(chunks != null && !chunks.isEmpty(), "server chunking produces chunks");
            // 乱序喂入（倒序）
            for (int i = chunks.size() - 1; i >= 0; i--) {
                S2C_BlueprintChunkMessage c = chunks.get(i);
                ClientBlueprintCache.receiveChunk(chunkId(c), chunkInt(c, "chunkIndex"),
                        chunkInt(c, "totalChunks"), chunkData(c));
            }
            BlueprintData back = ClientBlueprintCache.get(id);
            helper.assertTrue(back != null, "single-chunk transfer reassembled");
            helper.assertTrue(back.serialize().equals(root), "reassembled data byte-identical");

            // b. 多块乱序（同一份真实字节按协议切成 2 块）：先第 2 块再第 1 块
            String id2 = UUID.randomUUID().toString();
            byte[] full = toCompressedBytes(root);
            int half = full.length / 2;
            byte[] part1 = Arrays.copyOfRange(full, 0, half);
            byte[] part2 = Arrays.copyOfRange(full, half, full.length);
            ClientBlueprintCache.receiveChunk(id2, 1, 2, part2);
            ClientBlueprintCache.receiveChunk(id2, 0, 2, part1);
            BlueprintData back2 = ClientBlueprintCache.get(id2);
            helper.assertTrue(back2 != null, "two-chunk transfer reassembled");
            helper.assertTrue(back2.serialize().equals(root), "two-chunk data byte-identical");

            // c. 缺块 → 不挂缓存（等集齐），进度 50%
            String partial = UUID.randomUUID().toString();
            ClientBlueprintCache.receiveChunk(partial, 0, 2, part1);
            helper.assertTrue(ClientBlueprintCache.get(partial) == null, "incomplete transfer not cached");
            helper.assertValueEqual(ClientBlueprintCache.getDownloadProgress(partial), 50, "progress 50%");

            // d. 超块数 / 越界下标 → 丢弃（照 1.20.1 receiveChunk 的守卫）
            String overCount = UUID.randomUUID().toString();
            ClientBlueprintCache.receiveChunk(overCount, 0,
                    S2C_BlueprintChunkMessage.MAX_TOTAL_CHUNKS + 1, part1);
            helper.assertTrue(ClientBlueprintCache.get(overCount) == null, "too many chunks rejected");
            String overIndex = UUID.randomUUID().toString();
            ClientBlueprintCache.receiveChunk(overIndex, 5, 2, part1);
            ClientBlueprintCache.receiveChunk(overIndex, -1, 2, part1);
            helper.assertTrue(ClientBlueprintCache.get(overIndex) == null, "out-of-range index rejected");

            // e. 失败标记（S2C_BlueprintDataFailMessage → markFailed 语义）
            String failed = UUID.randomUUID().toString();
            ClientBlueprintCache.markFailed(failed, "spatial_blueprint.error.download_failed");
            helper.assertTrue(ClientBlueprintCache.isFailed(failed), "markFailed records failure");
            helper.assertValueEqual(ClientBlueprintCache.getFailReason(failed),
                    "spatial_blueprint.error.download_failed", "fail reason kept");
            ClientBlueprintCache.clearFailed(failed);
            helper.assertTrue(!ClientBlueprintCache.isFailed(failed), "clearFailed retryable");

            // f. clearAll → 全空
            ClientBlueprintCache.clearAll();
            helper.assertTrue(ClientBlueprintCache.get(id) == null
                    && ClientBlueprintCache.get(id2) == null, "clearAll empties cache");
        } finally {
            ClientBlueprintCache.clearAll();
        }
        helper.succeed();
    }

    // ── t02：OtherPlayerDownloadListener → 他人预览挂完整数据 ─────────────────

    @GameTest(template = "empty")
    public static void t02_otherDownload(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        String bpId = "sbn015-other-" + UUID.randomUUID();
        try {
            // 先有一条他人预览条目（广播到达）
            ClientOtherPreviewCache.receive(new S2C_PreviewBroadcastMessage(owner, "SB15Other", bpId,
                    "create", new BlockPos(1, 64, 1), 0, 3, 3, 3));
            helper.assertValueEqual(ClientOtherPreviewCache.all().size(), 1, "preview entry created");

            // 登记下载 → 数据到达 → 挂上完整数据
            OtherPlayerDownloadListener.register(bpId, owner);
            BlueprintData data = sampleData("sbn015-other");
            ClientBlueprintCache.put(bpId, data);
            ClientOtherPreviewCache.Entry entry = ClientOtherPreviewCache.all().iterator().next();
            helper.assertTrue(entry.fullData == data, "registered id attaches full data");

            // 未登记的 id 数据到达 → 不挂
            ClientOtherPreviewCache.Entry before = ClientOtherPreviewCache.all().iterator().next();
            ClientBlueprintCache.put(UUID.randomUUID().toString(), sampleData("sbn015-unregistered"));
            helper.assertTrue(ClientOtherPreviewCache.all().iterator().next() == before
                    && before.fullData == data, "unregistered id does not attach");

            // origin=null 广播 → 条目移除
            ClientOtherPreviewCache.receive(new S2C_PreviewBroadcastMessage(owner, "SB15Other", bpId,
                    "create", null, 0, 0, 0, 0));
            helper.assertTrue(ClientOtherPreviewCache.isEmpty(), "null-origin broadcast removes entry");
        } finally {
            ClientOtherPreviewCache.clear();
            ClientBlueprintCache.clearAll();
        }
        helper.succeed();
    }

    // ── t03：项目三缓存（真实服务端包 → 缓存读回）────────────────────────────

    @GameTest(template = "empty")
    public static void t03_projectCaches(GameTestHelper helper) {
        FakePlayer owner = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        UUID projectId = UUID.randomUUID();
        ProjectData project = new ProjectData();
        project.name = "sbn015-proj";
        project.kind = ProjectData.Kind.PRIVATE;
        project.stage = ProjectData.Stage.PREPARING;
        project.ownerUuid = owner.getUUID().toString();
        project.ownerName = owner.getScoreboardName();
        project.origin = new BlockPos(10, 64, 10);
        project.size = new BlockPos(4, 1, 4);
        project.anchor = project.origin;
        ProjectStorage.saveProject(projectId, project);
        try {
            // 1) 列表：C2S 真实执行 → S2C_ProjectListMessage → 缓存
            received.clear();
            C2S_RequestProjectListMessage.handleServer(
                    new C2S_RequestProjectListMessage(C2S_RequestProjectListMessage.TAB_PRIVATE), owner);
            S2C_ProjectListMessage listMsg = null;
            for (CustomPacketPayload p : received) {
                if (p instanceof S2C_ProjectListMessage m) listMsg = m;
            }
            helper.assertTrue(listMsg != null, "server produced project list");
            ClientProjectListCache.onReceive(listMsg);
            List<S2C_ProjectListMessage.ProjectSummary> cachedList =
                    ClientProjectListCache.get(C2S_RequestProjectListMessage.TAB_PRIVATE);
            helper.assertTrue(cachedList == listMsg.projects, "list cached verbatim");
            helper.assertTrue(cachedList.stream().anyMatch(s -> s.projectId().equals(projectId.toString())
                    && s.name().equals("sbn015-proj")), "cached list contains our project");

            // 2) 条目：C2S 真实执行 → S2C_ProjectEntriesMessage → 缓存
            received.clear();
            C2S_RequestProjectEntriesMessage.handleServer(
                    new C2S_RequestProjectEntriesMessage(projectId.toString()), owner);
            S2C_ProjectEntriesMessage entriesMsg = null;
            for (CustomPacketPayload p : received) {
                if (p instanceof S2C_ProjectEntriesMessage m) entriesMsg = m;
            }
            helper.assertTrue(entriesMsg != null && entriesMsg.found, "server produced entries");
            ClientProjectEntriesCache.onReceive(entriesMsg);
            helper.assertTrue(ClientProjectEntriesCache.getFor(projectId.toString()) == entriesMsg,
                    "entries cached for matching project");
            helper.assertTrue(ClientProjectEntriesCache.getFor("other-project") == null,
                    "other project not served from cache");

            // 3) 状态：真实主手蓝图 + C2S 真实执行 → S2C_ProjectStatusMessage → 缓存
            ItemStack stack = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(stack, projectId);
            owner.setItemInHand(InteractionHand.MAIN_HAND, stack);
            received.clear();
            C2S_RequestProjectStatusMessage.handleServer(new C2S_RequestProjectStatusMessage(), owner);
            S2C_ProjectStatusMessage statusMsg = null;
            for (CustomPacketPayload p : received) {
                if (p instanceof S2C_ProjectStatusMessage m) statusMsg = m;
            }
            helper.assertTrue(statusMsg != null && statusMsg.found, "server produced status");
            helper.assertValueEqual(statusMsg.projectId, projectId.toString(), "status project id");
            helper.assertValueEqual(statusMsg.authorSize, project.size, "status author size");
            ClientProjectStatusCache.onReceive(statusMsg);
            helper.assertTrue(ClientProjectStatusCache.get() == statusMsg, "status cached");
            helper.assertTrue(ClientProjectStatusCache.receivedAtMillis() > 0, "received timestamp set");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            ProjectStorage.deleteProject(projectId);
            ClientProjectListCache.clear();
            ClientProjectEntriesCache.clear();
            ClientProjectStatusCache.clear();
        }
        helper.succeed();
    }

    // ── t04：小缓存（身份 / 传送权限 / 捕获冷却 / 配置镜像）──────────────────

    @GameTest(template = "empty")
    public static void t04_smallCaches(GameTestHelper helper) {
        FakePlayer op = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        boolean opped = false;
        try {
            // 存档身份
            ClientWorldIdentity.set("uuid-1", "name-1");
            helper.assertValueEqual(ClientWorldIdentity.uuid(), "uuid-1", "identity uuid");
            helper.assertValueEqual(ClientWorldIdentity.name(), "name-1", "identity name");
            helper.assertTrue(ClientWorldIdentity.belongsToCurrentWorld("uuid-1"), "same uuid belongs");
            helper.assertTrue(!ClientWorldIdentity.belongsToCurrentWorld("uuid-2"), "other uuid rejected");
            helper.assertTrue(ClientWorldIdentity.belongsToCurrentWorld(""), "unknown uuid tolerated");
            ClientWorldIdentity.clear();
            helper.assertTrue(ClientWorldIdentity.belongsToCurrentWorld("uuid-2"),
                    "after clear, unknown tolerated");

            // 传送权限 + 冷却
            helper.assertTrue(!ClientTeleportPermCache.canTeleport(), "teleport perm default denied");
            ClientTeleportPermCache.set(true);
            helper.assertTrue(ClientTeleportPermCache.canTeleport(), "teleport perm set");
            ClientTeleportPermCache.markTeleportRequested();
            helper.assertTrue(ClientTeleportPermCache.cooldownRemainingMs() > 0, "tp cooldown started");
            ClientTeleportPermCache.clear();
            helper.assertTrue(!ClientTeleportPermCache.canTeleport()
                    && ClientTeleportPermCache.cooldownRemainingMs() == 0, "teleport cache cleared");

            // 捕获冷却
            helper.assertTrue(!ClientCaptureCooldown.onCooldown(), "cooldown default off");
            ClientCaptureCooldown.start(1);
            helper.assertTrue(ClientCaptureCooldown.onCooldown(), "cooldown started");
            helper.assertValueEqual(ClientCaptureCooldown.remainingSeconds(), 1, "remaining seconds");
            ClientCaptureCooldown.start(0);
            helper.assertTrue(!ClientCaptureCooldown.onCooldown(), "zero-second cooldown expires immediately");
            ClientCaptureCooldown.clear();

            // 配置镜像（真实 S2C_ConfigValuesMessage：OP 经 C2S 请求截获）
            grantOp(helper, op);
            opped = true;
            received.clear();
            C2S_RequestConfigMessage.handleServer(new C2S_RequestConfigMessage(), op);
            S2C_ConfigValuesMessage values = null;
            for (CustomPacketPayload p : received) {
                if (p instanceof S2C_ConfigValuesMessage m) values = m;
            }
            helper.assertTrue(values != null && !values.getInts().isEmpty(), "server produced config values");
            helper.assertTrue(!ClientConfigMirror.hasSnapshot(), "mirror empty before snapshot");
            ClientConfigMirror.onReceive(values.getInts(), values.getBools(), values.getLists());
            helper.assertTrue(ClientConfigMirror.hasSnapshot(), "mirror has snapshot");
            helper.assertValueEqual(ClientConfigMirror.snapshotVersion(), 1, "snapshot version");
            String volumeId = ConfigRegistry.idOf(BlueprintConfig.MAX_VOLUME_SPATIAL);
            helper.assertValueEqual(ClientConfigMirror.getInt(volumeId, -1),
                    BlueprintConfig.MAX_VOLUME_SPATIAL.get(), "mirror matches local disk value");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (opped) revokeOp(helper, op);
            ClientWorldIdentity.clear();
            ClientTeleportPermCache.clear();
            ClientCaptureCooldown.clear();
            ClientConfigMirror.clear();
        }
        helper.succeed();
    }

    // ── t05：ConfigAccess 按完整路径读写（C16）与 ModConfigSpec 校验语义 ──────

    @GameTest(template = "empty")
    public static void t05_configAccess(GameTestHelper helper) {
        ModConfigSpec.IntValue intVal = BlueprintConfig.MAX_VOLUME_SPATIAL;
        ModConfigSpec.BooleanValue boolVal = BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA;
        String intId = ConfigRegistry.idOf(intVal);
        String boolId = ConfigRegistry.idOf(boolVal);
        int intBefore = intVal.get();
        boolean boolBefore = boolVal.get();
        try {
            ClientConfigMirror.clear();
            helper.assertValueEqual(intVal.get(), intBefore, "config baseline unchanged");

            // 没快照 → 退回本地值（get 按完整路径取，不抛）
            helper.assertTrue(!ConfigAccess.hasSnapshot(), "no snapshot yet");
            helper.assertValueEqual(ConfigAccess.get(intVal), intBefore, "fallback to local int");
            helper.assertValueEqual(ConfigAccess.get(boolVal), boolBefore, "fallback to local bool");
            helper.assertValueEqual(ConfigAccess.defaultValue(intVal),
                    ConfigRegistry.defaultInt(intVal), "spec default via registry");

            // set → pending 优先于快照与本地的读取
            ConfigAccess.set(intVal, intBefore + 123);
            helper.assertValueEqual(ConfigAccess.get(intVal), intBefore + 123, "pending int wins");
            ConfigAccess.set(boolVal, !boolBefore);
            helper.assertValueEqual(ConfigAccess.get(boolVal), !boolBefore, "pending bool wins");
            helper.assertTrue(ConfigAccess.get(intVal) != intVal.get(), "pending does not change spec");

            // 快照到达（以稳定 ID 为键）→ 快照优先，pending 已丢
            ClientConfigMirror.clearPending();
            ClientConfigMirror.onReceive(
                    Map.of(intId, intBefore + 7),
                    Map.of(boolId, boolBefore),
                    Map.of());
            helper.assertValueEqual(ConfigAccess.get(intVal), intBefore + 7, "snapshot int wins");
            helper.assertValueEqual(intVal.get(), intBefore, "snapshot does not write local spec");

            // 越界值按 ModConfigSpec 校验语义：applyInt 拒绝并保留原值
            Integer rejected = ConfigRegistry.applyInt(intId, Integer.MAX_VALUE);
            helper.assertValueEqual(rejected, intBefore, "out-of-range rejected keeps current");
            helper.assertValueEqual(intVal.get(), intBefore, "spec value unchanged after reject");
            // 合法值可写入
            Integer applied = ConfigRegistry.applyInt(intId, intBefore + 5);
            helper.assertValueEqual(applied, intBefore + 5, "in-range value applied");
        } finally {
            ClientConfigMirror.clear();
            intVal.set(intBefore);
            boolVal.set(boolBefore);
        }
        helper.succeed();
    }

    // ── t06：物品客户端读缓存 ────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_itemClientRead(GameTestHelper helper) {
        try {
            // a. 投影蓝图带 id + 缓存里有 → 缓存（Abstract 走缓存）
            String bpId = UUID.randomUUID().toString();
            BlueprintData cached = sampleData("sbn015-cache");
            ClientBlueprintCache.put(bpId, cached);
            ItemStack withCache = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(withCache, bpId);
            helper.assertTrue(AbstractBlueprintItem.getBlueprintDataClient(withCache) == cached,
                    "projection reads downloaded cache");

            // b. 无 id + 内联 → 内联数据（只读，归档样本升级）
            ItemStack inline = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            setInlineData(inline, SBN011Tests.legacyBlueprintRoot());
            CompoundTag before = (CompoundTag) inline.save(helper.getLevel().registryAccess());
            BlueprintData inlineData = AbstractBlueprintItem.getBlueprintDataClient(inline);
            helper.assertTrue(inlineData != null && cacheHasGrass(inlineData), "inline fallback upgraded");
            helper.assertTrue(before.equals(inline.save(helper.getLevel().registryAccess())),
                    "inline client read is read-only");

            // c. 空间蓝图带 id 但缓存没有 → null（只认缓存，无内联回退）
            ItemStack spatial = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            AbstractBlueprintItem.setBlueprintId(spatial, UUID.randomUUID().toString());
            helper.assertTrue(SpatialBlueprintItem.getBlueprintDataClient(spatial) == null,
                    "spatial blueprint misses cache -> null");

            // d. ProjectBlueprintItem 三处：缓存无 / 有（found=false / found=true）
            String pid = UUID.randomUUID().toString();
            ItemStack proj = new ItemStack(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT.get());
            ProjectBlueprintItem.setProjectId(proj, UUID.fromString(pid));
            helper.assertTrue(!invokeBoolean(helper, ProjectBlueprintItem.class, "isProjectMissing", proj),
                    "no cache -> not missing");
            helper.assertTrue(ProjectBlueprintItem.knownProjectDimension(proj) == null,
                    "no cache -> dimension unknown");
            helper.assertTrue(!invokeBoolean(helper, ProjectBlueprintItem.class, "canEnterQuickPlan", proj),
                    "no cache -> quick plan denied");

            ClientProjectStatusCache.onReceive(S2C_ProjectStatusMessage.notFound(pid));
            helper.assertTrue(invokeBoolean(helper, ProjectBlueprintItem.class, "isProjectMissing", proj),
                    "found=false -> project missing");
            helper.assertValueEqual(ClientProjectStatusCache.get().projectId, pid, "cache pid matches");

            ClientProjectStatusCache.onReceive(
                    statusFound(pid, "minecraft:overworld", new BlockPos(4, 1, 4)));
            helper.assertValueEqual(ProjectBlueprintItem.knownProjectDimension(proj), "minecraft:overworld",
                    "found=true -> dimension from cache");
            helper.assertTrue(invokeBoolean(helper, ProjectBlueprintItem.class, "canEnterQuickPlan", proj),
                    "found=true with author size -> quick plan allowed");
        } finally {
            ClientBlueprintCache.clearAll();
            ClientProjectStatusCache.clear();
        }
        helper.succeed();
    }

    // ── t07：断线清缓存（入口 clearAllCaches 集中清理）───────────────────────

    @GameTest(template = "empty")
    public static void t07_logoutClears(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        try {
            // 逐个缓存填数据
            ClientBlueprintCache.put("sbn015-x", sampleData("sbn015-x"));
            ClientCaptureCooldown.start(5);
            ClientConfigMirror.onReceive(Map.of("k", 1), Map.of("b", true), Map.of("l", List.of("a")));
            ClientConfigPermCache.set(true);
            ClientLibraryCache.onReceive(List.of());
            ClientMaterialSpaceCache.onReceive(new S2C_MaterialSpaceSyncMessage(
                    Map.of("minecraft:stone", 3), Map.of(), false, "", "", 3, 100));
            ClientOtherPreviewCache.receive(new S2C_PreviewBroadcastMessage(owner, "SB15Other",
                    "sbn015-preview", "create", new BlockPos(1, 64, 1), 0, 1, 1, 1));
            ClientProjectEntriesCache.onReceive(new S2C_ProjectEntriesMessage(
                    "p-entries", true, "n", BlockPos.ZERO, BlockPos.ZERO, List.of()));
            ClientProjectListCache.onReceive(new S2C_ProjectListMessage(
                    C2S_RequestProjectListMessage.TAB_PRIVATE, List.of()));
            ClientProjectStatusCache.onReceive(S2C_ProjectStatusMessage.notFound("p-status"));
            ClientTeleportPermCache.set(true);
            ClientWorldIdentity.set("u", "n");
            // SBN-016 第 13 个缓存：破坏粒子缓存（RenderBlock.onRemove 写入）
            RenderBlockParticleCache.put(new BlockPos(2, 64, 2), Blocks.STONE.defaultBlockState());

            // 前置：确实填上了
            helper.assertTrue(ClientBlueprintCache.get("sbn015-x") != null, "cache pre-filled");
            helper.assertTrue(ClientCaptureCooldown.onCooldown(), "cooldown pre-filled");
            helper.assertTrue(ClientConfigMirror.hasSnapshot(), "mirror pre-filled");
            helper.assertTrue(ClientConfigPermCache.canEdit(), "config perm pre-filled");
            helper.assertTrue(ClientLibraryCache.get() != null, "library pre-filled");
            helper.assertValueEqual(ClientMaterialSpaceCache.total(), 3, "material space pre-filled");
            helper.assertTrue(!ClientOtherPreviewCache.isEmpty(), "preview pre-filled");
            helper.assertTrue(ClientProjectEntriesCache.getFor("p-entries") != null, "entries pre-filled");
            helper.assertTrue(ClientProjectListCache.get(C2S_RequestProjectListMessage.TAB_PRIVATE) != null,
                    "list pre-filled");
            helper.assertTrue(ClientProjectStatusCache.get() != null, "status pre-filled");
            helper.assertTrue(ClientTeleportPermCache.canTeleport(), "tp perm pre-filled");
            helper.assertValueEqual(ClientWorldIdentity.uuid(), "u", "identity pre-filled");
            // 粒子缓存只给 put / remove / clear 三个口：取出验证后放回（不破坏后续 clear 断言）
            BlockPos particlePos = new BlockPos(2, 64, 2);
            net.minecraft.world.level.block.state.BlockState pre =
                    RenderBlockParticleCache.remove(particlePos);
            helper.assertTrue(pre != null, "particle cache pre-filled");
            RenderBlockParticleCache.put(particlePos, pre);

            // 断线监听实际调用的那个方法
            SpatialBlueprintClient.clearAllCaches();

            // 全部为空
            helper.assertTrue(ClientBlueprintCache.get("sbn015-x") == null, "blueprint cache cleared");
            helper.assertTrue(!ClientCaptureCooldown.onCooldown(), "cooldown cleared");
            helper.assertTrue(!ClientConfigMirror.hasSnapshot()
                    && !ClientConfigMirror.hasPending(), "config mirror cleared");
            helper.assertTrue(!ClientConfigPermCache.canEdit(), "config perm cleared");
            helper.assertTrue(ClientLibraryCache.get() == null, "library cleared");
            helper.assertValueEqual(ClientMaterialSpaceCache.total(), 0, "material space cleared");
            helper.assertTrue(ClientOtherPreviewCache.isEmpty(), "preview cleared");
            helper.assertTrue(ClientProjectEntriesCache.getFor("p-entries") == null, "entries cleared");
            helper.assertTrue(ClientProjectListCache.get(C2S_RequestProjectListMessage.TAB_PRIVATE) == null,
                    "list cleared");
            helper.assertTrue(ClientProjectStatusCache.get() == null, "status cleared");
            helper.assertTrue(!ClientTeleportPermCache.canTeleport(), "tp perm cleared");
            helper.assertValueEqual(ClientWorldIdentity.uuid(), "", "identity cleared");
            helper.assertTrue(RenderBlockParticleCache.remove(particlePos) == null, "particle cache cleared");
        } finally {
            SpatialBlueprintClient.clearAllCaches();
        }
        helper.succeed();
    }
}
