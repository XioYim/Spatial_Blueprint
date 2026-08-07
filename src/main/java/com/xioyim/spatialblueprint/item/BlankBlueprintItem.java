package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.gui.PointSelectionScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
public class BlankBlueprintItem extends Item {
    public BlankBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            openSelectionScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) {
            openSelectionScreen(player, stack);
        }
        return InteractionResult.SUCCESS;
    }

    @OnlyIn(Dist.CLIENT)
    private static void openSelectionScreen(Player player, ItemStack stack) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new PointSelectionScreen(player));
    }

    // --- Point selection ---

    public static boolean hasPoint1(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("point1");
    }

    public static boolean hasPoint2(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("point2");
    }

    public static BlockPos getPoint1(ItemStack stack) {
        if (!hasPoint1(stack)) return null;
        CompoundTag tag = stack.getTag().getCompound("point1");
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    public static BlockPos getPoint2(ItemStack stack) {
        if (!hasPoint2(stack)) return null;
        CompoundTag tag = stack.getTag().getCompound("point2");
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    public static void setPoint1(ItemStack stack, BlockPos pos) {
        CompoundTag pointTag = new CompoundTag();
        pointTag.putInt("x", pos.getX());
        pointTag.putInt("y", pos.getY());
        pointTag.putInt("z", pos.getZ());
        stack.getOrCreateTag().put("point1", pointTag);
    }

    public static void setPoint2(ItemStack stack, BlockPos pos) {
        CompoundTag pointTag = new CompoundTag();
        pointTag.putInt("x", pos.getX());
        pointTag.putInt("y", pos.getY());
        pointTag.putInt("z", pos.getZ());
        stack.getOrCreateTag().put("point2", pointTag);
    }

    public static void clearPoints(ItemStack stack) {
        if (stack.hasTag()) {
            stack.getTag().remove("point1");
            stack.getTag().remove("point2");
        }
    }

    public static String getBlueprintName(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("blueprintName")) {
            return stack.getTag().getString("blueprintName");
        }
        return "";
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString("blueprintName", name);
    }
}
