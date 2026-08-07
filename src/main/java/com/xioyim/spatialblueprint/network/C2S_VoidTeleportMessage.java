package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
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
 * C2S: OP player requests teleportation to the coordinate recorded when the
 * dimensional blueprint was placed (player's position at placement time).
 * If no coord is stored, falls back to the nearest captureMin / captureMax corner.
 */
public class C2S_VoidTeleportMessage {

    public static void encode(C2S_VoidTeleportMessage msg, FriendlyByteBuf buf) {}

    public static C2S_VoidTeleportMessage decode(FriendlyByteBuf buf) {
        return new C2S_VoidTeleportMessage();
    }

    public static void handle(C2S_VoidTeleportMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Find held void dimensional blueprint
            ItemStack stack = null;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack s = player.getItemInHand(hand);
                if (s.getItem() instanceof VoidDimensionalBlueprintItem) {
                    stack = s;
                    break;
                }
            }
            if (stack == null) return;

            // OP check (creative bypasses)
            if (!player.isCreative() && !player.hasPermissions(2)) {
                sendResult(player, false, "spatial_blueprint.gui.void_dimensional.btn.tp.no_perm");
                return;
            }

            // Dimension check
            String tpDim = VoidDimensionalBlueprintItem.getTpDimension(stack);
            String currentDim = player.serverLevel().dimension().location().toString();
            if (!tpDim.isEmpty() && !tpDim.equals(currentDim)) {
                sendResult(player, false, "spatial_blueprint.gui.void_dimensional.btn.tp.wrong_dim");
                return;
            }

            // Determine destination
            double destX, destY, destZ;
            if (VoidDimensionalBlueprintItem.hasTpCoord(stack)) {
                destX = VoidDimensionalBlueprintItem.getTpX(stack) + 0.5;
                destY = VoidDimensionalBlueprintItem.getTpY(stack);
                destZ = VoidDimensionalBlueprintItem.getTpZ(stack) + 0.5;
            } else {
                // Fallback: nearest of captureMin / captureMax
                BlockPos minPos = VoidDimensionalBlueprintItem.getCaptureMin(stack);
                BlockPos maxPos = VoidDimensionalBlueprintItem.getCaptureMax(stack);
                if (minPos == null) return;
                if (maxPos == null) { maxPos = minPos; }
                Vec3 pos = player.position();
                double dMin = pos.distanceToSqr(minPos.getX() + 0.5, minPos.getY(), minPos.getZ() + 0.5);
                double dMax = pos.distanceToSqr(maxPos.getX() + 0.5, maxPos.getY(), maxPos.getZ() + 0.5);
                BlockPos dest = dMin <= dMax ? minPos : maxPos;
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
