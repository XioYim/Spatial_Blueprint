package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: creative-mode client requests renaming a server-side blueprint.
 *
 * <p>After a successful rename the server pushes a fresh {@link S2C_LibrarySyncMessage}
 * so the client list stays in sync.</p>
 */
public class C2S_RenameBlueprintMessage {

    private final String blueprintId;
    private final String newName;

    public C2S_RenameBlueprintMessage(String blueprintId, String newName) {
        this.blueprintId = blueprintId;
        this.newName     = newName;
    }

    public static void encode(C2S_RenameBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.newName,    128);
    }

    public static C2S_RenameBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_RenameBlueprintMessage(buf.readUtf(64), buf.readUtf(128));
    }

    public static void handle(C2S_RenameBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.isCreative()) return;

            String name = BlueprintValidator.sanitizeName(msg.newName);
            if (name.isEmpty()) return;

            BlueprintStorage.rename(msg.blueprintId, name);

            // Push the updated list back so the client refreshes immediately.
            List<BlueprintStorage.BlueprintSummary> updated = BlueprintStorage.listSummaries();
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(updated));
        });
        ctx.get().setPacketHandled(true);
    }
}
