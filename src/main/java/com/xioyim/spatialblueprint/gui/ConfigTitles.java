package com.xioyim.spatialblueprint.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 配置页面标题：黄色 + 下划线，悬浮显示"点击复制文件路径"，点击复制对应配置文件路径（无提示）。
 */
@OnlyIn(Dist.CLIENT)
public final class ConfigTitles {

    public static final String MAIN_CONFIG_PATH = FMLPaths.CONFIGDIR.get()
            .resolve("spatial_blueprint/spatial_blueprint.toml").toString();
    public static final String SUBS_CONFIG_PATH = FMLPaths.CONFIGDIR.get()
            .resolve("spatial_blueprint/block_substitutions.toml").toString();

    private static String filePath = "";
    private static int rx, ry, rw, rh;

    private ConfigTitles() {}

    /** 绘制黄色下划线标题，记录点击区域，悬浮显示复制提示；标题右侧黄色 ? 悬浮显示本配置用途。 */
    public static void render(GuiGraphics g, Font font, Component title, String path, String helpKey,
                              int cx, int y, int mx, int my) {
        String text = title.getString();
        int w = font.width(text);
        rx = cx - w / 2;
        ry = y;
        rw = w;
        rh = font.lineHeight;
        filePath = path != null ? path : "";
        Component styled = Component.literal(text)
                .withStyle(s -> s.withColor(0xFFFF00).withUnderlined(true));
        g.drawString(font, styled, rx, ry, 0xFFFF00, false);

        // 标题右侧黄色 "?"：悬浮显示本配置用途
        if (helpKey != null && !helpKey.isEmpty()) {
            int hx = rx + rw + 4;
            g.drawString(font, "?", hx, ry, 0xFFFF00, false);
            if (mx >= hx && mx < hx + font.width("?") && my >= ry && my < ry + rh) {
                g.renderComponentTooltip(font,
                        java.util.List.of(Component.translatable(helpKey)),
                        mx, my);
            }
        }

        if (mx >= rx && mx < rx + rw && my >= ry && my < ry + rh) {
            g.renderComponentTooltip(font,
                    java.util.List.of(Component.translatable("spatial_blueprint.config.title.click_copy")),
                    mx, my);
        }
    }

    /** 点击标题区域 → 复制文件路径（无提示）。命中返回 true。 */
    public static boolean click(int mx, int my) {
        if (!filePath.isEmpty() && mx >= rx && mx < rx + rw && my >= ry && my < ry + rh) {
            Minecraft.getInstance().keyboardHandler.setClipboard(filePath);
            return true;
        }
        return false;
    }
}
