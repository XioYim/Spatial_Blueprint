package com.xioyim.spatialblueprint.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 游戏式错误横幅工具类（无状态）。
 *
 * 布局（屏幕垂直正中，横幅左右超出屏幕）：
 * <pre>
 *   按 ESC 或点击其他区域关闭     ← 提示（横幅上方，居中，灰色）
 *  ━━━━━━━━━━━━━━━━━━━━━━━━━━━   ← 横幅（超出屏幕左右各 OVERFLOW px）
 *    错误消息文字（可多行）        ← 消息（横幅内，居中，白色）
 *  ━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * </pre>
 *
 * 横幅高度：最小 BANNER_MIN_H，文本超出时自动撑高。
 * 任意点击或 ESC 均可消除。
 */
@OnlyIn(Dist.CLIENT)
public final class ErrorToast {

    /** 横幅最小高度（px）。 */
    private static final int BANNER_MIN_H = 56;
    /** 左右各超出屏幕边缘的量（px）。 */
    private static final int OVERFLOW     = 24;
    /** 提示文字与横幅上边缘的间距（px）。 */
    private static final int TIP_GAP      = 6;
    /** 消息文字距横幅上下边缘的最小内边距（px）。 */
    private static final int TEXT_PAD_V   = 14;
    /** 消息文字距横幅左右边缘的内边距（px）。 */
    private static final int TEXT_PAD_H   = 40;
    /** 多行文字行间距（px）。 */
    private static final int LINE_GAP     = 3;

    private ErrorToast() {}

    /**
     * 渲染错误横幅。在宿主 Screen.render() 末尾调用，z=300 层级最高。
     * 全屏 62% 黑色遮罩模拟背后 UI 模糊（MC 无原生区域模糊 shader）。
     */
    public static void render(GuiGraphics graphics, Font font,
                              int screenW, int screenH, Component message) {
        // 按横幅可用宽度换行
        int contentW = screenW - TEXT_PAD_H * 2;
        List<FormattedCharSequence> lines = font.split(message, contentW);

        // 计算文本总高度，决定横幅实际高度
        int totalTextH = lines.size() * font.lineHeight
                + Math.max(0, lines.size() - 1) * LINE_GAP;
        int bannerH = Math.max(BANNER_MIN_H, totalTextH + TEXT_PAD_V * 2);
        int bannerY = (screenH - bannerH) / 2;
        int cx = screenW / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000);

        // 全屏遮罩（模拟背后 UI 模糊）
        graphics.fill(0, 0, screenW, screenH, 0xA0000000);

        // 横幅背景（左右超出屏幕）
        graphics.fill(-OVERFLOW, bannerY, screenW + OVERFLOW, bannerY + bannerH, 0xFF141414);

        // 横幅上下边缘线
        int lc = 0xFF3A3A3A;
        graphics.fill(-OVERFLOW, bannerY,               screenW + OVERFLOW, bannerY + 1,         lc);
        graphics.fill(-OVERFLOW, bannerY + bannerH - 1, screenW + OVERFLOW, bannerY + bannerH,   lc);

        // 消息文字（横幅内，垂直居中，逐行居中）
        int ty = bannerY + (bannerH - totalTextH) / 2;
        for (FormattedCharSequence seq : lines) {
            graphics.drawCenteredString(font, seq, cx, ty, 0xFFFFFFFF);
            ty += font.lineHeight + LINE_GAP;
        }

        // 关闭提示（横幅上方，居中，灰色小字）
        Component tip = Component.translatable("spatial_blueprint.gui.error_toast.tip");
        graphics.drawCenteredString(font, tip,
                cx, bannerY - TIP_GAP - font.lineHeight, 0xFF888888);

        graphics.pose().popPose();
    }
}
