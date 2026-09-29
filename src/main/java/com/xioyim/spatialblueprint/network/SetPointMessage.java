package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class SetPointMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetPointMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "set_point_message"));

    public static final StreamCodec<FriendlyByteBuf, SetPointMessage> STREAM_CODEC =
            StreamCodec.ofMember(SetPointMessage::encode, SetPointMessage::decode);

    private final int pointIndex;
    private final BlockPos pos;

    public SetPointMessage(int pointIndex, BlockPos pos) {
        this.pointIndex = pointIndex;
        this.pos = pos;
    }

    @Override
    public CustomPacketPayload.Type<SetPointMessage> type() {
        return TYPE;
    }

    public static void encode(SetPointMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.pointIndex);
        buf.writeBlockPos(msg.pos);
    }

    public static SetPointMessage decode(FriendlyByteBuf buf) {
        return new SetPointMessage(buf.readInt(), buf.readBlockPos());
    }

    public static void handle(SetPointMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(SetPointMessage msg, ServerPlayer player) {
        if (player == null) return;

        if (msg.pointIndex != 1 && msg.pointIndex != 2) {
            BlueprintLogger.failed(player, BlueprintLogger.Action.SET_POINT,
                    "point=" + msg.pointIndex + " " + BlueprintLogger.pos(msg.pos), "invalid_point_index");
            return;
        }

        BlueprintValidator.ValidationResult posCheck =
                BlueprintValidator.validateBlockPos(player.serverLevel(), msg.pos);
        if (!posCheck.valid()) {
            BlueprintLogger.failed(player, BlueprintLogger.Action.SET_POINT,
                    "point=" + msg.pointIndex + " " + BlueprintLogger.pos(msg.pos), posCheck.failKey());
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlankBlueprintItem)) {
            stack = player.getOffhandItem();
        }
        if (!(stack.getItem() instanceof BlankBlueprintItem)) {
            BlueprintLogger.failed(player, BlueprintLogger.Action.SET_POINT,
                    "point=" + msg.pointIndex + " " + BlueprintLogger.pos(msg.pos), "no_blank_blueprint");
            return;
        }

        if (msg.pointIndex == 1) {
            BlankBlueprintItem.setPoint1(stack, msg.pos);
        } else {
            BlankBlueprintItem.setPoint2(stack, msg.pos);
        }
        com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHands(player);
        BlueprintLogger.success(player, BlueprintLogger.Action.SET_POINT,
                "point=" + msg.pointIndex + " " + BlueprintLogger.pos(msg.pos));
    }
}
