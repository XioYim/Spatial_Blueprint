package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置 · 批量材料转换（bulkMaterialConversions）：与"方块替换表"同款选择器，但每侧带数量。
 *
 * <p>行：{@code [源图标 N] 转化为 [目标图标 M & ...] [新增+]}<br>
 * 底部"新增项目"弹框：方块 ID + 源数量（阈值）；行内 [+] 选物品后弹数量框。<br>
 * 目标最多 {@link #MAX_TARGETS} 个；数量强制 ≥1。<br>
 * 保存格式：{@code source=threshold|item1*count1+item2*count2}，写入
 * {@code block_substitutions.toml} 的 {@code bulkMaterialConversions}。</p>
 */
@OnlyIn(Dist.CLIENT)
public class BulkConversionScreen extends Screen implements ErrorToastSupport {

    /** 当前显示的错误横幅；null = 无。 */
    @javax.annotation.Nullable
    private net.minecraft.network.chat.Component errorToast;

    @Override
    public void setErrorToast(net.minecraft.network.chat.Component message) {
        this.errorToast = message;
    }

    private static final int W        = 360;
    private static final int HEADER_H = 24;
    private static final int ITEM_H   = 22;
    private static final int MAX_VIS  = 12;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    private static final int MAX_TARGETS = 5;
    private static final int ICON = 16;

    private record BulkEntry(int threshold, LinkedHashMap<String, Integer> targets) {}

    private final Screen parent;
    private int lastSnapshotVersion = -1;
    private final Map<String, BulkEntry> map = new LinkedHashMap<>();
    private int scrollOffset;
    private int selectedSource = -1;
    private int selectedTarget = -1;
    private int contentRight;
    private boolean scrollDragging = false;
    private Button deleteButton;

    private int gl, gt, panelH, listH, listX, listY, listW;

    // ── 对话框状态 ────────────────────────────────────────────────────────────
    private boolean addDialogOpen = false;
    private EditBox addIdInput, addCountInput;
    private boolean targetDialogOpen = false;
    private EditBox targetCountInput;
    private String pendingSource = null;
    private String pendingTarget = null;

    private static final int DW = 260, DH = 116, BW = 60, BH = 18, BP = 8;

    public BulkConversionScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.bulk.title"));
        this.parent = parent;
        com.xioyim.spatialblueprint.client.ConfigAccess.request();
        loadMap();
    }

    private void loadMap() {
        map.clear();
        for (String entry : com.xioyim.spatialblueprint.client.ConfigAccess.getList(
                GeneratedSubstitutionsConfig.BULK_CONVERSIONS)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String source = entry.substring(0, eq).trim();
            String rest = entry.substring(eq + 1).trim();
            int pipe = rest.indexOf('|');
            if (pipe <= 0) continue;
            int threshold;
            try { threshold = Integer.parseInt(rest.substring(0, pipe).trim()); }
            catch (NumberFormatException ignored) { continue; }
            if (threshold < 0) continue; // 0 = 免费（转化为空气）；负数非法
            LinkedHashMap<String, Integer> targets = new LinkedHashMap<>();
            for (String part : rest.substring(pipe + 1).split("\\+")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int star = part.indexOf('*');
                String item;
                int count = 1;
                if (star > 0) {
                    item = part.substring(0, star).trim();
                    try { count = Integer.parseInt(part.substring(star + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                } else item = part;
                if (!item.isEmpty()) targets.put(item, Math.max(1, count));
            }
            map.put(source, new BulkEntry(threshold, targets));
        }
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        selectedSource = -1;
        selectedTarget = -1;
        if (addDialogOpen) setupAddDialog();
        else if (targetDialogOpen) setupTargetDialog();
        else setupMainUi();
    }

    private void setupMainUi() {
        listH  = Math.max(ITEM_H, Math.min(MAX_VIS, map.size()) * ITEM_H);
        panelH = HEADER_H + PAD + listH + PAD + FOOTER_H;
        gl = (width  - W) / 2;
        gt = (height - panelH) / 2;
        listX = gl + PAD;
        listY = gt + HEADER_H + PAD;
        listW = W - PAD * 2;
        contentRight = listX + listW - 8;

        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 12) / 4;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.bulk.add_entry"),
                        b -> { addDialogOpen = true; init(); })
                .bounds(startX, buttonY, bW, BTN_H).build());
        deleteButton = addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.delete"),
                        b -> deleteSelection())
                .bounds(startX + bW + 4, buttonY, bW, BTN_H).build());
        deleteButton.active = selectedSource >= 0;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.save_return"),
                        b -> saveAndClose())
                .bounds(startX + (bW + 4) * 2, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.excluded.cancel"),
                        b -> onClose())
                .bounds(startX + (bW + 4) * 3, buttonY, bW, BTN_H).build());
    }

    private void setupAddDialog() {
        int dx = (width - DW) / 2, dy = (height - DH) / 2;
        addIdInput = new EditBox(font, dx + 8, dy + 30, DW - 16, 18, Component.empty());
        addIdInput.setMaxLength(128);
        addIdInput.setValue("");
        addCountInput = new EditBox(font, dx + 8, dy + 58, DW - 16, 18, Component.empty());
        addCountInput.setMaxLength(9);
        addCountInput.setFilter(s -> s.isEmpty() || s.matches("[1-9]\\d*"));
        addCountInput.setValue("1");
        addRenderableWidget(addIdInput);
        addRenderableWidget(addCountInput);
        setFocused(addIdInput);
        int okX = dx + DW / 2 - BW - 4;
        int cancelX = dx + DW / 2 + 4;
        int by = dy + DH - BH - BP;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.confirm"),
                        b -> confirmAddEntry())
                .bounds(okX, by, BW, BH).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.cancel"),
                        b -> { addDialogOpen = false; init(); })
                .bounds(cancelX, by, BW, BH).build());
    }

    private void setupTargetDialog() {
        int dx = (width - DW) / 2, dy = (height - DH) / 2;
        targetCountInput = new EditBox(font, dx + 8, dy + 30, DW - 16, 18, Component.empty());
        targetCountInput.setMaxLength(9);
        targetCountInput.setFilter(s -> s.isEmpty() || s.matches("[1-9]\\d*"));
        targetCountInput.setValue("1");
        addRenderableWidget(targetCountInput);
        setFocused(targetCountInput);
        int okX = dx + DW / 2 - BW - 4;
        int cancelX = dx + DW / 2 + 4;
        int by = dy + DH - BH - BP;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.confirm"),
                        b -> confirmTarget())
                .bounds(okX, by, BW, BH).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.substitutions.dialog.cancel"),
                        b -> { targetDialogOpen = false; pendingTarget = null; init(); })
                .bounds(cancelX, by, BW, BH).build());
    }

    private void confirmAddEntry() {
        String id = addIdInput.getValue().trim();
        int threshold = parseCount(addCountInput);
        if (threshold < 1) { showInvalid(); return; }
        if (id.isEmpty()) return;
        if (map.containsKey(id)) {
            showExists();
            return;
        }
        putFirst(id, new BulkEntry(threshold, new LinkedHashMap<>()));
        scrollOffset = 0;
        addDialogOpen = false;
        init();
    }

    private void confirmTarget() {
        int count = parseCount(targetCountInput);
        if (count < 1) { showInvalid(); return; }
        if (pendingSource == null || pendingTarget == null) { targetDialogOpen = false; pendingTarget = null; init(); return; }
        BulkEntry be = map.get(pendingSource);
        if (be != null) {
            if (be.targets().size() < MAX_TARGETS && !be.targets().containsKey(pendingTarget)) {
                be.targets().put(pendingTarget, count);
            }
        }
        targetDialogOpen = false;
        pendingTarget = null;
        init();
    }

    private int parseCount(EditBox box) {
        String s = box.getValue().trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return 0; }
    }

    // 原先用 displayClientMessage(..., true) 画在物品栏上方的 actionbar 上，
    // 但本界面是全屏的，actionbar 被完全挡住 —— 玩家看到的是「点了确认没反应」。
    private void showInvalid() {
        com.xioyim.spatialblueprint.util.NotifySound.clientError();
        setErrorToast(Component.translatable("spatial_blueprint.config.bulk.count_invalid"));
    }

    private void showExists() {
        com.xioyim.spatialblueprint.util.NotifySound.clientError();
        setErrorToast(Component.translatable("spatial_blueprint.config.substitutions.exists"));
    }

    private void putFirst(String source, BulkEntry entry) {
        LinkedHashMap<String, BulkEntry> rebuilt = new LinkedHashMap<>();
        rebuilt.put(source, entry);
        rebuilt.putAll(map);
        map.clear();
        map.putAll(rebuilt);
    }

    private void deleteSelection() {
        if (selectedSource < 0 || selectedSource >= map.size()) return;
        String source = sourceKeys().get(selectedSource);
        if (selectedTarget >= 0) {
            BulkEntry be = map.get(source);
            if (be != null && selectedTarget < be.targets().size()) {
                String key = new ArrayList<>(be.targets().keySet()).get(selectedTarget);
                be.targets().remove(key);
            }
        } else {
            map.remove(source);
        }
        selectedSource = -1;
        selectedTarget = -1;
        init();
    }

    private void saveAndClose() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, BulkEntry> e : map.entrySet()) {
            BulkEntry be = e.getValue();
            if (be.threshold() == 0) { out.add(e.getKey() + "=0|"); continue; } // 免费（转化为空气）
            if (be.targets().isEmpty()) continue; // 空目标跳过不写配置
            StringBuilder sb = new StringBuilder();
            sb.append(e.getKey()).append('=').append(be.threshold()).append('|');
            boolean first = true;
            for (Map.Entry<String, Integer> t : be.targets().entrySet()) {
                if (!first) sb.append('+');
                sb.append(t.getKey()).append('*').append(t.getValue());
                first = false;
            }
            out.add(sb.toString());
        }
        com.xioyim.spatialblueprint.client.ConfigAccess.setList(
                GeneratedSubstitutionsConfig.BULK_CONVERSIONS, out);
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
        onClose();
    }

    private List<String> sourceKeys() { return new ArrayList<>(map.keySet()); }

    private void openPicker(int rowIndex) {
        pendingSource = sourceKeys().get(rowIndex);
        Minecraft.getInstance().setScreen(new ExcludedBlocksScreen.ItemQuickPickerScreen(this, this::onPickNewTarget, true));
    }

    private void onPickNewTarget(String id) {
        if (id == null || id.isBlank()) {
            Minecraft.getInstance().setScreen(this);
            init();
            return;
        }
        if (ExcludedBlocksScreen.ItemQuickPickerScreen.FREE.equals(id)) {
            if (pendingSource != null)
                map.put(pendingSource, new BulkEntry(0, new LinkedHashMap<>())); // 免费（转化为空气）
            Minecraft.getInstance().setScreen(this);
            init();
            return;
        }
        pendingTarget = id;
        targetDialogOpen = true;
        Minecraft.getInstance().setScreen(this);
        init();
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        // 早返回模式：横幅出现时完全隐藏下层 UI（见「报错提示UI接入方法.md」）
        if (errorToast != null) {
            ErrorToast.render(g, this.font, this.width, this.height, errorToast);
            return;
        }
        if (addDialogOpen) { renderDialog(g, mx, my, pt); return; }
        if (targetDialogOpen) { renderTargetDialog(g, mx, my, pt); return; }

        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.SUBS_CONFIG_PATH,
                "spatial_blueprint.config.bulk.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);
        String hoverName = null;
        int end = Math.min(map.size(), scrollOffset + MAX_VIS);
        for (int i = scrollOffset; i < end; i++) {
            int y = listY + (i - scrollOffset) * ITEM_H;
            String source = sourceKeys().get(i);
            BulkEntry be = map.get(source);
            boolean free = be.threshold() == 0;
            int rowTop = y + (ITEM_H - ICON) / 2;

            // 源图标
            int sx = listX + 3;
            if (i == selectedSource && selectedTarget < 0) drawSelectBox(g, sx - 1, rowTop - 1);
            ItemStack srcStack = blockIcon(source);
            g.renderItem(srcStack, sx, rowTop);
            if (hoverInside(mx, my, sx, rowTop)) hoverName = displayName(source, false);

            // 转化为（免费行显示"转化为 空气！"）
            int toX;
            String toText;
            if (free) {
                toX = sx + ICON + 4;
                toText = Component.translatable("spatial_blueprint.config.substitutions.to_air").getString();
            } else {
                g.drawString(font, ">" + be.threshold(), sx + ICON + 2, rowTop + 4, 0xFFFFAA, false);
                toX = sx + ICON + 2 + font.width(">" + be.threshold()) + 4;
                toText = Component.translatable("spatial_blueprint.config.substitutions.to").getString();
            }
            g.drawString(font, toText, toX, rowTop + (ICON - font.lineHeight) / 2, 0xAAAAAA, false);

            // 目标图标（免费行无目标）
            if (!free) {
                int tX = toX + font.width(toText) + 4;
                List<String> tKeys = new ArrayList<>(be.targets().keySet());
                for (int j = 0; j < tKeys.size(); j++) {
                    if (i == selectedSource && j == selectedTarget) drawSelectBox(g, tX - 1, rowTop - 1);
                    ItemStack tStack = itemIcon(tKeys.get(j));
                    g.renderItem(tStack, tX, rowTop);
                    if (hoverInside(mx, my, tX, rowTop)) hoverName = displayName(tKeys.get(j), true);
                    g.drawString(font, String.valueOf(be.targets().get(tKeys.get(j))), tX + ICON + 2, rowTop + 4, 0xFFAAFF, false);
                    tX += ICON + 2 + font.width(String.valueOf(be.targets().get(tKeys.get(j)))) + 4;
                }
            }

            // 行内 [+]（免费行隐藏）
            if (!free && be.targets().size() < MAX_TARGETS) {
                int px = contentRight - ICON;
                g.fill(px, rowTop, px + ICON, rowTop + ICON, 0xFF2A2A2A);
                drawBorder(g, px, rowTop, ICON, ICON);
                g.drawCenteredString(font, "+", px + ICON / 2, rowTop + (ICON - font.lineHeight) / 2, 0xFFFFFF);
            }
        }
        drawScrollbar(g);
        super.render(g, mx, my, pt);
        if (hoverName != null) g.renderTooltip(font, Component.literal(hoverName), mx, my);
    }

    private void renderDialog(GuiGraphics g, int mx, int my, float pt) {
        int dx = (width - DW) / 2, dy = (height - DH) / 2;
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);
        g.drawString(font, Component.translatable("spatial_blueprint.config.bulk.dialog.title"),
                dx + 8, dy + 10, 0xFFFFFF, false);
        g.drawString(font, Component.translatable("spatial_blueprint.config.bulk.dialog.id"),
                dx + 8, dy + 33, 0xAAAAAA, false);
        g.drawString(font, Component.translatable("spatial_blueprint.config.bulk.dialog.threshold"),
                dx + 8, dy + 61, 0xAAAAAA, false);
        super.render(g, mx, my, pt);
    }

    private void renderTargetDialog(GuiGraphics g, int mx, int my, float pt) {
        int dx = (width - DW) / 2, dy = (height - DH) / 2;
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(dx, dy, dx + DW, dy + DH, 0xF0101010);
        drawBorder(g, dx, dy, DW, DH);
        g.drawString(font, Component.translatable("spatial_blueprint.config.bulk.target.dialog.title",
                        displayName(pendingTarget != null ? pendingTarget : "", true)),
                dx + 8, dy + 10, 0xFFFFFF, false);
        g.drawString(font, Component.translatable("spatial_blueprint.config.bulk.dialog.threshold"),
                dx + 8, dy + 33, 0xAAAAAA, false);
        super.render(g, mx, my, pt);
    }

    private boolean hoverInside(int mx, int my, int x, int y) {
        return mx >= x && mx < x + ICON && my >= y && my < y + ICON;
    }

    private void drawSelectBox(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + ICON + 2, y + ICON + 2, 0xFFFFFFFF);
    }

    private void drawScrollbar(GuiGraphics g) {
        if (map.size() <= MAX_VIS) return;
        int sbX = listX + listW - 4;
        g.fill(sbX, listY, sbX + 4, listY + listH, 0xFF333333);
        int contentH = map.size() * ITEM_H;
        int thumbH = Math.max(20, (int) (listH * (double) listH / contentH));
        int maxScroll = Math.max(0, map.size() - MAX_VIS);
        double ratio = maxScroll > 0 ? (double) scrollOffset / maxScroll : 0;
        int thumbY = listY + (int) (ratio * (listH - thumbH));
        g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFFAAAAAA);
    }

    private boolean inScrollbar(int mx, int my) {
        if (map.size() <= MAX_VIS) return false;
        return mx >= listX + listW - 5 && mx < listX + listW + 1
                && my >= listY && my < listY + listH;
    }

    private void updateScrollFromY(double my) {
        int maxScroll = Math.max(0, map.size() - MAX_VIS);
        if (maxScroll <= 0) { scrollOffset = 0; return; }
        int thumbH = Math.max(20, (int) (listH * (double) listH / (map.size() * ITEM_H)));
        double ratio = (my - listY - thumbH / 2.0) / (listH - thumbH);
        scrollOffset = (int) Math.round(net.minecraft.util.Mth.clamp(ratio * maxScroll, 0, maxScroll));
    }

    // ── 交互 ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        if (addDialogOpen || targetDialogOpen) return super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int mx = (int) mouseX, my = (int) mouseY;
        if (ConfigTitles.click(mx, my)) return true;
        if (inScrollbar(mx, my)) { scrollDragging = true; updateScrollFromY(mouseY); return true; }

        if (mx >= listX && mx < contentRight && my >= listY && my < listY + Math.min(MAX_VIS, map.size()) * ITEM_H) {
            int i = (int) (my - listY) / ITEM_H + scrollOffset;
            if (i >= 0 && i < map.size()) {
                int rowTop = listY + (i - scrollOffset) * ITEM_H + (ITEM_H - ICON) / 2;
                String source = sourceKeys().get(i);
                BulkEntry be = map.get(source);
                boolean free = be.threshold() == 0;
                List<String> tKeys = new ArrayList<>(be.targets().keySet());

                // 行内 [+]（仅非免费且未满）
                if (!free && be.targets().size() < MAX_TARGETS) {
                    int px = contentRight - ICON;
                    if (mx >= px && mx < px + ICON && my >= rowTop && my < rowTop + ICON) { openPicker(i); return true; }
                }
                int sx = listX + 3;
                if (mx >= sx && mx < sx + ICON && my >= rowTop && my < rowTop + ICON) {
                    selectedSource = i; selectedTarget = -1; deleteButton.active = true; return true;
                }
                // 目标图标（免费行无目标）
                if (!free) {
                    int toX = sx + ICON + 2 + font.width(">" + be.threshold()) + 4;
                    String toText = Component.translatable("spatial_blueprint.config.substitutions.to").getString();
                    int tX = toX + font.width(toText) + 4;
                    for (int j = 0; j < tKeys.size(); j++) {
                        if (mx >= tX && mx < tX + ICON && my >= rowTop && my < rowTop + ICON) {
                            selectedSource = i; selectedTarget = j; deleteButton.active = true; return true;
                        }
                        tX += ICON + 2 + font.width(String.valueOf(be.targets().get(tKeys.get(j)))) + 4;
                    }
                }
                selectedSource = -1; selectedTarget = -1; deleteButton.active = false; return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollDragging && button == 0) { updateScrollFromY(mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) scrollDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (addDialogOpen || targetDialogOpen) return super.mouseScrolled(mx, my, delta);
        int maxScroll = Math.max(0, map.size() - MAX_VIS);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;   // ESC
            return true;                              // 吃掉所有按键
        }
        if (addDialogOpen) {
            if (keyCode == 257 || keyCode == 335) { confirmAddEntry(); return true; }
            if (keyCode == 256) { addDialogOpen = false; init(); return true; }
        }
        if (targetDialogOpen) {
            if (keyCode == 257 || keyCode == 335) { confirmTarget(); return true; }
            if (keyCode == 256) { targetDialogOpen = false; pendingTarget = null; init(); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 图标辅助 ─────────────────────────────────────────────────────────────

    private static ItemStack blockIcon(String blockId) {
        try {
            net.minecraft.world.level.block.Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
            if (b != null) {
                if (!b.defaultBlockState().getFluidState().isEmpty()) {
                    if (b.defaultBlockState().getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                        return new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET);
                    if (b.defaultBlockState().getFluidState().is(net.minecraft.tags.FluidTags.LAVA))
                        return new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET);
                }
                if (b instanceof net.minecraft.world.level.block.FireBlock)
                    return new ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL);
                if (b != Blocks.AIR && b.asItem() != net.minecraft.world.item.Items.AIR)
                    return new ItemStack(b.asItem());
            }
        } catch (Exception ignored) {}
        return new ItemStack(Blocks.BARRIER);
    }

    private static ItemStack itemIcon(String itemId) {
        try {
            net.minecraft.world.item.Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
            if (it != null && it != net.minecraft.world.item.Items.AIR) return new ItemStack(it);
        } catch (Exception ignored) {}
        return new ItemStack(net.minecraft.world.item.Items.BARRIER);
    }

    private static String displayName(String id, boolean isItem) {
        String key = (isItem ? "item." : "block.") + id.replace(':', '.');
        String s = Component.translatable(key).getString();
        return s.equals(key) ? id : s;
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void tick() {
        super.tick();
        int v = com.xioyim.spatialblueprint.client.ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            lastSnapshotVersion = v;
            loadMap();
            init();
        }
    }
}
