package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

/**
 * 孤儿蓝图回收：蓝图物品指向的 {@code .litematic} 文件已经不在了。
 *
 * <p>管理员停服后手动删掉、或 OP 用 ✖ 归档成 {@code .bak} 之后，玩家背包里那枚蓝图
 * 就成了指向空气的引用。原来右键它只会打开一个永远下载失败的界面（甚至误报成
 * 「该蓝图的结构已被放置」），玩家完全看不出发生了什么 —— 直接换成空白蓝图并说清原因，
 * 物品不白丢。</p>
 *
 * <p>判定在服务端做：只有服务端看得到磁盘上的文件。</p>
 */
public final class OrphanBlueprintGuard {

    private OrphanBlueprintGuard() {}

    /**
     * 检查玩家该只手上的蓝图物品是否已经失去对应文件；是则换成空白蓝图并弹提示。
     *
     * @return {@code true} 表示已回收，调用方应当立即结束本次右键
     */
    public static boolean discardIfFileMissing(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String bpId = blueprintIdOf(stack);
        // 没有 blueprintId 的是空白蓝图 / 未绑定的工程蓝图，本来就没有文件可言
        if (bpId == null || bpId.isEmpty()) return false;
        if (BlueprintStorage.findFile(bpId) != null) return false;

        player.setItemInHand(hand, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
        ItemSyncUtil.syncHand(player, hand);
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, "spatial_blueprint.error.blueprint_file_missing"));
        return true;
    }

    private static String blueprintIdOf(ItemStack stack) {
        if (stack.getItem() instanceof SpatialBlueprintItem) return SpatialBlueprintItem.getBlueprintId(stack);
        if (stack.getItem() instanceof AbstractBlueprintItem) return AbstractBlueprintItem.getBlueprintId(stack);
        return null;
    }
}
