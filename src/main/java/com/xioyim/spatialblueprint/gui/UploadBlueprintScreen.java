package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_UploadBeginMessage;
import com.xioyim.spatialblueprint.network.C2S_UploadChunkMessage;
import com.xioyim.spatialblueprint.network.C2S_UploadProjectionTemplateMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.LitematicaImporter;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.util.SchematicImporter;
import com.xioyim.spatialblueprint.util.SpongeSchematicImporter;
import com.xioyim.spatialblueprint.util.StructureFileScanner;
import com.xioyim.spatialblueprint.util.StructureNbtImporter;
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
        "spatial_blueprint.gui.upload.tab.other"
    };
    private static final String[] TAB_TOOLTIP_KEYS = {
        "spatial_blueprint.gui.upload.tab.space.tooltip",
        "spatial_blueprint.gui.upload.tab.projection.tooltip",
        "spatial_blueprint.gui.upload.tab.other.tooltip"
    };

    private final Screen parent;
    @Nullable private String activeTab = null;
    @Nullable private Component errorToast = null;

    private List<File> schematicFiles = new ArrayList<>();
    private List<File> filteredFiles  = new ArrayList<>();
    private int        uploadingRow   = -1;

    /** 最近一次发起上传的本地文件信息 —— 服务端看不到原始文件名/扩展名/磁盘大小，
     *  报告页由本页直接传入（不走网络）。 */
    private String pendingFileName = "";
    private String pendingFileExt  = "";
    private long   pendingFileSize = 0L;

    private int    scrollRow    = 0;
    private int    maxScrollRow = 0;
    private String searchQuery  = "";

    @Nullable private EditBox searchBox = null;
    private int panelX, panelY;

    private int hoveredRow = -1;
    /** 当前选中的文件行（列表索引）；-1 = 未选中。 */
    private int selectedRow = -1;
    /** 本帧名字列命中的行（tooltip：蓝图元数据）；-1 = 无。 */
    private int hoverNameIdx = -1;
    /** 本帧日期列命中的行（tooltip：备注）；-1 = 无。 */
    private int hoverDateIdx = -1;

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
        // 空间蓝图 tab 仅创造可用：生存置灰不可点
        if (!isCreative() && "space".equals(activeTab)) {
            activeTab = null;
            scrollRow = 0;
        }
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
            // 空间蓝图 tab 生存模式置灰（服务端另有权限复核）
            btn.active = "space".equals(tab) && !isCreative()
                    ? false
                    : !tab.equals(activeTab);
        }

        // ── 底部按钮行 ─────────────────────────────────────────────────
        // 右侧：18px 迷你方钮，右→左：[❌ 关闭] [♻ 刷新]；左侧：⏏/⛨/✈ 迷你按钮
        int botBtnY   = panelY + PANEL_H - 22;
        int rightEdge = panelX + PANEL_W - PAD;
        int miniSz    = 18;

        addRenderableWidget(Button.builder(
                Component.literal("❌"),
                b -> onClose())
            .bounds(rightEdge - miniSz, botBtnY, miniSz, miniSz)
            .build());

        addRenderableWidget(Button.builder(
                Component.literal("♻"),
                b -> { StructureFileScanner.invalidateCache();
                       refreshSourceFiles(); scrollRow = 0; clearWidgets(); init(); })
            .bounds(rightEdge - miniSz * 2 - 2, botBtnY, miniSz, miniSz)
            .build());

        // ── 底部迷你按钮：⏏ 生效；⛨ 占位不可点；✈ 仅 OP 显示可点（强制上传）────
        int miniY  = botBtnY;
        int miniX  = panelX + PAD;
        Button btnUpload = addRenderableWidget(Button.builder(
                        Component.literal("⏏"), b -> doUpload(false))
                .bounds(miniX, miniY, miniSz, miniSz)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.upload.btn.mini_upload")))
                .build());
        btnUpload.active = selectedRow >= 0;
        miniX += miniSz + 2;
        Button btnAudit = addRenderableWidget(Button.builder(
                        Component.literal("⛨"), b -> {})
                .bounds(miniX, miniY, miniSz, miniSz)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.upload.btn.mini_audit")))
                .build());
        btnAudit.active = false;   // 测试期未开放
        miniX += miniSz + 2;
        if (isOp()) {
            Button btnForce = addRenderableWidget(Button.builder(
                            Component.literal("✈"), b -> doUpload(true))
                    .bounds(miniX, miniY, miniSz, miniSz)
                    .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.upload.btn.mini_force")))
                    .build());
            btnForce.active = selectedRow >= 0;
        }

        // ── 搜索框（三个 tab 都有；按完整文件名匹配，搜 ".nbt" 能筛出全部该格式）──
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
        // 点击文件行：选中 / 取消选中
        if (button == 0 && activeTab != null && hoveredRow >= 0) {
            int idx = scrollRow + hoveredRow;
            selectedRow = selectedRow == idx ? -1 : idx;
            clearWidgets(); init();
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

        // 三个 tab 都是同一批本地文件，差别只在上传后存成哪种分类
        if (activeTab == null) {
            String hint = Component.translatable("spatial_blueprint.gui.library.select_hint").getString();
            int hintY = panelY + 38 + (PANEL_H - 38 - 26 - font.lineHeight) / 2;
            graphics.drawCenteredString(font, hint, panelX + PANEL_W / 2, hintY, 0x666666);
        } else {
            // 搜索框下分割线
            graphics.fill(panelX + 4, panelY + 56, panelX + PANEL_W - 4, panelY + 57, 0xFF444444);
            renderSourceRows(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip（名字 → 蓝图元数据；日期 → 备注）
        if (activeTab != null) {
            renderRowTooltips(graphics, mouseX, mouseY);
        }
    }

    // ── Source 行渲染 ─────────────────────────────────────────────────────

    private void renderSourceRows(GuiGraphics g, int mx, int my) {
        int listTop      = getListTop();
        int listBottom   = getListBottom();
        int contentRight = panelX + PANEL_W - PAD;
        int vis          = Math.max(1, (listBottom - listTop) / ROW_H);

        hoverNameIdx = -1;
        hoverDateIdx = -1;
        hoveredRow   = -1;

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
                int textY = rowY + (ROW_H - font.lineHeight) / 2;
                boolean hov = mx >= panelX + PAD && mx < contentRight && my >= rowY && my < rowY + ROW_H;
                if (hov) hoveredRow = i;

                // 行背景 + 选中高亮（整行点击选中/取消）
                boolean sel = selectedRow == idx;
                g.fill(panelX + PAD, rowY, contentRight, rowY + ROW_H,
                        sel ? 0x40FFFFFF : (i % 2 == 0 ? 0x18FFFFFF : 0x08FFFFFF));
                if (sel) {
                    g.fill(panelX + PAD, rowY,             contentRight,    rowY + 1,         0xFFFFFFFF);
                    g.fill(panelX + PAD, rowY + ROW_H - 1, contentRight,    rowY + ROW_H,     0xFFFFFFFF);
                    g.fill(panelX + PAD, rowY,             panelX + PAD + 1, rowY + ROW_H,    0xFFFFFFFF);
                    g.fill(contentRight - 1, rowY,         contentRight,    rowY + ROW_H,     0xFFFFFFFF);
                }

                // 名字（左）+ 创建时间（右侧，原按钮位，右对齐）
                String dateStr = fmtDate(f.lastModified());
                int dateW  = font.width(dateStr);
                int dateX  = contentRight - dateW - 3;
                int nameMaxW = Math.max(20, dateX - (panelX + PAD + 3) - 6);
                String disp = truncate(displayNameOf(f), nameMaxW);
                // FORCE 文件：红 + 加粗（去删除线，避免挡住名字）
                boolean force = StructureFileScanner.isForceCaptured(f);
                Component nameComp = force
                        ? Component.literal(disp).withStyle(ChatFormatting.RED,
                                ChatFormatting.BOLD)
                        : Component.literal(disp);
                g.drawString(font, nameComp, panelX + PAD + 3, textY,
                        force ? 0xFF7777 : 0xEEEEEE, false);
                g.drawString(font, dateStr, dateX, textY, 0x888888, false);

                // 悬浮记录（名字 → 蓝图元数据 tooltip；日期 → 备注 tooltip）
                if (hov && my >= textY && my < textY + font.lineHeight) {
                    int nameEnd = panelX + PAD + 3 + font.width(disp);
                    if (mx >= panelX + PAD + 3 && mx < nameEnd) {
                        hoverNameIdx = idx;
                    } else if (mx >= dateX && mx < dateX + dateW) {
                        hoverDateIdx = idx;
                    }
                }
            }
        }
        g.disableScissor();
        maxScrollRow = Math.max(0, filteredFiles.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    // ── Source 文件上传 ───────────────────────────────────────────────────

    private void doUpload(boolean forceRequested) {
        int idx = selectedRow;
        if (idx < 0 || idx >= filteredFiles.size()) return;
        File f = filteredFiles.get(idx);
        // 强制上传（仅 OP）不被 force_blocked 拦截，允许上传 FORCE 蓝图
        if (!forceRequested && StructureFileScanner.isForceCaptured(f)) {
            errorToast = Component.translatable("spatial_blueprint.gui.upload.error.force_blocked");
            NotifySound.clientError();
            return;
        }
        if (forceRequested && !isOp()) {
            errorToast = Component.translatable("spatial_blueprint.error.upload_force_no_permission");
            NotifySound.clientError();
            return;
        }
        uploadingRow = idx;
        // 蓝图名：空间/投影 tab 直接用文件内部的实际蓝图名（与列表显示一致），
        // 其他结构文件去掉后缀（列表显示带后缀只是为了让玩家看清格式）。
        String bpName;
        if ("space".equals(activeTab) || "projection".equals(activeTab)) {
            String inner = StructureFileScanner.nameOf(f);
            bpName = inner.isEmpty() ? stripExt(f.getName()) : inner;
        } else {
            bpName = stripExt(f.getName());
        }
        BlueprintData data;
        try {
            data = switch (StructureFileScanner.extensionOf(f.getName())) {
                case ".litematic" -> LitematicaImporter.readLitematic(f, bpName, myName());
                case ".nbt"       -> StructureNbtImporter.readStructureNbt(f, bpName, myName());
                case ".schem"     -> SpongeSchematicImporter.readSchem(f, bpName, myName());
                case ".schematic" -> SchematicImporter.readSchematic(f, bpName, myName());
                default           -> throw new com.xioyim.spatialblueprint.util.StructureImportException(
                        "spatial_blueprint.gui.upload.error.unsupported_format");
            };
        } catch (com.xioyim.spatialblueprint.util.StructureImportException e) {
            // 新格式的失败原因不止一种（版本不支持 / 读不进来 / 根本不是这个格式），
            // 统一报「解析失败」玩家查不出问题，这里直接把具体原因显示出来
            uploadingRow = -1;
            errorToast = Component.translatable(e.failKey());
            NotifySound.clientError();
            return;
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
        // 目标分类由当前 tab 决定：空间 / 投影 / 投影模板。
        // 服务端会按这个分类取体积上限与槽位上限，并强制覆写 data.category。
        data.category = categoryForTab();
        pendingFileName = f.getName();
        pendingFileExt  = StructureFileScanner.extensionOf(f.getName());
        pendingFileSize = f.length();
        // SB-013：压缩字节 ≤30KB 走旧单包（行为不变）；超过走分片
        // （Begin 声明 + 逐块 30KB，组完走与单包相同的服务端全流程）。
        byte[] compressed = C2S_UploadProjectionTemplateMessage.compressPayload(data);
        if (compressed.length <= C2S_UploadBeginMessage.SINGLE_PACKET_LIMIT) {
            BlueprintNetwork.CHANNEL.sendToServer(
                    C2S_UploadProjectionTemplateMessage.fromCompressed(
                            compressed, data.category, forceRequested));
        } else {
            // 客户端先按镜像配置做快速失败：超限就不发 Begin，
            // 避免服务端拒收后每个块各回一个 staging_rejected 刷屏。
            long maxBytes;
            try {
                maxBytes = Math.max(1L, com.xioyim.spatialblueprint.client.ConfigAccess.get(
                        com.xioyim.spatialblueprint.BlueprintConfig.MAX_UPLOAD_FILE_BYTES));
            } catch (Exception ignored) {
                maxBytes = 8L * 1024L * 1024L;
            }
            if (compressed.length > maxBytes) {
                uploadingRow = -1;
                errorToast = Component.translatable("spatial_blueprint.error.upload_too_large");
                NotifySound.clientError();
                return;
            }
            int total = (compressed.length + C2S_UploadBeginMessage.CHUNK_SIZE - 1)
                    / C2S_UploadBeginMessage.CHUNK_SIZE;
            if (total > C2S_UploadBeginMessage.MAX_CHUNKS) {
                uploadingRow = -1;
                errorToast = Component.translatable("spatial_blueprint.error.upload_too_large");
                NotifySound.clientError();
                return;
            }
            java.util.UUID uploadId = java.util.UUID.randomUUID();
            BlueprintNetwork.CHANNEL.sendToServer(new C2S_UploadBeginMessage(
                    uploadId, total, compressed.length, data.category, forceRequested));
            for (int i = 0; i < total; i++) {
                int from = i * C2S_UploadBeginMessage.CHUNK_SIZE;
                int to = Math.min(from + C2S_UploadBeginMessage.CHUNK_SIZE, compressed.length);
                BlueprintNetwork.CHANNEL.sendToServer(new C2S_UploadChunkMessage(
                        uploadId, i, java.util.Arrays.copyOfRange(compressed, from, to)));
            }
        }
        // 立刻切到「上传中」报告页：扫描结果回来后由 S2C_UploadReportMessage 换成真实报告
        Minecraft.getInstance().setScreen(new UploadReportScreen(this, categoryForTab(),
                pendingFileName, pendingFileExt, pendingFileSize, forceRequested));
    }

    // ── 行 tooltip（名字 → 蓝图元数据；日期 → 备注）──────────────────────

    private void renderRowTooltips(GuiGraphics g, int mx, int my) {
        if (hoverNameIdx >= 0 && hoverNameIdx < filteredFiles.size()) {
            File f = filteredFiles.get(hoverNameIdx);
            if (("space".equals(activeTab) || "projection".equals(activeTab))
                    && StructureFileScanner.metaOf(f).isOwnModFile()) {
                g.renderTooltip(font, buildMetaTooltip(f), mx, my);
            } else {
                g.renderTooltip(font, Component.literal(f.getName()), mx, my);
            }
            return;
        }
        if (hoverDateIdx >= 0 && hoverDateIdx < filteredFiles.size()) {
            File f = filteredFiles.get(hoverDateIdx);
            if (("space".equals(activeTab) || "projection".equals(activeTab))
                    && StructureFileScanner.metaOf(f).isOwnModFile()) {
                StructureFileScanner.FileMeta meta = StructureFileScanner.metaOf(f);
                List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
                if (!meta.noteShort().isEmpty()) {
                    lines.add(Component.literal(meta.noteShort())
                            .withStyle(ChatFormatting.WHITE).getVisualOrderText());
                }
                if (!meta.noteLong().isEmpty()) {
                    lines.add(Component.literal(meta.noteLong())
                            .withStyle(ChatFormatting.GRAY).getVisualOrderText());
                }
                if (!lines.isEmpty()) g.renderTooltip(font, lines, mx, my);
            }
        }
    }

    /** 与蓝图列表一致的摘要 tooltip；FORCE 文件名字红+加粗 + (非OP禁止上传)。 */
    private List<net.minecraft.util.FormattedCharSequence> buildMetaTooltip(File f) {
        StructureFileScanner.FileMeta meta = StructureFileScanner.metaOf(f);
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        boolean force = meta.isForce();
        String name = meta.name().isEmpty() ? stripExt(f.getName()) : meta.name();
        net.minecraft.network.chat.MutableComponent title = Component.literal(name);
        if (force) {
            title.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            title.append(Component.translatable("spatial_blueprint.gui.upload.meta.force_suffix")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else {
            title.withStyle(ChatFormatting.WHITE);
        }
        lines.add(title.getVisualOrderText());

        lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.material", meta.materialCount())
                .withStyle(ChatFormatting.GREEN).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.duration",
                meta.animDuration() > 0 ? String.valueOf(meta.animDuration()) : "-")
                .withStyle(ChatFormatting.AQUA).getVisualOrderText());

        // 保存模式：SAFE / SANITIZE / FORCE(管理员强制保存 + 禁止上传)
        if (meta.saveMode() >= 0) {
            ChatFormatting modeColor = ChatFormatting.WHITE;
            String modeStr;
            switch (meta.saveMode()) {
                case 2 -> {
                    modeStr = Component.translatable("spatial_blueprint.gui.report.save_mode_force").getString()
                            + Component.translatable("spatial_blueprint.gui.upload.meta.force_suffix").getString();
                    modeColor = ChatFormatting.RED;
                }
                case 1 -> modeStr = Component.translatable("spatial_blueprint.gui.report.save_mode_sanitize").getString();
                default -> modeStr = Component.translatable("spatial_blueprint.gui.report.save_mode_safe").getString();
            }
            lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.mode", modeStr)
                    .withStyle(modeColor).getVisualOrderText());
        }
        if (!meta.creatorName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.creator", meta.creatorName())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
        }
        if (!meta.sourceName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.source", meta.sourceName())
                    .withStyle(ChatFormatting.GOLD).getVisualOrderText());
        }
        if (meta.createdAt() > 0) {
            lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.time",
                    BlueprintTooltips.formatDateFull(meta.createdAt()))
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        return lines;
    }

    private boolean isOp() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.hasPermissions(2);
    }

    private boolean isCreative() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.isCreative();
    }

    public String getPendingFileName() { return pendingFileName; }
    public String getPendingFileExt()  { return pendingFileExt; }
    public long   getPendingFileSize() { return pendingFileSize; }

    /** 当前 tab 对应的蓝图分类，供报告页显示体积上限。 */
    public String getActiveCategory() { return categoryForTab(); }

    /** 当前 tab 对应的蓝图分类。 */
    private String categoryForTab() {
        return switch (activeTab == null ? "" : activeTab) {
            case "space"      -> "create";
            case "projection" -> "project";
            default           -> "template";
        };
    }

    /**
     * 重新扫描 {@code .minecraft/schematics/} 并按当前 tab 过滤。
     *
     * <p>分类判据是文件顶层的 {@code SpatialBlueprintData.category} —— 只有本 mod 自己
     * 导出过的蓝图才有这个字段。前两个 tab 各收自己那一类，「其他结构文件」只收
     * <b>没有这个字段的</b>，也就是不属于本 mod 的结构文件。</p>
     *
     * <p>⚠ 因此带着 dimensional / engineering / template 字段的自家文件三个 tab 都不收 ——
     * 那几类本来也不该从 schematics 目录上传。</p>
     */
    private void refreshSourceFiles() {
        if (activeTab == null) {
            schematicFiles = new ArrayList<>();
            rebuildFiltered();
            return;
        }
        List<File> all = StructureFileScanner.listAll();
        schematicFiles = new ArrayList<>();
        for (File f : all) {
            if (tabAccepts(StructureFileScanner.categoryOf(f))) schematicFiles.add(f);
        }
        rebuildFiltered();
    }

    /**
     * 当前 tab 是否收这个分类的文件。
     *
     * <p>「其他结构文件」的语义是<b>不属于本 mod 的结构文件</b> —— 只要带着
     * {@code category} 字段（不管是空间、投影，还是次元 / 工程 / 模板）就说明是本 mod
     * 自己导出的，一概不收。留在那儿的只有别人做的 .litematic 与 .nbt / .schem /
     * .schematic 这些外来格式。</p>
     */
    private boolean tabAccepts(String category) {
        return switch (activeTab == null ? "" : activeTab) {
            case "space"      -> "create".equals(category);
            case "projection" -> "project".equals(category);
            default           -> category.isEmpty();
        };
    }

    private void rebuildFiltered() {
        filteredFiles = new ArrayList<>(schematicFiles);
        if (!searchQuery.isEmpty()) {
            String q = searchQuery.toLowerCase(java.util.Locale.ROOT);
            // 同时匹配文件名与列表里实际显示的名字（空间/投影 tab 显示的是文件内部名）
            filteredFiles.removeIf(f -> {
                String fn = f.getName().toLowerCase(java.util.Locale.ROOT);
                String dn = displayNameOf(f).toLowerCase(java.util.Locale.ROOT);
                return !fn.contains(q) && !dn.contains(q);
            });
        }
        int vis = Math.max(1, (getListBottom() - getListTop()) / ROW_H);
        maxScrollRow = Math.max(0, filteredFiles.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    /**
     * 列表里显示的名字：空间 / 投影 两个上传 tab 收的是本 mod 导出的 .litematic，
     * 显示文件内部的真实蓝图名（{@code Metadata.Name}）；其余 tab 显示完整文件名。
     * 内部名读不到时退回文件名，保证永远有字可显。
     */
    private String displayNameOf(File f) {
        if ("space".equals(activeTab) || "projection".equals(activeTab)) {
            String inner = StructureFileScanner.nameOf(f);
            if (!inner.isEmpty()) return inner;
        }
        return f.getName();
    }

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
