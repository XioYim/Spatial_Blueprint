package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
public class C2S_DestroyForceBlueprintMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_DestroyForceBlueprintMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_destroy_force_blueprint_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_DestroyForceBlueprintMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_DestroyForceBlueprintMessage::encode, C2S_DestroyForceBlueprintMessage::decode);

    @Override
    public CustomPacketPayload.Type<C2S_DestroyForceBlueprintMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_DestroyForceBlueprintMessage msg, FriendlyByteBuf buf) {}

    public static C2S_DestroyForceBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_DestroyForceBlueprintMessage();
    }

    public static void handle(C2S_DestroyForceBlueprintMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_DestroyForceBlueprintMessage msg, ServerPlayer player) {
        if (player == null) return;
        // OP 走的是「切创造模式」那条提示，不该发这个包；服务端再挡一道
        if (player.hasPermissions(2)) return;

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!isBlueprint(stack)) continue;

            // 1.20.1 的 stack.getTag().getString("blueprintId") 缺键得 ""；访问器缺组件返回 null，照原逻辑补 ""
            String bpId = blueprintIdOf(stack);
            if (bpId == null || bpId.isEmpty()) continue;

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
    }

    private static String blueprintIdOf(ItemStack stack) {
        if (stack.getItem() instanceof SpatialBlueprintItem) return SpatialBlueprintItem.getBlueprintId(stack);
        if (stack.getItem() instanceof AbstractBlueprintItem) return AbstractBlueprintItem.getBlueprintId(stack);
        return null;
    }

    private static boolean isBlueprint(ItemStack stack) {
        return stack.getItem() instanceof SpatialBlueprintItem
                || stack.getItem() instanceof AbstractBlueprintItem;
    }
}
