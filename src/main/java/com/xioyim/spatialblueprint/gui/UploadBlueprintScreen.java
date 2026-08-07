package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_UploadProjectionTemplateMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.LitematicaImporter;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.util.SchematicImporter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 上传本地蓝图界面。
 * <p>3 个 Tab：空间蓝图 / 投影蓝图 / 投影源文件（黄色，含 Schematic 上传逻辑）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class UploadBlueprintScreen extends Screen implements ErrorToastSupport {

    private static final int PANEL_W = 364;
    private static final int PANEL_H = 244;
    private static final int ROW_H   = 18;
    private static final int PAD     = 4;

    private static final String[] TAB_IDS  = { "space", "projection", "source" };
    private static final String[] TAB_KEYS = {
        "spatial_blueprint.gui.upload.tab.space",
        "spatial_blueprint.gui.upload.tab.projection",
        "spatial_blueprint.gui.proj_template.tab.source"
    };
    private static final String[] TAB_TOOLTIP_KEYS = {
        "spatial_blueprint.gui.upload.tab.space.tooltip",
        "spatial_blueprint.gui.upload.tab.projection.tooltip",
        "spatial_blueprint.gui.upload.tab.source.tooltip"
    };

    private final Screen parent;
    @Nullable private String activeTab = null;
    @Nullable private Component errorToast = null;

    private List<File> schematicFiles = new ArrayList<>();
    private List<File> filteredFiles  = new ArrayList<>();
    private int        uploadingRow   = -1;

    private int    scrollRow    = 0;
    private int    maxScrollRow = 0;
    private String searchQuery  = "";

    @Nullable private EditBox searchBox = null;
    private int panelX, panelY;

    private int hoveredRow = -1;
    private int hoveredBtn = -1;

    public UploadBlueprintScreen(Screen parent) {
        super(Component.translatable("spatial_blueprint.gui.upload.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        refreshSourceFiles();

        int cx = this.width  / 2;
        int cy = this.height / 2;
        panelX = cx - PANEL_W / 2;
        panelY = cy - PANEL_H / 2;

        // ── Tabs ─────────────────────────────────────────────────────────
        int tabW = (PANEL_W - 12) / 3;
        for (int i = 0; i < TAB_IDS.length; i++) {
            final String tab = TAB_IDS[i];
            int xOff = (i == 2) ? 1 : 0;
            boolean isSource = "source".equals(tab);
            Component label = isSource
                    ? Component.translatable(TAB_KEYS[i]).withStyle(ChatFormatting.YELLOW)
                    : Component.translatable(TAB_KEYS[i]);
            Button btn = addRenderableWidget(Button.builder(label, b -> switchTab(tab))
                .bounds(panelX + 4 + i * (tabW + 2) + xOff, panelY + 18, tabW, 16)
                .tooltip(Tooltip.create(Component.translatable(TAB_TOOLTIP_KEYS[i])))
                .build());
            btn.active = !tab.equals(activeTab);
        }

        // ── 底部按钮行 ─────────────────────────────────────────────────
        int botBtnH   = 18;
        int botBtnY   = panelY + PANEL_H - 22;
        int rightEdge = panelX + PANEL_W - PAD;

        int closeW   = font.width(Component.translatable("spatial_blueprint.gui.library.close")) + 8;
        int refreshW = font.width(Component.translatable("spatial_blueprint.gui.proj_template.btn.refresh")) + 8;

        int closeX   = rightEdge - closeW;
        int refreshX = closeX - 3 - refreshW;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.library.close"),
                b -> onClose())
            .bounds(closeX, botBtnY, closeW, botBtnH)
            .build());

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.proj_template.btn.refresh"),
                b -> { refreshSourceFiles(); scrollRow = 0; clearWidgets(); init(); })
            .bounds(refreshX, botBtnY, refreshW, botBtnH)
            .build());

        // ── 搜索框（仅 source tab）───────────────────────────────────────
        if ("source".equals(activeTab)) {
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

    private void switchTab(String tab) {
        activeTab = tab;
        scrollRow = 0;
        searchQuery = "";
        uploadingRow = -1;
        refreshSourceFiles();
        clearWidgets();
        init();
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
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!"source".equals(activeTab)) return true;
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
        if (button == 0 && "source".equals(activeTab) && hoveredRow >= 0 && hoveredBtn == 0) {
            int idx = scrollRow + hoveredRow;
            if (uploadingRow != idx) uploadSchematic(idx);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, font, this.width, this.height, errorToast);
            return;
        }

        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xD0101010);
        drawBorder(graphics, panelX, panelY, PANEL_W, PANEL_H);

        String titleStr = Component.translatable("spatial_blueprint.gui.upload.title").getString();
        graphics.drawCenteredString(font, titleStr, panelX + PANEL_W / 2, panelY + 5, 0xFFFFAA);

        // Tab 分割线
        graphics.fill(panelX + 4, panelY + 37, panelX + PANEL_W - 4, panelY + 38, 0xFF444444);

        hoveredRow = -1;
        hoveredBtn = -1;

        if (activeTab == null) {
            String hint = Component.translatable("spatial_blueprint.gui.library.select_hint").getString();
            int hintY = panelY + 38 + (PANEL_H - 38 - 26 - font.lineHeight) / 2;
            graphics.drawCenteredString(font, hint, panelX + PANEL_W / 2, hintY, 0x666666);
        } else if ("source".equals(activeTab)) {
            // 搜索框下分割线
            graphics.fill(panelX + 4, panelY + 56, panelX + PANEL_W - 4, panelY + 57, 0xFF444444);
            renderSourceRows(graphics, mouseX, mouseY);
        } else {
            // 空壳：显示占位提示
            String hint = Component.translatable("spatial_blueprint.gui.library.select_hint").getString();
            int hintY = panelY + 38 + (PANEL_H - 38 - 26 - font.lineHeight) / 2;
            graphics.drawCenteredString(font, hint, panelX + PANEL_W / 2, hintY, 0x666666);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip
        if ("source".equals(activeTab) && hoveredRow >= 0 && hoveredBtn == 0) {
            graphics.renderTooltip(font,
                    Component.translatable("spatial_blueprint.gui.proj_template.tooltip.convert"),
                    mouseX, mouseY);
        }
    }

    // ── Source 行渲染 ─────────────────────────────────────────────────────

    private void renderSourceRows(GuiGraphics g, int mx, int my) {
        int listTop      = getListTop();
        int listBottom   = getListBottom();
        int contentRight = panelX + PANEL_W - PAD;
        int vis          = Math.max(1, (listBottom - listTop) / ROW_H);

        String convertText = Component.translatable("spatial_blueprint.gui.proj_template.btn.convert").getString();
        int btnW        = font.width(convertText) + 12;
        int btnH        = ROW_H - 4;
        int mbX         = contentRight - btnW - 3;
        int statsCenter = panelX + PANEL_W / 2;
        int nameMaxW    = mbX - (panelX + PAD + 3) - 6;

        g.enableScissor(panelX + PAD, listTop, contentRight, listBottom);

        if (filteredFiles.isEmpty()) {
            String empty = Component.translatable("spatial_blueprint.gui.library.empty").getString();
            g.drawCenteredString(font, empty,
                    panelX + PAD + (contentRight - panelX - PAD) / 2,
                    listTop + (listBottom - listTop) / 2 - font.lineHeight / 2,
                    0x888888);
        } else {
            for (int i = 0; i < vis; i++) {
                int idx = scrollRow + i;
                if (idx >= filteredFiles.size()) break;
                File f = filteredFiles.get(idx);
                int rowY  = listTop + i * ROW_H;
                int mbY   = rowY + (ROW_H - btnH) / 2;
                int textY = rowY + (ROW_H - font.lineHeight) / 2;
                boolean hov = mx >= panelX + PAD && mx < contentRight && my >= rowY && my < rowY + ROW_H;

                g.fill(panelX + PAD, rowY, contentRight, rowY + ROW_H,
                        i % 2 == 0 ? 0x18FFFFFF : 0x08FFFFFF);

                boolean uploading = uploadingRow == idx;
                if (!uploading && hov && my >= mbY && my < mbY + btnH && mx >= mbX && mx < mbX + btnW) {
                    hoveredRow = i; hoveredBtn = 0;
                }

                g.drawString(font, truncate(stripExt(f.getName()), nameMaxW), panelX + PAD + 3, textY, 0xEEEEEE, false);
                g.drawCenteredString(font, fmtDate(f.lastModified()), statsCenter, textY, 0x888888);

                if (uploading) {
                    g.fill(mbX, mbY, mbX + btnW, mbY + btnH, 0xFF333333);
                    g.drawCenteredString(font, "…", mbX + btnW / 2, mbY + (btnH - font.lineHeight) / 2, 0xFF888888);
                } else {
                    boolean hBtn = hoveredRow == i && hoveredBtn == 0;
                    g.fill(mbX, mbY, mbX + btnW, mbY + btnH, hBtn ? 0xFF44AA44 : 0xFF225522);
                    g.drawCenteredString(font, convertText, mbX + btnW / 2, mbY + (btnH - font.lineHeight) / 2, 0xFF88FF88);
                }
            }
        }
        g.disableScissor();
        maxScrollRow = Math.max(0, filteredFiles.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    // ── Source 文件上传 ───────────────────────────────────────────────────

    private void uploadSchematic(int idx) {
        if (idx < 0 || idx >= schematicFiles.size()) return;
        File f = schematicFiles.get(idx);
        uploadingRow = idx;
        BlueprintData data;
        try {
            data = f.getName().endsWith(".litematic")
                    ? LitematicaImporter.readLitematic(f, stripExt(f.getName()), myName())
                    : SchematicImporter.readSchematic(f, stripExt(f.getName()), myName());
        } catch (Exception e) {
            uploadingRow = -1;
            errorToast = Component.translatable("spatial_blueprint.gui.upload.error.parse_failed");
            NotifySound.clientError();
            return;
        }
        if (data == null) {
            uploadingRow = -1;
            errorToast = Component.translatable("spatial_blueprint.gui.upload.error.parse_failed");
            NotifySound.clientError();
            return;
        }
        data.category = "template";
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_UploadProjectionTemplateMessage(data));
    }

    private void refreshSourceFiles() {
        if ("source".equals(activeTab)) {
            schematicFiles = new ArrayList<>(SchematicImporter.listSchematics());
            schematicFiles.addAll(LitematicaImporter.listLitematics());
            schematicFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        } else {
            schematicFiles = new ArrayList<>();
        }
        rebuildFiltered();
    }

    private void rebuildFiltered() {
        filteredFiles = new ArrayList<>(schematicFiles);
        if (!searchQuery.isEmpty()) {
            String q = searchQuery.toLowerCase(java.util.Locale.ROOT);
            filteredFiles.removeIf(f -> !stripExt(f.getName()).toLowerCase(java.util.Locale.ROOT).contains(q));
        }
        int vis = Math.max(1, (getListBottom() - getListTop()) / ROW_H);
        maxScrollRow = Math.max(0, filteredFiles.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    private int getListTop()    { return panelY + 58; }
    private int getListBottom() { return panelY + PANEL_H - 26; }

    private String myName() {
        LocalPlayer lp = Minecraft.getInstance().player;
        return lp != null ? lp.getGameProfile().getName() : "";
    }

    private String truncate(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "…") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String fmtDate(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("M/d HH:mm").format(new Date(ms));
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
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
