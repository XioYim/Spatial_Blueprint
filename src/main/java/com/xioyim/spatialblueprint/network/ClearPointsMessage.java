package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClearPointsMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClearPointsMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "clear_points_message"));

    public static final StreamCodec<FriendlyByteBuf, ClearPointsMessage> STREAM_CODEC =
            StreamCodec.ofMember(ClearPointsMessage::encode, ClearPointsMessage::decode);

    public ClearPointsMessage() {}

    @Override
    public CustomPacketPayload.Type<ClearPointsMessage> type() {
        return TYPE;
    }

    public static void encode(ClearPointsMessage msg, FriendlyByteBuf buf) {}

    public static ClearPointsMessage decode(FriendlyByteBuf buf) {
        return new ClearPointsMessage();
    }

    public static void handle(ClearPointsMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(ClearPointsMessage msg, ServerPlayer player) {
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
    }
}
