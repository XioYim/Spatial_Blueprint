package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_SafeCoordMessage;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * UI for setting the safe teleport coordinate for an engineering blueprint.
 * OP-only. Opened from the engineering menu by clicking a project name.
 */
@OnlyIn(Dist.CLIENT)
public class SafeCoordScreen extends Screen implements ErrorToastSupport {

    private final Screen  parent;
    private final String  blueprintId;
    private final String  fixedDimension;

    // Initial values from existing saved data
    private final boolean initCoordSet;
    private final int     initX, initY, initZ;
    private final boolean initAllowSurvival;

    private EditBox boxX, boxY, boxZ;
    private boolean allowSurvival;

    @Nullable private Component errorToast;

    // Panel dimensions — wide enough for "Allow survival teleport: OFF" to fit fully
    private static final int PAD       = 15;
    private static final int W         = 310;
    private static final int H         = 132;
    private static final int BTN_H     = 18;
    private static final int B_GAP     = 5;
    private static final int CONTENT_W = W - PAD * 2;          // 280
    private static final int BTN_SET_W = CONTENT_W - B_GAP * 2 - 62 - 62; // 146
    private static final int BTN_SMALL = 62;

    public SafeCoordScreen(Screen parent, BlueprintStorage.BlueprintSummary summary) {
        super(Component.translatable("spatial_blueprint.gui.safe_coord.title"));
        this.parent          = parent;
        this.blueprintId     = summary.id();
        this.fixedDimension  = summary.fixedDimension() != null ? summary.fixedDimension() : "";
        this.initCoordSet    = summary.safeCoordSet();
        this.initX           = summary.safeX();
        this.initY           = summary.safeY();
        this.initZ           = summary.safeZ();
        this.initAllowSurvival = summary.safeAllowSurvival();
        this.allowSurvival   = initAllowSurvival;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width  / 2;
        int cy = this.height / 2;
        int px = cx - W / 2;
        int py = cy - H / 2;

        // ── X / Y / Z edit boxes ──────────────────────────────────────────
        int fieldGap = 6;
        int fieldW   = (CONTENT_W - fieldGap * 2) / 3;
        int fieldH   = 20;
        int fieldY   = py + 54;
        int fStartX  = px + PAD;

        // Preserve typed values across re-init (toggle causes clearWidgets+init)
        String prevX = boxX != null ? boxX.getValue() : (initCoordSet ? String.valueOf(initX) : "");
        String prevY = boxY != null ? boxY.getValue() : (initCoordSet ? String.valueOf(initY) : "");
        String prevZ = boxZ != null ? boxZ.getValue() : (initCoordSet ? String.valueOf(initZ) : "");

        boxX = new EditBox(font, fStartX,                         fieldY, fieldW, fieldH, Component.literal("X"));
        boxY = new EditBox(font, fStartX + fieldW + fieldGap,     fieldY, fieldW, fieldH, Component.literal("Y"));
        boxZ = new EditBox(font, fStartX + (fieldW + fieldGap)*2, fieldY, fieldW, fieldH, Component.literal("Z"));
        boxX.setValue(prevX); boxY.setValue(prevY); boxZ.setValue(prevZ);
        for (EditBox box : new EditBox[]{boxX, boxY, boxZ}) {
            box.setMaxLength(8);
            box.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{0,7}"));
            addRenderableWidget(box);
        }
        if (getFocused() == null) setFocused(boxX);

        // ── Row 1: [设定为当前坐标]  [保存]  [取消] ─────────────────────
        int row1Y = py + H - 44;
        int row1X = px + PAD;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.safe_coord.set_current"),
                b -> setCurrentCoord())
            .bounds(row1X, row1Y, BTN_SET_W, BTN_H).build());
        row1X += BTN_SET_W + B_GAP;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.safe_coord.save"),
                b -> save())
            .bounds(row1X, row1Y, BTN_SMALL, BTN_H).build());
        row1X += BTN_SMALL + B_GAP;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.safe_coord.cancel"),
                b -> onClose())
            .bounds(row1X, row1Y, BTN_SMALL, BTN_H).build());

        // ── Row 2: [允许生存玩家传送:开/关] full-width ───────────────────
        int row2Y = row1Y + BTN_H + B_GAP;
        addRenderableWidget(Button.builder(
                buildAllowSurvivalLabel(),
                b -> { allowSurvival = !allowSurvival; clearWidgets(); init(); })
            .bounds(px + PAD, row2Y, CONTENT_W, BTN_H).build());
    }

    private Component buildAllowSurvivalLabel() {
        String state = allowSurvival
                ? Component.translatable("spatial_blueprint.gui.safe_coord.allow_survival.on").getString()
                : Component.translatable("spatial_blueprint.gui.safe_coord.allow_survival.off").getString();
        return Component.translatable("spatial_blueprint.gui.safe_coord.allow_survival", state);
    }

    private void setCurrentCoord() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        boxX.setValue(String.valueOf((int) Math.floor(player.getX())));
        boxY.setValue(String.valueOf((int) Math.floor(player.getY())));
        boxZ.setValue(String.valueOf((int) Math.floor(player.getZ())));
    }

    private void save() {
        String sx = boxX.getValue().trim();
        String sy = boxY.getValue().trim();
        String sz = boxZ.getValue().trim();
        boolean hasCoord = !sx.isEmpty() && !sy.isEmpty() && !sz.isEmpty();
        int x = 0, y = 0, z = 0;
        if (hasCoord) {
            try {
                x = Integer.parseInt(sx);
                y = Integer.parseInt(sy);
                z = Integer.parseInt(sz);
            } catch (NumberFormatException ignored) {
                hasCoord = false;
            }
        }
        BlueprintNetwork.CHANNEL.sendToServer(
                new C2S_SafeCoordMessage(blueprintId, hasCoord, x, y, z, allowSurvival));
        onClose();
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
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { save(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }

        int cx = this.width  / 2;
        int cy = this.height / 2;
        int px = cx - W / 2;
        int py = cy - H / 2;

        graphics.fill(px, py, px + W, py + H, 0xD0101010);
        drawBorder(graphics, px, py, W, H);

        // Title
        graphics.drawCenteredString(font, this.title, cx, py + 6, 0xFFFFAA);

        // Dimension row
        String dimKey = "spatial_blueprint.dimension." + fixedDimension.replace("minecraft:", "");
        String dimDisplay = fixedDimension.isEmpty() ? "?" : Component.translatable(dimKey).getString();
        if (dimDisplay.equals(dimKey)) dimDisplay = fixedDimension;
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.safe_coord.dimension", dimDisplay).getString(),
                cx, py + 20, 0xAAAAAA);

        // Coord label
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.safe_coord.coord_label").getString(),
                cx, py + 38, 0x777777);

        // X / Y / Z inline labels (left-edge of each box)
        int fieldGap = 6;
        int fieldW   = (CONTENT_W - fieldGap * 2) / 3;
        int fieldY   = py + 54;
        int fStartX  = px + PAD;
        int labelY   = fieldY + (20 - font.lineHeight) / 2;
        graphics.drawString(font, "X", fStartX + 4, labelY, 0xCCCCCC, false);
        graphics.drawString(font, "Y", fStartX + fieldW + fieldGap + 4, labelY, 0xCCCCCC, false);
        graphics.drawString(font, "Z", fStartX + (fieldW + fieldGap) * 2 + 4, labelY, 0xCCCCCC, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
