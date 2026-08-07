package com.xioyim.spatialblueprint.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
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
 * 空置次元蓝图 — 次元蓝图放置（生存模式）冷却后返还的物品。
 * 记录原次元蓝图的捕捉坐标和名称，供玩家在原区域重新储存。
 * 不含附魔光效，不加入创造模式标签页。
 */
public class VoidDimensionalBlueprintItem extends Item {

    public VoidDimensionalBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        String bpName = getBlueprintName(stack);
        if (!bpName.isEmpty()) {
            return Component.literal("[" + bpName + "]").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" - ").withStyle(ChatFormatting.WHITE))
                    .append(Component.translatable("spatial_blueprint.item.void_dimensional_blueprint.base_name")
                            .withStyle(ChatFormatting.WHITE));
        }
        return Component.translatable("spatial_blueprint.item.void_dimensional_blueprint.base_name")
                .withStyle(ChatFormatting.WHITE);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            openScreen(player.getItemInHand(hand));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) {
            openScreen(context.getItemInHand());
        }
        return InteractionResult.SUCCESS;
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(ItemStack stack) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.xioyim.spatialblueprint.gui.VoidDimensionalScreen(stack));
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        BlockPos min = getCaptureMin(stack);
        BlockPos max = getCaptureMax(stack);
        if (min != null && max != null) {
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.void_dimensional_blueprint.tooltip.min",
                    min.getX(), min.getY(), min.getZ()).withStyle(ChatFormatting.DARK_AQUA));
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.void_dimensional_blueprint.tooltip.max",
                    max.getX(), max.getY(), max.getZ()).withStyle(ChatFormatting.DARK_AQUA));
        } else {
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.void_dimensional_blueprint.tooltip.empty")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        String creator = getCreatorName(stack);
        if (!creator.isEmpty()) {
            tooltip.add(Component.translatable("spatial_blueprint.item.dimensional_blueprint.tooltip.creator", creator).withStyle(ChatFormatting.GRAY));
        }
    }

    // ── NBT helpers ──────────────────────────────────────────────────────────

    public static String getBlueprintName(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("blueprintName")
                ? stack.getTag().getString("blueprintName") : "";
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString("blueprintName", name);
    }

    @Nullable
    public static BlockPos getCaptureMin(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains("captureMin")) return null;
        return NbtUtils.readBlockPos(stack.getTag().getCompound("captureMin"));
    }

    @Nullable
    public static BlockPos getCaptureMax(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains("captureMax")) return null;
        return NbtUtils.readBlockPos(stack.getTag().getCompound("captureMax"));
    }

    public static String getCreatorName(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("creatorName")
                ? stack.getTag().getString("creatorName") : "";
    }

    public static void setCreatorName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString("creatorName", name);
    }

    public static void setCaptureRange(ItemStack stack, BlockPos min, BlockPos max) {
        stack.getOrCreateTag().put("captureMin", NbtUtils.writeBlockPos(min));
        stack.getOrCreateTag().put("captureMax", NbtUtils.writeBlockPos(max));
    }

    public static boolean hasTpCoord(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("tpDimension");
    }

    public static double getTpX(ItemStack stack) { return stack.hasTag() ? stack.getTag().getDouble("tpX") : 0; }
    public static double getTpY(ItemStack stack) { return stack.hasTag() ? stack.getTag().getDouble("tpY") : 0; }
    public static double getTpZ(ItemStack stack) { return stack.hasTag() ? stack.getTag().getDouble("tpZ") : 0; }

    public static String getTpDimension(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getString("tpDimension") : "";
    }
}
