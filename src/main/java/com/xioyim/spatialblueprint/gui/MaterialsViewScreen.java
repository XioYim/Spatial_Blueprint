package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestMaterialSpaceMessage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 「查看材料清单」—— 只读页，UI/排版与放置/工程界面的材料清单面板一致
 * （深底面板 + 金色标题 + 「N 种/共 M 个」 + 4 列图标网格 + 数量 + 物品 tooltip + 滚动条）。
 *
 * <p>格子的数量：空间 / 次元蓝图只显示总需求；其余分类显示「玩家材料空间已有 / 需求」
 * （同正常放置蓝图）。没有任何修改 / 存入按钮，仅 [关闭]。</p>
 */
@OnlyIn(Dist.CLIENT)
public class MaterialsViewScreen extends Screen {

    // ── 与放置/工程界面材料清单面板一致的布局常量 ──────────────────────────
    private static final int MAT_PANEL_W   = 298;
    private static final int MAT_PAD       = 8;
    private static final int MAT_HEADER_H  = 12;
    private static final int MAT_SEP_PAD   = 4;
    private static final int MAT_SB_W      = 4;
    private static final int MAT_SB_GAP    = 2;
    private static final int MAT_CONTENT_W = MAT_PANEL_W - MAT_PAD * 2 - MAT_SB_W - MAT_SB_GAP;
    private static final int MAT_COLS      = 4;
    private static final int MAT_CELL_W    = MAT_CONTENT_W / MAT_COLS;
    private static final int MAT_CELL_H    = 17;
    private static final int MAT_VISIBLE   = 6;
    private static final float MAT_SCALE   = 0.75f;
    private static final int MAT_ICON_SCR  = 16;

    private final Screen parent;
    /** 蓝图分类：create / dimensional 只显示总需求，不显示「已有/需求」。 */
    private final boolean onlyTotal;
    private final List<String> keys = new ArrayList<>();
    private final List<Integer> need = new ArrayList<>();

    private int panelX, panelY, contentY;
    private int matScrollRow = 0;

