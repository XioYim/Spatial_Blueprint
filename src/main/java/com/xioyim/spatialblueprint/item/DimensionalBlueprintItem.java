package com.xioyim.spatialblueprint.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 次元蓝图 — 行为与空间蓝图相同（打开放置界面），但继承储存蓝图的单次性：
 * <ul>
 *   <li>放置时：始终删除 dat 文件。</li>
 *   <li>创造模式：物品保留（无冷却），可无限放置（dat 被删后仍有内联 NBT）。</li>
 *   <li>生存模式：转为 BuildBlueprint，冷却结束后返回空白蓝图。</li>
 *   <li>强制取消：不删 dat，冷却结束后返回次元蓝图本身。</li>
 * </ul>
 */
public class DimensionalBlueprintItem extends SpatialBlueprintItem {

    public DimensionalBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public Component getName(ItemStack stack) {
        String customName = getBlueprintName(stack);
        if (!customName.isEmpty()) {
            return applySaveModeColor(stack, Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(customName));
        }
        return applySaveModeColor(stack, super.getName(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        String creator = getCreatorName(stack);
        if (!creator.isEmpty()) {
            tooltip.add(Component.translatable("spatial_blueprint.item.dimensional_blueprint.tooltip.creator", creator).withStyle(ChatFormatting.GRAY));
        }
    }

    public static String getCreatorName(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("creatorName")
                ? stack.getTag().getString("creatorName") : "";
    }
}
