package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class UpdateSettingsMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UpdateSettingsMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "update_settings_message"));

    public static final StreamCodec<FriendlyByteBuf, UpdateSettingsMessage> STREAM_CODEC =
            StreamCodec.ofMember(UpdateSettingsMessage::encode, UpdateSettingsMessage::decode);

    public final Rotation rotation;

    public UpdateSettingsMessage(Rotation rotation) {
        this.rotation = rotation;
    }

    @Override
    public CustomPacketPayload.Type<UpdateSettingsMessage> type() {
        return TYPE;
    }

    public static void encode(UpdateSettingsMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.rotation.name(), 32);
    }

    public static UpdateSettingsMessage decode(FriendlyByteBuf buf) {
        String rotStr = buf.readUtf(32);
        Rotation rotation = Rotation.NONE;
        try {
            rotation = Rotation.valueOf(rotStr);
        } catch (IllegalArgumentException ignored) {}
        return new UpdateSettingsMessage(rotation);
    }

    public static void handle(UpdateSettingsMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(UpdateSettingsMessage msg, ServerPlayer player) {
        if (player == null) return;

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof SpatialBlueprintItem)) {
            stack = player.getOffhandItem();
        }
        if (!(stack.getItem() instanceof SpatialBlueprintItem)) {
            BlueprintLogger.failed(player, BlueprintLogger.Action.UPDATE_SETTINGS,
                    "rotation=" + msg.rotation.name(), "no_blueprint_item");
            return;
        }

        // NOTE: We intentionally do NOT write rotation to server-side item NBT here.
        // Same reason as UpdateOffsetMessage: writing NBT triggers broadcastChanges()
        // auto-sync which would overwrite the client's lock state with locked=false.
        // The rotation is embedded directly in PlaceBlueprintMessage and used by
        // the server during placement without needing it stored in item NBT.
        BlueprintLogger.success(player, BlueprintLogger.Action.UPDATE_SETTINGS,
                "rotation=" + msg.rotation.name());
    }
}
