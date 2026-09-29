package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置 · 方块替换表（blockSubstitutions）：网格布局选择器。
 *
 * <p>横向每排 {@link #COLS} 条（分割线分隔），纵向最多 {@link #MAX_ROWS} 排，整体滚动。<br>
 * 每条：{@code [源图标] 转化为 [物品槽位 ×5]}——已选目标显示图标，空槽位显示 [＋]<br>
 * （点击打开背包选择器）；[免费] 后变为 {@code 转化为 空气！} 且无槽位。<br>
 * 点击源图标选中整条、点击目标图标选中单个（白框）；底部按钮：新增项目/删除/保存并返回/取消。</p>
 */
@OnlyIn(Dist.CLIENT)
public class BlockSubstitutionsScreen extends Screen implements ErrorToastSupport {

    /** 当前显示的错误横幅；null = 无。 */
    @javax.annotation.Nullable
    private net.minecraft.network.chat.Component errorToast;

    @Override
    public void setErrorToast(net.minecraft.network.chat.Component message) {
        this.errorToast = message;
    }

    private static final int W        = 480;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    private static final int COLS     = 3;
    private static final int MAX_ROWS = 12;
    private static final int CELL_H   = 24;
    private static final int GAP      = 4;
    private static final int ICON     = 16;
    private static final int MAX_TARGETS = 5;

    private final Screen parent;
    private int lastSnapshotVersion = -1;

    /** source → targets（有序工作副本；null = 免费/转化为空气）。 */
    private final Map<String, List<String>> map = new LinkedHashMap<>();
    private int scrollRows = 0;
    /** 当前选中的条目下标（-1 无）。 */
    private int selectedSource = -1;
    /** 当前选中的目标槽位（-1 无；≥0 时配合 selectedSource）。 */
    private int selectedTarget = -1;
    private boolean scrollDragging = false;
    private Button deleteButton;
    private Button addButton;

    private int gl, gt, panelH, listH, listX, listY, listW, cellW;
    /** 当前背包选择器作用的条目。 */
    private int pickerIndex = -1;

    // ── 新增项目对话框状态 ────────────────────────────────────────────────────
    private boolean addDialogOpen = false;
    private EditBox addInput;
    private static final int DW = 240, DH = 96, BW = 60, BH = 18, BP = 8;

    public BlockSubstitutionsScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.substitutions.title"));
        this.parent = parent;
        com.xioyim.spatialblueprint.client.ConfigAccess.request();
        loadMap();
    }

    private void loadMap() {
        map.clear();
        for (String entry : com.xioyim.spatialblueprint.client.ConfigAccess.getList(
                GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String source = entry.substring(0, eq).trim();
            String targetsStr = entry.substring(eq + 1).trim();
            if (targetsStr.isEmpty()) { map.put(source, null); continue; } // 空目标 = 免费
            List<String> targets = new ArrayList<>();
            for (String part : targetsStr.split("\\+")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int star = part.indexOf('*');
                if (star > 0) part = part.substring(0, star).trim(); // 数量统一为 1
                if (!part.isEmpty()) targets.add(part);
            }
            map.put(source, targets);
        }
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        selectedSource = -1;
        selectedTarget = -1;
        if (addDialogOpen) {
            setupAddDialog();
        } else {
            setupMainUi();
        }
    }

    private void setupMainUi() {
        int totalRows = Math.max(1, (map.size() + COLS - 1) / COLS);
        listH  = Math.min(MAX_ROWS, totalRows) * CELL_H;
        panelH = HEADER_H + PAD + listH + PAD + FOOTER_H;
        gl = (width  - W) / 2;
        gt = Math.max(8, (height - panelH) / 2);
        listX = gl + PAD;
        listY = gt + HEADER_H + PAD;
        listW = W - PAD * 2;
        cellW = (listW - GAP * (COLS - 1)) / COLS;

        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 20) / 5;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.add_entry"),
                        b -> { addDialogOpen = true; init(); })
                .bounds(startX, buttonY, bW, BTN_H).build());
        addButton = addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.add_target"),
                        b -> { if (selectedSource >= 0) openPicker(selectedSource); })
                .bounds(startX + bW + 5, buttonY, bW, BTN_H).build());
        addButton.active = canAdd();
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.delete"),
                        b -> deleteSelection())
                .bounds(startX + (bW + 5) * 2, buttonY, bW, BTN_H).build());
        deleteButton.active = selectedSource >= 0;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.save_return"),
                        b -> saveAndClose())
                .bounds(startX + (bW + 5) * 3, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.cancel"),
                        b -> onClose())
                .bounds(startX + (bW + 5) * 4, buttonY, bW, BTN_H).build());
    }

    /** [添加] 可用：选中了源图标，且该源目标未满 5 个（免费行不可加）。 */
    private boolean canAdd() {
        if (selectedSource < 0 || selectedSource >= map.size()) return false;
        List<String> targets = map.get(sourceKeys().get(selectedSource));
        return targets != null && targets.size() < MAX_TARGETS;
    }

    private void setupAddDialog() {
        int dx = (width - DW) / 2;
        int dy = (height - DH) / 2;
        addInput = new EditBox(font, dx + 8, dy + 32, DW - 16, 18, Component.empty());
        addInput.setMaxLength(128);
        addInput.setValue("");
        addRenderableWidget(addInput);
        setFocused(addInput);
        int okX = dx + DW / 2 - BW - 4;
        int cancelX = dx + DW / 2 + 4;
        int by = dy + DH - BH - BP;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.confirm"),
                        b -> confirmAddEntry())
                .bounds(okX, by, BW, BH).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.cancel"),
                        b -> { addDialogOpen = false; init(); })
                .bounds(cancelX, by, BW, BH).build());
    }

    private void confirmAddEntry() {
        String id = addInput.getValue().trim();
        if (!id.isEmpty()) {
            if (map.containsKey(id)) {
                // 原先画在 actionbar 上，但本界面全屏，玩家根本看不到（表现为「点了没反应」）
                com.xioyim.spatialblueprint.util.NotifySound.clientError();
                setErrorToast(Component.translatable("spatial_blueprint.config.substitutions.exists"));
                return;
            }
            putFirst(id, new ArrayList<>());
            scrollRows = 0;
        }
        addDialogOpen = false;
        init();
    }

    private void putFirst(String source, List<String> targets) {
        LinkedHashMap<String, List<String>> rebuilt = new LinkedHashMap<>();
        rebuilt.put(source, targets);
        rebuilt.putAll(map);
        map.clear();
        map.putAll(rebuilt);
    }

    private void deleteSelection() {
        if (selectedSource < 0 || selectedSource >= map.size()) return;
        String source = sourceKeys().get(selectedSource);
        if (selectedTarget >= 0) {
            List<String> targets = map.get(source);
            if (targets != null && selectedTarget < targets.size()) {
                targets.remove(selectedTarget);
            }
        } else {
            map.remove(source);
        }
        selectedSource = -1;
        selectedTarget = -1;
        init();
    }

    private void saveAndClose() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            List<String> targets = e.getValue();
            if (targets == null) { out.add(e.getKey() + "="); continue; } // 免费
            if (targets.isEmpty()) continue;
            out.add(e.getKey() + "=" + String.join("+", targets));
        }
        com.xioyim.spatialblueprint.client.ConfigAccess.setList(
                GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS, out);
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
        onClose();
    }

    private List<String> sourceKeys() { return new ArrayList<>(map.keySet()); }

    private void openPicker(int index) {
        pickerIndex = index;
        Minecraft.getInstance().setScreen(new ExcludedBlocksScreen.ItemQuickPickerScreen(this,
                this::onPickNewItem, true));
    }

    private void onPickNewItem(String id) {
        if (id == null || id.isBlank()) {
            Minecraft.getInstance().setScreen(this);
            init();
            return;
        }
        if (pickerIndex >= 0 && pickerIndex < map.size()) {
            String source = sourceKeys().get(pickerIndex);
            if (ExcludedBlocksScreen.ItemQuickPickerScreen.FREE.equals(id)) {
                map.put(source, null); // 免费（转化为空气）
            } else {
                List<String> targets = map.get(source);
                if (targets != null && !targets.contains(id) && targets.size() < MAX_TARGETS) {
                    targets.add(id);
                }
            }
        }
        Minecraft.getInstance().setScreen(this);
        init();
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        // 早返回模式：横幅出现时完全隐藏下层 UI（见「报错提示UI接入方法.md」）
        if (errorToast != null) {
            ErrorToast.render(g, this.font, this.width, this.height, errorToast);
            return;
        }
        if (addDialogOpen) { renderAddDialog(g, mx, my, pt); return; }

        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.SUBS_CONFIG_PATH,
                "spatial_blueprint.config.substitutions.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);
        String hoverName = null;
        int totalRows = (map.size() + COLS - 1) / COLS;
        int firstRow = scrollRows;
        int lastRow = Math.min(firstRow + MAX_ROWS, totalRows);
        for (int r = firstRow; r < lastRow; r++) {
            int y = listY + (r - firstRow) * CELL_H;
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                if (idx >= map.size()) break;
                int x = listX + c * (cellW + GAP);
                String hover = renderCell(g, mx, my, idx, x, y);
                if (hover != null) hoverName = hover;
            }
            // 横分割线
            g.fill(listX, y + CELL_H - 1, listX + listW, y + CELL_H, 0xFF222222);
        }
        // 竖分割线（列之间）
        for (int c = 1; c < COLS; c++) {
            int vx = listX + c * (cellW + GAP) - GAP / 2;
            g.fill(vx, listY, vx + 1, listY + listH, 0xFF222222);
        }
        drawScrollbar(g);
        super.render(g, mx, my, pt);
        if (hoverName != null) g.renderTooltip(font, Component.literal(hoverName), mx, my);
    }

    /** 渲染一个条目，返回悬浮提示的 lang 名（无则 null）。 */
    private String renderCell(GuiGraphics g, int mx, int my, int idx, int x, int y) {
        String source = sourceKeys().get(idx);
        List<String> targets = map.get(source);
        boolean free = targets == null;
        int rowTop = y + (CELL_H - ICON) / 2;

        // 源图标
        int sx = x + 2;
        if (idx == selectedSource && selectedTarget < 0) drawSelectBox(g, sx - 1, rowTop - 1);
        renderIcon(g, blockIcon(source), sx, rowTop);
        String hover = null;
        if (hoverInside(mx, my, sx, rowTop)) hover = displayName(source, false);

        // "转化为"
        int toX = sx + ICON + 3;
        String toText = free
                ? Component.translatable("spatial_blueprint.config.substitutions.to_air").getString()
                : Component.translatable("spatial_blueprint.config.substitutions.to").getString();
        g.drawString(font, toText, toX, rowTop + (ICON - font.lineHeight) / 2, 0xAAAAAA, false);

        // 槽位（免费行无槽位；只显示已选目标图标，空位留白）
        if (!free) {
            int tX = toX + font.width(toText) + 4;
            for (int j = 0; j < targets.size(); j++) {
                if (idx == selectedSource && j == selectedTarget) drawSelectBox(g, tX - 1, rowTop - 1);
                renderIcon(g, itemIcon(targets.get(j)), tX, rowTop);
                if (hoverInside(mx, my, tX, rowTop)) hover = displayName(targets.get(j), true);
                tX += ICON + 2;
            }
        }
        return hover;
    }

    private void renderAddDialog(GuiGraphics g, int mx, int my, float pt) {
        int dx = (width - DW) / 2, dy = (height - DH) / 2;
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);
        g.drawString(font, Component.translatable("spatial_blueprint.config.substitutions.dialog.title"),
                dx + 8, dy + 10, 0xFFFFFF, false);
        super.render(g, mx, my, pt);
    }

    private boolean hoverInside(int mx, int my, int x, int y) {
        return mx >= x && mx < x + ICON && my >= y && my < y + ICON;
    }

    private void drawSelectBox(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + ICON + 2, y + ICON + 2, 0xFFFFFFFF);
    }

    private void drawScrollbar(GuiGraphics g) {
        int totalRows = (map.size() + COLS - 1) / COLS;
        if (totalRows <= MAX_ROWS) return;
        int sbX = listX + listW - 4;
        g.fill(sbX, listY, sbX + 4, listY + listH, 0xFF333333);
        int thumbH = Math.max(20, (int) (listH * (double) listH / (totalRows * CELL_H)));
        int maxScroll = totalRows - MAX_ROWS;
        double ratio = maxScroll > 0 ? (double) scrollRows / maxScroll : 0;
        int thumbY = listY + (int) (ratio * (listH - thumbH));
        g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFFAAAAAA);
    }

    private boolean inScrollbar(int mx, int my) {
        int totalRows = (map.size() + COLS - 1) / COLS;
        if (totalRows <= MAX_ROWS) return false;
        return mx >= listX + listW - 5 && mx < listX + listW + 1
                && my >= listY && my < listY + listH;
    }

    private void updateScrollFromY(double my) {
        int totalRows = (map.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - MAX_ROWS);
        if (maxScroll <= 0) { scrollRows = 0; return; }
        int thumbH = Math.max(20, (int) (listH * (double) listH / (totalRows * CELL_H)));
        double ratio = (my - listY - thumbH / 2.0) / (listH - thumbH);
        scrollRows = (int) Math.round(net.minecraft.util.Mth.clamp(ratio * maxScroll, 0, maxScroll));
    }

    // ── 交互 ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        if (addDialogOpen) return super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int mx = (int) mouseX, my = (int) mouseY;
        if (ConfigTitles.click(mx, my)) return true;
        if (inScrollbar(mx, my)) { scrollDragging = true; updateScrollFromY(mouseY); return true; }

        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int row = (my - listY) / CELL_H + scrollRows;
            int col = (mx - listX) / (cellW + GAP);
            int idx = row * COLS + col;
            if (col < COLS && row >= scrollRows && idx >= 0 && idx < map.size()) {
                int x = listX + col * (cellW + GAP);
                int y = listY + (row - scrollRows) * CELL_H;
                String source = sourceKeys().get(idx);
                List<String> targets = map.get(source);
                boolean free = targets == null;
                int rowTop = y + (CELL_H - ICON) / 2;
                int sx = x + 2;
                // 源图标
                if (mx >= sx && mx < sx + ICON && my >= rowTop && my < rowTop + ICON) {
                    selectedSource = idx; selectedTarget = -1;
                    deleteButton.active = true;
                    addButton.active = canAdd();
                    return true;
                }
                // 目标图标（免费行无）
                if (!free && targets != null) {
                    int toX = sx + ICON + 3;
                    String toText = Component.translatable("spatial_blueprint.config.substitutions.to").getString();
                    int tX = toX + font.width(toText) + 4;
                    for (int j = 0; j < targets.size(); j++) {
                        if (mx >= tX && mx < tX + ICON && my >= rowTop && my < rowTop + ICON) {
                            selectedSource = idx; selectedTarget = j;
                            deleteButton.active = true;
                            addButton.active = canAdd();
                            return true;
                        }
                        tX += ICON + 2;
                    }
                }
                selectedSource = -1; selectedTarget = -1;
                deleteButton.active = false;
                addButton.active = false;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollDragging && button == 0) { updateScrollFromY(mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) scrollDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (addDialogOpen) return super.mouseScrolled(mx, my, delta);
        int totalRows = (map.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - MAX_ROWS);
        scrollRows = Math.max(0, Math.min(maxScroll, scrollRows - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;   // ESC
            return true;                              // 吃掉所有按键
        }
        if (addDialogOpen) {
            if (keyCode == 257 || keyCode == 335) { confirmAddEntry(); return true; }
            if (keyCode == 256) { addDialogOpen = false; init(); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 图标辅助 ─────────────────────────────────────────────────────────────

    private static ItemStack blockIcon(String blockId) {
        try {
            net.minecraft.world.level.block.Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
            if (b != null) {
                if (!b.defaultBlockState().getFluidState().isEmpty()) {
                    if (b.defaultBlockState().getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                        return new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET);
                    if (b.defaultBlockState().getFluidState().is(net.minecraft.tags.FluidTags.LAVA))
                        return new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET);
                }
                if (b instanceof net.minecraft.world.level.block.FireBlock)
                    return new ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL);
                if (b != Blocks.AIR && b.asItem() != net.minecraft.world.item.Items.AIR)
                    return new ItemStack(b.asItem());
            }
        } catch (Exception ignored) {}
        return new ItemStack(Blocks.BARRIER);
    }

    private static ItemStack itemIcon(String itemId) {
        try {
            net.minecraft.world.item.Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
            if (it != null && it != net.minecraft.world.item.Items.AIR) return new ItemStack(it);
        } catch (Exception ignored) {}
        return new ItemStack(net.minecraft.world.item.Items.BARRIER);
    }

    /** 渲染物品图标（保护性跳过：个别 mod 方块/物品图标缺失时避免崩溃）。 */
    private static void renderIcon(GuiGraphics g, ItemStack stack, int x, int y) {
        try {
            g.renderItem(stack, x, y);
        } catch (Exception ignored) {}
    }

    private static String displayName(String id, boolean isItem) {
        String key = (isItem ? "item." : "block.") + id.replace(':', '.');
        String s = Component.translatable(key).getString();
        return s.equals(key) ? id : s;
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void tick() {
        super.tick();
        int v = com.xioyim.spatialblueprint.client.ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            lastSnapshotVersion = v;
            loadMap();
            init();
        }
    }
}
