package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: server pushes the blueprint library summary list to the requesting client.
 *
 * <p>Sent in response to {@link C2S_RequestLibraryMessage} and after successful
 * rename / delete operations to keep the client list fresh.  On receipt the client
 * stores the list in {@code ClientLibraryCache}.</p>
 */
public class S2C_LibrarySyncMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_LibrarySyncMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_library_sync_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_LibrarySyncMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_LibrarySyncMessage::encode, S2C_LibrarySyncMessage::decode);

    private final List<BlueprintStorage.BlueprintSummary> summaries;

    public S2C_LibrarySyncMessage(List<BlueprintStorage.BlueprintSummary> summaries) {
        this.summaries = summaries;
    }

    public List<BlueprintStorage.BlueprintSummary> getSummaries() {
        return summaries;
    }

    @Override
    public CustomPacketPayload.Type<S2C_LibrarySyncMessage> type() {
        return TYPE;
    }

    // ── Encode / Decode ───────────────────────────────────────────────────

    public static void encode(S2C_LibrarySyncMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.summaries.size());
        for (BlueprintStorage.BlueprintSummary s : msg.summaries) {
            writeSummary(buf, s);
        }
    }

    /** 单条摘要的编码。抽出来供 {@link S2C_VoidBagSyncMessage} 复用，避免两处字段顺序走样。 */
    public static void writeSummary(FriendlyByteBuf buf, BlueprintStorage.BlueprintSummary s) {
        {
            buf.writeUtf(s.id(),           64);
            buf.writeUtf(s.name(),        128);
            buf.writeUtf(s.category(),     32);
            buf.writeVarInt(s.blockCount());
            buf.writeVarInt(s.animDuration());
            buf.writeVarInt(s.materialCount());
            buf.writeUtf(s.creatorName(),  64);
            buf.writeLong(s.createdAt());
            buf.writeUtf(s.sourceName() != null ? s.sourceName() : "", 64);
            boolean hasFixed = s.fixedStartPos() != null && s.fixedEndPos() != null;
            buf.writeBoolean(hasFixed);
            if (hasFixed) {
                buf.writeBlockPos(s.fixedStartPos());
                buf.writeBlockPos(s.fixedEndPos());
            }
            buf.writeUtf(s.fixedDimension() != null ? s.fixedDimension() : "", 128);
            buf.writeInt(s.saveMode() != null ? s.saveMode().ordinal() : 0);
            buf.writeUtf(s.noteShort() != null ? s.noteShort() : "", 15);
            buf.writeUtf(s.noteLong() != null ? s.noteLong() : "", 200);
            buf.writeBoolean(s.placed());
            buf.writeInt(s.placedX());
            buf.writeInt(s.placedY());
            buf.writeInt(s.placedZ());
            buf.writeUtf(s.placedDimension() != null ? s.placedDimension() : "", 128);
            buf.writeUtf(s.ownerUuid() != null ? s.ownerUuid() : "", 64);
            buf.writeBoolean(s.foreignWorld());
            buf.writeUtf(s.worldName() != null ? s.worldName() : "", 64);
            buf.writeVarInt(s.uploadMode());
            // 2.7.33 末尾追加：安全传送坐标（工程蓝图；其余分类恒 false/0）
            buf.writeBoolean(s.safeCoordSet());
            buf.writeInt(s.safeX());
            buf.writeInt(s.safeY());
            buf.writeInt(s.safeZ());
        }
    }

    public static S2C_LibrarySyncMessage decode(FriendlyByteBuf buf) {
        // A3 纵深防御：摘要数正常就是磁盘文件数（几百），异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int count = S2C_OperationResult.checkedCount(buf, 65536, "summaries");
        List<BlueprintStorage.BlueprintSummary> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(readSummary(buf));
        }
        return new S2C_LibrarySyncMessage(list);
    }

    /** 单条摘要的解码，与 {@link #writeSummary} 严格对应。 */
    public static BlueprintStorage.BlueprintSummary readSummary(FriendlyByteBuf buf) {
        {
            String  id            = buf.readUtf(64);
            String  name          = buf.readUtf(128);
            String  category      = buf.readUtf(32);
            int     blockCount    = buf.readVarInt();
            int     animDuration  = buf.readVarInt();
            int     materialCount = buf.readVarInt();
            String  creatorName   = buf.readUtf(64);
            long    createdAt     = buf.readLong();
            String  sourceName    = buf.readUtf(64);
            boolean hasFixed      = buf.readBoolean();
            BlockPos fixedStart   = hasFixed ? buf.readBlockPos() : null;
            BlockPos fixedEnd     = hasFixed ? buf.readBlockPos() : null;
            String  fixedDim      = buf.readUtf(128);
            com.xioyim.spatialblueprint.util.CaptureMode saveMode =
                    com.xioyim.spatialblueprint.util.CaptureMode.fromOrdinal(buf.readInt());
            String noteShort = buf.readUtf(15);
            String noteLong = buf.readUtf(200);
            boolean placed   = buf.readBoolean();
            int placedX      = buf.readInt();
            int placedY      = buf.readInt();
            int placedZ      = buf.readInt();
            String placedDim = buf.readUtf(128);
            String ownerUuid = buf.readUtf(64);
            boolean foreignWorld = buf.readBoolean();
            String worldName = buf.readUtf(64);
            int uploadMode = buf.readVarInt();
            boolean safeSet = buf.readBoolean();
            int safeX = buf.readInt();
            int safeY = buf.readInt();
            int safeZ = buf.readInt();
            return new BlueprintStorage.BlueprintSummary(
                    id, name, category, blockCount, animDuration, materialCount,
                    creatorName, createdAt,
                    fixedStart, fixedEnd, fixedDim,
                    sourceName, saveMode,
                    noteShort, noteLong,
                    placed, placedX, placedY, placedZ, placedDim, ownerUuid, foreignWorld, worldName,
                    uploadMode, safeSet, safeX, safeY, safeZ);
        }
    }

    // ── Handler ───────────────────────────────────────────────────────────

    public static void handle(S2C_LibrarySyncMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端把服务端下发的摘要列表写入 ClientLibraryCache（ClientLibraryCache.onReceive(msg.summaries)）
    }
}
