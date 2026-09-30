package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.UploadStagingRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S 上传分片开始声明（SB-013）。
 *
 * <p>客户端在压缩字节超过 {@link #SINGLE_PACKET_LIMIT} 时先发本消息声明
 * uploadId / 总块数 / 总字节 / category / force，随后逐块发
 * {@code C2S_UploadChunkMessage}。服务端只做轻量前置校验
 * （创造-create、槽位上限），真正的上传限流与全部重量级校验
 * 留在组包完成后的 {@code handleAssembled} 里统一执行。</p>
 */
public class C2S_UploadBeginMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_UploadBeginMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_upload_begin_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_UploadBeginMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_UploadBeginMessage::encode, C2S_UploadBeginMessage::decode);

    /** 单包上限：压缩字节 ≤ 此值走旧单包消息，超过走分片。留 2KB 余量给消息头。 */
    public static final int SINGLE_PACKET_LIMIT = 30000;

    /** 块大小：与服务端 30000 上限对齐（30000 = 30KB < 32767 硬上限，余 2767 给包头）。 */
    public static final int CHUNK_SIZE = 30000;

    /** 单次分片上传最多块数：30000×256 = 7.5MB，覆盖服务端展示预算（8MB）内的大文件。 */
    public static final int MAX_CHUNKS = 256;

    private final UUID uploadId;
    private final int totalChunks;
    private final long totalBytes;
    private final String category;
    private final boolean forceRequested;

    public C2S_UploadBeginMessage(UUID uploadId, int totalChunks, long totalBytes,
                                  String category, boolean forceRequested) {
        this.uploadId = uploadId;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.category = normalize(category);
        this.forceRequested = forceRequested;
    }

    @Override
    public CustomPacketPayload.Type<C2S_UploadBeginMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_UploadBeginMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.uploadId);
        buf.writeVarInt(msg.totalChunks);
        buf.writeVarLong(msg.totalBytes);
        buf.writeUtf(msg.category, 16);
        buf.writeBoolean(msg.forceRequested);
    }

    public static C2S_UploadBeginMessage decode(FriendlyByteBuf buf) {
        return new C2S_UploadBeginMessage(buf.readUUID(), buf.readVarInt(), buf.readVarLong(),
                buf.readUtf(16), buf.readBoolean());
    }

    public static void handle(C2S_UploadBeginMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_UploadBeginMessage msg, ServerPlayer player) {
        if (player == null) return;

        // 前置轻校验：非法参数直接拒绝（不占用上传限流配额）。
        if (msg.totalChunks < 2 || msg.totalChunks > MAX_CHUNKS || msg.totalBytes <= 0) {
            fail(player, "spatial_blueprint.upload.staging_rejected");
            return;
        }
        long maxBytes = safeMaxBytes();
        if (msg.totalBytes > maxBytes) {
            fail(player, "spatial_blueprint.error.upload_too_large");
            return;
        }
        if ("create".equals(msg.category) && !player.isCreative()) {
            fail(player, "spatial_blueprint.error.creative_only");
            return;
        }
        // 注：真正的槽位判断仍在 handleAssembled 全流程里。Begin 阶段不读盘，
        // 避免客户端用 Begin 探测刷盘。
        if (!UploadStagingRegistry.begin(msg.uploadId, player.getUUID(), msg.totalChunks,
                msg.totalBytes, msg.category, msg.forceRequested)) {
            fail(player, "spatial_blueprint.upload.staging_rejected");
        }
    }

    /** MAX_UPLOAD_FILE_BYTES 本身就是字节数（与旧单包 01 检测同一口径）。 */
    private static long safeMaxBytes() {
        try {
            return Math.max(1L, (long) BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get());
        } catch (Exception e) {
            return 8L * 1024L * 1024L;
        }
    }

    private static void fail(ServerPlayer player, String key) {
        BlueprintNetwork.sendToPlayer(player, S2C_OperationResult.failure(key));
    }

    private static String normalize(String category) {
        if (category == null) return "space";
        String c = category.trim().toLowerCase(java.util.Locale.ROOT);
        if (c.equals("space") || c.equals("create") || c.equals("project")
                || c.equals("project_template") || c.equals("projection_template")) return c;
        return "space";
    }
}
