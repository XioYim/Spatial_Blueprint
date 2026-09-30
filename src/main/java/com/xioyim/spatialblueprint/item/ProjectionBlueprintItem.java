package com.xioyim.spatialblueprint.item;

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

import java.util.List;

/**
 * 投影蓝图：记录区域内所有方块的完整数据及材料清单，不执行放置操作。
 *
 * <p>数据存法：{@code blueprint_id} 组件只存文件引用（D1），
 * 材料清单内嵌于 {@code BlueprintData.materialList}，随蓝图文件一起序列化，
 * 没有单独的材料清单文件。</p>
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
            return SpatialBlueprintItem.applySaveModeColor(stack, Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(name));
        }
        return SpatialBlueprintItem.applySaveModeColor(stack, super.getName(stack));
    }

    // ── 右键交互：打开放置界面 ────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 蓝图一律只认主手 —— 副手要留给「主手空白蓝图 + 副手项目蓝图」这类组合用法
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
        ItemStack stack = player.getItemInHand(hand);
        // 服务端权威：蓝图文件被删 / 归档后，这枚物品就是个指向空气的引用，就地回收
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (com.xioyim.spatialblueprint.util.OrphanBlueprintGuard.discardIfFileMissing(sp, hand)) {
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
        }
        if (level.isClientSide) {
            openPlacementScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!context.getLevel().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && com.xioyim.spatialblueprint.util.OrphanBlueprintGuard.discardIfFileMissing(sp, context.getHand())) {
            return InteractionResult.FAIL;
        }
        if (context.getLevel().isClientSide) {
            openPlacementScreen(player, context.getItemInHand());
        }
        return InteractionResult.SUCCESS;
    }

    private static void openPlacementScreen(Player player, ItemStack stack) {
        // SB-TODO(P6): 客户端打开放置界面（ForceBlueprintGuard 拦截、PlacementScreen）
    }

    // ── Tooltip ───────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        // 材料数量（内嵌于 BlueprintData.materialList）
        MaterialList ml = getMaterialListForTooltip(stack);
        if (ml != null) {
            int kinds  = ml.blockCounts.size();
            int total  = ml.totalCount();
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.projection_blueprint.tooltip",
                    kinds, total).withStyle(ChatFormatting.GRAY));
        }
    }

    // ── 组件辅助（共用方法继承自 AbstractBlueprintItem）────────────────────
    // getBlueprintName / setBlueprintName / getBlueprintId / setBlueprintId /
    // getBlueprintDataServer / getMaterialListServer / getMaterialListClient /
    // getMaterialListForTooltip


}
