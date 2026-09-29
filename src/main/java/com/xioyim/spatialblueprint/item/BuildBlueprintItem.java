package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;

@EventBusSubscriber(modid = SpatialBlueprintMod.MODID)
public class BuildBlueprintItem extends Item {

    public BuildBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        LegacyItemMigration.migrate(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        String customName = getBlueprintName(stack);
        if (!customName.isEmpty()) {
            return SpatialBlueprintItem.applySaveModeColor(stack,
                    Component.translatable("item.spatial_blueprint.build_blueprint")
                            .append(Component.translatable(
                                    "spatial_blueprint.item.build_blueprint.name_separator", customName)));
        }
        return SpatialBlueprintItem.applySaveModeColor(stack, super.getName(stack));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        long completionTime = getCompletionTime(stack);
        Level level = context.level();
        if (completionTime > 0 && level != null) {
            long remaining = completionTime - level.getGameTime();
            if (remaining > 0) {
                int seconds = (int) ((remaining + 19) / 20);
                tooltip.add(Component.translatable("spatial_blueprint.item.build_blueprint.countdown", seconds)
                        .withStyle(ChatFormatting.YELLOW));
            } else {
                tooltip.add(Component.translatable("spatial_blueprint.item.build_blueprint.ready")
                        .withStyle(ChatFormatting.GREEN));
            }
        }
    }

    // --- 组件辅助 ---

    public static long getCompletionTime(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.COMPLETION_TIME.get(), 0L);
    }

    public static void setCompletionTime(ItemStack stack, long time) {
        stack.set(SpatialBlueprintComponents.COMPLETION_TIME.get(), time);
    }

    public static String getBlueprintName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "");
    }

    // --- Server tick: replace expired build blueprints with spatial blueprints ---

    /** Counts server ticks; inventory scan runs every {@link #TICK_INTERVAL} ticks (M5/M7). */
    private static int tickCounter = 0;
    private static final int TICK_INTERVAL = 20; // once per second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % TICK_INTERVAL != 0) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            long gameTime = player.serverLevel().getGameTime();
            Inventory inv = player.getInventory();
            // getContainerSize() = 41: slots 0-35 items, 36-39 armor, 40 off-hand
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!(stack.getItem() instanceof BuildBlueprintItem)) continue;
                long completionTime = getCompletionTime(stack);
                if (completionTime <= 0 || gameTime < completionTime) continue;

                inv.setItem(i, restore(stack));
                ItemSyncUtil.syncSlot(player, i);
            }
        }
    }

    /**
     * 把一枚到期的建造蓝图恢复为它原来的物品（{@code source_type} 决定类型）。
     *
     * <p>空白蓝图 / 工程蓝图：不带任何数据，返回空白物品；
     * void_dimensional：只带 12 个原样组件（源有才带）；
     * 其余类型：整份组件带过去，删掉 completion_time / source_type。</p>
     */
    public static ItemStack restore(ItemStack stack) {
        String sourceType = stack.getOrDefault(SpatialBlueprintComponents.SOURCE_TYPE.get(), "");
        Item restoredItem = switch (sourceType) {
            case "project"          -> SpatialBlueprintItems.PROJECTION_BLUEPRINT.get();
            case "engineering"      -> SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get();
            case "dimensional"      -> SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get();
            case "void_dimensional" -> SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get();
            case "blank"            -> SpatialBlueprintItems.BLANK_BLUEPRINT.get();
            default                 -> SpatialBlueprintItems.SPATIAL_BLUEPRINT.get();
        };
        ItemStack restored = new ItemStack(restoredItem);
        if ("void_dimensional".equals(sourceType)) {
            copyIfPresent(stack, restored, SpatialBlueprintComponents.BLUEPRINT_NAME.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.SAVE_MODE.get());
            // 保留蓝图文件引用：「重新储存」要靠它覆盖写回原文件并清除已放置标记
            copyIfPresent(stack, restored, SpatialBlueprintComponents.BLUEPRINT_ID.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.CAPTURE_MIN.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.CAPTURE_MAX.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.TP_X.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.TP_Y.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.TP_Z.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.TP_DIMENSION.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.CREATOR_NAME.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.WORLD_UUID.get());
            copyIfPresent(stack, restored, SpatialBlueprintComponents.WORLD_NAME.get());
        } else if (!"blank".equals(sourceType) && !"engineering".equals(sourceType)) {
            restored = stack.transmuteCopy(restoredItem);
            restored.remove(SpatialBlueprintComponents.COMPLETION_TIME.get());
            restored.remove(SpatialBlueprintComponents.SOURCE_TYPE.get());
        }
        // "blank" / "engineering": 不带组件 → 返回空白物品
        return restored;
    }

    private static <T> void copyIfPresent(ItemStack from, ItemStack to, DataComponentType<T> type) {
        if (from.has(type)) {
            to.set(type, from.get(type));
        }
    }

    /**
     * 总冷却 tick 数 = (animDuration + extraCooldown) × 20。
     * 玩家在 GUI 设定的动画时长 + 配置文件中定义的追加冷却时间。
     */
    public static long buildTicks(int animDurationSeconds) {
        int extra = com.xioyim.spatialblueprint.BlueprintConfig.EXTRA_COOLDOWN_SECONDS.get();
        return (long)(animDurationSeconds + extra) * 20L;
    }
}
