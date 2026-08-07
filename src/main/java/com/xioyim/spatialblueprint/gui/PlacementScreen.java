package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer;
import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode;
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage;
import com.xioyim.spatialblueprint.client.PlacementPreValidator;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_EditMaterialMessage;
import com.xioyim.spatialblueprint.network.C2S_MaterialDepositMessage;
import com.xioyim.spatialblueprint.network.C2S_SaveMaterialListMessage;
import com.xioyim.spatialblueprint.network.PlaceBlueprintMessage;
import org.lwjgl.glfw.GLFW;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class PlacementScreen extends Screen implements ErrorToastSupport {

    private final Player player;
    /** Non-final: updated in tick() when ItemSyncUtil.syncHand() replaces the held item (Bug-1). */
    private ItemStack stack;
    /**
     * 蓝图数据；以下情况为 null：
     * <ul>
     *   <li>创造模式持有空白蓝图（无 blueprintId）</li>
     *   <li>多人模式尚未从服务端下载数据（有 blueprintId，等待 S2C 到达）</li>
     * </ul>
     * 当 {@link ClientBlueprintCache} 收到 S2C 数据后，{@link #tick()} 会将其更新为非 null
     * 并刷新界面。
     */
    @Nullable
    private BlueprintData data;

    private int xOffset;
    private int yOffset;
    private int zOffset;
    private Rotation rotation;
    private boolean locked;
    private BlockPos lockedPos;
    private boolean clearFirst;
    /** Tracks the last download state rendered, to avoid rebuilding widgets every tick. */
    private enum DownloadState { NONE, DOWNLOADING, FAILED, DONE }
    private DownloadState lastDownloadState = DownloadState.NONE;
    /** Reference to the "下载中 xx%..." button so tick() can update its label without full rebuild. */
    @Nullable private Button downloadingBtn;
    private int lastDownloadPct = -1;

    /** Refreshed on every init() call (screen open, button press). */
    @Nullable
    private PlacementPreValidator currentValidation;

    /** ⌘ 创建工程配置按钮引用，供 tick() 每帧刷新激活状态。 */
    @Nullable
    private Button engBtn;


    // ── Material panel layout constants (shared between render and mouse handling) ─
    private static final int MAT_COLS      = 4;
    private static final int MAT_PANEL_W   = 298;
    private static final int MAT_PAD       = 8;
    private static final int MAT_HEADER_H  = 12;
    private static final int MAT_SEP_PAD   = 4;
    /** 内容宽度 = PANEL_W - left_pad - right_pad = 282（滚动条已隐藏，空间并入右侧 padding） */
    private static final int MAT_CONTENT_W = MAT_PANEL_W - MAT_PAD * 2; // 282
    private static final int MAT_CELL_W    = MAT_CONTENT_W / MAT_COLS;  // 70
    private static final int MAT_BOT_BTN_H = 16; // 底部正方形按钮边长
    private static final int MAT_CELL_H    = 17;
    private static final int MAT_VISIBLE   = 6;
    private static final float MAT_SCALE   = 0.75f;
    private static final int MAT_ICON_SCR  = 12; // (int)(16 * MAT_SCALE)

    /** Whether the material list panel is currently visible. */
    private boolean showMaterials = false;
    /** Current first-visible row of the material panel (scroll offset). */
    private int matScrollRow = 0;
    /** Maximum scroll row, updated each render frame. */
    private int matMaxScrollRow = 0;
    /** Bounds of the material panel in screen space (for scroll hit-test). */
    private int matPanelX, matPanelY, matPanelW, matPanelH;
    /** Y of the material content area (below header/separator); -1 = not rendered. */
    private int matContentY = -1;
    /** Bounds of the ✚ add-material button in screen space; W=0 when not rendered. */
    private int matAddBtnX, matAddBtnY, matAddBtnW = 0, matAddBtnH;
    /** Bounds of the ⎙ save-to-file button in screen space; W=0 when not rendered. */
    private int matSaveBtnX, matSaveBtnY, matSaveBtnW = 0, matSaveBtnH;
    /** 底部按钮行 Button 实例（手动调用 render，不加入 renderables）*/
    @Nullable private Button matBtnAdd, matBtnDep, matBtnWd, matBtnSave;

    // ── Material context menu state ──────────────────────────────────────────
    @Nullable private String ctxBlockId   = null;
    @Nullable private String ctxBlockName = null;
    private int ctxX, ctxY;
    // ── Count edit overlay state ─────────────────────────────────────────────
    @Nullable private String       editBlockId = null;
    private final StringBuilder    editInput   = new StringBuilder();
    // ── Delete confirm overlay state ─────────────────────────────────────────
    @Nullable private String deleteBlockId = null;

    private Component errorToast;

    // ── Deposit / Withdraw overlay state ─────────────────────────────────────
    @Nullable private String       depositBlockId   = null;
    @Nullable private String       depositBlockName = null;
    private boolean                depositMode      = true;  // true=存入, false=取出
    private final StringBuilder    depositInput     = new StringBuilder();

    /** 客户端乐观跟踪的已存入材料（blockId → count）。 */
    private final Map<String, Integer> localDeposited = new LinkedHashMap<>();
    /** 客户端乐观跟踪的已取出次数（不等待服务端同步，避免UI延迟）。 */
    private int localWithdrawCount = 0;

    /**
     * Snapshot of {@code data.materialList.blockCounts.entrySet()} — rebuilt by
     * {@link #buildMatEntries()} so {@link #renderMaterialPanel} does not allocate a
     * new list on every frame (M9).
     */
    private List<Map.Entry<String, Integer>> cachedMatEntries = List.of();

    // ── Header quick-deposit/withdraw button bounds ───────────────────────────
    private int matQuickDepBtnX, matQuickDepBtnY, matQuickDepBtnW = 0, matQuickDepBtnH;
    private int matQuickWdBtnX,  matQuickWdBtnY,  matQuickWdBtnW  = 0, matQuickWdBtnH;

    // ── Deploy button text colors ─────────────────────────────────────────
    /** White — survival OK / blocked inactive text (MC handles gray via active=false) */
    private static final int COLOR_WHITE = 0xFFFFFF;
    /** Red — creative mode force-place */
    private static final int COLOR_RED   = 0xFF5555;

    // ─────────────────────────────────────────────────────────────────────

    public PlacementScreen(Player player, ItemStack stack, BlueprintData data) {
        super(Component.translatable("spatial_blueprint.gui.placement.title"));
        this.player    = player;
        this.stack     = stack;
        this.data      = data;
        this.xOffset     = SpatialBlueprintItem.getXOffset(stack);
        this.yOffset     = SpatialBlueprintItem.getYOffset(stack);
        this.zOffset     = SpatialBlueprintItem.getZOffset(stack);
        this.rotation    = SpatialBlueprintItem.getRotation(stack);
        this.locked      = SpatialBlueprintItem.isLocked(stack);
        this.lockedPos   = SpatialBlueprintItem.getLockedPos(stack);
        this.clearFirst  = SpatialBlueprintItem.getClearFirst(stack);
        // 从物品 NBT 初始化客户端存入记录
        this.localDeposited.putAll(ProjectionBlueprintItem.getDepositedMaterials(stack));
        // 从物品 NBT 初始化取出次数（后续乐观递增，无需等服务端同步）
        this.localWithdrawCount = ProjectionBlueprintItem.getWithdrawCount(stack);
        buildMatEntries(); // M9: pre-build entry snapshot so renderMaterialPanel needs no per-frame alloc
    }

    /** Rebuilds {@link #cachedMatEntries} from the live material map, or clears it when absent. */
    private void buildMatEntries() {
        if (data != null && data.materialList != null) {
            cachedMatEntries = new ArrayList<>(data.materialList.blockCounts.entrySet());
        } else {
            cachedMatEntries = List.of();
        }
    }

    /** 当前是否有可展示/编辑的材料清单（动态计算，反映客户端侧删除操作）。 */
    private boolean isProjection() {
        return data != null && data.materialList != null && !data.materialList.blockCounts.isEmpty();
    }

    /** 是否为空间/次元蓝图（材料清单仅供查阅，无存取/放置限制）。 */
    private boolean isSpatialOnly() {
        return stack.getItem() instanceof SpatialBlueprintItem;
    }

    @Override
    protected void init() {
        super.init();

        // 材料面板底部按钮（手动 render，不加入 renderables 以保证 z 顺序正确）
        int sz = MAT_BOT_BTN_H;
        matBtnAdd  = Button.builder(Component.literal("✚"), b -> {}).bounds(0, 0, sz, sz).build();
        matBtnDep  = Button.builder(Component.literal("✎"), b -> {}).bounds(0, 0, sz, sz).build();
        matBtnWd   = Button.builder(Component.literal("♻"), b -> {}).bounds(0, 0, sz, sz).build();
        matBtnSave = Button.builder(Component.literal("⎙").withStyle(ChatFormatting.GOLD), b -> {}).bounds(0, 0, sz, sz).build();

        // ── Measurements ────────────────────────────────────────────────
        int dW    = 18;
        int dH    = 18;
        int pGap  = 4;
        int botW  = 52;
        int botH  = 18;

        int cx       = this.width / 2;
        int cy       = this.height / 2;
        // Layout (center-relative, matches FancyMenu layout file):
        // Row1: ▲(-144,18) ↑(-122) ↓(-100) ←(-78) →(-56) 🔁(-34) Align(-12,86) ✚(+78,18) 关闭(+100,36)
        // Row2: ▼(-144,18) 🔒(-122) ♻(-100) ⛒(-78) 流体(-56) 材料(-34,52) 构建(+22,52) ⌘(+78,18) 取消(+100,36)
        int closeBtnW = dW * 2 + 10;  // 关闭/取消按钮宽度 = 42px
        int dX       = cx - 144;
        int r1StartX = dX + dW + pGap;   // cx - 122
        int dStep    = dW + pGap;         // 22
        int row1Y    = cy + 113;
        int row2Y    = cy + 135;
        int deployX  = r1StartX + 4 * dStep + dW + pGap;  // cx - 12
        int alignW   = 86;

        // 渲染模式为"关"时，除对齐按钮、关闭、取消外其余按钮全部禁用
        boolean isOff = BlueprintSelectionRenderer.getAlignMode(stack) == AlignMode.OFF;

        // 准星是否有合法预览目标（已锁定时视为有目标；否则做射线检测）
        boolean hasTarget = locked || BlueprintSelectionRenderer.getPreviewTarget(
                Minecraft.getInstance(), BlueprintConfig.PREVIEW_DISTANCE.get()) != null;

        // ── 刷新客户端预校验结果（data 为 null 时跳过校验，按钮保持禁用）────
        currentValidation = null;
        if (data != null && !isOff && this.minecraft != null && this.minecraft.level != null) {
            currentValidation = PlacementPreValidator.validate(
                    this.minecraft.level, player, computeFinalOrigin(), data, rotation, clearFirst);
        }

        // ── Row 1: [▲] | [↑][↓][←][→][🔁]  [Align] ─────────────────────
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> adjustOffset(0, 1, 0))
                .bounds(dX, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.up")))
                .build()).active = !isOff;
        addRenderableWidget(Button.builder(Component.literal("↑"), b -> adjustOffset(0, 0, 1))
                .bounds(r1StartX, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.south")))
                .build()).active = !isOff;
        addRenderableWidget(Button.builder(Component.literal("↓"), b -> adjustOffset(0, 0, -1))
                .bounds(r1StartX + dStep, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.north")))
                .build()).active = !isOff;
        addRenderableWidget(Button.builder(Component.literal("←"), b -> adjustOffset(-1, 0, 0))
                .bounds(r1StartX + 2 * dStep, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.west")))
                .build()).active = !isOff;
        addRenderableWidget(Button.builder(Component.literal("→"), b -> adjustOffset(1, 0, 0))
                .bounds(r1StartX + 3 * dStep, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.east")))
                .build()).active = !isOff;
        addRenderableWidget(Button.builder(Component.literal("🔁"), b -> rotate())
                .bounds(r1StartX + 4 * dStep, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.rotate")))
                .build()).active = !isOff;

        // ── Align / Download 按钮（五种状态共用同一位置）────────────────────
        // 状态 A：有数据 → 正常循环 Align 按钮
        // 状态 B：无数据且正在下载 → 灰色"请求中..."
        // 状态 C：无数据且未下载（初始）→ 可点击"下载数据"
        // 状态 D：无数据且无 blueprintId（空白蓝图）→ 灰色"无数据"
        // 状态 E：下载失败 → 显示失败原因 + 可点击重试
        String bpId = SpatialBlueprintItem.getBlueprintId(stack);
        boolean hasBpId = bpId != null && !bpId.isEmpty();
        if (data != null) {
            // 状态 A：正常 Align 按钮
            AlignMode curAlign = BlueprintSelectionRenderer.getAlignMode(stack);
            Component alignLabel = switch (curAlign) {
                case END    -> Component.translatable("spatial_blueprint.gui.placement.btn.align.end");
                case CENTER -> Component.translatable("spatial_blueprint.gui.placement.btn.align.center");
                case START  -> Component.translatable("spatial_blueprint.gui.placement.btn.align.start");
                default     -> Component.translatable("spatial_blueprint.gui.placement.btn.align.off");
            };
            // When OFF and no valid target: disable with out-of-range tooltip
            boolean alignNeedsTarget = curAlign == AlignMode.OFF;
            boolean alignHasTarget = !alignNeedsTarget || hasTarget;
            int margin = BlueprintConfig.RENDER_CANCEL_MARGIN.get();
            Component alignTip = alignHasTarget
                    ? Component.translatable("spatial_blueprint.gui.placement.tooltip.align.line1")
                            .append(Component.literal("\n"))
                            .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.align.line2", margin))
                            .append(Component.literal("\n"))
                            .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.align.line3"))
                            .append(Component.literal("\n"))
                            .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.align.line4"))
                    : Component.translatable("spatial_blueprint.gui.placement.tooltip.lock.out_of_range");
            Button alignBtn = addRenderableWidget(Button.builder(alignLabel, b -> cycleAlignMode())
                    .bounds(deployX, row1Y, alignW, botH)
                    .tooltip(Tooltip.create(alignTip))
                    .build());
            alignBtn.active = alignHasTarget;
        } else if (hasBpId && ClientBlueprintCache.isFailed(bpId)) {
            // 状态 E：下载失败，显示失败原因，可点击重试
            String failKey = ClientBlueprintCache.getFailReason(bpId);
            Component failLabel = Component.translatable("spatial_blueprint.gui.placement.btn.download_retry").withStyle(ChatFormatting.RED);
            Component failTip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.download_failed")
                    .append(Component.literal("\n"))
                    .append(Component.translatable(failKey).withStyle(ChatFormatting.RED));
            addRenderableWidget(Button.builder(failLabel, b -> {
                ClientBlueprintCache.clearFailed(bpId);
                requestBlueprintData(bpId);
            }).bounds(deployX, row1Y, alignW, botH)
              .tooltip(Tooltip.create(failTip))
              .build());
        } else if (hasBpId && ClientBlueprintCache.isDownloading(bpId)) {
            // 状态 B：下载中（灰色禁用），显示分块进度
            lastDownloadPct = ClientBlueprintCache.getDownloadProgress(bpId);
            downloadingBtn = addRenderableWidget(Button.builder(
                    buildDownloadLabel(lastDownloadPct), b -> {})
                    .bounds(deployX, row1Y, alignW, botH)
                    .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.downloading")))
                    .build());
            downloadingBtn.active = false;
        } else if (hasBpId) {
            // 状态 C：点击下载
            addRenderableWidget(Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.btn.download_data"),
                    b -> requestBlueprintData(bpId))
                    .bounds(deployX, row1Y, alignW, botH)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.placement.tooltip.download_data")))
                    .build());
        } else {
            // 状态 D：空白蓝图（无 blueprintId），仅创造模式会出现
            Button noDataBtn = addRenderableWidget(Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.btn.align.no_data"), b -> {})
                    .bounds(deployX, row1Y, alignW, botH)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.placement.tooltip.align.no_data")))
                    .build());
            noDataBtn.active = false;
        }

        // ── ✚ 蓝图库按钮（Row 1，Align 右侧）────────────────────────────
        boolean isCreative = player.isCreative();
        int libX = deployX + alignW + pGap;  // cx + 78
        addRenderableWidget(Button.builder(Component.literal("✚"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen(PlacementScreen.this)))
                .bounds(libX, row1Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.library")))
                .build());

        // ── 关闭（Row 1，✚ 右侧）────────────────────────────────────────
        int closeX = libX + dW + pGap;  // cx + 100
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.gui.placement.btn.close"), b -> this.onClose())
                .bounds(closeX, row1Y, closeBtnW, botH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.btn.save_and_close")))
                .build());

        // ── Row 2: ▼(-144) Lock(-122) Clear(-100) Hide(-78) Fluid(-56) Mat(-34,52) Deploy(+22,52) ⌘(+78) 取消(+100,36) ──
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> adjustOffset(0, -1, 0))
                .bounds(dX, row2Y, dW, dH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.down")))
                .build()).active = !isOff;

        int rx = r1StartX;  // cx - 122

        // ── 🔒/🔑 锁定按钮（18px）────────────────────────────────────────
        Component lockEmoji = locked ? Component.literal("🔑") : Component.literal("🔒");
        String lockTipKey = locked
                ? "spatial_blueprint.gui.placement.tooltip.unlock"
                : (hasTarget
                        ? "spatial_blueprint.gui.placement.tooltip.lock"
                        : "spatial_blueprint.gui.placement.tooltip.lock.out_of_range");
        Button lockBtn = addRenderableWidget(Button.builder(lockEmoji, b -> toggleLock())
                .bounds(rx, row2Y, dW, botH)
                .tooltip(Tooltip.create(Component.translatable(lockTipKey)))
                .build());
        lockBtn.active = !isOff && hasTarget;
        rx += dW + pGap;  // cx - 100

        // ── ♻/✖ 清空模式切换按钮（18px，仅创造模式可用）────────────────
        boolean isCreativeClear = player.isCreative();
        Component clearModeEmoji = clearFirst ? Component.literal("✖") : Component.literal("♻");
        Component clearModeTip = isCreativeClear
                ? buildClearModeTip()
                : Component.translatable("spatial_blueprint.gui.placement.tooltip.clearmode.survival_disabled");
        Button clearBtn = addRenderableWidget(Button.builder(clearModeEmoji, b -> toggleClearMode())
                .bounds(rx, row2Y, dW, botH)
                .tooltip(Tooltip.create(clearModeTip))
                .build());
        clearBtn.active = isCreativeClear && data != null;
        rx += dW + pGap;  // cx - 78

        // ── ⛒/❶…❺ 方块遮蔽模式（18px；data=null 时禁用）────────────────
        updateHiddenTopBlocks();
        int curHideMode = BlueprintSelectionRenderer.hideTopN;
        String[] modeIcons = { "⛒", "❶", "❷", "❸", "❹", "❺" };
        String modeTipKey = "spatial_blueprint.gui.placement.tooltip.hidemode." + curHideMode;
        Button hideBtn = addRenderableWidget(Button.builder(Component.literal(modeIcons[curHideMode]), b -> {
            BlueprintSelectionRenderer.hideTopN = (BlueprintSelectionRenderer.hideTopN + 1) % 6;
            updateHiddenTopBlocks();
            clearWidgets(); init();
        })
                .bounds(rx, row2Y, dW, botH)
                .tooltip(Tooltip.create(Component.translatable(modeTipKey)
                        .append(Component.literal("\n仅视觉关闭，不会影响最终构建！")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW))))
                .build());
        hideBtn.active = data != null;
        rx += dW + pGap;  // cx - 56

        // ── 流体显示模式（18px；data=null 时禁用）────────────────────────
        com.xioyim.spatialblueprint.util.FluidDisplayMode curFluidMode = BlueprintSelectionRenderer.fluidMode;
        String fluidIcon = switch (curFluidMode) {
            case SHOW_ALL -> "⛖";
            case HIDE_VANILLA -> "⛗";
            case HIDE_VANILLA_FILTER -> "⛖";
            case HIDE_ALL_FILTER -> "♨";
        };
        Component fluidLabel = curFluidMode.affectsBuild()
                ? Component.literal(fluidIcon).withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED))
                : Component.literal(fluidIcon);
        String fluidTipKey = "spatial_blueprint.gui.placement.tooltip.fluid." + curFluidMode.name().toLowerCase();
        Button fluidBtn = addRenderableWidget(Button.builder(fluidLabel, b -> {
            BlueprintSelectionRenderer.fluidMode = BlueprintSelectionRenderer.fluidMode.next();
            clearWidgets(); init();
        })
                .bounds(rx, row2Y, dW, botH)
                .tooltip(Tooltip.create(Component.translatable(fluidTipKey)))
                .build());
        fluidBtn.active = data != null;
        rx += dW + pGap;  // cx - 34

        // ── 材料清单切换按钮（52px）──────────────────────────────────────
        Component matLabel = showMaterials
                ? Component.translatable("spatial_blueprint.gui.placement.hide_materials")
                : Component.translatable("spatial_blueprint.gui.placement.show_materials");
        Button matBtn = addRenderableWidget(Button.builder(matLabel,
                        b -> { if (!showMaterials) matScrollRow = 0; showMaterials = !showMaterials; clearWidgets(); init(); })
                .bounds(rx, row2Y, botW, botH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.toggle_materials")))
                .build());
        matBtn.active = isProjection();
        rx += botW + pGap;  // cx + 22

        // ── Deploy 按钮（52px）— 颜色与文字随校验状态变化 ───────────────
        addRenderableWidget(buildDeployButton(rx, row2Y, botW, botH, isOff));
        rx += botW + pGap;  // cx + 78

        // ── ⌘ 创建工程文件按钮（18px；row2，创造模式且有蓝图数据时可用）──
        AlignMode initAlignMode = BlueprintSelectionRenderer.getAlignMode(stack);
        boolean hasPreview = initAlignMode != AlignMode.OFF
                && BlueprintSelectionRenderer.getLastPreviewMin() != null;
        Component engTip = !isCreative
                ? Component.translatable("spatial_blueprint.gui.placement.tooltip.create_engineering.no_access")
                : data == null || !hasPreview
                ? Component.translatable("spatial_blueprint.gui.placement.tooltip.create_engineering.no_preview")
                : Component.translatable("spatial_blueprint.gui.placement.tooltip.create_engineering");
        engBtn = addRenderableWidget(Button.builder(Component.literal("⌘"), b -> {
            if (data == null || bpId == null || bpId.isEmpty()) return;
            BlockPos previewMin = BlueprintSelectionRenderer.getLastPreviewMin();
            BlockPos previewMax = BlueprintSelectionRenderer.getLastPreviewMax();
            if (previewMin == null || previewMax == null) return;
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            String dimStr = (level != null)
                    ? level.dimension().location().toString()
                    : "minecraft:overworld";
            Minecraft.getInstance().setScreen(
                    new CreateEngineeringProjectScreen(
                            PlacementScreen.this, bpId, data.name, dimStr, previewMin, previewMax));
        })
                .bounds(rx, row2Y, dW, dH)
                .tooltip(Tooltip.create(engTip))
                .build());
        engBtn.active = isCreative && data != null;
        rx += dW + pGap;  // cx + 100

        // ── 取消（36px）──────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.gui.placement.btn.cancel"), b -> cancel())
                .bounds(rx, row2Y, closeBtnW, botH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.cancel")))
                .build());

        buildMatEntries(); // M9: rebuild snapshot on every init() (data may have just been set)
    }

    @Override
    public void tick() {
        super.tick();

        // ── Bug 10 fix: 死亡或切换维度时强制关闭界面 ────────────────────────
        // 这是被动事件（不是玩家主动取消），不调用 cancel()，直接 setScreen(null)。
        // mc.player == null  → 服务器断连/关闭
        // mc.player.isDeadOrDying() → 玩家死亡
        // mc.player != this.player  → 切换维度时 Minecraft 会重建 LocalPlayer 实例
        Minecraft mcInst = Minecraft.getInstance();
        if (mcInst.player == null || mcInst.player.isDeadOrDying()) {
            mcInst.setScreen(null);
            return;
        }
        if (mcInst.player != this.player) {
            mcInst.setScreen(null);
            return;
        }

        // ── 检测下载完成 / 失败 / 超时：每帧轮询 ClientBlueprintCache ─────
        if (data == null) {
            String id = SpatialBlueprintItem.getBlueprintId(stack);
            if (id != null && !id.isEmpty()) {
                BlueprintData fetched = ClientBlueprintCache.get(id);
                if (fetched != null) {
                    // 下载成功
                    data = fetched;
                    lastDownloadState = DownloadState.DONE;
                    clearWidgets();
                    init();
                    return;
                }
                // isDownloading() 内部会检测超时并自动调用 markFailed
                boolean downloading = ClientBlueprintCache.isDownloading(id);
                boolean failed      = !downloading && ClientBlueprintCache.isFailed(id);
                DownloadState cur   = failed ? DownloadState.FAILED
                                    : downloading ? DownloadState.DOWNLOADING
                                    : DownloadState.NONE;
                // Only rebuild widgets when state actually changes, to avoid tooltip flicker
                if (cur != lastDownloadState) {
                    lastDownloadState = cur;
                    downloadingBtn = null;
                    lastDownloadPct = -1;
                    clearWidgets();
                    init();
                    return;
                }
                // Update download progress label without full rebuild
                if (cur == DownloadState.DOWNLOADING && downloadingBtn != null) {
                    int pct = ClientBlueprintCache.getDownloadProgress(id);
                    if (pct != lastDownloadPct) {
                        lastDownloadPct = pct;
                        downloadingBtn.setMessage(buildDownloadLabel(pct));
                    }
                }
            }
        }

        // ── Bug-1 fix: detect when ItemSyncUtil.syncHand() replaced the held-item reference ─
        // After a deposit/withdraw the server calls syncHand(), which pushes a new deserialized
        // ItemStack via ClientboundContainerSetSlotPacket.  At that point player.getItemInHand()
        // returns a NEW object while this.stack still points to the old one.  All subsequent
        // NBT writes (setXOffset, setAlignMode, …) go to the discarded object and the renderer
        // (which reads from the live item) stops seeing them.  Fix: detect the swap and update.
        String thisBpId = SpatialBlueprintItem.getBlueprintId(this.stack);
        if (thisBpId != null && !thisBpId.isEmpty()) {
            for (net.minecraft.world.InteractionHand h : net.minecraft.world.InteractionHand.values()) {
                ItemStack live = player.getItemInHand(h);
                if (live != this.stack
                        && thisBpId.equals(SpatialBlueprintItem.getBlueprintId(live))) {
                    this.stack = live;
                    clearWidgets(); init();
                    return;
                }
            }
        }

        // ── 刷新 ⌘ 按钮状态 ────────────────────────────────────────────────
        if (engBtn == null) return;
        boolean creative = player.isCreative();
        AlignMode alignMode = BlueprintSelectionRenderer.getAlignMode(stack);
        boolean preview = alignMode != AlignMode.OFF
                && BlueprintSelectionRenderer.getLastPreviewMin() != null;
        engBtn.active = creative && data != null && preview;
        Component tip = !creative
                ? Component.translatable("spatial_blueprint.gui.placement.tooltip.create_engineering.no_access")
                : data == null || !preview
                ? Component.translatable("spatial_blueprint.gui.placement.tooltip.create_engineering.no_preview")
                : Component.translatable("spatial_blueprint.gui.placement.tooltip.create_engineering");
        engBtn.setTooltip(Tooltip.create(tip));
    }

    /** 发起蓝图数据下载请求，立刻刷新按钮为"下载中"状态。 */
    private static Component buildDownloadLabel(int pct) {
        return pct > 0
                ? Component.literal("下载中 " + pct + "%...")
                : Component.translatable("spatial_blueprint.gui.placement.btn.downloading");
    }

    private void requestBlueprintData(String bpId) {
        ClientBlueprintCache.startDownload(bpId);
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestBlueprintDataMessage(bpId));
        lastDownloadState = DownloadState.DOWNLOADING;
        clearWidgets();
        init();
    }

    /**
     * 根据当前 {@link #currentValidation} 状态构建带颜色的放置按钮。
     */
    private ColoredButton buildDeployButton(int x, int y, int w, int h, boolean isOff) {
        Component label;
        Component tip;
        int color;

        // 生存模式投影/次元蓝图：材料不足时显示专用禁用按钮（空间蓝图无此限制）
        if (!player.isCreative() && isProjection() && !isSpatialOnly() && !hasSufficientLocalMaterials()) {
            ColoredButton btn = new ColoredButton(x, y, w, h,
                    Component.translatable("spatial_blueprint.gui.placement.deploy.no_materials"),
                    b -> {},
                    Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.placement.tooltip.deploy.no_materials")),
                    COLOR_WHITE);
            btn.active = false;
            return btn;
        }

        if (isOff || currentValidation == null) {
            // OFF 模式 / 无目标 — 灰色禁用
            ColoredButton btn = new ColoredButton(x, y, w, h,
                    Component.translatable("spatial_blueprint.gui.placement.deploy"),
                    b -> deploy(),
                    Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy")),
                    COLOR_WHITE);
            btn.active = false;
            return btn;
        }

        switch (currentValidation.status) {
            case CREATIVE -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.creative")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal("\n"))
                        .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.creative.warning")
                                .withStyle(ChatFormatting.RED));
                color = COLOR_RED;
            }
            case EXTRA_BLOCKS -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.extra_blocks");
                color = COLOR_WHITE;
            }
            case ENTITY_BLOCKING -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.entity");
                color = COLOR_WHITE;
            }
            case OUT_OF_BOUNDS -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.out_of_bounds");
                color = COLOR_WHITE;
            }
            case CHUNK_NOT_LOADED -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.chunk_not_loaded");
                color = COLOR_WHITE;
            }
            default -> {   // OK
                label = Component.translatable("spatial_blueprint.gui.placement.deploy");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.ok");
                color = COLOR_WHITE;
            }
        }

        ColoredButton btn = new ColoredButton(x, y, w, h, label, b -> deploy(), Tooltip.create(tip), color);
        // 有障碍物时直接禁用，灰色不可点击；悬浮提示仍可见
        boolean isBlocked = currentValidation.status != PlacementPreValidator.Status.OK
                         && currentValidation.status != PlacementPreValidator.Status.CREATIVE;
        if (isBlocked) btn.active = false;
        return btn;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Computes the world-space origin that deploy() would use,
     * applying alignment offset + user offset on top of the look target.
     * Returns null when AlignMode is OFF or no look target exists.
     */
    @Nullable
    private BlockPos computeFinalOrigin() {
        if (data == null) return null;   // 无数据（创造模式空蓝图）时不产生坐标
        AlignMode mode = BlueprintSelectionRenderer.getAlignMode(stack);
        if (mode == AlignMode.OFF) return null;
        Minecraft mc = Minecraft.getInstance();
        BlockPos targetBase = (locked && lockedPos != null)
                ? lockedPos
                : BlueprintSelectionRenderer.getPreviewTarget(mc, BlueprintConfig.PREVIEW_DISTANCE.get());
        if (targetBase == null) return null;
        int alignOffX = BlueprintSelectionRenderer.getAlignOffsetX(data.size, rotation, mode);
        int alignOffZ = BlueprintSelectionRenderer.getAlignOffsetZ(data.size, rotation, mode);
        return targetBase.above().offset(xOffset + alignOffX, yOffset, zOffset + alignOffZ);
    }

    // ── Logic ──────────────────────────────────────────────────────────────

    private void adjustOffset(int dx, int dy, int dz) {
        this.xOffset += dx;
        this.yOffset += dy;
        this.zOffset += dz;
        SpatialBlueprintItem.setXOffset(stack, xOffset);
        SpatialBlueprintItem.setYOffset(stack, yOffset);
        SpatialBlueprintItem.setZOffset(stack, zOffset);
        // 刷新放置按钮状态
        this.clearWidgets();
        this.init();
    }

    private void toggleLock() {
        locked = !locked;
        if (locked) {
            Minecraft mc = Minecraft.getInstance();
            lockedPos = BlueprintSelectionRenderer.getPreviewTarget(mc, BlueprintConfig.PREVIEW_DISTANCE.get());
        } else {
            lockedPos = null;
        }
        SpatialBlueprintItem.setLocked(stack, locked);
        SpatialBlueprintItem.setLockedPos(stack, lockedPos);
        this.clearWidgets();
        this.init();
    }

    private void toggleClearMode() {
        clearFirst = !clearFirst;
        SpatialBlueprintItem.setClearFirst(stack, clearFirst);
        this.clearWidgets();
        this.init();
    }

    private Component buildClearModeTip() {
        if (clearFirst) {
            return Component.translatable("spatial_blueprint.gui.placement.tooltip.clearmode.full.line1")
                    .append(Component.literal("\n"))
                    .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.clearmode.full.line2"));
        } else {
            return Component.translatable("spatial_blueprint.gui.placement.tooltip.clearmode.replace.line1")
                    .append(Component.literal("\n"))
                    .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.clearmode.replace.line2"))
                    .append(Component.literal("\n"))
                    .append(Component.translatable("spatial_blueprint.gui.placement.tooltip.clearmode.replace.line3")
                            .withStyle(ChatFormatting.RED));
        }
    }

    private void cycleAlignMode() {
        BlueprintSelectionRenderer.setAlignMode(stack, BlueprintSelectionRenderer.getAlignMode(stack).next());
        this.clearWidgets();
        this.init();
    }

    private void rotate() {
        rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        SpatialBlueprintItem.setRotation(stack, rotation);
        // 旋转后范围变化，刷新放置按钮状态
        this.clearWidgets();
        this.init();
    }

    private void deploy() {
        if (data == null) return;  // 无数据时不允许放置
        // 生存模式投影/次元蓝图：材料不足时拦截（空间蓝图无此限制）
        if (!player.isCreative() && isProjection() && !isSpatialOnly() && !hasSufficientLocalMaterials()) return;
        // 生存模式下客户端校验未通过 → 阻止发包（防止意外破坏已有方块）
        if (currentValidation != null) {
            PlacementPreValidator.Status s = currentValidation.status;
            if (s == PlacementPreValidator.Status.EXTRA_BLOCKS
                    || s == PlacementPreValidator.Status.ENTITY_BLOCKING
                    || s == PlacementPreValidator.Status.OUT_OF_BOUNDS
                    || s == PlacementPreValidator.Status.CHUNK_NOT_LOADED) {
                return;
            }
        }

        Minecraft mc = Minecraft.getInstance();
        BlockPos targetBase = (locked && lockedPos != null)
                ? lockedPos
                : BlueprintSelectionRenderer.getPreviewTarget(mc, BlueprintConfig.PREVIEW_DISTANCE.get());
        if (targetBase == null) return;

        AlignMode mode = BlueprintSelectionRenderer.getAlignMode(stack);
        int alignOffX = BlueprintSelectionRenderer.getAlignOffsetX(data.size, rotation, mode);
        int alignOffZ = BlueprintSelectionRenderer.getAlignOffsetZ(data.size, rotation, mode);
        BlockPos finalOrigin = targetBase.above().offset(xOffset + alignOffX, yOffset, zOffset + alignOffZ);

        // 发送放置请求（rotation 已打包进 finalOrigin，偏移传 0）
        BlueprintNetwork.CHANNEL.sendToServer(
                new PlaceBlueprintMessage(finalOrigin, rotation, 0, 0, 0, clearFirst,
                        BlueprintSelectionRenderer.fluidMode));

        // 立刻重置所有操作状态：锁定、偏移、旋转、渲染模式
        // 服务端会将物品替换为 BuildBlueprintItem，但客户端先本地清零，
        // 防止 PlacementScreen 关闭到服务端同步之间短暂显示旧预览。
        resetItemState();
        this.onClose();
    }

    /** 将物品的所有操作状态重置为默认值，并关闭客户端渲染预览。 */
    private void resetItemState() {
        this.locked    = false;
        this.lockedPos = null;
        this.xOffset   = 0;
        this.yOffset   = 0;
        this.zOffset   = 0;
        this.rotation  = Rotation.NONE;
        SpatialBlueprintItem.setLocked(stack, false);
        SpatialBlueprintItem.setLockedPos(stack, null);
        SpatialBlueprintItem.setXOffset(stack, 0);
        SpatialBlueprintItem.setYOffset(stack, 0);
        SpatialBlueprintItem.setZOffset(stack, 0);
        SpatialBlueprintItem.setRotation(stack, Rotation.NONE);
        BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.OFF);
    }

    private void cancel() {
        // 取消时清除缓存，下次打开 UI 需重新下载
        String bpId = SpatialBlueprintItem.getBlueprintId(stack);
        if (bpId != null) ClientBlueprintCache.remove(bpId);
        BlueprintSelectionRenderer.hideTopN = 0;
        BlueprintSelectionRenderer.hiddenTopBlocks.clear();
        BlueprintSelectionRenderer.fluidMode = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
        resetItemState();
        this.onClose();
    }

    /** 根据当前 hideTopN 模式，将材料中数量最多的前 N 种方块 ID 写入 hiddenTopBlocks。 */
    private void updateHiddenTopBlocks() {
        BlueprintSelectionRenderer.hiddenTopBlocks.clear();
        int n = BlueprintSelectionRenderer.hideTopN;
        if (n == 0 || data == null || data.materialList == null
                || data.materialList.blockCounts.isEmpty()) return;
        data.materialList.blockCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .forEach(e -> BlueprintSelectionRenderer.hiddenTopBlocks.add(e.getKey()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (showMaterials && isProjection() && matMaxScrollRow > 0
                && mouseX >= matPanelX && mouseX < matPanelX + matPanelW
                && mouseY >= matPanelY && mouseY < matPanelY + matPanelH) {
            matScrollRow = Math.max(0, Math.min(matScrollRow - (int) Math.signum(delta), matMaxScrollRow));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showMaterials && isProjection()) {
            renderMaterialPanel(graphics, mouseX, mouseY);
        }
        // ── 悬浮 overlay（每次只显示一个，按优先级：存取 > 删除确认 > 数量编辑 > 右键菜单）
        if (depositBlockId != null) {
            renderDepositPanel(graphics, mouseX, mouseY);
        } else if (deleteBlockId != null) {
            renderDeleteMaterialPanel(graphics, mouseX, mouseY);
        } else if (editBlockId != null) {
            renderEditCountPanel(graphics, mouseX, mouseY);
        } else if (ctxBlockId != null) {
            renderMaterialContextMenu(graphics, mouseX, mouseY);
        }
    }

    /**
     * 在按钮行正上方渲染材料清单面板。
     * 宽度与按钮行一致（264px），内容使用 0.75× 缩放，支持滚轮滚动。
     * 创造模式下右键图标可弹出操作菜单。
     */
    private void renderMaterialPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        // ── 优先使用内存中的材料清单（反映客户端侧编辑），其次从物品/文件加载 ──
        MaterialList ml;
        if (data != null && data.materialList != null) {
            ml = data.materialList;
        } else {
            ml = ProjectionBlueprintItem.getMaterialList(stack);
            if (ml == null) {
                BlueprintData bd = ProjectionBlueprintItem.getBlueprintData(stack);
                if (bd != null) ml = MaterialList.fromBlueprintData(bd);
            }
        }

        int panelX = this.width / 2 - 149;  // MAT_PANEL_W/2 = 298/2
        int row1Y  = this.height - 45 - 18 - 4;

        // ── 无数据提示 ────────────────────────────────────────────────────
        if (ml == null || ml.blockCounts.isEmpty()) {
            matContentY = -1;
            int msgH = MAT_PAD + MAT_HEADER_H + MAT_PAD;
            int msgY = Math.max(4, row1Y - msgH - 4);
            matPanelX = panelX; matPanelY = msgY; matPanelW = MAT_PANEL_W; matPanelH = msgH;
            graphics.fill(panelX, msgY, panelX + MAT_PANEL_W, msgY + msgH, 0xD0101010);
            drawBorder(graphics, panelX, msgY, MAT_PANEL_W, msgH);
            graphics.drawString(font,
                    Component.translatable("spatial_blueprint.gui.placement.material_list").getString(),
                    panelX + MAT_PAD, msgY + MAT_PAD, 0x888888, false);
            return;
        }

        // ── 布局计算 ──────────────────────────────────────────────────────
        List<Map.Entry<String, Integer>> entries = cachedMatEntries; // M9: use pre-built snapshot
        int totalRows = (entries.size() + MAT_COLS - 1) / MAT_COLS;
        matMaxScrollRow = Math.max(0, totalRows - MAT_VISIBLE);
        matScrollRow    = Math.max(0, Math.min(matScrollRow, matMaxScrollRow));

        int contentH = MAT_VISIBLE * MAT_CELL_H;
        boolean showBotRow = !isSpatialOnly();
        int panelH = MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD + contentH
                   + (showBotRow ? MAT_SEP_PAD + MAT_BOT_BTN_H + MAT_PAD : MAT_PAD);
        int panelY   = Math.max(4, Math.min(this.height / 2 - panelH / 2 - 20, row1Y - panelH - 4));

        matPanelX = panelX; matPanelY = panelY; matPanelW = MAT_PANEL_W; matPanelH = panelH;

        // ── 背景 + 边框 ───────────────────────────────────────────────────
        graphics.fill(panelX, panelY, panelX + MAT_PANEL_W, panelY + panelH, 0xD0101010);
        drawBorder(graphics, panelX, panelY, MAT_PANEL_W, panelH);

        // ── 标题行 ────────────────────────────────────────────────────────
        String title = Component.translatable("spatial_blueprint.gui.placement.material_list").getString();
        String sub   = Component.translatable("spatial_blueprint.gui.placement.material_list_header",
                entries.size(), ml.totalCount()).getString();
        graphics.drawString(font, title, panelX + MAT_PAD, panelY + MAT_PAD, 0xFFFFAA, false);
        graphics.drawString(font, sub,
                panelX + MAT_PANEL_W - MAT_PAD - font.width(sub),
                panelY + MAT_PAD, 0x888888, false);
        boolean titleHovered = mouseX >= panelX + MAT_PAD
                && mouseX < panelX + MAT_PAD + font.width(title)
                && mouseY >= panelY + MAT_PAD
                && mouseY < panelY + MAT_PAD + font.lineHeight;

        // 按钮移至底部行，header 区域仅保留标题。悬浮状态在底部行处设置
        boolean addBtnHovered = false;
        boolean depHov = false, wdHov = false, saveHov = false;
        matAddBtnW = 0; matQuickDepBtnW = 0; matQuickWdBtnW = 0; matSaveBtnW = 0;

        // 分割线（全宽至内容右边缘）
        int sepY = panelY + MAT_PAD + MAT_HEADER_H;
        graphics.fill(panelX + MAT_PAD, sepY, panelX + MAT_PANEL_W - MAT_PAD, sepY + 1, 0xFF444444);

        // ── 内容区域 ──────────────────────────────────────────────────────
        int contentY = sepY + 1 + MAT_SEP_PAD;
        this.matContentY = contentY;
        // 物品格子区深色背景（50% 透明）
        graphics.fill(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH, 0x801A1A1A);

        // ── 内容格子（带裁剪 + 缩放）─────────────────────────────────────
        graphics.enableScissor(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH);
        graphics.pose().pushPose();
        graphics.pose().scale(MAT_SCALE, MAT_SCALE, 1f);

        String hoveredName = null;

        for (int i = 0; i < entries.size(); i++) {
            int row = i / MAT_COLS;
            int col = i % MAT_COLS;

            int screenX = panelX + MAT_PAD + col * MAT_CELL_W;
            int screenY = contentY + (row - matScrollRow) * MAT_CELL_H;

            if (screenY + MAT_CELL_H <= contentY || screenY >= contentY + contentH) continue;

            int ux = Math.round(screenX / MAT_SCALE);
            int uy = Math.round(screenY / MAT_SCALE);

            Map.Entry<String, Integer> entry = entries.get(i);
            ResourceLocation rl = ResourceLocation.tryParse(entry.getKey());
            if (rl == null) continue;

            // ── 优先作为方块查找，其次直接作为物品查找 ───────────────────
            var blockOpt = BuiltInRegistries.BLOCK.getOptional(rl);
            var itemOpt  = BuiltInRegistries.ITEM.getOptional(rl);

            // 显示名：方块名 > 物品名 > 注册名原文
            String entryName;
            if (blockOpt.isPresent()) {
                entryName = blockOpt.get().getName().getString();
            } else if (itemOpt.isPresent()) {
                entryName = new ItemStack(itemOpt.get()).getHoverName().getString();
            } else {
                entryName = entry.getKey();
            }

            // ── 图标（overlay z=300 > renderItem z=232，overlay 始终遮盖图标）──
            net.minecraft.world.item.Item displayItem = Items.AIR;
            if (blockOpt.isPresent()) {
                displayItem = blockOpt.get().asItem();
            } else if (itemOpt.isPresent()) {
                displayItem = itemOpt.get();
            }
            if (displayItem != Items.AIR) {
                graphics.renderItem(new ItemStack(displayItem), ux, uy);
            }

            // ── 数量：空间蓝图仅显示总量，其余显示已存入/需要 ──────────
            int depCount  = localDeposited.getOrDefault(entry.getKey(), 0);
            int needCount = entry.getValue();
            String countStr   = isSpatialOnly() ? String.valueOf(needCount) : (depCount + "/" + needCount);
            int    countColor = isSpatialOnly() ? 0xAAAAAA : (depCount >= needCount ? 0x55FF55 : 0xFFAA00);
            int textY = uy + (16 - font.lineHeight) / 2 + 1;
            graphics.drawString(font, countStr, ux + 17, textY, countColor, false);

            // 鼠标悬浮于图标上：记录名称供 tooltip 使用
            if (hoveredName == null
                    && mouseX >= screenX && mouseX < screenX + MAT_ICON_SCR
                    && mouseY >= screenY && mouseY < screenY + MAT_ICON_SCR
                    && screenY >= contentY && screenY < contentY + contentH) {
                hoveredName = entryName;
            }
        }

        graphics.pose().popPose();
        graphics.disableScissor();

        // ── 底部按钮行（非空间蓝图，正方形原版风格，左对齐）────────────
        if (showBotRow && matBtnDep != null) {
            int botY  = panelY + panelH - MAT_PAD - MAT_BOT_BTN_H;
            int sz    = MAT_BOT_BTN_H;
            int gap   = 3;
            boolean isOp = player.hasPermissions(2) && player.isCreative();
            int bx = panelX + MAT_PAD;

            // ✚ 新增（创造模式 + OP 专属）
            if (isOp && matBtnAdd != null) {
                matAddBtnX = bx; matAddBtnY = botY; matAddBtnW = sz; matAddBtnH = sz;
                addBtnHovered = mouseX >= bx && mouseX < bx + sz
                             && mouseY >= botY && mouseY < botY + sz;
                matBtnAdd.setX(bx); matBtnAdd.setY(botY);
                matBtnAdd.render(graphics, mouseX, mouseY, 0f);
                bx += sz + gap;
            }

            // ✎ 快捷存入（所有玩家）
            matQuickDepBtnX = bx; matQuickDepBtnY = botY; matQuickDepBtnW = sz; matQuickDepBtnH = sz;
            depHov = mouseX >= bx && mouseX < bx + sz
                  && mouseY >= botY && mouseY < botY + sz;
            matBtnDep.setX(bx); matBtnDep.setY(botY);
            matBtnDep.render(graphics, mouseX, mouseY, 0f);
            bx += sz + gap;

            // ♻ 一键清空（创造模式 + OP 专属）
            if (isOp && matBtnWd != null) {
                matQuickWdBtnX = bx; matQuickWdBtnY = botY; matQuickWdBtnW = sz; matQuickWdBtnH = sz;
                wdHov = mouseX >= bx && mouseX < bx + sz
                     && mouseY >= botY && mouseY < botY + sz;
                matBtnWd.setX(bx); matBtnWd.setY(botY);
                matBtnWd.render(graphics, mouseX, mouseY, 0f);
                bx += sz + gap;
            }

            // ⎙ 保存至服务器文件（创造模式 + OP 专属）
            if (isOp && matBtnSave != null) {
                matSaveBtnX = bx; matSaveBtnY = botY; matSaveBtnW = sz; matSaveBtnH = sz;
                saveHov = mouseX >= bx && mouseX < bx + sz
                       && mouseY >= botY && mouseY < botY + sz;
                matBtnSave.setX(bx); matBtnSave.setY(botY);
                matBtnSave.render(graphics, mouseX, mouseY, 0f);
            }
        }

        // ── Tooltip（有 overlay 时不显示，避免遮挡）─────────────────────
        boolean anyOverlay = ctxBlockId != null || editBlockId != null
                          || deleteBlockId != null || depositBlockId != null;
        if (!anyOverlay) {
            if (addBtnHovered) {
                java.util.List<net.minecraft.util.FormattedCharSequence> addLines = java.util.List.of(
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.add_material.title")
                                .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.add_material.line1")
                                .withStyle(ChatFormatting.GRAY).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.add_material.line2")
                                .withStyle(ChatFormatting.RED).getVisualOrderText()
                );
                graphics.renderTooltip(font, addLines, mouseX, mouseY);
            } else if (depHov) {
                java.util.List<net.minecraft.util.FormattedCharSequence> depLines =
                        java.util.List.of(
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.title")
                                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line1")
                                    .withStyle(ChatFormatting.GRAY).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line2")
                                    .withStyle(ChatFormatting.GRAY).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line3")
                                    .withStyle(ChatFormatting.GRAY).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line4")
                                    .withStyle(ChatFormatting.GREEN).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line5")
                                    .withStyle(ChatFormatting.RED).getVisualOrderText()
                        );
                graphics.renderTooltip(font, depLines, mouseX, mouseY);
            } else if (wdHov) {
                java.util.List<net.minecraft.util.FormattedCharSequence> wdLines = java.util.List.of(
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.title")
                                .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line1")
                                .withStyle(ChatFormatting.GRAY).getVisualOrderText()
                );
                graphics.renderTooltip(font, wdLines, mouseX, mouseY);
            } else if (saveHov) {
                java.util.List<net.minecraft.util.FormattedCharSequence> saveLines = java.util.List.of(
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.save_materials.title")
                                .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.save_materials.line1")
                                .withStyle(ChatFormatting.GOLD).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.save_materials.line2")
                                .withStyle(ChatFormatting.GRAY).getVisualOrderText()
                );
                graphics.renderTooltip(font, saveLines, mouseX, mouseY);
            } else if (hoveredName != null) {
                graphics.renderTooltip(font, Component.literal(hoveredName), mouseX, mouseY);
            } else if (titleHovered && data != null
                    && (!data.creatorName.isEmpty() || data.createdAt > 0)) {
                java.util.List<net.minecraft.util.FormattedCharSequence> infoLines = new java.util.ArrayList<>();
                if (!data.creatorName.isEmpty()) {
                    infoLines.add(Component.translatable(
                                    "spatial_blueprint.gui.library.tooltip.creator", data.creatorName)
                            .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
                }
                if (data.createdAt > 0) {
                    infoLines.add(Component.translatable(
                                    "spatial_blueprint.gui.library.tooltip.created_at", formatDate(data.createdAt))
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText());
                }
                if (!infoLines.isEmpty()) {
                    graphics.renderTooltip(font, infoLines, mouseX, mouseY);
                }
            }
        }
    }

    private static String formatDate(long epochMillis) {
        if (epochMillis <= 0) return "-";
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return d.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 透明背景：不绘制暗色遮罩，让世界透过显示
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    // ── 取出经验/次数辅助 ──────────────────────────────────────────────────

    /**
     * 计算当前快捷取出所需经验等级（客户端乐观估算）。
     * 优先使用物品 NBT 中的 customWithdrawXp；否则按配置公式计算。
     */
    private int computeWithdrawXpCost() {
        int custom = ProjectionBlueprintItem.getCustomWithdrawXp(stack);
        if (custom >= 0) return custom;
        long types = localDeposited.values().stream().filter(v -> v > 0).count();
        if (types == 0) return 0;
        int ratio = BlueprintConfig.WITHDRAW_XP_RATIO.get();
        if (ratio == 0) return 0;
        int cost  = Math.max(1, ((int) types + ratio - 1) / ratio);
        return Math.min(cost, BlueprintConfig.MAX_WITHDRAW_XP_COST.get());
    }

    /** 获取此蓝图物品的有效快捷取出次数上限。 */
    private int getEffectiveWithdrawLimit() {
        int custom = ProjectionBlueprintItem.getCustomWithdrawLimit(stack);
        return custom >= 0 ? custom : BlueprintConfig.MAX_WITHDRAW_COUNT.get();
    }

    // ── 材料右键菜单 & 编辑 overlay ────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        int mx = (int) mouseX, my = (int) mouseY;

        // ── 存入/取出 overlay ────────────────────────────────────────────
        if (depositBlockId != null) {
            final int EW = 200, EH = 90, BW = 60, BH = 16, BP = 8;
            int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;
            int okX = ex + EW / 2 - BW - 4, cancelX = ex + EW / 2 + 4;
            int by  = ey + EH - BH - BP;
            if (my >= by && my < by + BH) {
                if (mx >= okX && mx < okX + BW) { confirmDeposit(); return true; }
                if (mx >= cancelX && mx < cancelX + BW) {
                    depositBlockId = null; depositBlockName = null;
                    depositInput.setLength(0); return true;
                }
            }
            if (mx < ex || mx >= ex + EW || my < ey || my >= ey + EH) {
                depositBlockId = null; depositBlockName = null; depositInput.setLength(0);
            }
            return true;
        }

        // ── 删除确认 overlay ─────────────────────────────────────────────
        if (deleteBlockId != null) {
            if (!player.isCreative()) { deleteBlockId = null; return true; }
            final int DW = 200, DH = 80, BW = 60, BH = 16, BP = 8;
            int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;
            int okX = dx + DW / 2 - BW - 4, cancelX = dx + DW / 2 + 4;
            int by  = dy + DH - BH - BP;
            if (my >= by && my < by + BH) {
                if (mx >= okX && mx < okX + BW) {
                    if (data != null && data.materialList != null) {
                        data.materialList.blockCounts.remove(deleteBlockId);
                        buildMatEntries(); // M9: map mutated — refresh snapshot
                        String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
                        if (!bpId.isEmpty()) {
                            BlueprintNetwork.CHANNEL.sendToServer(
                                    new C2S_EditMaterialMessage(bpId, deleteBlockId, -1));
                        }
                    }
                    deleteBlockId = null;
                    return true;
                }
                if (mx >= cancelX && mx < cancelX + BW) { deleteBlockId = null; return true; }
            }
            // 点击面板外关闭
            if (mx < dx || mx >= dx + DW || my < dy || my >= dy + DH) deleteBlockId = null;
            return true;
        }

        // ── 数量编辑 overlay ─────────────────────────────────────────────
        if (editBlockId != null) {
            if (!player.isCreative()) { editBlockId = null; editInput.setLength(0); return true; }
            final int EW = 200, EH = 90, BW = 60, BH = 16, BP = 8;
            int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;
            int okX = ex + EW / 2 - BW - 4, cancelX = ex + EW / 2 + 4;
            int by  = ey + EH - BH - BP;
            if (my >= by && my < by + BH) {
                if (mx >= okX && mx < okX + BW) { confirmEditCount(); return true; }
                if (mx >= cancelX && mx < cancelX + BW) {
                    editBlockId = null; editInput.setLength(0); return true;
                }
            }
            if (mx < ex || mx >= ex + EW || my < ey || my >= ey + EH) {
                editBlockId = null; editInput.setLength(0);
            }
            return true;
        }

        // ── 右键菜单 overlay（仅创造模式）───────────────────────────────
        if (ctxBlockId != null) {
            final int CM_ROWS = 6;
            final int CMW = 130, CM_ROW_H = 16, CM_PAD = 4;
            final int CMH = CM_PAD + CM_ROWS * CM_ROW_H + CM_PAD;
            int cx2 = Math.min(ctxX, this.width - CMW - 2);
            int cy2 = Math.min(ctxY, this.height - CMH - 2);
            if (mx >= cx2 && mx < cx2 + CMW && my >= cy2 && my < cy2 + CMH) {
                int row = (my - cy2 - CM_PAD) / CM_ROW_H;
                if (row >= 0 && row < CM_ROWS) {
                    switch (row) {
                        case 0 -> Minecraft.getInstance().keyboardHandler
                                .setClipboard(ctxBlockName != null ? ctxBlockName : ctxBlockId);
                        case 1 -> Minecraft.getInstance().keyboardHandler.setClipboard(ctxBlockId);
                        case 2 -> {
                            if (data != null && data.materialList != null) {
                                editBlockId = ctxBlockId;
                                editInput.setLength(0);
                                editInput.append(data.materialList.blockCounts
                                        .getOrDefault(ctxBlockId, 0));
                            }
                        }
                        case 3 -> openDepositDialog(ctxBlockId, ctxBlockName, true);
                        case 4 -> openDepositDialog(ctxBlockId, ctxBlockName, false);
                        case 5 -> deleteBlockId = ctxBlockId;
                    }
                }
            }
            ctxBlockId = null;
            return true;
        }

        // ── ✚ 新增材料按钮点击 ────────────────────────────────────────────
        if (button == 0 && showMaterials && player.hasPermissions(2) && player.isCreative() && matAddBtnW > 0
                && mx >= matAddBtnX && mx < matAddBtnX + matAddBtnW
                && my >= matAddBtnY && my < matAddBtnY + matAddBtnH) {
            Minecraft.getInstance().setScreen(new MaterialItemPickerScreen(this, stack, data));
            return true;
        }

        // ── ✎ 快捷存入 ────────────────────────────────────────────────────
        if (button == 0 && showMaterials && isProjection() && matQuickDepBtnW > 0
                && mx >= matQuickDepBtnX && mx < matQuickDepBtnX + matQuickDepBtnW
                && my >= matQuickDepBtnY && my < matQuickDepBtnY + matQuickDepBtnH) {
            handleQuickDeposit();
            return true;
        }

        // ── ♻ 一键清空材料（仅创造模式 + OP）────────────────────────────
        if (button == 0 && showMaterials && isProjection() && matQuickWdBtnW > 0
                && player.hasPermissions(2) && player.isCreative()
                && mx >= matQuickWdBtnX && mx < matQuickWdBtnX + matQuickWdBtnW
                && my >= matQuickWdBtnY && my < matQuickWdBtnY + matQuickWdBtnH) {
            handleClearMaterials();
            return true;
        }

        // ── ⎙ 保存材料清单至服务器文件（仅创造模式 + OP）─────────────────
        if (button == 0 && showMaterials && player.hasPermissions(2) && player.isCreative()
                && matSaveBtnW > 0
                && mx >= matSaveBtnX && mx < matSaveBtnX + matSaveBtnW
                && my >= matSaveBtnY && my < matSaveBtnY + matSaveBtnH) {
            handleSaveMaterialList();
            return true;
        }

        // ── 右键点击材料图标 → 弹出菜单（仅创造模式）───────────────────
        if (button == 1 && player.isCreative() && showMaterials && isProjection()
                && data != null && data.materialList != null && matContentY >= 0) {
            int panelX = this.width / 2 - 149;  // MAT_PANEL_W/2 = 298/2
            List<Map.Entry<String, Integer>> entries = cachedMatEntries; // M9: use pre-built snapshot
            for (int i = 0; i < entries.size(); i++) {
                int row = i / MAT_COLS, col = i % MAT_COLS;
                int entryX = panelX + MAT_PAD + col * MAT_CELL_W;
                int entryY = matContentY + (row - matScrollRow) * MAT_CELL_H;
                // 跳过不可见格子
                if (entryY < matContentY || entryY + MAT_ICON_SCR > matContentY + MAT_VISIBLE * MAT_CELL_H) continue;
                if (mx >= entryX && mx < entryX + MAT_ICON_SCR
                        && my >= entryY && my < entryY + MAT_ICON_SCR) {
                    ctxBlockId = entries.get(i).getKey();
                    ResourceLocation rl = ResourceLocation.tryParse(ctxBlockId);
                    ctxBlockName = rl != null
                            ? BuiltInRegistries.BLOCK.getOptional(rl)
                                    .map(b -> b.getName().getString()).orElse(ctxBlockId)
                            : ctxBlockId;
                    ctxX = mx; ctxY = my;
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) { errorToast = null; return true; }
        if (depositBlockId != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmDeposit();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                depositBlockId = null; depositBlockName = null; depositInput.setLength(0);
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && depositInput.length() > 0) {
                depositInput.deleteCharAt(depositInput.length() - 1);
            }
            return true;
        }
        if (editBlockId != null) {
            if (!player.isCreative()) { editBlockId = null; editInput.setLength(0); return true; }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmEditCount();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editBlockId = null; editInput.setLength(0);
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && editInput.length() > 0) {
                editInput.deleteCharAt(editInput.length() - 1);
            }
            return true;  // 编辑模式下吞掉所有按键
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (depositBlockId != null) { depositBlockId = null; depositBlockName = null; depositInput.setLength(0); return true; }
            if (deleteBlockId  != null) { deleteBlockId  = null; return true; }
            if (ctxBlockId     != null) { ctxBlockId     = null; return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (depositBlockId != null) {
            if (Character.isDigit(c) && depositInput.length() < 9) depositInput.append(c);
            return true;
        }
        if (editBlockId != null) {
            if (Character.isDigit(c) && editInput.length() < 9) editInput.append(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    /** 确认数量编辑：本地更新 + 发包同步 dat 文件。 */
    private void confirmEditCount() {
        if (!player.isCreative() || editBlockId == null
                || data == null || data.materialList == null) {
            editBlockId = null; editInput.setLength(0); return;
        }
        try {
            int newCount = Integer.parseInt(editInput.toString());
            if (newCount > 0) {
                data.materialList.blockCounts.put(editBlockId, newCount);
                buildMatEntries(); // M9: map mutated — refresh snapshot
                String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
                if (!bpId.isEmpty()) {
                    BlueprintNetwork.CHANNEL.sendToServer(
                            new C2S_EditMaterialMessage(bpId, editBlockId, newCount));
                }
            }
        } catch (NumberFormatException ignored) {}
        editBlockId = null;
        editInput.setLength(0);
    }

    // ── 材料右键菜单渲染 ────────────────────────────────────────────────────

    private void renderMaterialContextMenu(GuiGraphics g, int mouseX, int mouseY) {
        // 右键菜单仅在创造模式下开启
        String[] labels = {
            Component.translatable("spatial_blueprint.gui.placement.context.copy_name").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.copy_id").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.edit_count").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.deposit").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.withdraw").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.delete").getString()
        };
        int[] colors = { 0xFFFFFF, 0xAAAAAA, 0xFFFFFF, 0x55FF55, 0xFFAA00, 0xFF5555 };
        int CM_ROWS = labels.length;
        final int CMW = 130, CM_ROW_H = 16, CM_PAD = 4;
        final int CMH = CM_PAD + CM_ROWS * CM_ROW_H + CM_PAD;
        int cx2 = Math.min(ctxX, this.width - CMW - 2);
        int cy2 = Math.min(ctxY, this.height - CMH - 2);

        g.pose().pushPose();
        g.pose().translate(0, 0, 300); // above renderItem z≈232
        g.fill(cx2, cy2, cx2 + CMW, cy2 + CMH, 0xF0101010);
        drawBorder(g, cx2, cy2, CMW, CMH);

        for (int i = 0; i < CM_ROWS; i++) {
            int ry = cy2 + CM_PAD + i * CM_ROW_H;
            if (mouseX >= cx2 && mouseX < cx2 + CMW && mouseY >= ry && mouseY < ry + CM_ROW_H) {
                g.fill(cx2 + 1, ry, cx2 + CMW - 1, ry + CM_ROW_H, 0x40FFFFFF);
            }
            g.drawString(font, labels[i], cx2 + CM_PAD + 2, ry + (CM_ROW_H - font.lineHeight) / 2, colors[i], false);
        }
        g.pose().popPose();
    }

    private void renderEditCountPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (!player.isCreative()) { editBlockId = null; editInput.setLength(0); return; }
        final int EW = 200, EH = 90, BW = 60, BH = 16, BP = 8, PAD = 8;
        int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(ex, ey, ex + EW, ey + EH, 0xF0101010);
        drawBorder(g, ex, ey, EW, EH);

        // 方块名（标题）
        String name = ctxBlockName != null ? ctxBlockName : (editBlockId != null ? editBlockId : "");
        g.drawString(font, name, ex + PAD, ey + PAD, 0xFFFFAA, false);

        // 输入框
        int fx = ex + PAD, fy = ey + PAD + font.lineHeight + 4, fw = EW - PAD * 2, fh = 16;
        g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF444444);
        g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A1A1A);
        String cursor = (System.currentTimeMillis() % 1000 < 500) ? "|" : "";
        g.drawString(font, editInput.toString() + cursor, fx + 4, fy + (fh - font.lineHeight) / 2, 0xFFFFFF, false);

        // 按钮
        int okX = ex + EW / 2 - BW - 4, cancelX = ex + EW / 2 + 4, by = ey + EH - BH - BP;
        boolean okH = mouseX >= okX && mouseX < okX + BW && mouseY >= by && mouseY < by + BH;
        boolean cnH = mouseX >= cancelX && mouseX < cancelX + BW && mouseY >= by && mouseY < by + BH;
        String ec_ok  = Component.translatable("spatial_blueprint.gui.common.confirm").getString();
        String ec_cnl = Component.translatable("spatial_blueprint.gui.common.cancel").getString();
        g.fill(okX, by, okX + BW, by + BH, okH ? 0xFF2A5530 : 0xFF1A351F);
        drawBorder(g, okX, by, BW, BH);
        g.drawString(font, ec_ok,  okX     + (BW - font.width(ec_ok))  / 2, by + (BH - font.lineHeight) / 2, 0x55FF55, false);
        g.fill(cancelX, by, cancelX + BW, by + BH, cnH ? 0xFF553030 : 0xFF351A1A);
        drawBorder(g, cancelX, by, BW, BH);
        g.drawString(font, ec_cnl, cancelX + (BW - font.width(ec_cnl)) / 2, by + (BH - font.lineHeight) / 2, 0xFF5555, false);
        g.pose().popPose();
    }

    private void renderDeleteMaterialPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (!player.isCreative()) { deleteBlockId = null; return; }
        final int DW = 200, DH = 80, BW = 60, BH = 16, BP = 8, PAD = 8;
        int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);

        String del_title   = Component.translatable("spatial_blueprint.gui.library.confirm_delete.title").getString();
        String del_warning = Component.translatable("spatial_blueprint.gui.library.confirm_delete.warning").getString();
        String del_ok      = Component.translatable("spatial_blueprint.gui.common.confirm").getString();
        String del_cnl     = Component.translatable("spatial_blueprint.gui.common.cancel").getString();
        g.drawString(font, del_title, dx + PAD, dy + PAD, 0xFF5555, false);
        String name = ctxBlockName != null ? ctxBlockName : (deleteBlockId != null ? deleteBlockId : "");
        g.drawString(font, name, dx + PAD, dy + PAD + font.lineHeight + 3, 0xFFFFAA, false);
        g.drawString(font, del_warning, dx + PAD, dy + PAD + (font.lineHeight + 3) * 2, 0x888888, false);

        int okX = dx + DW / 2 - BW - 4, cancelX = dx + DW / 2 + 4, by = dy + DH - BH - BP;
        boolean okH = mouseX >= okX && mouseX < okX + BW && mouseY >= by && mouseY < by + BH;
        boolean cnH = mouseX >= cancelX && mouseX < cancelX + BW && mouseY >= by && mouseY < by + BH;
        g.fill(okX, by, okX + BW, by + BH, okH ? 0xFF553030 : 0xFF351A1A);
        drawBorder(g, okX, by, BW, BH);
        g.drawString(font, del_ok,  okX     + (BW - font.width(del_ok))  / 2, by + (BH - font.lineHeight) / 2, 0xFF5555, false);
        g.fill(cancelX, by, cancelX + BW, by + BH, cnH ? 0xFF2A2A2A : 0xFF1A1A1A);
        drawBorder(g, cancelX, by, BW, BH);
        g.drawString(font, del_cnl, cancelX + (BW - font.width(del_cnl)) / 2, by + (BH - font.lineHeight) / 2, 0xFFFFFF, false);
        g.pose().popPose();
    }

    // ── 存入/取出 辅助方法 ─────────────────────────────────────────────────

    /** 检查客户端乐观跟踪的存入量是否满足所有材料需求。 */
    private boolean hasSufficientLocalMaterials() {
        if (data == null || data.materialList == null) return true;
        for (Map.Entry<String, Integer> e : data.materialList.blockCounts.entrySet()) {
            if (localDeposited.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** 打开存入/取出对话框。 */
    private void openDepositDialog(String blockId, String blockName, boolean isDeposit) {
        depositBlockId   = blockId;
        depositBlockName = blockName;
        depositMode      = isDeposit;
        depositInput.setLength(0);
        depositInput.append("1");
    }

    /** 一键存入：乐观更新 localDeposited，然后发包。 */
    private void handleQuickDeposit() {
        if (data == null || data.materialList == null) return;
        for (Map.Entry<String, Integer> entry : data.materialList.blockCounts.entrySet()) {
            String blockId  = entry.getKey();
            int needed      = entry.getValue();
            int already     = localDeposited.getOrDefault(blockId, 0);
            int stillNeed   = needed - already;
            if (stillNeed <= 0) continue;

            if (player.isCreative()) {
                localDeposited.put(blockId, needed);
            } else {
                net.minecraft.world.item.Item item = findDisplayItem(blockId);
                if (item == null) continue;
                int available = countInInventory(player, item);
                int toDeposit = Math.min(stillNeed, available);
                if (toDeposit > 0) localDeposited.put(blockId, already + toDeposit);
            }
        }
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_QUICK_DEPOSIT, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                SpatialBlueprintItem.getRotation(this.stack).name(),
                this.xOffset, this.yOffset, this.zOffset));
        clearWidgets(); init();
    }

    /** 一键取出：客户端预校验后发包；生存模式检查次数限制和经验等级。 */
    private void handleQuickWithdraw() {
        // 没有存入任何物品则直接跳过
        boolean hasAny = localDeposited.values().stream().anyMatch(v -> v > 0);
        if (!hasAny) return;

        if (!player.isCreative()) {
            // 次数限制预检
            int limit = getEffectiveWithdrawLimit();
            if (localWithdrawCount >= limit) {
                NotifySound.clientError();
                errorToast = Component.translatable("spatial_blueprint.error.withdraw_limit_reached");
                return;
            }
            // 经验等级预检
            int xpCost = computeWithdrawXpCost();
            if (player.experienceLevel < xpCost) {
                NotifySound.clientError();
                errorToast = Component.translatable("spatial_blueprint.error.withdraw_xp_insufficient");
                return;
            }
        }

        // 乐观递增（生存模式）：立刻反映服务端即将执行的 incrementWithdrawCount，不等待同步
        if (!player.isCreative()) localWithdrawCount++;
        localDeposited.clear();
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_QUICK_WITHDRAW, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                SpatialBlueprintItem.getRotation(this.stack).name(),
                this.xOffset, this.yOffset, this.zOffset));
        clearWidgets(); init();
    }

    /** OP 一键清空已存入材料（不返还物品）。 */
    private void handleClearMaterials() {
        localDeposited.clear();
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_CLEAR_MATERIALS, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                SpatialBlueprintItem.getRotation(this.stack).name(),
                this.xOffset, this.yOffset, this.zOffset));
        clearWidgets(); init();
    }

    /** OP 手动将当前材料清单（需要数量）完整保存至服务端蓝图文件。 */
    private void handleSaveMaterialList() {
        if (data == null || data.materialList == null) return;
        String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (bpId.isEmpty()) return;
        BlueprintNetwork.CHANNEL.sendToServer(
                new C2S_SaveMaterialListMessage(bpId, data.materialList.blockCounts));
    }

    /** 确认存入/取出对话框。 */
    private void confirmDeposit() {
        if (depositBlockId == null) { depositInput.setLength(0); return; }
        try {
            int amount = Integer.parseInt(depositInput.toString());
            if (amount >= 1) {
                if (depositMode) {
                    // 存入
                    int needed    = (data != null && data.materialList != null)
                            ? data.materialList.blockCounts.getOrDefault(depositBlockId, 0) : 0;
                    int already   = localDeposited.getOrDefault(depositBlockId, 0);
                    int canAccept = needed - already;
                    int toDeposit = Math.min(amount, canAccept);
                    if (toDeposit > 0) {
                        if (!player.isCreative()) {
                            net.minecraft.world.item.Item item = findDisplayItem(depositBlockId);
                            if (item != null) {
                                toDeposit = Math.min(toDeposit, countInInventory(player, item));
                            }
                        }
                        if (toDeposit > 0) {
                            localDeposited.put(depositBlockId, already + toDeposit);
                            BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                                    C2S_MaterialDepositMessage.OP_DEPOSIT, depositBlockId, toDeposit,
                                    BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                                    SpatialBlueprintItem.getRotation(this.stack).name(),
                                    this.xOffset, this.yOffset, this.zOffset));
                        }
                    }
                } else {
                    // 取出
                    int already    = localDeposited.getOrDefault(depositBlockId, 0);
                    int toWithdraw = Math.min(amount, already);
                    if (toWithdraw > 0) {
                        localDeposited.put(depositBlockId, already - toWithdraw);
                        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                                C2S_MaterialDepositMessage.OP_WITHDRAW, depositBlockId, toWithdraw,
                                BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                                SpatialBlueprintItem.getRotation(this.stack).name(),
                                this.xOffset, this.yOffset, this.zOffset));
                    }
                }
            }
        } catch (NumberFormatException ignored) {}
        depositBlockId = null; depositBlockName = null; depositInput.setLength(0);
        clearWidgets(); init();
    }

    /** 渲染存入/取出输入对话框。 */
    private void renderDepositPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (depositBlockId == null) return;
        final int EW = 200, EH = 90, BW = 60, BH = 16, BP = 8, PAD = 8;
        int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(ex, ey, ex + EW, ey + EH, 0xF0101010);
        drawBorder(g, ex, ey, EW, EH);

        String depTitleKey = depositMode
                ? "spatial_blueprint.gui.placement.deposit_dialog.title.deposit"
                : "spatial_blueprint.gui.placement.deposit_dialog.title.withdraw";
        String name  = depositBlockName != null ? depositBlockName : depositBlockId;
        String title = String.format(Component.translatable(depTitleKey).getString(), name);
        g.drawString(font, title, ex + PAD, ey + PAD, 0xFFFFAA, false);

        int fx = ex + PAD, fy = ey + PAD + font.lineHeight + 4, fw = EW - PAD * 2, fh = 16;
        g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF444444);
        g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A1A1A);
        String cursor = (System.currentTimeMillis() % 1000 < 500) ? "|" : "";
        g.drawString(font, depositInput.toString() + cursor,
                fx + 4, fy + (fh - font.lineHeight) / 2, 0xFFFFFF, false);

        int okX = ex + EW / 2 - BW - 4, cancelX = ex + EW / 2 + 4, by = ey + EH - BH - BP;
        boolean okH = mouseX >= okX && mouseX < okX + BW && mouseY >= by && mouseY < by + BH;
        boolean cnH = mouseX >= cancelX && mouseX < cancelX + BW && mouseY >= by && mouseY < by + BH;
        g.fill(okX, by, okX + BW, by + BH, okH ? 0xFF2A5530 : 0xFF1A351F);
        drawBorder(g, okX, by, BW, BH);
        String dep_ok  = Component.translatable("spatial_blueprint.gui.common.confirm").getString();
        String dep_cnl = Component.translatable("spatial_blueprint.gui.common.cancel").getString();
        g.drawString(font, dep_ok,  okX     + (BW - font.width(dep_ok))  / 2, by + (BH - font.lineHeight) / 2, 0x55FF55, false);
        g.fill(cancelX, by, cancelX + BW, by + BH, cnH ? 0xFF553030 : 0xFF351A1A);
        drawBorder(g, cancelX, by, BW, BH);
        g.drawString(font, dep_cnl, cancelX + (BW - font.width(dep_cnl)) / 2, by + (BH - font.lineHeight) / 2, 0xFF5555, false);
        g.pose().popPose();
    }

    /** 在物品注册表中查找显示用的 Item（block.asItem 优先）。 */
    @Nullable
    private static net.minecraft.world.item.Item findDisplayItem(String blockId) {
        net.minecraft.resources.ResourceLocation rl =
                net.minecraft.resources.ResourceLocation.tryParse(blockId);
        if (rl == null) return null;
        var blockOpt = BuiltInRegistries.BLOCK.getOptional(rl);
        if (blockOpt.isPresent()) {
            net.minecraft.world.item.Item bi = blockOpt.get().asItem();
            if (bi != Items.AIR) return bi;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    /** 统计玩家背包中指定 Item 的总数量（仅主背包格，不含装备栏）。 */
    private static int countInInventory(Player player,
                                        net.minecraft.world.item.Item item) {
        int count = 0;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) count += s.getCount();
        }
        return count;
    }

    // ── Inner class: Deploy button with custom text color ─────────────────

    /**
     * A {@link Button} subclass that overrides only the text color.
     * The button background is rendered identically to a standard button.
     * When {@code active=false} the text falls back to the standard MC gray.
     */
    private static class ColoredButton extends Button {

        private final int activeColor;

        ColoredButton(int x, int y, int w, int h,
                      Component msg, OnPress press, Tooltip tooltip, int activeColor) {
            super(x, y, w, h, msg, press, DEFAULT_NARRATION);
            this.setTooltip(tooltip);
            this.activeColor = activeColor;
        }

        /**
         * Intercepts the text-color parameter from the parent's {@code renderWidget}.
         * Parent keeps {@code color & 0xFF000000} (alpha); we replace the RGB part.
         */
        @Override
        public void renderString(GuiGraphics guiGraphics, Font font, int color) {
            // color from parent already contains the correct alpha in the top byte
            int rgb   = this.active ? activeColor : 0xA0A0A0;
            int alpha = color & 0xFF000000;
            super.renderString(guiGraphics, font, rgb | alpha);
        }
    }
}
