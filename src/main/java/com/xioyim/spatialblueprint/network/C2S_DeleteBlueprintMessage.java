package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: creative-mode client requests deleting a server-side blueprint.
 *
 * <p>After deletion the server pushes a fresh {@link S2C_LibrarySyncMessage}
 * so the client list stays in sync.</p>
 */
public class C2S_DeleteBlueprintMessage {

    private final String blueprintId;

    public C2S_DeleteBlueprintMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    public static void encode(C2S_DeleteBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_DeleteBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_DeleteBlueprintMessage(buf.readUtf(64));
    }

    public static void handle(C2S_DeleteBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.isCreative()) return;
            if (msg.blueprintId.isEmpty()) return;

            BlueprintStorage.delete(msg.blueprintId);

            // Push the updated list back so the client refreshes immediately.
            List<BlueprintStorage.BlueprintSummary> updated = BlueprintStorage.listSummaries();
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(updated));
        });
        ctx.get().setPacketHandled(true);
    }
}
