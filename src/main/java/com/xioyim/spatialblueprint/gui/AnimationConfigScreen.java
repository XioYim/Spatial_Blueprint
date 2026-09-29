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

/**
 * 配置 · 动画与建造时间（animation）：方块动画速度、建造时间分档、时长上下限、
 * 冷却/撤回/播报/领地等。数字用 EditBox，开关用切换按钮。
 */
@OnlyIn(Dist.CLIENT)
public class AnimationConfigScreen extends Screen {

    private final Screen parent;

    private static final int W        = 380;
    private static final int HEADER_H = 24;
    private static final int ROW_H    = 24;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    /** 「重置为默认」小按钮（正方形）尺寸与它到输入框的间距。 */
    private static final int RESET_W   = 16;
    private static final int RESET_GAP = 3;

    private record Entry(String labelKey, String descKey, boolean isBool,
                         ForgeConfigSpec.IntValue num,
                         ForgeConfigSpec.BooleanValue bool) {}

    private static Entry n(String label, String desc, ForgeConfigSpec.IntValue v) {
        return new Entry(label, desc, false, v, null);
    }
    private static Entry b(String label, String desc, ForgeConfigSpec.BooleanValue v) {
        return new Entry(label, desc, true, null, v);
    }

    private static final Entry[] ITEMS = {
            n("spatial_blueprint.config.animation.speed",
                    "spatial_blueprint.config.animation.speed.desc", BlueprintConfig.ANIMATION_SPEED),
            n("spatial_blueprint.config.animation.duration_min",
                    "spatial_blueprint.config.animation.duration_min.desc", BlueprintConfig.ANIMATION_DURATION_MIN),
            n("spatial_blueprint.config.animation.duration_max",
                    "spatial_blueprint.config.animation.duration_max.desc", BlueprintConfig.ANIMATION_DURATION_MAX),
            n("spatial_blueprint.config.animation.extra_cooldown",
                    "spatial_blueprint.config.animation.extra_cooldown.desc", BlueprintConfig.EXTRA_COOLDOWN_SECONDS),
            n("spatial_blueprint.config.animation.undo_history",
                    "spatial_blueprint.config.animation.undo_history.desc", BlueprintConfig.MAX_UNDO_HISTORY),
            n("spatial_blueprint.config.animation.claim_cooldown",
                    "spatial_blueprint.config.animation.claim_cooldown.desc", BlueprintConfig.SUBPROJECT_CLAIM_COOLDOWN),
            b("spatial_blueprint.config.animation.broadcast_enabled",
                    "spatial_blueprint.config.animation.broadcast_enabled.desc", BlueprintConfig.LARGE_BLUEPRINT_BROADCAST_ENABLED),
            n("spatial_blueprint.config.animation.broadcast_threshold",
                    "spatial_blueprint.config.animation.broadcast_threshold.desc", BlueprintConfig.LARGE_BLUEPRINT_BROADCAST_THRESHOLD),
            b("spatial_blueprint.config.animation.deny_creative_protected",
                    "spatial_blueprint.config.animation.deny_creative_protected.desc", BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA),
    };

    private EditBox[] numBoxes;

    private int gl, gt, panelH, contentY, listX, listW;
    private int lastSnapshotVersion = -1;

    public AnimationConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.animation.title"));
        com.xioyim.spatialblueprint.client.ConfigAccess.request();
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        int contentH = ITEMS.length * ROW_H;
        panelH = HEADER_H + PAD + contentH + PAD + FOOTER_H;
        gl = (width - W) / 2;
        gt = Math.max(8, (height - panelH) / 2);
        contentY = gt + HEADER_H + PAD;
        listX = gl + PAD;
        listW = W - PAD * 2;

        // 保留已输入值
        String[] prev = new String[ITEMS.length];
        if (numBoxes != null) {
            for (int i = 0; i < ITEMS.length; i++) {
                if (numBoxes[i] != null) prev[i] = numBoxes[i].getValue();
            }
        }
        numBoxes = new EditBox[ITEMS.length];

