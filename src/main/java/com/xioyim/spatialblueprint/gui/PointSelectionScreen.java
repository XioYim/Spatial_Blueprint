package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.network.CaptureAreaMessage;
import com.xioyim.spatialblueprint.network.CaptureProjectionMessage;
import com.xioyim.spatialblueprint.network.ClearPointsMessage;
import com.xioyim.spatialblueprint.network.SetBlueprintNameMessage;
import com.xioyim.spatialblueprint.network.SetPointMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class PointSelectionScreen extends Screen implements ErrorToastSupport {
    private final Player player;
    private EditBox nameInput;
    private EditBox timeInput;
    private Component errorToast;

    /** 流体捕捉过滤模式（4档循环），选择不录入蓝图的流体类型。 */
    private static com.xioyim.spatialblueprint.util.FluidCaptureFilter fluidCaptureFilter
            = com.xioyim.spatialblueprint.util.FluidCaptureFilter.ALL;

    // -1 = not computed yet, >=0 = count
    // Instance field: each screen instance has its own cache, preventing LAN multi-player interference.
    private int cachedBlockCount = -1;

    /** OP 玩家是否手动修改了时长输入框；为 true 时不自动覆盖。 */
    private boolean opDurationModified        = false;
    /** 程序自动填写时长时置 true，防止触发 opDurationModified。 */
    private boolean suppressDurationResponder = false;
    /** clearWidgets/init 重建时保留 OP 手动填写的值。 */
    private String  savedOpDuration           = "";

    // ── Refresh button ────────────────────────────────────────────────────
    private static final long REFRESH_COOLDOWN_MS = 3000L;
    // Instance field: cooldown is per-screen-instance, not shared across players.
    private long refreshCooldownEndMs = 0L;

    // Hit area of the 🔁 text; updated every render frame, -1 = hidden
    private int refreshBtnX = -1;
    private int refreshBtnY = -1;
    private static final int REFRESH_BTN_W = 10; // fixed hit width (emoji may render 0-width)
    private static final int REFRESH_BTN_H = 9;

    // ── Layout constants shared between init() and render() ─────────────
    private static final int BTN_W  = 20;   // direction button size
    private static final int BTN_H  = 20;
    private static final int V_GAP  = 2;    // gap between ▲ and ▼
    private static final int PT_W   = 92;   // Start / End button width
    private static final int PT_GAP = 2;    // gap between direction buttons

    /** 时间输入框宽度：能容纳 3 位数字。 */
    private static final int TIME_W = 36;
    /** 时间输入框与名称框的水平间距。 */
    private static final int TIME_GAP = 4;

    private static int p1UpDownX(int cx) { return cx - 122; }
    private static int p2UpDownX(int cx) { return cx + 100; }
    private static int rowY(int cy)      { return cy + 51; }

    public PointSelectionScreen(Player player) {
        super(Component.translatable("spatial_blueprint.gui.point_selection.title"));
        this.player = player;
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    /** Always returns the live item from the player's hands to avoid stale references after server syncs. */
    private ItemStack getStack() {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof BlankBlueprintItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof BlankBlueprintItem) return off;
        return ItemStack.EMPTY;
    }

    @Override
    protected void init() {
        super.init();

        // 首次 init（cachedBlockCount == -1）且两点已设好时立刻计算一次
        if (cachedBlockCount == -1) {
            ItemStack stack = getStack();
            if (BlankBlueprintItem.hasPoint1(stack) && BlankBlueprintItem.hasPoint2(stack))
                cachedBlockCount = computeNonAirCount();
        }

        int cx = this.width  / 2;
        int cy = this.height / 2;

        // ── Name input (略微缩窄以在右侧为时间框留出空间) ─────────────────────
        int nameW = 200 - TIME_W - TIME_GAP - 6; // 6px 留给 "s" 标签
        nameInput = new EditBox(this.font, cx - 100, cy - 115, nameW, 20,
                Component.translatable("spatial_blueprint.gui.point_selection.name"));
        nameInput.setMaxLength(32);
        nameInput.setHint(Component.translatable("spatial_blueprint.gui.point_selection.name_hint"));
        nameInput.setFocused(true);
        String currentName = BlankBlueprintItem.getBlueprintName(getStack());
        nameInput.setValue(currentName.isEmpty()
                ? Component.translatable("spatial_blueprint.gui.point_selection.default_name").getString()
                : currentName);
        nameInput.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.point_selection.tooltip.name_required")));
        this.addWidget(nameInput);

        // ── Time input（名称框右侧）──────────────────────────────────────────
        boolean isOp = player.hasPermissions(2);
        boolean canEditDuration = isOp
                || (player.isCreative() && BlueprintConfig.ALLOW_CREATIVE_DURATION_EDIT.get());
        int timeX  = cx - 100 + nameW + TIME_GAP;
        int minSec = BlueprintConfig.ANIMATION_DURATION_MIN.get();
        timeInput = new EditBox(this.font, timeX, cy - 115, TIME_W, 20,
                Component.literal("s"));
        timeInput.setMaxLength(4);
        timeInput.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        timeInput.setEditable(canEditDuration);

        // 可编辑玩家追踪手动修改，不可编辑玩家始终自动填充
        timeInput.setResponder(s -> {
            if (canEditDuration && !suppressDurationResponder) {
                opDurationModified = true;
                savedOpDuration    = s;
            }
        });

        // 初始值：手动修改过则恢复保存的值；否则按方块数自动填充
        suppressDurationResponder = true;
        if (canEditDuration && opDurationModified && !savedOpDuration.isEmpty()) {
            timeInput.setValue(savedOpDuration);
        } else if (cachedBlockCount >= 0) {
            timeInput.setValue(String.valueOf(BlueprintConfig.getAutoDuration(cachedBlockCount)));
        } else {
            timeInput.setValue(String.valueOf(BlueprintConfig.BUILD_TIME_TIER1_SECONDS.get()));
        }
        suppressDurationResponder = false;

        if (canEditDuration) {
            timeInput.setTooltip(Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.point_selection.tooltip.duration",
                            minSec)));
        } else {
            timeInput.setTooltip(Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.point_selection.tooltip.duration_readonly")));
        }
        this.addWidget(timeInput);

        // ── Layout positions ───────────────────────────────────────────────
        int rowY  = rowY(cy);
        int p1X   = cx - 100;
        int p2X   = cx + 12;
        int p1UDX = p1UpDownX(cx);
        int p2UDX = p2UpDownX(cx);

        // ── Direction buttons – Point 1 ────────────────────────────────────
        int p1X1 = p1X;
        int p1X2 = p1X1 + BTN_W + PT_GAP;
        int p1X3 = p1X2 + BTN_W + PT_GAP;
        int p1X4 = p1X3 + BTN_W + PT_GAP;

        addDirBtn(Component.literal("←"), "spatial_blueprint.gui.point_selection.tooltip.dir.west",
                p1X1, rowY, BTN_W, BTN_H, () -> adjustPoint(1, "west"));
        addDirBtn(Component.literal("↑"), "spatial_blueprint.gui.point_selection.tooltip.dir.south",
                p1X2, rowY, BTN_W, BTN_H, () -> adjustPoint(1, "south"));
        addDirBtn(Component.literal("→"), "spatial_blueprint.gui.point_selection.tooltip.dir.east",
                p1X3, rowY, BTN_W, BTN_H, () -> adjustPoint(1, "east"));
        addDirBtn(Component.literal("↓"), "spatial_blueprint.gui.point_selection.tooltip.dir.north",
                p1X4, rowY, BTN_W, BTN_H, () -> adjustPoint(1, "north"));

        addDirBtn(Component.literal("▲"), "spatial_blueprint.gui.point_selection.tooltip.dir.up",
                p1UDX, rowY,              BTN_W, BTN_H, () -> adjustPoint(1, "up"));
        addDirBtn(Component.literal("▼"), "spatial_blueprint.gui.point_selection.tooltip.dir.down",
                p1UDX, rowY + BTN_H + V_GAP, BTN_W, BTN_H, () -> adjustPoint(1, "down"));

        // ── Direction buttons – Point 2 ────────────────────────────────────
        int p2X1 = p2X;
        int p2X2 = p2X1 + BTN_W + PT_GAP;
        int p2X3 = p2X2 + BTN_W + PT_GAP;
        int p2X4 = p2X3 + BTN_W + PT_GAP;

        addDirBtn(Component.literal("←"), "spatial_blueprint.gui.point_selection.tooltip.dir.west",
                p2X1, rowY, BTN_W, BTN_H, () -> adjustPoint(2, "west"));
        addDirBtn(Component.literal("↑"), "spatial_blueprint.gui.point_selection.tooltip.dir.south",
                p2X2, rowY, BTN_W, BTN_H, () -> adjustPoint(2, "south"));
        addDirBtn(Component.literal("→"), "spatial_blueprint.gui.point_selection.tooltip.dir.east",
                p2X3, rowY, BTN_W, BTN_H, () -> adjustPoint(2, "east"));
        addDirBtn(Component.literal("↓"), "spatial_blueprint.gui.point_selection.tooltip.dir.north",
                p2X4, rowY, BTN_W, BTN_H, () -> adjustPoint(2, "north"));

        addDirBtn(Component.literal("▲"), "spatial_blueprint.gui.point_selection.tooltip.dir.up",
                p2UDX, rowY,              BTN_W, BTN_H, () -> adjustPoint(2, "up"));
        addDirBtn(Component.literal("▼"), "spatial_blueprint.gui.point_selection.tooltip.dir.down",
                p2UDX, rowY + BTN_H + V_GAP, BTN_W, BTN_H, () -> adjustPoint(2, "down"));

        // ── Set-point buttons ──────────────────────────────────────────────
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.set_point1"),
                        b -> setPoint(1))
                .bounds(p1X, cy + 73, BTN_W * 4 + PT_GAP * 3, 20)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.set_point1")))
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.set_point2"),
                        b -> setPoint(2))
                .bounds(p2X, cy + 73, BTN_W * 4 + PT_GAP * 3, 20)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.set_point2")))
                .build());

        // ── ♨ 流体捕捉过滤按钮（✎ 正上方，方向按钮行）──────────────────────
        int editBtnX = p1X + BTN_W * 4 + PT_GAP * 3 + 2;
        int editBtnW = p2X - editBtnX - 2;
        {
            com.xioyim.spatialblueprint.util.FluidCaptureFilter cf = fluidCaptureFilter;
            // 图标颜色：ALL=白，NO_WATER=蓝，NO_LAVA=橙，NONE=红
            Component captureFluidLabel = switch (cf) {
                case ALL      -> Component.literal("♨");
                case NO_WATER -> Component.literal("♨").withStyle(s -> s.withColor(net.minecraft.ChatFormatting.BLUE));
                case NO_LAVA  -> Component.literal("♨").withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GOLD));
                case NONE     -> Component.literal("♨").withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED));
            };
            String captureTipKey = "spatial_blueprint.gui.point_selection.tooltip.fluid_capture."
                    + cf.name().toLowerCase();
            addRenderableWidget(Button.builder(captureFluidLabel, b -> {
                fluidCaptureFilter = fluidCaptureFilter.next();
                cachedBlockCount = computeNonAirCount();
                clearWidgets(); init();
            })
                    .bounds(editBtnX, rowY(cy), editBtnW, BTN_H)
                    .tooltip(Tooltip.create(Component.translatable(captureTipKey)))
                    .build());
        }

        // ── ✎ 坐标编辑按钮（起点和终点之间，两侧各 2px 间距）────────────────────
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.coord_edit"),
                        b -> Minecraft.getInstance().setScreen(new CoordinateEditScreen(player, this)))
                .bounds(editBtnX, cy + 73, editBtnW, 20)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.tooltip.coord_edit")))
                .build());

        // ── Bottom action buttons: [创建][关闭][取消][配置投影模板] ──────────
        // 行范围：bx (cx-122) ~ rowRight (cx+118)，共 240px，4按钮间距 3px
        int bottomY  = cy + 95;
        int bx       = p1UpDownX(cx);                  // cx - 122
        int rowRight = bx + (int)((p2UpDownX(cx) + BTN_W) - bx); // cx + 122
        int totalW   = rowRight - bx;                  // 244px
        int gap      = 3;
        int btnW     = (totalW - gap * 3) / 4;         // 每按钮等宽

        // [创建] — 全模式可用，打开创建蓝图子菜单
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.capture"),
                        b -> Minecraft.getInstance().setScreen(
                                new CreateBlueprintScreen(PointSelectionScreen.this, player)))
                .bounds(bx, bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.tooltip.capture_limit",
                        player.hasPermissions(2) ? com.xioyim.spatialblueprint.BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME : com.xioyim.spatialblueprint.BlueprintConfig.MAX_CAPTURE_VOLUME_SURVIVAL.get(),
                        player.hasPermissions(2) ? com.xioyim.spatialblueprint.BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME : com.xioyim.spatialblueprint.BlueprintConfig.MAX_CAPTURE_VOLUME_CREATIVE.get())))
                .build());

        // [关闭] — 保留两点，关闭界面
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.close"),
                        b -> this.onClose())
                .bounds(bx + (btnW + gap), bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.btn.save_and_close")))
                .build());

        // [取消] — 清除两点并关闭
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.cancel"),
                        b -> clearAndClose())
                .bounds(bx + (btnW + gap) * 2, bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.clear_and_close")))
                .build());

        // [配置投影模板]
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.proj_template"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen(PointSelectionScreen.this)))
                .bounds(bx + (btnW + gap) * 3, bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.tooltip.proj_template")))
                .build());
    }

    private void addDirBtn(Component label, String tooltipKey,
                           int x, int y, int w, int h, Runnable action) {
        addRenderableWidget(Button.builder(label, b -> action.run())
                .bounds(x, y, w, h)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build());
    }

    // ── Logic ─────────────────────────────────────────────────────────────

    /** 自动将分档时长填入时间框（仅当可编辑玩家未手动修改时）。 */
    private void autoFillDuration() {
        if (timeInput == null || cachedBlockCount < 0) return;
        boolean canEdit = player.hasPermissions(2)
                || (player.isCreative() && BlueprintConfig.ALLOW_CREATIVE_DURATION_EDIT.get());
        if (canEdit && opDurationModified) return; // 已手动改过，不覆盖
        suppressDurationResponder = true;
        timeInput.setValue(String.valueOf(BlueprintConfig.getAutoDuration(cachedBlockCount)));
        suppressDurationResponder = false;
    }

    /**
     * 读取时间输入框的值。
     * 所有玩家均受全局 min/max 限制；非可编辑玩家返回自动分档值。
     */
    private int getTimeSec() {
        int minSec = BlueprintConfig.ANIMATION_DURATION_MIN.get();
        int maxSec = BlueprintConfig.ANIMATION_DURATION_MAX.get();
        try {
            int v = Integer.parseInt(timeInput.getValue().trim());
            v = Math.max(minSec, Math.min(maxSec, v));
            return v;
        } catch (NumberFormatException e) {
            if (cachedBlockCount >= 0) return BlueprintConfig.getAutoDuration(cachedBlockCount);
            return Math.max(minSec, BlueprintConfig.BUILD_TIME_TIER1_SECONDS.get());
        }
    }

    private boolean validateStillHoldingBlankBlueprint() {
        boolean hasBlank = player.getMainHandItem().getItem() instanceof BlankBlueprintItem
                || player.getOffhandItem().getItem() instanceof BlankBlueprintItem;
        if (!hasBlank) {
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.message.no_blank_blueprint");
            return false;
        }
        return true;
    }

    private void setPoint(int index) {
        if (!validateStillHoldingBlankBlueprint()) return;
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK)
                ? ((BlockHitResult) mc.hitResult).getBlockPos()
                : BlockPos.containing(player.getX(), player.getY(), player.getZ());
        BlueprintNetwork.CHANNEL.sendToServer(new SetPointMessage(index, pos));
        if (index == 1) BlankBlueprintItem.setPoint1(getStack(), pos);
        else            BlankBlueprintItem.setPoint2(getStack(), pos);

        // Auto-refresh block count once when both points are now set.
        ItemStack stack = getStack();
        if (BlankBlueprintItem.hasPoint1(stack) && BlankBlueprintItem.hasPoint2(stack)) {
            cachedBlockCount = computeNonAirCount();
            autoFillDuration();
        }
    }

    private void adjustPoint(int pointIndex, String direction) {
        if (!validateStillHoldingBlankBlueprint()) return;
        BlockPos cur = (pointIndex == 1)
                ? BlankBlueprintItem.getPoint1(getStack())
                : BlankBlueprintItem.getPoint2(getStack());
        if (cur == null) {
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.message.missing_points");
            return;
        }
        BlockPos next = switch (direction) {
            case "east"  -> cur.east();
            case "west"  -> cur.west();
            case "south" -> cur.south();
            case "north" -> cur.north();
            case "up"    -> cur.above();
            case "down"  -> cur.below();
            default -> null;
        };
        if (next == null) return;
        BlueprintNetwork.CHANNEL.sendToServer(new SetPointMessage(pointIndex, next));
        if (pointIndex == 1) BlankBlueprintItem.setPoint1(getStack(), next);
        else                  BlankBlueprintItem.setPoint2(getStack(), next);
        cachedBlockCount = computeNonAirCount();
        autoFillDuration();
    }

    private void manualRefresh() {
        cachedBlockCount = computeNonAirCount();
        autoFillDuration();
        refreshCooldownEndMs = System.currentTimeMillis() + REFRESH_COOLDOWN_MS;
    }

    private void clearAndClose() {
        BlueprintNetwork.CHANNEL.sendToServer(new ClearPointsMessage());
        BlankBlueprintItem.clearPoints(getStack());
        this.onClose();
    }

    private boolean prepareCapture() {
        if (!validateStillHoldingBlankBlueprint()) {
            Minecraft.getInstance().setScreen(this);
            return false;
        }
        if (!BlankBlueprintItem.hasPoint1(getStack()) || !BlankBlueprintItem.hasPoint2(getStack())) {
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.message.missing_points");
            Minecraft.getInstance().setScreen(this);
            return false;
        }
        String name = nameInput.getValue().trim();
        if (name.isEmpty()) {
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.message.name_required");
            Minecraft.getInstance().setScreen(this);
            return false;
        }
        saveName(name);
        return true;
    }

    /** [创建] 仅捕捉，不清除原始方块（仅创造模式触发）。 */
    void capture() {
        if (!prepareCapture()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new CaptureAreaMessage(nameInput.getValue().trim(), false, getTimeSec(), fluidCaptureFilter));
        this.onClose();
    }

    /** [储存] 捕捉后立刻清除选区内所有方块（所有模式均可触发）。 */
    void captureAndStore() {
        if (!prepareCapture()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new CaptureAreaMessage(nameInput.getValue().trim(), true, getTimeSec(), fluidCaptureFilter));
        this.onClose();
    }

    /** [工程] 打开"创建工程蓝图"确认界面（仅创造模式）。 */
    void openCreateEngineeringScreen() {
        if (!prepareCapture()) return;
        ItemStack stack = getStack();
        BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
        if (p1 == null || p2 == null) return;
        BlockPos min = new BlockPos(
                Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        String dimStr = (level != null) ? level.dimension().location().toString() : "minecraft:overworld";
        String name = nameInput.getValue().trim();
        Minecraft.getInstance().setScreen(
                new CreateEngineeringProjectScreen(this, "", name, dimStr, min, max));
    }

    /** [投影] 创建投影蓝图，不清除原始方块，额外生成材料清单。 */
    void captureProjection() {
        if (!prepareCapture()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new CaptureProjectionMessage(nameInput.getValue().trim(), getTimeSec()));
        this.onClose();
    }

    private void saveName(String name) {
        BlankBlueprintItem.setBlueprintName(getStack(), name);
        BlueprintNetwork.CHANNEL.sendToServer(new SetBlueprintNameMessage(name));
    }

    private int computeNonAirCount() {
        ItemStack stack = getStack();
        BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
        if (p1 == null || p2 == null) return 0;
        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
        // 无论选区是否超出捕获上限，都如实统计并显示——是否允许实际捕获由服务端
        // BlueprintValidator.validateCaptureArea() 单独把关，这里只是给玩家看一个准确数字。
        Level level = Minecraft.getInstance().level;
        if (level == null) return 0;
        com.xioyim.spatialblueprint.util.FluidCaptureFilter cf = fluidCaptureFilter;
        int count = 0;
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState bs = level.getBlockState(new BlockPos(x, y, z));
                    if (!bs.isAir() && !cf.shouldSkip(bs)) count++;
                }
        return count;
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    public void onClose() {
        String name = nameInput.getValue().trim();
        if (!name.isEmpty()) saveName(name);
        super.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx   = this.width  / 2;
        int cy   = this.height / 2;
        int rowY = rowY(cy);

        ItemStack stack = getStack();
        boolean hasP1 = BlankBlueprintItem.hasPoint1(stack);
        boolean hasP2 = BlankBlueprintItem.hasPoint2(stack);
        BlockPos p1   = hasP1 ? BlankBlueprintItem.getPoint1(stack) : null;
        BlockPos p2   = hasP2 ? BlankBlueprintItem.getPoint2(stack) : null;

        boolean showRefresh = (p1 != null && p2 != null);

        // ── Title ──────────────────────────────────────────────────────────
        final int titleY = cy - 130;
        Component titleComp;
        if (showRefresh) {
            int sX = Math.abs(p2.getX() - p1.getX()) + 1;
            int sY = Math.abs(p2.getY() - p1.getY()) + 1;
            int sZ = Math.abs(p2.getZ() - p1.getZ()) + 1;
            String countStr = cachedBlockCount == -1 ? "-" : String.valueOf(cachedBlockCount);

            MutableComponent baseTitle = this.title.copy()
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
            MutableComponent sizeComp = Component.literal(" (" + sX + "×" + sZ + "×" + sY + ")")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55)));
            MutableComponent countComp = Component.literal(" (" + countStr + ")")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)));

            titleComp = baseTitle.append(sizeComp).append(countComp);
        } else {
            titleComp = this.title.copy().withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
        }
        graphics.drawCenteredString(this.font, titleComp, cx, titleY, 0xFFFFFF);

        // ── Refresh button (🔁) ────────────────────────────────────────────
        if (showRefresh) {
            int titleW  = this.font.width(titleComp);
            int btnX    = cx + titleW / 2 + 3;
            refreshBtnX = btnX;
            refreshBtnY = titleY;

            boolean onCooldown = System.currentTimeMillis() < refreshCooldownEndMs;
            boolean hovering   = mouseX >= btnX && mouseX < btnX + REFRESH_BTN_W
                    && mouseY >= titleY && mouseY < titleY + REFRESH_BTN_H;

            int btnColor;
            if (onCooldown) {
                btnColor = 0xFF5555;
            } else if (hovering) {
                btnColor = 0xFFFF55;
            } else {
                btnColor = 0x55FF55;
            }

            graphics.drawString(this.font, "🔁", btnX, titleY, btnColor, false);

            if (hovering) {
                Component tip = onCooldown
                        ? Component.translatable("spatial_blueprint.gui.point_selection.tooltip.refresh_cooldown")
                        : Component.translatable("spatial_blueprint.gui.point_selection.tooltip.refresh");
                graphics.renderTooltip(this.font, tip, mouseX, mouseY);
            }
        } else {
            refreshBtnX = -1;
        }

        // ── Coordinates above each button group ────────────────────────────
        int coordY = rowY - 13;
        if (p1 != null) {
            String coord = "X:" + p1.getX() + ",Y:" + p1.getY() + ",Z:" + p1.getZ();
            graphics.drawString(this.font, coord, p1UpDownX(cx), coordY, 0xFFFFFF);
        }
        if (p2 != null) {
            String coord = "X:" + p2.getX() + ",Y:" + p2.getY() + ",Z:" + p2.getZ();
            int rightEdge = p2UpDownX(cx) + BTN_W;
            graphics.drawString(this.font, coord, rightEdge - this.font.width(coord), coordY, 0xFFFFFF);
        }

        // ── Input boxes ────────────────────────────────────────────────────
        nameInput.render(graphics, mouseX, mouseY, partialTick);

        // 时间输入框：低于最低值或超过全局上限时文字变红
        int minSec = BlueprintConfig.ANIMATION_DURATION_MIN.get();
        int maxSec = BlueprintConfig.ANIMATION_DURATION_MAX.get();
        boolean timeValid;
        try {
            int tv = Integer.parseInt(timeInput.getValue().trim());
            timeValid = (tv >= minSec && tv <= maxSec);
        } catch (NumberFormatException e) {
            timeValid = false;
        }
        timeInput.setTextColor(timeValid ? 0xFFFFFF : 0xFF5555);
        timeInput.render(graphics, mouseX, mouseY, partialTick);

        // "s" label to the right of the time input
        int sLabelX = timeInput.getX() + TIME_W + 2;
        int sLabelY = timeInput.getY() + (20 - font.lineHeight) / 2 + 1;
        graphics.drawString(this.font, "s", sLabelX, sLabelY, timeValid ? 0xAAAAAA : 0xFF5555, false);

    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        // Refresh button click
        if (refreshBtnX >= 0
                && mouseX >= refreshBtnX && mouseX < refreshBtnX + REFRESH_BTN_W
                && mouseY >= refreshBtnY && mouseY < refreshBtnY + REFRESH_BTN_H
                && button == 0) {
            if (System.currentTimeMillis() >= refreshCooldownEndMs) {
                manualRefresh();
                return true;
            }
            return true;
        }
        // Delegate clicks: clicking one box de-focuses the other
        if (nameInput.mouseClicked(mouseX, mouseY, button)) {
            nameInput.setFocused(true);
            timeInput.setFocused(false);
            return true;
        }
        if (timeInput.mouseClicked(mouseX, mouseY, button)) {
            timeInput.setFocused(true);
            nameInput.setFocused(false);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) { errorToast = null; return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
        if (nameInput.isFocused() && nameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (timeInput.isFocused() && timeInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameInput.isFocused() && nameInput.charTyped(codePoint, modifiers)) return true;
        if (timeInput.isFocused() && timeInput.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
