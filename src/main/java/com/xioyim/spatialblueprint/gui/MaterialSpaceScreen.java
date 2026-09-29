package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读的材料空间查看界面，由 {@code /sb space [玩家]} 打开。
 *
 * <p>只显示<b>普通池</b>（玩家手动存入、供投影 / 工程蓝图消耗的部分）。次元袋的材料绑定
 * 蓝图 ID、玩家无法取出，因此不在此展示。</p>
 *
 * <p>布局参考「参考 · 方块替换表」页：网格排布，每格为物品图标 + 数量，
 * 悬浮到图标上显示完整物品 tooltip（附魔、耐久等一并可见）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class MaterialSpaceScreen extends Screen {

    private static final int W        = 480;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    private static final int COLS     = 5;
    private static final int MAX_ROWS = 12;
    private static final int CELL_H   = 22;
    private static final int GAP      = 3;
    private static final int ICON     = 16;

    private final String                   targetName;
    /** 被查看玩家的 UUID —— ✦ 按钮要带着它继续查同一个人的次元袋。 */
    private final String                   targetUuid;
    private final Map<String, Integer>     counts;
    private final Map<String, CompoundTag> tags;
    private final int                      total;
    private final int                      capacity;
    private final List<String>             keys;

    private int scrollRows = 0;
    private int gl, gt, panelH, listH, listX, listY, listW, cellW;
    private int effectiveMaxRows = MAX_ROWS;

    public MaterialSpaceScreen(String targetName, String targetUuid, Map<String, Integer> counts,
                               Map<String, CompoundTag> tags, int total, int capacity) {
        super(Component.translatable("spatial_blueprint.gui.material_space.title"));
        this.targetName = targetName == null ? "" : targetName;
        this.targetUuid = targetUuid == null ? "" : targetUuid;
        this.counts     = new LinkedHashMap<>(counts);
        this.tags       = new LinkedHashMap<>(tags);
        this.total      = total;
        this.capacity   = capacity;
        this.keys       = new ArrayList<>(this.counts.keySet());
    }

    // ── init ──────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        gl = (width - W) / 2;

        int available    = height - 16 - HEADER_H - 2 * PAD - FOOTER_H;
        effectiveMaxRows = Math.max(3, Math.min(MAX_ROWS, available / CELL_H));
        int totalRows    = Math.max(1, (keys.size() + COLS - 1) / COLS);
        scrollRows = Math.max(0, Math.min(scrollRows, Math.max(0, totalRows - effectiveMaxRows)));
        listH  = Math.min(effectiveMaxRows, totalRows) * CELL_H;
        panelH = HEADER_H + PAD + listH + PAD + FOOTER_H;
        gt     = Math.max(8, (height - panelH) / 2);
        listX  = gl + PAD;
        listY  = gt + HEADER_H + PAD;
        listW  = W - PAD * 2;
        cellW  = (listW - GAP * (COLS - 1)) / COLS;

        int btnY = gt + panelH - FOOTER_H + 5;
        int closeX = gl + W / 2 - 50;

        // ✦ 查看次元蓝图材料空间（关闭按钮左侧）
        addRenderableWidget(Button.builder(Component.literal("✦"), b -> openVoidBagSpace())
                .bounds(closeX - BTN_H - 4, btnY, BTN_H, BTN_H)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.void_bag_space.btn.tip")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.close"),
                        b -> onClose())
                .bounds(closeX, btnY, 100, BTN_H)
                .build());
    }

    /** 请求并打开「次元蓝图材料空间」；OP 查看他人时跟随同一个目标玩家。 */
    private void openVoidBagSpace() {
        com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_RequestVoidBagsMessage(
                        targetUuid, targetName));
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);

        // 标题：查看他人时显示玩家名
        Component titleComp = targetName.isEmpty()
                ? Component.translatable("spatial_blueprint.gui.material_space.title")
                : Component.translatable("spatial_blueprint.gui.material_space.title_other", targetName);
        g.drawString(font, titleComp,
                gl + W / 2 - font.width(titleComp) / 2, gt + 5, 0xFFFFFF, false);

        // 占用情况
        Component usage = Component.translatable("spatial_blueprint.gui.material_space.usage",
                keys.size(), total, capacity);
        g.drawString(font, usage,
                gl + W / 2 - font.width(usage) / 2, gt + 17, 0xAAAAAA, false);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);

        if (keys.isEmpty()) {
            Component empty = Component.translatable("spatial_blueprint.gui.material_space.empty");
            g.drawString(font, empty,
                    gl + W / 2 - font.width(empty) / 2, listY + listH / 2 - 4, 0x777777, false);
            super.render(g, mx, my, pt);
            return;
        }

        int totalRows = (keys.size() + COLS - 1) / COLS;
        int lastRow   = Math.min(scrollRows + effectiveMaxRows, totalRows);
        ItemStack hoverStack = null;

        for (int r = scrollRows; r < lastRow; r++) {
            int ry = listY + (r - scrollRows) * CELL_H;
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                if (idx >= keys.size()) break;
                ItemStack h = renderCell(g, mx, my, keys.get(idx), listX + c * (cellW + GAP), ry);
                if (h != null) hoverStack = h;
            }
            g.fill(listX, ry + CELL_H - 1, listX + listW, ry + CELL_H, 0xFF222222);
        }
        for (int c = 1; c < COLS; c++) {
            int vx = listX + c * (cellW + GAP) - (GAP + 1) / 2;
            g.fill(vx, listY, vx + 1, listY + listH, 0xFF222222);
        }

        super.render(g, mx, my, pt);

        // 完整物品 tooltip 最后渲染，压在按钮之上
        if (hoverStack != null && !hoverStack.isEmpty()) {
            g.renderTooltip(font, getTooltipFromItem(Minecraft.getInstance(), hoverStack),
                    hoverStack.getTooltipImage(), mx, my);
        }
    }

    /** 渲染单格；鼠标悬浮在图标上时返回该 ItemStack，否则返回 {@code null}。 */
    private ItemStack renderCell(GuiGraphics g, int mx, int my, String key, int x, int y) {
        Integer count = counts.get(key);
        if (count == null) return null;

        ItemStack stack = MaterialList.buildDisplayStack(key, tags.get(key));
        int top = y + (CELL_H - ICON) / 2;

        SafeItemRenderer.render(g, stack, x + 1, top);
        g.drawString(font, String.valueOf(count),
                x + 1 + ICON + 3, top + (ICON - font.lineHeight) / 2 + 1, 0xE0E0E0, false);

        boolean hovered = mx >= x + 1 && mx < x + 1 + ICON && my >= top && my < top + ICON;
        return hovered && !stack.isEmpty() ? stack : null;
    }

    // ── 事件 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < listX || mx >= listX + listW || my < listY || my >= listY + listH)
            return super.mouseScrolled(mx, my, delta);
        int totalRows = (keys.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - effectiveMaxRows);
        scrollRows = Math.max(0, Math.min(maxScroll, scrollRows - (int) Math.signum(delta)));
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
