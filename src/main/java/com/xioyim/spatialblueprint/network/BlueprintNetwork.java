package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenVoidScreenMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 网络骨架（SBN-005 / P3a）。
 *
 * <p><b>1.20.1 消息 → 1.21 payload 的改写口径</b>（SBN-006 / 007 沿用）：</p>
 * <ul>
 *   <li>类名 / 包名 / 字段 / 构造器不改；类加 {@code implements CustomPacketPayload}，
 *       带 {@code TYPE} 与 {@code type()}；TYPE id = 类名小写 snake_case。</li>
 *   <li>1.20.1 的 static encode / decode 原样保留，
 *       {@code STREAM_CODEC = StreamCodec.ofMember(X::encode, X::decode)}；decode 的上限校验一个不删。</li>
 *   <li>C2S：{@code handle(msg, IPayloadContext ctx)} 里 {@code ctx.player() instanceof ServerPlayer} →
 *       调 public static 的 handleServer（测试可直接调）；去掉 enqueueWork / setPacketHandled / getSender 判空。</li>
 *   <li>S2C：payload 类里<b>不许</b>出现任何客户端类引用（1.21 不按 @OnlyIn 剥离代码，专服加载即崩）；
 *       客户端处理体整体换成一条 SB-TODO（P5）占位。</li>
 *   <li>发包一律走本类的 {@link #sendToPlayer} / {@link #sendToServer}，不直接调 PacketDistributor。</li>
 * </ul>
 *
 * <p>1.21 按 payload ID 区分消息，注册顺序无关；但增删 payload / 改 wire format 仍须 bump
 * mod_version（D4：协议版本 = mod 版本，版本不一致会被 NeoForge 握手拒绝）。</p>
 */
public class BlueprintNetwork {

    /** D4：协议版本 = mod 版本。 */
    public static String protocolVersion() {
        return ModList.get()
                .getModContainerById(SpatialBlueprintMod.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("1.0.0");
    }

    /**
     * 假玩家收包口子：FakePlayer 没有真连接，别的 mod 的假玩家触发我们的逻辑时丢弃是正确行为；
     * GameTest 借这个口子收回包断言（默认丢弃）。
     */
    public static volatile BiConsumer<ServerPlayer, CustomPacketPayload> fakePlayerSink = (player, payload) -> {};

    /** 发包统一封装：假玩家走 sink，真玩家走网络。 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player instanceof FakePlayer) {
            fakePlayerSink.accept(player, payload);
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 客户端 → 服务端发包统一封装。 */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // registrar 的参数就是注册器版本（等价于 new PayloadRegistrar(版本)）：协议版本 = mod 版本（D4）。
        PayloadRegistrar r = event.registrar(protocolVersion());

        // C2S —— playToServer 绑定方向；方向不符的包会被 NeoForge 直接断开
        r.playToServer(C2S_RequestBlueprintDataMessage.TYPE,
                C2S_RequestBlueprintDataMessage.STREAM_CODEC, C2S_RequestBlueprintDataMessage::handle);
        r.playToServer(C2S_DiscardInvalidBlueprintMessage.TYPE,
                C2S_DiscardInvalidBlueprintMessage.STREAM_CODEC, C2S_DiscardInvalidBlueprintMessage::handle);

        // S2C —— playToClient 绑定方向
        r.playToClient(S2C_OperationResult.TYPE,
                S2C_OperationResult.STREAM_CODEC, S2C_OperationResult::handle);
        r.playToClient(S2C_BlueprintChunkMessage.TYPE,
                S2C_BlueprintChunkMessage.STREAM_CODEC, S2C_BlueprintChunkMessage::handle);
        r.playToClient(S2C_BlueprintDataMessage.TYPE,
                S2C_BlueprintDataMessage.STREAM_CODEC, S2C_BlueprintDataMessage::handle);
        r.playToClient(S2C_BlueprintDataFailMessage.TYPE,
                S2C_BlueprintDataFailMessage.STREAM_CODEC, S2C_BlueprintDataFailMessage::handle);
        r.playToClient(S2C_OpenVoidScreenMessage.TYPE,
                S2C_OpenVoidScreenMessage.STREAM_CODEC, S2C_OpenVoidScreenMessage::handle);
        r.playToClient(S2C_IllegalBlocksListMessage.TYPE,
                S2C_IllegalBlocksListMessage.STREAM_CODEC, S2C_IllegalBlocksListMessage::handle);
    }

    // ── D5：蓝图分片传输（保留自有分片）──────────────────────────────────────

    /** 存放 {@code length} 字节需要几块（每块 {@link S2C_BlueprintChunkMessage#CHUNK_SIZE} 字节）。 */
    public static int chunkCount(int length) {
        int chunkSize = S2C_BlueprintChunkMessage.CHUNK_SIZE;
        return (length + chunkSize - 1) / chunkSize;
    }

    /**
     * 把整份压缩字节切成块；块数超 {@link S2C_BlueprintChunkMessage#MAX_TOTAL_CHUNKS} 时返回
     * {@code null}（由调用方记原来那条 warn）。
     */
    @Nullable
    public static List<S2C_BlueprintChunkMessage> splitIntoChunks(String blueprintId, byte[] full) {
        int chunkSize = S2C_BlueprintChunkMessage.CHUNK_SIZE;
        int totalChunks = chunkCount(full.length);
        if (totalChunks > S2C_BlueprintChunkMessage.MAX_TOTAL_CHUNKS) {
            return null;
        }
        List<S2C_BlueprintChunkMessage> out = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, full.length);
            out.add(new S2C_BlueprintChunkMessage(blueprintId, i, totalChunks,
                    Arrays.copyOfRange(full, start, end)));
        }
        return out;
    }

    /**
     * Server-side helper: always chunk the blueprint payload into
     * {@link S2C_BlueprintChunkMessage} frames. One code path, no size branch.
     * Refuses (log + no send) when chunk count would exceed
     * {@link S2C_BlueprintChunkMessage#MAX_TOTAL_CHUNKS}.
     */
    public static void sendBlueprintTo(ServerPlayer player, String blueprintId, BlueprintData data) {
        if (player == null || blueprintId == null || blueprintId.isEmpty() || data == null) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(data.serialize(), baos);
            byte[] full = baos.toByteArray();
            List<S2C_BlueprintChunkMessage> chunks = splitIntoChunks(blueprintId, full);
            if (chunks == null) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[BlueprintNetwork] Refusing to send blueprint {}: {} chunks > limit {} ({} bytes)",
                        blueprintId, chunkCount(full.length), S2C_BlueprintChunkMessage.MAX_TOTAL_CHUNKS,
                        full.length);
                return;
            }
            for (S2C_BlueprintChunkMessage chunk : chunks) {
                sendToPlayer(player, chunk);
            }
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintNetwork] Failed to send blueprint {}: {}", blueprintId, e.getMessage());
        }
    }
}
