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
 * 工程蓝图「安全传送坐标」设定页：蓝图库工程 Tab 选中一行 → [TP] 打开，仅 OP。
 *
 * <p>三个数字框留空 = 不设（[Tp] 传起 / 终点取近）；[设定当前坐标] 填玩家脚下；
 * [保存] 发 {@link C2S_SafeCoordMessage} 存服务端 SavedData。传送权限不在这里管
 * （完全归 {@code /sb tpperm}），所以没有放行开关。</p>
 */
@OnlyIn(Dist.CLIENT)
public class SafeCoordScreen extends Screen implements ErrorToastSupport {

    private final Screen  parent;
    private final String  blueprintId;
    private final String  fixedDimension;

    // 打开时摘要里的现值（服务端 C2S_RequestLibraryMessage 合并进摘要）
    private final boolean initCoordSet;
    private final int     initX, initY, initZ;

    private EditBox boxX, boxY, boxZ;

    @Nullable private Component errorToast;

    private static final int PAD       = 15;
    private static final int W         = 310;
    private static final int H         = 118;   // 标题 / 维度 / 提示 / 三框 / 一排按钮（原有放行开关那排去掉后收窄）
    private static final int BTN_H     = 18;
    private static final int B_GAP     = 5;
    private static final int CONTENT_W = W - PAD * 2;          // 280
    private static final int BTN_SET_W = CONTENT_W - B_GAP * 2 - 62 - 62; // 146
    private static final int BTN_SMALL = 62;

    public SafeCoordScreen(Screen parent, BlueprintStorage.BlueprintSummary summary) {
        super(Component.translatable("spatial_blueprint.gui.safe_coord.title"));
        this.parent         = parent;
        this.blueprintId    = summary.id();
        this.fixedDimension = summary.fixedDimension() != null ? summary.fixedDimension() : "";
        this.initCoordSet   = summary.safeCoordSet();
        this.initX          = summary.safeX();
        this.initY          = summary.safeY();
        this.initZ          = summary.safeZ();
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width  / 2;
        int cy = this.height / 2;
        int px = cx - W / 2;
        int py = cy - H / 2;

        // ── X / Y / Z 输入框 ─────────────────────────────────────────────
        int fieldGap = 6;
        int fieldW   = (CONTENT_W - fieldGap * 2) / 3;
        int fieldH   = 20;
        int fieldY   = py + 54;
        int fStartX  = px + PAD;

        // 窗口缩放会重跑 init：保留已输入的值
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

        // ── 底排：[设定当前坐标] [保存] [取消] ───────────────────────────
        int rowY = py + H - PAD - BTN_H;
        int rowX = px + PAD;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.safe_coord.set_current"),
                b -> setCurrentCoord())
            .bounds(rowX, rowY, BTN_SET_W, BTN_H).build());
        rowX += BTN_SET_W + B_GAP;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.safe_coord.save"),
                b -> save())
            .bounds(rowX, rowY, BTN_SMALL, BTN_H).build());
        rowX += BTN_SMALL + B_GAP;

        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.safe_coord.cancel"),
                b -> onClose())
            .bounds(rowX, rowY, BTN_SMALL, BTN_H).build());
    }

    private void setCurrentCoord() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        boxX.setValue(String.valueOf((int) Math.floor(player.getX())));
        boxY.setValue(String.valueOf((int) Math.floor(player.getY())));
        boxZ.setValue(String.valueOf((int) Math.floor(player.getZ())));
    }

    /** 三框齐全且都是整数才算「设了」；任一为空或解析失败 = 清除（服务端 hasCoord=false）。 */
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
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_SafeCoordMessage(blueprintId, hasCoord, x, y, z));
        // 蓝图库只在切 Tab 时拉列表，这里主动再拉一次：服务端按顺序处理，新列表已带刚存的坐标，
        // 库界面按 id 重解析选中行，[TP] tooltip 的「当前：x, y, z」随之刷新
        BlueprintNetwork.CHANNEL.sendToServer(new com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage());
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

        graphics.drawCenteredString(font, this.title, cx, py + 6, 0xFFFFAA);

        // 维度名与项目维度横幅同一份映射（ProjectData.dimensionName）
        String dimDisplay = fixedDimension.isEmpty() ? "?"
                : com.xioyim.spatialblueprint.util.ProjectData.dimensionName(fixedDimension).getString();
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.safe_coord.dimension", dimDisplay).getString(),
                cx, py + 20, 0xAAAAAA);

        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.safe_coord.coord_label").getString(),
                cx, py + 38, 0x777777);

        // 框内左缘的 X / Y / Z 标签
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
