package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * 空置次元蓝图 → [重置并删除] 确认。
 *
 * <p>语义是「放弃这片结构」：删除对应的蓝图文件、清掉它的次元袋，并把手上的空置次元蓝图
 * 换成空白蓝图。次元蓝图放置后不再删文件（改为打「已放置」标记），因此必须由这里
 * 或蓝图列表的删除按钮来收尾，否则文件会永远停在已放置状态成为僵尸记录。</p>
 *
 * <p>删除后即使玩家还留着别的空置次元蓝图，也无法再回收那片结构 —— 这是不可逆操作，
 * 客户端有二次确认。</p>
 */
public class C2S_VoidResetMessage {

    public static void encode(C2S_VoidResetMessage msg, FriendlyByteBuf buf) {}

    public static C2S_VoidResetMessage decode(FriendlyByteBuf buf) {
        return new C2S_VoidResetMessage();
    }

    public static void handle(C2S_VoidResetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            InteractionHand hand = null;
            if (player.getMainHandItem().getItem() instanceof VoidDimensionalBlueprintItem) {
                hand = InteractionHand.MAIN_HAND;
            } else if (player.getOffhandItem().getItem() instanceof VoidDimensionalBlueprintItem) {
                hand = InteractionHand.OFF_HAND;
            }
            if (hand == null) return;

            ItemStack stack = player.getItemInHand(hand);

            // 存档归属：蓝图目录是所有单人存档共用的，别的存档的空置次元蓝图不得在本档删除它的文件。
            // 与 C2S_VoidRecaptureMessage 同口径。这里只拒不销毁（销毁由 C2S_DiscardForeignBlueprintMessage 负责），
            // 所以不能用带「已销毁」字样的 error.foreign_world.discarded。
            if (!com.xioyim.spatialblueprint.util.WorldIdentity.belongsToCurrentWorld(
                    VoidDimensionalBlueprintItem.getWorldUuid(stack))) {
                sendFail(player, "spatial_blueprint.gui.library.foreign_world");
                return;
            }

            // 文件已不在：查不到主人，只把物品换成空白蓝图，不动任何次元袋
            // （孤儿袋由 C2S_DiscardOrphanBagMessage 让主人自己丢弃）。
            // 必须排在归属校验前面，理由同 C2S_VoidRecaptureMessage。
            String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
            if (!bpId.isEmpty() && BlueprintStorage.findFile(bpId) != null) {
                // 归属校验（服务端兜底，右键入口已拦过一道）：只有主人能删自己的蓝图文件与次元袋。
                // 这是不可逆操作，客户端二次确认挡不住改包。
                if (!player.getUUID().toString().equals(BlueprintStorage.ownerUuidOf(bpId))) {
                    sendFail(player, "spatial_blueprint.message.void_blueprint.not_owner");
                    return;
                }
                BlueprintStorage.delete(bpId);
                MaterialSpace.get(player.serverLevel()).removeBagEverywhere(bpId);
            }

            player.setItemInHand(hand, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);

            // 列表里那条记录随之消失，推一次最新列表（H2：过滤后再推）
            List<BlueprintStorage.BlueprintSummary> updated = C2S_RequestLibraryMessage.buildFilteredSummaries(player);
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(updated));
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_OperationResult(true, ""));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendFail(ServerPlayer player, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, key));
    }
}
