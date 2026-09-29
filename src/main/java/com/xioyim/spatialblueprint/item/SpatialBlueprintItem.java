package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.gui.PlacementScreen;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.ChatFormatting;
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

    @OnlyIn(Dist.CLIENT)
    private static void openPlacementScreen(Player player, ItemStack stack) {
        BlueprintData data = getBlueprintDataClient(stack);
        // 生存模式下：无 blueprintId（空白蓝图）才拒绝打开界面
        // 有 blueprintId 但数据未缓存时，允许打开界面，玩家点击"下载数据"获取
        if (!player.isCreative() && data == null && getBlueprintId(stack) == null) {
            NotifySound.clientError();
            String msgKey = (stack.getItem() instanceof DimensionalBlueprintItem)
                    ? "spatial_blueprint.message.no_dimensional_data"
                    : "spatial_blueprint.message.no_data";
            com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(Component.translatable(msgKey));
            return;
        }

        // ── 强制捕获(FORCE)的蓝图：仅 OP 创造模式可用 ─────────────────────
        if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard.intercept(player, stack, data)) return;

        // ── 存档归属校验：工程 / 次元蓝图不属于本档时直接销毁，OP 也不例外 ──
        if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                .interceptForeignWorld(player, stack, data)) return;

        // ── 次元蓝图归属校验：任何人（含 OP）都不能用别人的 ────────────────
        // 与 PlacementScreen 的迟到补判共用同一个守卫，避免两边规则走样。
        if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                .interceptNotOwner(player, stack, data)) return;

        // ── 已放置的次元蓝图：拦截右键，不打开界面 ────────────────────────
        // 玩家可能持有多份指向同一文件的次元蓝图（创造模式复制、/give 等）。
        // 放置其中一份后文件被标记 placed，其余几份就成了指向已消耗结构的废蓝图 ——
        // 放行进界面只会让玩家在里面看到「材料不足」，不如在门口就说清楚并直接回收。
        // 服务端在放置后会推送更新过的 BlueprintData，因此这里的 placed 是新鲜的。
        if (data != null && data.placed) {
            discardInvalidBlueprint(stack);
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
                            mc, ConfigAccess.get(com.xioyim.spatialblueprint.BlueprintConfig.PREVIEW_DISTANCE));
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

    /**
     * 处理一枚指向已放置结构的废蓝图：清空它的全部操作状态、弹横幅、请求服务端回收成空白蓝图。
     *
     * <p>状态必须清干净 —— 否则预览结构会继续渲染在世界里，而对应的蓝图马上就要被换掉，
     * 留下一个没有归属的悬空预览。</p>
     */
    @OnlyIn(Dist.CLIENT)
    public static void discardInvalidBlueprint(ItemStack stack) {
        NotifySound.clientError();

        // 该蓝图自身的操作状态（预览模式 / 锁定 / 偏移 / 旋转）
        com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.setAlignMode(
                stack, com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode.OFF);
        setLocked(stack, false);
        setLockedPos(stack, null);
        setXOffset(stack, 0);
        setYOffset(stack, 0);
        setZOffset(stack, 0);
        setRotation(stack, net.minecraft.world.level.block.Rotation.NONE);

        // 渲染器的全局显示状态（隐藏层数 / 流体显示模式）
        com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.hideTopN = 0;
        com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.hiddenTopBlocks.clear();
        com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.fluidMode =
                com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;

        String bpId = getBlueprintId(stack);
        if (bpId != null) com.xioyim.spatialblueprint.client.ClientBlueprintCache.remove(bpId);

        // 请求服务端把这枚废蓝图换成空白蓝图（服务端会重新校验 placed）
        com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_DiscardInvalidBlueprintMessage());

        // 右键时通常没有任何界面打开，用独立横幅界面承载报错（聊天栏太容易被忽略）
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.xioyim.spatialblueprint.gui.ErrorToastScreen(
                        Component.translatable("spatial_blueprint.message.invalid_blueprint")));
    }

    // --- Blueprint data ---

    /**
     * 服务端读法：从文件存储读。空间蓝图的数据不再内联在物品 NBT 里，
     * 所以没有 blueprintId 就是没有数据。
     *
     * <p>服务端逻辑一律走这条，<b>包括单人与局域网主机的集成服务器</b>。</p>
     */
    public static BlueprintData getBlueprintDataServer(ItemStack stack) {
        String id = blueprintIdOrNull(stack);
        if (id == null) return null;
        return com.xioyim.spatialblueprint.util.BlueprintStorage.load(id);
    }

    /**
     * 客户端读法：只认 {@link com.xioyim.spatialblueprint.client.ClientBlueprintCache}
     * ——由 {@link com.xioyim.spatialblueprint.network.S2C_BlueprintDataMessage} 填充。
     *
     * <p>客户端在多人游戏里没有蓝图文件，因此永远不读本地文件。
     * 只能在客户端线程调用。</p>
     */
    public static BlueprintData getBlueprintDataClient(ItemStack stack) {
        String id = blueprintIdOrNull(stack);
        if (id == null) return null;
        return com.xioyim.spatialblueprint.client.ClientBlueprintCache.get(id);
    }

    private static String blueprintIdOrNull(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (!tag.contains("blueprintId")) return null;
        String id = tag.getString("blueprintId");
        return id.isEmpty() ? null : id;
    }

    /**
     * Saves {@code data} to the file store and writes the UUID reference into the item NBT.
     * Blueprint data is no longer stored inline — clients fetch it on demand via
     * {@link com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage}.
     */
    public static void setBlueprintData(ItemStack stack, BlueprintData data) {
        java.util.UUID id = com.xioyim.spatialblueprint.util.BlueprintStorage.save(data);
        stack.getOrCreateTag().putString("blueprintId", id.toString());
        setSaveMode(stack, data.saveMode);
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

    public static void setSaveMode(ItemStack stack, CaptureMode mode) {
        stack.getOrCreateTag().putInt("saveMode", (mode == null ? CaptureMode.SAFE : mode).ordinal());
    }

    public static CaptureMode getSaveMode(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains("saveMode")) return CaptureMode.SAFE;
        int ordinal = stack.getTag().getInt("saveMode");
        CaptureMode[] modes = CaptureMode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : CaptureMode.SAFE;
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
