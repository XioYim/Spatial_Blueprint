package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** 空置次元蓝图 → [重置] 确认：删除物品，给予空白蓝图。 */
public class C2S_VoidResetMessage {

    public static void encode(C2S_VoidResetMessage msg, FriendlyByteBuf buf) {}

    public static C2S_VoidResetMessage decode(FriendlyByteBuf buf) {
        return new C2S_VoidResetMessage();
    }

    public static void handle(C2S_VoidResetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            InteractionHand hand = null;
            if (player.getMainHandItem().getItem() instanceof VoidDimensionalBlueprintItem) {
                hand = InteractionHand.MAIN_HAND;
            } else if (player.getOffhandItem().getItem() instanceof VoidDimensionalBlueprintItem) {
                hand = InteractionHand.OFF_HAND;
            }
            if (hand == null) return;

            player.setItemInHand(hand, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_OperationResult(true, ""));
        });
        ctx.get().setPacketHandled(true);
    }
}
