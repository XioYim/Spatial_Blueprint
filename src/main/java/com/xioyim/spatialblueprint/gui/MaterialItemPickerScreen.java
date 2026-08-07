package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_EditMaterialMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * 从玩家背包中选取物品、新增条目到材料清单的辅助界面。
 *
 * <p>以 9×4 格子展示玩家背包（槽位 9-35 占前三行，槽位 0-8 热键栏占第四行），
 * 仅显示物品图标，不显示数量。点击任意非空物品后弹出新增对话框。</p>
 */
@OnlyIn(Dist.CLIENT)
public class MaterialItemPickerScreen extends Screen {

    // ── 上下文 ────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final ItemStack blueprintStack;
    @Nullable
    private final BlueprintData blueprintData;

    // ── 布局常量 ──────────────────────────────────────────────────────────────
    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 4;
    private static final int PANEL_PAD = 10;

    // ── 新增对话框状态 ────────────────────────────────────────────────────────
    /** 当前待添加的物品注册名（null = 对话框关闭）*/
    @Nullable private String addingItemId   = null;
    @Nullable private String addingItemName = null;
    private final StringBuilder addInput = new StringBuilder();

    // ── 对话框尺寸（供 render 和 mouseClicked 共享）───────────────────────────
    private static final int DW = 240, DH = 100, BW = 64, BH = 16, BP = 8, PAD = 8;

    // ─────────────────────────────────────────────────────────────────────────

    public MaterialItemPickerScreen(Screen parent, ItemStack blueprintStack,
                                    @Nullable BlueprintData blueprintData) {
        super(Component.translatable("spatial_blueprint.gui.material_picker.title"));
        this.parent         = parent;
        this.blueprintStack = blueprintStack;
        this.blueprintData  = blueprintData;
    }

    // ── 格子坐标辅助 ──────────────────────────────────────────────────────────

    private int gridX() { return (this.width  - GRID_COLS * SLOT_SIZE) / 2; }
    private int gridY() { return (this.height - GRID_ROWS * SLOT_SIZE) / 2 - 8; }

    // ── 渲染 ──────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        Minecraft mc  = Minecraft.getInstance();
        Inventory inv = (mc.player != null) ? mc.player.getInventory() : null;

        int gx = gridX(), gy = gridY();
        int panelW = GRID_COLS * SLOT_SIZE + PANEL_PAD * 2;
        int titleH = font.lineHeight + 6;
        int panelH = titleH + GRID_ROWS * SLOT_SIZE + PANEL_PAD;
        int px = gx - PANEL_PAD;
        int py = gy - titleH - PANEL_PAD / 2;

        // 背景 + 边框
        graphics.fill(px, py, px + panelW, py + panelH, 0xD0101010);
        drawBorder(graphics, px, py, panelW, panelH);

        // 标题
        String titleStr = Component.translatable("spatial_blueprint.gui.material_picker.panel_title").getString();
        graphics.drawString(font, titleStr,
                px + (panelW - font.width(titleStr)) / 2,
                py + PANEL_PAD / 2, 0xFFFFAA, false);

        String hoveredName = null;

