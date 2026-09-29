package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.ClientProjectListCache;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_DeleteProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage;
import com.xioyim.spatialblueprint.network.C2S_TakeProjectMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 导入项目界面（蓝图库 projectset tab 的 ◈ 打开）。壳子照 {@link UploadBlueprintScreen}：
 * 三个 tab（共用 / 自用 / 蓝图站）、列表 + 搜索、右下 ❌ ♻、左下 [导入项目] [规划项目]。
 *
 * <p>列表数据来自服务端 {@link ClientProjectListCache}（项目文件只在服务端）。
 * 每点一次 tab 就发一次请求，2 秒冷却；冷却内只切显示。</p>
 *
 * <p>行显示照蓝图库：名字(后缀) / 备注居中 / 创建时间靠右。名字按 kind 着色：
 * 共用亮蓝加粗、自用橙加粗、蓝图站普通。后缀由 stage 与进度算出（见 {@link #suffixOf}）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ProjectBrowserScreen extends Screen implements ErrorToastSupport {

    private static final int PANEL_W = 364;
    private static final int PANEL_H = 244;
    private static final int ROW_H   = 18;
    private static final int PAD     = 4;
    private static final int NAME_COL_W = 132;
    private static final int NOTE_COL_W = 108;
    private static final int MAX_NAME_CHARS = 16;
    private static final int MAX_NOTE_CHARS = 16;

    private static final String[] TAB_IDS = {
        C2S_RequestProjectListMessage.TAB_PUBLIC,
        C2S_RequestProjectListMessage.TAB_PRIVATE,
        C2S_RequestProjectListMessage.TAB_STATION
    };
    private static final String[] TAB_KEYS = {
        "spatial_blueprint.gui.project_browser.tab.public",
        "spatial_blueprint.gui.project_browser.tab.private",
        "spatial_blueprint.gui.project_browser.tab.station"
    };
    private static final String[] TAB_TOOLTIP_KEYS = {
        "spatial_blueprint.gui.project_browser.tab.public.tooltip",
        "spatial_blueprint.gui.project_browser.tab.private.tooltip",
        "spatial_blueprint.gui.project_browser.tab.station.tooltip"
    };

    private final Screen parent;

    @Nullable private String activeTab = null;
    @Nullable private Component errorToast = null;

    private List<S2C_ProjectListMessage.ProjectSummary> filtered = new ArrayList<>();
    /** 上次同步到的缓存版本；变化即重建列表与按钮。 */
    private int cacheVersion = -1;

    // I1 同款：逐字符截断的按文本缓存（键 = 宽度|文本），rebuildFiltered 开头清掉。
    private final java.util.Map<String, String> truncateCache = new java.util.HashMap<>();

    private int    scrollRow    = 0;
    private int    maxScrollRow = 0;
    private String searchQuery  = "";

    @Nullable private EditBox searchBox = null;
    private int panelX, panelY;

    private int hoveredRow = -1;
    /** 选中的项目 id（不是行号，刷新后仍能对上）；null = 未选中。 */
    @Nullable private String selectedId = null;
    /** [删除项目] 确认框开着：只建确认框的两个按钮，其余控件不建、输入被吃（防误删）。 */
    private boolean deleteConfirm = false;
    private int hoverNameIdx = -1;
    private int hoverNoteIdx = -1;
    @Nullable private String hoveredNoteTip = null;

    public ProjectBrowserScreen(Screen parent) {
        super(Component.translatable("spatial_blueprint.gui.project_browser.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int cx = this.width  / 2;
        int cy = this.height / 2;
        panelX = cx - PANEL_W / 2;
        panelY = cy - PANEL_H / 2;

        // 蓝图站 tab 仅 OP：被降权后还停在这个 tab 上的话退回未选
        if (!isOp() && C2S_RequestProjectListMessage.TAB_STATION.equals(activeTab)) {
            activeTab = null;
            selectedId = null;
        }
        // 先按当前缓存把列表算出来，下面底部按钮的可点状态要看「选中的还在不在」
        rebuildFiltered();

        // 删除确认态：只建确认框的两个按钮，其余控件不建（与规划模式的关闭提示同一做法）
        if (deleteConfirm) {
            addDeletePromptWidgets(deletePromptBounds());
            return;
        }

        // ── Tabs ─────────────────────────────────────────────────────────
        int tabW = (PANEL_W - PAD * 2 - 2 * (TAB_IDS.length - 1)) / TAB_IDS.length;
        for (int i = 0; i < TAB_IDS.length; i++) {
            final String tab = TAB_IDS[i];
            boolean station = C2S_RequestProjectListMessage.TAB_STATION.equals(tab);
            Button btn = addRenderableWidget(Button.builder(
                            Component.translatable(TAB_KEYS[i]), b -> switchTab(tab))
                .bounds(panelX + PAD + i * (tabW + 2), panelY + 18, tabW, 16)
                .tooltip(Tooltip.create(station && !isOp()
                        ? Component.translatable(TAB_TOOLTIP_KEYS[i]).append("\n")
                                .append(Component.translatable("spatial_blueprint.gui.project_browser.tab.station.op_only")
                                        .withStyle(ChatFormatting.RED))
                        : Component.translatable(TAB_TOOLTIP_KEYS[i])))
                .build());
            btn.active = (station && !isOp()) ? false : !tab.equals(activeTab);
        }

        // ── 右下：❌ 关闭 / ♻ 刷新 ────────────────────────────────────────
        int botBtnY   = panelY + PANEL_H - 22;
        int rightEdge = panelX + PANEL_W - PAD;
        int miniSz    = 18;

        addRenderableWidget(Button.builder(Component.literal("❌"), b -> onClose())
            .bounds(rightEdge - miniSz, botBtnY, miniSz, miniSz)
            .build());

        Button btnRefresh = addRenderableWidget(Button.builder(Component.literal("♻"), b -> {
                    if (activeTab != null) ClientProjectListCache.request(activeTab);
                })
            .bounds(rightEdge - miniSz * 2 - 2, botBtnY, miniSz, miniSz)
            .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.project_browser.btn.refresh")))
            .build());
        btnRefresh.active = activeTab != null;

        // ── 左下：[导入项目] [规划项目]（文字按钮）────────────────────────
        S2C_ProjectListMessage.ProjectSummary sel = selected();
        Component importLabel = Component.translatable("spatial_blueprint.gui.project_browser.btn.import");
        Component planLabel   = Component.translatable("spatial_blueprint.gui.project_browser.btn.plan");
        int importW = font.width(importLabel) + 12;
        int planW   = font.width(planLabel) + 12;
        int bx = panelX + PAD;

        boolean stationTab = C2S_RequestProjectListMessage.TAB_STATION.equals(activeTab);
        Button btnImport = addRenderableWidget(Button.builder(importLabel,
                        b -> takeSelected(C2S_TakeProjectMessage.MODE_IMPORT))
            .bounds(bx, botBtnY, importW, miniSz)
            .tooltip(Tooltip.create(stationTab
                    ? Component.translatable("spatial_blueprint.gui.project_browser.btn.import.station")
                            .withStyle(ChatFormatting.RED)
                    : Component.translatable("spatial_blueprint.gui.project_browser.btn.import.tooltip")))
            .build());
        btnImport.active = sel != null && !stationTab;
        bx += importW + 2;

        Button btnPlan = addRenderableWidget(Button.builder(planLabel,
                        b -> takeSelected(C2S_TakeProjectMessage.MODE_PLAN))
            .bounds(bx, botBtnY, planW, miniSz)
            .tooltip(Tooltip.create(isOp()
                    ? Component.translatable("spatial_blueprint.gui.project_browser.btn.plan.tooltip")
                    : Component.translatable("spatial_blueprint.gui.project_browser.btn.plan.tooltip").append("\n")
                            .append(Component.translatable("spatial_blueprint.gui.project_browser.tab.station.op_only")
                                    .withStyle(ChatFormatting.RED))))
            .build());
        btnPlan.active = sel != null && isOp();
        bx += planW + 2;

        // ── [删除项目]（右侧，仅 OP；确认框防误删）────────────────────────
        Component deleteLabel = Component.translatable("spatial_blueprint.gui.project_browser.btn.delete");
        int deleteW = font.width(deleteLabel) + 12;
        Button btnDelete = addRenderableWidget(Button.builder(deleteLabel, b -> askDelete())
                .bounds(bx, botBtnY, deleteW, miniSz)
                .tooltip(Tooltip.create(isOp()
                        ? Component.translatable("spatial_blueprint.gui.project_browser.btn.delete.tooltip")
                        : Component.translatable("spatial_blueprint.gui.project_browser.btn.delete.tooltip")
                                .append("\n")
                                .append(Component.translatable("spatial_blueprint.gui.project_browser.tab.station.op_only")
                                        .withStyle(ChatFormatting.RED))))
                .build());
        btnDelete.active = sel != null && isOp();

        // ── 搜索框 ───────────────────────────────────────────────────────
        if (activeTab != null) {
            searchBox = new EditBox(font,
                    panelX + PAD + 1, panelY + 40,
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
    }

    /** 每点一次 tab 都向服务端要一次（2 秒冷却由缓存层挡）；冷却内先显示缓存。 */
    private void switchTab(String tab) {
        activeTab = tab;
        scrollRow = 0;
        searchQuery = "";
        selectedId = null;
        ClientProjectListCache.request(tab);
        clearWidgets();
        init();
    }

    private void takeSelected(int mode) {
        S2C_ProjectListMessage.ProjectSummary sel = selected();
        if (sel == null) return;
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_TakeProjectMessage(sel.projectId(), mode));
    }

    @Nullable
    private S2C_ProjectListMessage.ProjectSummary selected() {
        if (selectedId == null) return null;
        for (S2C_ProjectListMessage.ProjectSummary p : filtered) {
            if (p.projectId().equals(selectedId)) return p;
        }
        return null;
    }

    // ── [删除项目] 确认框 ─────────────────────────────────────────────────

    /** 弹确认框（防误删）；没有选中项时不动作。 */
    private void askDelete() {
        if (selected() == null) return;
        deleteConfirm = true;
        clearWidgets();
        init();
    }

    private int[] deletePromptBounds() {
        int w = Math.min(260, PANEL_W - 20);
        int h = 80;
        return new int[]{panelX + (PANEL_W - w) / 2, panelY + (PANEL_H - h) / 2, w, h};
    }

    private void addDeletePromptWidgets(int[] b) {
        int gap = 4;
        int bw = (b[2] - gap * 3) / 2;
        int by = b[1] + b[3] - 18 - 6;
        Button del = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_browser.delete_confirm.btn_delete"),
                        btn -> confirmDelete())
                .bounds(b[0] + gap, by, bw, 18).build());
        del.setFGColor(0xFF5555);
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_browser.delete_confirm.btn_cancel"),
                        btn -> {
                            deleteConfirm = false;
                            clearWidgets();
                            init();
                        })
                .bounds(b[0] + gap * 2 + bw, by, bw, 18).build());
    }

    /** 确认删除：发服务端；成功后服务端会推一份同 tab 的新清单刷新界面（失败走横幅）。 */
    private void confirmDelete() {
        S2C_ProjectListMessage.ProjectSummary sel = selected();
        deleteConfirm = false;
        clearWidgets();
        if (sel != null) {
            BlueprintNetwork.CHANNEL.sendToServer(
                    new C2S_DeleteProjectMessage(sel.projectId(), activeTab));
            selectedId = null;
        }
        init();
    }

    private void renderDeletePrompt(GuiGraphics graphics) {
        int[] b = deletePromptBounds();
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1] + b[3] + 1, 0xFF666666);
        graphics.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xFF161616);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.project_browser.delete_confirm.title"),
                b[0] + b[2] / 2, b[1] + 10, 0xFFFFAA);
        S2C_ProjectListMessage.ProjectSummary sel = selected();
        String name = sel == null ? "" : (sel.name().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : sel.name());
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.project_browser.delete_confirm.hint", name),
                b[0] + b[2] / 2, b[1] + 10 + font.lineHeight + 4, 0xAAAAAA);
    }

    private void rebuildFiltered() {
        truncateCache.clear();
        filtered = new ArrayList<>();
        if (activeTab == null) return;
        List<S2C_ProjectListMessage.ProjectSummary> source = ClientProjectListCache.get(activeTab);
        if (source == null) return;
        String q = searchQuery.toLowerCase(Locale.ROOT);
        for (S2C_ProjectListMessage.ProjectSummary p : source) {
            if (!q.isEmpty() && !p.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            filtered.add(p);
        }
        // 服务端已按创建时间倒序，这里不再排
        if (selectedId != null && selected() == null) selectedId = null;
        int vis = Math.max(1, (getListBottom() - getListTop()) / ROW_H);
        maxScrollRow = Math.max(0, filtered.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        if (deleteConfirm) {
            if (keyCode == 256) {
                deleteConfirm = false;
                clearWidgets();
                init();
            }
            return true;
        }
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (deleteConfirm) return true;
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
        if (errorToast != null) {
            errorToast = null;
            return true;
        }
        if (deleteConfirm) {
            int[] b = deletePromptBounds();
            if (mouseX < b[0] || mouseX >= b[0] + b[2] || mouseY < b[1] || mouseY >= b[1] + b[3]) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && activeTab != null && hoveredRow >= 0) {
            int idx = scrollRow + hoveredRow;
            if (idx < filtered.size()) {
                String id = filtered.get(idx).projectId();
                selectedId = id.equals(selectedId) ? null : id;
                clearWidgets(); init();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 服务端清单到了：重建列表与底部按钮状态（选中按 id 保留）
        int v = ClientProjectListCache.version();
        if (v != cacheVersion) {
            cacheVersion = v;
            clearWidgets(); init();
        }

        this.renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, font, this.width, this.height, errorToast);
            return;
        }

        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xD0101010);
        drawBorder(graphics, panelX, panelY, PANEL_W, PANEL_H);

        String titleStr = Component.translatable("spatial_blueprint.gui.project_browser.title").getString();
        graphics.drawCenteredString(font, titleStr, panelX + PANEL_W / 2, panelY + 5, 0xFFFFAA);

        // 删除确认态：只画确认框（底下列表与提示都不画），按钮由 super.render 画
        if (deleteConfirm) {
            renderDeletePrompt(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        graphics.fill(panelX + 4, panelY + 37, panelX + PANEL_W - 4, panelY + 38, 0xFF444444);

        hoveredRow = -1;
        hoverNameIdx = -1;
        hoverNoteIdx = -1;
        hoveredNoteTip = null;

        if (activeTab == null) {
            String hint = Component.translatable("spatial_blueprint.gui.library.select_hint").getString();
            int hintY = panelY + 38 + (PANEL_H - 38 - 26 - font.lineHeight) / 2;
            graphics.drawCenteredString(font, hint, panelX + PANEL_W / 2, hintY, 0x666666);
        } else {
            graphics.fill(panelX + 4, panelY + 56, panelX + PANEL_W - 4, panelY + 57, 0xFF444444);
            renderRows(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (activeTab != null) renderRowTooltips(graphics, mouseX, mouseY);
    }

    private void renderRows(GuiGraphics g, int mx, int my) {
        int listTop      = getListTop();
        int listBottom   = getListBottom();
        int contentRight = panelX + PANEL_W - PAD;
        int vis          = Math.max(1, (listBottom - listTop) / ROW_H);

        g.enableScissor(panelX + PAD, listTop, contentRight, listBottom);

        if (filtered.isEmpty()) {
            String empty = ClientProjectListCache.isLoading(activeTab)
                    ? Component.translatable("spatial_blueprint.gui.library.loading").getString()
                    : Component.translatable("spatial_blueprint.gui.project_browser.empty").getString();
            g.drawCenteredString(font, empty,
                    panelX + PAD + (contentRight - panelX - PAD) / 2,
                    listTop + (listBottom - listTop) / 2 - font.lineHeight / 2,
                    0x888888);
        } else {
            for (int i = 0; i < vis; i++) {
                int idx = scrollRow + i;
                if (idx >= filtered.size()) break;
                S2C_ProjectListMessage.ProjectSummary p = filtered.get(idx);
                int rowY  = listTop + i * ROW_H;
                int textY = rowY + (ROW_H - font.lineHeight) / 2;
                boolean hov = mx >= panelX + PAD && mx < contentRight && my >= rowY && my < rowY + ROW_H;
                if (hov) hoveredRow = i;
                boolean sel = p.projectId().equals(selectedId);

                g.fill(panelX + PAD, rowY, contentRight, rowY + ROW_H,
                        sel ? 0x30FFFFFF : (i % 2 == 0 ? 0x18FFFFFF : 0x08FFFFFF));

                // 日期（右对齐）
                String dateStr = formatDateShort(p.createdAt());
                int dateW = font.width(dateStr);
                int dateX = contentRight - dateW - 3;

                // 名字(后缀)：后缀是状态，永远完整显示，让位的是名字
                Component suffix = suffixOf(p);
                int suffixW = font.width(suffix);
                int suffixChars = suffix.getString().codePointCount(0, suffix.getString().length());
                String rawName = p.name().isEmpty()
                        ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                        : p.name();
                int nameChars = Math.max(6, MAX_NAME_CHARS - suffixChars);
                String displayName = truncate(truncateChars(rawName, nameChars),
                        Math.max(0, NAME_COL_W - suffixW));
                int nameX = panelX + PAD + 3;
                MutableComponent nameComp = Component.literal(displayName);
                int nameColor;
                switch (p.kind()) {
                    case PUBLIC  -> { nameComp.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD); nameColor = 0x55FFFF; }
                    case PRIVATE -> { nameComp.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD); nameColor = 0xFFAA00; }
                    default      -> nameColor = 0xEEEEEE;
                }
                g.drawString(font, nameComp, nameX, textY, nameColor, false);
                int nameDrawnW = font.width(nameComp);
                g.drawString(font, suffix, nameX + nameDrawnW, textY, 0xFFFFFF, false);
                nameDrawnW += suffixW;

                g.drawString(font, dateStr, dateX, textY, 0x888888, false);

                // 备注居中（与蓝图库一致）
                String noteDisplay;
                String noteTip = p.noteLong().isEmpty() ? null : p.noteLong();
                if (p.noteShort().isEmpty() && p.noteLong().isEmpty()) {
                    noteDisplay = Component.translatable("spatial_blueprint.gui.library.note.none").getString();
                    noteTip = null;
                } else if (p.noteShort().isEmpty()) {
                    noteDisplay = Component.translatable("spatial_blueprint.gui.library.note.default").getString();
                } else {
                    noteDisplay = p.noteShort();
                }
                String fullNote = noteDisplay;
                noteDisplay = truncate(truncateChars(noteDisplay, MAX_NOTE_CHARS), NOTE_COL_W);
                if (noteTip == null && !noteDisplay.equals(fullNote)) noteTip = fullNote;
                int noteW = font.width(noteDisplay);
                int noteX = panelX + PANEL_W / 2 - noteW / 2;
                g.drawString(font, noteDisplay, noteX, textY, 0x888888, false);

                if (hov && my >= textY && my < textY + font.lineHeight) {
                    if (mx >= nameX && mx < nameX + nameDrawnW) {
                        hoverNameIdx = idx;
                    } else if (noteTip != null && mx >= noteX && mx < noteX + noteW) {
                        hoverNoteIdx = idx;
                        hoveredNoteTip = noteTip;
                    }
                }

                if (sel) {
                    int sc = 0xFFFFFFFF;
                    g.fill(panelX + PAD,     rowY,             contentRight,    rowY + 1,         sc);
                    g.fill(panelX + PAD,     rowY + ROW_H - 1, contentRight,    rowY + ROW_H,     sc);
                    g.fill(panelX + PAD,     rowY,             panelX + PAD + 1, rowY + ROW_H,    sc);
                    g.fill(contentRight - 1, rowY,             contentRight,    rowY + ROW_H,     sc);
                }
            }
        }
        g.disableScissor();
        maxScrollRow = Math.max(0, filtered.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    /**
     * 名字后缀：已拆除最优先；有进度（completedCount ≥ 1）按完成比例；否则看手动阶段。
     * 私人项目的进度是查看者自己那份，没激活过就只看阶段。
     */
    static Component suffixOf(S2C_ProjectListMessage.ProjectSummary p) {
        if (p.stage() == ProjectData.Stage.REMOVED) {
            return Component.translatable("spatial_blueprint.gui.project_browser.suffix.removed");
        }
        if (p.completedCount() > 0 && p.entryCount() > 0) {
            if (p.completedCount() >= p.entryCount()) {
                return Component.translatable("spatial_blueprint.gui.project_browser.suffix.completed");
            }
            int percent = (int) (p.completedCount() * 100L / p.entryCount());
            return Component.translatable("spatial_blueprint.gui.project_browser.suffix.building", percent);
        }
        return switch (p.stage()) {
            case LAYOUT   -> Component.translatable("spatial_blueprint.gui.project_browser.suffix.layout");
            case PLANNING -> Component.translatable("spatial_blueprint.gui.project_browser.suffix.planning");
            default       -> Component.translatable("spatial_blueprint.gui.project_browser.suffix.preparing");
        };
    }

    private void renderRowTooltips(GuiGraphics g, int mx, int my) {
        if (hoverNameIdx >= 0 && hoverNameIdx < filtered.size()) {
            g.renderTooltip(font, buildMetaTooltip(filtered.get(hoverNameIdx)), mx, my);
            return;
        }
        if (hoverNoteIdx >= 0 && hoveredNoteTip != null) {
            g.renderTooltip(font, Component.literal(hoveredNoteTip), mx, my);
        }
    }

    /** 名字 tooltip：完整名字 + 类型 / 阶段 + 子工程数 + 创建玩家 + 创建时间。 */
    private List<FormattedCharSequence> buildMetaTooltip(S2C_ProjectListMessage.ProjectSummary p) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        MutableComponent title = Component.literal(p.name().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString() : p.name());
        switch (p.kind()) {
            case PUBLIC  -> title.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
            case PRIVATE -> title.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            default      -> title.withStyle(ChatFormatting.WHITE);
        }
        lines.add(title.append(suffixOf(p).copy().withStyle(ChatFormatting.WHITE)).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.project_browser.meta.entries",
                        p.entryCount(), Math.max(0, p.completedCount()))
                .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        if (p.kind() == ProjectData.Kind.PUBLIC && !p.activated()) {
            lines.add(Component.translatable("spatial_blueprint.gui.project_browser.meta.not_activated")
                    .withStyle(ChatFormatting.RED).getVisualOrderText());
        }
        if (!p.ownerName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.creator", p.ownerName())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
        }
        if (p.createdAt() > 0) {
            lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.time",
                            BlueprintTooltips.formatDateFull(p.createdAt()))
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        return lines;
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    private int getListTop()    { return panelY + 58; }
    private int getListBottom() { return panelY + PANEL_H - 26; }

    private boolean isOp() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.hasPermissions(2);
    }

    private static String truncateChars(String s, int maxChars) {
        if (s.codePointCount(0, s.length()) <= maxChars) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxChars)) + "…";
    }

    private String truncate(String s, int maxPx) {
        String key = maxPx + "|" + s;
        String hit = truncateCache.get(key);
        if (hit != null) return hit;
        String out = s;
        if (font.width(out) <= maxPx) { truncateCache.put(key, out); return out; }
        while (out.length() > 1 && font.width(out + "…") > maxPx) out = out.substring(0, out.length() - 1);
        out = out + "…";
        truncateCache.put(key, out);
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    /** 与蓝图库列表同一格式：M/d HH:mm:ss。 */
    private static String formatDateShort(long epochMillis) {
        if (epochMillis <= 0) return "-";
        java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return dt.getMonthValue() + "/" + dt.getDayOfMonth()
                + " " + String.format("%02d:%02d:%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
