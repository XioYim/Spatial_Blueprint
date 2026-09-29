package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer;
import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer.AlignMode;
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.client.PlacementPreValidator;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_EngineeringTeleportMessage;
import com.xioyim.spatialblueprint.network.C2S_MaterialDepositMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage;
import com.xioyim.spatialblueprint.network.PlaceBlueprintMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工程蓝图界面：右键打开，支持材料存取、渲染对齐模式和构建工程触发。
 *
 * <p>目标位置在界面打开时从准星处捕获（无实时 3D 预览）。
 * 数据加载支持 {@link ClientBlueprintCache} 异步模式（多人联机）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class EngineeringBlueprintScreen extends Screen implements ErrorToastSupport {

    private final Player    player;
    /** Non-final: updated in tick() when ItemSyncUtil.syncHand() replaces the held item (Bug-1). */
    private ItemStack stack;

    @Nullable private BlueprintData data;

    private Component errorToast;

    /** Refreshed on every init() call. Null when preview is off / data / fixedStartPos missing. */
    @Nullable private PlacementPreValidator currentValidation;

    /** [Tp] 按钮；每次 init() 重建，tick 里按权限 / 冷却刷新置灰与 tooltip。 */
    @Nullable private Button tpBtn;
    /** 上一帧是否处于冷却，用来只在冷却期间及其结束那一帧刷 tooltip，避免每帧重建。 */
    private boolean tpCooling = false;


    // ── 材料面板布局常量（与 PlacementScreen 相同）────────────────────────────
    private static final int MAT_COLS      = 4;
    private static final int MAT_PANEL_W   = 298;
    private static final int MAT_PAD       = 8;
    private static final int MAT_HEADER_H  = 12;
    private static final int MAT_SEP_PAD   = 4;
    private static final int MAT_SB_W      = 4;
    private static final int MAT_SB_GAP    = 2;
    private static final int MAT_CONTENT_W = MAT_PANEL_W - MAT_PAD * 2 - MAT_SB_W - MAT_SB_GAP;
    private static final int MAT_CELL_W    = MAT_CONTENT_W / MAT_COLS;
    private static final int MAT_CELL_H    = 17;
    private static final int MAT_VISIBLE   = 6;
    /** 数量文字的缩放（图标不缩放，见 renderMaterialPanel）。 */
    private static final float MAT_SCALE   = 0.75f;
    /** 图标屏幕尺寸：与 /sb space 一致的原生 16×16。 */
    private static final int MAT_ICON_SCR  = 16;

    // ── 材料面板状态 ──────────────────────────────────────────────────────────
    private boolean showMaterials  = false;
    private int     matScrollRow   = 0;
    private int     matMaxScrollRow = 0;
    private int     matPanelX, matPanelY, matPanelW, matPanelH;
    private int     matContentY    = -1;

    // 快捷存入/取出按钮边界（W=0 表示未渲染）
    private int matQuickDepBtnX, matQuickDepBtnY, matQuickDepBtnW = 0, matQuickDepBtnH;

    /** 客户端乐观跟踪的已存入材料（权威数据在服务端材料空间）。 */
    private final Map<String, Integer> localDeposited = new LinkedHashMap<>();

    /** 上次同步到的材料空间版本号；与缓存版本不一致时用权威值刷新。 */
    private int spaceVersion = -1;

    /** 界面打开时刻，用于材料空间同步的超时兜底。 */
    private final long openedAt = System.currentTimeMillis();

    /**
     * 本次打开后是否已收到材料空间同步。首个同步包到达前 {@link #localDeposited} 必然为空，
     * 此时不能下「材料不足」的结论 —— 否则每次打开都会先误报一次。
     */
    private boolean spaceSynced = false;

    /** FORCE / 归属的延迟拦截是否已执行过 —— tick 每帧都跑，必须去重，否则闪屏。 */
    private boolean lateGuardChecked = false;

    /** 上次同步到的蓝图数据版本号；服务端回推新数据时用它替换本地 data 引用。 */
    private int blueprintVersion = -1;

    /**
     * 材料条目缓存：在 init() / data 到达时构建一次，render() 直接复用，
     * 避免每帧重新创建 ArrayList（每帧 60 次 BlueprintStorage 访问）。
     */
    private List<Map.Entry<String, Integer>> cachedMatEntries = List.of();

    // ─────────────────────────────────────────────────────────────────────────

    public EngineeringBlueprintScreen(Player player, ItemStack stack) {
        super(Component.translatable("spatial_blueprint.gui.engineering.title"));
        this.player = player;
        this.stack  = stack;
        this.data   = EngineeringBlueprintItem.getBlueprintDataClient(stack);
        // 已存入数量来自世界级材料空间（服务端权威），不再存于物品 NBT。
        // 先用缓存填一次避免闪烁，同时向服务端拉取最新值。
        // 记录发请求前的版本号：缓存是全局静态的，只有版本再次变化才说明本次响应到达。
        this.spaceVersion = com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache.version();
        refreshDepositedFromSpace();
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_RequestMaterialSpaceMessage());
        // 材料清单同样以服务端为准：打开界面时拉一次最新的蓝图数据
        String reqId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (!reqId.isEmpty()) {
            BlueprintNetwork.CHANNEL.sendToServer(
                    new com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage(reqId));
        }
        this.cachedMatEntries = buildMatEntries();
    }

    /** 是否有可显示的材料清单。 */
    private boolean hasMaterials() {
        return data != null && data.materialList != null && !data.materialList.blockCounts.isEmpty();
    }

    /**
     * 是否为强制捕获（{@link com.xioyim.spatialblueprint.util.CaptureMode#FORCE}）的蓝图 ——
     * 这类蓝图只有 OP 创造模式能用，不需要也不允许在这里提交材料。
     */
    private boolean isForceBlueprint() {
        return data != null && (data.saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE
                || data.uploadMode == 2);
    }

    /**
     * 是否需要玩家提交材料 —— 工程蓝图在生存模式下需要，创造模式不需要（OP 也不例外，
     * 权限不改变游戏模式的规则）。强制捕获的蓝图一律不提交。
     */
    private boolean needsMaterialSubmit() {
        return !player.isCreative() && !isForceBlueprint();
    }

    /**
     * 材料面板上指定屏幕坐标处的物品；没命中格子时返回 {@link ItemStack#EMPTY}。
     *
     * <p>命中判定与 {@code renderMaterialPanel} 里画图标那段用的是同一套格子坐标 ——
     * 改布局时两处都要动。</p>
     */
    private ItemStack materialStackAt(int mx, int my) {
        if (data == null || data.materialList == null || matContentY < 0) return ItemStack.EMPTY;
        int panelX = this.width / 2 - MAT_PANEL_W / 2;
        var entries = new ArrayList<>(data.materialList.blockCounts.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            int row = i / MAT_COLS, col = i % MAT_COLS;
            int ex = panelX + MAT_PAD + col * MAT_CELL_W;
            int ey = matContentY + (row - matScrollRow) * MAT_CELL_H;
            if (ey < matContentY
                    || ey + MAT_ICON_SCR > matContentY + MAT_VISIBLE * MAT_CELL_H) continue;
            if (mx >= ex && mx < ex + MAT_ICON_SCR && my >= ey && my < ey + MAT_ICON_SCR) {
                return data.materialList.getDisplayStack(entries.get(i).getKey());
            }
        }
        return ItemStack.EMPTY;
    }

    /** 构建（或重建）材料条目快照，供 render() 复用。 */
    private List<Map.Entry<String, Integer>> buildMatEntries() {
        if (data == null || data.materialList == null) return List.of();
        return new ArrayList<>(data.materialList.blockCounts.entrySet());
    }

    // ── Screen 生命周期 ───────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        // 防御：无材料数据时确保材料面板保持关闭
        if (!hasMaterials()) showMaterials = false;

        // 预览可能已被自动取消（走远 / 换维度 / 缓存清）：把 AlignMode 对齐到真实预览状态，
        // 否则按钮文案与 deploy 的放置门槛会拿着过期的 START
        String curBpId = EngineeringBlueprintItem.getBlueprintId(stack);
        if (curBpId != null && !curBpId.isEmpty()
                && BlueprintSelectionRenderer.getAlignMode(stack) != AlignMode.OFF
                && !com.xioyim.spatialblueprint.client.ClientEngineeringPreview.isFor(curBpId)) {
            BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.OFF);
        }

        int cx   = this.width / 2;
        int botH = 18;
        int botW = 52;
        int pGap = 4;
        int dW   = 18;  // 方形小按钮宽度（[Tp] / [✚]）
        int rowY = this.height - 45;

        // 总宽 = 5×botW + dW + 5×pGap = 260 + 18 + 20 = 298  →  offset = 149
        int rx = cx - 149;

        // ── [Tp] 传送到工程蓝图位置（最左侧）─────────────────────────────────
        // 权限来自服务端推送：OP / 创造模式 / 被 /sb tpperm 授予，与次元蓝图共用同一套判定；
        // 点下后 3 秒冷却（ClientTeleportPermCache），置灰 + tooltip「冷却中」，tick 里刷新
        tpBtn = addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.engineering.btn.tp"),
                b -> {
                    com.xioyim.spatialblueprint.client.ClientTeleportPermCache.markTeleportRequested();
                    BlueprintNetwork.CHANNEL.sendToServer(new C2S_EngineeringTeleportMessage());
                    refreshTpButton();
                })
            .bounds(rx - pGap - dW, rowY, dW, botH)
            .build());
        refreshTpButton();

        // ── [材料清单] ────────────────────────────────────────────────────────
        Component matLabel = showMaterials
                ? Component.translatable("spatial_blueprint.gui.placement.hide_materials")
                : Component.translatable("spatial_blueprint.gui.placement.show_materials");
        Button matBtn = addRenderableWidget(Button.builder(matLabel,
                b -> { if (!showMaterials) matScrollRow = 0; showMaterials = !showMaterials; clearWidgets(); init(); })
            .bounds(rx, rowY, botW, botH)
            .tooltip(Tooltip.create(Component.translatable(
                    "spatial_blueprint.gui.placement.tooltip.toggle_materials")))
            .build());
        matBtn.active = hasMaterials();
        rx += botW + pGap;

        // ── [预览开关] — ON/OFF 切换 / 下载数据 ──────────────────────────────
        String bpId = EngineeringBlueprintItem.getBlueprintId(stack);
        boolean hasBpId = bpId != null && !bpId.isEmpty();
        if (data != null) {
            // 有数据：根据维度/距离约束决定按钮状态
            net.minecraft.client.multiplayer.ClientLevel clientLevel = Minecraft.getInstance().level;
            boolean hasBound = EngineeringBlueprintItem.hasBoundProject(stack);
            EngineeringBlueprintItem.ProjectBoundsResult boundsResult =
                    (clientLevel != null && hasBound)
                    ? EngineeringBlueprintItem.checkProjectBounds(stack, player, clientLevel)
                    : EngineeringBlueprintItem.ProjectBoundsResult.OK;

            if (!hasBound) {
                // 未绑定工程坐标 → 灰色
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.engineering.btn.preview_on"), b -> {})
                    .bounds(rx, rowY, botW, botH)
                    .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.message.no_data")))
                    .build());
                btn.active = false;
            } else if (boundsResult == EngineeringBlueprintItem.ProjectBoundsResult.WRONG_DIM) {
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.engineering.btn.preview_on"), b -> {})
                    .bounds(rx, rowY, botW, botH)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.error.engineering.wrong_dimension")))
                    .build());
                btn.active = false;
            } else {
                // 距离口径 = 全局 previewDistance（服务端镜像优先，T8）；比服务端的
                // 放置校验（previewDistance + 512）严，按钮亮了服务端不会拒。
                int previewLimit = com.xioyim.spatialblueprint.client.ConfigAccess.get(
                        BlueprintConfig.PREVIEW_DISTANCE);
                BlockPos boundStart = EngineeringBlueprintItem.getBoundStartPos(stack);
                BlockPos boundEnd   = EngineeringBlueprintItem.getBoundEndPos(stack);
                net.minecraft.world.phys.Vec3 playerPos = player.position();
                double dToStart = playerPos.distanceTo(
                        net.minecraft.world.phys.Vec3.atCenterOf(boundStart));
                double dToEnd   = playerPos.distanceTo(
                        net.minecraft.world.phys.Vec3.atCenterOf(boundEnd));
                if (Math.min(dToStart, dToEnd) > previewLimit) {
                    Button btn = addRenderableWidget(Button.builder(
                            Component.translatable("spatial_blueprint.gui.engineering.btn.preview_on"), b -> {})
                        .bounds(rx, rowY, botW, botH)
                        .tooltip(Tooltip.create(buildTooFarTooltip(previewLimit)))
                        .build());
                    btn.active = false;
                } else {
                    // OK：显示 ON/OFF 切换按钮
                    boolean isPreviewOn = com.xioyim.spatialblueprint.client.ClientEngineeringPreview.isFor(bpId);
                    Component label = isPreviewOn
                            ? Component.translatable("spatial_blueprint.gui.engineering.btn.preview_off")
                            : Component.translatable("spatial_blueprint.gui.engineering.btn.preview_on");
                    Component previewTip = isPreviewOn
                            ? Component.translatable("spatial_blueprint.gui.engineering.tooltip.preview_off")
                            : Component.translatable("spatial_blueprint.gui.engineering.tooltip.preview_on");
                    addRenderableWidget(Button.builder(label, b -> togglePreview())
                        .bounds(rx, rowY, botW, botH)
                        .tooltip(Tooltip.create(previewTip))
                        .build());
                }
            }
        } else if (hasBpId && ClientBlueprintCache.isDownloading(bpId)) {
            Button dlBtn = addRenderableWidget(Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.btn.downloading"), b -> {})
                .bounds(rx, rowY, botW, botH).build());
            dlBtn.active = false;
        } else if (hasBpId) {
            addRenderableWidget(Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.btn.download_data"),
                    b -> requestBlueprintData(bpId))
                .bounds(rx, rowY, botW, botH)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.gui.placement.tooltip.download_data")))
                .build());
        } else {
            Button noDataBtn = addRenderableWidget(Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.btn.align.no_data"), b -> {})
                .bounds(rx, rowY, botW, botH).build());
            noDataBtn.active = false;
        }
        rx += botW + pGap;

        // ── 刷新客户端预校验结果 ───────────────────────────────────────────
        currentValidation = null;
        BlockPos placementStart = placementStart();
        if (data != null && BlueprintSelectionRenderer.getAlignMode(stack) != AlignMode.OFF
                && placementStart != null && Minecraft.getInstance().level != null) {
            currentValidation = PlacementPreValidator.validate(
                    Minecraft.getInstance().level, player, placementStart, data, Rotation.NONE);
        }

        // ── [构建工程] — 放置按钮 ───────────────────────────────────────────
        addRenderableWidget(buildDeployButton(rx, rowY, botW, botH));
        rx += botW + pGap;

        // ── [关闭] ───────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.placement.close"), b -> this.onClose())
            .bounds(rx, rowY, botW, botH)
            .tooltip(Tooltip.create(Component.translatable(
                    "spatial_blueprint.gui.engineering.tooltip.close")))
            .build());
        rx += botW + pGap;

        // ── [取消] ───────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.placement.cancel"), b -> cancel())
            .bounds(rx, rowY, botW, botH)
            .tooltip(Tooltip.create(Component.translatable(
                    "spatial_blueprint.gui.engineering.tooltip.cancel")))
            .build());
        rx += botW + pGap;

        // ── [✚] 蓝图列表（与 PlacementScreen 的 ✚ 同款：字形、tooltip、行为一致）──────
        // 之前叫 ⌘，跟放置界面「创建工程」的 ⌘ 撞了字形；这里只是开蓝图库，不限游戏模式。
        addRenderableWidget(Button.builder(Component.literal("✚"), b -> {
                    Minecraft.getInstance().setScreen(
                        new BlueprintLibraryScreen(EngineeringBlueprintScreen.this));
                })
            .bounds(rx, rowY, dW, botH)
            .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.library")))
            .build());
    }

    private Button buildDeployButton(int x, int y, int w, int h) {
        boolean isOff = BlueprintSelectionRenderer.getAlignMode(stack) == AlignMode.OFF;

        // 材料不足 —— 材料空间是异步同步的，首个同步包到达前 localDeposited 必然为空，
        // 这时下「材料不足」的结论是误报，先显示中性文案，同步到达后由 render() 重建控件修正。
        if (!player.isCreative() && hasMaterials() && !hasSufficientLocalMaterials()) {
            boolean syncing = !spaceSynced;
            Button btn = Button.builder(
                    Component.translatable(syncing
                            ? "spatial_blueprint.gui.placement.deploy"
                            : "spatial_blueprint.gui.placement.deploy.no_materials"),
                    b -> {})
                .bounds(x, y, w, h)
                .tooltip(Tooltip.create(Component.translatable(syncing
                        ? "spatial_blueprint.gui.placement.tooltip.deploy.syncing"
                        : "spatial_blueprint.gui.placement.tooltip.deploy.no_materials")))
                .build();
            btn.active = false;
            return btn;
        }

        // 无数据 / 渲染模式关闭 / 无固定坐标 / 无校验结果
        if (data == null || isOff || placementStart() == null || currentValidation == null) {
            Button btn = Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.deploy"),
                    b -> deploy())
                .bounds(x, y, w, h)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.gui.engineering.tooltip.deploy.disabled")))
                .build();
            btn.active = false;
            return btn;
        }

        // 根据校验结果决定标签 & 提示
        Component label;
        Component tip;
        switch (currentValidation.status) {
            case EXTRA_BLOCKS -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.extra_blocks");
            }
            case ENTITY_BLOCKING -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.entity");
            }
            case OUT_OF_BOUNDS -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.out_of_bounds");
            }
            case CHUNK_NOT_LOADED -> {
                label = Component.translatable("spatial_blueprint.gui.placement.deploy.blocked");
                tip   = Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.chunk_not_loaded");
            }
            default -> {  // OK / CREATIVE
                label = Component.translatable("spatial_blueprint.gui.placement.deploy");
                tip   = player.isCreative()
                        ? Component.translatable("spatial_blueprint.gui.placement.tooltip.deploy.creative")
                                .append(Component.literal("\n"))
                                .append(Component.translatable(
                                        "spatial_blueprint.gui.placement.tooltip.deploy.creative.warning")
                                        .withStyle(net.minecraft.ChatFormatting.RED))
                        : Component.translatable("spatial_blueprint.gui.engineering.tooltip.deploy.ok");
            }
        }

        Button btn = Button.builder(label, b -> deploy())
                .bounds(x, y, w, h)
                .tooltip(Tooltip.create(tip))
                .build();
        // 有障碍物时禁用（悬浮提示仍可见）
        boolean isBlocked = currentValidation.status != PlacementPreValidator.Status.OK
                         && currentValidation.status != PlacementPreValidator.Status.CREATIVE;
        if (isBlocked) btn.active = false;
        return btn;
    }

    /**
     * 按当前权限 / 绑定 / 冷却刷新 [Tp] 的置灰与 tooltip。
     *
     * <p>优先级：无权限 → 「你没有传送权限」；冷却中 → 「冷却中：N 秒」（每秒变）；否则正常提示。
     * 只在 init、点击、以及 tick 里冷却期间与冷却刚结束那一帧调用。</p>
     */
    private void refreshTpButton() {
        if (tpBtn == null) return;
        boolean hasBound = EngineeringBlueprintItem.hasBoundProject(stack);
        boolean canTp    = com.xioyim.spatialblueprint.client.ClientTeleportPermCache.canTeleport();
        boolean cooling  = com.xioyim.spatialblueprint.client.ClientTeleportPermCache.cooldownRemainingMs() > 0L;
        Component tip = !canTp
                ? Component.translatable("spatial_blueprint.gui.engineering.btn.tp.no_op")
                : cooling
                    ? com.xioyim.spatialblueprint.client.ClientTeleportPermCache.cooldownTooltip()
                    : Component.translatable("spatial_blueprint.gui.engineering.btn.tp.tip");
        tpBtn.setTooltip(Tooltip.create(tip));
        tpBtn.active = canTp && hasBound && !cooling;
        tpCooling = cooling;
    }

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isDeadOrDying() || mc.player != player) {
            mc.setScreen(null);
            return;
        }

        // ── [Tp] 冷却倒计时：冷却期间每 tick 刷剩余秒数，结束那一帧再刷一次把按钮点亮 ──
        if (tpCooling || com.xioyim.spatialblueprint.client.ClientTeleportPermCache.cooldownRemainingMs() > 0L) {
            refreshTpButton();
        }

        // ── 材料空间同步的超时兜底 ────────────────────────────────────────
        // C2S_RequestMaterialSpaceMessage 在服务端读不到清单等情况下不会回包，
        // 不兜底的话按钮会永远卡在「同步中」而无法构建。
        if (!spaceSynced && System.currentTimeMillis() - openedAt > 1500L) {
            spaceSynced = true;
            rebuildWidgets();
        }

        // ── FORCE 的补充拦截（只做一次）───────────────────────────────────
        // 右键那一刻数据可能还没到达，saveMode 无从判断，界面照常打开了；数据到位后补判一次。
        // ⚠ 必须去重：tick 每帧都跑，不去重会每帧弹横幅 + 发销毁包，表现为闪屏。
        if (!lateGuardChecked && data != null) {
            lateGuardChecked = true;
            if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                    .intercept(player, stack, data)) return;
            if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard
                    .interceptForeignWorld(player, stack, data)) return;
        }
        // 异步等待蓝图数据（多人模式）
        if (data == null) {
            String id = EngineeringBlueprintItem.getBlueprintId(stack);
            if (id != null && !id.isEmpty()) {
                BlueprintData fetched = ClientBlueprintCache.get(id);
                if (fetched != null) {
                    data = fetched;
                    cachedMatEntries = buildMatEntries();
                    clearWidgets();
                    init();
                    return;
                }
            }
        }

        // ── Bug-1 fix: detect when ItemSyncUtil.syncHand() replaced the held-item reference ─
        String thisBpId = EngineeringBlueprintItem.getBlueprintId(this.stack);
        if (thisBpId != null && !thisBpId.isEmpty()) {
            for (net.minecraft.world.InteractionHand h : net.minecraft.world.InteractionHand.values()) {
                ItemStack live = player.getItemInHand(h);
                if (live != this.stack
                        && thisBpId.equals(EngineeringBlueprintItem.getBlueprintId(live))) {
                    this.stack = live;
                    clearWidgets(); init();
                    return;
                }
            }
        }
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    private void requestBlueprintData(String bpId) {
        ClientBlueprintCache.startDownload(bpId);
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestBlueprintDataMessage(bpId));
        clearWidgets();
        init();
    }

    // ── 逻辑操作 ──────────────────────────────────────────────────────────────

    /**
     * 切换工程蓝图预览 ON/OFF。
     * ON：设 AlignMode = START，写入固定坐标到 {@link BlueprintSelectionRenderer}。
     * OFF：设 AlignMode = OFF，清除工程预览。
     */
    private void togglePreview() {
        AlignMode cur = BlueprintSelectionRenderer.getAlignMode(stack);
        if (cur == AlignMode.OFF) {
            // 开启预览（登记给 ClientEngineeringPreview，脱手后仍显示）
            String bpId     = EngineeringBlueprintItem.getBlueprintId(stack);
            BlockPos startPos = EngineeringBlueprintItem.getBoundStartPos(stack);
            BlockPos endPos   = EngineeringBlueprintItem.getBoundEndPos(stack);
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            String dim = level != null ? level.dimension().location().toString() : "";
            BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.START);
            BlueprintSelectionRenderer.setEngineeringPreview(
                    bpId == null ? "" : bpId, startPos, endPos, dim);
        } else {
            // 关闭预览
            BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.OFF);
            BlueprintSelectionRenderer.clearEngineeringPreview();
        }
        clearWidgets();
        init();
    }

    private void deploy() {
        if (data == null) return;
        BlockPos placementStart = placementStart();
        if (placementStart == null) return;
        if (!player.isCreative() && hasMaterials() && !hasSufficientLocalMaterials()) return;
        if (BlueprintSelectionRenderer.getAlignMode(stack) == AlignMode.OFF) return;
        // 客户端校验未通过时阻止发包（防御性拦截，正常情况按钮已禁用）
        if (currentValidation != null) {
            PlacementPreValidator.Status s = currentValidation.status;
            if (s == PlacementPreValidator.Status.EXTRA_BLOCKS
                    || s == PlacementPreValidator.Status.ENTITY_BLOCKING
                    || s == PlacementPreValidator.Status.OUT_OF_BOUNDS
                    || s == PlacementPreValidator.Status.CHUNK_NOT_LOADED) {
                return;
            }
        }

        // 工程蓝图固定坐标放置：子工程优先使用物品上按激活原点计算的绑定坐标
        BlueprintNetwork.CHANNEL.sendToServer(
                new PlaceBlueprintMessage(placementStart, Rotation.NONE, 0, 0, 0, true,
                        com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL));

        BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.OFF);
        BlueprintSelectionRenderer.clearEngineeringPreview();
        this.onClose();
    }

    /** 独立工程读数据坐标；子工程数据不写死坐标，改读领取物品上的任务集绑定坐标。 */
    @Nullable
    private BlockPos placementStart() {
        if (data != null && data.fixedStartPos != null) return data.fixedStartPos;
        return EngineeringBlueprintItem.hasBoundProject(stack)
                ? EngineeringBlueprintItem.getBoundStartPos(stack) : null;
    }

    private void cancel() {
        String bpId = EngineeringBlueprintItem.getBlueprintId(stack);
        if (bpId != null) ClientBlueprintCache.remove(bpId);
        BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.OFF);
        BlueprintSelectionRenderer.clearEngineeringPreview();
        this.onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (showMaterials && hasMaterials() && matMaxScrollRow > 0
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
        int bv = com.xioyim.spatialblueprint.client.ClientBlueprintCache.version();
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
            // 放置按钮的「材料不足 / 可构建」是在 init() 里算好的，不重建的话
            // 同步到达后按钮会一直停在旧状态。
            rebuildWidgets();
        }
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showMaterials && hasMaterials()) {
            renderMaterialPanel(graphics, mouseX, mouseY);
        }
    }

    private void renderMaterialPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (data == null || data.materialList == null || cachedMatEntries.isEmpty()) return;
        var ml = data.materialList;

        int panelX = this.width / 2 - MAT_PANEL_W / 2;
        int rowY   = this.height - 45;

        List<Map.Entry<String, Integer>> entries = cachedMatEntries;
        int totalRows   = (entries.size() + MAT_COLS - 1) / MAT_COLS;
        matMaxScrollRow = Math.max(0, totalRows - MAT_VISIBLE);
        matScrollRow    = Math.max(0, Math.min(matScrollRow, matMaxScrollRow));

        int contentH = MAT_VISIBLE * MAT_CELL_H;
        int panelH   = MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD + contentH + MAT_PAD;
        int panelY   = Math.max(4, rowY - panelH - 4);

        matPanelX = panelX; matPanelY = panelY; matPanelW = MAT_PANEL_W; matPanelH = panelH;

        // 背景 + 边框
        graphics.fill(panelX, panelY, panelX + MAT_PANEL_W, panelY + panelH, 0xD0101010);
        drawBorder(graphics, panelX, panelY, MAT_PANEL_W, panelH);

        // 标题行
        String title = Component.translatable("spatial_blueprint.gui.placement.material_list").getString();
        String sub   = Component.translatable("spatial_blueprint.gui.placement.material_list_header",
                entries.size(), ml.totalCount()).getString();
        graphics.drawString(font, title, panelX + MAT_PAD, panelY + MAT_PAD, 0xFFFFAA, false);
        boolean titleHovered = mouseX >= panelX + MAT_PAD
                && mouseX < panelX + MAT_PAD + font.width(title)
                && mouseY >= panelY + MAT_PAD
                && mouseY < panelY + MAT_PAD + font.lineHeight;
        graphics.drawString(font, sub,
                panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W - MAT_SB_GAP - font.width(sub),
                panelY + MAT_PAD, 0x888888, false);

        // ✎ 快捷存入 —— 只有需要提交材料时才画（创造模式不消耗材料，OP 也不例外）
        matQuickDepBtnW = 0;
        if (needsMaterialSubmit()) {
            int headerCursor = panelX + MAT_PAD + font.width(title) + 5;
            int depBtnW = font.width("✎") + 4;
            matQuickDepBtnX = headerCursor; matQuickDepBtnY = panelY + MAT_PAD;
            matQuickDepBtnW = depBtnW;      matQuickDepBtnH = font.lineHeight;
            boolean depHov = mouseX >= headerCursor && mouseX < headerCursor + depBtnW
                    && mouseY >= panelY + MAT_PAD && mouseY < panelY + MAT_PAD + font.lineHeight;
            graphics.drawString(font, "✎", headerCursor, panelY + MAT_PAD,
                    depHov ? 0xFFFF55 : 0xAAFF55, false);

            if (depHov) {
                java.util.List<net.minecraft.util.FormattedCharSequence> depTip = java.util.List.of(
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.title")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line1")
                                .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line2")
                                .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line3")
                                .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText(),
                        Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line5")
                                .withStyle(net.minecraft.ChatFormatting.GREEN).getVisualOrderText()
                );
                graphics.renderTooltip(font, depTip, mouseX, mouseY);
            }
        }

        // 分割线
        int sepY = panelY + MAT_PAD + MAT_HEADER_H;
        graphics.fill(panelX + MAT_PAD, sepY,
                panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W - MAT_SB_GAP, sepY + 1, 0xFF444444);

        // 滚动条
        int contentY = sepY + 1 + MAT_SEP_PAD;
        matContentY  = contentY;
        int sbX = panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W;
        graphics.fill(sbX, contentY, sbX + MAT_SB_W, contentY + contentH, 0xFF2A2A2A);
        if (matMaxScrollRow > 0) {
            int thumbH = Math.max(8, contentH * MAT_VISIBLE / totalRows);
            int thumbY = contentY + (contentH - thumbH) * matScrollRow / matMaxScrollRow;
            graphics.fill(sbX, thumbY, sbX + MAT_SB_W, thumbY + thumbH, 0xFF888888);
        } else {
            graphics.fill(sbX, contentY, sbX + MAT_SB_W, contentY + contentH, 0xFF555555);
        }

        // 内容格子。图标按 16×16 原生尺寸渲染，不套 pose scale ——
        // 缩放后的物品模型会失真，与 /sb space 的图标看起来是两种东西。
        graphics.enableScissor(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH);

        ItemStack hoveredStack = ItemStack.EMPTY;
        for (int i = 0; i < entries.size(); i++) {
            int row = i / MAT_COLS, col = i % MAT_COLS;
            int screenX = panelX + MAT_PAD + col * MAT_CELL_W;
            int screenY = contentY + (row - matScrollRow) * MAT_CELL_H;
            if (screenY + MAT_CELL_H <= contentY || screenY >= contentY + contentH) continue;

            var entry = entries.get(i);

            // 构建带 NBT 的显示 ItemStack（含附魔光效和完整 tooltip）
            // 与 /sb space 使用同一套安全渲染：崩溃隔离 + PoseStack 修正 + GL 状态还原
            ItemStack displayStack = data.materialList.getDisplayStack(entry.getKey());
            int iconY = screenY + (MAT_CELL_H - MAT_ICON_SCR) / 2;
            SafeItemRenderer.render(graphics, displayStack, screenX, iconY);

            int depCount  = localDeposited.getOrDefault(entry.getKey(), 0);
            int needCount = entry.getValue();
            String countStr   = depCount + "/" + needCount;
            int    countColor = depCount >= needCount ? 0x55FF55 : 0xFFAA00;

            graphics.pose().pushPose();
            graphics.pose().scale(MAT_SCALE, MAT_SCALE, 1f);
            int tx = Math.round((screenX + MAT_ICON_SCR + 2) / MAT_SCALE);
            int ty = Math.round((screenY + (MAT_CELL_H - font.lineHeight * MAT_SCALE) / 2f) / MAT_SCALE);
            graphics.drawString(font, countStr, tx, ty, countColor, false);
            graphics.pose().popPose();

            if (hoveredStack.isEmpty()
                    && mouseX >= screenX && mouseX < screenX + MAT_ICON_SCR
                    && mouseY >= iconY && mouseY < iconY + MAT_ICON_SCR
                    && screenY >= contentY && screenY < contentY + contentH) {
                hoveredStack = displayStack;
            }
        }

        graphics.disableScissor();

        if (titleHovered) {
            // 蓝图列表同款元数据 tooltip（缺「文件名字」——BlueprintData 不带 blueprintId）
            graphics.renderTooltip(font, BlueprintTooltips.buildDataTip(data), mouseX, mouseY);
        } else if (!hoveredStack.isEmpty()) {
            graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
        }
    }

    // ── 鼠标 & 键盘输入 ───────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        int mx = (int) mouseX, my = (int) mouseY;

        // ✎ 快捷存入
        if (button == 0 && showMaterials && needsMaterialSubmit() && matQuickDepBtnW > 0
                && mx >= matQuickDepBtnX && mx < matQuickDepBtnX + matQuickDepBtnW
                && my >= matQuickDepBtnY && my < matQuickDepBtnY + matQuickDepBtnH) {
            handleQuickDeposit();
            return true;
        }

        // ── 左键材料图标 → JEI 查来源（仅生存模式）─────────────────────────
        if (button == 0 && !player.isCreative() && showMaterials && hasMaterials()
                && matContentY >= 0) {
            ItemStack jeiStack = materialStackAt(mx, my);
            if (!jeiStack.isEmpty()
                    && com.xioyim.spatialblueprint.compat.jei.SBJeiSupport
                            .showRecipe(jeiStack, false)) {
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) { errorToast = null; return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();   // ESC 仅关闭界面，不清除预览（取消按钮才清除）
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 一键存入：创造模式不消耗材料，故不参与。 */
    private void handleQuickDeposit() {
        if (data == null || data.materialList == null || !needsMaterialSubmit()) return;
        for (var entry : data.materialList.blockCounts.entrySet()) {
            String blockId = entry.getKey();
            int already = localDeposited.getOrDefault(blockId, 0);
            int stillNeed = entry.getValue() - already;
            if (stillNeed <= 0) continue;
            int toDeposit = Math.min(stillNeed, countInInventory(player, blockId));
            if (toDeposit > 0) localDeposited.put(blockId, already + toDeposit);
        }
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_QUICK_DEPOSIT, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(), "NONE", 0, 0, 0));
        clearWidgets(); init();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /**
     * 构建"超出预览距离"tooltip：第一行带全局预览距离上限，后两行显示起点/终点坐标及到玩家的实际距离。
     */
    private Component buildTooFarTooltip(int limit) {
        BlockPos sp = EngineeringBlueprintItem.getBoundStartPos(stack);
        BlockPos ep = EngineeringBlueprintItem.getBoundEndPos(stack);
        net.minecraft.world.phys.Vec3 pos = player.position();
        int dStart = (int) Math.round(pos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(sp)));
        int dEnd   = (int) Math.round(pos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(ep)));
        return Component.translatable("spatial_blueprint.gui.engineering.tooltip.too_far", limit)
                .append(Component.literal("\n"))
                .append(Component.translatable(
                        "spatial_blueprint.gui.engineering.tooltip.too_far.start",
                        sp.getX(), sp.getY(), sp.getZ(), dStart)
                        .withStyle(net.minecraft.ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.translatable(
                        "spatial_blueprint.gui.engineering.tooltip.too_far.end",
                        ep.getX(), ep.getY(), ep.getZ(), dEnd)
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    private boolean hasSufficientLocalMaterials() {
        if (data == null || data.materialList == null) return true;
        for (var e : data.materialList.blockCounts.entrySet()) {
            if (localDeposited.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** 用服务端权威的材料空间数据刷新 {@link #localDeposited}。 */
    private void refreshDepositedFromSpace() {
        localDeposited.clear();
        if (data == null || data.materialList == null) return;
        for (String key : data.materialList.blockCounts.keySet()) {
            int c = com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache.count(key);
            if (c > 0) localDeposited.put(key, c);
        }
    }

    /**
     * 用服务端回推的蓝图数据替换本地 {@link #data} 引用并重建材料条目快照。
     * 材料清单的权威数据在服务端 dat 文件，客户端只负责显示。
     */
    private void refreshDataFromCache() {
        String bpId = stack.hasTag() ? stack.getTag().getString("blueprintId") : "";
        if (bpId.isEmpty()) return;
        BlueprintData fresh = com.xioyim.spatialblueprint.client.ClientBlueprintCache.get(bpId);
        if (fresh == null) return;
        boolean wasEmpty = (this.data == null);
        this.data = fresh;
        this.cachedMatEntries = buildMatEntries();
        refreshDepositedFromSpace();
        // 数据异步到达后必须重建控件，否则按「无数据」算好的按钮状态不会更新
        if (wasEmpty) rebuildWidgets();
    }

    /**
     * 统计背包中与该材料复合键<b>完全匹配</b>的物品总数（物品类型 + 规范化 NBT），
     * 与服务端 {@code C2S_MaterialDepositMessage.takeFromInventory} 的判定一致。
     */
    private static int countInInventory(Player player, String materialKey) {
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (materialKey.equals(com.xioyim.spatialblueprint.util.MaterialKey.of(s))) {
                count += s.getCount();
            }
        }
        return count;
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
    public boolean isPauseScreen() { return false; }
}
