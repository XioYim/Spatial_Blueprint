package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.List;

@Mod.EventBusSubscriber(modid = SpatialBlueprintMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BuildBlueprintItem extends Item {

    public BuildBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        if (isForceCancelled(stack)) {
            return Component.translatable("item.spatial_blueprint.build_blueprint_cancelled");
        }
        String customName = getBlueprintName(stack);
        if (!customName.isEmpty()) {
            return Component.translatable("item.spatial_blueprint.build_blueprint")
                    .append(Component.translatable("spatial_blueprint.item.build_blueprint.name_separator", customName));
        }
        return super.getName(stack);
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
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        long completionTime = getCompletionTime(stack);
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

    // --- NBT helpers ---

    public static long getCompletionTime(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getLong("completionTime") : 0L;
    }

    public static void setCompletionTime(ItemStack stack, long time) {
        stack.getOrCreateTag().putLong("completionTime", time);
    }

    public static String getBlueprintName(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("blueprintName")
                ? stack.getTag().getString("blueprintName") : "";
    }

    public static boolean isForceCancelled(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean("forceCancelled");
    }

    // --- Server tick: replace expired build blueprints with spatial blueprints ---

    /** Counts server ticks; inventory scan runs every {@link #TICK_INTERVAL} ticks (M5/M7). */
    private static int tickCounter = 0;
    private static final int TICK_INTERVAL = 20; // once per second

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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

                // 根据 sourceType 恢复为正确类型
                String sourceType = stack.hasTag() ? stack.getTag().getString("sourceType") : "";
                net.minecraft.world.item.Item restoredItem = switch (sourceType) {
                    case "project"          -> SpatialBlueprintItems.PROJECTION_BLUEPRINT.get();
                    case "engineering"      -> SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get();
                    case "dimensional"      -> SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get();
                    case "void_dimensional" -> SpatialBlueprintItems.VOID_DIMENSIONAL_BLUEPRINT.get();
                    case "blank"            -> SpatialBlueprintItems.BLANK_BLUEPRINT.get();
                    default                 -> SpatialBlueprintItems.SPATIAL_BLUEPRINT.get();
                };
                ItemStack restored = new ItemStack(restoredItem);
                // 空白蓝图 / 工程蓝图：不携带蓝图数据，返回空白物品
                // void_dimensional：只拷贝坐标和名称；其他类型：完整拷贝
                if ("void_dimensional".equals(sourceType) && stack.hasTag()) {
                    CompoundTag src = stack.getTag();
                    CompoundTag nbt = new CompoundTag();
                    if (src.contains("blueprintName")) nbt.putString("blueprintName", src.getString("blueprintName"));
                    if (src.contains("captureMin"))    nbt.put("captureMin", src.getCompound("captureMin").copy());
                    if (src.contains("captureMax"))    nbt.put("captureMax", src.getCompound("captureMax").copy());
                    if (src.contains("tpX"))           nbt.putDouble("tpX", src.getDouble("tpX"));
                    if (src.contains("tpY"))           nbt.putDouble("tpY", src.getDouble("tpY"));
                    if (src.contains("tpZ"))           nbt.putDouble("tpZ", src.getDouble("tpZ"));
                    if (src.contains("tpDimension"))   nbt.putString("tpDimension", src.getString("tpDimension"));
                    if (src.contains("creatorName"))   nbt.putString("creatorName", src.getString("creatorName"));
                    restored.setTag(nbt);
                } else if (!"blank".equals(sourceType) && !"engineering".equals(sourceType) && stack.hasTag()) {
                    CompoundTag nbt = stack.getTag().copy();
                    nbt.remove("completionTime");
                    nbt.remove("forceCancelled");
                    nbt.remove("sourceType");
                    restored.setTag(nbt);
                }
                // "blank" / "engineering": no NBT → 返回空白物品
                inv.setItem(i, restored);
                ItemSyncUtil.syncSlot(player, i);
            }
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
