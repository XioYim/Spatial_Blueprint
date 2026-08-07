package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.client.ClientLibraryCache;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C: server pushes the blueprint library summary list to the requesting client.
 *
 * <p>Sent in response to {@link C2S_RequestLibraryMessage} and after successful
 * rename / delete operations to keep the client list fresh.  On receipt the client
 * stores the list in {@link ClientLibraryCache}.</p>
 */
public class S2C_LibrarySyncMessage {

    private final List<BlueprintStorage.BlueprintSummary> summaries;

    public S2C_LibrarySyncMessage(List<BlueprintStorage.BlueprintSummary> summaries) {
        this.summaries = summaries;
    }

    public List<BlueprintStorage.BlueprintSummary> getSummaries() {
        return summaries;
    }

    // ── Encode / Decode ───────────────────────────────────────────────────

    public static void encode(S2C_LibrarySyncMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.summaries.size());
        for (BlueprintStorage.BlueprintSummary s : msg.summaries) {
            buf.writeUtf(s.id(),           64);
            buf.writeUtf(s.name(),        128);
            buf.writeUtf(s.category(),     32);
            buf.writeVarInt(s.blockCount());
            buf.writeVarInt(s.animDuration());
            buf.writeInt(s.customWithdrawXp());
            buf.writeInt(s.customWithdrawLimit());
            buf.writeUtf(s.creatorName(),  64);
            buf.writeLong(s.createdAt());
            buf.writeUtf(s.sourceName() != null ? s.sourceName() : "", 64);
            boolean hasFixed = s.fixedStartPos() != null && s.fixedEndPos() != null;
            buf.writeBoolean(hasFixed);
            if (hasFixed) {
                buf.writeBlockPos(s.fixedStartPos());
                buf.writeBlockPos(s.fixedEndPos());
            }
            buf.writeInt(s.maxDistance());
            buf.writeUtf(s.fixedDimension() != null ? s.fixedDimension() : "", 128);
            buf.writeBoolean(s.safeCoordSet());
            buf.writeInt(s.safeX());
            buf.writeInt(s.safeY());
            buf.writeInt(s.safeZ());
            buf.writeBoolean(s.safeAllowSurvival());
        }
    }

    public static S2C_LibrarySyncMessage decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BlueprintStorage.BlueprintSummary> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String  id            = buf.readUtf(64);
            String  name          = buf.readUtf(128);
            String  category      = buf.readUtf(32);
            int     blockCount    = buf.readVarInt();
            int     animDuration  = buf.readVarInt();
            int     cwXp          = buf.readInt();
            int     cwLimit       = buf.readInt();
            String  creatorName   = buf.readUtf(64);
            long    createdAt     = buf.readLong();
            String  sourceName    = buf.readUtf(64);
            boolean hasFixed      = buf.readBoolean();
            BlockPos fixedStart   = hasFixed ? buf.readBlockPos() : null;
            BlockPos fixedEnd     = hasFixed ? buf.readBlockPos() : null;
            int     maxDist       = buf.readInt();
            String  fixedDim      = buf.readUtf(128);
            boolean safeSet       = buf.readBoolean();
            int     safeX         = buf.readInt();
            int     safeY         = buf.readInt();
            int     safeZ         = buf.readInt();
            boolean safeAllow     = buf.readBoolean();
            list.add(new BlueprintStorage.BlueprintSummary(
                    id, name, category, blockCount, animDuration,
                    cwXp, cwLimit, creatorName, createdAt,
                    fixedStart, fixedEnd, maxDist, fixedDim,
                    safeSet, safeX, safeY, safeZ, safeAllow, sourceName));
        }
        return new S2C_LibrarySyncMessage(list);
    }

    // ── Handler ───────────────────────────────────────────────────────────

    public static void handle(S2C_LibrarySyncMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientLibraryCache.onReceive(msg.summaries);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