        int editW = 110;
        int boxX = listX + listW - editW;
        for (int i = 0; i < ITEMS.length; i++) {
            Entry item = ITEMS[i];
            int y = contentY + i * ROW_H;
            if (item.isBool()) {
                boolean val = com.xioyim.spatialblueprint.client.ConfigAccess.get(item.bool());
                Component text = Component.translatable("spatial_blueprint.config.bool."
                        + (val ? "on" : "off"));
                final int idx = i;
                addRenderableWidget(Button.builder(text, btn -> {
                            com.xioyim.spatialblueprint.client.ConfigAccess.set(item.bool(), !com.xioyim.spatialblueprint.client.ConfigAccess.get(item.bool()));
                            init();
                        })
                        .bounds(boxX, y + (ROW_H - 20) / 2, editW, 20).build());
            } else {
                EditBox box = new EditBox(this.font,
                        boxX, y + (ROW_H - 20) / 2, editW, 20,
                        Component.translatable(item.labelKey()));
                box.setMaxLength(12);
                box.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,10}"));
                box.setValue(prev[i] != null ? prev[i] : String.valueOf(com.xioyim.spatialblueprint.client.ConfigAccess.get(item.num())));
                addRenderableWidget(box);
                numBoxes[i] = box;
                addResetButton(item.num(), box, y);
            }
        }

        // 底部按钮
        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 16) / 3;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.save"),
                        b -> saveAll())
                .bounds(startX, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.animation.tiers_btn"),
                        b -> Minecraft.getInstance().setScreen(new BuildTimeTierScreen(this)))
                .bounds(startX + bW + 4, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"),
                        b -> onClose())
                .bounds(startX + (bW + 4) * 2, buttonY, bW, BTN_H).build());
    }

    /**
     * 提交给<b>服务端</b>写入 —— 界面读写的是服务端配置，不是客户端本地的 toml。
     */
    private void addResetButton(ForgeConfigSpec.IntValue value, EditBox box, int rowY) {
        int def = com.xioyim.spatialblueprint.client.ConfigAccess.defaultValue(value);
        Button btn = Button.builder(Component.literal("\u267B"), b ->
                        box.setValue(String.valueOf(
                                com.xioyim.spatialblueprint.client.ConfigAccess.defaultValue(value))))
                .bounds(box.getX() - RESET_W - RESET_GAP, rowY + (ROW_H - RESET_W) / 2, RESET_W, RESET_W)
                .build();
        btn.setTooltip(Tooltip.create(Component.translatable("spatial_blueprint.config.reset.tip", def)));
        addRenderableWidget(btn);
    }

    private void saveAll() {
        for (int i = 0; i < ITEMS.length; i++) {
            Entry item = ITEMS[i];
            if (item.isBool()) continue;   // 布尔项点一下就已经进了待提交集合
            if (numBoxes[i] == null) continue;
            String s = numBoxes[i].getValue().trim();
            if (s.isEmpty()) continue;
            try {
                com.xioyim.spatialblueprint.client.ConfigAccess.set(item.num(), Integer.parseInt(s));
            } catch (NumberFormatException ignored) {}
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
                "spatial_blueprint.config.animation.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, contentY, listX + listW, contentY + ITEMS.length * ROW_H, 0xFF141414);
        int editW = 110;
        // 右侧：输入框 / 布尔开关。数字行输入框左旁还有「重置」按钮，文字区相应收窄
        int labelW = listW - editW - (RESET_W + RESET_GAP) - 12;
        for (int i = 0; i < ITEMS.length; i++) {
            Entry item = ITEMS[i];
            int y = contentY + i * ROW_H;
            String label = Component.translatable(item.labelKey()).getString();
            if (font.width(label) > labelW)
                label = font.plainSubstrByWidth(label, labelW - font.width("...")) + "...";
            g.drawString(this.font, label, listX + 4, y + (ROW_H - this.font.lineHeight) / 2, 0xFFFFFFFF, false);
            if (mx >= listX && mx <= listX + labelW && my >= y && my < y + ROW_H) {
                String desc = Component.translatable(item.descKey()).getString().replace("\\n", "\n");
                java.util.List<Component> lines = new java.util.ArrayList<>();
                for (String line : desc.split("\n", -1)) lines.add(Component.literal(line));
                g.renderComponentTooltip(this.font, lines, mx, my);
            }
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
        int v = com.xioyim.spatialblueprint.client.ClientConfigMirror.snapshotVersion();
        if (v != lastSnapshotVersion) {
            lastSnapshotVersion = v;
            init(); // 快照到达，重建所有控件以显示服务端真实值
        }
    }
}
