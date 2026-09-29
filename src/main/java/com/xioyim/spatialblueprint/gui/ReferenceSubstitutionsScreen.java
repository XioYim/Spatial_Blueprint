package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.*;

@OnlyIn(Dist.CLIENT)
public class ReferenceSubstitutionsScreen extends Screen {

    private static final int W        = 480;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 52;   // 2 排按钮：18 + 4 + 18 + 上下 pad
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    private static final int COLS     = 6;
    private static final int MAX_ROWS = 12;
    private static final int CELL_H   = 22;
    private static final int GAP      = 3;
    private static final int ICON     = 16;
    private static final int TAB_H    = 14;   // 标签页栏高度

    private final Screen      parent;
    private final Map<String, String> map         = new LinkedHashMap<>();
    private final Set<String>         conflictKeys = new HashSet<>();
    private final Set<String>         selectedKeys = new LinkedHashSet<>();
    private boolean hideConflicts = false;
    private int     scrollRows    = 0;
    private int     lastSnapshotVersion = -1;
    private int     selectedTab   = 0;         // 0 = Minecraft, 1 = MOD

    private int gl, gt, panelH, listH, listX, listY, listW, cellW;
    private int effectiveMaxRows = MAX_ROWS;

    private boolean          scanStarted = false;
    private volatile boolean scanDone    = false;

    // ── 诊断计数（静态，renderIcon 是 static 方法）─────────────────────────
    private static int dbgCalls, dbgThrows, dbgDepthChgs, dbgBewlr;
    private int dbgFrames = 3;  // tab 切换后记录前 N 帧

