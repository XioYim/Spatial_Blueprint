package com.xioyim.spatialblueprint.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 创建蓝图子菜单：从 PointSelectionScreen 跳入，选择蓝图类型后执行对应捕捉逻辑。
 */
@OnlyIn(Dist.CLIENT)
public class CreateBlueprintScreen extends Screen {

    private final PointSelectionScreen parent;
    private final Player player;

    public CreateBlueprintScreen(PointSelectionScreen parent, Player player) {
        super(Component.translatable("spatial_blueprint.gui.create_blueprint.title"));
        this.parent = parent;
        this.player = player;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        super.init();

        int cx = this.width  / 2;
        int cy = this.height / 2;
        int btnW = 160;
        int btnH = 20;
        int gap  = 6;
        int totalH = btnH * 5 + gap * 5;
        int startY = cy - totalH / 2;

        boolean creative = player.isCreative();

        // [创建空间蓝图] — 仅创造模式
        String spatialTip = creative
                ? "spatial_blueprint.gui.point_selection.capture"
                : "spatial_blueprint.gui.point_selection.capture.disabled";
        Button spatialBtn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.btn.spatial"),
                        b -> parent.capture())
                .bounds(cx - btnW / 2, startY, btnW, btnH)
                .tooltip(Tooltip.create(Component.translatable(spatialTip)))
                .build());
        spatialBtn.active = creative;

        // [创建投影蓝图] — 全模式
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.btn.projection"),
                        b -> parent.captureProjection())
                .bounds(cx - btnW / 2, startY + (btnH + gap), btnW, btnH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.project")))
                .build());

        // [创建次元蓝图] — 全模式
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.btn.void"),
                        b -> parent.captureAndStore())
                .bounds(cx - btnW / 2, startY + (btnH + gap) * 2, btnW, btnH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.create_blueprint.tooltip.void")))
                .build());

        // [创建工程蓝图] — 仅创造模式（openCreateEngineeringScreen 内部会 setScreen，不需要额外关闭）
        String engTip = creative
                ? "spatial_blueprint.gui.point_selection.tooltip.create_engineering"
                : "spatial_blueprint.gui.point_selection.tooltip.create_engineering.disabled";
        Button engBtn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.btn.engineering"),
                        b -> parent.openCreateEngineeringScreen())
                .bounds(cx - btnW / 2, startY + (btnH + gap) * 3, btnW, btnH)
                .tooltip(Tooltip.create(Component.translatable(engTip)))
                .build());
        engBtn.active = creative;

        // [返回]
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.btn.back"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - btnW / 2, startY + (btnH + gap) * 4 + gap, btnW, btnH)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.create_blueprint.tooltip.back")))
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 80, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }
}
