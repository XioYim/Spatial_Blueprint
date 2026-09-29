package com.xioyim.spatialblueprint.gametest;

import com.mojang.authlib.GameProfile;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_DiscardInvalidBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintChunkMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintDataFailMessage;
import com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenVoidScreenMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.SafeNbtIo;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * SBN-005 网络骨架与蓝图传输 GameTest（t01～t07）。
 * 越界 / 恶意包是唯一允许手写处（先证明形状可达再断言被拒）。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN005Tests {

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
                new GameProfile(UUID.randomUUID(), "SBTest" + UUID.randomUUID().toString().substring(0, 4)));
    }

    private static BlueprintData sampleBlueprint(String name) {
        BlueprintData data = new BlueprintData();
        data.name = name;
        data.category = "create";
        data.creatorName = "Tester";
        data.size = new BlockPos(2, 1, 1);
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(0, 0, 0),
                Blocks.STONE.defaultBlockState(), null));
        data.normalBlocks.add(new BlueprintData.BlockEntry(new BlockPos(1, 0, 0),
                Blocks.OAK_LOG.defaultBlockState(), null));
        return data;
    }

    // ── t01 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t01_protocolVersion(GameTestHelper helper) {
        String expected = ModList.get()
                .getModContainerById(SpatialBlueprintMod.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("?");
        helper.assertValueEqual(expected, BlueprintNetwork.protocolVersion(), "protocol version = mod version");
        helper.assertFalse(expected.isEmpty() || expected.equals("?"), "mod version resolvable");
        helper.succeed();
    }

    // ── t02 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t02_codecRoundTrip(GameTestHelper helper) {
        BlueprintData data = sampleBlueprint("t02");

        byte[] full = new byte[600_000];
        new Random(42L).nextBytes(full);
        List<S2C_BlueprintChunkMessage> chunks = BlueprintNetwork.splitIntoChunks("bp-1", full);
        helper.assertTrue(chunks != null && chunks.size() == 2, "chunk sample");

        S2C_IllegalBlocksListMessage list = new S2C_IllegalBlocksListMessage(
                List.of(new S2C_IllegalBlocksListMessage.IllegalBlock(1, 2, 3, "minecraft:bedrock", "")),
                List.of(new S2C_IllegalBlocksListMessage.IllegalBlock(4, 5, 6, "minecraft:chest", "")),
                List.of(new S2C_IllegalBlocksListMessage.SpecialEntity(7, 8, 9, "mob", "minecraft:zombie", "", false)),
                List.of(new S2C_IllegalBlocksListMessage.ConversionEntry("minecraft:water", 5, 3,
                        List.of(new S2C_IllegalBlocksListMessage.TargetItem("minecraft:water_bucket", 2)))),
                List.of("minecraft:seagrass"),
                List.of(new S2C_IllegalBlocksListMessage.BoxEntity(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, true)),
                List.of(new S2C_IllegalBlocksListMessage.ProtectedChunk(3, 4)),
                100);

        // 8 个消息各造一条真实实例
        assertRoundTrip(helper, S2C_OperationResult.STREAM_CODEC,
                new S2C_OperationResult(false, "spatial_blueprint.error.test", "spatial_blueprint.error.detail.test",
                        new Object[]{"a", "b"}), "S2C_OperationResult");
        assertRoundTrip(helper, S2C_BlueprintChunkMessage.STREAM_CODEC, chunks.get(0), "S2C_BlueprintChunkMessage");
        assertRoundTrip(helper, S2C_BlueprintDataMessage.STREAM_CODEC,
                new S2C_BlueprintDataMessage("bp-1", data), "S2C_BlueprintDataMessage");
        assertRoundTrip(helper, S2C_BlueprintDataFailMessage.STREAM_CODEC,
                new S2C_BlueprintDataFailMessage("bp-1", "spatial_blueprint.error.blueprint_not_found"),
                "S2C_BlueprintDataFailMessage");
        assertRoundTrip(helper, S2C_OpenVoidScreenMessage.STREAM_CODEC,
                new S2C_OpenVoidScreenMessage(), "S2C_OpenVoidScreenMessage");
        assertRoundTrip(helper, S2C_IllegalBlocksListMessage.STREAM_CODEC, list, "S2C_IllegalBlocksListMessage");
        assertRoundTrip(helper, C2S_RequestBlueprintDataMessage.STREAM_CODEC,
                new C2S_RequestBlueprintDataMessage("bp-1"), "C2S_RequestBlueprintDataMessage");
        assertRoundTrip(helper, C2S_DiscardInvalidBlueprintMessage.STREAM_CODEC,
                new C2S_DiscardInvalidBlueprintMessage(), "C2S_DiscardInvalidBlueprintMessage");

        // TYPE id 命名规则
        helper.assertValueEqual("s2c_operation_result", S2C_OperationResult.TYPE.id().getPath(), "op result id");
        helper.assertValueEqual("s2c_blueprint_chunk_message", S2C_BlueprintChunkMessage.TYPE.id().getPath(), "chunk id");
        helper.assertValueEqual("s2c_blueprint_data_message", S2C_BlueprintDataMessage.TYPE.id().getPath(), "data id");
        helper.assertValueEqual("s2c_blueprint_data_fail_message", S2C_BlueprintDataFailMessage.TYPE.id().getPath(), "fail id");
        helper.assertValueEqual("s2c_open_void_screen_message", S2C_OpenVoidScreenMessage.TYPE.id().getPath(), "void id");
        helper.assertValueEqual("s2c_illegal_blocks_list_message", S2C_IllegalBlocksListMessage.TYPE.id().getPath(), "list id");
        helper.assertValueEqual("c2s_request_blueprint_data_message",
                C2S_RequestBlueprintDataMessage.TYPE.id().getPath(), "req id");
        helper.assertValueEqual("c2s_discard_invalid_blueprint_message",
                C2S_DiscardInvalidBlueprintMessage.TYPE.id().getPath(), "discard id");
        helper.assertValueEqual(SpatialBlueprintMod.MODID, S2C_OperationResult.TYPE.id().getNamespace(), "namespace");
        helper.succeed();
    }

    private static <T extends CustomPacketPayload> void assertRoundTrip(
            GameTestHelper helper, StreamCodec<FriendlyByteBuf, T> codec, T msg, String what) {
        byte[] once = encodeToBytes(codec, msg, helper);
        T back = decodeFromBytes(codec, once, helper);
        byte[] twice = encodeToBytes(codec, back, helper);
        helper.assertTrue(Arrays.equals(once, twice), "byte-identical roundtrip: " + what);
    }

    // ── t03 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t03_decodeGuards(GameTestHelper helper) {
        // Chunk：totalChunks = 0
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("bp", 64);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                buf.writeVarInt(0);
                S2C_BlueprintChunkMessage.decode(buf);
            } finally { buf.release(); }
        }, "totalChunks=0");

        // Chunk：totalChunks = 257
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("bp", 64);
                buf.writeVarInt(0);
                buf.writeVarInt(257);
                buf.writeVarInt(0);
                S2C_BlueprintChunkMessage.decode(buf);
            } finally { buf.release(); }
        }, "totalChunks=257");

        // Chunk：chunkIndex = totalChunks
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("bp", 64);
                buf.writeVarInt(1);
                buf.writeVarInt(1);
                buf.writeVarInt(0);
                S2C_BlueprintChunkMessage.decode(buf);
            } finally { buf.release(); }
        }, "chunkIndex=totalChunks");

        // Chunk：length = CHUNK_SIZE + 1
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("bp", 64);
                buf.writeVarInt(0);
                buf.writeVarInt(1);
                buf.writeVarInt(S2C_BlueprintChunkMessage.CHUNK_SIZE + 1);
                S2C_BlueprintChunkMessage.decode(buf);
            } finally { buf.release(); }
        }, "length=CHUNK_SIZE+1");

        // OperationResult：detailArgs 计数 17
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeBoolean(true);
                buf.writeUtf("", 256);
                buf.writeUtf("", 256);
                buf.writeVarInt(17);
                S2C_OperationResult.decode(buf);
            } finally { buf.release(); }
        }, "detailArgs=17");

        // DataMessage：length = 1_048_577
        expectDecoderException(helper, () -> {
            RegistryFriendlyByteBuf buf = newBuf(helper);
            try {
                buf.writeUtf("bp", 64);
                buf.writeInt(1_048_577);
                S2C_BlueprintDataMessage.decode(buf);
            } finally { buf.release(); }
        }, "payload length=1_048_577");
        helper.succeed();
    }

    // ── t04 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t04_splitIntoChunks(GameTestHelper helper) {
        int[] lengths = {0, 1, 512_000, 512_001, 1_500_000};
        int[] expected = {0, 1, 1, 2, 3};
        for (int i = 0; i < lengths.length; i++) {
            int len = lengths[i];
            byte[] arr = new byte[len];
            new Random(1234L + len).nextBytes(arr);
            List<S2C_BlueprintChunkMessage> chunks = BlueprintNetwork.splitIntoChunks("bp-t04", arr);
            helper.assertTrue(chunks != null, "chunks non-null len=" + len);
            helper.assertValueEqual(expected[i], chunks.size(), "chunk count len=" + len);
            helper.assertValueEqual(expected[i], BlueprintNetwork.chunkCount(len), "chunkCount len=" + len);

            // 拼回与原数组逐字相等 + 块编码字节数 < 1 MiB
            byte[] joined = new byte[len];
            int off = 0;
            for (S2C_BlueprintChunkMessage chunk : chunks) {
                byte[] enc = encodeToBytes(S2C_BlueprintChunkMessage.STREAM_CODEC, chunk, helper);
                helper.assertTrue(enc.length < 1_048_576, "chunk encoded < 1 MiB");
                RegistryFriendlyByteBuf buf = newBuf(helper);
                try {
                    buf.writeBytes(enc);
                    S2C_BlueprintChunkMessage back = S2C_BlueprintChunkMessage.decode(buf);
                    byte[] enc2 = encodeToBytes(S2C_BlueprintChunkMessage.STREAM_CODEC, back, helper);
                    helper.assertTrue(Arrays.equals(enc, enc2), "chunk byte-identical roundtrip");
                } finally { buf.release(); }
            }
            for (S2C_BlueprintChunkMessage chunk : chunks) {
                RegistryFriendlyByteBuf buf = newBuf(helper);
                try {
                    S2C_BlueprintChunkMessage.STREAM_CODEC.encode(buf, chunk);
                    buf.readUtf(64);
                    int index = buf.readVarInt();
                    int total = buf.readVarInt();
                    int dataLen = buf.readVarInt();
                    byte[] data = new byte[dataLen];
                    buf.readBytes(data);
                    helper.assertValueEqual(index, off == 0 ? 0 : off / 512_000, "chunk index order");
                    helper.assertTrue(total == expected[i], "totalChunks");
                    System.arraycopy(data, 0, joined, off, dataLen);
                    off += dataLen;
                } finally { buf.release(); }
            }
            helper.assertTrue(Arrays.equals(arr, joined), "reassembled equals original len=" + len);
        }

        // 超上限分支：块数计算（不必真分配）
        helper.assertValueEqual(257, BlueprintNetwork.chunkCount(257 * 512_000), "257 chunks over limit");
        helper.assertValueEqual(256, BlueprintNetwork.chunkCount(256 * 512_000), "256 chunks at limit");
        helper.succeed();
    }

    // ── t05 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t05_requestBlueprintData(GameTestHelper helper) {
        FakePlayer owner = fakePlayer(helper);
        BlueprintData data = sampleBlueprint("t05");
        data.ownerUuid = owner.getUUID().toString();
        UUID id = BlueprintStorage.save(data);

        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        try {
            // ① 主人请求 → 收到分片，拼回 = 原 data.serialize()
            C2S_RequestBlueprintDataMessage.handleServer(new C2S_RequestBlueprintDataMessage(id.toString()), owner);
            List<S2C_BlueprintChunkMessage> chunks = new ArrayList<>();
            for (CustomPacketPayload payload : received) {
                if (payload instanceof S2C_BlueprintChunkMessage chunk) chunks.add(chunk);
            }
            helper.assertFalse(chunks.isEmpty(), "chunks received");
            chunks.sort(Comparator.comparingInt(c -> chunkIndex(c)));
            byte[] full = reassemble(chunks);
            CompoundTag tag;
            try {
                tag = SafeNbtIo.readCompressed(new ByteArrayInputStream(full));
            } catch (java.io.IOException e) {
                helper.fail("reassembled NBT unreadable: " + e.getMessage());
                return;
            }
            helper.assertTrue(tag.equals(data.serialize()), "reassembled NBT equals original");

            // ② 同一假玩家 500ms 内再请求 → request_rate_limited
            received.clear();
            C2S_RequestBlueprintDataMessage.handleServer(new C2S_RequestBlueprintDataMessage(id.toString()), owner);
            S2C_BlueprintDataFailMessage fail = firstFail(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.request_rate_limited", failReason(fail), "rate limited");

            // ③ 另一个非 OP 假玩家请求别人的蓝图 → blueprint_not_found（不泄露存在性）
            FakePlayer other = fakePlayer(helper);
            received.clear();
            C2S_RequestBlueprintDataMessage.handleServer(new C2S_RequestBlueprintDataMessage(id.toString()), other);
            fail = firstFail(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.blueprint_not_found", failReason(fail), "not owner hidden");

            // ④ 不存在的 id → blueprint_not_found
            FakePlayer third = fakePlayer(helper);
            received.clear();
            C2S_RequestBlueprintDataMessage.handleServer(
                    new C2S_RequestBlueprintDataMessage(UUID.randomUUID().toString()), third);
            fail = firstFail(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.blueprint_not_found", failReason(fail), "missing id");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            BlueprintStorage.delete(id.toString());
        }
        helper.succeed();
    }

    private static int chunkIndex(S2C_BlueprintChunkMessage chunk) {
        // chunkIndex 没有 getter：从编码字节里读回（与网络一致）
        RegistryFriendlyByteBuf buf = RegistryFriendlyByteBuf.decorator(
                        net.minecraft.core.RegistryAccess.EMPTY, ConnectionType.OTHER)
                .apply(Unpooled.buffer());
        try {
            S2C_BlueprintChunkMessage.STREAM_CODEC.encode(buf, chunk);
            buf.readUtf(64);
            return buf.readVarInt();
        } finally {
            buf.release();
        }
    }

    private static byte[] chunkData(S2C_BlueprintChunkMessage chunk) {
        RegistryFriendlyByteBuf buf = RegistryFriendlyByteBuf.decorator(
                        net.minecraft.core.RegistryAccess.EMPTY, ConnectionType.OTHER)
                .apply(Unpooled.buffer());
        try {
            S2C_BlueprintChunkMessage.STREAM_CODEC.encode(buf, chunk);
            buf.readUtf(64);
            buf.readVarInt();
            buf.readVarInt();
            int len = buf.readVarInt();
            byte[] data = new byte[len];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    private static byte[] reassemble(List<S2C_BlueprintChunkMessage> chunks) {
        int total = 0;
        List<byte[]> parts = new ArrayList<>();
        for (S2C_BlueprintChunkMessage chunk : chunks) {
            byte[] data = chunkData(chunk);
            parts.add(data);
            total += data.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, off, part.length);
            off += part.length;
        }
        return out;
    }

    private static S2C_BlueprintDataFailMessage firstFail(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_BlueprintDataFailMessage fail) return fail;
        }
        helper.fail("expected S2C_BlueprintDataFailMessage");
        return null;
    }

    /** fail 消息的 reason 没有 getter：从编码字节读回（与网络一致）。 */
    private static String failReason(S2C_BlueprintDataFailMessage fail) {
        RegistryFriendlyByteBuf buf = RegistryFriendlyByteBuf.decorator(
                        net.minecraft.core.RegistryAccess.EMPTY, ConnectionType.OTHER)
                .apply(Unpooled.buffer());
        try {
            S2C_BlueprintDataFailMessage.STREAM_CODEC.encode(buf, fail);
            buf.readUtf(64);
            return buf.readUtf(128);
        } finally {
            buf.release();
        }
    }

    // ── t06 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t06_discardInvalid(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);

        BlueprintData placed = sampleBlueprint("t06-placed");
        placed.placed = true;
        UUID placedId = BlueprintStorage.save(placed);
        BlueprintData normal = sampleBlueprint("t06-normal");
        UUID normalId = BlueprintStorage.save(normal);

        try {
            ItemStack stack = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            stack.set(com.xioyim.spatialblueprint.item.SpatialBlueprintComponents.BLUEPRINT_ID.get(), placedId.toString());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            C2S_DiscardInvalidBlueprintMessage.handleServer(new C2S_DiscardInvalidBlueprintMessage(), player);
            helper.assertTrue(player.getMainHandItem().getItem() == SpatialBlueprintItems.BLANK_BLUEPRINT.get(),
                    "placed blueprint discarded to blank");

            ItemStack stack2 = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            stack2.set(com.xioyim.spatialblueprint.item.SpatialBlueprintComponents.BLUEPRINT_ID.get(), normalId.toString());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack2);
            C2S_DiscardInvalidBlueprintMessage.handleServer(new C2S_DiscardInvalidBlueprintMessage(), player);
            helper.assertTrue(player.getMainHandItem().getItem() == SpatialBlueprintItems.SPATIAL_BLUEPRINT.get()
                            && normalId.toString().equals(SpatialBlueprintItem.getBlueprintId(player.getMainHandItem())),
                    "not-placed blueprint untouched");
        } finally {
            BlueprintStorage.delete(placedId.toString());
            BlueprintStorage.delete(normalId.toString());
        }
        helper.succeed();
    }

    // ── t07 ───────────────────────────────────────────────────────────────────

    @GameTest(template = "empty")
    public static void t07_itemStubsWired(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper);
        List<CustomPacketPayload> received = new ArrayList<>();
        BlueprintNetwork.fakePlayerSink = (p, payload) -> received.add(payload);
        UUID voidId = null;
        try {
            // ① OrphanBlueprintGuard：指向不存在的文件 → 变空白 + OperationResult
            ItemStack orphan = new ItemStack(SpatialBlueprintItems.SPATIAL_BLUEPRINT.get());
            orphan.set(com.xioyim.spatialblueprint.item.SpatialBlueprintComponents.BLUEPRINT_ID.get(), UUID.randomUUID().toString());
            player.setItemInHand(InteractionHand.MAIN_HAND, orphan);
            received.clear();
            boolean discarded = com.xioyim.spatialblueprint.util.OrphanBlueprintGuard
                    .discardIfFileMissing(player, InteractionHand.MAIN_HAND);
            helper.assertTrue(discarded, "orphan discarded");
            helper.assertTrue(player.getMainHandItem().getItem() == SpatialBlueprintItems.BLANK_BLUEPRINT.get(),
                    "orphan -> blank");
            S2C_OperationResult result = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.blueprint_file_missing", result.messageKey,
                    "orphan message key");

            // ② VoidDimensional：文件存在 + world_uuid 当前 + 主人 = 该假玩家 → OpenVoidScreen
            ServerLevel level = helper.getLevel();
            BlueprintData data = sampleBlueprint("t07-void");
            data.ownerUuid = player.getUUID().toString();
            data.worldUuid = WorldIdentity.get(level).uuid().toString();
            voidId = BlueprintStorage.save(data);

            ItemStack voidStack = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
            voidStack.set(com.xioyim.spatialblueprint.item.SpatialBlueprintComponents.BLUEPRINT_ID.get(),
                    voidId.toString());
            voidStack.set(com.xioyim.spatialblueprint.item.SpatialBlueprintComponents.WORLD_UUID.get(),
                    data.worldUuid);
            player.setItemInHand(InteractionHand.MAIN_HAND, voidStack);
            received.clear();
            voidStack.getItem().use(level, player, InteractionHand.MAIN_HAND);
            boolean gotOpen = received.stream().anyMatch(p -> p instanceof S2C_OpenVoidScreenMessage);
            helper.assertTrue(gotOpen, "void screen opened for valid file");

            // ③ 文件缺失 → 变空白 + OperationResult(void_blueprint_orphaned)
            ItemStack missing = new ItemStack(SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get());
            missing.set(com.xioyim.spatialblueprint.item.SpatialBlueprintComponents.BLUEPRINT_ID.get(),
                    UUID.randomUUID().toString());
            player.setItemInHand(InteractionHand.MAIN_HAND, missing);
            received.clear();
            missing.getItem().use(level, player, InteractionHand.MAIN_HAND);
            helper.assertTrue(player.getMainHandItem().getItem() == SpatialBlueprintItems.BLANK_BLUEPRINT.get(),
                    "missing void -> blank");
            S2C_OperationResult orphaned = firstOperationResult(helper, received);
            helper.assertValueEqual("spatial_blueprint.error.void_blueprint_orphaned", orphaned.messageKey,
                    "void orphaned message key");
        } finally {
            BlueprintNetwork.fakePlayerSink = (p, payload) -> {};
            if (voidId != null) BlueprintStorage.delete(voidId.toString());
        }
        helper.succeed();
    }

    private static S2C_OperationResult firstOperationResult(GameTestHelper helper, List<CustomPacketPayload> received) {
        for (CustomPacketPayload payload : received) {
            if (payload instanceof S2C_OperationResult result) return result;
        }
        helper.fail("expected S2C_OperationResult");
        return null;
    }
}
