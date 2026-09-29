package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.UploadStagingRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S 上传分片数据块（SB-013）。
 *
 * <p>每块 ≤30000 字节，远小于 C2S 32767 硬上限。最后一块到齐后组装出完整
 * gzip 字节，交回 {@code C2S_UploadProjectionTemplateMessage.handleAssembled}，
 * 走与单包完全相同的上传限流 → 00~14 全校验 → 扫描 → 写入 → 报告流程。</p>
 */
public class C2S_UploadChunkMessage {

    private final UUID uploadId;
    private final int index;
    private final byte[] payload;

    public C2S_UploadChunkMessage(UUID uploadId, int index, byte[] payload) {
        this.uploadId = uploadId;
        this.index = index;
        this.payload = payload;
    }

    public static void encode(C2S_UploadChunkMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.uploadId);
        buf.writeVarInt(msg.index);
        buf.writeByteArray(msg.payload);
    }

    public static C2S_UploadChunkMessage decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int index = buf.readVarInt();
        byte[] payload = buf.readByteArray(30000);
        return new C2S_UploadChunkMessage(id, index, payload);
    }

    public static void handle(C2S_UploadChunkMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            long maxBytes = safeMaxBytes();
            UploadStagingRegistry.AppendOutcome out = UploadStagingRegistry.append(
                    msg.uploadId, player.getUUID(), msg.index, msg.payload, maxBytes);
            switch (out.result) {
                case OK -> { /* 等后续块 */ }
                case COMPLETE ->
                        C2S_UploadProjectionTemplateMessage.handleAssembled(
                                out.assembled, out.category, out.forceRequested, ctx);
                default ->
                        BlueprintNetwork.CHANNEL.send(
                                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                                S2C_OperationResult.failure(
                                        "spatial_blueprint.upload.staging_rejected"));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** MAX_UPLOAD_FILE_BYTES 本身就是字节数（与旧单包 01 检测同一口径）。 */
    private static long safeMaxBytes() {
        try {
            return Math.max(1L, (long) BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get());
        } catch (Exception e) {
            return 8L * 1024L * 1024L;
        }
    }
}
