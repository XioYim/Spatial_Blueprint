package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class UpdateOffsetMessage {
    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    public UpdateOffsetMessage(int xOffset, int yOffset, int zOffset) {
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;
    }

    public static void encode(UpdateOffsetMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.xOffset);
        buf.writeInt(msg.yOffset);
        buf.writeInt(msg.zOffset);
    }

    public static UpdateOffsetMessage decode(FriendlyByteBuf buf) {
        return new UpdateOffsetMessage(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void handle(UpdateOffsetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
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

            // NOTE: We intentionally do NOT write offset to server-side item NBT here.
            // Writing NBT on the server triggers Minecraft's broadcastChanges() auto-sync,
            // which would push the server item (without lock state) back to the client,
            // corrupting the client's locked=true state. The offset is already stored
            // client-side and is embedded directly in PlaceBlueprintMessage.finalOrigin.
            BlueprintLogger.success(player, BlueprintLogger.Action.UPDATE_OFFSET,
                    "offset=(" + x + "," + y + "," + z + ")");
        });
        ctx.get().setPacketHandled(true);
    }
}
