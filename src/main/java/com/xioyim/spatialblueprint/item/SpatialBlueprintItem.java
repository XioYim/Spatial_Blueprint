package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.CaptureMode;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

public class SpatialBlueprintItem extends Item {
    public SpatialBlueprintItem(Properties properties) {
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
            return applySaveModeColor(stack, net.minecraft.network.chat.Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(customName));
        }
        return applySaveModeColor(stack, super.getName(stack));
    }

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
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!context.getLevel().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && com.xioyim.spatialblueprint.util.OrphanBlueprintGuard.discardIfFileMissing(sp, context.getHand())) {
            return InteractionResult.FAIL;
        }
        if (context.getLevel().isClientSide) {
            openPlacementScreen(player, stack);
        }
        return InteractionResult.SUCCESS;
    }

    private static void openPlacementScreen(Player player, ItemStack stack) {
        // SB-TODO(P6): 客户端打开放置界面（ForceBlueprintGuard 拦截、存档归属校验、已放置蓝图回收、超出预览范围自动取消、PlacementScreen）
    }

    /**
     * 处理一枚指向已放置结构的废蓝图：清空它的全部操作状态、弹横幅、请求服务端回收成空白蓝图。
     *
     * <p>状态必须清干净 —— 否则预览结构会继续渲染在世界里，而对应的蓝图马上就要被换掉，
     * 留下一个没有归属的悬空预览。</p>
     */
    public static void discardInvalidBlueprint(ItemStack stack) {
        // SB-TODO(P6): 客户端回收废蓝图（清预览状态、弹横幅、C2S_DiscardInvalidBlueprintMessage）
    }

    // --- Blueprint data ---

    /**
     * 服务端读法：从文件存储读。空间蓝图的数据不再内联在物品里，
     * 所以没有 blueprint_id 就是没有数据。
     *
     * <p>服务端逻辑一律走这条，<b>包括单人与局域网主机的集成服务器</b>。</p>
     */
    public static BlueprintData getBlueprintDataServer(ItemStack stack) {
        String id = blueprintIdOrNull(stack);
        if (id == null) return null;
        return com.xioyim.spatialblueprint.util.BlueprintStorage.load(id);
    }

    /**
     * 客户端读法：只认客户端蓝图缓存（由 S2C 数据包填充）。
     *
     * <p>客户端在多人游戏里没有蓝图文件，因此永远不读本地文件。
     * 空间蓝图只认缓存、没有内联回退（与 1.20.1 一致）。</p>
     */
    public static BlueprintData getBlueprintDataClient(ItemStack stack) {
        String id = blueprintIdOrNull(stack);
        if (id == null) return null;
        return com.xioyim.spatialblueprint.client.ClientBlueprintCache.get(id);
    }

    private static String blueprintIdOrNull(ItemStack stack) {
        String id = getBlueprintId(stack);
        return (id == null || id.isEmpty()) ? null : id;
    }

    /**
     * Saves {@code data} to the file store and writes the UUID reference into the item components.
     * Blueprint data is no longer stored inline — clients fetch it on demand.
     */
    public static void setBlueprintData(ItemStack stack, BlueprintData data) {
        java.util.UUID id = com.xioyim.spatialblueprint.util.BlueprintStorage.save(data);
        stack.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), id.toString());
        setSaveMode(stack, data.saveMode);
    }

    public static String getBlueprintId(ItemStack stack) {
        return stack.has(SpatialBlueprintComponents.BLUEPRINT_ID.get())
                ? stack.get(SpatialBlueprintComponents.BLUEPRINT_ID.get()) : null;
    }

    public static String getBlueprintName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "");
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), name);
    }

    public static void setSaveMode(ItemStack stack, CaptureMode mode) {
        stack.set(SpatialBlueprintComponents.SAVE_MODE.get(), mode == null ? CaptureMode.SAFE : mode);
    }

    public static CaptureMode getSaveMode(ItemStack stack) {
        CaptureMode mode = stack.get(SpatialBlueprintComponents.SAVE_MODE.get());
        return mode == null ? CaptureMode.SAFE : mode;
    }

    public static Component applySaveModeColor(ItemStack stack, Component name) {
        ChatFormatting color = switch (getSaveMode(stack)) {
            case SANITIZE -> ChatFormatting.YELLOW;
            case FORCE -> ChatFormatting.RED;
            default -> ChatFormatting.WHITE;
        };
        return name.copy().withStyle(color);
    }

    // --- Animation duration ---

    /**
     * Returns the player-specified animation duration stored on this item,
     * or {@code fallback} if not set.
     */
    public static int getAnimDuration(ItemStack stack, int fallback) {
        if (stack.has(SpatialBlueprintComponents.ANIM_DURATION.get())) {
            return Math.max(1, stack.get(SpatialBlueprintComponents.ANIM_DURATION.get()));
        }
        return fallback;
    }

    public static void setAnimDuration(ItemStack stack, int seconds) {
        stack.set(SpatialBlueprintComponents.ANIM_DURATION.get(), Math.max(1, seconds));
    }

    // --- Transform state ---

    public static Rotation getRotation(ItemStack stack) {
        Rotation rotation = stack.get(SpatialBlueprintComponents.ROTATION.get());
        return rotation == null ? Rotation.NONE : rotation;
    }

    public static void setRotation(ItemStack stack, Rotation rotation) {
        stack.set(SpatialBlueprintComponents.ROTATION.get(), rotation);
    }

    public static int getXOffset(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.X_OFFSET.get(), 0);
    }

    public static void setXOffset(ItemStack stack, int offset) {
        stack.set(SpatialBlueprintComponents.X_OFFSET.get(), offset);
    }

    public static int getYOffset(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.Y_OFFSET.get(), 0);
    }

    public static void setYOffset(ItemStack stack, int offset) {
        stack.set(SpatialBlueprintComponents.Y_OFFSET.get(), offset);
    }

    public static int getZOffset(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.Z_OFFSET.get(), 0);
    }

    public static void setZOffset(ItemStack stack, int offset) {
        stack.set(SpatialBlueprintComponents.Z_OFFSET.get(), offset);
    }

    // --- Align mode (stored as string; AlignMode enum lives in client code) ---

    /**
     * Returns the raw align-mode string ("OFF", "CENTER", "START", "END"),
     * or "OFF" if not set. Client-only code converts this to its AlignMode.
     */
    public static String getAlignModeStr(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.ALIGN_MODE.get(), "OFF");
    }

    /** Writes {@code mode} to this item's components (removes the component when {@code "OFF"}). */
    public static void setAlignModeStr(ItemStack stack, String mode) {
        if ("OFF".equals(mode)) {
            stack.remove(SpatialBlueprintComponents.ALIGN_MODE.get());
        } else {
            stack.set(SpatialBlueprintComponents.ALIGN_MODE.get(), mode);
        }
    }

    public static boolean isLocked(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.LOCKED.get(), false);
    }

    public static void setLocked(ItemStack stack, boolean locked) {
        stack.set(SpatialBlueprintComponents.LOCKED.get(), locked);
    }

    public static BlockPos getLockedPos(ItemStack stack) {
        return stack.get(SpatialBlueprintComponents.LOCKED_POS.get());
    }

    public static void setLockedPos(ItemStack stack, BlockPos pos) {
        if (pos == null) {
            stack.remove(SpatialBlueprintComponents.LOCKED_POS.get());
            return;
        }
        // immutable()：调用方可能传 MutableBlockPos，存进组件后被改就是静默串值
        stack.set(SpatialBlueprintComponents.LOCKED_POS.get(), pos.immutable());
    }

    // --- Clear mode ---

    /** Returns true = clear entire bounding box before placement; false (default) = replace-only mode. */
    public static boolean getClearFirst(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.CLEAR_FIRST.get(), false);
    }

    public static void setClearFirst(ItemStack stack, boolean clearFirst) {
        stack.set(SpatialBlueprintComponents.CLEAR_FIRST.get(), clearFirst);
    }
}
