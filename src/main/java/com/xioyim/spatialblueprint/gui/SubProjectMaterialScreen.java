package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 子工程材料清单编辑界面（规划模式 ☄ 打开）。
 *
 * <p>版式照 {@code PlacementScreen} 的材料面板（常量与画法照抄一份，不改它）：4 列、
 * 格 70×17、可见 6 行、图标原生 16×16、数量文字 0.75 缩放、面板宽 298 居中。</p>
 *
 * <p>所有增 / 删 / 改数量都只改本界面的<b>工作副本</b>；点 [确定] 把副本交回父界面的
 * {@code pendingMaterials}（与起始清单一致时父界面自动撤销暂存），点 [取消] / ESC 丢弃。
 * 打开时不再向服务端要数据（父界面已保证缓存里有）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class SubProjectMaterialScreen extends Screen implements ErrorToastSupport {

    // ── 版式常量：与 PlacementScreen 材料面板一致（照抄，勿各写一份）────────────
    private static final int MAT_COLS = 4;
    private static final int MAT_PANEL_W = 298;
    private static final int MAT_PAD = 8;
    private static final int MAT_HEADER_H = 12;
    private static final int MAT_SEP_PAD = 4;
    private static final int MAT_CONTENT_W = MAT_PANEL_W - MAT_PAD * 2; // 282
    private static final int MAT_CELL_W = MAT_CONTENT_W / MAT_COLS;     // 70
    private static final int MAT_CELL_H = 17;
    private static final int MAT_VISIBLE = 6;
    private static final int MAT_ICON_SCR = 16;
    private static final float MAT_SCALE = 0.75f;
    private static final int BOT_H = 16;

    private final ProjectBlueprintScreen parent;
    private final int entryIndex;
    private final String entryLabel;

    /** 工作副本：本界面所有编辑都只改它。 */
    private final MaterialList working;

    private int scrollRow = 0;
    private int maxScrollRow = 0;

    private int panelX;
    private int panelY;
    private int panelH;
    private int contentY;

    /** 数量编辑弹窗（null = 关闭）。 */
    @Nullable private String editKey = null;
    private final StringBuilder editInput = new StringBuilder();
    /** 删除确认弹窗（null = 关闭）。 */
    @Nullable private String deleteKey = null;
    @Nullable private String deleteName = null;
    /** 右键菜单（null = 关闭）。 */
    @Nullable private String ctxKey = null;
    private int ctxX, ctxY;

    /** 对话框按钮（原版 Button，手动 render / 命中，不加入 renderables —— 与 PlacementScreen 同一套做法）。 */
    @Nullable private Button editOkBtn, editCancelBtn, deleteOkBtn, deleteCancelBtn;

    @Nullable private Component errorToast;
    /** 悬浮格子的 ItemStack（渲染 tooltip 用）。 */
    private ItemStack hoveredStack = ItemStack.EMPTY;

    public SubProjectMaterialScreen(ProjectBlueprintScreen parent,
                                    S2C_ProjectStatusMessage.EntryStatus entry,
                                    MaterialList initial) {
        super(Component.translatable("spatial_blueprint.gui.subproject_material.title",
                entry.label() == null || entry.label().isEmpty()
                        ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                        : entry.label()));
        this.parent = parent;
        this.entryIndex = entry.index();
        this.entryLabel = entry.label() == null ? "" : entry.label();
        this.working = initial == null ? new MaterialList() : initial.copy();
        this.working.blueprintName = entryLabel;
    }

    // ── 布局 ──────────────────────────────────────────────────────────────

    private int contentHeight() {
        return MAT_VISIBLE * MAT_CELL_H;
    }

    @Override
    protected void init() {
        super.init();
        hoveredStack = ItemStack.EMPTY;

        // 对话框按钮（原版 Button，手动 render / 命中，不加入 renderables）
        editOkBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.confirm"),
                        b -> confirmEditCount())
                .bounds(0, 0, 60, 20).build();
        editCancelBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.cancel"),
                        b -> { editKey = null; editInput.setLength(0); })
                .bounds(0, 0, 60, 20).build();
        deleteOkBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.confirm"),
                        b -> confirmDelete())
                .bounds(0, 0, 60, 20).build();
        deleteCancelBtn = Button.builder(Component.translatable("spatial_blueprint.gui.common.cancel"),
                        b -> { deleteKey = null; deleteName = null; })
                .bounds(0, 0, 60, 20).build();

        panelX = this.width / 2 - MAT_PANEL_W / 2;
        panelH = MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD + contentHeight()
                + MAT_SEP_PAD + BOT_H + MAT_PAD;
        panelY = Math.max(4, (this.height - panelH) / 2);
        contentY = panelY + MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD;

        int totalRows = (working.blockCounts.size() + MAT_COLS - 1) / MAT_COLS;
        maxScrollRow = Math.max(0, totalRows - MAT_VISIBLE);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScrollRow));

        int botY = panelY + panelH - MAT_PAD - BOT_H;

        // ✚ 新增（左）
        Button addBtn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.subproject_material.btn_add"),
                        b -> Minecraft.getInstance().setScreen(
                                new MaterialItemPickerScreen(this, this::addMaterial)))
                .bounds(panelX + MAT_PAD, botY, MAT_CELL_H, BOT_H)
                .build());
        addBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("spatial_blueprint.gui.subproject_material.btn_add")));

        // [确定] [取消]（右）
        Component okLabel = Component.translatable("spatial_blueprint.gui.subproject_material.btn_ok");
        Component cancelLabel = Component.translatable("spatial_blueprint.gui.subproject_material.btn_cancel");
        int okW = font.width(okLabel) + 12;
        int cancelW = font.width(cancelLabel) + 12;
        addRenderableWidget(Button.builder(okLabel, b -> confirm())
                .bounds(panelX + MAT_PANEL_W - MAT_PAD - okW, botY, okW, BOT_H)
                .build());
        addRenderableWidget(Button.builder(cancelLabel, b -> Minecraft.getInstance().setScreen(parent))
                .bounds(panelX + MAT_PANEL_W - MAT_PAD - okW - 4 - cancelW, botY, cancelW, BOT_H)
                .build());
    }

    /** [确定]：把工作副本交回父界面，由父界面决定暂存 / 撤销暂存。 */
    private void confirm() {
        // 双保险：清单里若有非法方块（例如历史遗留数据），[确定] 也不放行 ——
        // 与「添加确认」同一口径；服务端提交处仍会复判。
        for (String key : working.blockCounts.keySet()) {
            if (isIllegalMaterial(key)) {
                com.xioyim.spatialblueprint.util.NotifySound.clientError();
                setErrorToast(Component.translatable("spatial_blueprint.error.illegal_material"));
                return;
            }
        }
        parent.applyMaterialEdit(entryIndex, working);
        Minecraft.getInstance().setScreen(parent);
    }

    /**
     * 材料是否非法（禁用方块）—— 与 {@code C2S_SubmitProjectChangesMessage} 第 7 道校验
     * 及 {@code C2S_SaveMaterialListMessage} 同口径：复合键先还原注册 ID，再查服务端
     * 配置镜像 {@code EXCLUDED_BLOCKS}（{@link com.xioyim.spatialblueprint.client.ConfigAccess}）。
     */
    private static boolean isIllegalMaterial(String materialKey) {
        return com.xioyim.spatialblueprint.client.ConfigAccess
                .getList(com.xioyim.spatialblueprint.BlueprintConfig.EXCLUDED_BLOCKS)
                .contains(MaterialKey.idOf(materialKey));
    }

    // ── 编辑动作（只改工作副本）────────────────────────────────────────────

    private void addMaterial(String materialKey, @Nullable net.minecraft.nbt.CompoundTag normTag, int count) {
        if (count <= 0) return;
        // 非法方块在「添加确认」这一刻直接拦截（服务端提交处仍保留同口径复判，防改包）。
        if (isIllegalMaterial(materialKey)) {
            com.xioyim.spatialblueprint.util.NotifySound.clientError();
            setErrorToast(Component.translatable("spatial_blueprint.error.illegal_material"));
            return;
        }
        working.blockCounts.merge(materialKey, count, Integer::sum);
        if (normTag != null) working.keyTags.putIfAbsent(materialKey, normTag);
        rebuildWidgets();
    }

    private void confirmEditCount() {
        if (editKey == null) return;
        try {
            int newCount = Integer.parseInt(editInput.toString());
            if (newCount > 0) {
                working.blockCounts.put(editKey, newCount);
            }
        } catch (NumberFormatException ignored) {
            // 非法输入 = 不改
        }
        editKey = null;
        editInput.setLength(0);
        rebuildWidgets();
    }

    private void confirmDelete() {
        if (deleteKey == null) return;
        working.blockCounts.remove(deleteKey);
        working.keyTags.remove(deleteKey);
        deleteKey = null;
        deleteName = null;
        rebuildWidgets();
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, font, this.width, this.height, errorToast);
            return;
        }

        hoveredStack = ItemStack.EMPTY;

        // ── 面板 ──────────────────────────────────────────────────────────
        graphics.fill(panelX, panelY, panelX + MAT_PANEL_W, panelY + panelH, 0xF0101010);
        drawBorder(graphics, panelX, panelY, MAT_PANEL_W, panelH);

        // 标题行：标题 + 右侧「条目数 / 总数」
        String title = this.title.getString();
        String sub = working.blockCounts.size() + " / " + working.totalCount();
        graphics.drawString(font, title, panelX + MAT_PAD, panelY + MAT_PAD, 0xFFFFAA, false);
        graphics.drawString(font, sub,
                panelX + MAT_PANEL_W - MAT_PAD - font.width(sub),
                panelY + MAT_PAD, 0x888888, false);

        int sepY = panelY + MAT_PAD + MAT_HEADER_H;
        graphics.fill(panelX + MAT_PAD, sepY, panelX + MAT_PANEL_W - MAT_PAD, sepY + 1, 0xFF444444);

        // ── 内容区 ────────────────────────────────────────────────────────
        int contentH = contentHeight();
        graphics.fill(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH, 0x801A1A1A);
        graphics.enableScissor(panelX + MAT_PAD, contentY,
                panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH);

        if (working.blockCounts.isEmpty()) {
            Component empty = Component.translatable("spatial_blueprint.gui.subproject_material.empty");
            graphics.drawString(font, empty,
                    panelX + MAT_PAD + (MAT_CONTENT_W - font.width(empty)) / 2,
                    contentY + (contentH - font.lineHeight) / 2,
                    0x888888, false);
        }

        int i = 0;
        for (Map.Entry<String, Integer> entry : working.blockCounts.entrySet()) {
            int row = i / MAT_COLS;
            int col = i % MAT_COLS;
            i++;

            int cellX = panelX + MAT_PAD + col * MAT_CELL_W;
            int cellY = contentY + (row - scrollRow) * MAT_CELL_H;
            if (cellY + MAT_CELL_H <= contentY || cellY >= contentY + contentH) continue;

            ItemStack displayStack = working.getDisplayStack(entry.getKey());
            int iconY = cellY + (MAT_CELL_H - MAT_ICON_SCR) / 2;
            SafeItemRenderer.render(graphics, displayStack, cellX, iconY);

            // 数量（0.75 缩放，与 PlacementScreen 一致）
            graphics.pose().pushPose();
            graphics.pose().scale(MAT_SCALE, MAT_SCALE, 1f);
            int tx = Math.round((cellX + MAT_ICON_SCR + 2) / MAT_SCALE);
            int ty = Math.round((cellY + (MAT_CELL_H - font.lineHeight * MAT_SCALE) / 2f) / MAT_SCALE);
            graphics.drawString(font, String.valueOf(entry.getValue()), tx, ty, 0xFFAA00, false);
            graphics.pose().popPose();

            if (hoveredStack.isEmpty()
                    && mouseX >= cellX && mouseX < cellX + MAT_ICON_SCR
                    && mouseY >= iconY && mouseY < iconY + MAT_ICON_SCR) {
                hoveredStack = displayStack;
            }
        }
        graphics.disableScissor();

        super.render(graphics, mouseX, mouseY, partialTick);

        if (hoveredStack != null && !hoveredStack.isEmpty()
                && deleteKey == null && editKey == null && ctxKey == null) {
            graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
        }

        // ── 悬浮 overlay（每次只显示一个，按优先级：删除确认 > 数量编辑 > 右键菜单）
        if (deleteKey != null) {
            renderDeletePanel(graphics, mouseX, mouseY);
        } else if (editKey != null) {
            renderEditPanel(graphics, mouseX, mouseY);
        } else if (ctxKey != null) {
            renderContextMenu(graphics, mouseX, mouseY);
        }
    }

    private void renderEditPanel(GuiGraphics g, int mouseX, int mouseY) {
        final int EW = 200, EH = 90, BW = 60, BH = 20, BP = 8, PAD = 8;
        int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(ex, ey, ex + EW, ey + EH, 0xF0101010);
        drawBorder(g, ex, ey, EW, EH);
        g.drawString(font,
                Component.translatable("spatial_blueprint.gui.subproject_material.edit_count").getString(),
                ex + PAD, ey + PAD, 0xFFFFAA, false);
        String name = displayNameOf(editKey);
        g.drawString(font, truncate(name, EW - PAD * 2), ex + PAD, ey + PAD + font.lineHeight + 3,
                0xDDDDDD, false);

        int fx = ex + PAD, fy = ey + PAD + (font.lineHeight + 3) * 2, fw = EW - PAD * 2, fh = 16;
        g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF444444);
        g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A1A1A);
        String cursor = (System.currentTimeMillis() % 1000 < 500) ? "|" : "";
        g.drawString(font, editInput.toString() + cursor, fx + 4,
                fy + (fh - font.lineHeight) / 2, 0xFFFFFF, false);

        layoutAndRenderDialogButtons(g, mouseX, mouseY, editOkBtn, editCancelBtn, ex, ey, EW, EH, BW, BH, BP);
        g.pose().popPose();
    }

    private void renderDeletePanel(GuiGraphics g, int mouseX, int mouseY) {
        final int DW = 200, DH = 80, BW = 60, BH = 20, BP = 8, PAD = 8;
        int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);
        g.drawString(font,
                Component.translatable("spatial_blueprint.gui.subproject_material.delete_confirm",
                        deleteName != null ? deleteName : "").getString(),
                dx + PAD, dy + PAD, 0xFF5555, false);

        layoutAndRenderDialogButtons(g, mouseX, mouseY, deleteOkBtn, deleteCancelBtn, dx, dy, DW, DH, BW, BH, BP);
        g.pose().popPose();
    }

    /** 右键菜单（与 PlacementScreen 材料面板同款：仅 [修改数量] [删除]）。 */
    private void renderContextMenu(GuiGraphics g, int mouseX, int mouseY) {
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
        g.pose().translate(0, 0, 300);
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

    /**
     * 摆好一个对话框的 [确定] [取消] 两个原版 Button 并手动渲染 ——
     * 不加入 renderables（overlay 必须画在所有控件之上），命中在 {@code mouseClicked} 里
     * 调 {@code Button#mouseClicked} 处理。与 PlacementScreen 同名方法同一套做法，改一边也要看另一边。
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

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    private String displayNameOf(@Nullable String materialKey) {
        if (materialKey == null) return "";
        ItemStack stack = working.getDisplayStack(materialKey);
        return stack.isEmpty() ? MaterialKey.idOf(materialKey) : stack.getHoverName().getString();
    }

    private String truncate(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "…") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ── 输入 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) {
            errorToast = null;
            return true;
        }

        // 弹窗优先（吃掉框内点击，框外点击关闭；按钮命中由原版 Button 自己处理）
        if (deleteKey != null) {
            if (deleteOkBtn != null && deleteOkBtn.mouseClicked(mouseX, mouseY, button)) return true;
            if (deleteCancelBtn != null && deleteCancelBtn.mouseClicked(mouseX, mouseY, button)) return true;
            final int DW = 200, DH = 80;
            int dx = (this.width - DW) / 2, dy = (this.height - DH) / 2;
            if (mouseX < dx || mouseX >= dx + DW || mouseY < dy || mouseY >= dy + DH) {
                deleteKey = null;
                deleteName = null;
            }
            return true;
        }
        if (editKey != null) {
            if (editOkBtn != null && editOkBtn.mouseClicked(mouseX, mouseY, button)) return true;
            if (editCancelBtn != null && editCancelBtn.mouseClicked(mouseX, mouseY, button)) return true;
            final int EW = 200, EH = 90;
            int ex = (this.width - EW) / 2, ey = (this.height - EH) / 2;
            if (mouseX < ex || mouseX >= ex + EW || mouseY < ey || mouseY >= ey + EH) {
                editKey = null;
                editInput.setLength(0);
            }
            return true;
        }

        // 右键菜单：命中执行，点任意处关闭
        if (ctxKey != null) {
            final int CM_ROWS = 2;
            final int CMW = 130, CM_ROW_H = 16, CM_PAD = 4;
            final int CMH = CM_PAD + CM_ROWS * CM_ROW_H + CM_PAD;
            int cx2 = Math.min(ctxX, this.width - CMW - 2);
            int cy2 = Math.min(ctxY, this.height - CMH - 2);
            if (mouseX >= cx2 && mouseX < cx2 + CMW && mouseY >= cy2 && mouseY < cy2 + CMH) {
                int row = (int) ((mouseY - cy2 - CM_PAD) / CM_ROW_H);
                if (row >= 0 && row < CM_ROWS) {
                    switch (row) {
                        case 0 -> {
                            editKey = ctxKey;
                            editInput.setLength(0);
                            editInput.append(working.blockCounts.getOrDefault(ctxKey, 0));
                        }
                        case 1 -> {
                            deleteKey = ctxKey;
                            deleteName = displayNameOf(ctxKey);
                        }
                    }
                }
            }
            ctxKey = null;
            return true;
        }

        // 材料格子：右键弹出菜单（与 PlacementScreen 材料面板同款；左键不动作）
        if (button == 1) {
            int cellIndex = cellIndexAt(mouseX, mouseY);
            if (cellIndex >= 0) {
                String key = keyAt(cellIndex);
                if (key != null) {
                    ctxKey = key;
                    ctxX = (int) mouseX;
                    ctxY = (int) mouseY;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中的格子序号（按 working 的插入顺序）；没命中返回 -1。 */
    private int cellIndexAt(double mouseX, double mouseY) {
        int contentH = contentHeight();
        if (mouseY < contentY || mouseY >= contentY + contentH) return -1;
        if (mouseX < panelX + MAT_PAD || mouseX >= panelX + MAT_PAD + MAT_CONTENT_W) return -1;
        int col = (int) ((mouseX - (panelX + MAT_PAD)) / MAT_CELL_W);
        int row = (int) ((mouseY - contentY) / MAT_CELL_H) + scrollRow;
        int index = row * MAT_COLS + col;
        return index >= 0 && index < working.blockCounts.size() ? index : -1;
    }

    @Nullable
    private String keyAt(int cellIndex) {
        int i = 0;
        for (String key : working.blockCounts.keySet()) {
            if (i == cellIndex) return key;
            i++;
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (deleteKey != null || editKey != null || ctxKey != null) return true;
        if (mouseX >= panelX && mouseX < panelX + MAT_PANEL_W
                && mouseY >= contentY && mouseY < contentY + contentHeight()) {
            scrollRow = Math.max(0, Math.min(maxScrollRow,
                    scrollRow - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        if (editKey != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmEditCount();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editKey = null;
                editInput.setLength(0);
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && editInput.length() > 0) {
                editInput.deleteCharAt(editInput.length() - 1);
            }
            return true;
        }
        if (deleteKey != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                deleteKey = null;
                deleteName = null;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (ctxKey != null) { ctxKey = null; return true; }
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (editKey != null) {
            if (Character.isDigit(c) && editInput.length() < 9) editInput.append(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