        if (inv != null) {
            for (int row = 0; row < GRID_ROWS; row++) {
                for (int col = 0; col < GRID_COLS; col++) {
                    // 槽位映射：前三行 = 主背包槽 9-35；第四行 = 热键栏槽 0-8
                    int slotIndex = (row < 3) ? (9 + row * 9 + col) : col;
                    ItemStack item = inv.getItem(slotIndex);

                    int sx = gx + col * SLOT_SIZE;
                    int sy = gy + row * SLOT_SIZE;

                    // 格子底色
                    graphics.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1,
                            item.isEmpty() ? 0xFF1A1A1A : 0xFF2A2A2A);

                    if (!item.isEmpty()) {
                        // 悬浮高亮（仅对话框未打开时）
                        boolean hovered = addingItemId == null
                                && mouseX >= sx && mouseX < sx + SLOT_SIZE
                                && mouseY >= sy && mouseY < sy + SLOT_SIZE;
                        if (hovered) {
                            graphics.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x60FFFFFF);
                            hoveredName = item.getHoverName().getString();
                        }

                        // 图标：对话框打开时跳过 renderItem()，否则 z=200 穿透 overlay
                        if (addingItemId == null) {
                            graphics.renderItem(item, sx + 1, sy + 1);
                        }
                    }
                }
            }
        }

        // 热键栏与主背包之间的分割线
        int sepY = gy + 3 * SLOT_SIZE;
        graphics.fill(gx, sepY - 1, gx + GRID_COLS * SLOT_SIZE, sepY, 0xFF555555);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip（仅对话框关闭时）
        if (hoveredName != null) {
            graphics.renderTooltip(font, Component.literal(hoveredName), mouseX, mouseY);
        }

        // 新增对话框 overlay（置于最顶层，在所有 renderItem 之后）
        if (addingItemId != null) {
            renderAddDialog(graphics, mouseX, mouseY);
        }
    }

    /** 渲染新增材料数量的输入对话框。 */
    private void renderAddDialog(GuiGraphics g, int mouseX, int mouseY) {
        int dx = (this.width  - DW) / 2;
        int dy = (this.height - DH) / 2;

        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);

        // 标题："新增材料 [名称] 到清单中"
        String itemName = (addingItemName != null) ? addingItemName : "";
        String line1 = String.format(
                Component.translatable("spatial_blueprint.gui.material_picker.dialog.title").getString(), itemName);
        g.drawString(font, line1, dx + PAD, dy + PAD, 0xFFFFFF, false);

        // 数量标签 + 输入框
        String amountLabel = Component.translatable("spatial_blueprint.gui.material_picker.dialog.amount").getString();
        int labelY = dy + PAD + font.lineHeight + 8;
        g.drawString(font, amountLabel, dx + PAD, labelY + (16 - font.lineHeight) / 2, 0xAAAAAA, false);

        int fx = dx + PAD + font.width(amountLabel) + 4;
        int fy = labelY;
        int fw = DW - PAD - (fx - dx);
        int fh = 16;
        g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, 0xFF444444);
        g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A1A1A);
        String cursor = (System.currentTimeMillis() % 1000 < 500) ? "│" : "";
        g.drawString(font, addInput.toString() + cursor,
                fx + 4, fy + (fh - font.lineHeight) / 2, 0xFFFFFF, false);

        // 按钮
        int okX     = dx + DW / 2 - BW - 4;
        int cancelX = dx + DW / 2 + 4;
        int by      = dy + DH - BH - BP;
        boolean okH = mouseX >= okX     && mouseX < okX     + BW && mouseY >= by && mouseY < by + BH;
        boolean cnH = mouseX >= cancelX && mouseX < cancelX + BW && mouseY >= by && mouseY < by + BH;

        String mp_add = Component.translatable("spatial_blueprint.gui.material_picker.dialog.add").getString();
        String mp_cnl = Component.translatable("spatial_blueprint.gui.common.cancel").getString();
        g.fill(okX, by, okX + BW, by + BH, okH ? 0xFF2A5530 : 0xFF1A351F);
        drawBorder(g, okX, by, BW, BH);
        g.drawString(font, mp_add,
                okX + (BW - font.width(mp_add)) / 2, by + (BH - font.lineHeight) / 2, 0x55FF55, false);

        g.fill(cancelX, by, cancelX + BW, by + BH, cnH ? 0xFF553030 : 0xFF351A1A);
        drawBorder(g, cancelX, by, BW, BH);
        g.drawString(font, mp_cnl,
                cancelX + (BW - font.width(mp_cnl)) / 2, by + (BH - font.lineHeight) / 2, 0xFF5555, false);
    }

    // ── 输入处理 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;

        // ── 新增对话框交互 ────────────────────────────────────────────────────
        if (addingItemId != null) {
            int dx = (this.width  - DW) / 2;
            int dy = (this.height - DH) / 2;
            int okX     = dx + DW / 2 - BW - 4;
            int cancelX = dx + DW / 2 + 4;
            int by      = dy + DH - BH - BP;

            if (my >= by && my < by + BH) {
                if (mx >= okX && mx < okX + BW)         { confirmAdd(); return true; }
                if (mx >= cancelX && mx < cancelX + BW) { cancelAdd(); return true; }
            }
            // 点击面板外：关闭对话框
            if (mx < dx || mx >= dx + DW || my < dy || my >= dy + DH) cancelAdd();
            return true;
        }

        // ── 点击背包格子 ─────────────────────────────────────────────────────
        if (button == 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return super.mouseClicked(mouseX, mouseY, button);

            Inventory inv = mc.player.getInventory();
            int gx = gridX(), gy = gridY();

            for (int row = 0; row < GRID_ROWS; row++) {
                for (int col = 0; col < GRID_COLS; col++) {
                    int sx = gx + col * SLOT_SIZE;
                    int sy = gy + row * SLOT_SIZE;
                    if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE) {
                        int slotIndex = (row < 3) ? (9 + row * 9 + col) : col;
                        ItemStack item = inv.getItem(slotIndex);
                        if (!item.isEmpty()) {
                            // 使用物品的注册名作为清单 key（不限制是否为方块）
                            addingItemId   = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                            addingItemName = item.getHoverName().getString();
                            addInput.setLength(0);
                            addInput.append("1");
                        }
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (addingItemId != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmAdd(); return true;
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelAdd(); return true;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE && addInput.length() > 0) {
                addInput.deleteCharAt(addInput.length() - 1); return true;
            }
            return true; // 对话框期间吞掉所有按键
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose(); return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (addingItemId != null) {
            if (Character.isDigit(c) && addInput.length() < 9) addInput.append(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    // ── 业务逻辑 ──────────────────────────────────────────────────────────────

    /** 确认新增：合并计数到本地清单，发包同步服务端，关闭 picker 返回父界面。 */
    private void confirmAdd() {
        if (addingItemId == null) return;
        try {
            int count = Integer.parseInt(addInput.toString());
            if (count > 0 && blueprintData != null) {
                // 确保 materialList 存在
                if (blueprintData.materialList == null) {
                    blueprintData.materialList = new MaterialList();
                    blueprintData.materialList.blueprintName =
                            blueprintData.name != null ? blueprintData.name : "";
                }
                // 与已有条目合并（+count）
                int existing = blueprintData.materialList.blockCounts.getOrDefault(addingItemId, 0);
                int newTotal = existing + count;
                blueprintData.materialList.blockCounts.put(addingItemId, newTotal);

                // 发包同步服务端 dat 文件
                String bpId = blueprintStack.hasTag()
                        ? blueprintStack.getTag().getString("blueprintId") : "";
                if (!bpId.isEmpty()) {
                    BlueprintNetwork.CHANNEL.sendToServer(
                            new C2S_EditMaterialMessage(bpId, addingItemId, newTotal));
                }
            }
        } catch (NumberFormatException ignored) {}
        clearAdd();
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancelAdd() { clearAdd(); }

    private void clearAdd() {
        addingItemId   = null;
        addingItemName = null;
        addInput.setLength(0);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }
}
