package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置 · 通用（general）。
 *
 * <p>按<b>配置文件里的分组</b>排版，而不是一条大平铺列表 —— 每个
 * {@code BUILDER.push(...)} 段对应界面上一个居中的黄色小标题，
 * 小标题的悬浮提示就是该段在 toml 里的那块 {@code #} 注释。
 * 段与段之间空一行。</p>
 *
 * <p>行数超过 {@link #VISIBLE_ROWS} 时用滚轮滚动。<b>三种行等高</b>（都是 {@link #ROW_H}），
 * 滚轮每格正好滚一行 —— 高度不一的话没有任何固定步长能让每种行都停得整齐，
 * 滚几格之后行的上沿就会落在视口中间，出现被裁掉一半的文字。
 * 列表区开了裁剪兜底，输入框则按可见性逐帧显隐，
 * 因为 {@code EditBox} 是 Widget，由 {@code super.render()} 绘制，不受裁剪影响。</p>
 */
@OnlyIn(Dist.CLIENT)
public class GeneralConfigScreen extends Screen {

    private final Screen parent;

    private static final int W        = 360;
    private static final int HEADER_H = 24;
    private static final int ROW_H    = 26;
    private static final int GROUP_H  = ROW_H;   // 小标题行，与项目行等高
    private static final int SPACER_H = ROW_H;   // 段间空行，同样等高
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;

    /** 列表区一屏最多容纳的行位（小标题与空行同样占一个行位）。 */
    private static final int VISIBLE_ROWS = 12;
    /** 滚轮每格滚动的像素 —— 恒等于行高，因此每次停下来行的上沿都是齐的。 */
    private static final int SCROLL_STEP  = ROW_H;
    /** 滚动条宽度。 */
    private static final int SB_W = 3;

    /** 「重置为默认」小按钮（正方形）尺寸与它到输入框的间距。 */
    private static final int RESET_W   = 16;
    private static final int RESET_GAP = 3;

    // ── 行模型 ────────────────────────────────────────────────────────────

    private enum Kind { GROUP, SPACER, NUMBER }

    /**
     * 列表里的一行。
     *
     * @param labelKey GROUP=小标题文案；NUMBER=条目名；SPACER 不用
     * @param descKey  悬浮提示（GROUP 用段注释，NUMBER 用单项说明）
     */
    private record Row(Kind kind, String labelKey,
                       @Nullable ForgeConfigSpec.IntValue value, String descKey) {

        static Row group(String key) {
            return new Row(Kind.GROUP, "spatial_blueprint.config.general.group." + key,
                    null, "spatial_blueprint.config.general.group." + key + ".desc");
        }

        static Row spacer() {
            return new Row(Kind.SPACER, "", null, "");
        }

        static Row num(String key, ForgeConfigSpec.IntValue value) {
            return new Row(Kind.NUMBER, "spatial_blueprint.config.general." + key,
                    value, "spatial_blueprint.config.general." + key + ".desc");
        }

        int height() {
            return switch (kind) {
                case GROUP  -> GROUP_H;
                case SPACER -> SPACER_H;
                case NUMBER -> ROW_H;
            };
        }
    }

    /** 顺序与 {@code BlueprintConfig} 里的 push 段一致，改配置时两边一起改。 */
    private static final Row[] ROWS = {
            Row.group("volume"),
            Row.num("volume_spatial",      BlueprintConfig.MAX_VOLUME_SPATIAL),
            Row.num("volume_projection",   BlueprintConfig.MAX_VOLUME_PROJECTION),
            Row.num("volume_dimensional",  BlueprintConfig.MAX_VOLUME_DIMENSIONAL),
            Row.num("volume_engineering",  BlueprintConfig.MAX_VOLUME_ENGINEERING),
            Row.num("volume_template",     BlueprintConfig.MAX_VOLUME_TEMPLATE),
            Row.num("volume_projectset",   BlueprintConfig.MAX_VOLUME_PROJECTSET),
            Row.num("volume_subproject",   BlueprintConfig.MAX_VOLUME_SUBPROJECT),
            Row.spacer(),

            Row.group("slot"),
            Row.num("slot_spatial",        BlueprintConfig.SLOT_LIMIT_SPATIAL),
            Row.num("slot_projection",     BlueprintConfig.SLOT_LIMIT_PROJECTION),
            Row.num("slot_dimensional",    BlueprintConfig.SLOT_LIMIT_DIMENSIONAL),
            Row.num("slot_engineering",    BlueprintConfig.SLOT_LIMIT_ENGINEERING),
            Row.num("slot_template",       BlueprintConfig.SLOT_LIMIT_TEMPLATE),
            Row.spacer(),

            Row.group("preview"),
            Row.num("preview_distance",    BlueprintConfig.PREVIEW_DISTANCE),
            Row.num("cancel_margin",       BlueprintConfig.RENDER_CANCEL_MARGIN),
            Row.num("broadcast_radius",    BlueprintConfig.PREVIEW_BROADCAST_RADIUS),
            Row.num("broadcast_interval",  BlueprintConfig.PREVIEW_BROADCAST_INTERVAL),
            Row.spacer(),

            Row.group("logs"),
            Row.num("log_placement",       BlueprintConfig.LOG_RETENTION_DAYS_PLACEMENT),
            Row.num("log_admin",           BlueprintConfig.LOG_RETENTION_DAYS_ADMIN),
    };

    // ── 布局与状态 ────────────────────────────────────────────────────────

    /** 与 ROWS 一一对应，非 NUMBER 行为 null。 */
    private EditBox[] boxes;
    /** 与 ROWS 一一对应的「重置为默认」按钮，非 NUMBER 行为 null。 */
    private Button[] resetButtons;
    /** 每行在内容坐标系里的 y 偏移（不含滚动）。 */
    private int[] rowTop;
    private int contentH;

    private int scrollY = 0;
    private int maxScroll = 0;

    private int gl, gt, panelH, viewH, listX, listY, listW;
    /** 上次刷新时读到的快照版本号；版本变化时重填文本框。 */
    private int lastSnapshotVersion = -1;

    public GeneralConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.general.title"));
        // 打开就向服务端要一份当前配置 —— 界面读写的是服务端的值，不是本地 toml
        com.xioyim.spatialblueprint.client.ConfigAccess.request();
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        // 行偏移表
        rowTop = new int[ROWS.length];
        int acc = 0;
        for (int i = 0; i < ROWS.length; i++) {
            rowTop[i] = acc;
            acc += ROWS[i].height();
        }
        contentH = acc;

        // 一屏 12 个行位；屏幕不够高时再压缩，保证面板整体不出界
        int wanted = VISIBLE_ROWS * ROW_H;
        int room   = height - 40 - HEADER_H - FOOTER_H - PAD * 2;
        viewH = Math.max(ROW_H * 3, Math.min(wanted, room));

        panelH = HEADER_H + PAD + viewH + PAD + FOOTER_H;
        gl = (width  - W) / 2;
        gt = (height - panelH) / 2;
        listX = gl + PAD;
        listY = gt + HEADER_H + PAD;
        listW = W - PAD * 2;

        maxScroll = Math.max(0, contentH - viewH);
        scrollY = clamp(scrollY, 0, maxScroll);

        int editW = 130;
        int boxX = listX + listW - editW - SB_W - 2 - RESET_W - RESET_GAP;
        boxes = new EditBox[ROWS.length];
        resetButtons = new Button[ROWS.length];
        for (int i = 0; i < ROWS.length; i++) {
            if (ROWS[i].kind() != Kind.NUMBER) continue;
            EditBox box = new EditBox(this.font, boxX, 0, editW, 20,
                    Component.translatable(ROWS[i].labelKey()));
            box.setMaxLength(12);
            box.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,10}"));
            box.setValue(String.valueOf(com.xioyim.spatialblueprint.client.ConfigAccess.get(ROWS[i].value())));
            final int idx = i;
            box.setResponder(s -> {
                try {
                    com.xioyim.spatialblueprint.client.ConfigAccess.set(
                            ROWS[idx].value(), Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {}
            });
            addRenderableWidget(box);
            boxes[i] = box;
            resetButtons[i] = addResetButton(ROWS[i].value(), box);
        }
        layoutBoxes();

        // 底部按钮行：编辑禁捕方块 / 编辑禁物品 / 保存 / 返回
        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 20) / 4;
        int gap = 4;
        int total = bW * 4 + gap * 3;
        int startX = gl + (W - total) / 2;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.edit_excluded"), b ->
                        Minecraft.getInstance().setScreen(new ExcludedBlocksScreen(this)))
                .bounds(startX, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.edit_excluded_items"), b ->
                        Minecraft.getInstance().setScreen(new BlockListConfigScreen(this,
                                Component.translatable("spatial_blueprint.config.excluded_items.title"),
                                "spatial_blueprint.config.excluded_items.help",
                                BlueprintConfig.EXCLUDED_ITEMS, true)))
                .bounds(startX + bW + gap, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.save"), b -> saveAll())
                .bounds(startX + (bW + gap) * 2, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"), b -> onClose())
                .bounds(startX + (bW + gap) * 3, buttonY, bW, BTN_H).build());
    }

    /**
     * 按当前滚动位置摆放输入框，整行不在可视区内的直接隐藏。
     *
     * <p>{@code EditBox} 由 {@code super.render()} 绘制，不受列表区裁剪约束 ——
     * 不隐藏的话滚出去的输入框会画到面板外面。</p>
     */
    private void layoutBoxes() {
        for (int i = 0; i < ROWS.length; i++) {
            EditBox box = boxes[i];
            if (box == null) continue;
            int y = listY + rowTop[i] - scrollY + 3;
            boolean visible = y >= listY && y + 20 <= listY + viewH;
            box.visible = visible;
            box.active  = visible;
            box.setPosition(box.getX(), y);
            Button reset = resetButtons[i];
            if (reset != null) {
                reset.visible = visible;
                reset.active  = visible;
                reset.setY(y + (20 - RESET_W) / 2);
            }
        }
    }

    /** 在输入框左侧加一个「重置为默认」小按钮（正方形）。 */
    private Button addResetButton(ForgeConfigSpec.IntValue value, EditBox box) {
        int def = com.xioyim.spatialblueprint.client.ConfigAccess.defaultValue(value);
        Button btn = Button.builder(Component.literal("\u267B"), b ->
                        box.setValue(String.valueOf(
                                com.xioyim.spatialblueprint.client.ConfigAccess.defaultValue(value))))
                .bounds(box.getX() - RESET_W - RESET_GAP, 0, RESET_W, RESET_W)
                .build();
        btn.setTooltip(Tooltip.create(Component.translatable(
                "spatial_blueprint.config.reset.tip", def)));
        addRenderableWidget(btn);
        return btn;
    }

    /**
     * 把界面上的值提交给<b>服务端</b>写入。
     *
     * <p>以前这里是 {@code value.set(); value.save();} —— 写的是客户端自己那份 toml，
     * 服主在服务器上改完毫无效果。现在只把改动送进待提交集合，由
     * {@link com.xioyim.spatialblueprint.client.ConfigAccess#save()} 一次性发回服务端。</p>
     */
    private void saveAll() {
        for (int i = 0; i < ROWS.length; i++) {
            if (boxes[i] == null) continue;
            String s = boxes[i].getValue().trim();
            if (s.isEmpty()) continue;
            try {
                com.xioyim.spatialblueprint.client.ConfigAccess.set(
                        ROWS[i].value(), Integer.parseInt(s));
            } catch (NumberFormatException ignored) {}
        }
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        layoutBoxes();

        g.fill(0, 0, width, height, 0x88000000);                         // 全屏遮罩
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555); // 外边框
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);                 // 面板主体
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);               // 标题区
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.general.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, listY, listX + listW, listY + viewH, 0xFF141414);

        int editW = 130;
        int boxX = listX + listW - editW - SB_W - 2 - RESET_W - RESET_GAP;
        int labelW = boxX - (RESET_W + RESET_GAP) - listX - 8;
        boolean overList = mx >= listX && mx < listX + listW && my >= listY && my < listY + viewH;
        String tooltipKey = null;

        g.enableScissor(listX, listY, listX + listW, listY + viewH);
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            int y = listY + rowTop[i] - scrollY;
            int h = row.height();
            if (y + h < listY || y > listY + viewH) continue;   // 整行在视口外，跳过

            switch (row.kind()) {
                case SPACER -> { }
                case GROUP -> {
                    String text = Component.translatable(row.labelKey()).getString();
                    int tw = font.width(text);
                    int cx = listX + (listW - SB_W - 2) / 2;
                    int ty = y + (GROUP_H - font.lineHeight) / 2;
                    g.drawString(this.font, text, cx - tw / 2, ty, 0xFFFFD24A, false);
                    // 下划线：贴着文字底部，宽度跟文字走
                    g.fill(cx - tw / 2, ty + font.lineHeight, cx + tw / 2, ty + font.lineHeight + 1, 0xFFFFD24A);
                    if (overList && my >= y && my < y + h
                            && mx >= cx - tw / 2 && mx <= cx + tw / 2) {
                        tooltipKey = row.descKey();
                    }
                }
                case NUMBER -> {
                    String label = Component.translatable(row.labelKey()).getString();
                    if (font.width(label) > labelW) {
                        label = font.plainSubstrByWidth(label, labelW - font.width("...")) + "...";
                    }
                    g.drawString(this.font, label, listX + 4, y + 9, 0xFFFFFFFF, false);
                    if (overList && my >= y && my < y + h && mx >= listX && mx <= listX + labelW) {
                        tooltipKey = row.descKey();
                    }
                }
            }
        }
        g.disableScissor();

        // 滚动条：内容装得下就不画
        if (maxScroll > 0) {
            int trackX = listX + listW - SB_W;
            g.fill(trackX, listY, trackX + SB_W, listY + viewH, 0xFF2A2A2A);
            int thumbH = Math.max(12, viewH * viewH / contentH);
            int thumbY = listY + (viewH - thumbH) * scrollY / maxScroll;
            g.fill(trackX, thumbY, trackX + SB_W, thumbY + thumbH, 0xFF777777);
        }

        super.render(g, mx, my, pt);

        // tooltip 最后画，压在输入框之上
        if (tooltipKey != null) renderDescTooltip(g, tooltipKey, mx, my);
    }

    /**
     * 逐行渲染说明文本。
     *
     * <p>手动按 {@code \n} 拆行而不是交给 {@code translatable} —— 语言文件里的换行
     * 在运行时不一定被拆开。行内的 §颜色码由字体渲染器自己处理，
     * 因此关键词着色直接写在 lang 值里。</p>
     */
    private void renderDescTooltip(GuiGraphics g, String key, int mx, int my) {
        String desc = Component.translatable(key).getString().replace("\\n", "\n");
        List<Component> lines = new ArrayList<>();
        for (String line : desc.split("\n", -1)) lines.add(Component.literal(line));
        g.renderComponentTooltip(this.font, lines, mx, my);
    }

    // ── 交互 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll > 0
                && mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + viewH) {
            scrollY = clamp(scrollY - (int) Math.signum(delta) * SCROLL_STEP, 0, maxScroll);
            layoutBoxes();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void tick() {
        super.tick();
        int v = com.xioyim.spatialblueprint.client.ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            lastSnapshotVersion = v;
            refreshBoxes();
        }
    }

    /** 用当前快照重填所有数字输入框（快照刚到时调用）。 */
    private void refreshBoxes() {
        if (boxes == null) return;
        for (int i = 0; i < ROWS.length; i++) {
            if (ROWS[i].kind() != Kind.NUMBER || boxes[i] == null) continue;
            boxes[i].setValue(String.valueOf(
                    com.xioyim.spatialblueprint.client.ConfigAccess.get(ROWS[i].value())));
        }
    }
}
