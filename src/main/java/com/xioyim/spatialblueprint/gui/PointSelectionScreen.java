package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.network.CaptureAreaMessage;
import com.xioyim.spatialblueprint.network.CaptureProjectionMessage;
import com.xioyim.spatialblueprint.network.ClearPointsMessage;
import com.xioyim.spatialblueprint.network.SetBlueprintNameMessage;
import com.xioyim.spatialblueprint.network.SetPointMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
public class PointSelectionScreen extends Screen implements ErrorToastSupport, ReportHost {
    private final Player player;
    private Component errorToast;
    /**
     * 已经为这个选区弹过「跨界胶水」警告（记的是弹警告时的两个选点）。
     * 只在点 [创建] 时弹：第一下弹横幅拦一次，同一选区再点就放行；选区一变就重新提醒。
     * 不按扫描回包时间戳记 —— [创建] 点击本身会触发重扫，按时间戳记会每次点都弹。
     */
    @javax.annotation.Nullable private BlockPos glueWarnedP1, glueWarnedP2;
    /** [创建] 按钮引用，用于实时检查选区区块加载状态并禁用。 */
    private Button captureButton;

    /** 流体捕捉过滤模式（4档循环），选择不录入蓝图的流体类型。 */
    private static com.xioyim.spatialblueprint.util.FluidCaptureFilter fluidCaptureFilter
            = com.xioyim.spatialblueprint.util.FluidCaptureFilter.ALL;

    // -1 = not computed yet, >=0 = count
    // Instance field: each screen instance has its own cache, preventing LAN multi-player interference.
    private int cachedBlockCount = -1;

    // ── 分片异步计数（主线程分预算，MutableBlockPos 零分配）──────────────────
    private boolean countRunning = false;
    private int countVer = 0;
    private int cntMinX, cntMinY, cntMinZ, cntMaxX, cntMaxY, cntMaxZ;
    private int cntX, cntY, cntZ;
    private int cntAccum = 0;
    private com.xioyim.spatialblueprint.util.FluidCaptureFilter cntFilter;
    private Level cntLevel;
    private final BlockPos.MutableBlockPos cntMutable = new BlockPos.MutableBlockPos();
    private static final long COUNT_BUDGET_NS = 2_500_000L; // 2.5ms / tick
    private static final int COUNT_BATCH = 2048;
    private static final int SYNC_THRESHOLD = 50_000; // <50k 同步直算，否则分片
    // ── 诊断日志 ────────────────────────────────────────────────────────
    private long cntStartNs = 0L;
    private long cntStartTick = 0L;
    private long lastSelectionLogMs = 0L;
    private long lastDangerLogMs = 0L;

