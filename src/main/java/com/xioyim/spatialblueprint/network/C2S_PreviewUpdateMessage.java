package com.xioyim.spatialblueprint.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: client reports its current blueprint preview position to the server.
 * Server rebroadcasts to nearby players via {@link S2C_PreviewBroadcastMessage}.
 *
 * <p>origin == null means "clear my preview" (screen closed / cancel).</p>
 */
public class C2S_PreviewUpdateMessage {

    /** null = clear */
    final BlockPos origin;
    final int      rotation; // 0=NONE,1=CW90,2=CW180,3=CCW90
    final int      sizeX;
    final int      sizeY;
    final int      sizeZ;
    final String   blueprintId;

    public C2S_PreviewUpdateMessage(BlockPos origin, int rotation,
                                    int sizeX, int sizeY, int sizeZ,
                                    String blueprintId) {
        this.origin      = origin;
        this.rotation    = rotation;
        this.sizeX       = sizeX;
        this.sizeY       = sizeY;
        this.sizeZ       = sizeZ;
        this.blueprintId = blueprintId != null ? blueprintId : "";
    }

    public static void encode(C2S_PreviewUpdateMessage msg, FriendlyByteBuf buf) {
        boolean hasOrigin = msg.origin != null;
        buf.writeBoolean(hasOrigin);
        if (hasOrigin) buf.writeBlockPos(msg.origin);
        buf.writeVarInt(msg.rotation);
        buf.writeVarInt(msg.sizeX);
        buf.writeVarInt(msg.sizeY);
        buf.writeVarInt(msg.sizeZ);
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_PreviewUpdateMessage decode(FriendlyByteBuf buf) {
        boolean hasOrigin = buf.readBoolean();
        BlockPos origin   = hasOrigin ? buf.readBlockPos() : null;
        int rotation      = buf.readVarInt();
        int sizeX         = buf.readVarInt();
        int sizeY         = buf.readVarInt();
        int sizeZ         = buf.readVarInt();
        String blueprintId = buf.readUtf(64);
        return new C2S_PreviewUpdateMessage(origin, rotation, sizeX, sizeY, sizeZ, blueprintId);
    }

    public static void handle(C2S_PreviewUpdateMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            com.xioyim.spatialblueprint.server.ServerPreviewTracker.update(
                    player, msg.origin, msg.rotation, msg.sizeX, msg.sizeY, msg.sizeZ, msg.blueprintId);
        });
        ctx.get().setPacketHandled(true);
    }
}
