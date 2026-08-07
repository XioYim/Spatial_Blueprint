package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.gui.PlacementScreen;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class SpatialBlueprintItem extends Item {
    public SpatialBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        String customName = getBlueprintName(stack);
        if (!customName.isEmpty()) {
            return net.minecraft.network.chat.Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(customName);
        }
        return super.getName(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            openPlacementScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) {
            openPlacementScreen(player, stack);
        }
        return InteractionResult.SUCCESS;
    }

    @OnlyIn(Dist.CLIENT)
    private static void openPlacementScreen(Player player, ItemStack stack) {
        BlueprintData data = getBlueprintData(stack);
        // 生存模式下：无 blueprintId（空白蓝图）才拒绝打开界面
        // 有 blueprintId 但数据未缓存时，允许打开界面，玩家点击"下载数据"获取
        if (!player.isCreative() && data == null && getBlueprintId(stack) == null) {
            NotifySound.clientError();
            String msgKey = (stack.getItem() instanceof DimensionalBlueprintItem)
                    ? "spatial_blueprint.message.no_dimensional_data"
                    : "spatial_blueprint.message.no_data";
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof com.xioyim.spatialblueprint.gui.ErrorToastSupport ts) {
                ts.setErrorToast(Component.translatable(msgKey));
            } else {
                player.displayClientMessage(Component.translatable(msgKey), false);
            }
            return;
        }

        // 预览处于激活状态（非 OFF）但未锁定且准星超出范围 → 自动取消，重置到初始状态后再开菜单
        com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode curMode =
                com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.getAlignMode(stack);
        boolean isLocked = isLocked(stack);
        if (curMode != com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode.OFF
                && !isLocked) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.core.BlockPos target =
                    com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.getPreviewTarget(
                            mc, com.xioyim.spatialblueprint.BlueprintConfig.PREVIEW_DISTANCE.get());
            if (target == null) {
                // 自动取消：重置 item 状态 + 渲染状态（与 cancel() 逻辑一致，但保留缓存以便立即重用）
                if (com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.isDebugSession())
                    com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                        "[PlacementScreen][DBG] 超出预览范围，自动取消预览（alignMode={}）",
                        curMode.name());
                setLocked(stack, false);
                setLockedPos(stack, null);
                setXOffset(stack, 0);
                setYOffset(stack, 0);
                setZOffset(stack, 0);
                setRotation(stack, net.minecraft.world.level.block.Rotation.NONE);
                com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.setAlignMode(
                        stack, com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode.OFF);
                com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.hideTopN = 0;
                com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.hiddenTopBlocks.clear();
                com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.fluidMode =
                        com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
            }
        }

        net.minecraft.client.Minecraft.getInstance().setScreen(new PlacementScreen(player, stack, data));
    }

    // --- Blueprint data ---

    /**
     * Loads blueprint data for this stack.
     * <ol>
     *   <li>Client: checks {@link com.xioyim.spatialblueprint.client.ClientBlueprintCache}
     *       (populated by {@link com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage}).</li>
     *   <li>Any side: tries the file store — works in single-player and on the server.</li>
     * </ol>
     * Blueprint data is no longer stored inline in item NBT.
     */
    public static BlueprintData getBlueprintData(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (!tag.contains("blueprintId")) return null;
        String id = tag.getString("blueprintId");
        if (id.isEmpty()) return null;

        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            // Client: only use the in-memory cache populated by S2C packets.
            // Never read local files — client has no blueprint files in multiplayer,
            // and in singleplayer all reads must go through the server.
            return com.xioyim.spatialblueprint.client.ClientBlueprintCache.get(id);
        }

        // Server-side: read from file store.
        return com.xioyim.spatialblueprint.util.BlueprintStorage.load(id);
    }

    /**
     * Saves {@code data} to the file store and writes the UUID reference into the item NBT.
     * Blueprint data is no longer stored inline — clients fetch it on demand via
     * {@link com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage}.
     */
    public static void setBlueprintData(ItemStack stack, BlueprintData data) {
        java.util.UUID id = com.xioyim.spatialblueprint.util.BlueprintStorage.save(data);
        stack.getOrCreateTag().putString("blueprintId", id.toString());
    }

    public static String getBlueprintId(ItemStack stack) {
        return (stack.hasTag() && stack.getTag().contains("blueprintId"))
                ? stack.getTag().getString("blueprintId") : null;
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

    // --- Animation duration ---

    /**
     * Returns the player-specified animation duration stored in this item's NBT,
     * or {@code fallback} if not set (use {@link com.xioyim.spatialblueprint.BlueprintConfig#ANIMATION_DURATION_DEFAULT}).
     */
    public static int getAnimDuration(ItemStack stack, int fallback) {
        if (stack.hasTag() && stack.getTag().contains("animDuration")) {
            return Math.max(1, stack.getTag().getInt("animDuration"));
        }
        return fallback;
    }

    public static void setAnimDuration(ItemStack stack, int seconds) {
        stack.getOrCreateTag().putInt("animDuration", Math.max(1, seconds));
    }

    // --- Transform state ---

    public static Rotation getRotation(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("rotation")) {
            try {
                return Rotation.valueOf(stack.getTag().getString("rotation"));
            } catch (IllegalArgumentException ignored) {}
        }
        return Rotation.NONE;
    }

    public static void setRotation(ItemStack stack, Rotation rotation) {
        stack.getOrCreateTag().putString("rotation", rotation.name());
    }

    public static int getXOffset(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("xOffset") ? stack.getTag().getInt("xOffset") : 0;
    }

    public static void setXOffset(ItemStack stack, int offset) {
        stack.getOrCreateTag().putInt("xOffset", offset);
    }

    public static int getYOffset(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("yOffset") ? stack.getTag().getInt("yOffset") : 0;
    }

    public static void setYOffset(ItemStack stack, int offset) {
        stack.getOrCreateTag().putInt("yOffset", offset);
    }

    public static int getZOffset(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("zOffset") ? stack.getTag().getInt("zOffset") : 0;
    }

    public static void setZOffset(ItemStack stack, int offset) {
        stack.getOrCreateTag().putInt("zOffset", offset);
    }

    // --- Align mode (stored as string; AlignMode enum lives in BlueprintSelectionRenderer @OnlyIn CLIENT) ---

    /**
     * Returns the raw align-mode string stored in this item's NBT ("OFF", "CENTER", "START", "END"),
     * or "OFF" if not set.  Client-only code converts this to {@code AlignMode} via
     * {@link com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer#getAlignMode(ItemStack)}.
     */
    public static String getAlignModeStr(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("alignMode")) {
            return stack.getTag().getString("alignMode");
        }
        return "OFF";
    }

    /** Writes {@code mode} to this item's NBT (removes the key when {@code "OFF"} to keep NBT lean). */
    public static void setAlignModeStr(ItemStack stack, String mode) {
        if ("OFF".equals(mode)) {
            if (stack.hasTag()) stack.getTag().remove("alignMode");
        } else {
            stack.getOrCreateTag().putString("alignMode", mode);
        }
    }

    public static boolean isLocked(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("locked") && stack.getTag().getBoolean("locked");
    }

    public static void setLocked(ItemStack stack, boolean locked) {
        stack.getOrCreateTag().putBoolean("locked", locked);
    }

    public static BlockPos getLockedPos(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("lockedPos")) {
            CompoundTag tag = stack.getTag().getCompound("lockedPos");
            return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        }
        return null;
    }

    public static void setLockedPos(ItemStack stack, BlockPos pos) {
        if (pos == null) {
            if (stack.hasTag()) stack.getTag().remove("lockedPos");
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        stack.getOrCreateTag().put("lockedPos", tag);
    }

    // --- Clear mode ---

    /** Returns true = clear entire bounding box before placement; false (default) = replace-only mode. */
    public static boolean getClearFirst(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("clearFirst")) {
            return stack.getTag().getBoolean("clearFirst");
        }
        return false;
    }

    public static void setClearFirst(ItemStack stack, boolean clearFirst) {
        stack.getOrCreateTag().putBoolean("clearFirst", clearFirst);
    }
}
