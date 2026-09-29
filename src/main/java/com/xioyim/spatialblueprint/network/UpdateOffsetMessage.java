package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class UpdateOffsetMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UpdateOffsetMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "update_offset_message"));

    public static final StreamCodec<FriendlyByteBuf, UpdateOffsetMessage> STREAM_CODEC =
            StreamCodec.ofMember(UpdateOffsetMessage::encode, UpdateOffsetMessage::decode);

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    public UpdateOffsetMessage(int xOffset, int yOffset, int zOffset) {
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;
    }

    @Override
    public CustomPacketPayload.Type<UpdateOffsetMessage> type() {
        return TYPE;
    }

    public static void encode(UpdateOffsetMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.xOffset);
        buf.writeInt(msg.yOffset);
        buf.writeInt(msg.zOffset);
    }

    public static UpdateOffsetMessage decode(FriendlyByteBuf buf) {
        return new UpdateOffsetMessage(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(UpdateOffsetMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(UpdateOffsetMessage msg, ServerPlayer player) {
        if (player == null) return;

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof SpatialBlueprintItem)) {
            stack = player.getOffhandItem();
        }
        if (!(stack.getItem() instanceof SpatialBlueprintItem)) {
            BlueprintLogger.failed(player, BlueprintLogger.Action.UPDATE_OFFSET,
                    "offset=(" + msg.xOffset + "," + msg.yOffset + "," + msg.zOffset + ")",
                    "no_blueprint_item");
            return;
        }

        int x = BlueprintValidator.clampOffset(msg.xOffset);
        int y = BlueprintValidator.clampOffset(msg.yOffset);
        int z = BlueprintValidator.clampOffset(msg.zOffset);

        // NOTE: We intentionally do NOT write offset to server-side item data here.
        // Writing item data on the server triggers Minecraft's broadcastChanges() auto-sync,
        // which would push the server item (without lock state) back to the client,
        // corrupting the client's locked=true state. The offset is already stored
        // client-side and is embedded directly in PlaceBlueprintMessage.finalOrigin.
        BlueprintLogger.success(player, BlueprintLogger.Action.UPDATE_OFFSET,
                "offset=(" + x + "," + y + "," + z + ")");
    }
}
