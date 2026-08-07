package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.SBSafeCoordData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: save or clear the safe teleport coordinate for an engineering blueprint.
 */
public class C2S_SafeCoordMessage {

    private final String  blueprintId;
    private final boolean hasCoord;
    private final int     x, y, z;
    private final boolean allowSurvival;

    public C2S_SafeCoordMessage(String blueprintId, boolean hasCoord, int x, int y, int z, boolean allowSurvival) {
        this.blueprintId   = blueprintId;
        this.hasCoord      = hasCoord;
        this.x             = x;
        this.y             = y;
        this.z             = z;
        this.allowSurvival = allowSurvival;
    }

    public static void encode(C2S_SafeCoordMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeBoolean(msg.hasCoord);
        buf.writeInt(msg.x);
        buf.writeInt(msg.y);
        buf.writeInt(msg.z);
        buf.writeBoolean(msg.allowSurvival);
    }

    public static C2S_SafeCoordMessage decode(FriendlyByteBuf buf) {
        String  id            = buf.readUtf(64);
        boolean hasCoord      = buf.readBoolean();
        int     x             = buf.readInt();
        int     y             = buf.readInt();
        int     z             = buf.readInt();
        boolean allowSurvival = buf.readBoolean();
        return new C2S_SafeCoordMessage(id, hasCoord, x, y, z, allowSurvival);
    }

    public static void handle(C2S_SafeCoordMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!player.hasPermissions(2)) return;

            SBSafeCoordData.get(player.serverLevel())
                    .setEntry(msg.blueprintId, msg.hasCoord, msg.x, msg.y, msg.z, msg.allowSurvival);

            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.gui.safe_coord.saved"), false);
        });
        ctx.get().setPacketHandled(true);
    }
}
