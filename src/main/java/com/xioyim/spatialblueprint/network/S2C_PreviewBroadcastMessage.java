package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * S2C: server broadcasts another player's blueprint preview to nearby clients.
 * origin == null means that player cleared their preview.
 */
public class S2C_PreviewBroadcastMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_PreviewBroadcastMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_preview_broadcast_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_PreviewBroadcastMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_PreviewBroadcastMessage::encode, S2C_PreviewBroadcastMessage::decode);

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

    @Override
    public CustomPacketPayload.Type<S2C_PreviewBroadcastMessage> type() {
        return TYPE;
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

    public static void handle(S2C_PreviewBroadcastMessage msg, IPayloadContext ctx) {
        com.xioyim.spatialblueprint.client.SpatialBlueprintClient.receiveOtherPreview(msg);
    }
}
