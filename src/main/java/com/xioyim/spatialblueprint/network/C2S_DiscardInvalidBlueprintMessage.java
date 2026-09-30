package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
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
 * C2S：客户端右键到一枚指向<b>已放置</b>结构的废蓝图，请求服务端把它换成空白蓝图。
 *
 * <p>玩家可能持有多份指向同一文件的次元蓝图（创造模式复制物品、{@code /give} 等）。
 * 放置其中一份后文件被标记 {@code placed}，其余几份就再也无法使用 ——
 * 留在背包里只会误导玩家，直接回收成空白蓝图。</p>
 *
 * <p>服务端会<b>重新校验</b> {@code placed} 才替换，不信任客户端的判断 ——
 * 否则伪造的包能把玩家自己手上正常的蓝图洗掉。</p>
 */
public class C2S_DiscardInvalidBlueprintMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_DiscardInvalidBlueprintMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_discard_invalid_blueprint_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_DiscardInvalidBlueprintMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_DiscardInvalidBlueprintMessage::encode, C2S_DiscardInvalidBlueprintMessage::decode);

    public static void encode(C2S_DiscardInvalidBlueprintMessage msg, FriendlyByteBuf buf) {}

    public static C2S_DiscardInvalidBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_DiscardInvalidBlueprintMessage();
    }

    @Override
    public CustomPacketPayload.Type<C2S_DiscardInvalidBlueprintMessage> type() {
        return TYPE;
    }

    public static void handle(C2S_DiscardInvalidBlueprintMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_DiscardInvalidBlueprintMessage msg, ServerPlayer player) {
        if (player == null) return;

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof SpatialBlueprintItem)) continue;

            String bpId = SpatialBlueprintItem.getBlueprintId(stack);
            if (bpId == null || bpId.isEmpty()) continue;

            BlueprintData data = BlueprintStorage.load(bpId);
            if (data == null || !data.placed) continue;  // 服务端校验：确实已放置才回收

            player.setItemInHand(hand, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            ItemSyncUtil.syncHand(player, hand);
            return;
        }
    }
}
