package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetBlueprintNameMessage {
    public final String name;

    public SetBlueprintNameMessage(String name) {
        this.name = name;
    }

    public static void encode(SetBlueprintNameMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, BlueprintValidator.MAX_NAME_LENGTH * 2);
    }

    public static SetBlueprintNameMessage decode(FriendlyByteBuf buf) {
        return new SetBlueprintNameMessage(buf.readUtf(BlueprintValidator.MAX_NAME_LENGTH * 2));
    }

    public static void handle(SetBlueprintNameMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
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
        });
        ctx.get().setPacketHandled(true);
    }
}
