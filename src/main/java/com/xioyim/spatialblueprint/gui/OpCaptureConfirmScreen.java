package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.CaptureAreaMessage;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * OP 捕捉确认对话框：检测到蓝图区域内含危险内容（容器物品、告示牌文字、展示框危险物品）时弹出。
 * [确认] → 以 opBypass=true 重新发送捕捉请求，保留全部原始 NBT 内容。
 * [取消] → 关闭，放弃本次捕捉。
 */
@OnlyIn(Dist.CLIENT)
public class OpCaptureConfirmScreen extends net.minecraft.client.gui.screens.Screen {

    private final String name;
    private final boolean clearAfterCapture;
    private final int durationSeconds;
    private final FluidCaptureFilter fluidCaptureFilter;

    public OpCaptureConfirmScreen(String name, boolean clearAfterCapture,
                                   int durationSeconds, FluidCaptureFilter fluidCaptureFilter) {
        super(Component.translatable("spatial_blueprint.gui.op_capture_confirm.title"));
        this.name = name;
        this.clearAfterCapture = clearAfterCapture;
        this.durationSeconds = durationSeconds;
        this.fluidCaptureFilter = fluidCaptureFilter;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int cy = this.height / 2;
        int btnW = 100;
        int btnH = 20;

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.op_capture_confirm.btn.confirm"),
                        b -> {
                            BlueprintNetwork.CHANNEL.sendToServer(
                                    new CaptureAreaMessage(name, clearAfterCapture, durationSeconds,
                                            fluidCaptureFilter, true));
                            this.onClose();
                        })
                .bounds(cx - btnW - 4, cy + 30, btnW, btnH)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.op_capture_confirm.btn.cancel"),
                        b -> this.onClose())
                .bounds(cx + 4, cy + 30, btnW, btnH)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        // 标题
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFF5555);
        // 描述（换行显示）
        Component desc = Component.translatable("spatial_blueprint.gui.op_capture_confirm.desc");
        int maxWidth = Math.min(300, this.width - 40);
        int lineY = this.height / 2 - 20;
        for (net.minecraft.util.FormattedCharSequence line :
                this.font.split(desc, maxWidth)) {
            g.drawCenteredString(this.font, line, this.width / 2, lineY, 0xFFFFAA);
            lineY += this.font.lineHeight + 2;
        }
        super.render(g, mx, my, pt);
    }
}
