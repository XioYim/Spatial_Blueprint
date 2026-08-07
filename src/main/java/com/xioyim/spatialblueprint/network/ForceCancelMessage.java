package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.BuildBlueprintItem;
import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Sent by the client when the auto-cancel distance threshold is exceeded
 * (or the user presses Cancel in PlacementScreen).
 * The server replaces the SpatialBlueprint with a BuildBlueprint (10 s cooldown).
 */
public class ForceCancelMessage {

    public ForceCancelMessage() {}

    public static void encode(ForceCancelMessage msg, FriendlyByteBuf buf) {
        // no payload
    }

    public static ForceCancelMessage decode(FriendlyByteBuf buf) {
        return new ForceCancelMessage();
    }

    public static void handle(ForceCancelMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack stack = null;
            InteractionHand hand = null;
            boolean isDimensional = false;
            ItemStack mainHand = player.getMainHandItem();
            // DimensionalBlueprintItem 须先于 SpatialBlueprintItem 检测（前者是后者的子类）
            if (mainHand.getItem() instanceof DimensionalBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND; isDimensional = true;
            } else if (mainHand.getItem() instanceof SpatialBlueprintItem) {
                stack = mainHand; hand = InteractionHand.MAIN_HAND;
            } else {
                ItemStack offHand = player.getOffhandItem();
                if (offHand.getItem() instanceof DimensionalBlueprintItem) {
                    stack = offHand; hand = InteractionHand.OFF_HAND; isDimensional = true;
                } else if (offHand.getItem() instanceof SpatialBlueprintItem) {
                    stack = offHand; hand = InteractionHand.OFF_HAND;
                }
            }

            if (stack == null) {
                // Blueprint already gone — nothing to do.
                return;
            }

            BlueprintData data = SpatialBlueprintItem.getBlueprintData(stack);
            String name = (data != null) ? data.name : "unknown";

            // Replace with BuildBlueprint (cooldown), mirroring PlaceBlueprintMessage.
            ItemStack buildStack = new ItemStack(SpatialBlueprintItems.BUILD_BLUEPRINT.get());
            CompoundTag buildNbt = new CompoundTag();
            buildNbt.putString("blueprintName", name);
            buildNbt.putBoolean("forceCancelled", true);
            if (stack.hasTag()) {
                CompoundTag src = stack.getTag();
                if (src.contains("blueprintId")) buildNbt.putString("blueprintId", src.getString("blueprintId"));
                if (src.contains("blueprintData")) buildNbt.put("blueprintData", src.getCompound("blueprintData").copy());
            }
            // 次元蓝图强制取消：dat 文件不删除，冷却后返回次元蓝图本身
            if (isDimensional) buildNbt.putString("sourceType", "dimensional");
            // 强制取消时沿用蓝图记录的动画时长计算冷却，若无则使用配置默认值
            int animDurCancel = com.xioyim.spatialblueprint.item.SpatialBlueprintItem.getAnimDuration(stack, 8);
            buildNbt.putLong("completionTime",
                    player.serverLevel().getGameTime() + BuildBlueprintItem.buildTicks(animDurCancel));
            buildStack.setTag(buildNbt);

            player.setItemInHand(hand, buildStack);
            ItemSyncUtil.syncHand(player, hand);

            BlueprintLogger.success(player, BlueprintLogger.Action.UPDATE_SETTINGS,
                    "force_cancel → BuildBlueprint name=" + name);
        });
        ctx.get().setPacketHandled(true);
    }
}
