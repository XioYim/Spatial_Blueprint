package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：玩家在次元蓝图材料空间里丢弃一个「蓝图已丢失」的孤儿袋。
 *
 * <p>对应蓝图文件已被删除或归档，这袋材料再也没有任何东西能消耗它，只会永久占着空间。
 * 删不删由玩家自己决定 —— 万一是误删，管理员还能把 {@code .bak} 恢复回来，
 * 所以不做自动清理。<b>材料永久消失，不返还。</b></p>
 *
 * <p>服务端<b>重新校验</b>「蓝图文件确实不存在」才允许丢弃，不信任客户端判断 ——
 * 否则伪造的包能把玩家（或别人）正常在用的次元袋清掉。玩家只能操作自己的袋子。</p>
 */
public class C2S_DiscardOrphanBagMessage {

    private final String blueprintId;

    public C2S_DiscardOrphanBagMessage(String blueprintId) {
        this.blueprintId = blueprintId == null ? "" : blueprintId;
    }

    public static void encode(C2S_DiscardOrphanBagMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_DiscardOrphanBagMessage decode(FriendlyByteBuf buf) {
        return new C2S_DiscardOrphanBagMessage(buf.readUtf(64));
    }

    public static void handle(C2S_DiscardOrphanBagMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || msg.blueprintId.isEmpty()) return;

            // 只允许丢弃真正的孤儿袋：蓝图文件还在的话，这袋材料仍然有用
            if (BlueprintStorage.findFile(msg.blueprintId) != null) return;

            // 只能删自己的袋子（不接收目标玩家参数，直接用发送者 UUID）
            MaterialSpace space = MaterialSpace.get(player.serverLevel());
            // 名字与件数要在删之前取，删完就查不到了
            String bagName  = space.bagName(msg.blueprintId);
            int    bagItems = space.bagTotal(player.getUUID(), msg.blueprintId);
            if (!space.discardBag(player.getUUID(), msg.blueprintId)) return;

            // 材料永久消失，玩家事后申诉时管理员需要凭据
            com.xioyim.spatialblueprint.log.BlueprintFileLogger.discardOrphanBag(
                    player, msg.blueprintId, bagName, bagItems);

            // 回推最新列表，界面立即少掉这一行
            C2S_RequestVoidBagsMessage.sendVoidBags(
                    player, player.getUUID(), player.getScoreboardName());
        });
        ctx.get().setPacketHandled(true);
    }
}