    public MaterialsViewScreen(Screen parent, String category,
                               List<String> keys, List<Integer> counts) {
        super(Component.translatable("spatial_blueprint.gui.upload_report.title"));
        this.parent = parent;
        this.onlyTotal = "create".equals(category) || "dimensional".equals(category);
        if (keys != null && counts != null) {
            int n = Math.min(keys.size(), counts.size());
            for (int i = 0; i < n; i++) {
                this.keys.add(keys.get(i));
                this.need.add(counts.get(i));
            }
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void init() {
        super.init();
        int contentH = MAT_VISIBLE * MAT_CELL_H;
        int panelH   = MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD + contentH + MAT_PAD;
        panelX = this.width / 2 - MAT_PANEL_W / 2;
        panelY = Math.max(8, this.height / 2 - panelH / 2 - 16);
        contentY = panelY + MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD;

        // 拉最新玩家材料空间（与正常蓝图界面同源；只刷缓存，不开屏）
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestMaterialSpaceMessage());

        Component closeLabel = Component.translatable("spatial_blueprint.gui.library.close");
        int closeW = font.width(closeLabel) + 16;
        addRenderableWidget(Button.builder(closeLabel, b -> onClose())
                .bounds(panelX + MAT_PANEL_W / 2 - closeW / 2, panelY + panelH + 8, closeW, 18)
                .build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int bottom = contentY + MAT_VISIBLE * MAT_CELL_H;
        if (mouseX >= panelX && mouseX < panelX + MAT_PANEL_W
                && mouseY >= contentY && mouseY < bottom) {
            int totalRows = (keys.size() + MAT_COLS - 1) / MAT_COLS;
            int max = Math.max(0, totalRows - MAT_VISIBLE);
            matScrollRow = clamp(matScrollRow - (int) Math.signum(delta), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int contentH = MAT_VISIBLE * MAT_CELL_H;
        int panelH   = MAT_PAD + MAT_HEADER_H + 1 + MAT_SEP_PAD + contentH + MAT_PAD;

        // 面板
        graphics.fill(panelX, panelY, panelX + MAT_PANEL_W, panelY + panelH, 0xD0101010);
        drawBorder(graphics, panelX, panelY, MAT_PANEL_W, panelH);

        // 标题行（与放置/工程界面同款）
        String title = Component.translatable("spatial_blueprint.gui.placement.material_list").getString();
        int totalNeed = 0;
        for (int c : need) totalNeed += c;
        String sub = Component.translatable("spatial_blueprint.gui.placement.material_list_header",
                keys.size(), totalNeed).getString();
        graphics.drawString(font, title, panelX + MAT_PAD, panelY + MAT_PAD, 0xFFFFAA, false);
        graphics.drawString(font, sub,
                panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W - MAT_SB_GAP - font.width(sub),
                panelY + MAT_PAD, 0x888888, false);

        // 分割线
        int sepY = panelY + MAT_PAD + MAT_HEADER_H;
        graphics.fill(panelX + MAT_PAD, sepY,
                panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W - MAT_SB_GAP, sepY + 1, 0xFF444444);

        // 滚动条槽
        int sbX = panelX + MAT_PANEL_W - MAT_PAD - MAT_SB_W;
        graphics.fill(sbX, contentY, sbX + MAT_SB_W, contentY + contentH, 0xFF2A2A2A);
        int totalRows = (keys.size() + MAT_COLS - 1) / MAT_COLS;
        if (totalRows > MAT_VISIBLE) {
            int maxScroll = totalRows - MAT_VISIBLE;
            int thumbH = Math.max(8, contentH * MAT_VISIBLE / totalRows);
            int thumbY = contentY + (contentH - thumbH) * matScrollRow / maxScroll;
            graphics.fill(sbX, thumbY, sbX + MAT_SB_W, thumbY + thumbH, 0xFF888888);
        }

        // 内容区
        ItemStack hoveredStack = ItemStack.EMPTY;
        graphics.enableScissor(panelX + MAT_PAD, contentY, panelX + MAT_PANEL_W - MAT_PAD, contentY + contentH);
        graphics.fill(panelX + MAT_PAD, contentY, panelX + MAT_PAD + MAT_CONTENT_W, contentY + contentH, 0x801A1A1A);

        if (keys.isEmpty()) {
            Component freeMsg = Component.translatable("spatial_blueprint.gui.placement.material_list.free");
            graphics.drawString(font, freeMsg,
                    panelX + MAT_PAD + (MAT_CONTENT_W - font.width(freeMsg)) / 2,
                    contentY + (contentH - font.lineHeight) / 2, 0x88DD88, false);
        } else {
            for (int i = 0; i < keys.size(); i++) {
                int row = i / MAT_COLS, col = i % MAT_COLS;
                int ex = panelX + MAT_PAD + col * MAT_CELL_W;
                int ey = contentY + (row - matScrollRow) * MAT_CELL_H;
                if (ey < contentY || ey + MAT_ICON_SCR > contentY + MAT_VISIBLE * MAT_CELL_H) continue;

                ItemStack stack = MaterialList.buildDisplayStack(
                        keys.get(i), ClientMaterialSpaceCache.tagFor(keys.get(i)));
                if (!stack.isEmpty()) graphics.renderItem(stack, ex, ey);

                // 数量：空间/次元只显总需求；其余显「材料空间已有 / 需求」
                int dep = onlyTotal ? 0 : ClientMaterialSpaceCache.count(keys.get(i));
                String countStr;
                int countColor;
                if (onlyTotal) {
                    countStr = String.valueOf(need.get(i));
                    countColor = 0xAAAAAA;
                } else {
                    countStr = dep + "/" + need.get(i);
                    countColor = dep >= need.get(i) ? 0x55FF55 : 0xFFAA00;
                }
                graphics.pose().pushPose();
                graphics.pose().scale(MAT_SCALE, MAT_SCALE, 1f);
                int tx = Math.round((ex + MAT_ICON_SCR + 2) / MAT_SCALE);
                int ty = Math.round((ey + (MAT_CELL_H - font.lineHeight * MAT_SCALE) / 2f) / MAT_SCALE);
                graphics.drawString(font, countStr, tx, ty, countColor, false);
                graphics.pose().popPose();

                if (hoveredStack.isEmpty()
                        && mouseX >= ex && mouseX < ex + MAT_ICON_SCR
                        && mouseY >= ey && mouseY < ey + MAT_ICON_SCR) {
                    hoveredStack = stack;
                }
            }
        }
        graphics.disableScissor();

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!hoveredStack.isEmpty()) {
            graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
        }
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }
}
