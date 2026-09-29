package com.xioyim.spatialblueprint.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
 * 空置次元蓝图 — 次元蓝图放置（生存模式）冷却后返还的物品。
 * 记录原次元蓝图的捕捉坐标和名称，供玩家在原区域重新储存。
 * 不含附魔光效，不加入创造模式标签页。
 */
public class VoidDimensionalBlueprintItem extends Item {

    public VoidDimensionalBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        LegacyItemMigration.migrate(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        String bpName = getBlueprintName(stack);
        if (!bpName.isEmpty()) {
            return SpatialBlueprintItem.applySaveModeColor(stack,
                    Component.literal("[" + bpName + "] - ")
                            .append(Component.translatable(
                                    "spatial_blueprint.item.void_dimensional_blueprint.base_name")));
        }
        return SpatialBlueprintItem.applySaveModeColor(stack,
                Component.translatable("spatial_blueprint.item.void_dimensional_blueprint.base_name"));
    }

    // 右键一律由【服务端】决定：文件还在才让客户端开界面，已失效则直接换成空白蓝图。
    //
    // 早先的写法是客户端自己 openScreen()、服务端并行判定，结果失效时界面已经开出来了，
    // 提示横幅只是盖在上面 —— 关掉横幅就露出本该被拦截的界面。

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 蓝图一律只认主手 —— 副手要留给「主手空白蓝图 + 副手项目蓝图」这类组合用法
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (!level.isClientSide) {
            handleServerUse(player, hand);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getHand() != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Player player = context.getPlayer();
        if (!context.getLevel().isClientSide && player != null) {
            handleServerUse(player, context.getHand());
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 服务端处理右键：校验该空置次元蓝图指向的蓝图文件是否仍然存在。
     *
     * <p>文件被 OP 清理（或「重置并删除」）后，这枚空置蓝图永远无法再回收结构，
     * 留在背包里只会误导玩家 —— 直接换成空白蓝图，与「重置」的处理一致，物品不白丢。
     * 没有 {@code blueprint_id} 的旧物品同样按失效处理。</p>
     *
     * <p>文件有效时才发指令让客户端打开界面。</p>
     */
    private static void handleServerUse(Player player, InteractionHand hand) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        ItemStack stack = player.getItemInHand(hand);
        String bpId = stack.getOrDefault(SpatialBlueprintComponents.BLUEPRINT_ID.get(), "");

        boolean valid = !bpId.isEmpty()
                && com.xioyim.spatialblueprint.util.BlueprintStorage.findFile(bpId) != null;

        if (!valid) {
            player.setItemInHand(hand, new ItemStack(SpatialBlueprintItems.BLANK_BLUEPRINT.get()));
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(sp, hand);
            // SB-TODO(P3): S2C_OperationResult（空置蓝图已失效提示）
            return;
        }

        // ── 存档归属校验：不属于本档的空置次元蓝图直接销毁，OP 也不例外 ────
        // 它记录的是「结构放在世界里的哪个坐标」，换个存档那串坐标指向的是完全不相干的地方；
        // 更要命的是「重新储存」会把本档的方块覆盖写进另一个存档的蓝图文件里。
        if (!com.xioyim.spatialblueprint.util.WorldIdentity.belongsToCurrentWorld(getWorldUuid(stack))) {
            player.setItemInHand(hand, ItemStack.EMPTY);
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(sp, hand);
            // SB-TODO(P3): S2C_OperationResult（不属于本存档，已销毁提示）
            return;
        }

        // ── 归属校验：不能用别人的空置次元蓝图 ────────────────────────────
        // 「重新储存」会覆盖写回原蓝图文件，别人拿到这枚物品就等于能篡改他人的蓝图。
        // 这里只拦截、不换空白蓝图 —— 物品本身对它真正的主人仍然有效。
        String owner = com.xioyim.spatialblueprint.util.BlueprintStorage.ownerUuidOf(bpId);
        if (!sp.getUUID().toString().equals(owner)) {
            // SB-TODO(P3): S2C_OperationResult（不是主人提示）
            return;
        }

        // SB-TODO(P3): S2C_OpenVoidScreenMessage（文件有效，让客户端打开空置次元蓝图界面）
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
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

    // ── 组件辅助 ──────────────────────────────────────────────────────────────

    public static String getBlueprintName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "");
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), name);
    }

    @javax.annotation.Nullable
    public static BlockPos getCaptureMin(ItemStack stack) {
        return stack.get(SpatialBlueprintComponents.CAPTURE_MIN.get());
    }

    @javax.annotation.Nullable
    public static BlockPos getCaptureMax(ItemStack stack) {
        return stack.get(SpatialBlueprintComponents.CAPTURE_MAX.get());
    }

    public static String getCreatorName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.CREATOR_NAME.get(), "");
    }

    public static void setCreatorName(ItemStack stack, String name) {
        stack.set(SpatialBlueprintComponents.CREATOR_NAME.get(), name);
    }

    public static void setCaptureRange(ItemStack stack, BlockPos min, BlockPos max) {
        stack.set(SpatialBlueprintComponents.CAPTURE_MIN.get(), min.immutable());
        stack.set(SpatialBlueprintComponents.CAPTURE_MAX.get(), max.immutable());
    }

    public static boolean hasTpCoord(ItemStack stack) {
        return stack.has(SpatialBlueprintComponents.TP_DIMENSION.get());
    }

    public static double getTpX(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.TP_X.get(), 0.0);
    }

    public static double getTpY(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.TP_Y.get(), 0.0);
    }

    public static double getTpZ(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.TP_Z.get(), 0.0);
    }

    /** 生成这枚空置次元蓝图时所在存档的 UUID；空串 = 本机制加入前生成的旧物品。 */
    public static String getWorldUuid(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.WORLD_UUID.get(), "");
    }

    /** 生成时所在的存档名，仅用于提示文案。 */
    public static String getWorldName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.WORLD_NAME.get(), "");
    }

    public static String getTpDimension(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.TP_DIMENSION.get(), "");
    }
}
