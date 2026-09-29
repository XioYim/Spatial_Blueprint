package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.ClientConfigMirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 配置 · 禁捕方块（excludedBlocks）：可选中列表。
 *
 * <p>布局参考 Healthy_Diets {@code ShelfSchemePickerScreen}：紧凑面板 + 分层背景 + 选中高亮 + 滚动（无裁剪）。<br>
 * 方块禁用完全由服务端配置驱动，列表内所有条目都可选中删除。<br>
 * 行显示方块 lang key，悬浮 tooltip 显示方块 ID。底部按钮：新增 / 删除 / 保存并返回 / 取消。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ExcludedBlocksScreen extends Screen {

    private static final int W        = 300;
    private static final int HEADER_H = 24;
    private static final int ITEM_H   = 20;
    private static final int MAX_VIS  = 12;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;

    private final Screen parent;

    /** 工作副本（来自 EXCLUDED_BLOCKS 配置），保存时写回。 */
    private final List<String> items = new ArrayList<>();
    private boolean itemsLoaded = false;
    private int selectedIndex = -1;
    private int scrollOffset;

    private int gl, gt, panelH, listH, listX, listY, listW;
    private Button deleteButton;
    private int lastSnapshotVersion = -1;

    public ExcludedBlocksScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.excluded.title"));
        this.parent = parent;
        ConfigAccess.request();
    }

    @Override
    protected void init() {
        super.init();
        // 仅首次（或快照刷新后）从配置加载；新增/删除后 init() 重建 UI 时不覆盖工作副本
        if (!itemsLoaded) {
            items.clear();
            items.addAll(ConfigAccess.getList(BlueprintConfig.EXCLUDED_BLOCKS));
            itemsLoaded = true;
            lastSnapshotVersion = ClientConfigMirror.snapshotVersion();
        }
        selectedIndex = -1;
        scrollOffset = 0;
        setupUi();
    }

    private void setupUi() {
        clearWidgets();
        listH  = Math.max(ITEM_H, Math.min(MAX_VIS, items.size()) * ITEM_H);
        panelH = HEADER_H + PAD + listH + PAD + FOOTER_H;
        gl = (width  - W) / 2;
        gt = (height - panelH) / 2;
        listX = gl + PAD;
        listY = gt + HEADER_H + PAD;
        listW = W - PAD * 2;

        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 12) / 4;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.add"),
                        b -> openPicker())
                .bounds(startX, buttonY, bW, BTN_H).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.delete"),
                        b -> deleteSelected())
                .bounds(startX + bW + 4, buttonY, bW, BTN_H).build());
        deleteButton.active = selectedIndex >= 0;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.save_return"),
                        b -> saveAndClose())
                .bounds(startX + (bW + 4) * 2, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.cancel"),
                        b -> onClose())
                .bounds(startX + (bW + 4) * 3, buttonY, bW, BTN_H).build());
    }

    private void openPicker() {
        Minecraft.getInstance().setScreen(new ItemQuickPickerScreen(this, this::onPickNewItem));
    }

    /** 点击物品图标直接新增（不弹额外 UI）。 */
    private void onPickNewItem(String id) {
        if (id != null && !id.isBlank() && !items.contains(id)) {
            items.add(id);
        }
        Minecraft.getInstance().setScreen(this);
        init();
    }

    private void deleteSelected() {
        if (selectedIndex >= 0 && selectedIndex < items.size()) {
            items.remove(selectedIndex);
            selectedIndex = -1;
            init();
        }
    }

    private void saveAndClose() {
        ConfigAccess.setList(BlueprintConfig.EXCLUDED_BLOCKS, new ArrayList<>(items));
        ConfigAccess.save();
        onClose();
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // 分层背景（参考 ShelfSchemePickerScreen）
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.excluded.help", gl + W / 2, gt + 8, mx, my);

        // 列表区
        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);
        String hoveredId = null;
        int end = Math.min(items.size(), scrollOffset + MAX_VIS);
        for (int i = scrollOffset; i < end; i++) {
            int y = listY + (i - scrollOffset) * ITEM_H;
            if (i == selectedIndex) g.fill(listX, y, listX + listW, y + ITEM_H, 0xFF2D4A6A);

            String id = items.get(i);
            String label = Component.translatable("block." + id.replace(':', '.')).getString();
            int maxW = listW - 8;
            if (font.width(label) > maxW)
                label = font.plainSubstrByWidth(label, maxW - font.width("...")) + "...";
            g.drawCenteredString(font, label, listX + listW / 2, y + 6, 0xFFFFFFFF);

            if (mx >= listX && mx < listX + listW && my >= y && my < y + ITEM_H) {
                hoveredId = id;
            }
        }

        super.render(g, mx, my, pt);

        if (hoveredId != null) {
            g.renderComponentTooltip(font, java.util.List.of(Component.literal(hoveredId)), mx, my);
        }
    }

    // ── 交互 ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        int mx = (int) mouseX, my = (int) mouseY;
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + Math.min(MAX_VIS, items.size()) * ITEM_H) {
            int row = (int) (my - listY) / ITEM_H + scrollOffset;
            if (row >= 0 && row < items.size()) {
                selectedIndex = (selectedIndex == row) ? -1 : row;
                deleteButton.active = selectedIndex >= 0;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int maxScroll = Math.max(0, items.size() - MAX_VIS);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void tick() {
        super.tick();
        int v = ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            itemsLoaded = false; // 触发 init() 重新从快照加载
            init();
        }
    }

    /** 背包物品快速选择器：点击物品图标直接回调新增，不弹额外 UI。 */
    @OnlyIn(Dist.CLIENT)
    public static class ItemQuickPickerScreen extends Screen {
        private static final int SLOT_SIZE = 18;
        private static final int GRID_COLS = 9;
        private static final int GRID_ROWS = 4;
        private static final int PANEL_PAD = 10;

        private final Screen parent;
        private final Consumer<String> onPick;
        private final boolean showFreeButton;

        /** 免费标记（转化为空气）：点击 [免费] 时回调携带此值。 */
        public static final String FREE = "\u0000sb_free";

        public ItemQuickPickerScreen(Screen parent, Consumer<String> onPick) {
            this(parent, onPick, false);
        }

        public ItemQuickPickerScreen(Screen parent, Consumer<String> onPick, boolean showFreeButton) {
            super(Component.translatable("spatial_blueprint.gui.material_picker.panel_title"));
            this.parent = parent;
            this.onPick = onPick;
            this.showFreeButton = showFreeButton;
        }

        @Override
        protected void init() {
            super.init();
            clearWidgets();
            if (showFreeButton) {
                int gx = gridX(), gy = gridY();
                int panelW = GRID_COLS * SLOT_SIZE + PANEL_PAD * 2;
                int titleH = font.lineHeight + 6;
                int px = gx - PANEL_PAD;
                int py = gy - titleH - PANEL_PAD / 2;
                int panelH = titleH + GRID_ROWS * SLOT_SIZE + PANEL_PAD;
                int btnW = 60;
                // 免费按钮：面板底部之外（下方），以面板中心水平居中
                addRenderableWidget(Button.builder(
                                Component.translatable("spatial_blueprint.gui.material_picker.free"),
                                b -> onPick.accept(FREE))
                        .bounds(px + panelW / 2 - btnW / 2, py + panelH + 6, btnW, 16).build());
            }
        }

        private int gridX() { return (this.width - GRID_COLS * SLOT_SIZE) / 2; }
        private int gridY() { return (this.height - GRID_ROWS * SLOT_SIZE) / 2 - 8; }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            this.renderBackground(g);
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.entity.player.Inventory inv =
                    (mc.player != null) ? mc.player.getInventory() : null;

            int gx = gridX(), gy = gridY();
            int panelW = GRID_COLS * SLOT_SIZE + PANEL_PAD * 2;
            int titleH = font.lineHeight + 6;
            int panelH = titleH + GRID_ROWS * SLOT_SIZE + PANEL_PAD;
            int px = gx - PANEL_PAD;
            int py = gy - titleH - PANEL_PAD / 2;

            g.fill(px, py, px + panelW, py + panelH, 0xD0101010);
            drawBorder(g, px, py, panelW, panelH);
            String title = Component.translatable("spatial_blueprint.gui.material_picker.panel_title").getString();
            g.drawString(font, title, px + (panelW - font.width(title)) / 2, py + PANEL_PAD / 2, 0xFFFFAA, false);

            String hoveredName = null;
            if (inv != null) {
                for (int row = 0; row < GRID_ROWS; row++) {
                    for (int col = 0; col < GRID_COLS; col++) {
                        int slotIndex = (row < 3) ? (9 + row * 9 + col) : col;
                        net.minecraft.world.item.ItemStack item = inv.getItem(slotIndex);
                        int sx = gx + col * SLOT_SIZE;
                        int sy = gy + row * SLOT_SIZE;
                        g.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1,
                                item.isEmpty() ? 0xFF1A1A1A : 0xFF2A2A2A);
                        if (!item.isEmpty()) {
                            if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE) {
                                g.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x60FFFFFF);
                                hoveredName = item.getHoverName().getString();
                            }
                            g.renderItem(item, sx + 1, sy + 1);
                        }
                    }
                }
            }
            super.render(g, mx, my, pt);

            if (hoveredName != null) {
                g.renderTooltip(font, Component.literal(hoveredName), mx, my);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
            int mx = (int) mouseX, my = (int) mouseY;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return super.mouseClicked(mouseX, mouseY, button);

            int gx = gridX(), gy = gridY();
            for (int row = 0; row < GRID_ROWS; row++) {
                for (int col = 0; col < GRID_COLS; col++) {
                    int sx = gx + col * SLOT_SIZE;
                    int sy = gy + row * SLOT_SIZE;
                    if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE) {
                        int slotIndex = (row < 3) ? (9 + row * 9 + col) : col;
                        net.minecraft.world.item.ItemStack item = mc.player.getInventory().getItem(slotIndex);
                        if (!item.isEmpty()) {
                            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(item.getItem()).toString();
                            onPick.accept(id);
                        } else {
                            Minecraft.getInstance().setScreen(parent);
                        }
                        return true;
                    }
                }
            }
            // 点击格子外：先让控件处理（免费按钮等），未命中则返回
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (!handled) Minecraft.getInstance().setScreen(parent);
            return true;
        }

        @Override
        public void onClose() { Minecraft.getInstance().setScreen(parent); }

        @Override
        public boolean isPauseScreen() { return false; }

        private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
            int c = 0xFF888888;
            g.fill(x,         y,         x + w,     y + 1,     c);
            g.fill(x,         y + h - 1, x + w,     y + h,     c);
            g.fill(x,         y,         x + 1,     y + h,     c);
            g.fill(x + w - 1, y,         x + w,     y + h,     c);
        }
    }
}
