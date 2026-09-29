package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：非 OP 玩家右键到一枚强制捕获（{@link CaptureMode#FORCE}）的蓝图，请求服务端销毁它。
 *
 * <p>FORCE 捕获跳过了全部非法方块检测与危险 NBT 净化，因此这类蓝图只允许 OP 在创造模式下使用。
 * 它出现在普通玩家手上（掉落、交易、{@code /give} 等）本身就是异常状态，直接销毁而非回收 ——
 * 换成空白蓝图等于白送一张可用蓝图。</p>
 *
 * <p>服务端<b>重新校验</b>「发送者非 OP」且「该蓝图确实是 FORCE 捕获」，不信任客户端判断 ——
 * 否则伪造的包能销毁玩家手上任意一枚正常蓝图。蓝图 dat 文件保留，OP 仍可从蓝图库取回。</p>
 */
public class C2S_DestroyForceBlueprintMessage {

    public static void encode(C2S_DestroyForceBlueprintMessage msg, FriendlyByteBuf buf) {}

    public static C2S_DestroyForceBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_DestroyForceBlueprintMessage();
    }

    public static void handle(C2S_DestroyForceBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            // OP 走的是「切创造模式」那条提示，不该发这个包；服务端再挡一道
            if (player.hasPermissions(2)) return;

            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (!isBlueprint(stack)) continue;

                String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
                if (bpId.isEmpty()) continue;

                BlueprintData data = BlueprintStorage.load(bpId);
                // FORCE 捕获或强制上传的蓝图，非 OP 持有都要销毁（服务端复核，与 saveMode 同一门槛）
                if (data == null
                        || (data.saveMode != CaptureMode.FORCE && data.uploadMode != 2)) continue;

                com.xioyim.spatialblueprint.log.BlueprintFileLogger.forceBlueprintDestroyed(
                        player, bpId, data.name);
                player.setItemInHand(hand, ItemStack.EMPTY);
                ItemSyncUtil.syncHand(player, hand);
                return;
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static boolean isBlueprint(ItemStack stack) {
        return stack.getItem() instanceof SpatialBlueprintItem
                || stack.getItem() instanceof AbstractBlueprintItem;
    }
}
