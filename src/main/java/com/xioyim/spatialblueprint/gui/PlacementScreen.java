package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer;
import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode;
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage;
import com.xioyim.spatialblueprint.client.PlacementPreValidator;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
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
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
    /** 数量文字的缩放（图标不缩放，见 renderMaterialPanel）。 */
    private static final float MAT_SCALE   = 0.75f;
    /** 图标屏幕尺寸：与 /sb space 一致的原生 16×16，不再随 MAT_SCALE 缩小。 */
    private static final int MAT_ICON_SCR  = 16;

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
    /** Bounds of the ✎ 提取并载入材料 button（奖励蓝图专属）；W=0 when not rendered. */
    private int matGenBtnX, matGenBtnY, matGenBtnW = 0, matGenBtnH;
    /** Bounds of the ✖ 清空全部材料 button（奖励蓝图专属）；W=0 when not rendered. */
    private int matClearBtnX, matClearBtnY, matClearBtnW = 0, matClearBtnH;
    /** 底部按钮行 Button 实例（手动调用 render，不加入 renderables）；null = 当前不可用 */
    @Nullable private Button matBtnAdd, matBtnDep, matBtnSave, matBtnGen, matBtnClear;

    // ── Material context menu state ──────────────────────────────────────────
    @Nullable private String ctxBlockId   = null;
    @Nullable private String ctxBlockName = null;
    private int ctxX, ctxY;
    // ── Count edit overlay state ─────────────────────────────────────────────
    @Nullable private String       editBlockId = null;
    private final StringBuilder    editInput   = new StringBuilder();
    // ── Delete confirm overlay state ─────────────────────────────────────────
    @Nullable private String deleteBlockId = null;
    // ── Clear-all confirm overlay state（奖励蓝图 ✖ 清空全部材料）────────────
    private boolean confirmClearAll = false;
    /** 对话框按钮（原版 Button 样式；手动渲染在 overlay 之上，见 {@link #syncDialogButtons}）。 */
    @Nullable private Button editOkBtn, editCancelBtn, deleteOkBtn, deleteCancelBtn, clearOkBtn, clearCancelBtn;

    private Component errorToast;

    /**
     * 客户端乐观跟踪的已存入材料（材料复合键 → 数量）。
     * 权威数据在服务端的材料空间，这里只在发包后先行更新以避免界面延迟；
     * 服务端同步包到达后由 {@link #refreshDepositedFromSpace()} 覆盖。
     */
    private final Map<String, Integer> localDeposited = new LinkedHashMap<>();

    /** 上次同步到的材料空间版本号；与缓存版本不一致时用权威值刷新。 */
    private int spaceVersion = -1;

    /**
     * 本地暂存的材料清单 —— {@code null} 表示尚未编辑，直接用 {@code data.materialList}。
     *
     * <p>✚ 新增 / ✎ 改数量 / 右键删除都只改这份副本，必须点 ⎙ 才写入服务端文件。
     * 不保存就关界面的话，下次打开会重新向服务端拉取，未保存的改动随之消失。</p>
     */
    @Nullable private MaterialList localMats = null;

    /** 界面打开时刻，用于材料空间同步的超时兜底。 */
    private final long openedAt = System.currentTimeMillis();

    /**
     * 本次打开后是否已收到材料空间同步。首个同步包到达前 {@link #localDeposited} 必然为空，
     * 此时不能下「材料不足」的结论 —— 否则每次右键打开都会先误报一次。
     */
    private boolean spaceSynced = false;

    /** FORCE / 归属的延迟拦截是否已执行过 —— tick 每帧都跑，必须去重，否则闪屏。 */
    private boolean lateGuardChecked = false;

    /** 上次同步到的蓝图数据版本号；服务端回推新数据时用它替换本地 data 引用。 */
    private int blueprintVersion = -1;

    /**
     * Snapshot of {@code data.materialList.blockCounts.entrySet()} — rebuilt by
     * {@link #buildMatEntries()} so {@link #renderMaterialPanel} does not allocate a
     * new list on every frame (M9).
     */
    private List<Map.Entry<String, Integer>> cachedMatEntries = List.of();

    // ── Quick-deposit button bounds ───────────────────────────────────────────
    private int matQuickDepBtnX, matQuickDepBtnY, matQuickDepBtnW = 0, matQuickDepBtnH;

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
        // 已存入数量来自世界级材料空间（服务端权威），不再存于物品 NBT。
        // 先用缓存填一次避免闪烁，同时向服务端拉取最新值。
        // 记录发请求前的版本号：缓存是全局静态的，之前的界面可能已经把它推到 >0，
        // 只有版本再次变化才说明本次请求的响应真正到达（见 spaceSynced）。
        this.spaceVersion = com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache.version();
        refreshDepositedFromSpace();
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_RequestMaterialSpaceMessage());
        // 材料清单同样以服务端为准：打开界面时拉一次最新的蓝图数据，
        // 避免显示上一次会话残留的、或被服务端拒绝过的条目
        String reqId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (!reqId.isEmpty()) {
            BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestBlueprintDataMessage(reqId));
        }
        buildMatEntries(); // M9: pre-build entry snapshot so renderMaterialPanel needs no per-frame alloc
    }

    /** Rebuilds {@link #cachedMatEntries} from the live material map, or clears it when absent. */
    private void buildMatEntries() {
        MaterialList ml = mats();
        if (ml != null) {
            cachedMatEntries = new ArrayList<>(ml.blockCounts.entrySet());
        } else {
            cachedMatEntries = List.of();
        }
    }

    /**
     * 当前生效的材料清单：有本地未保存编辑时用副本，否则用服务端权威数据。
     */
    @Nullable
    private MaterialList mats() {
        if (localMats != null) return localMats;
        return (data != null) ? data.materialList : null;
    }

    /**
     * 进入本地编辑：把清单换成一份独立副本。
     *
     * <p>{@link #data} 是 {@link ClientBlueprintCache} 里的共享实例，直接改它的
     * {@code blockCounts} 会污染缓存、波及蓝图列表等其他界面。</p>
     */
    private void beginLocalEdit() {
        if (localMats != null) return;
        MaterialList src = (data != null) ? data.materialList : null;
        localMats = (src != null) ? src.copy() : new MaterialList();
    }

    /**
     * 是否<b>存在</b>材料清单对象（允许为空清单）。
     *
     * <p>与 {@link #isProjection()} 的区别：那个要求清单<b>非空</b>，用于材料校验；
     * 本方法只问「有没有这份清单」，用于决定材料面板要不要显示 ——
     * OP 把最后一条材料删光后，面板必须继续开着，否则他没机会点 ⎙ 保存这份空清单。</p>
     */
    private boolean hasMaterialList() {
        return mats() != null;
    }

    /** 当前是否有可展示/编辑的材料清单（动态计算，反映客户端侧删除操作）。 */
    private boolean isProjection() {
        MaterialList ml = mats();
        return ml != null && !ml.blockCounts.isEmpty();
    }

    /**
     * 是否为强制捕获（{@link com.xioyim.spatialblueprint.util.CaptureMode#FORCE}）的蓝图。
     *
     * <p>这类蓝图只有 OP + 创造模式能使用，材料清单底部的全部按钮一律隐藏 ——
     * 它既不需要提交材料，也不该在这条路径上被编辑。</p>
     */
    private boolean isForceBlueprint() {
        return data != null && (data.saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE
                || data.uploadMode == 2);
    }

    private boolean isRewardBlueprint() {
        return stack.getItem() instanceof com.xioyim.spatialblueprint.item.RewardBlueprintItem;
    }

    /** 是否允许编辑材料清单（✚ 新增 / ✎ 改数量 / 删除条目 / ⎙ 保存）：仅创造模式 OP。
     * 奖励蓝图虽为 FORCE 捕获，但允许 OP 事后自定义耗材。 */
    private boolean canEditMaterials() {
        return player.isCreative() && player.hasPermissions(2)
                && (!isForceBlueprint() || isRewardBlueprint());
    }

    /**
     * 是否需要玩家提交材料 —— 只有<b>投影 / 工程</b>蓝图需要，且必须是生存模式。
     *
     * <p>创造模式放置不消耗材料，OP 也不例外（权限不改变游戏模式的规则）；
     * 次元蓝图的材料在捕获时已自动入次元袋，玩家无从手动提交。</p>
     */
    private boolean needsMaterialSubmit() {
        if (player.isCreative()) return false;
        return stack.getItem() instanceof ProjectionBlueprintItem
                || stack.getItem() instanceof com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
    }

    /**
     * 是否为「材料清单仅供查阅」的蓝图 —— 只有<b>空间蓝图</b>属于此类。
     *
     * <p>注意 {@code DimensionalBlueprintItem extends SpatialBlueprintItem}，
     * 单用 {@code instanceof SpatialBlueprintItem} 会把次元蓝图也算进来，
     * 导致次元蓝图不显示「已存入/需要」、材料不足也不拦截 —— 等于永远免费放置。
     * 因此必须显式排除次元蓝图。</p>
     */
    private boolean isSpatialOnly() {
        return stack.getItem() instanceof SpatialBlueprintItem
                && !(stack.getItem() instanceof com.xioyim.spatialblueprint.item.DimensionalBlueprintItem);
    }

    @Override
    protected void init() {
        super.init();

        // 对话框按钮（原版 Button，手动 render / 命中，不加入 renderables —— 与底部材料按钮同一套做法）
        editOkBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.confirm"),
                        b -> confirmEditCount())
                .bounds(0, 0, 60, 20).build();
        editCancelBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.cancel"),
                        b -> { editBlockId = null; editInput.setLength(0); })
                .bounds(0, 0, 60, 20).build();
        deleteOkBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.confirm"),
                        b -> confirmDeleteMaterial())
                .bounds(0, 0, 60, 20).build();
        deleteCancelBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.cancel"),
                        b -> deleteBlockId = null)
                .bounds(0, 0, 60, 20).build();
        clearOkBtn = Button.builder(
                        Component.translatable("spatial_blueprint.gui.placement.confirm_clear_all.btn"),
                        b -> confirmClearAllMaterials())
                .bounds(0, 0, 60, 20).build();
        clearCancelBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.cancel"),
                        b -> confirmClearAll = false)
                .bounds(0, 0, 60, 20).build();

        // 材料面板底部按钮（手动 render，不加入 renderables 以保证 z 顺序正确）
        int sz = MAT_BOT_BTN_H;
        // ✚ 新增 / ⎙ 保存：仅创造模式 OP；✎ 快捷存入：仅生存模式的投影 / 工程蓝图。
        // 三者对强制捕获的蓝图一律不显示（见 canEditMaterials / showBotRow）。
        matBtnAdd  = canEditMaterials()
                ? Button.builder(Component.literal("✚"), b -> {}).bounds(0, 0, sz, sz).build()
                : null;
        matBtnDep  = needsMaterialSubmit()
                ? Button.builder(Component.literal("✎"), b -> {}).bounds(0, 0, sz, sz).build()
                : null;
        matBtnSave = canEditMaterials()
                ? Button.builder(Component.literal("⎙").withStyle(ChatFormatting.GOLD), b -> {}).bounds(0, 0, sz, sz).build()
                : null;
        // ✎ 提取并载入材料 / ✖ 清空全部材料：奖励蓝图专属（创造 OP；FORCE 捕获只有奖励蓝图能编辑）
        boolean rewardOps = isRewardBlueprint() && canEditMaterials();
        matBtnGen = rewardOps
                ? Button.builder(Component.literal("✎"), b -> {}).bounds(0, 0, sz, sz).build()
                : null;
        matBtnClear = rewardOps
                ? Button.builder(Component.literal("✖").withStyle(ChatFormatting.RED), b -> {}).bounds(0, 0, sz, sz).build()
                : null;

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
                Minecraft.getInstance(), ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE)) != null;

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
            int margin = ConfigAccess.get(BlueprintConfig.RENDER_CANCEL_MARGIN);
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
                        .append(Component.literal("\n"))
                        .append(Component.translatable(
                                "spatial_blueprint.gui.placement.tooltip.hidemode.visual_only")
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
        matBtn.active = hasMaterialList() || canEditMaterials();
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
            // 先开「加载中」报告页再发包（回包到达时 handleClient 才能找到加载页），
            // 报告确认后直接发创建消息（模式由报告页决定：净化 / OP 强制）；名字 / 备注 / 时长
            // 沿用源蓝图，想改名去蓝图库 —— 不再多一页创建页。
            final BlueprintData srcData = data;
            java.util.function.Consumer<com.xioyim.spatialblueprint.util.CaptureMode> modeCallback = mode -> {
                BlueprintNetwork.CHANNEL.sendToServer(
                        new com.xioyim.spatialblueprint.network.C2S_CreateEngineeringBlueprintMessage(
                                bpId, srcData.name, dimStr, previewMin, previewMax, mode,
                                srcData.animDuration,
                                srcData.noteShort == null ? "" : srcData.noteShort,
                                srcData.noteLong == null ? "" : srcData.noteLong));
                Minecraft.getInstance().setScreen(null);
            };
            Minecraft.getInstance().setScreen(new UploadReportScreen(
                    PlacementScreen.this, "engineering", data.name, "", 0L, false, modeCallback));
            BlueprintNetwork.CHANNEL.sendToServer(
                    new com.xioyim.spatialblueprint.network.C2S_RequestEngineeringReportMessage(bpId));
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

        // ── 材料空间同步的超时兜底 ────────────────────────────────────────
        // C2S_RequestMaterialSpaceMessage 在服务端读不到清单等情况下不会回包，
        // 不兜底的话按钮会永远卡在「同步中」而无法构建。超时后退回按本地数据判定。
        if (!spaceSynced && System.currentTimeMillis() - openedAt > 1500L) {
            spaceSynced = true;
            rebuildWidgets();
        }

        // ── FORCE / 归属 的补充拦截（只做一次）─────────────────────────────
        // 右键那一刻数据可能还没从服务端到达，saveMode 与 ownerUuid 都无从判断，
        // 界面就照常打开了。数据到位后在这里补判一次。
        //
        // ⚠ 必须用标志去重：tick 每帧都跑，不去重的话非 OP 持 FORCE 蓝图时
        // 每帧都会弹一次横幅、发一个销毁包，表现就是疯狂闪屏。
        if (!lateGuardChecked && data != null) {
            lateGuardChecked = true;
            if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                    .intercept(player, stack, data)) return;
            if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                    .interceptNotOwner(player, stack, data)) return;
            if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                    .interceptForeignWorld(player, stack, data)) return;
            // 已放置的次元蓝图：数据迟到时早期入口判不了，补判并连界面一起关掉
            if (data.placed) {
                SpatialBlueprintItem.discardInvalidBlueprint(stack);
                return;
            }
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
                ? Component.translatable("spatial_blueprint.gui.placement.btn.downloading_progress", pct)
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
            // 材料空间是异步同步的：首个同步包到达前 localDeposited 必然为空，
            // 这时下「材料不足」的结论是误报（池里可能早就存够了）。
            // 同步未到达就先显示中性的「构建蓝图」+ 同步中提示，到达后由 render() 重建控件修正。
            boolean syncing = !spaceSynced;
            ColoredButton btn = new ColoredButton(x, y, w, h,
                    Component.translatable(syncing
                            ? "spatial_blueprint.gui.placement.deploy"
                            : "spatial_blueprint.gui.placement.deploy.no_materials"),
                    b -> {},
                    Tooltip.create(Component.translatable(syncing
                            ? "spatial_blueprint.gui.placement.tooltip.deploy.syncing"
                            : "spatial_blueprint.gui.placement.tooltip.deploy.no_materials")),
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
                : BlueprintSelectionRenderer.getPreviewTarget(mc, ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE));
        if (targetBase == null) return null;
        int[] bb = computeRelBBox();
        int alignOffX = BlueprintSelectionRenderer.alignOffsetFromMinMax(bb[0], bb[1], mode);
        int alignOffZ = BlueprintSelectionRenderer.alignOffsetFromMinMax(bb[2], bb[3], mode);
        return targetBase.above().offset(xOffset + alignOffX, yOffset, zOffset + alignOffZ);
    }

    /** 结构相对 bbox：返回 {minX, maxX, minZ, maxZ}（旋转后实际方块范围）。 */
    private int[] computeRelBBox() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        if (data != null) {
            for (BlueprintData.BlockEntry entry : data.allBlocks()) {
                int tx = com.xioyim.spatialblueprint.util.BlueprintTransform.x(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(), data.size, rotation);
                int tz = com.xioyim.spatialblueprint.util.BlueprintTransform.z(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(), data.size, rotation);
                if (tx < minX) minX = tx;
                if (tx > maxX) maxX = tx;
                if (tz < minZ) minZ = tz;
                if (tz > maxZ) maxZ = tz;
            }
        }
        if (minX == Integer.MAX_VALUE) { minX = 0; maxX = 0; minZ = 0; maxZ = 0; }
        return new int[]{minX, maxX, minZ, maxZ};
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
            lockedPos = BlueprintSelectionRenderer.getPreviewTarget(mc, ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE));
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
                : BlueprintSelectionRenderer.getPreviewTarget(mc, ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE));
        if (targetBase == null) return;

        AlignMode mode = BlueprintSelectionRenderer.getAlignMode(stack);
        int[] bb = computeRelBBox();
        int alignOffX = BlueprintSelectionRenderer.alignOffsetFromMinMax(bb[0], bb[1], mode);
        int alignOffZ = BlueprintSelectionRenderer.alignOffsetFromMinMax(bb[2], bb[3], mode);
        BlockPos finalOrigin = targetBase.above().offset(xOffset + alignOffX, yOffset, zOffset + alignOffZ);

        // 启动放置加载动画（从底部往上填充的 ghost box，颜色按蓝图类型）
        int animDuration = data.animDuration > 0 ? data.animDuration : 8;
        int animColor;
        if (stack.getItem() instanceof com.xioyim.spatialblueprint.item.DimensionalBlueprintItem) {
            animColor = 0xFC9274;
        } else if (stack.getItem() instanceof com.xioyim.spatialblueprint.item.EngineeringBlueprintItem) {
            animColor = 0x7C6FBD;
        } else if (stack.getItem() instanceof com.xioyim.spatialblueprint.item.ProjectionBlueprintItem) {
            animColor = 0x73DA9A;
        } else {
            animColor = 0x6886C5; // 空间蓝图
        }
        BlockPos animSize = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? new BlockPos(data.size.getZ(), data.size.getY(), data.size.getX())
                : data.size;
        BlueprintSelectionRenderer.startPlacementAnimation(finalOrigin, animSize, animDuration, animColor);

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
        MaterialList ml = mats();
        if (n == 0 || ml == null || ml.blockCounts.isEmpty()) return;
        ml.blockCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .forEach(e -> BlueprintSelectionRenderer.hiddenTopBlocks.add(e.getKey()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (showMaterials && hasMaterialList() && matMaxScrollRow > 0
                && mouseX >= matPanelX && mouseX < matPanelX + matPanelW
                && mouseY >= matPanelY && mouseY < matPanelY + matPanelH) {
            matScrollRow = Math.max(0, Math.min(matScrollRow - (int) Math.signum(delta), matMaxScrollRow));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 服务端推来新的蓝图数据时（材料清单被增删改后会回推），用权威版本替换本地引用
        int bv = ClientBlueprintCache.version();
        if (bv != blueprintVersion) {
            blueprintVersion = bv;
            refreshDataFromCache();
        }
        // 服务端推来新的材料空间数据时，用权威值覆盖乐观更新
        int v = com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache.version();
        if (v != spaceVersion) {
            spaceVersion = v;
            spaceSynced  = true;
            refreshDepositedFromSpace();
            // 放置按钮的「材料不足 / 可构建」是在 init() 里算好的，不重建控件的话
            // 同步到达后按钮会一直停在旧状态（这正是「材料够却显示材料不足」的成因）。
            rebuildWidgets();
        }
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showMaterials && hasMaterialList()) {
            renderMaterialPanel(graphics, mouseX, mouseY);
        }
        // ── 悬浮 overlay（每次只显示一个，按优先级：清空确认 > 删除确认 > 数量编辑 > 右键菜单）
        if (confirmClearAll) {
            renderConfirmClearAllPanel(graphics, mouseX, mouseY);
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
        MaterialList ml = mats();
        if (ml == null) {
            ml = ProjectionBlueprintItem.getMaterialListClient(stack);
            if (ml == null) {
                BlueprintData bd = ProjectionBlueprintItem.getBlueprintDataClient(stack);
                if (bd != null) ml = MaterialList.fromBlueprintData(bd);
            }
        }

        int panelX = this.width / 2 - 149;  // MAT_PANEL_W/2 = 298/2
        int row1Y  = this.height - 45 - 18 - 4;

        // ── 完全没有清单对象 ──────────────────────────────────────────────
        if (ml == null) {
            if (canEditMaterials()) {
                // 奖励蓝图由 OP 开编：初始化一份空的本地清单以展示底部按钮行
                beginLocalEdit();
                ml = localMats;
            } else {
                // 其他情况：只显示一行占位，没有可操作内容
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
        }

        // 注意：清单存在但为空时【不能】在这里 return —— 面板要照常画出来（含底部按钮行），
        // 否则 OP 删掉最后一条材料后面板立刻消失，他就没机会点 ⎙ 把这份空清单存下去，
        // 也就做不出「构建不消耗任何材料」的免费蓝图。空清单的提示见下方内容区。

        // ── 布局计算 ──────────────────────────────────────────────────────
        List<Map.Entry<String, Integer>> entries = cachedMatEntries; // M9: use pre-built snapshot
        int totalRows = (entries.size() + MAT_COLS - 1) / MAT_COLS;
        matMaxScrollRow = Math.max(0, totalRows - MAT_VISIBLE);
        matScrollRow    = Math.max(0, Math.min(matScrollRow, matMaxScrollRow));

        int contentH = MAT_VISIBLE * MAT_CELL_H;
        // 空间蓝图的清单仅供查阅；强制捕获的蓝图（除奖励蓝图外）只有 OP 创造模式能用，
        // 既不提交材料也不在这里编辑 —— 两者都不显示底部按钮行。
        boolean showBotRow = !isSpatialOnly() && (!isForceBlueprint() || isRewardBlueprint())
                && (matBtnAdd != null || matBtnDep != null || matBtnSave != null);
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
        boolean depHov = false, saveHov = false, genHov = false, clearHov = false;
        matAddBtnW = 0; matQuickDepBtnW = 0; matSaveBtnW = 0; matGenBtnW = 0; matClearBtnW = 0;

        // 分割线（全宽至内容右边缘）
        int sepY = panelY + MAT_PAD + MAT_HEADER_H;
        graphics.fill(panelX + MAT_PAD, sepY, panelX + MAT_PANEL_W - MAT_PAD, sepY + 1, 0xFF444444);

        // ── 内容区域 ──────────────────────────────────────────────────────
        int contentY = sepY + 1 + MAT_SEP_PAD;
        this.matContentY = contentY;
        // 物品格子区深色背景（50% 透明）
        graphics.fill(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH, 0x801A1A1A);

        // ── 内容格子（带裁剪）───────────────────────────────────────────
        // 图标按 16×16 原生尺寸渲染，不套 pose scale —— 缩放后的物品模型会失真，
        // 与 /sb space 的图标看起来是两种东西。数量文字仍缩小，以免撑破格宽。
        graphics.enableScissor(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH);

        ItemStack hoveredStack = ItemStack.EMPTY;

        // 空清单：内容区居中给一句说明。这不是错误状态 ——
        // OP 可以有意存一份不消耗任何材料的「免费蓝图」。
        if (entries.isEmpty()) {
            Component freeMsg = Component.translatable("spatial_blueprint.gui.placement.material_list.free");
            graphics.drawString(font, freeMsg,
                    panelX + MAT_PAD + (MAT_CONTENT_W - font.width(freeMsg)) / 2,
                    contentY + (contentH - font.lineHeight) / 2,
                    0x88DD88, false);
        }

        for (int i = 0; i < entries.size(); i++) {
            int row = i / MAT_COLS;
            int col = i % MAT_COLS;

            int screenX = panelX + MAT_PAD + col * MAT_CELL_W;
            int screenY = contentY + (row - matScrollRow) * MAT_CELL_H;

            if (screenY + MAT_CELL_H <= contentY || screenY >= contentY + contentH) continue;

            Map.Entry<String, Integer> entry = entries.get(i);

            // 构建带 NBT 的显示 ItemStack（含附魔光效和完整 tooltip）
            ItemStack displayStack = ml.getDisplayStack(entry.getKey());

            // ── 图标（overlay z=300 > renderItem z=232，overlay 始终遮盖图标）──
            // 与 /sb space 使用同一套安全渲染：崩溃隔离 + PoseStack 修正 + GL 状态还原
            int iconY = screenY + (MAT_CELL_H - MAT_ICON_SCR) / 2;
            SafeItemRenderer.render(graphics, displayStack, screenX, iconY);

            // ── 数量：空间蓝图仅显示总量，其余显示已存入/需要 ──────────
            int depCount  = localDeposited.getOrDefault(entry.getKey(), 0);
            int needCount = entry.getValue();
            String countStr   = isSpatialOnly() ? String.valueOf(needCount) : (depCount + "/" + needCount);
            int    countColor = isSpatialOnly() ? 0xAAAAAA : (depCount >= needCount ? 0x55FF55 : 0xFFAA00);

            graphics.pose().pushPose();
            graphics.pose().scale(MAT_SCALE, MAT_SCALE, 1f);
            int tx = Math.round((screenX + MAT_ICON_SCR + 2) / MAT_SCALE);
            int ty = Math.round((screenY + (MAT_CELL_H - font.lineHeight * MAT_SCALE) / 2f) / MAT_SCALE);
            graphics.drawString(font, countStr, tx, ty, countColor, false);
            graphics.pose().popPose();

            // 鼠标悬浮于图标上：记录 ItemStack 供 tooltip 使用（含附魔等完整 NBT）
            if (hoveredStack.isEmpty()
                    && mouseX >= screenX && mouseX < screenX + MAT_ICON_SCR
                    && mouseY >= iconY && mouseY < iconY + MAT_ICON_SCR
                    && screenY >= contentY && screenY < contentY + contentH) {
                hoveredStack = displayStack;
            }
        }

        graphics.disableScissor();

        // ── 底部按钮行（非空间蓝图，正方形原版风格，左对齐）────────────
        if (showBotRow) {
            int botY  = panelY + panelH - MAT_PAD - MAT_BOT_BTN_H;
            int sz    = MAT_BOT_BTN_H;
            int gap   = 3;
            int bx = panelX + MAT_PAD;

            // ✚ 新增（创造模式 + OP 专属）
            if (matBtnAdd != null) {
                matAddBtnX = bx; matAddBtnY = botY; matAddBtnW = sz; matAddBtnH = sz;
                addBtnHovered = mouseX >= bx && mouseX < bx + sz
                             && mouseY >= botY && mouseY < botY + sz;
                matBtnAdd.setX(bx); matBtnAdd.setY(botY);
                matBtnAdd.render(graphics, mouseX, mouseY, 0f);
                bx += sz + gap;
            }

            // ✎ 快捷存入（仅生存模式的投影 / 工程蓝图）
            if (matBtnDep != null) {
                matQuickDepBtnX = bx; matQuickDepBtnY = botY; matQuickDepBtnW = sz; matQuickDepBtnH = sz;
                depHov = mouseX >= bx && mouseX < bx + sz
                      && mouseY >= botY && mouseY < botY + sz;
                matBtnDep.setX(bx); matBtnDep.setY(botY);
                matBtnDep.render(graphics, mouseX, mouseY, 0f);
                bx += sz + gap;
            }

            // ✎ 提取并载入材料（奖励蓝图专属：按结构生成 + 剔非法方块 + 直接写盘）
            if (matBtnGen != null) {
                matGenBtnX = bx; matGenBtnY = botY; matGenBtnW = sz; matGenBtnH = sz;
                genHov = mouseX >= bx && mouseX < bx + sz
                      && mouseY >= botY && mouseY < botY + sz;
                matBtnGen.setX(bx); matBtnGen.setY(botY);
                matBtnGen.render(graphics, mouseX, mouseY, 0f);
                bx += sz + gap;
            }

            // ✖ 清空全部材料（奖励蓝图专属：确认后直接写盘）
            if (matBtnClear != null) {
                matClearBtnX = bx; matClearBtnY = botY; matClearBtnW = sz; matClearBtnH = sz;
                clearHov = mouseX >= bx && mouseX < bx + sz
                        && mouseY >= botY && mouseY < botY + sz;
                matBtnClear.setX(bx); matBtnClear.setY(botY);
                matBtnClear.render(graphics, mouseX, mouseY, 0f);
                bx += sz + gap;
            }

            // ⎙ 保存至服务器文件（创造模式 + OP 专属）
            if (matBtnSave != null) {
                matSaveBtnX = bx; matSaveBtnY = botY; matSaveBtnW = sz; matSaveBtnH = sz;
                saveHov = mouseX >= bx && mouseX < bx + sz
                       && mouseY >= botY && mouseY < botY + sz;
                matBtnSave.setX(bx); matBtnSave.setY(botY);
                matBtnSave.render(graphics, mouseX, mouseY, 0f);
            }
        }

        // ── Tooltip（有 overlay 时不显示，避免遮挡）─────────────────────
        boolean anyOverlay = ctxBlockId != null || editBlockId != null
                          || deleteBlockId != null || confirmClearAll;
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
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line5")
                                    .withStyle(ChatFormatting.GREEN).getVisualOrderText()
                        );
                graphics.renderTooltip(font, depLines, mouseX, mouseY);
            } else if (genHov) {
                java.util.List<net.minecraft.util.FormattedCharSequence> genLines = java.util.List.of(
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.reward_generate.title")
                                .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.reward_generate.line1")
                                .withStyle(ChatFormatting.GRAY).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.reward_generate.line2")
                                .withStyle(ChatFormatting.RED).getVisualOrderText()
                );
                graphics.renderTooltip(font, genLines, mouseX, mouseY);
            } else if (clearHov) {
                java.util.List<net.minecraft.util.FormattedCharSequence> clearLines = java.util.List.of(
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.reward_clear.title")
                                .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.reward_clear.line1")
                                .withStyle(ChatFormatting.GRAY).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.reward_clear.line2")
                                .withStyle(ChatFormatting.RED).getVisualOrderText()
                );
                graphics.renderTooltip(font, clearLines, mouseX, mouseY);
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
            } else if (!hoveredStack.isEmpty()) {
                graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
            } else if (titleHovered && data != null) {
                // 蓝图列表同款元数据 tooltip（缺「文件名字」——BlueprintData 不带 blueprintId）
                graphics.renderTooltip(font, BlueprintTooltips.buildDataTip(data), mouseX, mouseY);
            }
        }
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

    // ── 材料右键菜单 & 编辑 overlay ────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        int mx = (int) mouseX, my = (int) mouseY;

        // ── 清空全部材料确认 overlay（按钮是原版 Button，命中由按钮自己处理）──
        if (confirmClearAll) {
            if (!canEditMaterials()) { confirmClearAll = false; return true; }
            if (clearOkBtn != null && clearOkBtn.mouseClicked(mouseX, mouseY, button)) return true;
            if (clearCancelBtn != null && clearCancelBtn.mouseClicked(mouseX, mouseY, button)) return true;
            final int CW = 200, CH = 80;
            int cwx = (this.width - CW) / 2, cwy = (this.height - CH) / 2;
            // 点击面板外关闭
            if (mx < cwx || mx >= cwx + CW || my < cwy || my >= cwy + CH) confirmClearAll = false;
            return true;
        }

        // ── 删除确认 overlay（按钮是原版 Button，命中由按钮自己处理）─────────
        if (deleteBlockId != null) {
            if (!canEditMaterials()) { deleteBlockId = null; return true; }
            if (deleteOkBtn != null && deleteOkBtn.mouseClicked(mouseX, mouseY, button)) return true;
            if (deleteCancelBtn != null && deleteCancelBtn.mouseClicked(mouseX, mouseY, button)) return true;
            final int DW = 200, DH = 80;
            int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;
            // 点击面板外关闭
            if (mx < dx || mx >= dx + DW || my < dy || my >= dy + DH) deleteBlockId = null;
            return true;
        }

        // ── 数量编辑 overlay（按钮是原版 Button，命中由按钮自己处理）─────────
        if (editBlockId != null) {
            if (!canEditMaterials()) { editBlockId = null; editInput.setLength(0); return true; }
            if (editOkBtn != null && editOkBtn.mouseClicked(mouseX, mouseY, button)) return true;
            if (editCancelBtn != null && editCancelBtn.mouseClicked(mouseX, mouseY, button)) return true;
            final int EW = 200, EH = 90;
            int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;
            if (mx < ex || mx >= ex + EW || my < ey || my >= ey + EH) {
                editBlockId = null; editInput.setLength(0);
            }
            return true;
        }

        // ── 右键菜单 overlay（仅创造模式）───────────────────────────────
        if (ctxBlockId != null) {
            final int CM_ROWS = 2;   // [修改数量] [删除]（复制名字 / 复制 ID / 存入 已移除，见 e35）
            final int CMW = 130, CM_ROW_H = 16, CM_PAD = 4;
            final int CMH = CM_PAD + CM_ROWS * CM_ROW_H + CM_PAD;
            int cx2 = Math.min(ctxX, this.width - CMW - 2);
            int cy2 = Math.min(ctxY, this.height - CMH - 2);
            if (mx >= cx2 && mx < cx2 + CMW && my >= cy2 && my < cy2 + CMH) {
                int row = (my - cy2 - CM_PAD) / CM_ROW_H;
                if (row >= 0 && row < CM_ROWS) {
                    switch (row) {
                        case 0 -> {
                            MaterialList ctxMl = mats();
                            if (ctxMl != null) {
                                editBlockId = ctxBlockId;
                                editInput.setLength(0);
                                editInput.append(ctxMl.blockCounts.getOrDefault(ctxBlockId, 0));
                            }
                        }
                        case 1 -> deleteBlockId = ctxBlockId;
                    }
                }
            }
            ctxBlockId = null;
            return true;
        }

        // ── ✚ 新增材料按钮点击（仅创造模式 OP）────────────────────────────
        if (button == 0 && showMaterials && canEditMaterials() && matAddBtnW > 0
                && mx >= matAddBtnX && mx < matAddBtnX + matAddBtnW
                && my >= matAddBtnY && my < matAddBtnY + matAddBtnH) {
            Minecraft.getInstance().setScreen(new MaterialItemPickerScreen(this, this::addMaterialLocally));
            return true;
        }

        // ── ✎ 快捷存入（仅生存模式的投影 / 工程蓝图）──────────────────────
        if (button == 0 && showMaterials && isProjection() && needsMaterialSubmit() && matQuickDepBtnW > 0
                && mx >= matQuickDepBtnX && mx < matQuickDepBtnX + matQuickDepBtnW
                && my >= matQuickDepBtnY && my < matQuickDepBtnY + matQuickDepBtnH) {
            handleQuickDeposit();
            return true;
        }

        // ── ⎙ 保存材料清单至服务器文件（仅创造模式 OP）───────────────────
        if (button == 0 && showMaterials && canEditMaterials() && matSaveBtnW > 0
                && mx >= matSaveBtnX && mx < matSaveBtnX + matSaveBtnW
                && my >= matSaveBtnY && my < matSaveBtnY + matSaveBtnH) {
            handleSaveMaterialList();
            return true;
        }

        // ── ✎ 提取并载入材料（奖励蓝图专属：服务端生成 + 剔非法 + 直接写盘）──
        if (button == 0 && showMaterials && canEditMaterials() && matGenBtnW > 0
                && mx >= matGenBtnX && mx < matGenBtnX + matGenBtnW
                && my >= matGenBtnY && my < matGenBtnY + matGenBtnH) {
            if (sendRewardIntent(com.xioyim.spatialblueprint.network.C2S_RewardMaterialsMessage.MODE_GENERATE)) {
                NotifySound.client();
            }
            return true;
        }

        // ── ✖ 清空全部材料（奖励蓝图专属；第一次点进确认覆盖层）────────────
        if (button == 0 && showMaterials && canEditMaterials() && matClearBtnW > 0
                && mx >= matClearBtnX && mx < matClearBtnX + matClearBtnW
                && my >= matClearBtnY && my < matClearBtnY + matClearBtnH) {
            String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
            if (bpId.isEmpty()) {
                NotifySound.clientError();
                errorToast = Component.translatable("spatial_blueprint.gui.placement.error.material_save_no_id");
                return true;
            }
            confirmClearAll = true;
            return true;
        }

        // ── 材料图标 → JEI 查配方（仅生存模式）─────────────────────────────
        // 左键 = 来源（这东西怎么来的），右键 = 用途（拿它能做什么）。
        //
        // 只在生存模式接管：创造模式下右键是「编辑清单」菜单（见下），
        // 两者抢同一个按键，必须错开。生存模式本来就没有那个菜单。
        if (!player.isCreative() && showMaterials && hasMaterialList() && matContentY >= 0
                && (button == 0 || button == 1)) {
            ItemStack jeiStack = materialStackAt(mx, my);
            if (!jeiStack.isEmpty()
                    && com.xioyim.spatialblueprint.compat.jei.SBJeiSupport
                            .showRecipe(jeiStack, button == 1)) {
                return true;
            }
        }

        // ── 右键点击材料图标 → 弹出菜单（仅创造模式 OP）───────────────────
        if (button == 1 && canEditMaterials() && showMaterials && hasMaterialList()
                && mats() != null && matContentY >= 0) {
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
                    // 复合键（带 NBT 摘要后缀）无法直接解析为 ResourceLocation，
                    // 改用显示用 ItemStack 的名称 —— 附魔物品还能带上自定义名。
                    MaterialList ctxMl = mats();
                    ItemStack nameStack = (ctxMl != null)
                            ? ctxMl.getDisplayStack(ctxBlockId) : ItemStack.EMPTY;
                    ctxBlockName = nameStack.isEmpty()
                            ? com.xioyim.spatialblueprint.util.MaterialKey.idOf(ctxBlockId)
                            : nameStack.getHoverName().getString();
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
        if (editBlockId != null) {
            if (!canEditMaterials()) { editBlockId = null; editInput.setLength(0); return true; }
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
            if (confirmClearAll) { confirmClearAll = false; return true; }
            if (deleteBlockId  != null) { deleteBlockId  = null; return true; }
            if (ctxBlockId     != null) { ctxBlockId     = null; return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (editBlockId != null) {
            if (Character.isDigit(c) && editInput.length() < 9) editInput.append(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    /** 确认数量编辑：只改本地副本，必须点 ⎙ 才写入服务端文件。 */
    private void confirmEditCount() {
        if (!canEditMaterials() || editBlockId == null || mats() == null) {
            editBlockId = null; editInput.setLength(0); return;
        }
        try {
            int newCount = Integer.parseInt(editInput.toString());
            if (newCount > 0) {
                beginLocalEdit();
                localMats.blockCounts.put(editBlockId, newCount);
                buildMatEntries();
                clearWidgets(); init();
            }
        } catch (NumberFormatException ignored) {}
        editBlockId = null;
        editInput.setLength(0);
    }

    /**
     * ✚ 新增材料 —— 由 {@link MaterialItemPickerScreen} 回调，只写本地副本。
     *
     * <p>非法方块的校验放在服务端保存入口：这里先让 OP 看到结果，点 ⎙ 时若清单含
     * 非法方块，服务端会拒绝并回推真实清单。带 NBT 的物品不做限制 ——
     * OP 手动添加即等同管理员准许其存在。</p>
     */
    private void addMaterialLocally(String materialKey, @Nullable net.minecraft.nbt.CompoundTag normTag, int count) {
        if (!canEditMaterials() || count <= 0) return;
        beginLocalEdit();
        localMats.blockCounts.merge(materialKey, count, Integer::sum);
        if (normTag != null) localMats.keyTags.putIfAbsent(materialKey, normTag);
        buildMatEntries();
        clearWidgets(); init();
    }

    // ── 材料右键菜单渲染 ────────────────────────────────────────────────────

    private void renderMaterialContextMenu(GuiGraphics g, int mouseX, int mouseY) {
        // 右键菜单仅在创造模式下开启（复制名字 / 复制 ID / 存入 已整体移除，见 e35）
        String[] labels = {
            Component.translatable("spatial_blueprint.gui.placement.context.edit_count").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.delete").getString()
        };
        int[] colors = { 0xFFFFFF, 0xFF5555 };
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
        final int EW = 200, EH = 90, BW = 60, BH = 20, BP = 8, PAD = 8;
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

        layoutAndRenderDialogButtons(g, mouseX, mouseY, editOkBtn, editCancelBtn, ex, ey, EW, EH, BW, BH, BP);
        g.pose().popPose();
    }

    private void renderDeleteMaterialPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (!player.isCreative()) { deleteBlockId = null; return; }
        final int DW = 200, DH = 80, BW = 60, BH = 20, BP = 8, PAD = 8;
        int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);

        String del_title   = Component.translatable("spatial_blueprint.gui.library.confirm_delete.title").getString();
        String del_warning = Component.translatable("spatial_blueprint.gui.library.confirm_delete.warning").getString();
        g.drawString(font, del_title, dx + PAD, dy + PAD, 0xFF5555, false);
        String name = ctxBlockName != null ? ctxBlockName : (deleteBlockId != null ? deleteBlockId : "");
        g.drawString(font, name, dx + PAD, dy + PAD + font.lineHeight + 3, 0xFFFFAA, false);
        g.drawString(font, del_warning, dx + PAD, dy + PAD + (font.lineHeight + 3) * 2, 0x888888, false);

        layoutAndRenderDialogButtons(g, mouseX, mouseY, deleteOkBtn, deleteCancelBtn, dx, dy, DW, DH, BW, BH, BP);
        g.pose().popPose();
    }

    /**
     * 摆好一个对话框的 [确定] [取消] 两个原版 Button 并手动渲染 ——
     * 不加入 renderables（overlay 必须画在所有控件之上），命中在 {@code mouseClicked} 里
     * 调 {@code Button#mouseClicked} 处理。两处布局必须一起看：改这里也要改那边。
     */
    private void layoutAndRenderDialogButtons(GuiGraphics g, int mouseX, int mouseY,
                                              @Nullable Button ok, @Nullable Button cancel,
                                              int panelX, int panelY, int panelW, int panelH,
                                              int btnW, int btnH, int pad) {
        int by = panelY + panelH - btnH - pad;
        if (ok != null) {
            ok.setX(panelX + panelW / 2 - btnW - 4);
            ok.setY(by);
            ok.render(g, mouseX, mouseY, 0f);
        }
        if (cancel != null) {
            cancel.setX(panelX + panelW / 2 + 4);
            cancel.setY(by);
            cancel.render(g, mouseX, mouseY, 0f);
        }
    }

    /** [删除] 确认：只改本地副本 —— 必须点 ⎙ 才写入服务端文件。 */
    private void confirmDeleteMaterial() {
        if (deleteBlockId == null) return;
        if (mats() != null) {
            beginLocalEdit();
            localMats.blockCounts.remove(deleteBlockId);
            localMats.keyTags.remove(deleteBlockId);
            buildMatEntries();
            clearWidgets(); init();
        }
        deleteBlockId = null;
    }

    // ── 奖励蓝图材料：✎ 提取并载入 / ✖ 清空全部 ─────────────────────────────

    /**
     * ✎ 提取 / ✖ 清空的公共发包：丢弃本地未保存副本 → 发服务端 → 结果由
     * 聊天栏 + 回推体现（成功不走 S2C_OperationResult，它会把界面关掉）。
     *
     * @return 是否真的发出（bpId 缺失时为 false，已弹横幅）
     */
    private boolean sendRewardIntent(int mode) {
        String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (bpId.isEmpty()) {
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.gui.placement.error.material_save_no_id");
            return false;
        }
        // 回推到达后 mats() 因 localMats == null 回落到服务端那份，面板自然刷新
        localMats = null;
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_RewardMaterialsMessage(bpId, mode));
        return true;
    }

    /** ✖ 清空确认覆盖层里点 [清空] 的落点。 */
    private void confirmClearAllMaterials() {
        confirmClearAll = false;
        if (!canEditMaterials()) return;
        sendRewardIntent(com.xioyim.spatialblueprint.network.C2S_RewardMaterialsMessage.MODE_CLEAR);
    }

    /** ✖ 清空全部材料的确认覆盖层（一行提示 + [清空][取消]，样式照单条 [删除] 的确认）。 */
    private void renderConfirmClearAllPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (!canEditMaterials()) { confirmClearAll = false; return; }
        final int DW = 200, DH = 80, BW = 60, BH = 20, BP = 8, PAD = 8;
        int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);

        String title = Component.translatable("spatial_blueprint.gui.placement.confirm_clear_all").getString();
        g.drawString(font, title, dx + PAD, dy + PAD, 0xFF5555, false);
        String warning = Component.translatable(
                "spatial_blueprint.gui.placement.tooltip.reward_clear.line2").getString();
        g.drawString(font, warning, dx + PAD, dy + PAD + font.lineHeight + 3, 0x888888, false);

        layoutAndRenderDialogButtons(g, mouseX, mouseY, clearOkBtn, clearCancelBtn, dx, dy, DW, DH, BW, BH, BP);
        g.pose().popPose();
    }

    // ── 存入/取出 辅助方法 ─────────────────────────────────────────────────

    /**
     * 材料面板上指定屏幕坐标处的物品；没有命中任何格子时返回 {@link ItemStack#EMPTY}。
     *
     * <p>命中判定与 {@code renderMaterialPanel} 里画图标的那段保持一致 ——
     * 同一套格子坐标算两遍容易走样，改布局时两处都要动。</p>
     */
    private ItemStack materialStackAt(int mx, int my) {
        MaterialList ml = mats();
        if (ml == null || matContentY < 0) return ItemStack.EMPTY;
        int panelX = this.width / 2 - 149;   // MAT_PANEL_W/2 = 298/2
        List<Map.Entry<String, Integer>> entries = cachedMatEntries;
        for (int i = 0; i < entries.size(); i++) {
            int row = i / MAT_COLS, col = i % MAT_COLS;
            int entryX = panelX + MAT_PAD + col * MAT_CELL_W;
            int entryY = matContentY + (row - matScrollRow) * MAT_CELL_H;
            if (entryY < matContentY
                    || entryY + MAT_ICON_SCR > matContentY + MAT_VISIBLE * MAT_CELL_H) continue;
            if (mx >= entryX && mx < entryX + MAT_ICON_SCR
                    && my >= entryY && my < entryY + MAT_ICON_SCR) {
                return ml.getDisplayStack(entries.get(i).getKey());
            }
        }
        return ItemStack.EMPTY;
    }

    /** 检查客户端乐观跟踪的存入量是否满足所有材料需求。 */
    private boolean hasSufficientLocalMaterials() {
        MaterialList ml = mats();
        if (ml == null) return true;
        for (Map.Entry<String, Integer> e : ml.blockCounts.entrySet()) {
            if (localDeposited.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /**
     * 用服务端权威的材料空间数据刷新 {@link #localDeposited}。
     * 服务端在每次存入 / 消耗后推送 {@code S2C_MaterialSpaceSyncMessage}，缓存随之更新。
     */
    private void refreshDepositedFromSpace() {
        localDeposited.clear();
        MaterialList ml = mats();
        if (ml == null) return;
        for (String key : ml.blockCounts.keySet()) {
            int c = com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache.count(key);
            if (c > 0) localDeposited.put(key, c);
        }
    }

    /**
     * 用服务端回推的蓝图数据替换本地 {@link #data} 引用并重建材料条目快照。
     *
     * <p>材料清单的权威数据在服务端 dat 文件。OP 的本地编辑暂存在 {@link #localMats}，
     * 而 {@link #mats()} 优先返回它 —— 因此这里替换 {@code data} 不会冲掉未保存的改动。
     * 玩家不点 ⎙ 就关界面的话，下次打开重新拉取，未保存的内容自然消失。</p>
     */
    private void refreshDataFromCache() {
        String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (bpId.isEmpty()) return;
        BlueprintData fresh = ClientBlueprintCache.get(bpId);
        if (fresh == null) return;
        boolean wasEmpty = (this.data == null);
        this.data = fresh;
        buildMatEntries();
        refreshDepositedFromSpace();
        // 数据是异步到达的：打开界面时 data 可能还是 null，此时 init() 已按「无数据」
        // 算好了按钮状态（材料按钮禁用 = 看起来没有材料清单）。数据到位后必须重建控件，
        // 否则材料按钮会一直是灰的。
        if (wasEmpty) rebuildWidgets();
    }

    /** 一键存入：乐观更新 localDeposited，然后发包。创造模式不消耗材料，故不参与。 */
    private void handleQuickDeposit() {
        MaterialList ml = mats();
        if (ml == null || !needsMaterialSubmit()) return;
        for (Map.Entry<String, Integer> entry : ml.blockCounts.entrySet()) {
            String blockId  = entry.getKey();
            int already     = localDeposited.getOrDefault(blockId, 0);
            int stillNeed   = entry.getValue() - already;
            if (stillNeed <= 0) continue;

            int available = countInInventory(player, blockId);
            int toDeposit = Math.min(stillNeed, available);
            if (toDeposit > 0) localDeposited.put(blockId, already + toDeposit);
        }
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_QUICK_DEPOSIT, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                SpatialBlueprintItem.getRotation(this.stack).name(),
                this.xOffset, this.yOffset, this.zOffset));
        clearWidgets(); init();
    }

    /** OP 手动将当前材料清单（需要数量）完整保存至服务端蓝图文件。 */
    private void handleSaveMaterialList() {
        MaterialList ml = mats();
        if (ml == null) return;
        String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (bpId.isEmpty()) {
            NotifySound.clientError();
            errorToast = Component.translatable("spatial_blueprint.gui.placement.error.material_save_no_id");
            return;
        }
        BlueprintNetwork.CHANNEL.sendToServer(
                new C2S_SaveMaterialListMessage(bpId, ml.blockCounts, ml.keyTags));
        // 用与错误提示同款的横幅给出保存反馈。
        // 不走 S2C_OperationResult 的成功分支 —— 那条路径会关闭本界面并只在聊天栏显示。
        // 若服务端校验失败（如清单含非法方块），随后到达的失败结果会覆盖这条横幅。
        NotifySound.client();
        errorToast = Component.translatable("spatial_blueprint.gui.placement.material_saved");
    }

    /**
     * 统计玩家背包中与该材料复合键<b>完全匹配</b>的物品总数。
     * 匹配基于物品类型 + 规范化 NBT，因此附魔、耐久不同的同种物品不会互相顶替
     * （与服务端 {@code C2S_MaterialDepositMessage.takeFromInventory} 的判定一致）。
     */
    private static int countInInventory(Player player, String materialKey) {
        int count = 0;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (materialKey.equals(com.xioyim.spatialblueprint.util.MaterialKey.of(s))) {
                count += s.getCount();
            }
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
