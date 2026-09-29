package com.xioyim.spatialblueprint.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 独立的错误横幅界面 —— 用于当前<b>没有任何界面打开</b>时展示报错。
 *
 * <p>{@link ErrorToast} 是无状态渲染工具，必须依附在某个 Screen 的 {@code render()} 里。
 * 但玩家在世界中右键物品触发的报错往往没有宿主界面，以前只能退回聊天栏，很容易被忽略。
 * 本界面就是给这类场景当宿主：只画横幅，任意点击或 ESC 关闭。</p>
 *
 * <p>不暂停游戏（{@link #isPauseScreen()} 返回 false），避免单人模式因一条提示卡住世界。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ErrorToastScreen extends Screen implements ErrorToastSupport {

    private Component message;
    /** 关闭后回到的界面；{@code null} 表示回到游戏。 */
    @javax.annotation.Nullable
    private final Screen parent;

    public ErrorToastScreen(Component message) {
        this(message, null);
    }

    public ErrorToastScreen(Component message, @javax.annotation.Nullable Screen parent) {
        super(Component.empty());
        this.message = message;
        this.parent  = parent;
    }

    /**
     * 显示一条报错的统一入口 —— <b>本项目所有报错都应该走这里</b>，不要用
     * {@code player.displayClientMessage()} 发聊天栏。
     *
     * <p>当前界面实现了 {@link ErrorToastSupport} 就画在它自己身上（状态不丢失）；
     * 否则开一个 {@link ErrorToastScreen} 顶上去，关闭后回到原界面（或游戏）。</p>
     */
    public static void show(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ErrorToastSupport toastScreen) {
            toastScreen.setErrorToast(message);
        } else {
            mc.setScreen(new ErrorToastScreen(message, mc.screen));
        }
    }

    /**
     * 强制独占地显示报错 —— <b>始终替换当前界面</b>，关闭后回到游戏。
     *
     * <p>{@link #show} 会在实现了 {@link ErrorToastSupport} 的界面上「挂一条横幅」，
     * 原界面继续开着。那对「操作失败但界面本身没问题」是对的，但对
     * <b>拦截类</b>报错完全错了 —— 拦截的含义就是「你不该待在这个界面里」，
     * 挂横幅的结果是玩家关掉横幅又回到了本该被拦住的界面。</p>
     *
     * <p>典型场景：右键时蓝图数据还没下载完，早期守卫判不了、界面照常打开，
     * 数据到达后由 tick 里的迟到守卫补判 —— 这时必须把界面一起关掉。</p>
     */
    public static void showExclusive(Component message) {
        Minecraft.getInstance().setScreen(new ErrorToastScreen(message, null));
    }

    @Override
    public void setErrorToast(Component message) {
        this.message = message;
    }

    @Override
    protected void init() {
        // 无控件：整屏就是一张横幅
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // ErrorToast.render() 自带全屏遮罩，这里不再叠加
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ErrorToast.render(graphics, this.font, this.width, this.height, message);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) onClose();   // ESC
        return true;                      // 吃掉所有按键
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
