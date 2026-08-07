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
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    private static final float MAT_SCALE   = 0.75f;
    private static final int MAT_ICON_SCR  = 12;

    // ── 材料面板状态 ──────────────────────────────────────────────────────────
    private boolean showMaterials  = false;
    private int     matScrollRow   = 0;
    private int     matMaxScrollRow = 0;
    private int     matPanelX, matPanelY, matPanelW, matPanelH;
    private int     matContentY    = -1;

    // 快捷存入/取出按钮边界（W=0 表示未渲染）
    private int matQuickDepBtnX, matQuickDepBtnY, matQuickDepBtnW = 0, matQuickDepBtnH;
    private int matQuickWdBtnX,  matQuickWdBtnY,  matQuickWdBtnW  = 0, matQuickWdBtnH;

    // ── 存取 overlay 状态 ──────────────────────────────────────────────────────
    @Nullable private String      depositBlockId   = null;
    @Nullable private String      depositBlockName = null;
    private boolean               depositMode      = true;
    private final StringBuilder   depositInput     = new StringBuilder();

    // ── 右键菜单状态 ───────────────────────────────────────────────────────────
    @Nullable private String ctxBlockId   = null;
    @Nullable private String ctxBlockName = null;
    private int ctxX, ctxY;

    /** 客户端乐观跟踪的已存入材料。 */
    private final Map<String, Integer> localDeposited    = new LinkedHashMap<>();
    private int                        localWithdrawCount = 0;

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
        this.data   = EngineeringBlueprintItem.getBlueprintData(stack);
        // 初始化存入记录
        this.localDeposited.putAll(EngineeringBlueprintItem.getDepositedMaterials(stack));
        this.localWithdrawCount = EngineeringBlueprintItem.getWithdrawCount(stack);
        this.cachedMatEntries = buildMatEntries();
    }

    /** 是否有可显示的材料清单。 */
    private boolean hasMaterials() {
        return data != null && data.materialList != null && !data.materialList.blockCounts.isEmpty();
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

        int cx   = this.width / 2;
        int botH = 18;
        int botW = 52;
        int pGap = 4;
        int dW   = 18;  // ⌘ 按钮宽度
        int rowY = this.height - 45;

        // 总宽 = 5×botW + dW + 5×pGap = 260 + 18 + 20 = 298  →  offset = 149
        int rx = cx - 149;

        // ── [Tp] 传送到工程蓝图位置（最左侧，仅OP）──────────────────────────
        {
            boolean hasBound = EngineeringBlueprintItem.hasBoundProject(stack);
            boolean isOp     = player.hasPermissions(2);
            Component tpTip  = isOp
                    ? Component.translatable("spatial_blueprint.gui.engineering.btn.tp.tip")
                    : Component.translatable("spatial_blueprint.gui.engineering.btn.tp.no_op");
            Button tpBtn = addRenderableWidget(Button.builder(
                    Component.translatable("spatial_blueprint.gui.engineering.btn.tp"),
                    b -> BlueprintNetwork.CHANNEL.sendToServer(new C2S_EngineeringTeleportMessage()))
                .bounds(rx - pGap - dW, rowY, dW, botH)
                .tooltip(Tooltip.create(tpTip))
                .build());
            tpBtn.active = isOp && hasBound;
        }

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
            } else if (boundsResult == EngineeringBlueprintItem.ProjectBoundsResult.TOO_FAR) {
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.engineering.btn.preview_on"), b -> {})
                    .bounds(rx, rowY, botW, botH)
                    .tooltip(Tooltip.create(buildTooFarTooltip()))
                    .build());
                btn.active = false;
            } else {
                // OK：显示 ON/OFF 切换按钮
                boolean isPreviewOn = BlueprintSelectionRenderer.getAlignMode(stack) != AlignMode.OFF;
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

        // ── 刷新客户端预校验结果（data / fixedStartPos 为 null 时跳过，按钮保持禁用）──
        currentValidation = null;
        if (data != null && BlueprintSelectionRenderer.getAlignMode(stack) != AlignMode.OFF
                && data.fixedStartPos != null && Minecraft.getInstance().level != null) {
            currentValidation = PlacementPreValidator.validate(
                    Minecraft.getInstance().level, player, data.fixedStartPos, data, Rotation.NONE);
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

        // ── [⌘] 工程菜单 ─────────────────────────────────────────────────────
        // 创造模式：始终可点击（菜单用于管理/加载蓝图）
        // 生存模式：禁用并显示提示
        addRenderableWidget(Button.builder(Component.literal("⌘"), b -> {
                    Minecraft.getInstance().setScreen(
                        new BlueprintLibraryScreen(EngineeringBlueprintScreen.this));
                })
            .bounds(rx, rowY, dW, botH)
            .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.engineering.btn.menu")))
            .build());
    }

    private Button buildDeployButton(int x, int y, int w, int h) {
        boolean isOff = BlueprintSelectionRenderer.getAlignMode(stack) == AlignMode.OFF;

        // 材料不足
        if (!player.isCreative() && hasMaterials() && !hasSufficientLocalMaterials()) {
            Button btn = Button.builder(
                    Component.translatable("spatial_blueprint.gui.placement.deploy.no_materials"),
                    b -> {})
                .bounds(x, y, w, h)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.gui.placement.tooltip.deploy.no_materials")))
                .build();
            btn.active = false;
            return btn;
        }

        // 无数据 / 渲染模式关闭 / 无固定坐标 / 无校验结果
        if (data == null || isOff || data.fixedStartPos == null || currentValidation == null) {
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

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isDeadOrDying() || mc.player != player) {
            mc.setScreen(null);
            return;
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
            // 开启预览
            BlockPos startPos = EngineeringBlueprintItem.getBoundStartPos(stack);
            BlockPos endPos   = EngineeringBlueprintItem.getBoundEndPos(stack);
            BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.START);
            BlueprintSelectionRenderer.setEngineeringPreview(startPos, endPos);
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
        if (data.fixedStartPos == null) return;
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

        // 工程蓝图固定坐标放置：直接使用 fixedStartPos 作为原点（始终清空区域）
        BlueprintNetwork.CHANNEL.sendToServer(
                new PlaceBlueprintMessage(data.fixedStartPos, Rotation.NONE, 0, 0, 0, true,
                        com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL));

        BlueprintSelectionRenderer.setAlignMode(stack, AlignMode.OFF);
        BlueprintSelectionRenderer.clearEngineeringPreview();
        this.onClose();
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
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (showMaterials && hasMaterials()) {
            renderMaterialPanel(graphics, mouseX, mouseY);
        }
        // Overlay 优先级：存取 > 右键菜单
        if (depositBlockId != null) {
            renderDepositPanel(graphics, mouseX, mouseY);
        } else if (ctxBlockId != null) {
            renderContextMenu(graphics, mouseX, mouseY);
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
        graphics.drawString(font, sub,
                panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W - MAT_SB_GAP - font.width(sub),
                panelY + MAT_PAD, 0x888888, false);

        // ✎ 快捷存入 & ♻ 快捷取出
        int headerCursor = panelX + MAT_PAD + font.width(title) + 5;
        {
            boolean wdAtLimit = !player.isCreative()
                    && localWithdrawCount >= getEffectiveWithdrawLimit();

            int depBtnW = font.width("✎") + 4;
            matQuickDepBtnX = headerCursor; matQuickDepBtnY = panelY + MAT_PAD;
            matQuickDepBtnW = depBtnW;      matQuickDepBtnH = font.lineHeight;
            boolean depHov = mouseX >= headerCursor && mouseX < headerCursor + depBtnW
                    && mouseY >= panelY + MAT_PAD && mouseY < panelY + MAT_PAD + font.lineHeight;
            graphics.drawString(font, "✎", headerCursor, panelY + MAT_PAD,
                    depHov ? 0xFFFF55 : 0xAAFF55, false);
            headerCursor += depBtnW + 3;

            int wdBtnW = font.width("♻") + 4;
            matQuickWdBtnX = headerCursor; matQuickWdBtnY = panelY + MAT_PAD;
            matQuickWdBtnW = wdBtnW;       matQuickWdBtnH = font.lineHeight;
            boolean wdHov = mouseX >= headerCursor && mouseX < headerCursor + wdBtnW
                    && mouseY >= panelY + MAT_PAD && mouseY < panelY + MAT_PAD + font.lineHeight;
            int wdColor = wdAtLimit ? (wdHov ? 0xFF8844 : 0xAA3333)
                                    : (wdHov ? 0xFFFF55 : 0x55AAFF);
            graphics.drawString(font, "♻", headerCursor, panelY + MAT_PAD, wdColor, false);

            // ── Tooltips for ✎ / ♻ ──────────────────────────────────────
            if (depositBlockId == null && ctxBlockId == null) {
                if (depHov) {
                    java.util.List<net.minecraft.util.FormattedCharSequence> depTip = java.util.List.of(
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.title")
                                    .withStyle(net.minecraft.ChatFormatting.YELLOW).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line1")
                                    .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line2")
                                    .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText(),
                            Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_deposit.line3")
                                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY).getVisualOrderText()
                    );
                    graphics.renderTooltip(font, depTip, mouseX, mouseY);
                } else if (wdHov) {
                    java.util.List<net.minecraft.util.FormattedCharSequence> wdTip = new java.util.ArrayList<>();
                    wdTip.add(Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.title")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW).getVisualOrderText());
                    wdTip.add(Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line1")
                            .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
                    wdTip.add(Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line2")
                            .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
                    if (!player.isCreative()) {
                        int xpCost = computeWithdrawXpCost();
                        wdTip.add(xpCost > 0
                                ? Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line3_xp", xpCost)
                                        .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText()
                                : Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line3_free")
                                        .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
                        int limit     = getEffectiveWithdrawLimit();
                        int remaining = Math.max(0, limit - localWithdrawCount);
                        wdTip.add((limit == 0)
                                ? Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line4_disabled")
                                        .withStyle(net.minecraft.ChatFormatting.RED).getVisualOrderText()
                                : Component.translatable("spatial_blueprint.gui.placement.tooltip.quick_withdraw.line4_remaining",
                                        remaining, limit)
                                        .withStyle(remaining == 0
                                                ? net.minecraft.ChatFormatting.RED
                                                : net.minecraft.ChatFormatting.YELLOW)
                                        .getVisualOrderText());
                    }
                    graphics.renderTooltip(font, wdTip, mouseX, mouseY);
                }
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

        // 内容格子
        graphics.enableScissor(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH);
        graphics.pose().pushPose();
        graphics.pose().scale(MAT_SCALE, MAT_SCALE, 1f);

        String hoveredName = null;
        for (int i = 0; i < entries.size(); i++) {
            int row = i / MAT_COLS, col = i % MAT_COLS;
            int screenX = panelX + MAT_PAD + col * MAT_CELL_W;
            int screenY = contentY + (row - matScrollRow) * MAT_CELL_H;
            if (screenY + MAT_CELL_H <= contentY || screenY >= contentY + contentH) continue;

            int ux = Math.round(screenX / MAT_SCALE);
            int uy = Math.round(screenY / MAT_SCALE);

            var entry = entries.get(i);
            ResourceLocation rl = ResourceLocation.tryParse(entry.getKey());
            if (rl == null) continue;

            var blockOpt = BuiltInRegistries.BLOCK.getOptional(rl);
            var itemOpt  = BuiltInRegistries.ITEM.getOptional(rl);

            String entryName;
            if (blockOpt.isPresent())       entryName = blockOpt.get().getName().getString();
            else if (itemOpt.isPresent())   entryName = new ItemStack(itemOpt.get()).getHoverName().getString();
            else                            entryName = entry.getKey();

            net.minecraft.world.item.Item displayItem = Items.AIR;
            if (blockOpt.isPresent())       displayItem = blockOpt.get().asItem();
            else if (itemOpt.isPresent())   displayItem = itemOpt.get();
            if (displayItem != Items.AIR)   graphics.renderItem(new ItemStack(displayItem), ux, uy);

            int depCount  = localDeposited.getOrDefault(entry.getKey(), 0);
            int needCount = entry.getValue();
            String countStr   = depCount + "/" + needCount;
            int    countColor = depCount >= needCount ? 0x55FF55 : 0xFFAA00;
            graphics.drawString(font, countStr, ux + 17,
                    uy + (16 - font.lineHeight) / 2 + 1, countColor, false);

            if (hoveredName == null
                    && mouseX >= screenX && mouseX < screenX + MAT_ICON_SCR
                    && mouseY >= screenY && mouseY < screenY + MAT_ICON_SCR
                    && screenY >= contentY && screenY < contentY + contentH) {
                hoveredName = entryName;
            }
        }

        graphics.pose().popPose();
        graphics.disableScissor();

        if (ctxBlockId == null && depositBlockId == null && hoveredName != null) {
            graphics.renderTooltip(font, Component.literal(hoveredName), mouseX, mouseY);
        }
    }

    private void renderContextMenu(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = {
            Component.translatable("spatial_blueprint.gui.placement.context.deposit").getString(),
            Component.translatable("spatial_blueprint.gui.placement.context.withdraw").getString()
        };
        int[] colors = { 0x55FF55, 0xFFAA00 };
        final int CMW = 100, CM_ROW_H = 16, CM_PAD = 4, CM_ROWS = labels.length;
        final int CMH = CM_PAD + CM_ROWS * CM_ROW_H + CM_PAD;
        int cx2 = Math.min(ctxX, this.width - CMW - 2);
        int cy2 = Math.min(ctxY, this.height - CMH - 2);

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(cx2, cy2, cx2 + CMW, cy2 + CMH, 0xF0101010);
        drawBorder(g, cx2, cy2, CMW, CMH);
        for (int i = 0; i < CM_ROWS; i++) {
            int ry = cy2 + CM_PAD + i * CM_ROW_H;
            if (mouseX >= cx2 && mouseX < cx2 + CMW && mouseY >= ry && mouseY < ry + CM_ROW_H)
                g.fill(cx2 + 1, ry, cx2 + CMW - 1, ry + CM_ROW_H, 0x40FFFFFF);
            g.drawString(font, labels[i], cx2 + CM_PAD + 2,
                    ry + (CM_ROW_H - font.lineHeight) / 2, colors[i], false);
        }
        g.pose().popPose();
    }

    private void renderDepositPanel(GuiGraphics g, int mouseX, int mouseY) {
        final int EW = 200, EH = 90, BW = 60, BH = 16, BP = 8, PAD = 8;
        int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(ex, ey, ex + EW, ey + EH, 0xF0101010);
        drawBorder(g, ex, ey, EW, EH);

        String depTitleKey = depositMode
                ? "spatial_blueprint.gui.placement.deposit_dialog.title.deposit"
                : "spatial_blueprint.gui.placement.deposit_dialog.title.withdraw";
        String name = depositBlockName != null ? depositBlockName : depositBlockId;
        g.drawString(font,
                String.format(Component.translatable(depTitleKey).getString(), name),
                ex + PAD, ey + PAD, 0xFFFFAA, false);

        int fx = ex + PAD, fy = ey + PAD + font.lineHeight + 4, fw = EW - PAD * 2, fh = 16;
        g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF444444);
        g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A1A1A);
        String cursor = (System.currentTimeMillis() % 1000 < 500) ? "|" : "";
        g.drawString(font, depositInput.toString() + cursor,
                fx + 4, fy + (fh - font.lineHeight) / 2, 0xFFFFFF, false);

        int okX = ex + EW / 2 - BW - 4, cancelX = ex + EW / 2 + 4, by = ey + EH - BH - BP;
        boolean okH = mouseX >= okX && mouseX < okX + BW && mouseY >= by && mouseY < by + BH;
        boolean cnH = mouseX >= cancelX && mouseX < cancelX + BW && mouseY >= by && mouseY < by + BH;
        String ok  = Component.translatable("spatial_blueprint.gui.common.confirm").getString();
        String cnl = Component.translatable("spatial_blueprint.gui.common.cancel").getString();
        g.fill(okX, by, okX + BW, by + BH, okH ? 0xFF2A5530 : 0xFF1A351F);
        drawBorder(g, okX, by, BW, BH);
        g.drawString(font, ok,  okX     + (BW - font.width(ok))  / 2, by + (BH - font.lineHeight) / 2, 0x55FF55, false);
        g.fill(cancelX, by, cancelX + BW, by + BH, cnH ? 0xFF553030 : 0xFF351A1A);
        drawBorder(g, cancelX, by, BW, BH);
        g.drawString(font, cnl, cancelX + (BW - font.width(cnl)) / 2, by + (BH - font.lineHeight) / 2, 0xFF5555, false);
        g.pose().popPose();
    }

    // ── 鼠标 & 键盘输入 ───────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        int mx = (int) mouseX, my = (int) mouseY;

        // 存取 overlay 拦截
        if (depositBlockId != null) {
            final int EW = 200, EH = 90, BW = 60, BH = 16, BP = 8;
            int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;
            int okX = ex + EW / 2 - BW - 4, cancelX = ex + EW / 2 + 4, by = ey + EH - BH - BP;
            if (my >= by && my < by + BH) {
                if (mx >= okX && mx < okX + BW)   { confirmDeposit(); return true; }
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

        // 右键菜单拦截
        if (ctxBlockId != null) {
            final int CMW = 100, CM_ROW_H = 16, CM_PAD = 4, CM_ROWS = 2;
            final int CMH = CM_PAD + CM_ROWS * CM_ROW_H + CM_PAD;
            int cx2 = Math.min(ctxX, this.width - CMW - 2);
            int cy2 = Math.min(ctxY, this.height - CMH - 2);
            if (mx >= cx2 && mx < cx2 + CMW && my >= cy2 && my < cy2 + CMH) {
                int row = (my - cy2 - CM_PAD) / CM_ROW_H;
                if (row == 0) openDepositDialog(ctxBlockId, ctxBlockName, true);
                if (row == 1) openDepositDialog(ctxBlockId, ctxBlockName, false);
            }
            ctxBlockId = null;
            return true;
        }

        // ✎ 快捷存入
        if (button == 0 && showMaterials && matQuickDepBtnW > 0
                && mx >= matQuickDepBtnX && mx < matQuickDepBtnX + matQuickDepBtnW
                && my >= matQuickDepBtnY && my < matQuickDepBtnY + matQuickDepBtnH) {
            handleQuickDeposit();
            return true;
        }

        // ♻ 快捷取出
        if (button == 0 && showMaterials && matQuickWdBtnW > 0
                && mx >= matQuickWdBtnX && mx < matQuickWdBtnX + matQuickWdBtnW
                && my >= matQuickWdBtnY && my < matQuickWdBtnY + matQuickWdBtnH) {
            if (!player.isCreative() && localWithdrawCount >= getEffectiveWithdrawLimit()) {
                NotifySound.clientError();
                errorToast = Component.translatable(
                        "spatial_blueprint.error.withdraw_limit_reached");
                return true;
            }
            handleQuickWithdraw();
            return true;
        }

        // 右键材料图标 → 菜单
        if (button == 1 && showMaterials && hasMaterials()
                && data != null && data.materialList != null && matContentY >= 0) {
            int panelX = this.width / 2 - MAT_PANEL_W / 2;
            var entries = new ArrayList<>(data.materialList.blockCounts.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                int row = i / MAT_COLS, col = i % MAT_COLS;
                int ex = panelX + MAT_PAD + col * MAT_CELL_W;
                int ey = matContentY + (row - matScrollRow) * MAT_CELL_H;
                if (ey < matContentY || ey + MAT_ICON_SCR > matContentY + MAT_VISIBLE * MAT_CELL_H) continue;
                if (mx >= ex && mx < ex + MAT_ICON_SCR && my >= ey && my < ey + MAT_ICON_SCR) {
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (ctxBlockId != null) { ctxBlockId = null; return true; }
            onClose();   // ESC 仅关闭界面，不清除预览（取消按钮才清除）
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (depositBlockId != null) {
            if (Character.isDigit(c) && depositInput.length() < 9) depositInput.append(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    // ── 存入/取出操作 ─────────────────────────────────────────────────────────

    private void openDepositDialog(String blockId, String blockName, boolean isDeposit) {
        depositBlockId   = blockId;
        depositBlockName = blockName;
        depositMode      = isDeposit;
        depositInput.setLength(0);
        depositInput.append("1");
    }

    private void handleQuickDeposit() {
        if (data == null || data.materialList == null) return;
        for (var entry : data.materialList.blockCounts.entrySet()) {
            String blockId = entry.getKey();
            int needed  = entry.getValue();
            int already = localDeposited.getOrDefault(blockId, 0);
            int stillNeed = needed - already;
            if (stillNeed <= 0) continue;
            if (player.isCreative()) {
                localDeposited.put(blockId, needed);
            } else {
                var item = findDisplayItem(blockId);
                if (item == null) continue;
                int toDeposit = Math.min(stillNeed, countInInventory(player, item));
                if (toDeposit > 0) localDeposited.put(blockId, already + toDeposit);
            }
        }
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_QUICK_DEPOSIT, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(), "NONE", 0, 0, 0));
        clearWidgets(); init();
    }

    private void handleQuickWithdraw() {
        boolean hasAny = localDeposited.values().stream().anyMatch(v -> v > 0);
        if (!hasAny) return;
        if (!player.isCreative()) {
            int xpCost = computeWithdrawXpCost();
            if (player.experienceLevel < xpCost) {
                NotifySound.clientError();
                errorToast = Component.translatable(
                        "spatial_blueprint.error.withdraw_xp_insufficient");
                return;
            }
        }
        if (!player.isCreative()) localWithdrawCount++;
        localDeposited.clear();
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                C2S_MaterialDepositMessage.OP_QUICK_WITHDRAW, "", 0,
                BlueprintSelectionRenderer.getAlignMode(this.stack).name(), "NONE", 0, 0, 0));
        clearWidgets(); init();
    }

    private void confirmDeposit() {
        if (depositBlockId == null) { depositInput.setLength(0); return; }
        try {
            int amount = Integer.parseInt(depositInput.toString());
            if (amount >= 1) {
                if (depositMode) {
                    int needed    = (data != null && data.materialList != null)
                            ? data.materialList.blockCounts.getOrDefault(depositBlockId, 0) : 0;
                    int already   = localDeposited.getOrDefault(depositBlockId, 0);
                    int canAccept = needed - already;
                    int toDeposit = Math.min(amount, canAccept);
                    if (toDeposit > 0) {
                        if (!player.isCreative()) {
                            var item = findDisplayItem(depositBlockId);
                            if (item != null) toDeposit = Math.min(toDeposit, countInInventory(player, item));
                        }
                        if (toDeposit > 0) {
                            localDeposited.put(depositBlockId, already + toDeposit);
                            BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                                    C2S_MaterialDepositMessage.OP_DEPOSIT, depositBlockId, toDeposit,
                                    BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                                    "NONE", 0, 0, 0));
                        }
                    }
                } else {
                    int already    = localDeposited.getOrDefault(depositBlockId, 0);
                    int toWithdraw = Math.min(amount, already);
                    if (toWithdraw > 0) {
                        localDeposited.put(depositBlockId, already - toWithdraw);
                        BlueprintNetwork.CHANNEL.sendToServer(new C2S_MaterialDepositMessage(
                                C2S_MaterialDepositMessage.OP_WITHDRAW, depositBlockId, toWithdraw,
                                BlueprintSelectionRenderer.getAlignMode(this.stack).name(),
                                "NONE", 0, 0, 0));
                    }
                }
            }
        } catch (NumberFormatException ignored) {}
        depositBlockId = null; depositBlockName = null; depositInput.setLength(0);
        clearWidgets(); init();
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    /**
     * 构建"距离过远"tooltip：第一行错误文本，后两行显示起点/终点坐标及到玩家的实际距离。
     */
    private Component buildTooFarTooltip() {
        BlockPos sp = EngineeringBlueprintItem.getBoundStartPos(stack);
        BlockPos ep = EngineeringBlueprintItem.getBoundEndPos(stack);
        net.minecraft.world.phys.Vec3 pos = player.position();
        int dStart = (int) Math.round(pos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(sp)));
        int dEnd   = (int) Math.round(pos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(ep)));
        return Component.translatable("spatial_blueprint.error.engineering.too_far")
                .append(Component.literal("\n"))
                .append(Component.translatable(
                        "spatial_blueprint.error.engineering.too_far.start",
                        sp.getX(), sp.getY(), sp.getZ(), dStart)
                        .withStyle(net.minecraft.ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.translatable(
                        "spatial_blueprint.error.engineering.too_far.end",
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

    private int computeWithdrawXpCost() {
        int custom = EngineeringBlueprintItem.getCustomWithdrawXp(stack);
        if (custom >= 0) return custom;
        long types = localDeposited.values().stream().filter(v -> v > 0).count();
        if (types == 0) return 0;
        int ratio = BlueprintConfig.WITHDRAW_XP_RATIO.get();
        if (ratio == 0) return 0;
        int cost = Math.max(1, ((int) types + ratio - 1) / ratio);
        return Math.min(cost, BlueprintConfig.MAX_WITHDRAW_XP_COST.get());
    }

    private int getEffectiveWithdrawLimit() {
        int custom = EngineeringBlueprintItem.getCustomWithdrawLimit(stack);
        return custom >= 0 ? custom : BlueprintConfig.MAX_WITHDRAW_COUNT.get();
    }

    @Nullable
    private static net.minecraft.world.item.Item findDisplayItem(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return null;
        var blockOpt = BuiltInRegistries.BLOCK.getOptional(rl);
        if (blockOpt.isPresent()) {
            net.minecraft.world.item.Item bi = blockOpt.get().asItem();
            if (bi != Items.AIR) return bi;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    private static int countInInventory(Player player, net.minecraft.world.item.Item item) {
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) count += s.getCount();
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
