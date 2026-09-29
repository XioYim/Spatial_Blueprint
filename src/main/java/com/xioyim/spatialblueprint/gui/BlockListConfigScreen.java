package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.ClientConfigMirror;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用方块 ID 列表编辑页（用于"延迟放置方块"/"隐藏预览方块"等 List 配置）。
 * 可滚动 + 选中 + 新增（背包选择器）/删除/保存。
 */
@OnlyIn(Dist.CLIENT)
public class BlockListConfigScreen extends Screen {

    private static final int W        = 300;
    private static final int HEADER_H = 24;
    private static final int ITEM_H   = 22;
    private static final int MAX_VIS  = 10;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;

    private final Screen parent;
    private final ForgeConfigSpec.ConfigValue<List<String>> config;
    private final String helpKey;
    /** true = 列表为物品 ID（lang 前缀 item.）；false = 方块 ID（前缀 block.）。 */
    private final boolean itemPrefix;
    private final List<String> items = new ArrayList<>();
    private boolean itemsLoaded = false;
    private int selectedIndex = -1;
    private int scrollOffset;
    private boolean scrollDragging = false;
    private Button deleteButton;

    private int gl, gt, panelH, listH, listX, listY, listW;
    private int lastSnapshotVersion = -1;

    public BlockListConfigScreen(@Nullable Screen parent, Component title, String helpKey,
                                 ForgeConfigSpec.ConfigValue<List<String>> config) {
        this(parent, title, helpKey, config, false);
    }

    public BlockListConfigScreen(@Nullable Screen parent, Component title, String helpKey,
                                 ForgeConfigSpec.ConfigValue<List<String>> config, boolean itemPrefix) {
        super(title);
        this.parent = parent;
        this.helpKey = helpKey;
        this.config = config;
        this.itemPrefix = itemPrefix;
        ConfigAccess.request();
    }

    @Override
    protected void init() {
        super.init();
        if (!itemsLoaded) {
            items.clear();
            items.addAll(ConfigAccess.getList(config));
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
        Minecraft.getInstance().setScreen(new ExcludedBlocksScreen.ItemQuickPickerScreen(this,
                id -> {
                    if (id != null && !id.isBlank() && !items.contains(id)) items.add(id);
                    Minecraft.getInstance().setScreen(this);
                    init();
                }));
    }

    private void deleteSelected() {
        if (selectedIndex >= 0 && selectedIndex < items.size()) {
            items.remove(selectedIndex);
            selectedIndex = -1;
            init();
        }
    }

    private void saveAndClose() {
        ConfigAccess.setList(config, new ArrayList<>(items));
        ConfigAccess.save();
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                helpKey, gl + W / 2, gt + 8, mx, my);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);
        String hoveredId = null;
        int end = Math.min(items.size(), scrollOffset + MAX_VIS);
        for (int i = scrollOffset; i < end; i++) {
            int y = listY + (i - scrollOffset) * ITEM_H;
            if (i == selectedIndex) g.fill(listX, y, listX + listW, y + ITEM_H, 0xFF2D4A6A);
            String id = items.get(i);
            String label = displayLabel(id);
            int maxW = listW - 8;
            if (font.width(label) > maxW)
                label = font.plainSubstrByWidth(label, maxW - font.width("...")) + "...";
            g.drawCenteredString(font, label, listX + listW / 2, y + 6, 0xFFFFFFFF);
            if (mx >= listX && mx < listX + listW && my >= y && my < y + ITEM_H) {
                hoveredId = id;
            }
        }
        drawScrollbar(g);
        super.render(g, mx, my, pt);
        if (hoveredId != null) {
            g.renderTooltip(font, Component.literal(hoveredId), mx, my);
        }
    }

    private void drawScrollbar(GuiGraphics g) {
        if (items.size() <= MAX_VIS) return;
        int sbX = listX + listW - 4;
        g.fill(sbX, listY, sbX + 4, listY + listH, 0xFF333333);
        int contentH = items.size() * ITEM_H;
        int thumbH = Math.max(20, (int) (listH * (double) listH / contentH));
        int maxScroll = Math.max(0, items.size() - MAX_VIS);
        double ratio = maxScroll > 0 ? (double) scrollOffset / maxScroll : 0;
        int thumbY = listY + (int) (ratio * (listH - thumbH));
        g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFFAAAAAA);
    }

    private boolean inScrollbar(int mx, int my) {
        if (items.size() <= MAX_VIS) return false;
        return mx >= listX + listW - 5 && mx < listX + listW + 1
                && my >= listY && my < listY + listH;
    }

    private void updateScrollFromY(double my) {
        int maxScroll = Math.max(0, items.size() - MAX_VIS);
        if (maxScroll <= 0) { scrollOffset = 0; return; }
        int thumbH = Math.max(20, (int) (listH * (double) listH / (items.size() * ITEM_H)));
        double ratio = (my - listY - thumbH / 2.0) / (listH - thumbH);
        scrollOffset = (int) Math.round(net.minecraft.util.Mth.clamp(ratio * maxScroll, 0, maxScroll));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int mx = (int) mouseX, my = (int) mouseY;
        if (inScrollbar(mx, my)) { scrollDragging = true; updateScrollFromY(mouseY); return true; }
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
        int maxScroll = Math.max(0, items.size() - MAX_VIS);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
        return true;
    }

    /**
     * 方块/物品混在同一张列表时（如禁捕物品 = 方块物品 + 纯物品），单一前缀
     * {@code item.} / {@code block.} 会让另一类查不到翻译而显示成
     * {@code item.minecraft.bedrock} 这类 raw key。优先按页面的主前缀查，
     * 查不到再试另一前缀，仍查不到就回落为原始 id。
     */
    private String displayLabel(String id) {
        String base = id.replace(':', '.');
        String primary   = (itemPrefix ? "item." : "block.") + base;
        String secondary = (itemPrefix ? "block." : "item.") + base;
        String s = Component.translatable(primary).getString();
        if (!s.equals(primary)) return s;
        s = Component.translatable(secondary).getString();
        if (!s.equals(secondary)) return s;
        return id;
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void tick() {
        super.tick();
        int v = ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            itemsLoaded = false;
            init();
        }
    }
}
