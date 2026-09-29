package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.S2C_VoidBagSyncMessage;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读的「次元蓝图材料空间」查看界面，由材料空间界面的 ✦ 按钮打开。
 *
 * <p>列出当前仍持有次元袋及已放置后消耗过次元袋的蓝图。FORCE 捕获的蓝图本就不入袋，
 * 不会出现在这里。</p>
 *
 * <p>每行：左侧蓝图名（悬浮显示与蓝图列表相同的 tooltip，另加一行 UUID），
 * 右侧状态。状态只在<b>异常</b>时才带括号说明 —— 正常情况就是「未放置」。</p>
 */
@OnlyIn(Dist.CLIENT)
public class VoidBagSpaceScreen extends Screen {

    private static final int W        = 420;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    private static final int ROW_H    = 14;
    private static final int MAX_ROWS = 12;

    private final String                          targetName;
    private final List<S2C_VoidBagSyncMessage.Entry> entries;

    private int scrollRow = 0;
    private int gl, gt, panelH, listH, listX, listY, listW;
    private int effectiveMaxRows = MAX_ROWS;

    /** 当前鼠标悬浮的行索引（列表内绝对索引），-1 表示无。 */
    private int hoveredRow = -1;

    /** 「点击删除清单」文本的命中范围，按行索引记录；每帧重建。 */
    private final java.util.Map<Integer, int[]> discardHitboxes = new java.util.HashMap<>();

    /** 正在确认丢弃的孤儿袋蓝图 ID；null = 无弹窗。 */
    @Nullable private String confirmDiscardId = null;

    // 确认弹窗尺寸（render 与 mouseClicked 共享）
    private static final int CD_W = 300, CD_H = 96, CD_BW = 76, CD_BH = 18, CD_BP = 10;

    public VoidBagSpaceScreen(String targetName, List<S2C_VoidBagSyncMessage.Entry> entries) {
        super(Component.translatable("spatial_blueprint.gui.void_bag_space.title"));
        this.targetName = targetName == null ? "" : targetName;
        this.entries    = new ArrayList<>(entries);
    }

    // ── init ──────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        gl = (width - W) / 2;

