package com.xioyim.spatialblueprint.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 项目蓝图：纯显示 / 总览道具，不放置任何方块。
 *
 * <p>NBT：
 * <ul>
 *   <li>{@code projectId} — 项目 UUID 字符串，空表示未绑定（草稿空白状态）</li>
 * </ul>
 * </p>
 */
public class ProjectBlueprintItem extends Item {

    public ProjectBlueprintItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    // ── 右键：打开项目界面（Client 侧，GUI 后续实现）──────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            openProjectScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openProjectScreen(Player player, ItemStack stack) {
        // TODO: implement ProjectBlueprintScreen and open it here
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        String id = getProjectId(stack);
        if (id == null) {
            tooltip.add(Component.translatable("spatial_blueprint.item.project_blueprint.tooltip.empty")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("spatial_blueprint.item.project_blueprint.tooltip.id", id)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    // ── NBT 访问器 ────────────────────────────────────────────────────────────

    @Nullable
    public static String getProjectId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("projectId")) return null;
        String id = tag.getString("projectId");
        return id.isEmpty() ? null : id;
    }

    public static void setProjectId(ItemStack stack, UUID projectId) {
        stack.getOrCreateTag().putString("projectId", projectId.toString());
    }

    /** 道具名称：有项目名时显示项目名，否则显示默认名。 */
    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("projectName")) {
            String pname = tag.getString("projectName");
            if (!pname.isEmpty()) return Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(pname);
        }
        return super.getName(stack);
    }

    public static void setProjectName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString("projectName", name);
    }
}
