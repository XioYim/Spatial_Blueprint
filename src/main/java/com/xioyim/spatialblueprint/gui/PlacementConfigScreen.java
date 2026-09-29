package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * 配置 · 放置：两个列表子页入口（延迟放置方块 / 隐藏预览方块）。
 */
@OnlyIn(Dist.CLIENT)
public class PlacementConfigScreen extends Screen {

    private final Screen parent;

    private static final int W = 300;
    private static final int BTN_H = 20;

    public PlacementConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.placement.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        int cx = width / 2;
        int startY = height / 2 - 30;
        int bW = 220;
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.placement.late_btn"),
                        b -> Minecraft.getInstance().setScreen(new BlockListConfigScreen(this,
                                Component.translatable("spatial_blueprint.config.placement.late.title"),
                                "spatial_blueprint.config.placement.late.help",
                                BlueprintConfig.LATE_PLACE_BLOCKS)))
                .bounds(cx - bW / 2, startY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.placement.hidden_btn"),
                        b -> Minecraft.getInstance().setScreen(new BlockListConfigScreen(this,
                                Component.translatable("spatial_blueprint.config.placement.hidden.title"),
                                "spatial_blueprint.config.placement.hidden.help",
                                BlueprintConfig.HIDDEN_PREVIEW_BLOCKS)))
                .bounds(cx - bW / 2, startY + BTN_H + 6, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"),
                        b -> onClose())
                .bounds(cx - 75, startY + (BTN_H + 6) * 2 + 6, 150, BTN_H).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.placement.help", width / 2, 16, mx, my);
        super.render(g, mx, my, pt);
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
}
