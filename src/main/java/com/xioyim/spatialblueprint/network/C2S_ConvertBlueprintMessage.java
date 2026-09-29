package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: OP client requests converting a blueprint between Spatial ({@code "create"}) and
 * Projection ({@code "project"}) categories.
 *
 * <p>Only the {@code category} field changes; all other blueprint data (name, creator,
 * timestamps, notes, etc.) is preserved. After a successful conversion the server pushes a
 * fresh {@link S2C_LibrarySyncMessage} so the client list stays in sync.</p>
 */
public class C2S_ConvertBlueprintMessage {

    private final String blueprintId;

    public C2S_ConvertBlueprintMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    public static void encode(C2S_ConvertBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_ConvertBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_ConvertBlueprintMessage(buf.readUtf(64));
    }

    public static void handle(C2S_ConvertBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;

            boolean ok = BlueprintStorage.convertCategory(msg.blueprintId);
            if (ok) {
                // H2：OP 也走过滤口径（OP 本来就是全量，结果一致）。
                List<BlueprintStorage.BlueprintSummary> updated = C2S_RequestLibraryMessage.buildFilteredSummaries(player);
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_LibrarySyncMessage(updated));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
