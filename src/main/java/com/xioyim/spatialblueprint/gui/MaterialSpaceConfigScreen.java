package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ClientConfigMirror;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * 配置 · 材料空间（子页）：总件数上限 + 「忽略 NBT 字段」列表编辑入口。
 *
 * <p>读写一律走 {@link ConfigAccess}（服务端镜像 + 提交），改的是服务端那份 toml。</p>
 */
@OnlyIn(Dist.CLIENT)
public class MaterialSpaceConfigScreen extends Screen {

    private final Screen parent;

    private static final int W        = 380;
    private static final int HEADER_H = 24;
    private static final int ROW_H    = 30;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    /** 「重置为默认」小按钮（正方形）尺寸与它到输入框的间距。 */
    private static final int RESET_W   = 16;
    private static final int RESET_GAP = 3;

    private EditBox countBox;
    private int gl, gt, panelH, contentY, listX, listW;
    private int lastSnapshotVersion = -1;

    public MaterialSpaceConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.material.space.title"));
        this.parent = parent;
        ConfigAccess.request();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        int contentH = ROW_H;
        panelH = HEADER_H + PAD + contentH + PAD + FOOTER_H;
        gl = (width - W) / 2;
        gt = Math.max(8, (height - panelH) / 2);
        contentY = gt + HEADER_H + PAD;
        listX = gl + PAD;
        listW = W - PAD * 2;

        int editW = 140;
        String prev = (countBox != null
                && lastSnapshotVersion == ClientConfigMirror.snapshotVersion())
                ? countBox.getValue() : null;
        int boxX = listX + listW - editW;
        countBox = new EditBox(this.font,
                boxX, contentY + (ROW_H - 20) / 2, editW, 20, Component.empty());
        countBox.setMaxLength(12);
        countBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,10}"));
        countBox.setValue(prev != null ? prev : String.valueOf(
                ConfigAccess.get(BlueprintConfig.MATERIAL_SPACE_MAX_COUNT)));
        addRenderableWidget(countBox);
        addResetButton(countBox);
        lastSnapshotVersion = ClientConfigMirror.snapshotVersion();

        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 16) / 3;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.material.nbtkeys_btn"),
                        b -> Minecraft.getInstance().setScreen(new StringListConfigScreen(this)))
                .bounds(startX, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.save"),
                        b -> saveAll())
                .bounds(startX + bW + 4, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"),
                        b -> onClose())
                .bounds(startX + (bW + 4) * 2, buttonY, bW, BTN_H).build());
    }

    private void addResetButton(EditBox box) {
        int def = ConfigAccess.defaultValue(BlueprintConfig.MATERIAL_SPACE_MAX_COUNT);
        Button btn = Button.builder(Component.literal("\u267B"), b ->
                        box.setValue(String.valueOf(
                                ConfigAccess.defaultValue(BlueprintConfig.MATERIAL_SPACE_MAX_COUNT))))
                .bounds(box.getX() - RESET_W - RESET_GAP, box.getY() + (20 - RESET_W) / 2, RESET_W, RESET_W)
                .build();
        btn.setTooltip(Tooltip.create(Component.translatable("spatial_blueprint.config.reset.tip", def)));
        addRenderableWidget(btn);
    }

    private void saveAll() {
        String s = countBox.getValue().trim();
        if (!s.isEmpty()) {
            try {
                ConfigAccess.set(BlueprintConfig.MATERIAL_SPACE_MAX_COUNT, Integer.parseInt(s));
            } catch (NumberFormatException ignored) {}
        }
        ConfigAccess.save();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.material.space.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, contentY, listX + listW, contentY + ROW_H, 0xFF141414);
        String label = Component.translatable(
                "spatial_blueprint.config.material.space.max_count").getString();
        int maxW = listW - 140 - (RESET_W + RESET_GAP) - 12;
        if (font.width(label) > maxW)
            label = font.plainSubstrByWidth(label, maxW - font.width("...")) + "...";
        g.drawString(this.font, label, listX + 4, contentY + (ROW_H - this.font.lineHeight) / 2,
                0xFFFFFFFF, false);
        if (mx >= listX && mx <= listX + maxW && my >= contentY && my < contentY + ROW_H) {
            renderDescTooltip(g, "spatial_blueprint.config.material.space.max_count.desc", mx, my);
        }
        super.render(g, mx, my, pt);
    }

    private void renderDescTooltip(GuiGraphics g, String key, int mx, int my) {
        String desc = Component.translatable(key).getString().replace("\\n", "\n");
        java.util.List<Component> lines = new java.util.ArrayList<>();
        for (String line : desc.split("\n", -1)) lines.add(Component.literal(line));
        g.renderComponentTooltip(this.font, lines, mx, my);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
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
            init(); // 快照到达 / 保存后刷新为服务端真实值
        }
    }
}