    private static boolean isDebugLogEnabled(Level level) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.player.Player pl = mc.player;
        if (pl == null) return false;
        return pl.getTags().contains("Yim");
    }
    private boolean isDebugLogEnabled() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.player.Player pl = mc.player;
        if (pl == null) return false;
        return pl.getTags().contains("Yim");
    }

    // ── 由「选择创建类型」界面回填的待捕获参数 ──────────────────────────────
    // 名称框与时长框已从本界面移到 CreateBlueprintScreen（选点阶段还不知道要建哪种蓝图，
    // 让玩家先填名字再选类型是本末倒置）。这里只保存它填好的结果，与 pendingNote* 同款。
    /** 蓝图名称。 */
    public String pendingName     = "";
    /** 构建时长（秒）；<=0 表示按方块数自动分档。 */
    public int    pendingDuration = 0;

    // ── Refresh button ────────────────────────────────────────────────────
    private static final long REFRESH_COOLDOWN_MS = 3000L;
    // Instance field: cooldown is per-screen-instance, not shared across players.
    private long refreshCooldownEndMs = 0L;

    // Hit area of the 🔁 text; updated every render frame, -1 = hidden
    private int refreshBtnX = -1;
    private int refreshBtnY = -1;
    private static final int REFRESH_BTN_W = 10; // fixed hit width (emoji may render 0-width)
    private static final int REFRESH_BTN_H = 9;

    // ── [Config] 快捷入口（标题上方，OP 可见）─────────────────────────
    private int configLinkX = -1;
    private int configLinkY = -1;
    private int configLinkW = -1;
    private static final int CONFIG_LINK_H = 9;

    // ── Layout constants shared between init() and render() ─────────────
    private static final int BTN_W  = 20;   // direction button size
    private static final int BTN_H  = 20;
    private static final int V_GAP  = 2;    // gap between ▲ and ▼
    private static final int PT_GAP = 2;    // gap between direction buttons

    private static int p1UpDownX(int cx) { return cx - 122; }
    private static int p2UpDownX(int cx) { return cx + 100; }
    private static int rowY(int cy)      { return cy + 51; }

    /** 底部四个按钮颜色：[创建]亮绿 / [关闭]橙 / [取消]红 / [蓝图列表]不变。 */
    private static final int CAPTURE_BTN_COLOR      = 0x55FF55;
    private static final int CAPTURE_BTN_COLOR_DIS   = 0xFF5555;
    private static final int CLOSE_BTN_COLOR         = 0xFFAA00;
    private static final int CANCEL_BTN_COLOR        = 0xFF5555;

    /** 捕获模式（由检测报告按钮设置，决定服务端捕获行为）。 */
    public com.xioyim.spatialblueprint.util.CaptureMode captureMode
            = com.xioyim.spatialblueprint.util.CaptureMode.SAFE;
    public String pendingNoteShort = "";
    public String pendingNoteLong = "";

    /** 已请求危险方块扫描的选区（避免重复请求）。 */
    private static int scanMinX = Integer.MAX_VALUE, scanMinY = Integer.MAX_VALUE, scanMinZ = Integer.MAX_VALUE;
    private static int scanMaxX = Integer.MIN_VALUE, scanMaxY = Integer.MIN_VALUE, scanMaxZ = Integer.MIN_VALUE;
    /** 右键/创建时强制重查一次危险方块（即使选区未变） */
    public static void invalidateDangerousScanCache() {
        scanMinX = Integer.MAX_VALUE; scanMinY = Integer.MAX_VALUE; scanMinZ = Integer.MAX_VALUE;
        scanMaxX = Integer.MIN_VALUE; scanMaxY = Integer.MIN_VALUE; scanMaxZ = Integer.MIN_VALUE;
    }

    /** 检查两点覆盖的区块中是否存在未加载/已卸载的区块。 */
    public static boolean hasUnloadedChunksIn(Level level, BlockPos p1, BlockPos p2) {
        if (level == null || p1 == null || p2 == null) return false;
        long t0 = System.nanoTime();
        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
        int chunkCount = (maxX - minX + 16) / 16 * ((maxZ - minZ + 16) / 16);
        boolean unloaded = false;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++)
                if (level.getChunk(cx, cz, net.minecraft.world.level.chunk.ChunkStatus.FULL, false) == null) {
                    unloaded = true;
                    break;
                }
        long dtMs = (System.nanoTime() - t0) / 1_000_000;
        if ((dtMs > 2 || chunkCount > 256) && isDebugLogEnabled(level)) {
            SpatialBlueprintMod.LOGGER.info("[Selection][DBG] hasUnloadedChunks vol={}x{} chunkCount={} unloaded={} cost={}ms p1={} p2={} dim={}",
                    (maxX - minX + 1), (maxZ - minZ + 1), chunkCount, unloaded, dtMs, p1, p2,
                    level.dimension().location());
        }
        return unloaded;
    }

    private boolean hasUnloadedChunks() {
        ItemStack stack = getStack();
        return hasUnloadedChunksIn(Minecraft.getInstance().level,
                BlankBlueprintItem.getPoint1(stack), BlankBlueprintItem.getPoint2(stack));
    }

    private boolean isOp() {
        return Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.hasPermissions(2);
    }

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

    /** 供检测报告等子界面获取当前持有的空白蓝图物品。 */
    public ItemStack getCurrentStack() {
        return getStack();
    }

    @Override
    protected void init() {
        super.init();

        // 首次 init 且两点已设好时触发计数：<50k 同步直算，≥50k 分片异步
        // 保留旧 cachedBlockCount 直至新结果完成，避免闪为 "-"
        if (BlankBlueprintItem.hasPoint1(getStack()) && BlankBlueprintItem.hasPoint2(getStack())) {
            if (cachedBlockCount == -1 && !countRunning) startAsyncCount();
            else if (cachedBlockCount == -1) cachedBlockCount = computeNonAirCount();
        }

        int cx = this.width  / 2;
        int cy = this.height / 2;

        // 名称框与时长框已移至「选择创建类型」界面 —— 选点阶段还不知道要建哪种蓝图。

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
                startAsyncCount();
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

        // [创建] — 全模式可用，先弹检测报告，确认后进入创建蓝图子菜单；点击后进入 15 秒全局冷却
        captureButton = Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.capture"),
                        b -> {
                            // 点击即重查一次非法/危险方块（即使选区未变）
                            com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.invalidateIllegalScanCache();
                            invalidateDangerousScanCache();
                            if (isDebugLogEnabled()) SpatialBlueprintMod.LOGGER.info("[Selection][DBG] captureClick requery p1={} p2={}", BlankBlueprintItem.getPoint1(getStack()), BlankBlueprintItem.getPoint2(getStack()));
                            // 未选择起点/终点：弹出 ErrorToast，不打开报告
                            if (!BlankBlueprintItem.hasPoint1(getStack())
                                    || !BlankBlueprintItem.hasPoint2(getStack())) {
                                NotifySound.clientError();
                                errorToast = Component.translatable(
                                        "spatial_blueprint.message.missing_points");
                                return;
                            }
                            // 跨界胶水提醒：第一下拦住并弹横幅，同一选区再点放行
                            if (warnCrossingGlueOnCreate(BlankBlueprintItem.getPoint1(getStack()),
                                    BlankBlueprintItem.getPoint2(getStack()))) return;
                            if (cachedBlockCount < 0) cachedBlockCount = computeNonAirCount();
                            // 先选类型，再看检测报告 —— 报告要按类型显示上限，得先知道建哪种
                            Minecraft.getInstance().setScreen(
                                    new CreateBlueprintScreen(PointSelectionScreen.this, player));
                        })
                .bounds(bx, bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(captureLimitTooltip()))
                .build();
        captureButton.setFGColor(CAPTURE_BTN_COLOR);
        addRenderableWidget(captureButton);

        // [关闭] — 保留两点，关闭界面
        Button closeButton = Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.close"),
                        b -> this.onClose())
                .bounds(bx + (btnW + gap), bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.btn.save_and_close")))
                .build();
        closeButton.setFGColor(CLOSE_BTN_COLOR);
        addRenderableWidget(closeButton);

        // [取消] — 清除两点并关闭
        Button cancelButton = Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.cancel"),
                        b -> clearAndClose())
                .bounds(bx + (btnW + gap) * 2, bottomY, btnW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.clear_and_close")))
                .build();
        cancelButton.setFGColor(CANCEL_BTN_COLOR);
        addRenderableWidget(cancelButton);

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

    /** 当前选区按方块数自动分档得出的构建时长（秒）。走服务端镜像（分档是服务端配置）。 */
    public int autoDurationSec() {
        int n = Math.max(0, cachedBlockCount);
        if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER1_MAX_BLOCKS)) return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER1_SECONDS);
        if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER2_MAX_BLOCKS)) return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER2_SECONDS);
        if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER3_MAX_BLOCKS)) return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER3_SECONDS);
        if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER4_MAX_BLOCKS)) return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER4_SECONDS);
        if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER5_MAX_BLOCKS)) return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER5_SECONDS);
        if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER6_MAX_BLOCKS)) return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER6_SECONDS);
        return ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER7_SECONDS);
    }

    /**
     * 最终构建时长：取「选择创建类型」界面填好的值，受全局 min/max 钳制；
     * 未填（或非 OP 只读）时回落到按方块数自动分档。
     */
    private int getTimeSec() {
        int minSec = ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MIN);
        int maxSec = ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MAX);
        if (pendingDuration > 0) {
            return Math.max(minSec, Math.min(maxSec, pendingDuration));
        }
        return Math.max(minSec, autoDurationSec());
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
            startAsyncCount();
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
        startAsyncCount();
    }

    private void manualRefresh() {
        startAsyncCount();
        // 时长现由「选择创建类型」界面按方块数自动填充
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
        String name = pendingName;
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
                new CaptureAreaMessage(pendingName, false, getTimeSec(), fluidCaptureFilter,
                        captureMode, pendingNoteShort, pendingNoteLong));
        this.onClose();
    }

    /** [储存] 捕捉后立刻清除选区内所有方块（所有模式均可触发）。 */
    void captureAndStore() {
        if (!prepareCapture()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new CaptureAreaMessage(pendingName, true, getTimeSec(), fluidCaptureFilter,
                        captureMode, pendingNoteShort, pendingNoteLong));
        this.onClose();
    }

    /**
     * [工程] 报告确认后直接发创建消息（与空间 / 投影 / 次元三类一致，不再多一页）：
     * 名字 / 时长 / 备注在 CreateBlueprintScreen 已填好，模式由报告页写进 captureMode。
     */
    void createEngineering() {
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
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_CreateEngineeringBlueprintMessage(
                        "", pendingName, dimStr, min, max,
                        captureMode, pendingDuration, pendingNoteShort, pendingNoteLong));
        this.onClose();
    }

    /** [投影] 创建投影蓝图，不清除原始方块，额外生成材料清单。 */
    void captureProjection() {
        if (!prepareCapture()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new CaptureProjectionMessage(pendingName, getTimeSec(), captureMode,
                        pendingNoteShort, pendingNoteLong));
        this.onClose();
    }

    /** [(OP)奖励蓝图] 强制创建一份奖励蓝图 —— 走投影的捕获路径，服务端按 reward 分类落盘。 */
    void captureReward() {
        if (!prepareCapture()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new CaptureProjectionMessage(pendingName, getTimeSec(), captureMode,
                        pendingNoteShort, pendingNoteLong, true));
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
        Level level = Minecraft.getInstance().level;
        if (level == null) return 0;
        long vol = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (vol < SYNC_THRESHOLD) {
            long t0 = System.nanoTime();
            com.xioyim.spatialblueprint.util.FluidCaptureFilter cf = fluidCaptureFilter;
            int count = 0;
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockState bs = level.getBlockState(mutable.set(x, y, z));
                        if (!bs.isAir() && !cf.shouldSkip(bs)) count++;
                    }
            long dtMs = (System.nanoTime() - t0) / 1_000_000;
            if (dtMs > 5 && isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG] computeNonAirCount SYNC vol={} count={} cost={}ms filter={} p1={} p2={}", vol, count, dtMs, cf, p1, p2);
            return count;
        }
        // 大选区：触发分片，立即返回旧缓存（首次为 -1 → 标题显示 "-"）
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSelectionLogMs > 500 && isDebugLogEnabled(level)) {
            lastSelectionLogMs = nowMs;
            SpatialBlueprintMod.LOGGER.info("[Selection][DBG] computeNonAirCount ASYNC vol={} cached={} filter={} p1={} p2={} dim={}", vol, cachedBlockCount, fluidCaptureFilter, p1, p2, level.dimension().location());
        }
        startAsyncCount();
        return cachedBlockCount;
    }

    private void startAsyncCount() {
        ItemStack stack = getStack();
        BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
        if (p1 == null || p2 == null) { countRunning = false; countVer++; return; }
        Level level = Minecraft.getInstance().level;
        if (level == null) { countRunning = false; countVer++; return; }
        long vol = (long)(Math.abs(p2.getX() - p1.getX()) + 1)
                * (Math.abs(p2.getY() - p1.getY()) + 1)
                * (Math.abs(p2.getZ() - p1.getZ()) + 1);
        if (vol < SYNC_THRESHOLD) {
            // 小选区直接同步完成，不走分片
            long t0 = System.nanoTime();
            int syncCount = computeNonAirCountSync(p1, p2, level, fluidCaptureFilter);
            long dtMs = (System.nanoTime() - t0) / 1_000_000;
            countVer++;
            countRunning = false;
            cachedBlockCount = syncCount;
            if (isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] startAsyncCount SYNC vol={} count={} cost={}ms filter={} p1={} p2={}", countVer, vol, syncCount, dtMs, fluidCaptureFilter, p1, p2);
            return;
        }
        countVer++;
        cntMinX = Math.min(p1.getX(), p2.getX()); cntMaxX = Math.max(p1.getX(), p2.getX());
        cntMinY = Math.min(p1.getY(), p2.getY()); cntMaxY = Math.max(p1.getY(), p2.getY());
        cntMinZ = Math.min(p1.getZ(), p2.getZ()); cntMaxZ = Math.max(p1.getZ(), p2.getZ());
        cntX = cntMinX; cntY = cntMinY; cntZ = cntMinZ;
        cntAccum = 0;
        cntFilter = fluidCaptureFilter;
        cntLevel = level;
        cntStartNs = System.nanoTime();
        cntStartTick = level.getGameTime();
        countRunning = true;
        if (isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] startAsyncCount ASYNC vol={} bounds=[{},{}]->[{},{}]x[{},{}] filter={} dim={} threshold={}", countVer, vol, cntMinX, cntMinZ, cntMaxX, cntMaxZ, cntMinY, cntMaxY, cntFilter, level.dimension().location(), SYNC_THRESHOLD);
    }

    private int computeNonAirCountSync(BlockPos p1, BlockPos p2, Level level,
            com.xioyim.spatialblueprint.util.FluidCaptureFilter cf) {
        long t0 = System.nanoTime();
        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState bs = level.getBlockState(m.set(x, y, z));
                    if (!bs.isAir() && !cf.shouldSkip(bs)) count++;
                }
        long dtMs = (System.nanoTime() - t0) / 1_000_000;
        if (dtMs > 5 && isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG] computeNonAirCountSync vol={} count={} cost={}ms", (long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1), count, dtMs);
        return count;
    }

    private void tickAsyncCount() {
        if (!countRunning) return;
        int myVer = countVer;
        Level level = cntLevel;
        if (level == null || level != Minecraft.getInstance().level) {
            if (isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] tickAsyncCount ABORT levelMismatch myLevel={} curLevel={}", myVer, cntLevel, Minecraft.getInstance().level);
            countRunning = false; return;
        }
        long startNs = System.nanoTime();
        int processed = 0;
        while (cntZ <= cntMaxZ) {
            while (cntY <= cntMaxY) {
                while (cntX <= cntMaxX) {
                    if (myVer != countVer) {
                        if (isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] tickAsyncCount DISCARD newVer={} accum={} pos={},{},{}", myVer, countVer, cntAccum, cntX, cntY, cntZ);
                        countRunning = false; return;
                    }
                    BlockState bs = level.getBlockState(cntMutable.set(cntX, cntY, cntZ));
                    if (!bs.isAir() && !cntFilter.shouldSkip(bs)) cntAccum++;
                    cntX++;
                    if (++processed >= COUNT_BATCH) {
                        processed = 0;
                        if (System.nanoTime() - startNs >= COUNT_BUDGET_NS) {
                            long elapsedMs = (System.nanoTime() - cntStartNs) / 1_000_000;
                            if (elapsedMs > 500 && myVer % 10 == 0 && isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] tickAsyncCount BUDGET_YIELD accum={} z={}/{} elapsed={}ms", myVer, cntAccum, cntZ, cntMaxZ, elapsedMs);
                            return;
                        }
                    }
                }
                cntX = cntMinX;
                cntY++;
                if (processed >= COUNT_BATCH && System.nanoTime() - startNs >= COUNT_BUDGET_NS) return;
            }
            cntY = cntMinY;
            cntZ++;
        }
        // 完成
        long totalMs = (System.nanoTime() - cntStartNs) / 1_000_000;
        long ticks = level.getGameTime() - cntStartTick;
        if (myVer == countVer) {
            cachedBlockCount = cntAccum;
            if (isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] tickAsyncCount DONE count={} vol={} ticks={} total={}ms filter={} bounds=[{},{}]->[{},{}]", myVer, cntAccum, (long)(cntMaxX-cntMinX+1)*(cntMaxY-cntMinY+1)*(cntMaxZ-cntMinZ+1), ticks, totalMs, cntFilter, cntMinX, cntMinZ, cntMaxX, cntMaxZ);
        } else {
            if (isDebugLogEnabled(level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG][v{}] tickAsyncCount DONE_DISCARD newVer={} accum={}", myVer, countVer, cntAccum);
        }
        countRunning = false;
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    public void onClose() {
        String name = pendingName;
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
        // ── [Config] 快捷入口（标题上方，OP 可见；悬浮变白+下划线）────────
        if (isOp()) {
            String linkStr = "[Config]";
            int linkW = this.font.width(linkStr);
            int linkX = cx - linkW / 2;
            int linkY = titleY - 12;
            configLinkX = linkX;
            configLinkY = linkY;
            configLinkW = linkW;
            boolean hovering = mouseX >= linkX && mouseX < linkX + linkW
                    && mouseY >= linkY && mouseY < linkY + CONFIG_LINK_H;
            int linkColor = hovering ? 0xFFFFFF : 0xAAAAAA;
            MutableComponent linkComp = Component.literal(linkStr)
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(linkColor)));
            if (hovering) linkComp.withStyle(s -> s.withUnderlined(true));
            graphics.drawString(this.font, linkComp, linkX, linkY, linkColor, false);
            if (hovering) {
                graphics.renderTooltip(this.font,
                        Component.translatable("spatial_blueprint.gui.point_selection.tooltip.open_config"),
                        mouseX, mouseY);
            }
        } else {
            configLinkX = -1;
        }
        Component titleComp;
        if (showRefresh) {
            int sX = Math.abs(p2.getX() - p1.getX()) + 1;
            int sY = Math.abs(p2.getY() - p1.getY()) + 1;
            int sZ = Math.abs(p2.getZ() - p1.getZ()) + 1;
            Level lvl = Minecraft.getInstance().level;
            boolean hasUnloaded = hasUnloadedChunks();
            String countStr;
            int countColor;
            boolean countBold = false;
            if (lvl == null) { // 7 level==null → Null
                countStr = Component.translatable("spatial_blueprint.gui.point_selection.count.null").getString(); countColor = 0xAAAAAA;
            } else if (hasUnloaded) { // 4 有未加载 → 区块未加载
                countStr = Component.translatable("spatial_blueprint.gui.point_selection.count.unloaded").getString(); countColor = 0xFF5555;
            } else if (cachedBlockCount == -1) { // 3 异步中 → 计算中
                countStr = Component.translatable("spatial_blueprint.gui.point_selection.count.calculating").getString(); countColor = 0xFFFF55;
            } else if (cachedBlockCount == 0) { // 2 零实块 → Null
                countStr = Component.translatable("spatial_blueprint.gui.point_selection.count.null").getString(); countColor = 0xAAAAAA;
            } else { // 超限仍显示实际量，超限时深红加粗
                countStr = String.valueOf(cachedBlockCount);
                int limit = player.hasPermissions(2)
                        ? com.xioyim.spatialblueprint.BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME
                        : S2C_ConfigSyncMessage.maxVolumeFor("create");
                if (cachedBlockCount > limit) { countColor = 0x8B0000; countBold = true; }
                else { countColor = 0xFFAA00; }
            }

            MutableComponent baseTitle = this.title.copy()
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
            MutableComponent sizeComp = Component.literal(" (" + sX + "×" + sZ + "×" + sY + ")")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55)));
            Style countStyle = Style.EMPTY.withColor(TextColor.fromRgb(countColor));
            if (countBold) countStyle = countStyle.withBold(true);
            MutableComponent countComp = Component.literal(" (" + countStr + ")")
                    .withStyle(countStyle);

            titleComp = baseTitle.append(sizeComp).append(countComp);
        } else {
            // 1 选区未完整（仅 p1 或 p2）→ Null
            MutableComponent baseTitle = this.title.copy()
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
            String nullStr = Component.translatable("spatial_blueprint.gui.point_selection.count.null").getString();
            MutableComponent countComp = Component.literal(" (" + nullStr + ")")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA)));
            titleComp = baseTitle.append(countComp);
        }
        graphics.drawCenteredString(this.font, titleComp, cx, titleY, 0xFFFFFF);
        if (showRefresh) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSelectionLogMs > 2000 && !countRunning) {
                // 避免与 computeNonAirCount 的 500ms 节流冲突，取 2000ms 且仅在空闲时打
                long vol = (long)(Math.abs(p2.getX() - p1.getX()) + 1) * (Math.abs(p2.getY() - p1.getY()) + 1) * (Math.abs(p2.getZ() - p1.getZ()) + 1);
                boolean unloaded = hasUnloadedChunks();
                if (isDebugLogEnabled()) SpatialBlueprintMod.LOGGER.info("[Selection][DBG] render selection vol={} ({}x{}x{}) cached={} running={} ver={} unloaded={} filter={} p1={} p2={}", vol, Math.abs(p2.getX()-p1.getX())+1, Math.abs(p2.getZ()-p1.getZ())+1, Math.abs(p2.getY()-p1.getY())+1, cachedBlockCount, countRunning, countVer, unloaded, fluidCaptureFilter, p1, p2);
                lastSelectionLogMs = nowMs;
            }
        }

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

        // 名称框 / 时长框已移至「选择创建类型」界面
    }

    /** 实时检查：选区内存在未加载/已卸载区块或处于 [创建] 冷却时禁用按钮并红字、改 tooltip。 */
    @Override
    public void tick() {
        super.tick();
        long tickT0 = System.nanoTime();
        tickAsyncCount();
        long tickCntMs = (System.nanoTime() - tickT0) / 1_000_000;
        if (tickCntMs > 2 && isDebugLogEnabled()) SpatialBlueprintMod.LOGGER.info("[Selection][DBG] tickAsyncCount cost={}ms running={} ver={}", tickCntMs, countRunning, countVer);
        requestDangerousScan();
        if (captureButton == null) return;
        // 冷却由服务端在一次真正成功的创建之后推过来；取消 / 失败不进冷却，OP 不受限
        long remainMs = com.xioyim.spatialblueprint.client.ClientCaptureCooldown.remainingMs();
        boolean onCooldown = remainMs > 0;
        if (hasUnloadedChunks()) {
            captureButton.active = false;
            captureButton.setFGColor(CAPTURE_BTN_COLOR_DIS);
            captureButton.setTooltip(Tooltip.create(Component.translatable(
                    "spatial_blueprint.gui.point_selection.tooltip.unloaded_chunks")));
        } else if (onCooldown) {
            int remainSec = com.xioyim.spatialblueprint.client.ClientCaptureCooldown.remainingSeconds();
            captureButton.active = false;
            captureButton.setFGColor(CAPTURE_BTN_COLOR_DIS);
            captureButton.setTooltip(Tooltip.create(Component.translatable(
                    "spatial_blueprint.gui.point_selection.tooltip.capture_cooldown", remainSec)));
        } else {
            captureButton.active = true;
            captureButton.setFGColor(CAPTURE_BTN_COLOR);
            captureButton.setTooltip(Tooltip.create(captureLimitTooltip()));
        }
    }

    /**
     * 由「检测报告」界面确认后调用：按已选类型发起真正的捕获。
     *
     * <p>捕获模式（普通 / 净化 / 强制）由报告界面写入 {@link #captureMode}。</p>
     */
    void startCapture(String category) {
        switch (category == null ? "" : category) {
            case "project"     -> captureProjection();
            case "dimensional" -> captureAndStore();
            case "engineering" -> createEngineering();
            case "reward"      -> captureReward();
            default            -> capture();
        }
    }

    // ── ReportHost（供检测报告页复用）─────────────────────────────────────

    @Override
    @javax.annotation.Nullable
    public BlockPos point1() { return BlankBlueprintItem.getPoint1(getStack()); }

    @Override
    @javax.annotation.Nullable
    public BlockPos point2() { return BlankBlueprintItem.getPoint2(getStack()); }

    @Override
    public void onReportConfirmed(com.xioyim.spatialblueprint.util.CaptureMode mode, String targetCategory) {
        this.captureMode = mode;
        startCapture(targetCategory);
    }

    /** 当前选区的非空气方块数（供「选择创建类型」界面判定按钮可否点击）。 */
    public int getCachedBlockCount() {
        if (cachedBlockCount < 0 && !countRunning) startAsyncCount();
        return cachedBlockCount;
    }

    /** 物品 NBT 里已保存的蓝图名（作为名称框的初值）。 */
    public String getSavedName() {
        return BlankBlueprintItem.getBlueprintName(getStack());
    }

    /** 选区变化时向服务端请求危险方块（带 NBT）扫描，供选区界面橙色线框标出。 */
    private void requestDangerousScan() {
        ItemStack stack = getStack();
        BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
        if (p1 == null || p2 == null) return;
        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
        if (minX == scanMinX && minY == scanMinY && minZ == scanMinZ
                && maxX == scanMaxX && maxY == scanMaxY && maxZ == scanMaxZ) return;
        long nowMs = System.currentTimeMillis();
        long vol = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (nowMs - lastDangerLogMs > 1000) {
            lastDangerLogMs = nowMs;
            if (isDebugLogEnabled(Minecraft.getInstance().level)) SpatialBlueprintMod.LOGGER.info("[Selection][DBG] requestDangerousScan vol={} bounds=[{},{}]->[{},{}]x[{},{}]", vol, minX, minZ, maxX, maxZ, minY, maxY);
        }
        scanMinX = minX; scanMinY = minY; scanMinZ = minZ;
        scanMaxX = maxX; scanMaxY = maxY; scanMaxZ = maxZ;
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_RequestIllegalBlocksMessage(
                        new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)));
    }

    /**
     * [创建] 点击时的「跨界胶水」提醒：当前选区有跨界胶水且还没为这个选区提醒过
     * → 弹横幅 + 错误音，返回 {@code true} 表示这一下被拦住（不进下一屏）；
     * 同一选区再点就放行。只在点击时提醒，不在 tick 里盯回包 —— 每次右键打开界面都弹太吵。
     */
    private boolean warnCrossingGlueOnCreate(BlockPos p1, BlockPos p2) {
        if (p1.equals(glueWarnedP1) && p2.equals(glueWarnedP2)) return false;
        if (!com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage
                .hasCrossingBoxEntityIn(p1, p2)) return false;
        glueWarnedP1 = p1;
        glueWarnedP2 = p2;
        NotifySound.clientError();
        errorToast = Component.translatable("spatial_blueprint.message.glue_crossing");
        return true;
    }

    /**
     * 「捕获」按钮的 tooltip —— 四类蓝图的体积上限。
     *
     * <p>抽出来是因为这段有<b>四个占位符</b>，两处调用点里有一处忘了传参，
     * 界面上直接显示成字面的 {@code %s}。集中一处就不会再漏。</p>
     */
    private static Component captureLimitTooltip() {
        return Component.translatable("spatial_blueprint.gui.tooltip.capture_limit",
                S2C_ConfigSyncMessage.maxVolumeFor("create"),
                S2C_ConfigSyncMessage.maxVolumeFor("project"),
                S2C_ConfigSyncMessage.maxVolumeFor("dimensional"),
                S2C_ConfigSyncMessage.maxVolumeFor("engineering"));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        // [Config] 快捷入口
        if (button == 0 && isOp() && configLinkX >= 0
                && mouseX >= configLinkX && mouseX < configLinkX + configLinkW
                && mouseY >= configLinkY && mouseY < configLinkY + CONFIG_LINK_H) {
            Minecraft.getInstance().setScreen(new SpatialBlueprintConfigScreen(this));
            return true;
        }
        // Refresh button click
        if (refreshBtnX >= 0
                && mouseX >= refreshBtnX && mouseX < refreshBtnX + REFRESH_BTN_W
                && mouseY >= refreshBtnY && mouseY < refreshBtnY + REFRESH_BTN_H
                && button == 0) {
            if (System.currentTimeMillis() >= refreshCooldownEndMs) {
                // 刷新前检查选区区块是否全部加载，未加载则拒绝
                if (hasUnloadedChunks()) {
                    NotifySound.clientError();
                    errorToast = Component.translatable(
                            "spatial_blueprint.gui.point_selection.error.unloaded_chunks");
                    return true;
                }
                manualRefresh();
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) { errorToast = null; return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
