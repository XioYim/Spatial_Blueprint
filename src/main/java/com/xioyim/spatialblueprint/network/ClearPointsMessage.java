package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ClearPointsMessage {
    public ClearPointsMessage() {}

    public static void encode(ClearPointsMessage msg, FriendlyByteBuf buf) {}

    public static ClearPointsMessage decode(FriendlyByteBuf buf) {
        return new ClearPointsMessage();
    }

    public static void handle(ClearPointsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack stack = null;
            ItemStack mainHand = player.getMainHandItem();
            if (mainHand.getItem() instanceof BlankBlueprintItem) {
                stack = mainHand;
            } else {
                ItemStack offHand = player.getOffhandItem();
                if (offHand.getItem() instanceof BlankBlueprintItem) {
                    stack = offHand;
                }
            }

            if (stack != null) {
                BlankBlueprintItem.clearPoints(stack);
                com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHands(player);
                BlueprintLogger.success(player, BlueprintLogger.Action.CLEAR_POINTS, "");
            } else {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CLEAR_POINTS, "", "no_blank_blueprint");
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
