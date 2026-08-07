package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.SBSafeCoordData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: client requests the full blueprint library summary list.
 *
 * <p>The server reads its local {@link BlueprintStorage#listSummaries()} and replies
 * with an {@link S2C_LibrarySyncMessage}.  Enables the blueprint library screen to
 * display server-side blueprints in multiplayer (#2).</p>
 */
public class C2S_RequestLibraryMessage {

    public C2S_RequestLibraryMessage() {}

    public static void encode(C2S_RequestLibraryMessage msg, FriendlyByteBuf buf) {
        // no payload
    }

    public static C2S_RequestLibraryMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestLibraryMessage();
    }

    public static void handle(C2S_RequestLibraryMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            BlueprintStorage.invalidateSummaryCache();
            SBSafeCoordData safeData = SBSafeCoordData.get(player.serverLevel());
            List<BlueprintStorage.BlueprintSummary> raw = BlueprintStorage.listSummaries();
            List<BlueprintStorage.BlueprintSummary> summaries = raw.stream().map(s -> {
                SBSafeCoordData.SafeEntry e = safeData.getEntry(s.id());
                if (e == null) return s;
                return new BlueprintStorage.BlueprintSummary(
                        s.id(), s.name(), s.category(), s.blockCount(), s.animDuration(),
                        s.customWithdrawXp(), s.customWithdrawLimit(), s.creatorName(), s.createdAt(),
                        s.fixedStartPos(), s.fixedEndPos(), s.maxDistance(), s.fixedDimension(),
                        e.hasCoord(), e.x(), e.y(), e.z(), e.allowSurvival(), s.sourceName());
            }).collect(java.util.stream.Collectors.toList());
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(summaries));
        });
        ctx.get().setPacketHandled(true);
    }
}
