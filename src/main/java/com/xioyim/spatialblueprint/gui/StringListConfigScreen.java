package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ClientConfigMirror;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置 · 纯文本字符串列表编辑器（按原文显示，不做物品/方块翻译）。
 *
 * <p>用于「构造材料键时忽略的顶层 NBT 字段」这类条目不是注册表 ID 的列表：
 * 行内文本原样显示，[新增] 弹输入框手动键入，选中后可删除。
 * 读写走 {@link ConfigAccess}（服务端镜像 + 提交）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class StringListConfigScreen extends Screen {

    private static final int W        = 320;
    private static final int HEADER_H = 24;
    private static final int ITEM_H   = 22;
    private static final int MAX_VIS  = 10;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;

    // ── 新增对话框尺寸 ─────────────────────────────────────────────────────
    private static final int DW = 240, DH = 96, BW = 60, BH = 18, BP = 8;

    private final Screen parent;

    private final List<String> items = new ArrayList<>();
    private boolean itemsLoaded = false;
    private int selectedIndex = -1;
    private int scrollOffset;

    private int gl, gt, panelH, listH, listX, listY, listW;
    private Button deleteButton;
    private int lastSnapshotVersion = -1;

    private boolean addDialogOpen = false;
    private EditBox addInput;

    public StringListConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.material.nbtkeys.title"));
        this.parent = parent;
        ConfigAccess.request();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        if (!itemsLoaded) {
            items.clear();
            items.addAll(ConfigAccess.getList(BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS));
            itemsLoaded = true;
            lastSnapshotVersion = ClientConfigMirror.snapshotVersion();
        }
        selectedIndex = -1;
        scrollOffset = 0;
        if (addDialogOpen) {
            setupAddDialog();
        } else {
            setupUi();
        }
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
                        b -> { addDialogOpen = true; init(); })
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
                        b -> confirmAdd())
                .bounds(okX, by, BW, BH).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.cancel"),
                        b -> { addDialogOpen = false; init(); })
                .bounds(cancelX, by, BW, BH).build());
    }

    private void confirmAdd() {
        String s = addInput.getValue().trim();
        if (!s.isEmpty() && !items.contains(s)) {
            items.add(s);
            scrollOffset = Math.max(0, items.size() - MAX_VIS);
        }
        addDialogOpen = false;
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
        ConfigAccess.setList(BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS, new ArrayList<>(items));
        ConfigAccess.save();
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        if (addDialogOpen) { renderAddDialog(g); return; }
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.material.nbtkeys.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);
        int end = Math.min(items.size(), scrollOffset + MAX_VIS);
        for (int i = scrollOffset; i < end; i++) {
            int y = listY + (i - scrollOffset) * ITEM_H;
            if (i == selectedIndex) g.fill(listX, y, listX + listW, y + ITEM_H, 0xFF2D4A6A);
            String label = items.get(i);
            int maxW = listW - 8;
            if (font.width(label) > maxW)
                label = font.plainSubstrByWidth(label, maxW - font.width("...")) + "...";
            g.drawString(font, label, listX + 4, y + (ITEM_H - font.lineHeight) / 2, 0xFFFFFFFF, false);
        }
        super.render(g, mx, my, pt);
    }

    private void renderAddDialog(GuiGraphics g) {
        int dx = (width - DW) / 2, dy = (height - DH) / 2;
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        int c = 0xFF888888;
        g.fill(dx, dy, dx + DW, dy + 1, c);
        g.fill(dx, dy + DH - 1, dx + DW, dy + DH, c);
        g.fill(dx, dy, dx + 1, dy + DH, c);
        g.fill(dx + DW - 1, dy, dx + DW, dy + DH, c);
        g.drawString(font, Component.translatable("spatial_blueprint.config.material.nbtkeys.dialog.title"),
                dx + 8, dy + 10, 0xFFFFFF, false);
        super.render(g, 0, 0, 0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int mx = (int) mouseX, my = (int) mouseY;
        if (ConfigTitles.click(mx, my)) return true;
        if (addDialogOpen) return super.mouseClicked(mouseX, mouseY, button);
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
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
        if (addDialogOpen) return super.mouseScrolled(mx, my, delta);
        int maxScroll = Math.max(0, items.size() - MAX_VIS);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (addDialogOpen) {
            if (keyCode == 257 || keyCode == 335) { confirmAdd(); return true; }
            if (keyCode == 256) { addDialogOpen = false; init(); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