        int available    = height - 16 - HEADER_H - 2 * PAD - FOOTER_H;
        effectiveMaxRows = Math.max(3, Math.min(MAX_ROWS, available / ROW_H));
        int totalRows    = Math.max(1, entries.size());
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, entries.size() - effectiveMaxRows)));
        listH  = Math.min(effectiveMaxRows, totalRows) * ROW_H;
        panelH = HEADER_H + PAD + listH + PAD + FOOTER_H;
        gt     = Math.max(8, (height - panelH) / 2);
        listX  = gl + PAD;
        listY  = gt + HEADER_H + PAD;
        listW  = W - PAD * 2;

        // 确认弹窗打开时只放弹窗自己的两个按钮：原版 Button 才有正确的悬停 / 按下反馈，
        // 手绘矩形在不同资源包下和界面其余部分对不上。
        if (confirmDiscardId != null) {
            int dx = (width - CD_W) / 2, dy = (height - CD_H) / 2;
            int by = dy + CD_H - CD_BH - CD_BP;
            addRenderableWidget(Button.builder(
                            Component.translatable("spatial_blueprint.gui.common.confirm")
                                    .withStyle(ChatFormatting.RED),
                            b -> {
                                com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL.sendToServer(
                                        new com.xioyim.spatialblueprint.network.C2S_DiscardOrphanBagMessage(
                                                confirmDiscardId));
                                // 服务端删完会回推整份列表并重开本界面，这里不做乐观更新
                                confirmDiscardId = null;
                                clearWidgets(); init();
                            })
                    .bounds(dx + CD_W / 2 - CD_BW - 4, by, CD_BW, CD_BH)
                    .build());
            addRenderableWidget(Button.builder(
                            Component.translatable("spatial_blueprint.gui.common.cancel"),
                            b -> { confirmDiscardId = null; clearWidgets(); init(); })
                    .bounds(dx + CD_W / 2 + 4, by, CD_BW, CD_BH)
                    .build());
            return;
        }

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.close"),
                        b -> onClose())
                .bounds(gl + W / 2 - 50, gt + panelH - FOOTER_H + 5, 100, BTN_H)
                .build());
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hoveredRow = -1;

        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);

        Component titleComp = targetName.isEmpty()
                ? Component.translatable("spatial_blueprint.gui.void_bag_space.title")
                : Component.translatable("spatial_blueprint.gui.void_bag_space.title_other", targetName);
        g.drawString(font, titleComp, gl + W / 2 - font.width(titleComp) / 2, gt + 5, 0xFFFFFF, false);

        Component usage = Component.translatable("spatial_blueprint.gui.void_bag_space.usage", entries.size());
        g.drawString(font, usage, gl + W / 2 - font.width(usage) / 2, gt + 17, 0xAAAAAA, false);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);

        if (entries.isEmpty()) {
            Component empty = Component.translatable("spatial_blueprint.gui.void_bag_space.empty");
            g.drawString(font, empty, gl + W / 2 - font.width(empty) / 2,
                    listY + listH / 2 - 4, 0x777777, false);
            super.render(g, mx, my, pt);
            return;
        }

        discardHitboxes.clear();
        int lastRow = Math.min(scrollRow + effectiveMaxRows, entries.size());
        for (int i = scrollRow; i < lastRow; i++) {
            int ry = listY + (i - scrollRow) * ROW_H;
            S2C_VoidBagSyncMessage.Entry e = entries.get(i);
            BlueprintStorage.BlueprintSummary s = e.summary();
            boolean lost = e.isLost();

            boolean rowHovered = mx >= listX && mx < listX + listW && my >= ry && my < ry + ROW_H;
            if (rowHovered) g.fill(listX, ry, listX + listW, ry + ROW_H, 0x22FFFFFF);

            int textY = ry + (ROW_H - font.lineHeight) / 2;

            // ── 右侧：状态；丢失的蓝图额外挂一个「点击删除清单」──────────
            int rightEdge = listX + listW - 4;
            if (lost) {
                Component discard = Component.translatable(
                        "spatial_blueprint.gui.void_bag_space.discard").withStyle(ChatFormatting.RED);
                int dW = font.width(discard);
                int dX = rightEdge - dW;
                boolean dHover = mx >= dX && mx < dX + dW && my >= ry && my < ry + ROW_H;
                g.drawString(font, dHover
                                ? discard.copy().withStyle(ChatFormatting.UNDERLINE) : discard,
                        dX, textY, 0xFFFFFF, false);
                discardHitboxes.put(i, new int[]{dX, ry, dX + dW, ry + ROW_H});
                rightEdge = dX - 6;
            }

            Component status = lost
                    ? Component.translatable("spatial_blueprint.gui.void_bag_space.status.lost")
                            .withStyle(ChatFormatting.RED)
                    : buildStatus(s);
            int statusW = font.width(status);
            int statusX = rightEdge - statusW;
            g.drawString(font, status, statusX, textY, 0xFFFFFF, false);

            // ── 左侧：蓝图名（截断到不与状态重叠）─────────────────────
            // 蓝图丢失时名字取自次元袋里的快照 —— 文件都没了，名字没别处可查
            String rawName = lost
                    ? (e.lostName() == null || e.lostName().isEmpty()
                            ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                            : e.lostName())
                    : (s.name().isEmpty()
                            ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                            : s.name());
            int nameX = listX + 4;
            int maxNameW = Math.max(0, statusX - 8 - nameX);
            String shownName = truncate(rawName, maxNameW);
            if (lost) {
                // 灰色 + 删除线：这个蓝图已经不存在了
                g.drawString(font, Component.literal(shownName)
                                .withStyle(ChatFormatting.GRAY, ChatFormatting.STRIKETHROUGH),
                        nameX, textY, 0xFFFFFF, false);
            } else {
                g.drawString(font, shownName, nameX, textY, 0xEEEEEE, false);
            }

            // 悬浮在名字上才出 tooltip（丢失的蓝图没有摘要，无从构建）
            if (!lost && rowHovered && mx >= nameX && mx < nameX + font.width(shownName)) {
                hoveredRow = i;
            }
        }

        // 确认弹窗画在 super.render 之前：两个按钮现在是原版 Widget，由 super.render 绘制，
        // 弹窗底板若压在它们上面就会把按钮盖掉。
        if (confirmDiscardId != null) renderDiscardConfirm(g);

        super.render(g, mx, my, pt);

        // tooltip 最后渲染，压在按钮之上；弹窗打开时不显示，免得盖在弹窗上
        if (confirmDiscardId == null && hoveredRow >= 0 && hoveredRow < entries.size()
                && entries.get(hoveredRow).summary() != null) {
            g.renderTooltip(font,
                    BlueprintTooltips.build(entries.get(hoveredRow).summary(), true),
                    mx, my);
        }
    }

    /** 丢弃孤儿袋的确认弹窗 —— 材料永久消失，必须让玩家明确点一次。 */
    private void renderDiscardConfirm(GuiGraphics g) {
        int dx = (width - CD_W) / 2, dy = (height - CD_H) / 2;
        g.fill(0, 0, width, height, 0x99000000);
        g.fill(dx - 1, dy - 1, dx + CD_W + 1, dy + CD_H + 1, 0xFF666666);
        g.fill(dx, dy, dx + CD_W, dy + CD_H, 0xFF1A1A1A);

        Component title = Component.translatable("spatial_blueprint.gui.void_bag_space.discard_confirm.title");
        g.drawString(font, title, dx + CD_W / 2 - font.width(title) / 2, dy + 10, 0xFFFFAA, false);

        Component l1 = Component.translatable("spatial_blueprint.gui.void_bag_space.discard_confirm.line1");
        Component l2 = Component.translatable("spatial_blueprint.gui.void_bag_space.discard_confirm.line2");
        g.drawString(font, l1, dx + CD_W / 2 - font.width(l1) / 2, dy + 30, 0xFF5555, false);
        g.drawString(font, l2, dx + CD_W / 2 - font.width(l2) / 2, dy + 42, 0xAAAAAA, false);
    }

    /**
     * 状态文本。
     *
     * <p>{@code placed == true} 表示结构已经还原到世界。生存模式下袋子已消耗，创造模式下
     * 袋子仍保留供重新储存复用；对玩家统一显示「已放置(材料已使用)」。</p>
     */
    private static Component buildStatus(BlueprintStorage.BlueprintSummary s) {
        if (s.placed()) {
            MutableComponent c = Component.translatable("spatial_blueprint.gui.void_bag_space.status.placed")
                    .withStyle(ChatFormatting.YELLOW);
            c.append(Component.translatable("spatial_blueprint.gui.void_bag_space.status.used")
                    .withStyle(ChatFormatting.GRAY));
            return c;
        }
        return Component.translatable("spatial_blueprint.gui.void_bag_space.status.unplaced")
                .withStyle(ChatFormatting.GREEN);
    }

    private String truncate(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int room = maxWidth - font.width(ellipsis);
        if (room <= 0) return "";
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }

    // ── 事件 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;

        // ── 确认弹窗打开时吞掉所有点击 ────────────────────────────────────
        // 两个按钮是原版 Widget，交给 super 处理；这里只负责「点弹窗外面 = 取消」。
        if (confirmDiscardId != null) {
            int dx = (width - CD_W) / 2, dy = (height - CD_H) / 2;
            if (super.mouseClicked(mouseX, mouseY, button)) return true;
            if (mx < dx || mx >= dx + CD_W || my < dy || my >= dy + CD_H) {
                confirmDiscardId = null;
                clearWidgets(); init();
            }
            return true;
        }

        // ── 「点击删除清单」──────────────────────────────────────────────
        if (button == 0) {
            for (java.util.Map.Entry<Integer, int[]> hit : discardHitboxes.entrySet()) {
                int[] b = hit.getValue();
                if (mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3]) {
                    int idx = hit.getKey();
                    if (idx >= 0 && idx < entries.size()) {
                        confirmDiscardId = entries.get(idx).blueprintId();
                        clearWidgets(); init();   // 换成弹窗自己的两个原版按钮
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (confirmDiscardId != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                confirmDiscardId = null;
                clearWidgets(); init();
            }
            return true;   // 弹窗期间吞掉所有按键
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (confirmDiscardId != null) return true;
        if (mx < listX || mx >= listX + listW || my < listY || my >= listY + listH)
            return super.mouseScrolled(mx, my, delta);
        int maxScroll = Math.max(0, entries.size() - effectiveMaxRows);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
