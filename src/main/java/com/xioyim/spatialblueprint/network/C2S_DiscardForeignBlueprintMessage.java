package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.util.WorldIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：客户端右键到一枚<b>不属于本存档</b>的工程 / 次元蓝图，请求服务端销毁它。
 *
 * <p>蓝图存在 {@code config/spatial_blueprint/} 下，是所有单人存档共用的一个目录：
 * 玩家可以在 A 档做好一张工程蓝图，带着物品栏（或直接 {@code /give}）跑到 B 档去。
 * 但工程蓝图绑定的是固定坐标与维度，在 B 档放出来只会砸坏别人的建筑，
 * 次元蓝图同理（它的材料锁在 A 档的次元袋里）—— 所以直接销毁物品，OP 也不例外。</p>
 *
 * <p>只销毁玩家手上的物品，<b>不动磁盘上的 .dat</b> —— 那份文件是另一个存档的资产，
 * 删了会把人家好端端的蓝图一起弄没。</p>
 *
 * <p>服务端会<b>重新校验</b> {@code worldUuid} 才销毁，不信任客户端的判断 ——
 * 否则伪造的包能把玩家自己手上正常的蓝图洗掉。</p>
 */
public class C2S_DiscardForeignBlueprintMessage {

    public static void encode(C2S_DiscardForeignBlueprintMessage msg, FriendlyByteBuf buf) {}

    public static C2S_DiscardForeignBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_DiscardForeignBlueprintMessage();
    }

    public static void handle(C2S_DiscardForeignBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                String bpId = blueprintIdOf(stack);
                if (bpId == null || bpId.isEmpty()) continue;

                BlueprintData data = BlueprintStorage.load(bpId);
                if (!isForeign(data)) continue;   // 服务端校验：确实是外档蓝图才销毁

                player.setItemInHand(hand, ItemStack.EMPTY);
                ItemSyncUtil.syncHand(player, hand);
                return;
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 蓝图是否「产自别的存档且换档就失去意义」。
     *
     * <p>只有工程蓝图与次元蓝图绑定了固定坐标与维度；空间 / 投影 / 模板本来就是
     * 拿到哪儿都能放的，不受存档约束。</p>
     */
    public static boolean isForeign(BlueprintData data) {
        if (data == null) return false;
        // 子工程豁免：绝对坐标由任务集 origin 推算，不绑定存档
        if (data.parentProjectId != null && !data.parentProjectId.isEmpty()) return false;
        if (!"engineering".equals(data.category) && !"dimensional".equals(data.category)) return false;
        return !WorldIdentity.belongsToCurrentWorld(data.worldUuid);
    }

    private static String blueprintIdOf(ItemStack stack) {
        if (stack.getItem() instanceof SpatialBlueprintItem) return SpatialBlueprintItem.getBlueprintId(stack);
        if (stack.getItem() instanceof AbstractBlueprintItem) return AbstractBlueprintItem.getBlueprintId(stack);
        return null;
    }
}
