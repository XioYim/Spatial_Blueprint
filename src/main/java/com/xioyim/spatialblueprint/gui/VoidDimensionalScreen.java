package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_VoidRecaptureMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidResetMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidTeleportMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

@OnlyIn(Dist.CLIENT)
public class VoidDimensionalScreen extends Screen implements ErrorToastSupport {

    private final ItemStack stack;
    private boolean showResetConfirm = false;

    @Nullable private Component errorToast;

    // ── 固定 Y 坐标 & 尺寸 ──────────────────────────────────────────────────
    private static final int BTN_H   = 18;
    private static final int BTN_GAP = 4;
    private static final int BTN_MIN_W = 50;
    private static final int ROW_OFFSET_FROM_BOTTOM = 63;

    public VoidDimensionalScreen(ItemStack stack) {
        super(Component.empty());
        this.stack = stack;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();

        int btnY = this.height - ROW_OFFSET_FROM_BOTTOM;

        if (showResetConfirm) {
            initResetConfirm();
            return;
        }

        Component tpLabel     = Component.translatable("spatial_blueprint.gui.void_dimensional.btn.tp");
        Component viewLabel   = Component.translatable("spatial_blueprint.gui.void_dimensional.view_range");
        Component storeLabel  = Component.translatable("spatial_blueprint.gui.void_dimensional.store");
        Component resetLabel  = Component.translatable("spatial_blueprint.gui.void_dimensional.reset");
        Component cancelLabel = Component.translatable("spatial_blueprint.gui.void_dimensional.cancel");

        int wTp = Math.max(BTN_MIN_W, this.font.width(tpLabel)    + 12);
        int w1  = Math.max(BTN_MIN_W, this.font.width(viewLabel)   + 12);
        int w2  = Math.max(BTN_MIN_W, this.font.width(storeLabel)  + 12);
        int w3  = Math.max(BTN_MIN_W, this.font.width(resetLabel)  + 12);
        int w4  = Math.max(BTN_MIN_W, this.font.width(cancelLabel) + 12);

        int totalW = wTp + w1 + w2 + w3 + w4 + 4 * BTN_GAP;
        int cx = (this.width - totalW) / 2;

        BlockPos min = VoidDimensionalBlueprintItem.getCaptureMin(stack);
        BlockPos max = VoidDimensionalBlueprintItem.getCaptureMax(stack);
        boolean hasCoords = min != null && max != null;

        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        boolean isOp = player != null && player.hasPermissions(2);

        Button tpBtn = addRenderableWidget(Button.builder(tpLabel,
                b -> BlueprintNetwork.CHANNEL.sendToServer(new C2S_VoidTeleportMessage()))
                .bounds(cx, btnY, wTp, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.btn.tp.tip")))
                .build());
        tpBtn.active = isOp && hasCoords;
        cx += wTp + BTN_GAP;

        Button viewBtn = addRenderableWidget(Button.builder(viewLabel, b -> onViewRange())
                .bounds(cx, btnY, w1, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.view_range.tip")))
                .build());
        viewBtn.active = hasCoords;
        cx += w1 + BTN_GAP;

        Button storeBtn = addRenderableWidget(Button.builder(storeLabel, b -> onStore())
                .bounds(cx, btnY, w2, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.store.tip")))
                .build());
        storeBtn.active = hasCoords;
        cx += w2 + BTN_GAP;

        addRenderableWidget(Button.builder(resetLabel, b -> onReset())
                .bounds(cx, btnY, w3, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.reset.tip")))
                .build());
        cx += w3 + BTN_GAP;

        addRenderableWidget(Button.builder(cancelLabel, b -> onCancel())
                .bounds(cx, btnY, w4, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.cancel.tip")))
                .build());
    }

    private void initResetConfirm() {
        Component okLabel     = Component.translatable("spatial_blueprint.gui.library.confirm_delete.ok");
        Component cancelLabel = Component.translatable("spatial_blueprint.gui.library.confirm_delete.cancel");

        int okW  = Math.max(BTN_MIN_W, this.font.width(okLabel)     + 12);
        int canW = Math.max(BTN_MIN_W, this.font.width(cancelLabel) + 12);
        int confirmY = this.height / 2 + 2;
        int totalW = okW + canW + BTN_GAP;
        int startX = (this.width - totalW) / 2;

        addRenderableWidget(Button.builder(okLabel, b -> confirmReset())
                .bounds(startX, confirmY, okW, BTN_H).build());
        addRenderableWidget(Button.builder(cancelLabel, b -> cancelResetConfirm())
                .bounds(startX + okW + BTN_GAP, confirmY, canW, BTN_H).build());
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        if (showResetConfirm) {
            renderResetConfirmOverlay(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 透明背景：不绘制暗色遮罩，让世界透过显示
    }

    private void renderResetConfirmOverlay(GuiGraphics graphics) {
        int panelW = 180;
        int panelH = 70;
        int panelX = (this.width  - panelW) / 2;
        int panelY = (this.height - panelH) / 2 - 10;

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xCC000000);
        // 边框
        graphics.fill(panelX,           panelY,           panelX + panelW, panelY + 1,       0xFF555555);
        graphics.fill(panelX,           panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF555555);
        graphics.fill(panelX,           panelY,           panelX + 1,       panelY + panelH, 0xFF555555);
        graphics.fill(panelX + panelW - 1, panelY,        panelX + panelW, panelY + panelH, 0xFF555555);

        Component title   = Component.translatable("spatial_blueprint.gui.void_dimensional.reset_title");
        Component warning = Component.translatable("spatial_blueprint.gui.void_dimensional.reset_warning")
                .copy().withStyle(ChatFormatting.RED);

        graphics.drawCenteredString(this.font, title,   this.width / 2, panelY + 10, 0xFFFFFF);
        graphics.drawCenteredString(this.font, warning, this.width / 2, panelY + 26, 0xFF5555);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showResetConfirm) {
                cancelResetConfirm();
            } else {
                onCancel();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 按钮行为 ──────────────────────────────────────────────────────────

    private void onViewRange() {
        BlockPos min = VoidDimensionalBlueprintItem.getCaptureMin(stack);
        BlockPos max = VoidDimensionalBlueprintItem.getCaptureMax(stack);
        if (min != null && max != null) {
            BlueprintSelectionRenderer.setVoidPreview(min, max);
        }
        this.onClose();
    }

    private void onStore() {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_VoidRecaptureMessage());
        BlueprintSelectionRenderer.clearVoidPreview();
        this.onClose();
    }

    private void onReset() {
        showResetConfirm = true;
        clearWidgets();
        init();
    }

    private void onCancel() {
        BlueprintSelectionRenderer.clearVoidPreview();
        this.onClose();
    }

    private void confirmReset() {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_VoidResetMessage());
        BlueprintSelectionRenderer.clearVoidPreview();
        this.onClose();
    }

    private void cancelResetConfirm() {
        showResetConfirm = false;
        clearWidgets();
        init();
    }
}
