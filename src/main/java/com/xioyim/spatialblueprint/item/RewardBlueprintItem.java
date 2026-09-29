package com.xioyim.spatialblueprint.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 奖励蓝图 —— 投影蓝图的特殊变体，由 OP 在检测报告里「(OP)奖励蓝图」按钮强制创建。
 *
 * <p>用途是<b>分发给玩家当奖励</b>，因此与普通投影蓝图有三点不同：</p>
 * <ul>
 *   <li><b>耗材可选</b>：创建时清单为空（默认免费），OP 可事后在放置界面自定义耗材，有耗材则构建时从材料空间扣除。</li>
 *   <li><b>用后销毁</b>：与空间蓝图一致，放置一次就没了 —— 投影蓝图是可以反复用的，
 *       奖励要是也能反复用，发一张等于发无限张。</li>
 *   <li><b>带附魔光效</b>：和次元蓝图同款做法，让它在背包里一眼能认出来。</li>
 * </ul>
 *
 * <p>创建路径是 FORCE（OP 强制，不受任何限制、完整捕获），所以内容里可能有危险方块 ——
 * 这一点在创建按钮的提示里写明了，分发前需要自己心里有数。</p>
 */
public class RewardBlueprintItem extends ProjectionBlueprintItem {

    public RewardBlueprintItem(Properties properties) {
        super(properties);
    }

    /** 背包里一眼可辨：奖励蓝图带附魔光效（与次元蓝图同款做法）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public Component getName(ItemStack stack) {
        String custom = getBlueprintName(stack);
        Component base = custom.isEmpty()
                ? Component.translatable(getDescriptionId())
                : Component.translatable(getDescriptionId()).append(" - ").append(custom);
        return base.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

}
