package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ClientConfigMirror;
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

/**
 * 配置 · 建造时间分档（子页）：按方块数分 6 档。
 * 每行：{@code 分档N [方块上限][时长(秒)]}，输入框悬浮 tooltip 显示用途。
 *
 * <p>读写一律走 {@link com.xioyim.spatialblueprint.client.ConfigAccess}（服务端镜像 + 提交），
 * 不再直写本地 toml —— 服主在服务器上敲 /sb config 改的必须是服务端那份。</p>
 */
@OnlyIn(Dist.CLIENT)
public class BuildTimeTierScreen extends Screen {

    private final Screen parent;

    private static final int W        = 380;
    private static final int HEADER_H = 24;
    private static final int ROW_H    = 26;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    /** 「重置为默认」小按钮（正方形）尺寸与它到输入框的间距。 */
    private static final int RESET_W   = 16;
    private static final int RESET_GAP = 3;

    private record Row(@Nullable ForgeConfigSpec.IntValue max, ForgeConfigSpec.IntValue sec) {}

    private static final Row[] ROWS = {
            new Row(BlueprintConfig.BUILD_TIME_TIER1_MAX_BLOCKS, BlueprintConfig.BUILD_TIME_TIER1_SECONDS),
            new Row(BlueprintConfig.BUILD_TIME_TIER2_MAX_BLOCKS, BlueprintConfig.BUILD_TIME_TIER2_SECONDS),
            new Row(BlueprintConfig.BUILD_TIME_TIER3_MAX_BLOCKS, BlueprintConfig.BUILD_TIME_TIER3_SECONDS),
            new Row(BlueprintConfig.BUILD_TIME_TIER4_MAX_BLOCKS, BlueprintConfig.BUILD_TIME_TIER4_SECONDS),
            new Row(BlueprintConfig.BUILD_TIME_TIER5_MAX_BLOCKS, BlueprintConfig.BUILD_TIME_TIER5_SECONDS),
            new Row(BlueprintConfig.BUILD_TIME_TIER6_MAX_BLOCKS, BlueprintConfig.BUILD_TIME_TIER6_SECONDS),
            new Row(null, BlueprintConfig.BUILD_TIME_TIER7_SECONDS),
    };

    private EditBox[][] boxes; // [row][0=max,1=sec]

    private int gl, gt, panelH, contentY, listX, listW;
    private int lastSnapshotVersion = -1;

    public BuildTimeTierScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.animation.tiers.title"));
        this.parent = parent;
        com.xioyim.spatialblueprint.client.ConfigAccess.request();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        int contentH = ROWS.length * ROW_H;
        panelH = HEADER_H + PAD + contentH + PAD + FOOTER_H;
        gl = (width - W) / 2;
        gt = Math.max(8, (height - panelH) / 2);
        contentY = gt + HEADER_H + PAD;
        listX = gl + PAD;
        listW = W - PAD * 2;

        // 保留已输入值（仅在快照版本没变时）；快照到了以后用服务端真值重建
        String[][] prev = (lastSnapshotVersion == com.xioyim.spatialblueprint.client.ClientConfigMirror.snapshotVersion())
                ? capturePrev()
                : null;
        boxes = new EditBox[ROWS.length][2];