    // 已知崩溃物品集合：首次抛异常后加入，后续帧直接显示 BARRIER 跳过重渲染
    private static final java.util.Set<String> KNOWN_CRASH =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public ReferenceSubstitutionsScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.reference.title"));
        this.parent = parent;
        com.xioyim.spatialblueprint.client.ConfigAccess.request();
    }

    // ── init ──────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        gl = (width - W) / 2;
        if (scanDone) {
            buildLayout();
        } else {
            panelH = HEADER_H + PAD + 32 + PAD;
            gt     = Math.max(8, (height - panelH) / 2);
            listH  = 32;
            listX  = gl + PAD;
            listY  = gt + HEADER_H + PAD;
            listW  = W - PAD * 2;
            cellW  = (listW - GAP * (COLS - 1)) / COLS;
            if (!scanStarted) {
                scanStarted = true;
                startAsyncLoad();
            }
        }
    }

    private void buildLayout() {
        gl = (width - W) / 2;
        int displaySize  = displayKeys().size();
        int available    = height - 16 - HEADER_H - TAB_H - 2 * PAD - FOOTER_H;
        effectiveMaxRows = Math.max(3, Math.min(MAX_ROWS, available / CELL_H));
        int totalRows    = Math.max(1, (displaySize + COLS - 1) / COLS);
        listH  = Math.min(effectiveMaxRows, totalRows) * CELL_H;
        panelH = HEADER_H + TAB_H + PAD + listH + PAD + FOOTER_H;
        gt     = Math.max(8, (height - panelH) / 2);
        listX  = gl + PAD;
        listY  = gt + HEADER_H + TAB_H + PAD;
        listW  = W - PAD * 2;
        cellW  = (listW - GAP * (COLS - 1)) / COLS;

        int btnY1 = gt + panelH - FOOTER_H + 5;
        int btnY2 = btnY1 + BTN_H + 4;
        int bW    = (W - 2 * PAD - 2 * 4) / 3;
        int sx    = gl + PAD;

        // ── 上排 ──────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.reference.btn.delete"),
                        b -> deleteSelected())
                .bounds(sx, btnY1, bW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.config.reference.btn.delete.tooltip")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable(hideConflicts
                                ? "spatial_blueprint.config.reference.btn.show_conflicts"
                                : "spatial_blueprint.config.reference.btn.hide_conflicts"),
                        b -> toggleConflictFilter())
                .bounds(sx + bW + 4, btnY1, bW, BTN_H).build());

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.reference.btn.enable_all"),
                        b -> enableAll())
                .bounds(sx + (bW + 4) * 2, btnY1, bW, BTN_H).build());

        // ── 下排 ──────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.reference.btn.reset"),
                        b -> resetGenerated())
                .bounds(sx, btnY2, bW, BTN_H).build());

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.reference.btn.save"),
                        b -> saveGenerated())
                .bounds(sx + bW + 4, btnY2, bW, BTN_H).build());

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.config.cancel"),
                        b -> onClose())
                .bounds(sx + (bW + 4) * 2, btnY2, bW, BTN_H).build());
    }

    // ── 显示列表（hideConflicts 时过滤冲突项） ────────────────────────────

    private List<String> displayKeys() {
        // 先按 tab 过滤命名空间：0=minecraft: 前缀，1=其余(MOD)
        List<String> tabFiltered = new ArrayList<>(map.size());
        for (String k : map.keySet()) {
            boolean isMc = k.startsWith("minecraft:");
            if ((selectedTab == 0) == isMc) tabFiltered.add(k);
        }
        if (!hideConflicts) return tabFiltered;
        List<String> filtered = new ArrayList<>(tabFiltered.size());
        for (String k : tabFiltered) if (!conflictKeys.contains(k)) filtered.add(k);
        return filtered;
    }

    // ── 后台扫描 ──────────────────────────────────────────────────────────

    private void startAsyncLoad() {
        Thread t = new Thread(() -> {
            Map<String, String> newMap = new LinkedHashMap<>();
            for (String entry : com.xioyim.spatialblueprint.util.BlockSubstitutionGenerator.scanCandidates()) {
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String src = entry.substring(0, eq).trim();
                String tgt = entry.substring(eq + 1).trim();
                int plus = tgt.indexOf('+'); if (plus > 0) tgt = tgt.substring(0, plus).trim();
                int star = tgt.indexOf('*'); if (star > 0) tgt = tgt.substring(0, star).trim();
                if (!src.isEmpty() && !tgt.isEmpty()) newMap.put(src, tgt);
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen != this) return;
                map.clear();
                map.putAll(newMap);
                // 标记与 blockSubstitutions 中已有条目冲突的项
                conflictKeys.clear();
                Set<String> configured = new HashSet<>();
                for (String e : com.xioyim.spatialblueprint.client.ConfigAccess.getList(
                        GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS)) {
                    int eq = e.indexOf('=');
                    if (eq > 0) configured.add(e.substring(0, eq).trim());
                }
                for (String k : map.keySet()) if (configured.contains(k)) conflictKeys.add(k);
                scanDone = true;
                this.init(mc, width, height);
            });
        }, "SpatialBlueprint-RefScan");
        t.setDaemon(true);
        t.start();
    }

    // ── 按钮动作 ──────────────────────────────────────────────────────────

    private void deleteSelected() {
        if (selectedKeys.isEmpty()) return;
        for (String k : selectedKeys) { map.remove(k); conflictKeys.remove(k); }
        selectedKeys.clear();
        scrollRows = 0;
        clearWidgets();
        buildLayout();
    }

    private void toggleConflictFilter() {
        hideConflicts = !hideConflicts;
        scrollRows = 0;
        clearWidgets();
        buildLayout();
    }

    private void enableAll() {
        // 只追加，永远不覆盖 blockSubstitutions 中已有的条目
        List<String> existing = new ArrayList<>(com.xioyim.spatialblueprint.client.ConfigAccess.getList(
                GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS));
        Set<String> existingKeys = new HashSet<>();
        for (String e : existing) {
            int eq = e.indexOf('=');
            if (eq > 0) existingKeys.add(e.substring(0, eq).trim());
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!existingKeys.contains(entry.getKey()))
                existing.add(entry.getKey() + "=" + entry.getValue());
        }
        com.xioyim.spatialblueprint.client.ConfigAccess.setList(
                GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS, existing);
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
        onClose();
    }

    private void resetGenerated() {
        com.xioyim.spatialblueprint.client.ConfigAccess.setList(
                GeneratedSubstitutionsConfig.GENERATED, java.util.List.of());
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
        map.clear();
        conflictKeys.clear();
        selectedKeys.clear();
        onClose();
    }

    private void saveGenerated() {
        List<String> list = new ArrayList<>();
        map.forEach((k, v) -> list.add(k + "=" + v));
        com.xioyim.spatialblueprint.client.ConfigAccess.setList(
                GeneratedSubstitutionsConfig.GENERATED, list);
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.SUBS_CONFIG_PATH,
                "spatial_blueprint.config.reference.help", gl + W / 2, gt + 8, mx, my);

        if (!scanDone) {
            String txt = Component.translatable("spatial_blueprint.config.reference.loading").getString();
            g.drawString(font, txt, gl + W / 2 - font.width(txt) / 2, gt + HEADER_H + PAD + 8, 0xAAAAAA, false);
            // 加载中无物品，GL 状态干净，按钮可以直接渲染
            super.render(g, mx, my, pt);
            return;
        }

        // ── Tab 标签栏（仅 fill，无 drawString——文字留到最后批次一起提交）────────
        int tabY = gt + HEADER_H;
        int tabW = listW / 2;
        g.fill(listX, tabY, listX + tabW, tabY + TAB_H,
                selectedTab == 0 ? 0xFF282828 : 0xFF141414);
        if (selectedTab == 0)
            g.fill(listX, tabY + TAB_H - 1, listX + tabW, tabY + TAB_H, 0xFF55AA55);
        g.fill(listX + tabW, tabY, listX + listW, tabY + TAB_H,
                selectedTab == 1 ? 0xFF282828 : 0xFF141414);
        if (selectedTab == 1)
            g.fill(listX + tabW, tabY + TAB_H - 1, listX + listW, tabY + TAB_H, 0xFF5588CC);
        g.fill(listX + tabW, tabY, listX + tabW + 1, tabY + TAB_H, 0xFF333333);

        // ── 列表背景 + 物品循环 ───────────────────────────────────────────────
        g.fill(listX, listY, listX + listW, listY + listH, 0xFF141414);

        boolean isDbgFrame = dbgFrames > 0;
        if (isDbgFrame) {
            com.mojang.blaze3d.vertex.PoseStack _mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[RefSubs][DBG-START] frame={} tab={} scroll={} mvDepth={} poseFieldOk={} depthTest={} scissor={} blend={}",
                4 - dbgFrames, selectedTab, scrollRows, mvDepth(_mv),
                MV_POSE_FIELD != null,
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND));
        }
        dbgCalls = dbgThrows = dbgDepthChgs = dbgBewlr = 0;

        List<String> keys = displayKeys();
        int totalRows     = (keys.size() + COLS - 1) / COLS;
        int lastRow       = Math.min(scrollRows + effectiveMaxRows, totalRows);
        String hoverText  = null;

        for (int r = scrollRows; r < lastRow; r++) {
            int ry = listY + (r - scrollRows) * CELL_H;
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                if (idx >= keys.size()) break;
                String h = renderCell(g, mx, my, keys.get(idx), listX + c * (cellW + GAP), ry);
                if (h != null) hoverText = h;
            }
            g.fill(listX, ry + CELL_H - 1, listX + listW, ry + CELL_H, 0xFF222222);
        }
        for (int c = 1; c < COLS; c++) {
            int vx = listX + c * (cellW + GAP) - (GAP + 1) / 2;
            g.fill(vx, listY, vx + 1, listY + listH, 0xFF222222);
        }

        if (hoverText != null) g.renderTooltip(font, Component.literal(hoverText), mx, my);

        // ── 物品循环结束：诊断摘要 ────────────────────────────────────────────
        if (isDbgFrame || dbgThrows > 0 || dbgDepthChgs > 0) {
            com.mojang.blaze3d.vertex.PoseStack _mv2 = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[RefSubs][DBG-END] calls={} bewlr={} throws={} depthChgs={} mvDepth={} depthTest={} scissor={} blend={}",
                dbgCalls, dbgBewlr, dbgThrows, dbgDepthChgs, mvDepth(_mv2),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST),
                org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND));
            if (isDbgFrame) dbgFrames--;
        }

        // ── 物品循环结束后全面还原 GL 状态 ───────────────────────────────────
        // BEWLR 物品可能污染 depth test / blend func / shader color，必须逐一还原
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // ── Tab 文字在 GL 状态干净后渲染 ──────────────────────────────────────
        String t0 = "Minecraft";
        g.drawString(font, t0,
                listX + (tabW - font.width(t0)) / 2,
                tabY + (TAB_H - font.lineHeight) / 2,
                selectedTab == 0 ? 0xFFFFFF : 0x666666, false);
        String t1 = "MOD";
        g.drawString(font, t1,
                listX + tabW + (tabW - font.width(t1)) / 2,
                tabY + (TAB_H - font.lineHeight) / 2,
                selectedTab == 1 ? 0xFFFFFF : 0x666666, false);

        // ── 按钮最后渲染，GL 状态已完全干净 ──────────────────────────────────
        super.render(g, mx, my, pt);
    }

    private String renderCell(GuiGraphics g, int mx, int my, String source, int x, int y) {
        String target = map.get(source);
        if (target == null) return null;
        int top  = y + (CELL_H - ICON) / 2;
        String hover = null;

        // 选中高亮
        if (selectedKeys.contains(source))
            g.fill(x, y, x + cellW, y + CELL_H, 0x44FF3333);

        // 源图标（左）
        renderIcon(g, blockIcon(source), x + 1, top);
        if (hoverIn(mx, my, x + 1, top)) hover = displayName(source, false);

        // 箭头：冲突项亮绿色，其余灰色
        String arrow      = ">";
        int    arrowX     = x + (cellW - font.width(arrow)) / 2;
        int    arrowColor = conflictKeys.contains(source) ? 0x55FF55 : 0x555555;
        g.drawString(font, arrow, arrowX, top + (ICON - font.lineHeight) / 2, arrowColor, false);

        // 目标图标（右）
        int tgtX = x + cellW - ICON - 1;
        renderIcon(g, itemIcon(target), tgtX, top);
        if (hoverIn(mx, my, tgtX, top)) hover = displayName(target, true);

        return hover;
    }

    // ── 事件处理 ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        if (!scanDone) return super.mouseClicked(mouseX, mouseY, button);

        // 点击 Tab 标签：切换 tab
        if (button == 0) {
            int mx = (int) mouseX, my = (int) mouseY;
            int tabY = gt + HEADER_H;
            int tabW = listW / 2;
            if (my >= tabY && my < tabY + TAB_H && mx >= listX && mx < listX + listW) {
                int newTab = mx < listX + tabW ? 0 : 1;
                if (newTab != selectedTab) {
                    selectedTab = newTab;
                    scrollRows  = 0;
                    selectedKeys.clear();
                    logTabState("TAB_CLICK -> " + (newTab == 0 ? "Minecraft" : "MOD"));
                    dbgFrames = 3;
                    clearWidgets();
                    buildLayout();
                }
                return true;
            }
        }

        // 点击格子：切换选中状态
        if (button == 0) {
            int mx = (int) mouseX, my = (int) mouseY;
            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
                int row = scrollRows + (my - listY) / CELL_H;
                int col = (mx - listX) / (cellW + GAP);
                if (col >= 0 && col < COLS) {
                    List<String> keys = displayKeys();
                    int idx = row * COLS + col;
                    if (idx < keys.size()) {
                        String key = keys.get(idx);
                        if (!selectedKeys.remove(key)) selectedKeys.add(key);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!scanDone) return false;
        if (mx < listX || mx >= listX + listW || my < listY || my >= listY + listH)
            return super.mouseScrolled(mx, my, delta);
        List<String> keys = displayKeys();
        int totalRows = (keys.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - effectiveMaxRows);
        scrollRows = Math.max(0, Math.min(maxScroll, scrollRows - (int) Math.signum(delta)));
        return true;
    }

    // ── 调试日志 ──────────────────────────────────────────────────────────

    private void logTabState(String event) {
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[RefSubs] === {} ===", event);
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[RefSubs]  selectedTab={}({})  hideConflicts={}  scrollRows={}",
                selectedTab, selectedTab == 0 ? "Minecraft" : "MOD",
                hideConflicts, scrollRows);
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[RefSubs]  map.size={}  conflictKeys={}  selectedKeys={}",
                map.size(), conflictKeys.size(), selectedKeys.size());

        List<String> keys = displayKeys();
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[RefSubs]  displayKeys.size={}", keys.size());
        int limit = Math.min(keys.size(), 20);
        for (int i = 0; i < limit; i++) {
            String k = keys.get(i);
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                    "[RefSubs]  [{}] {} -> {}{}{}",
                    i, k, map.get(k),
                    conflictKeys.contains(k) ? " [CONFLICT]" : "",
                    selectedKeys.contains(k)  ? " [SEL]"     : "");
        }
        if (keys.size() > limit)
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                    "[RefSubs]  ... and {} more", keys.size() - limit);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    // ── 反射：按 Deque 类型查找 PoseStack 的内部栈字段（不依赖字段名，兼容 Blaze3D 各版本）──
    private static final java.lang.reflect.Field MV_POSE_FIELD;
    static {
        java.lang.reflect.Field found = null;
        try {
            for (java.lang.reflect.Field f : com.mojang.blaze3d.vertex.PoseStack.class.getDeclaredFields()) {
                if (java.util.Deque.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    found = f;
                    break;
                }
            }
        } catch (Exception ignored) {}
        MV_POSE_FIELD = found;
    }

    @SuppressWarnings("unchecked")
    private static int mvDepth(com.mojang.blaze3d.vertex.PoseStack ps) {
        if (MV_POSE_FIELD == null) return -1;
        try { return ((java.util.Deque<?>) MV_POSE_FIELD.get(ps)).size(); }
        catch (Exception e) { return -1; }
    }

    private static void renderIcon(GuiGraphics g, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        dbgCalls++;

        String itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();

        // 已知崩溃物品：跳过重渲染，直接显示 BARRIER 占位，避免每帧重复抛异常造成卡顿
        if (KNOWN_CRASH.contains(itemKey)) {
            dbgBewlr++;
            try {
                g.renderItem(new ItemStack(Blocks.BARRIER), x, y);
            } catch (Throwable ignored) {}
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }

        com.mojang.blaze3d.vertex.PoseStack mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        int depthBefore = mvDepth(mv);
        Throwable caught = null;

        try {
            g.renderItem(stack, x, y);
        } catch (Throwable ex) {
            caught = ex;
            dbgThrows++;
            // 首次崩溃：加入已知崩溃集合，后续帧改用 BARRIER 不再重试
            KNOWN_CRASH.add(itemKey);
            // BEWLR 崩溃时可能已向 bufferSource 写入残余顶点，立即 endBatch 将其提交清空，
            // 防止下一次正常 endBatch() 把这批错误数据混入后续物品的渲染结果
            try {
                net.minecraft.client.Minecraft.getInstance()
                        .renderBuffers().bufferSource().endBatch();
            } catch (Throwable ignored) {}
        } finally {
            boolean corrected = false;
            if (depthBefore >= 0) {
                while (mvDepth(mv) > depthBefore) { mv.popPose(); corrected = true; }
            } else if (caught != null) {
                mv.popPose();
                corrected = true;
                com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.warn(
                    "[RefSubs][pop-fallback] popped for item={}", itemKey);
            }
            if (corrected) {
                com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
                dbgDepthChgs++;
            }
            if (caught != null) {
                com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.warn(
                    "[RefSubs][renderIcon] threw for {} depthBefore={} corrected={}: {}",
                    itemKey, depthBefore, corrected, caught.toString());
            }
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private boolean hoverIn(int mx, int my, int x, int y) {
        return mx >= x && mx < x + ICON && my >= y && my < y + ICON;
    }

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
        String s   = Component.translatable(key).getString();
        return s.equals(key) ? id : s;
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
            // 仅更新冲突集合（scan 结果保留）
            if (scanDone) {
                Set<String> configured = new java.util.HashSet<>();
                for (String e : com.xioyim.spatialblueprint.client.ConfigAccess.getList(
                        GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS)) {
                    int eq = e.indexOf('=');
                    if (eq > 0) configured.add(e.substring(0, eq).trim());
                }
                conflictKeys.clear();
                for (String k : map.keySet()) if (configured.contains(k)) conflictKeys.add(k);
                init();
            }
        }
    }
}
