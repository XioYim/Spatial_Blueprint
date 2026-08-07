package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ClientLibraryCache;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage;
import com.xioyim.spatialblueprint.network.C2S_ApplyBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_DeleteBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_RenameBlueprintMessage;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 统一蓝图库界面，含两排 Tab（共 6 个）。
 *
 * <p>Row 1：空间蓝图 / 投影蓝图 / 次元蓝图<br>
 * Row 2：工程蓝图 / 投影源文件 / 投影模板</p>
 *
 * <p>默认不选中任何 Tab，内容区显示"先选择一个选项"。
 * 所有写操作均通过 C2S 包路由至服务端。</p>
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintLibraryScreen extends Screen implements ErrorToastSupport {

    // ── 布局常量 ──────────────────────────────────────────────────────────
    private static final int PANEL_W   = 364;
    // PANEL_H 是动态的，见 getPanelH()
    private static final int ROW_H     = 18;
    private static final int LIST_ROWS = 9;
    private static final int PAD       = 4;

    private static final int MB_W   = 16;
    private static final int MB_H   = 12;
    private static final int MB_GAP = 2;

    // Row 1（创造专属）：空间蓝图 / 工程蓝图 / 项目蓝图
    private static final String[] ROW1_IDS  = { "create", "engineering", "projects" };
    private static final String[] ROW1_KEYS = {
        "spatial_blueprint.gui.library.tab.create",
        "spatial_blueprint.gui.library.tab.engineering",
        "spatial_blueprint.gui.library.tab.projects"
    };
    // Row 2（所有玩家）：投影蓝图 / 次元蓝图 / 投影模板
    private static final String[] ROW2_IDS  = { "project", "dimensional", "template" };
    private static final String[] ROW2_KEYS = {
        "spatial_blueprint.gui.library.tab.project",
        "spatial_blueprint.gui.library.tab.dimensional",
        "spatial_blueprint.gui.proj_template.tab.template"
    };

    // ── 状态 ──────────────────────────────────────────────────────────────
    private final Screen parent;
    /** null = 未选中任何 Tab，显示"先选择一个选项"。 */
    @Nullable private String activeTab = null;

    private List<BlueprintStorage.BlueprintSummary> summaries = new ArrayList<>();
    private List<BlueprintStorage.BlueprintSummary> filtered  = new ArrayList<>();

    private int    scrollRow    = 0;
    private int    maxScrollRow = 0;
    private String searchQuery  = "";

    /**
     * false = 私人模式（仅自己的蓝图，默认）；true = 服务器模式（全部蓝图，仅 OP 可切换）。
     * 非 static，每次打开界面重置为 false。
     */
    private boolean serverMode = false;
    private Component displayTitle = Component.translatable("spatial_blueprint.gui.library.title.private");

    // Title segment pixel bounds (set each frame in render, used for hover/click)
    private int titlePrefixX, titlePrefixW, titleMainX, titleMainW, titleY;

    @Nullable private EditBox searchBox = null;
    private int panelX, panelY;

    // ── 弹窗状态 ──────────────────────────────────────────────────────────
    @Nullable private BlueprintStorage.BlueprintSummary confirmDelete        = null;
    @Nullable private BlueprintStorage.BlueprintSummary renamingTarget       = null;
    @Nullable private EditBox                           renameBox            = null;
    @Nullable private BlueprintStorage.BlueprintSummary editDurationTarget   = null;
    @Nullable private EditBox                           editDurationBox      = null;
    @Nullable private BlueprintStorage.BlueprintSummary editWithdrawXpTarget = null;
    @Nullable private EditBox                           editWithdrawXpBox    = null;
    @Nullable private BlueprintStorage.BlueprintSummary editWithdrawLimitTarget = null;
    @Nullable private EditBox                           editWithdrawLimitBox    = null;

    // ── 选中状态 ──────────────────────────────────────────────────────────
    @Nullable private BlueprintStorage.BlueprintSummary selectedSummary = null;

    // ── 操作按钮引用（用于动态 tooltip）────────────────────────────────────
    @Nullable private Button actionXpButton    = null;
    @Nullable private Button actionLimitButton = null;
    @Nullable private Button btnCopyRef        = null;  // ♦ OP 专属，手动渲染 tooltip
    @Nullable private Button btnDurRef         = null;  // ✂ OP 专属，手动渲染 tooltip

    @Nullable private Component errorToast;

    // ── 悬浮状态（每帧更新）────────────────────────────────────────────────
    private int     hoveredNameRow  = -1;
    private int     hoveredStatsRow = -1;
    private boolean hoverConfirm        = false;
    private boolean hoverCancel         = false;
    private boolean hoverRenameConf     = false;
    private boolean hoverRenameCancel   = false;
    private boolean hoverDurationConf   = false;
    private boolean hoverDurationCancel = false;

    // ── 构造 ──────────────────────────────────────────────────────────────

    public BlueprintLibraryScreen(Screen parent) {
        super(Component.translatable("spatial_blueprint.gui.library.title.private"));
        this.parent = parent;
    }

    // ── Screen 生命周期 ───────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();

        List<BlueprintStorage.BlueprintSummary> cached = ClientLibraryCache.get();
        summaries = cached != null ? cached : new ArrayList<>();
        if (cached == null) ClientLibraryCache.requestIfNeeded();
        rebuildFiltered();

        int cx = this.width  / 2;
        int cy = this.height / 2;
        panelX = cx - PANEL_W / 2;
        panelY = cy - getPanelH() / 2;

        // 生存模式下，若已选中 Row1 专属 Tab（create/engineering/projects），重置
        if (!isCreative() && activeTab != null) {
            for (String id : ROW1_IDS) {
                if (id.equals(activeTab)) { activeTab = null; break; }
            }
        }

        // ── Row 1 tabs（仅创造模式显示）──────────────────────────────────
        if (isCreative()) {
            int n1 = ROW1_IDS.length;
            int tabW1 = (PANEL_W - 4 * 2 - 2 * (n1 - 1)) / n1;
            String[] row1Tips = {
                "spatial_blueprint.gui.library.tab.create.tip",
                "spatial_blueprint.gui.library.tab.engineering.tip",
                "spatial_blueprint.gui.library.tab.projects.tip"
            };
            for (int i = 0; i < n1; i++) {
                final String tab = ROW1_IDS[i];
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable(ROW1_KEYS[i]),
                        b -> { switchTab(tab); })
                    .bounds(panelX + 4 + i * (tabW1 + 2), panelY + 18, tabW1, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(row1Tips[i])))
                    .build());
                btn.active = !tab.equals(activeTab);
            }
        }

        // ── Row 2 tabs（所有玩家）────────────────────────────────────────
        {
            int n2    = ROW2_IDS.length;
            int tabW2 = (PANEL_W - 4 * 2 - 2 * (n2 - 1)) / n2;
            int row2Y = panelY + 18 + (isCreative() ? 18 : 0);
            String[] row2Tips = {
                "spatial_blueprint.gui.library.tab.project.tip",
                "spatial_blueprint.gui.library.tab.dimensional.tip",
                "spatial_blueprint.gui.library.tab.template.tip"
            };
            for (int i = 0; i < n2; i++) {
                final String tab = ROW2_IDS[i];
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable(ROW2_KEYS[i]),
                        b -> { switchTab(tab); })
                    .bounds(panelX + 4 + i * (tabW2 + 2), row2Y, tabW2, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(row2Tips[i])))
                    .build());
                btn.active = !tab.equals(activeTab);
            }
        }

        // ── 底部按钮行 ────────────────────────────────────────────────────
        // 布局（右→左）：[关闭] [刷新] [上传]
        int botBtnH   = 18;
        int botBtnY   = panelY + getPanelH() - 22;
        int rightEdge = panelX + PANEL_W - PAD;

        int closeW   = font.width(Component.translatable("spatial_blueprint.gui.library.close")) + 8;
        int refreshW = font.width(Component.translatable("spatial_blueprint.gui.proj_template.btn.refresh")) + 8;
        int uploadW  = font.width(Component.translatable("spatial_blueprint.gui.upload.btn.open")) + 8;

        int closeX   = rightEdge - closeW;
        int refreshX = closeX - 3 - refreshW;
        int uploadX  = refreshX - 3 - uploadW;

        // [关闭]
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.library.close"),
                b -> onClose())
            .bounds(closeX, botBtnY, closeW, botBtnH)
            .build());

        // [刷新]
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.proj_template.btn.refresh"),
                b -> {
                    ClientLibraryCache.invalidate();
                    BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestLibraryMessage());
                    scrollRow = 0;
                    clearWidgets(); init();
                })
            .bounds(refreshX, botBtnY, refreshW, botBtnH)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.library.btn.refresh_tip")))
            .build());

        // [上传]（全玩家可见）
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.upload.btn.open"),
                b -> Minecraft.getInstance().setScreen(new UploadBlueprintScreen(BlueprintLibraryScreen.this)))
            .bounds(uploadX, botBtnY, uploadW, botBtnH)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.upload.btn.open.tooltip")))
            .build());

        // ── 搜索框（仅在 Tab 已选中时显示）──────────────────────────────
        if (activeTab != null) {
            searchBox = new EditBox(font,
                    panelX + PAD + 1, getSearchBoxY(),
                    PANEL_W - PAD * 2 - 2, 14,
                    Component.empty());
            searchBox.setMaxLength(64);
            searchBox.setHint(Component.translatable("spatial_blueprint.gui.library.search_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            searchBox.setValue(searchQuery);
            searchBox.setResponder(text -> {
                if (!text.equals(searchQuery)) {
                    searchQuery = text;
                    scrollRow = 0;
                    rebuildFiltered();
                }
            });
            addRenderableWidget(searchBox);
        }

        // ── 操作按钮行（左对齐，按 tab 可见，选中时激活）─────────────────────
        actionXpButton    = null;
        actionLimitButton = null;
        if (activeTab != null) {
            boolean hasSelection  = selectedSummary != null;
            boolean opUser        = isOp();
            boolean creativeLocal = isCreative();
            int btnSz = 18;
            int abY   = panelY + getPanelH() - 22;
            int abX   = panelX + PAD;
            int abGap = 2;

            // ✎ 重命名
            Button btnRename = addRenderableWidget(Button.builder(
                    Component.literal("✎"), b -> {
                        if (selectedSummary == null) return;
                        renamingTarget = selectedSummary;
                        selectedSummary = null;
                        clearWidgets(); init();
                    })
                .bounds(abX, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.library.btn.rename_tip")))
                .build());
            btnRename.active = hasSelection;
            abX += btnSz + abGap;

            // ♦ 复制文件名（OP 专属，手动渲染 tooltip）
            btnCopyRef = null;
            if (opUser) {
                btnCopyRef = addRenderableWidget(Button.builder(
                        Component.literal("♦"), b -> {
                            if (selectedSummary == null) return;
                            Minecraft.getInstance().keyboardHandler
                                    .setClipboard(getSummaryFilename(selectedSummary));
                            NotifySound.client();
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .build());
                btnCopyRef.active = hasSelection;
                abX += btnSz + abGap;
            }

            // ✂ 改时长（projects tab 无此按钮，仅 OP，手动渲染 tooltip）
            btnDurRef = null;
            if (!"projects".equals(activeTab)) {
                btnDurRef = addRenderableWidget(Button.builder(
                        Component.literal("✂"), b -> {
                            if (selectedSummary == null || !isOp()) return;
                            editDurationTarget = selectedSummary;
                            selectedSummary = null;
                            clearWidgets(); init();
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .build());
                btnDurRef.active = hasSelection && opUser;
                abX += btnSz + abGap;
            }

            // ✔ 应用
            Button btnApply = addRenderableWidget(Button.builder(
                    Component.literal("✔"), b -> {
                        if (selectedSummary == null) return;
                        BlueprintNetwork.CHANNEL.sendToServer(new C2S_ApplyBlueprintMessage(selectedSummary.id()));
                        Minecraft.getInstance().setScreen(null);
                    })
                .bounds(abX, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.library.btn.apply_tip")))
                .build());
            btnApply.active = hasSelection;
            abX += btnSz + abGap;

            // ✖ 删除
            Button btnDelete = addRenderableWidget(Button.builder(
                    Component.literal("✖"), b -> {
                        if (selectedSummary == null) return;
                        confirmDelete = selectedSummary;
                    })
                .bounds(abX, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.library.btn.delete_tip")))
                .build());
            btnDelete.active = hasSelection;
            abX += btnSz + abGap;

            // ✦ 改取出经验 / ☀ 改取出次数（project, dimensional, template，仅 OP）
            boolean showXpLimBtn = "project".equals(activeTab) || "dimensional".equals(activeTab)
                    || "template".equals(activeTab);
            if (showXpLimBtn) {
                actionXpButton = addRenderableWidget(Button.builder(
                        Component.literal("✦"), b -> {
                            if (selectedSummary == null || !isOp()) return;
                            editWithdrawXpTarget = selectedSummary;
                            selectedSummary = null;
                            clearWidgets(); init();
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .build()); // 动态 tooltip 在 renderTooltips() 渲染
                actionXpButton.active = hasSelection && opUser;
                abX += btnSz + abGap;

                actionLimitButton = addRenderableWidget(Button.builder(
                        Component.literal("☀"), b -> {
                            if (selectedSummary == null || !isOp()) return;
                            editWithdrawLimitTarget = selectedSummary;
                            selectedSummary = null;
                            clearWidgets(); init();
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .build()); // 动态 tooltip 在 renderTooltips() 渲染
                actionLimitButton.active = hasSelection && opUser;
            }
        }

        // ── 弹窗 EditBox ──────────────────────────────────────────────────
        int bw = 190, bx = this.width / 2 - 95, by = this.height / 2 - 18;
        if (renamingTarget != null) {
            renameBox = new EditBox(font, bx, by, bw, 20, Component.empty());
            renameBox.setValue(renamingTarget.name());
            renameBox.setMaxLength(64);
            addRenderableWidget(renameBox);
            setFocused(renameBox);
        }
        if (editDurationTarget != null) {
            editDurationBox = new EditBox(font, bx, by, bw, 20, Component.empty());
            int cur = editDurationTarget.animDuration();
            editDurationBox.setValue(cur > 0 ? String.valueOf(cur) : "");
            editDurationBox.setMaxLength(4);
            editDurationBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            addRenderableWidget(editDurationBox);
            setFocused(editDurationBox);
        }
        if (editWithdrawXpTarget != null) {
            editWithdrawXpBox = new EditBox(font, bx, by, bw, 20, Component.empty());
            int cur = editWithdrawXpTarget.customWithdrawXp();
            editWithdrawXpBox.setValue(cur >= 0 ? String.valueOf(cur) : "");
            editWithdrawXpBox.setMaxLength(6);
            editWithdrawXpBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            addRenderableWidget(editWithdrawXpBox);
            setFocused(editWithdrawXpBox);
        }
        if (editWithdrawLimitTarget != null) {
            editWithdrawLimitBox = new EditBox(font, bx, by, bw, 20, Component.empty());
            int cur = editWithdrawLimitTarget.customWithdrawLimit();
            editWithdrawLimitBox.setValue(cur >= 0 ? String.valueOf(cur) : "");
            editWithdrawLimitBox.setMaxLength(6);
            editWithdrawLimitBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            addRenderableWidget(editWithdrawLimitBox);
            setFocused(editWithdrawLimitBox);
        }
    }

    private void switchTab(String tab) {
        activeTab = tab;
        scrollRow = 0;
        searchQuery = "";
        confirmDelete = null;
        selectedSummary = null;
        clearWidgets();
        init();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        List<BlueprintStorage.BlueprintSummary> fresh = ClientLibraryCache.get();
        if (fresh != null && fresh != summaries) {
            summaries = fresh;
            rebuildFiltered();
        }
    }

    // ── 键盘 ──────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }

        if (renamingTarget != null) {
            if (keyCode == 257 || keyCode == 335) { confirmRename(); return true; }
            if (keyCode == 256) { cancelRename(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (editDurationTarget != null) {
            if (keyCode == 257 || keyCode == 335) { confirmEditDuration(); return true; }
            if (keyCode == 256) { cancelEditDuration(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (editWithdrawXpTarget != null) {
            if (keyCode == 257 || keyCode == 335) { confirmEditWithdrawXp(); return true; }
            if (keyCode == 256) { cancelEditWithdrawXp(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (editWithdrawLimitTarget != null) {
            if (keyCode == 257 || keyCode == 335) { confirmEditWithdrawLimit(); return true; }
            if (keyCode == 256) { cancelEditWithdrawLimit(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    // ── 鼠标 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isOverlayActive()) return true;
        if (activeTab == null) return true;
        int listTop    = getListTop();
        int listBottom = getListBottom();
        if (mouseX >= panelX && mouseX < panelX + PANEL_W
                && mouseY >= listTop && mouseY < listBottom) {
            scrollRow = clamp(scrollRow - (int) Math.signum(delta), 0, maxScrollRow);
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (renamingTarget != null) {
            if (button == 0) {
                if (inRect(mouseX, mouseY, overlayConfirmBtnBounds())) { confirmRename(); return true; }
                if (inRect(mouseX, mouseY, overlayCancelBtnBounds()))  { cancelRename();  return true; }
            }
            if (!inRect(mouseX, mouseY, overlayPanelBounds())) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (editDurationTarget != null) {
            if (button == 0) {
                if (inRect(mouseX, mouseY, overlayConfirmBtnBounds())) { confirmEditDuration(); return true; }
                if (inRect(mouseX, mouseY, overlayCancelBtnBounds()))  { cancelEditDuration();  return true; }
            }
            if (!inRect(mouseX, mouseY, overlayPanelBounds())) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (editWithdrawXpTarget != null) {
            if (button == 0) {
                if (inRect(mouseX, mouseY, overlayConfirmBtnBounds())) { confirmEditWithdrawXp(); return true; }
                if (inRect(mouseX, mouseY, overlayCancelBtnBounds()))  { cancelEditWithdrawXp();  return true; }
            }
            if (!inRect(mouseX, mouseY, overlayPanelBounds())) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (editWithdrawLimitTarget != null) {
            if (button == 0) {
                if (inRect(mouseX, mouseY, overlayConfirmBtnBounds())) { confirmEditWithdrawLimit(); return true; }
                if (inRect(mouseX, mouseY, overlayCancelBtnBounds()))  { cancelEditWithdrawLimit();  return true; }
            }
            if (!inRect(mouseX, mouseY, overlayPanelBounds())) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (confirmDelete != null) {
            if (button == 0) {
                if (inRect(mouseX, mouseY, confirmBtnBounds())) {
                    BlueprintNetwork.CHANNEL.sendToServer(new C2S_DeleteBlueprintMessage(confirmDelete.id()));
                    ClientLibraryCache.invalidate();
                    summaries = new ArrayList<>();
                    rebuildFiltered();
                    confirmDelete = null;
                } else if (inRect(mouseX, mouseY, cancelBtnBounds())) {
                    confirmDelete = null;
                }
            }
            return true;
        }

        // ── 标题前缀（⎘/⎗）点击 → 切换服务器/私人模式 ────────────────────
        if (errorToast != null) {
            errorToast = null;
            if (button == 0) return true;
        }

        if (button == 0 && isOp()
                && mouseY >= titleY && mouseY < titleY + font.lineHeight
                && mouseX >= titlePrefixX && mouseX < titlePrefixX + titlePrefixW) {
            toggleServerMode();
            return true;
        }

        // ── 行选中（单击切换选中，工程 Tab 已选中行再点击 → SafeCoordScreen）──
        if (button == 0 && activeTab != null && confirmDelete == null) {
            int listTop      = getListTop();
            int listBottom   = getListBottom();
            int contentRight = panelX + PANEL_W - PAD;
            if (mouseX >= panelX + PAD && mouseX < contentRight
                    && mouseY >= listTop && mouseY < listBottom) {
                int clickedVis = (int)((mouseY - listTop) / ROW_H);
                int idx = scrollRow + clickedVis;
                if (idx >= 0 && idx < filtered.size()) {
                    BlueprintStorage.BlueprintSummary clicked = filtered.get(idx);
                    // 工程 Tab：已选中行再点击 → SafeCoordScreen
                    if ("engineering".equals(activeTab) && clicked.equals(selectedSummary)) {
                        Minecraft.getInstance().setScreen(new SafeCoordScreen(this, clicked));
                        return true;
                    }
                    selectedSummary = clicked.equals(selectedSummary) ? null : clicked;
                    clearWidgets(); init();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }

        boolean inOverlay = isOverlayActive();

        int pH = getPanelH();

        // 面板背景 + 边框
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + pH, 0xD0101010);
        drawBorder(graphics, panelX, panelY, PANEL_W, pH);

        if (!inOverlay) {
            renderTitle(graphics, mouseX, mouseY);
        }

        // Tab 区域分割线（最后一排 Tab 下方）
        graphics.fill(panelX + 4, getSepY(), panelX + PANEL_W - 4, getSepY() + 1, 0xFF444444);

        if (activeTab != null && !inOverlay) {
            // 搜索框下方分割线
            graphics.fill(panelX + 4, getListSepY(), panelX + PANEL_W - 4, getListSepY() + 1, 0xFF444444);
        }

        // 重置悬浮状态
        hoveredNameRow  = -1;
        hoveredStatsRow = -1;

        if (!inOverlay) {
            if (activeTab == null) {
                // 未选 Tab：提示文本
                String hint = Component.translatable("spatial_blueprint.gui.library.select_hint").getString();
                int hintX   = panelX + PANEL_W / 2;
                int sepYTop = getSepY() + 1;
                int hintY   = sepYTop + (getPanelH() - (sepYTop - panelY) - 26 - font.lineHeight) / 2;
                graphics.drawCenteredString(font, hint, hintX, hintY, 0x666666);
            } else {
                renderServerRows(graphics, mouseX, mouseY);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // ── 弹窗（在 super 之后绘制，覆盖其他元素）──────────────────────
        if (renamingTarget != null) {
            renderOverlayBackground(graphics, "spatial_blueprint.gui.library.rename_title");
            if (renameBox != null) renameBox.render(graphics, mouseX, mouseY, partialTick);
            renderOverlayButtons(graphics, mouseX, mouseY, true);
        }
        if (editDurationTarget != null) {
            renderOverlayBackground(graphics, "spatial_blueprint.gui.library.edit_duration_title");
            if (editDurationBox != null) editDurationBox.render(graphics, mouseX, mouseY, partialTick);
            renderOverlayButtons(graphics, mouseX, mouseY, false);
        }
        if (editWithdrawXpTarget != null) {
            renderOverlayBackground(graphics, "spatial_blueprint.gui.library.edit_withdraw_xp_title");
            if (editWithdrawXpBox != null) editWithdrawXpBox.render(graphics, mouseX, mouseY, partialTick);
            renderOverlayButtons(graphics, mouseX, mouseY, false);
        }
        if (editWithdrawLimitTarget != null) {
            renderOverlayBackground(graphics, "spatial_blueprint.gui.library.edit_withdraw_limit_title");
            if (editWithdrawLimitBox != null) editWithdrawLimitBox.render(graphics, mouseX, mouseY, partialTick);
            renderOverlayButtons(graphics, mouseX, mouseY, false);
        }

        // ── Tooltip ───────────────────────────────────────────────────────
        if (!inOverlay && confirmDelete == null) {
            renderTooltips(graphics, mouseX, mouseY);
        }

        // ── 确认删除弹窗 ──────────────────────────────────────────────────
        if (confirmDelete != null) {
            renderConfirmOverlay(graphics, mouseX, mouseY);
        }
    }

    // ── 服务器列表行渲染（含 Tab 逻辑）──────────────────────────────────

    private void renderServerRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int listTop      = getListTop();
        int listBottom   = getListBottom();
        int contentRight = panelX + PANEL_W - PAD;

        // 日期列预留宽度（最长 "12/31 23:59:59" ≈ 84px）
        final int DATE_RESERVE = 90;
        int nameMaxW = contentRight - DATE_RESERVE - 8 - (panelX + PAD + 3);

        graphics.enableScissor(panelX + PAD, listTop, contentRight, listBottom);

        if (filtered.isEmpty()) {
            String empty = ClientLibraryCache.isLoading()
                    ? Component.translatable("spatial_blueprint.gui.library.loading").getString()
                    : Component.translatable("spatial_blueprint.gui.library.empty").getString();
            graphics.drawCenteredString(font, empty,
                    panelX + PAD + (contentRight - panelX - PAD) / 2,
                    listTop + (listBottom - listTop) / 2 - font.lineHeight / 2,
                    0x888888);
        } else {
            int vis = Math.max(1, (listBottom - listTop) / ROW_H);
            for (int i = 0; i < vis; i++) {
                int idx = scrollRow + i;
                if (idx >= filtered.size()) break;
                BlueprintStorage.BlueprintSummary s = filtered.get(idx);
                int rowY = listTop + i * ROW_H;
                boolean isSelected = s.equals(selectedSummary);
                boolean hoverableRow = confirmDelete == null
                        && mouseX >= panelX + PAD && mouseX < contentRight
                        && mouseY >= rowY && mouseY < rowY + ROW_H;

                // 行背景：选中行用较亮的底色
                graphics.fill(panelX + PAD, rowY, contentRight, rowY + ROW_H,
                        isSelected ? 0x30FFFFFF : (i % 2 == 0 ? 0x18FFFFFF : 0x08FFFFFF));

                // 名称（左对齐）
                String displayName = truncate(
                        s.name().isEmpty()
                            ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                            : s.name(),
                        nameMaxW);
                int textY = rowY + (ROW_H - font.lineHeight) / 2;
                graphics.drawString(font, displayName, panelX + PAD + 3, textY, 0xEEEEEE, false);

                // 日期（右对齐，贴面板右边缘）
                String statsStr = formatDateShort(s.createdAt());
                int dateX = contentRight - font.width(statsStr) - 3;
                graphics.drawString(font, statsStr, dateX, textY, 0x888888, false);

                // 名称 / 日期 hover 检测
                if (hoverableRow && mouseY >= textY && mouseY < textY + font.lineHeight) {
                    int nameEndX = panelX + PAD + 3 + font.width(displayName);
                    if (mouseX >= panelX + PAD && mouseX < nameEndX) {
                        hoveredNameRow = i;
                    } else if (mouseX >= dateX && mouseX < dateX + font.width(statsStr)) {
                        hoveredStatsRow = i;
                    }
                }

                // 选中边框（白色 1px）
                if (isSelected) {
                    int sc = 0xFFFFFFFF;
                    graphics.fill(panelX + PAD,     rowY,             contentRight,    rowY + 1,         sc);
                    graphics.fill(panelX + PAD,     rowY + ROW_H - 1, contentRight,    rowY + ROW_H,     sc);
                    graphics.fill(panelX + PAD,     rowY,             panelX + PAD + 1, rowY + ROW_H,    sc);
                    graphics.fill(contentRight - 1, rowY,             contentRight,    rowY + ROW_H,     sc);
                }
            }
        }
        graphics.disableScissor();
    }

    // ── Tooltip ───────────────────────────────────────────────────────────

    private void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题前缀（⎘/⎗）tooltip
        if (isOp() && mouseY >= titleY && mouseY < titleY + font.lineHeight
                && mouseX >= titlePrefixX && mouseX < titlePrefixX + titlePrefixW) {
            String tipKey = serverMode
                    ? "spatial_blueprint.gui.library.title.prefix.to_private"
                    : "spatial_blueprint.gui.library.title.prefix.to_server";
            graphics.renderTooltip(font, Component.translatable(tipKey), mouseX, mouseY);
            return;
        }
        // 主标题文本 tooltip
        if (mouseY >= titleY && mouseY < titleY + font.lineHeight
                && mouseX >= titleMainX && mouseX < titleMainX + titleMainW) {
            String tipKey = serverMode
                    ? "spatial_blueprint.gui.library.title.main.tooltip.server"
                    : "spatial_blueprint.gui.library.title.main.tooltip.private";
            graphics.renderTooltip(font, Component.translatable(tipKey), mouseX, mouseY);
            return;
        }

        // 名称 tooltip
        if (hoveredNameRow >= 0) {
            int tipIdx = scrollRow + hoveredNameRow;
            if (tipIdx < filtered.size()) renderSummaryTooltip(graphics, filtered.get(tipIdx), mouseX, mouseY, true);
            return;
        }
        // 日期列 tooltip
        if (hoveredStatsRow >= 0) {
            int tipIdx = scrollRow + hoveredStatsRow;
            if (tipIdx < filtered.size()) renderSummaryTooltip(graphics, filtered.get(tipIdx), mouseX, mouseY, false);
            return;
        }
        boolean shiftHeld = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        net.minecraft.util.FormattedCharSequence opPermLine =
                Component.translatable("spatial_blueprint.gui.library.btn.perm_op")
                        .withStyle(ChatFormatting.RED).getVisualOrderText();

        // ♦ 复制文件名（OP 专属，手动 tooltip）
        if (btnCopyRef != null && btnCopyRef.isMouseOver(mouseX, mouseY)) {
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
            lines.add(Component.translatable("spatial_blueprint.gui.library.btn.copy_tip")
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
            if (shiftHeld) lines.add(opPermLine);
            graphics.renderTooltip(font, lines, mouseX, mouseY);
            return;
        }

        // ✂ 改时长（OP 专属，手动 tooltip）
        if (btnDurRef != null && btnDurRef.isMouseOver(mouseX, mouseY)) {
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
            lines.add(Component.translatable("spatial_blueprint.gui.library.btn.duration_tip")
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
            if (shiftHeld) lines.add(opPermLine);
            graphics.renderTooltip(font, lines, mouseX, mouseY);
            return;
        }

        // ✦ 取出经验 按钮动态 tooltip（显示选中项当前值）
        if (actionXpButton != null && actionXpButton.active
                && actionXpButton.isMouseOver(mouseX, mouseY) && selectedSummary != null) {
            int cur = selectedSummary.customWithdrawXp();
            net.minecraft.util.FormattedCharSequence curLine = cur >= 0
                    ? Component.translatable("spatial_blueprint.gui.library.btn.withdraw_xp_tip_current", cur)
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()
                    : Component.translatable("spatial_blueprint.gui.library.btn.withdraw_xp_tip_current_auto",
                            BlueprintConfig.MAX_WITHDRAW_XP_COST.get())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText();
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>(List.of(
                    Component.translatable("spatial_blueprint.gui.library.btn.withdraw_xp_tip")
                            .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                    curLine,
                    Component.translatable("spatial_blueprint.gui.library.btn.withdraw_tip_hint")
                            .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText()));
            if (shiftHeld) lines.add(opPermLine);
            graphics.renderTooltip(font, lines, mouseX, mouseY);
            return;
        }
        // ☀ 取出次数 按钮动态 tooltip
        if (actionLimitButton != null && actionLimitButton.active
                && actionLimitButton.isMouseOver(mouseX, mouseY) && selectedSummary != null) {
            int cur = selectedSummary.customWithdrawLimit();
            net.minecraft.util.FormattedCharSequence curLine = cur >= 0
                    ? Component.translatable("spatial_blueprint.gui.library.btn.withdraw_limit_tip_current", cur)
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText()
                    : Component.translatable("spatial_blueprint.gui.library.btn.withdraw_limit_tip_current_default",
                            BlueprintConfig.MAX_WITHDRAW_COUNT.get())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText();
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>(List.of(
                    Component.translatable("spatial_blueprint.gui.library.btn.withdraw_limit_tip")
                            .withStyle(ChatFormatting.YELLOW).getVisualOrderText(),
                    curLine,
                    Component.translatable("spatial_blueprint.gui.library.btn.withdraw_tip_hint")
                            .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText()));
            if (shiftHeld) lines.add(opPermLine);
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void renderSummaryTooltip(GuiGraphics g, BlueprintStorage.BlueprintSummary s, int mx, int my, boolean showEngHint) {
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        String displayName = s.name().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : s.name();
        lines.add(Component.literal(displayName).withStyle(ChatFormatting.WHITE).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.block_count", s.blockCount())
                .withStyle(ChatFormatting.GREEN).getVisualOrderText());
        int dur = s.animDuration();
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.anim_duration",
                dur > 0 ? dur : "-").withStyle(ChatFormatting.AQUA).getVisualOrderText());
        if (!s.creatorName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.creator", s.creatorName())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
        }
        if (!s.sourceName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.source", s.sourceName())
                    .withStyle(ChatFormatting.GOLD).getVisualOrderText());
        }
        if (s.createdAt() > 0) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.created_at",
                    formatDateFull(s.createdAt())).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        if (showEngHint && "engineering".equals(activeTab)) {
            String clickTip = s.safeCoordSet()
                    ? Component.translatable("spatial_blueprint.gui.safe_coord.name_tooltip").getString()
                      + "(" + s.safeX() + " " + s.safeY() + " " + s.safeZ() + ")"
                    : Component.translatable("spatial_blueprint.gui.safe_coord.name_tooltip").getString();
            lines.add(Component.literal(clickTip).withStyle(ChatFormatting.WHITE).getVisualOrderText());
        }
        g.renderTooltip(font, lines, mx, my);
    }

    // ── 弹窗渲染 ──────────────────────────────────────────────────────────

    private void renderOverlayBackground(GuiGraphics graphics, String titleKey) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        int[] p = overlayPanelBounds();
        graphics.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], 0xFF1A1A1A);
        drawBorder(graphics, p[0], p[1], p[2], p[3]);
        graphics.drawCenteredString(font, Component.translatable(titleKey).getString(),
                this.width / 2, this.height / 2 - 38, 0xFFFFAA);
    }

    private void renderOverlayButtons(GuiGraphics graphics, int mouseX, int mouseY, boolean isRename) {
        int[] confBtn = overlayConfirmBtnBounds();
        int[] cancBtn = overlayCancelBtnBounds();
        boolean hConf, hCanc;
        if (isRename) {
            hoverRenameConf   = inRect(mouseX, mouseY, confBtn);
            hoverRenameCancel = inRect(mouseX, mouseY, cancBtn);
            hConf = hoverRenameConf; hCanc = hoverRenameCancel;
        } else {
            hoverDurationConf   = inRect(mouseX, mouseY, confBtn);
            hoverDurationCancel = inRect(mouseX, mouseY, cancBtn);
            hConf = hoverDurationConf; hCanc = hoverDurationCancel;
        }
        graphics.fill(confBtn[0], confBtn[1], confBtn[0] + confBtn[2], confBtn[1] + confBtn[3],
                hConf ? 0xFF44AA44 : 0xFF225522);
        drawBorder(graphics, confBtn[0], confBtn[1], confBtn[2], confBtn[3]);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.library.confirm_delete.ok").getString(),
                confBtn[0] + confBtn[2] / 2, confBtn[1] + (confBtn[3] - font.lineHeight) / 2, 0xFFFFFF);
        graphics.fill(cancBtn[0], cancBtn[1], cancBtn[0] + cancBtn[2], cancBtn[1] + cancBtn[3],
                hCanc ? 0xFF555555 : 0xFF333333);
        drawBorder(graphics, cancBtn[0], cancBtn[1], cancBtn[2], cancBtn[3]);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.library.confirm_delete.cancel").getString(),
                cancBtn[0] + cancBtn[2] / 2, cancBtn[1] + (cancBtn[3] - font.lineHeight) / 2, 0xCCCCCC);
    }

    private void renderConfirmOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        int cx = this.width / 2, cy = this.height / 2;
        int cpX = cx - 110, cpW = 220, cpY = cy - 45, cpH = 90;
        graphics.fill(cpX, cpY, cpX + cpW, cpY + cpH, 0xFF1A1A1A);
        drawBorder(graphics, cpX, cpY, cpW, cpH);
        String title   = Component.translatable("spatial_blueprint.gui.library.confirm_delete.title").getString();
        String warning = Component.translatable("spatial_blueprint.gui.library.confirm_delete.warning").getString();
        String nameStr = truncate(confirmDelete.name().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : confirmDelete.name(), cpW - 20);
        graphics.drawCenteredString(font, title,   cx, cpY + 10, 0xFF5555);
        graphics.drawCenteredString(font, nameStr, cx, cpY + 24, 0xFFFFFF);
        graphics.drawCenteredString(font, warning, cx, cpY + 38, 0xAAAAAA);
        int[] confBtn = confirmBtnBounds(), cancBtn = cancelBtnBounds();
        hoverConfirm = inRect(mouseX, mouseY, confBtn);
        hoverCancel  = inRect(mouseX, mouseY, cancBtn);
        graphics.fill(confBtn[0], confBtn[1], confBtn[0] + confBtn[2], confBtn[1] + confBtn[3],
                hoverConfirm ? 0xFFAA3333 : 0xFF662222);
        drawBorder(graphics, confBtn[0], confBtn[1], confBtn[2], confBtn[3]);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.library.confirm_delete.ok").getString(),
                confBtn[0] + confBtn[2] / 2, confBtn[1] + (confBtn[3] - font.lineHeight) / 2, 0xFFFFFF);
        graphics.fill(cancBtn[0], cancBtn[1], cancBtn[0] + cancBtn[2], cancBtn[1] + cancBtn[3],
                hoverCancel ? 0xFF555555 : 0xFF333333);
        drawBorder(graphics, cancBtn[0], cancBtn[1], cancBtn[2], cancBtn[3]);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.library.confirm_delete.cancel").getString(),
                cancBtn[0] + cancBtn[2] / 2, cancBtn[1] + (cancBtn[3] - font.lineHeight) / 2, 0xCCCCCC);
    }

    // ── 重命名 ────────────────────────────────────────────────────────────

    private void confirmRename() {
        if (renamingTarget == null || renameBox == null) return;
        String newName = renameBox.getValue().trim();
        if (!newName.isEmpty()) {
            BlueprintNetwork.CHANNEL.sendToServer(new C2S_RenameBlueprintMessage(renamingTarget.id(), newName));
            ClientLibraryCache.invalidate();
            summaries = new ArrayList<>();
        }
        renamingTarget = null; renameBox = null; clearWidgets(); init();
    }

    private void cancelRename() {
        renamingTarget = null; renameBox = null; clearWidgets(); init();
    }

    // ── 修改放置时间 ──────────────────────────────────────────────────────

    private void confirmEditDuration() {
        if (editDurationTarget == null || editDurationBox == null) return;
        String val = editDurationBox.getValue().trim();
        if (!val.isEmpty()) {
            try {
                int dur = Math.max(BlueprintConfig.ANIMATION_DURATION_MIN.get(), Integer.parseInt(val));
                ClientLibraryCache.invalidate();
                summaries = new ArrayList<>();
                BlueprintNetwork.CHANNEL.sendToServer(
                        new com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage(
                                editDurationTarget.id(),
                                com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage.PROP_DURATION,
                                dur));
            } catch (NumberFormatException ignored) {}
        }
        editDurationTarget = null; editDurationBox = null; clearWidgets(); init();
    }

    private void cancelEditDuration() {
        editDurationTarget = null; editDurationBox = null; clearWidgets(); init();
    }

    // ── 设定取出经验 ──────────────────────────────────────────────────────

    private void confirmEditWithdrawXp() {
        if (editWithdrawXpTarget == null || editWithdrawXpBox == null) return;
        String val = editWithdrawXpBox.getValue().trim();
        int xp = -1;
        if (!val.isEmpty()) {
            try { xp = Math.max(0, Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
        }
        ClientLibraryCache.invalidate(); summaries = new ArrayList<>();
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage(
                        editWithdrawXpTarget.id(),
                        com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage.PROP_XP, xp));
        editWithdrawXpTarget = null; editWithdrawXpBox = null; clearWidgets(); init();
    }

    private void cancelEditWithdrawXp() {
        editWithdrawXpTarget = null; editWithdrawXpBox = null; clearWidgets(); init();
    }

    // ── 设定取出次数 ──────────────────────────────────────────────────────

    private void confirmEditWithdrawLimit() {
        if (editWithdrawLimitTarget == null || editWithdrawLimitBox == null) return;
        String val = editWithdrawLimitBox.getValue().trim();
        int limit = -1;
        if (!val.isEmpty()) {
            try { limit = Math.max(0, Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
        }
        ClientLibraryCache.invalidate(); summaries = new ArrayList<>();
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage(
                        editWithdrawLimitTarget.id(),
                        com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage.PROP_LIMIT, limit));
        editWithdrawLimitTarget = null; editWithdrawLimitBox = null; clearWidgets(); init();
    }

    private void cancelEditWithdrawLimit() {
        editWithdrawLimitTarget = null; editWithdrawLimitBox = null; clearWidgets(); init();
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────────

    // ── 动态布局辅助 ──────────────────────────────────────────────────────
    /** 面板高度：创造 264（2排 Tab），生存 246（1排 Tab）。 */
    private int getPanelH()      { return isCreative() ? 264 : 246; }
    /** 最后一排 Tab 底部 Y。 */
    private int getLastTabRowBottom() { return isCreative() ? panelY + 52 : panelY + 34; }
    /** Tab 区域与搜索框之间的分割线 Y。 */
    private int getSepY()        { return getLastTabRowBottom() + 3; }
    /** 搜索框顶部 Y（分割线下 3px）。 */
    private int getSearchBoxY()  { return getSepY() + 3; }
    /** 搜索框下方分割线 Y（搜索框 14px 高 + 2px 间距）。 */
    private int getListSepY()    { return getSearchBoxY() + 14 + 2; }
    private int getListTop()     { return getListSepY() + 1; }
    private int getListBottom()  { return panelY + getPanelH() - 26; }

    private boolean isOp() {
        return Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.hasPermissions(2);
    }

    private void toggleServerMode() {
        serverMode = !serverMode;
        displayTitle = serverMode
                ? Component.translatable("spatial_blueprint.gui.library.title.server")
                : Component.translatable("spatial_blueprint.gui.library.title.private");
        scrollRow = 0;
        ClientLibraryCache.invalidate();
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestLibraryMessage());
        rebuildFiltered();
        clearWidgets(); init();
    }

    private void renderTitle(GuiGraphics graphics, int mouseX, int mouseY) {
        int ty = panelY + 5;
        titleY = ty;
        int titleColor = serverMode ? 0x55DDFF : 0xFFFFAA;
        String mainStr = displayTitle.getString();
        int mainW = font.width(mainStr);

        if (isOp()) {
            String prefix = serverMode ? "⎗" : "⎘";
            String hash   = "#";
            int prefixW = font.width(prefix);
            int hashW   = font.width(hash);
            int totalW  = prefixW + 2 + hashW + 2 + mainW;
            int startX  = panelX + PANEL_W / 2 - totalW / 2;

            titlePrefixX = startX;
            titlePrefixW = prefixW;
            titleMainX   = startX + prefixW + 2 + hashW + 2;
            titleMainW   = mainW;

            boolean hoverPrefix = mouseX >= titlePrefixX && mouseX < titlePrefixX + titlePrefixW
                    && mouseY >= ty && mouseY < ty + font.lineHeight;
            Component prefixComp = hoverPrefix
                    ? Component.literal(prefix).withStyle(net.minecraft.ChatFormatting.UNDERLINE)
                    : Component.literal(prefix);
            graphics.drawString(font, prefixComp, startX, ty, titleColor, false);
            graphics.drawString(font, hash, startX + prefixW + 2, ty, titleColor, false);
            graphics.drawString(font, mainStr, titleMainX, ty, titleColor, false);
        } else {
            int startX = panelX + PANEL_W / 2 - mainW / 2;
            titlePrefixX = -999; titlePrefixW = 0;
            titleMainX   = startX; titleMainW = mainW;
            graphics.drawString(font, mainStr, startX, ty, titleColor, false);
        }
    }

    private boolean isServerTab() {
        return "create".equals(activeTab) || "project".equals(activeTab)
                || "dimensional".equals(activeTab) || "engineering".equals(activeTab)
                || "projects".equals(activeTab) || "template".equals(activeTab);
    }

    private boolean isOverlayActive() {
        return renamingTarget != null || editDurationTarget != null
                || editWithdrawXpTarget != null || editWithdrawLimitTarget != null
                || confirmDelete != null;
    }

    private void rebuildFiltered() {
        String lowerQuery = searchQuery.toLowerCase(java.util.Locale.ROOT);
        String me = myName();

        filtered = new ArrayList<>();
        for (BlueprintStorage.BlueprintSummary s : summaries) {
            String cat = s.category().isEmpty() ? "create" : s.category();
            if (!cat.equals(activeTab)) continue;
            // 私人模式或 template tab：只显示自己的
            if (!serverMode || "template".equals(activeTab)) {
                if (!me.equals(s.creatorName())) continue;
            }
            filtered.add(s);
        }
        if (!lowerQuery.isEmpty()) {
            if (lowerQuery.endsWith(".litematic")) {
                // 完整文件名精确匹配
                filtered.removeIf(s -> !getSummaryFilename(s).toLowerCase(java.util.Locale.ROOT).equals(lowerQuery));
            } else {
                filtered.removeIf(s -> !s.name().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery));
            }
        }
        filtered.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));

        // 校验选中项仍在过滤结果中，若缓存刷新则更新到新实例
        if (selectedSummary != null) {
            String selId = selectedSummary.id();
            selectedSummary = null;
            for (BlueprintStorage.BlueprintSummary s : filtered) {
                if (s.id().equals(selId)) { selectedSummary = s; break; }
            }
        }

        int vis = Math.max(1, (getListBottom() - getListTop()) / ROW_H);
        maxScrollRow = Math.max(0, filtered.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    private int[] overlayPanelBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        return new int[]{ cx - 110, cy - 45, 220, 90 };
    }
    private int[] overlayConfirmBtnBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        return new int[]{ cx - 110 + 20, cy - 45 + 65, 75, 18 };
    }
    private int[] overlayCancelBtnBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        return new int[]{ cx - 110 + 125, cy - 45 + 65, 75, 18 };
    }
    private int[] confirmBtnBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        return new int[]{ cx - 110 + 20, cy - 45 + 57, 75, 18 };
    }
    private int[] cancelBtnBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        return new int[]{ cx - 110 + 125, cy - 45 + 57, 75, 18 };
    }

    private static boolean inRect(double mx, double my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void drawMiniText(GuiGraphics g, String text, int bx, int by, int bw, int bh, int color) {
        final float S = 0.75f;
        int sw = (int)(font.width(text) * S);
        int sh = (int)(font.lineHeight * S);
        int ox = bx + (bw - sw) / 2;
        int oy = by + (bh - sh) / 2;
        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.pose().scale(S, S, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private String truncate(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "…") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String clientSanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.replaceAll("[^A-Za-z0-9_]", "").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "unknown" : s;
    }

    private static String getSummaryFilename(BlueprintStorage.BlueprintSummary s) {
        String cat = clientSanitizeFilename(s.category().isEmpty() ? "unknown" : s.category());
        String cre = clientSanitizeFilename(s.creatorName().isBlank() ? "unknown" : s.creatorName());
        return cat + "_" + cre + "_" + s.id() + ".litematic";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String fmtDate(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("M/d HH:mm").format(new Date(ms));
    }

    private static String formatDateFull(long epochMillis) {
        if (epochMillis <= 0) return "-";
        java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return dt.getYear() + "/" + dt.getMonthValue() + "/" + dt.getDayOfMonth()
                + " " + String.format("%02d:%02d:%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private static String formatDateShort(long epochMillis) {
        if (epochMillis <= 0) return "-";
        java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return dt.getMonthValue() + "/" + dt.getDayOfMonth()
                + " " + String.format("%02d:%02d:%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private String myName() {
        LocalPlayer lp = Minecraft.getInstance().player;
        return lp != null ? lp.getGameProfile().getName() : "";
    }

    private boolean isCreative() {
        LocalPlayer lp = Minecraft.getInstance().player;
        return lp != null && lp.isCreative();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
