package com.xioyim.spatialblueprint.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C: server broadcasts another player's blueprint preview to nearby clients.
 * origin == null means that player cleared their preview.
 */
public class S2C_PreviewBroadcastMessage {

    public final UUID    playerUUID;
    public final String  playerName;
    public final String  blueprintId;
    /** 蓝图分类，决定预览框颜色。 */
    public final String  category;
    /** null = clear */
    public final BlockPos origin;
    public final int      rotation;
    public final int      sizeX;
    public final int      sizeY;
    public final int      sizeZ;

    public S2C_PreviewBroadcastMessage(UUID playerUUID, String playerName, String blueprintId,
                                       String category, BlockPos origin, int rotation,
                                       int sizeX, int sizeY, int sizeZ) {
        this.playerUUID  = playerUUID;
        this.playerName  = playerName;
        this.blueprintId = blueprintId;
        this.category    = category != null ? category : "";
        this.origin      = origin;
        this.rotation    = rotation;
        this.sizeX       = sizeX;
        this.sizeY       = sizeY;
        this.sizeZ       = sizeZ;
    }

    public static void encode(S2C_PreviewBroadcastMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.playerUUID);
        buf.writeUtf(msg.playerName, 40);
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.category, 32);
        boolean hasOrigin = msg.origin != null;
        buf.writeBoolean(hasOrigin);
        if (hasOrigin) buf.writeBlockPos(msg.origin);
        buf.writeVarInt(msg.rotation);
        buf.writeVarInt(msg.sizeX);
        buf.writeVarInt(msg.sizeY);
        buf.writeVarInt(msg.sizeZ);
    }

    public static S2C_PreviewBroadcastMessage decode(FriendlyByteBuf buf) {
        UUID   uuid        = buf.readUUID();
        String playerName  = buf.readUtf(40);
        String blueprintId = buf.readUtf(64);
        String category    = buf.readUtf(32);
        boolean hasOrigin  = buf.readBoolean();
        BlockPos origin    = hasOrigin ? buf.readBlockPos() : null;
        int rotation       = buf.readVarInt();
        int sizeX          = buf.readVarInt();
        int sizeY          = buf.readVarInt();
        int sizeZ          = buf.readVarInt();
        return new S2C_PreviewBroadcastMessage(uuid, playerName, blueprintId, category,
                origin, rotation, sizeX, sizeY, sizeZ);
    }

    public static void handle(S2C_PreviewBroadcastMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (FMLEnvironment.dist != Dist.CLIENT) return;
            handleClient(msg);
        });
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(S2C_PreviewBroadcastMessage msg) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // Don't show our own preview via broadcast
        if (mc.player != null && mc.player.getUUID().equals(msg.playerUUID)) return;
        com.xioyim.spatialblueprint.client.ClientOtherPreviewCache.receive(msg);
    }
}
