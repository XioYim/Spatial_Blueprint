package com.xioyim.spatialblueprint.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class BlankBlueprintItem extends Item {
    public BlankBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        LegacyItemMigration.migrate(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 空白蓝图必须在主手 —— 副手位置留给项目蓝图（决定这次创建的是独立工程还是子工程）
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            openSelectionScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) {
            openSelectionScreen(player, stack);
        }
        return InteractionResult.SUCCESS;
    }

    private static void openSelectionScreen(Player player, ItemStack stack) {
        // SB-TODO(P6): 客户端打开点选界面（BlueprintSelectionRenderer 非法扫描缓存失效、未加载区块横幅、PointSelectionScreen）
    }

    // --- Point selection ---

    public static boolean hasPoint1(ItemStack stack) {
        return stack.has(SpatialBlueprintComponents.POINT1.get());
    }

    public static boolean hasPoint2(ItemStack stack) {
        return stack.has(SpatialBlueprintComponents.POINT2.get());
    }

    public static BlockPos getPoint1(ItemStack stack) {
        return stack.get(SpatialBlueprintComponents.POINT1.get());
    }

    public static BlockPos getPoint2(ItemStack stack) {
        return stack.get(SpatialBlueprintComponents.POINT2.get());
    }

    public static void setPoint1(ItemStack stack, BlockPos pos) {
        // immutable()：调用方可能传 MutableBlockPos，存进组件后被改就是静默串值
        stack.set(SpatialBlueprintComponents.POINT1.get(), pos.immutable());
    }

    public static void setPoint2(ItemStack stack, BlockPos pos) {
        stack.set(SpatialBlueprintComponents.POINT2.get(), pos.immutable());
    }

    public static void clearPoints(ItemStack stack) {
        stack.remove(SpatialBlueprintComponents.POINT1.get());
        stack.remove(SpatialBlueprintComponents.POINT2.get());
    }

    public static String getBlueprintName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "");
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), name);
    }
}