        int editW = 96;
        for (int r = 0; r < ROWS.length; r++) {
            Row row = ROWS[r];
            int y = contentY + r * ROW_H;
            int centerY = y + (ROW_H - 20) / 2;
            // 右对齐：时长框贴列表右缘，其左是时长重置钮、上限框、上限重置钮
            int secX = listX + listW - editW;
            int secResetX = secX - RESET_W - RESET_GAP;
            // 上限框
            if (row.max() != null) {
                int maxX = secResetX - 4 - editW;
                String init = prev != null ? prev[r][0] : String.valueOf(
                        com.xioyim.spatialblueprint.client.ConfigAccess.get(row.max()));
                EditBox max = new EditBox(this.font, maxX, centerY, editW, 20, Component.empty());
                max.setMaxLength(12);
                max.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,10}"));
                max.setValue(init);
                max.setTooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.config.animation.tier.max_tip")));
                addRenderableWidget(max);
                boxes[r][0] = max;
                addResetButton(row.max(), max);
            }
            // 时长框
            String initSec = prev != null ? prev[r][1] : String.valueOf(
                    com.xioyim.spatialblueprint.client.ConfigAccess.get(row.sec()));
            EditBox sec = new EditBox(this.font, secX, centerY, editW, 20, Component.empty());
            sec.setMaxLength(12);
            sec.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,10}"));
            sec.setValue(initSec);
            sec.setTooltip(Tooltip.create(Component.translatable(
                    "spatial_blueprint.config.animation.tier.sec_tip")));
            addRenderableWidget(sec);
            boxes[r][1] = sec;
            addResetButton(row.sec(), sec);
        }
        lastSnapshotVersion = ClientConfigMirror.snapshotVersion();

        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 12) / 3;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.save"),
                        b -> saveAll())
                .bounds(startX, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"),
                        b -> onClose())
                .bounds(startX + bW + 4, buttonY, bW, BTN_H).build());
    }

    private void addResetButton(ForgeConfigSpec.IntValue value, EditBox box) {
        int def = com.xioyim.spatialblueprint.client.ConfigAccess.defaultValue(value);
        Button btn = Button.builder(Component.literal("\u267B"), b ->
                        box.setValue(String.valueOf(
                                com.xioyim.spatialblueprint.client.ConfigAccess.defaultValue(value))))
                .bounds(box.getX() - RESET_W - RESET_GAP, box.getY() + (20 - RESET_W) / 2, RESET_W, RESET_W)
                .build();
        btn.setTooltip(Tooltip.create(Component.translatable("spatial_blueprint.config.reset.tip", def)));
        addRenderableWidget(btn);
    }

    private String[][] capturePrev() {
        if (boxes == null) return null;
        String[][] prev = new String[ROWS.length][2];
        for (int r = 0; r < ROWS.length; r++) {
            for (int c = 0; c < 2; c++) {
                prev[r][c] = (boxes[r][c] != null) ? boxes[r][c].getValue() : null;
            }
        }
        return prev;
    }

    private void saveAll() {
        for (int r = 0; r < ROWS.length; r++) {
            Row row = ROWS[r];
            for (int c = 0; c < 2; c++) {
                if (boxes[r][c] == null) continue;
                ForgeConfigSpec.IntValue cfg = (c == 0) ? row.max() : row.sec();
                if (cfg == null) continue;
                String s = boxes[r][c].getValue().trim();
                if (s.isEmpty()) continue;
                try {
                    com.xioyim.spatialblueprint.client.ConfigAccess.set(cfg, Integer.parseInt(s));
                } catch (NumberFormatException ignored) {}
            }
        }
        com.xioyim.spatialblueprint.client.ConfigAccess.save();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.animation.tiers.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, contentY, listX + listW, contentY + ROWS.length * ROW_H, 0xFF141414);
        // 分档标签 + 行分隔
        for (int r = 0; r < ROWS.length; r++) {
            int y = contentY + r * ROW_H;
            g.drawString(this.font,
                    Component.translatable("spatial_blueprint.config.animation.tier.label", r + 1).getString(),
                    listX + 4, y + (ROW_H - this.font.lineHeight) / 2, 0xFFFFAA, false);
        }
        for (int r = 0; r <= ROWS.length; r++) {
            int y = contentY + r * ROW_H;
            g.fill(listX, y, listX + listW, y + 1, 0xFF222222);
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ConfigTitles.click((int) mouseX, (int) mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void tick() {
        super.tick();
        int v = ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            init(); // 快照到达 / 保存后刷新为服务端真实值
        }
    }
}
