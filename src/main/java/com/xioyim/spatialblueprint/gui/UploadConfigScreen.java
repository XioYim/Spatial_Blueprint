package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ClientConfigMirror;
import com.xioyim.spatialblueprint.client.ConfigAccess;
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
import java.util.List;

/**
 * 配置 · 上传安全（upload_security + nbt_budget）：
 * 上传 .schematic / .litematic 投影文件时的服务端安全检测参数。
 *
 * <p>这些检测<b>全部在服务端</b>执行（C2S_UploadProjectionTemplateMessage 校验链），
 * 客户端不预测，因此这里只需要 OP 通过快照读到服务端真值，不投影给普通玩家。</p>
 */
@OnlyIn(Dist.CLIENT)
public class UploadConfigScreen extends Screen {

    private final Screen parent;

    private static final int W        = 420;
    private static final int HEADER_H = 24;
    private static final int ROW_H    = 24;
    private static final int FOOTER_H = 28;
    private static final int PAD      = 4;
    private static final int BTN_H    = 18;
    /** 「重置为默认」小按钮（正方形）尺寸与它到输入框的间距。 */
    private static final int RESET_W   = 16;
    private static final int RESET_GAP = 3;

    private record Item(String labelKey, String descKey, ForgeConfigSpec.IntValue value) {}

    private static final Item[] ITEMS = {
            new Item("spatial_blueprint.config.upload.max_file", "spatial_blueprint.config.upload.max_file.desc",
                    BlueprintConfig.MAX_UPLOAD_FILE_BYTES),
            new Item("spatial_blueprint.config.upload.max_depth", "spatial_blueprint.config.upload.max_depth.desc",
                    BlueprintConfig.MAX_NBT_DEPTH),
            new Item("spatial_blueprint.config.upload.max_bbox", "spatial_blueprint.config.upload.max_bbox.desc",
                    BlueprintConfig.MAX_BOUNDING_VOLUME),
            new Item("spatial_blueprint.config.upload.cooldown", "spatial_blueprint.config.upload.cooldown.desc",
                    BlueprintConfig.UPLOAD_COOLDOWN_SECONDS),
            new Item("spatial_blueprint.config.upload.cooldown_op", "spatial_blueprint.config.upload.cooldown_op.desc",
                    BlueprintConfig.UPLOAD_COOLDOWN_SECONDS_OP),
            new Item("spatial_blueprint.config.upload.max_per_minute", "spatial_blueprint.config.upload.max_per_minute.desc",
                    BlueprintConfig.UPLOAD_MAX_PER_MINUTE),
            new Item("spatial_blueprint.config.upload.nbt_single", "spatial_blueprint.config.upload.nbt_single.desc",
                    BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES),
            new Item("spatial_blueprint.config.upload.nbt_total", "spatial_blueprint.config.upload.nbt_total.desc",
                    BlueprintConfig.MAX_TOTAL_NBT_BYTES),
    };

    private EditBox[] boxes;

    private int gl, gt, panelH, contentY, listX, listW;
    private int lastSnapshotVersion = -1;

    public UploadConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("spatial_blueprint.config.upload.title"));
        this.parent = parent;
        ConfigAccess.request();
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

        String[] prev = new String[ITEMS.length];
        if (boxes != null && lastSnapshotVersion == ClientConfigMirror.snapshotVersion()) {
            for (int i = 0; i < ITEMS.length; i++) {
                if (boxes[i] != null) prev[i] = boxes[i].getValue();
            }
        }
        boxes = new EditBox[ITEMS.length];

        int editW = 140;
        int boxX = listX + listW - editW;
        for (int i = 0; i < ITEMS.length; i++) {
            Item item = ITEMS[i];
            int y = contentY + i * ROW_H;
            EditBox box = new EditBox(this.font,
                    boxX, y + (ROW_H - 20) / 2, editW, 20, Component.empty());
            box.setMaxLength(12);
            box.setFilter(s -> s.isEmpty() || s.matches("-?\\d{0,10}"));
            box.setValue(prev[i] != null ? prev[i] : String.valueOf(ConfigAccess.get(item.value())));
            addRenderableWidget(box);
            boxes[i] = box;
            addResetButton(item.value(), box);
        }
        lastSnapshotVersion = ClientConfigMirror.snapshotVersion();

        int buttonY = gt + panelH - FOOTER_H + 5;
        int bW = (W - 2 * PAD - 16) / 3;
        int startX = gl + PAD;
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.general.save"),
                        b -> saveAll())
                .bounds(startX, buttonY, bW, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("spatial_blueprint.config.back"),
                        b -> onClose())
                .bounds(startX + (bW + 4) * 2, buttonY, bW, BTN_H).build());
    }

    private void addResetButton(ForgeConfigSpec.IntValue value, EditBox box) {
        int def = ConfigAccess.defaultValue(value);
        Button btn = Button.builder(Component.literal("\u267B"), b ->
                        box.setValue(String.valueOf(ConfigAccess.defaultValue(value))))
                .bounds(box.getX() - RESET_W - RESET_GAP, box.getY() + (20 - RESET_W) / 2, RESET_W, RESET_W)
                .build();
        btn.setTooltip(Tooltip.create(Component.translatable("spatial_blueprint.config.reset.tip", def)));
        addRenderableWidget(btn);
    }

    private void saveAll() {
        for (int i = 0; i < ITEMS.length; i++) {
            if (boxes[i] == null) continue;
            String s = boxes[i].getValue().trim();
            if (s.isEmpty()) continue;
            try {
                ConfigAccess.set(ITEMS[i].value(), Integer.parseInt(s));
            } catch (NumberFormatException ignored) {}
        }
        ConfigAccess.save();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(gl - 1, gt - 1, gl + W + 1, gt + panelH + 1, 0xFF555555);
        g.fill(gl, gt, gl + W, gt + panelH, 0xFF1C1C1C);
        g.fill(gl, gt, gl + W, gt + HEADER_H, 0xFF181818);
        ConfigTitles.render(g, this.font, this.title, ConfigTitles.MAIN_CONFIG_PATH,
                "spatial_blueprint.config.upload.help", gl + W / 2, gt + 8, mx, my);

        g.fill(listX, contentY, listX + listW, contentY + ITEMS.length * ROW_H, 0xFF141414);
        int editW = 140;
        // 输入框左旁有「重置」按钮，文字区相应收窄
        int labelW = listW - editW - (RESET_W + RESET_GAP) - 12;
        for (int i = 0; i < ITEMS.length; i++) {
            Item item = ITEMS[i];
            int y = contentY + i * ROW_H;
            String label = Component.translatable(item.labelKey()).getString();
            if (font.width(label) > labelW)
                label = font.plainSubstrByWidth(label, labelW - font.width("...")) + "...";
            g.drawString(this.font, label, listX + 4, y + (ROW_H - this.font.lineHeight) / 2,
                    0xFFFFFFFF, false);
            if (mx >= listX && mx <= listX + labelW && my >= y && my < y + ROW_H) {
                renderDescTooltip(g, item.descKey(), mx, my);
            }
        }
        super.render(g, mx, my, pt);
    }

    private void renderDescTooltip(GuiGraphics g, String key, int mx, int my) {
        String desc = Component.translatable(key).getString().replace("\\n", "\n");
        List<Component> lines = new java.util.ArrayList<>();
        for (String line : desc.split("\n", -1)) lines.add(Component.literal(line));
        g.renderComponentTooltip(this.font, lines, mx, my);
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
