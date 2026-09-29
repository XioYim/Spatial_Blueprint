package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.gui.ErrorToastScreen;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_DestroyForceBlueprintMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.FluidDisplayMode;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * 强制捕获（{@link CaptureMode#FORCE}）蓝图的使用门槛 —— 所有右键入口共用。
 *
 * <p>FORCE 捕获跳过了非法方块检测与 NBT 净化，只有 <b>OP + 创造模式</b>能用：</p>
 *
 * <table>
 *   <tr><th>玩家</th><th>行为</th></tr>
 *   <tr><td>非 OP</td><td>拦截 + 横幅提示 + 请求服务端销毁该蓝图</td></tr>
 *   <tr><td>OP，非创造</td><td>拦截 + 横幅提示，不销毁（切到创造即可用）</td></tr>
 *   <tr><td>OP + 创造</td><td>放行</td></tr>
 * </table>
 *
 * <p>这里只是客户端的即时反馈；防改包的底线在服务端放置入口。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ForceBlueprintGuard {

    private ForceBlueprintGuard() {}

    /**
     * 检查是否应拦截这次右键。
     *
     * <p>{@code data} 为 {@code null}（数据尚未从服务端下载）时一律放行 —— 此时无从判断
     * {@code saveMode}。界面打开后拿到数据仍会按同样规则处理。</p>
     *
     * @return {@code true} 表示已拦截并给出提示，调用方必须立即 return，不要打开界面
     */
    public static boolean intercept(Player player, ItemStack stack, @Nullable BlueprintData data) {
        if (data == null) return false;
        // 受限制 = FORCE 捕获 或 强制上传 —— 两者都跳过了净化，只有 OP + 创造能用
        boolean restricted = data.saveMode == CaptureMode.FORCE || data.uploadMode == 2;
        if (!restricted) return false;

        // 奖励蓝图是 FORCE 捕获的，但它的用途是分发给所有玩家，不受 OP 限制
        if (stack.getItem() instanceof com.xioyim.spatialblueprint.item.RewardBlueprintItem) return false;

        if (!player.hasPermissions(2)) {
            NotifySound.clientError();
            resetRenderState(stack);
            BlueprintNetwork.CHANNEL.sendToServer(new C2S_DestroyForceBlueprintMessage());
            ErrorToastScreen.showExclusive(Component.translatable(
                    "spatial_blueprint.message.force_blueprint.no_permission"));
            return true;
        }

        if (!player.isCreative()) {
            NotifySound.clientError();
            ErrorToastScreen.showExclusive(Component.translatable(
                    "spatial_blueprint.message.force_blueprint.need_creative"));
            return true;
        }

        return false;
    }

    /**
     * 存档归属检查：工程 / 次元蓝图不属于当前存档时直接销毁，OP 也不例外。
     *
     * <p>蓝图存在 {@code config/spatial_blueprint/} 下，是所有单人存档共用的一个目录，
     * 玩家完全可以带着物品栏（或 {@code /give}）把 A 档的工程蓝图拿到 B 档来。
     * 但工程蓝图绑定的是固定坐标与维度，在 B 档放出来只会砸坏别人的建筑；
     * 次元蓝图同理 —— 它的材料锁在 A 档的次元袋里，这里根本取不出来。</p>
     *
     * <p>只销毁玩家手上的物品，<b>不动磁盘上的 .dat</b> —— 那是另一个存档的资产。
     * 真正的销毁由服务端复核 {@code worldUuid} 后执行。</p>
     *
     * @return {@code true} 表示已拦截并给出提示，调用方必须立即 return
     */
    public static boolean interceptForeignWorld(Player player, ItemStack stack,
                                                @Nullable BlueprintData data) {
        if (data == null) return false;
        // 子工程的绝对坐标是从任务集 origin 算出来的，本来就不绑定存档 —— 豁免
        if (data.parentProjectId != null && !data.parentProjectId.isEmpty()) return false;
        if (!"engineering".equals(data.category) && !"dimensional".equals(data.category)) return false;
        if (ClientWorldIdentity.belongsToCurrentWorld(data.worldUuid)) return false;

        NotifySound.clientError();
        resetRenderState(stack);
        String bpId = blueprintIdOf(stack);
        if (bpId != null) ClientBlueprintCache.remove(bpId);
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_DiscardForeignBlueprintMessage());
        ErrorToastScreen.showExclusive(Component.translatable(
                "spatial_blueprint.message.foreign_world.blocked",
                data.worldName.isEmpty() ? "?" : data.worldName));
        return true;
    }

    private static String blueprintIdOf(ItemStack stack) {
        if (stack.getItem() instanceof com.xioyim.spatialblueprint.item.SpatialBlueprintItem) {
            return com.xioyim.spatialblueprint.item.SpatialBlueprintItem.getBlueprintId(stack);
        }
        if (stack.getItem() instanceof com.xioyim.spatialblueprint.item.AbstractBlueprintItem) {
            return com.xioyim.spatialblueprint.item.AbstractBlueprintItem.getBlueprintId(stack);
        }
        return null;
    }

    /**
     * 次元蓝图归属检查：任何人（含 OP）都不能用别人的。
     *
     * <p>与 {@link #intercept} 分开是因为触发条件不同 —— FORCE 看 {@code saveMode}，
     * 归属看 {@code ownerUuid}；一枚蓝图可能同时命中，调用方先判 FORCE。</p>
     *
     * <p>只拦截、不销毁物品：蓝图本身是合法的，只是不属于当前玩家。</p>
     *
     * @return {@code true} 表示已拦截并给出提示，调用方必须立即 return
     */
    public static boolean interceptNotOwner(Player player, ItemStack stack,
                                            @Nullable BlueprintData data) {
        if (data == null || !"dimensional".equals(data.category)) return false;
        if (data.isOwnedBy(player.getUUID())) return false;
        NotifySound.clientError();
        ErrorToastScreen.showExclusive(Component.translatable(
                "spatial_blueprint.message.void_blueprint.not_owner"));
        return true;
    }

    /**
     * 蓝图马上要被销毁，先把它留下的预览状态清干净 ——
     * 否则物品没了，世界里那圈预览框还会继续渲染，且没有任何东西能再关掉它。
     */
    private static void resetRenderState(ItemStack stack) {
        BlueprintSelectionRenderer.setAlignMode(stack, BlueprintSelectionRenderer.AlignMode.OFF);
        BlueprintSelectionRenderer.hideTopN = 0;
        BlueprintSelectionRenderer.hiddenTopBlocks.clear();
        BlueprintSelectionRenderer.fluidMode = FluidDisplayMode.SHOW_ALL;
    }
}
