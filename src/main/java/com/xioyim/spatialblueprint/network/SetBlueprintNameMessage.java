package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class SetBlueprintNameMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetBlueprintNameMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "set_blueprint_name_message"));

    public static final StreamCodec<FriendlyByteBuf, SetBlueprintNameMessage> STREAM_CODEC =
            StreamCodec.ofMember(SetBlueprintNameMessage::encode, SetBlueprintNameMessage::decode);

    public final String name;

    public SetBlueprintNameMessage(String name) {
        this.name = name;
    }

    @Override
    public CustomPacketPayload.Type<SetBlueprintNameMessage> type() {
        return TYPE;
    }

    public static void encode(SetBlueprintNameMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, BlueprintValidator.MAX_NAME_LENGTH * 2);
    }

    public static SetBlueprintNameMessage decode(FriendlyByteBuf buf) {
        return new SetBlueprintNameMessage(buf.readUtf(BlueprintValidator.MAX_NAME_LENGTH * 2));
    }

    public static void handle(SetBlueprintNameMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(SetBlueprintNameMessage msg, ServerPlayer player) {
        if (player == null) return;

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlankBlueprintItem)) {
            stack = player.getOffhandItem();
        }
        if (!(stack.getItem() instanceof BlankBlueprintItem)) {
            BlueprintLogger.failed(player, BlueprintLogger.Action.SET_NAME,
                    BlueprintLogger.name(msg.name), "no_blank_blueprint");
            return;
        }

        String safeName = BlueprintValidator.sanitizeName(msg.name);

        BlankBlueprintItem.setBlueprintName(stack, safeName);
        com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHands(player);
        BlueprintLogger.success(player, BlueprintLogger.Action.SET_NAME,
                BlueprintLogger.name(safeName));
    }
}
