package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.gui.PlacementScreen;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 投影蓝图：记录区域内所有方块的完整数据及材料清单，不执行放置操作。
 *
 * <p>NBT 结构：
 * <ul>
 *   <li>{@code blueprintName}  — 蓝图显示名称</li>
 *   <li>{@code blueprintId}    — 蓝图文件 UUID（{@code <uuid>.litematic}）</li>
 *   <li>{@code blueprintData}  — 内联蓝图数据（用于多人客户端无法访问服务端文件的情况）</li>
 * </ul>
 * 材料清单内嵌于 {@code BlueprintData.materialList}，随蓝图文件一起序列化，
 * 无需额外 NBT key，也没有单独的材料清单文件。</p>
 */
public class ProjectionBlueprintItem extends AbstractBlueprintItem {

    public ProjectionBlueprintItem(Properties properties) {
        super(properties);
    }

    // ── 物品名称：有蓝图名时显示蓝图名，否则使用翻译键"投影蓝图" ──────────

    @Override
    public Component getName(ItemStack stack) {
        String name = getBlueprintName(stack);
        if (!name.isEmpty()) {
            return Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(name);
        }
        return super.getName(stack);
    }

    // ── 右键交互：打开放置界面 ────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            openPlacementScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) {
            openPlacementScreen(player, context.getItemInHand());
        }
        return InteractionResult.SUCCESS;
    }

    @OnlyIn(Dist.CLIENT)
    private static void openPlacementScreen(Player player, ItemStack stack) {
        BlueprintData data = getBlueprintData(stack);
        // data==null 时也允许进入界面，PlacementScreen 内部会显示下载按钮并请求服务端数据
        net.minecraft.client.Minecraft.getInstance().setScreen(new PlacementScreen(player, stack, data));
    }

    // ── Tooltip ───────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        // 材料数量（内嵌于 BlueprintData.materialList）
        MaterialList ml = getMaterialList(stack);
        if (ml != null) {
            int kinds  = ml.blockCounts.size();
            int total  = ml.totalCount();
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.projection_blueprint.tooltip",
                    kinds, total).withStyle(ChatFormatting.GRAY));
        }
    }

    // ── NBT 辅助（共用方法继承自 AbstractBlueprintItem）────────────────────
    // getBlueprintName / setBlueprintName / getBlueprintId / setBlueprintId /
    // getBlueprintData / getMaterialList / getDepositedMaterials / getDepositedCount /
    // setDepositedCount / clearDepositedMaterials / hasSufficientMaterials /
    // getWithdrawCount / setWithdrawCount / incrementWithdrawCount /
    // getCustomWithdrawXp / setCustomWithdrawXp / getCustomWithdrawLimit / setCustomWithdrawLimit


}
