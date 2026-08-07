package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.SBSafeCoordData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S: OP player requests teleportation to the nearest engineering blueprint endpoint
 * or to the saved safe coordinate (if one is configured).
 */
public class C2S_EngineeringTeleportMessage {

    public static void encode(C2S_EngineeringTeleportMessage msg, FriendlyByteBuf buf) {}

    public static C2S_EngineeringTeleportMessage decode(FriendlyByteBuf buf) {
        return new C2S_EngineeringTeleportMessage();
    }

    public static void handle(C2S_EngineeringTeleportMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Find held engineering blueprint
            ItemStack stack = null;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack s = player.getItemInHand(hand);
                if (s.getItem() instanceof EngineeringBlueprintItem
                        && EngineeringBlueprintItem.hasBoundProject(s)) {
                    stack = s;
                    break;
                }
            }
            if (stack == null) {
                sendResult(player, false, "spatial_blueprint.gui.engineering.btn.tp.no_bound");
                return;
            }

            // OP check (creative mode bypasses)
            if (!player.isCreative() && !player.hasPermissions(2)) {
                sendResult(player, false, "spatial_blueprint.gui.engineering.btn.tp.no_op");
                return;
            }

            // Dimension check
            String boundDim = EngineeringBlueprintItem.getBoundDimension(stack);
            String currentDim = player.serverLevel().dimension().location().toString();
            if (!boundDim.isEmpty() && !boundDim.equals(currentDim)) {
                sendResult(player, false, "spatial_blueprint.gui.engineering.btn.tp.wrong_dim");
                return;
            }

            String blueprintId = EngineeringBlueprintItem.getBlueprintId(stack);
            SBSafeCoordData.SafeEntry entry = blueprintId != null
                    ? SBSafeCoordData.get(player.serverLevel()).getEntry(blueprintId)
                    : null;

            // allowSurvival check (survival/adventure without creative flag)
            if (!player.isCreative()) {
                boolean allowSurvival = entry != null && entry.allowSurvival();
                if (!allowSurvival) {
                    sendResult(player, false, "spatial_blueprint.gui.engineering.btn.tp.no_survival");
                    return;
                }
            }

            // Determine destination
            double destX, destY, destZ;
            if (entry != null && entry.hasCoord()) {
                destX = entry.x() + 0.5;
                destY = entry.y();
                destZ = entry.z() + 0.5;
            } else {
                // Nearest of startPos / endPos
                BlockPos startPos = EngineeringBlueprintItem.getBoundStartPos(stack);
                BlockPos endPos   = EngineeringBlueprintItem.getBoundEndPos(stack);
                Vec3 pos = player.position();
                double dStart = pos.distanceToSqr(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);
                double dEnd   = pos.distanceToSqr(endPos.getX() + 0.5,   endPos.getY(),   endPos.getZ() + 0.5);
                BlockPos dest = dStart <= dEnd ? startPos : endPos;
                destX = dest.getX() + 0.5;
                destY = dest.getY();
                destZ = dest.getZ() + 0.5;
            }

            player.teleportTo(destX, destY, destZ);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey));
    }
}
