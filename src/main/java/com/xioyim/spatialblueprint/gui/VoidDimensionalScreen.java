package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_VoidRecaptureMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidResetMessage;
import com.xioyim.spatialblueprint.network.C2S_VoidTeleportMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

@OnlyIn(Dist.CLIENT)
public class VoidDimensionalScreen extends Screen implements ErrorToastSupport {

    private final ItemStack stack;
    private boolean showResetConfirm = false;
    /**
     * 打开界面时是否已发过胶水扫描请求（init 会因窗口缩放重跑，用布尔挡住重复发）。
     * 这次扫描是静默的，只为让「查看范围」点击时手上有数据；提醒只在点击时弹。
     */
    private boolean glueScanRequested = false;

    @Nullable private Component errorToast;

    /** [Tp] 按钮；每次 init() 重建，tick 里按权限 / 冷却刷新置灰与 tooltip。重置确认面板下为 null。 */
    @Nullable private Button tpBtn;
    /** 上一帧是否处于冷却，用来只在冷却期间及其结束那一帧刷 tooltip，避免每帧重建。 */
    private boolean tpCooling = false;

    // ── 固定 Y 坐标 & 尺寸 ──────────────────────────────────────────────────
    private static final int BTN_H   = 18;
    private static final int BTN_GAP = 4;
    private static final int BTN_MIN_W = 50;
    /** 整行压缩时单个按钮的宽度下限；比这更窄就只剩标签在里面滚动了。 */
    private static final int BTN_SHRINK_MIN_W = 28;
    private static final int ROW_OFFSET_FROM_BOTTOM = 63;
    /** 重置确认面板的左右内边距。 */
    private static final int CONFIRM_PAD = 8;
    /** 警告文本的折行宽度上限；再宽读起来就要来回扫视了。 */
    private static final int CONFIRM_TEXT_MAX_W = 220;

    public VoidDimensionalScreen(ItemStack stack) {
        super(Component.empty());
        this.stack = stack;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 按当前权限 / 坐标 / 冷却刷新 [Tp] 的置灰与 tooltip。
     *
     * <p>优先级：无权限 → 「你没有传送权限」；冷却中 → 「冷却中：N 秒」（每秒变）；否则正常提示。
     * 与 {@code EngineeringBlueprintScreen.refreshTpButton} 同一口径。</p>
     */
    private void refreshTpButton() {
        if (tpBtn == null) return;
        boolean hasCoords = VoidDimensionalBlueprintItem.getCaptureMin(stack) != null
                && VoidDimensionalBlueprintItem.getCaptureMax(stack) != null;
        boolean canTp   = com.xioyim.spatialblueprint.client.ClientTeleportPermCache.canTeleport();
        boolean cooling = com.xioyim.spatialblueprint.client.ClientTeleportPermCache.cooldownRemainingMs() > 0L;
        Component tip = !canTp
                ? Component.translatable("spatial_blueprint.gui.void_dimensional.btn.tp.tip.no_perm")
                : cooling
                    ? com.xioyim.spatialblueprint.client.ClientTeleportPermCache.cooldownTooltip()
                    : Component.translatable("spatial_blueprint.gui.void_dimensional.btn.tp.tip");
        tpBtn.setTooltip(Tooltip.create(tip));
        tpBtn.active = canTp && hasCoords && !cooling;
        tpCooling = cooling;
    }

    @Override
    public void tick() {
        super.tick();
        // [Tp] 冷却倒计时：冷却期间每 tick 刷剩余秒数，结束那一帧再刷一次把按钮点亮
        if (tpCooling || com.xioyim.spatialblueprint.client.ClientTeleportPermCache.cooldownRemainingMs() > 0L) {
            refreshTpButton();
        }
    }

    @Override
    protected void init() {
        super.init();

        // 打开界面即静默请求一次扫描，让「查看范围」点击时手上已有跨界胶水数据；
        // 窗口缩放会重跑 init，用布尔挡一次。不调 setVoidPreview、不关界面、不弹横幅。
        if (!glueScanRequested) {
            glueScanRequested = true;
            BlockPos min = VoidDimensionalBlueprintItem.getCaptureMin(stack);
            BlockPos max = VoidDimensionalBlueprintItem.getCaptureMax(stack);
            if (min != null && max != null) {
                BlueprintNetwork.CHANNEL.sendToServer(
                        new com.xioyim.spatialblueprint.network.C2S_RequestIllegalBlocksMessage(min, max));
            }
        }

        int btnY = this.height - ROW_OFFSET_FROM_BOTTOM;

        tpBtn = null;   // 重置确认面板下没有 [Tp]；不清会让 tick 去刷一个已被 clearWidgets 移走的按钮
        if (showResetConfirm) {
            initResetConfirm();
            return;
        }

        Component tpLabel     = Component.translatable("spatial_blueprint.gui.void_dimensional.btn.tp");
        Component viewLabel   = Component.translatable("spatial_blueprint.gui.void_dimensional.view_range");
        Component storeLabel  = Component.translatable("spatial_blueprint.gui.void_dimensional.store");
        Component resetLabel  = Component.translatable("spatial_blueprint.gui.void_dimensional.reset");
        Component cancelLabel = Component.translatable("spatial_blueprint.gui.void_dimensional.cancel");

        int wTp = Math.max(BTN_MIN_W, this.font.width(tpLabel)    + 12);
        int w1  = Math.max(BTN_MIN_W, this.font.width(viewLabel)   + 12);
        int w2  = Math.max(BTN_MIN_W, this.font.width(storeLabel)  + 12);
        int w3  = Math.max(BTN_MIN_W, this.font.width(resetLabel)  + 12);
        int w4  = Math.max(BTN_MIN_W, this.font.width(cancelLabel) + 12);

        int totalW = wTp + w1 + w2 + w3 + w4 + 4 * BTN_GAP;
        // 整行顶出屏幕时按比例压缩：英文标签（Re-store Structure 等）
        // 比中文长出一大截，再叠上高 GUI 缩放，五个按钮就会从屏幕两边溢出去。
        int maxRowW = this.width - 8;
        if (totalW > maxRowW) {
            int rawSum = wTp + w1 + w2 + w3 + w4;
            int budget = Math.max(5 * BTN_SHRINK_MIN_W, maxRowW - 4 * BTN_GAP);
            wTp = Math.max(BTN_SHRINK_MIN_W, wTp * budget / rawSum);
            w1  = Math.max(BTN_SHRINK_MIN_W, w1  * budget / rawSum);
            w2  = Math.max(BTN_SHRINK_MIN_W, w2  * budget / rawSum);
            w3  = Math.max(BTN_SHRINK_MIN_W, w3  * budget / rawSum);
            w4  = Math.max(BTN_SHRINK_MIN_W, w4  * budget / rawSum);
            totalW = wTp + w1 + w2 + w3 + w4 + 4 * BTN_GAP;
        }
        int cx = (this.width - totalW) / 2;

        BlockPos min = VoidDimensionalBlueprintItem.getCaptureMin(stack);
        BlockPos max = VoidDimensionalBlueprintItem.getCaptureMax(stack);
        boolean hasCoords = min != null && max != null;
        // 区块加载闸门：与空白蓝图选点界面同口径 —— 选区覆盖的区块里有未加载的就不允许
        // 重新储存，否则服务端会为这些坐标同步加载/生成区块。
        boolean chunksLoaded = hasCoords && !PointSelectionScreen.hasUnloadedChunksIn(
                net.minecraft.client.Minecraft.getInstance().level, min, max);

        // 传送权限来自服务端推送（OP / 创造模式 / 被 /sb tpperm 授予），客户端算不出来；
        // 点下后 3 秒冷却（ClientTeleportPermCache，与工程蓝图 [Tp] 共用），置灰 + tooltip「冷却中」
        tpBtn = addRenderableWidget(Button.builder(tpLabel,
                b -> {
                    com.xioyim.spatialblueprint.client.ClientTeleportPermCache.markTeleportRequested();
                    BlueprintNetwork.CHANNEL.sendToServer(new C2S_VoidTeleportMessage());
                    refreshTpButton();
                })
                .bounds(cx, btnY, wTp, BTN_H)
                .build());
        refreshTpButton();
        cx += wTp + BTN_GAP;

        Button viewBtn = addRenderableWidget(Button.builder(viewLabel, b -> onViewRange())
                .bounds(cx, btnY, w1, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.view_range.tip")))
                .build());
        viewBtn.active = hasCoords;
        cx += w1 + BTN_GAP;

        Button storeBtn = addRenderableWidget(Button.builder(storeLabel, b -> onStore())
                .bounds(cx, btnY, w2, BTN_H)
                .tooltip(Tooltip.create(Component.translatable(hasCoords && !chunksLoaded
                        ? "spatial_blueprint.gui.point_selection.tooltip.unloaded_chunks"
                        : "spatial_blueprint.gui.void_dimensional.store.tip")))
                .build());
        storeBtn.active = hasCoords && chunksLoaded;
        cx += w2 + BTN_GAP;

        addRenderableWidget(Button.builder(resetLabel, b -> onReset())
                .bounds(cx, btnY, w3, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.reset.tip")))
                .build());
        cx += w3 + BTN_GAP;

        addRenderableWidget(Button.builder(cancelLabel, b -> onCancel())
                .bounds(cx, btnY, w4, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.void_dimensional.cancel.tip")))
                .build());
    }

    /**
     * 重置确认弹窗的几何：{@code {panelX, panelY, panelW, panelH}}。
     *
     * <p>面板尺寸由警告文本反推 —— 这段文字在中英文下长度差了近一倍，
     * 写死宽度必然有一边会顶出面板。绘制与按钮定位共用这一份计算，避免两边算出不同的框。</p>
     */
    private int[] resetConfirmBounds() {
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = resetWarningLines();
        int textW = 0;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            textW = Math.max(textW, this.font.width(line));
        }
        textW = Math.max(textW, this.font.width(
                Component.translatable("spatial_blueprint.gui.void_dimensional.reset_title")));

        int panelW = Math.min(this.width - 20, Math.max(180, textW + 2 * CONFIRM_PAD));
        int panelH = 26 + lines.size() * this.font.lineHeight + 12 + BTN_H + 5;
        return new int[]{(this.width - panelW) / 2, (this.height - panelH) / 2 - 10, panelW, panelH};
    }

    /** 警告文本按可用宽度折行；返回的行数直接决定面板高度。 */
    private java.util.List<net.minecraft.util.FormattedCharSequence> resetWarningLines() {
        return this.font.split(
                Component.translatable("spatial_blueprint.gui.void_dimensional.reset_warning")
                        .copy().withStyle(ChatFormatting.RED),
                Math.min(CONFIRM_TEXT_MAX_W, Math.max(80, this.width - 20 - 2 * CONFIRM_PAD)));
    }

    private void initResetConfirm() {
        Component okLabel     = Component.translatable("spatial_blueprint.gui.library.confirm_delete.ok");
        Component cancelLabel = Component.translatable("spatial_blueprint.gui.library.confirm_delete.cancel");

        int[] p = resetConfirmBounds();
        int okW  = Math.max(BTN_MIN_W, this.font.width(okLabel)     + 12);
        int canW = Math.max(BTN_MIN_W, this.font.width(cancelLabel) + 12);
        // 两个按钮也得塞进面板里：中文「确认/取消」很短，英文长标签配上窄面板就会露出边框
        int avail = p[2] - 2 * CONFIRM_PAD - BTN_GAP;
        if (okW + canW > avail) {
            int half = Math.max(24, avail / 2);
            okW  = half;
            canW = half;
        }
        int confirmY = p[1] + p[3] - 5 - BTN_H;
        int startX   = p[0] + (p[2] - (okW + canW + BTN_GAP)) / 2;

        addRenderableWidget(Button.builder(okLabel, b -> confirmReset())
                .bounds(startX, confirmY, okW, BTN_H).build());
        addRenderableWidget(Button.builder(cancelLabel, b -> cancelResetConfirm())
                .bounds(startX + okW + BTN_GAP, confirmY, canW, BTN_H).build());
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        if (showResetConfirm) {
            renderResetConfirmOverlay(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 透明背景：不绘制暗色遮罩，让世界透过显示
    }

    private void renderResetConfirmOverlay(GuiGraphics graphics) {
        int[] p = resetConfirmBounds();
        int panelX = p[0], panelY = p[1], panelW = p[2], panelH = p[3];

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xCC000000);
        // 边框
        graphics.fill(panelX,           panelY,           panelX + panelW, panelY + 1,       0xFF555555);
        graphics.fill(panelX,           panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF555555);
        graphics.fill(panelX,           panelY,           panelX + 1,       panelY + panelH, 0xFF555555);
        graphics.fill(panelX + panelW - 1, panelY,        panelX + panelW, panelY + panelH, 0xFF555555);

        Component title = Component.translatable("spatial_blueprint.gui.void_dimensional.reset_title");
        int cx = panelX + panelW / 2;
        graphics.drawCenteredString(this.font, title, cx, panelY + 10, 0xFFFFFF);

        int lineY = panelY + 26;
        for (net.minecraft.util.FormattedCharSequence line : resetWarningLines()) {
            graphics.drawString(this.font, line, cx - this.font.width(line) / 2, lineY, 0xFF5555, false);
            lineY += this.font.lineHeight;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) { errorToast = null; return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showResetConfirm) {
                cancelResetConfirm();
            } else {
                onCancel();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 按钮行为 ──────────────────────────────────────────────────────────

    private void onViewRange() {
        BlockPos min = VoidDimensionalBlueprintItem.getCaptureMin(stack);
        BlockPos max = VoidDimensionalBlueprintItem.getCaptureMax(stack);
        boolean crossingGlue = false;
        if (min != null && max != null) {
            BlueprintSelectionRenderer.setVoidPreview(min, max);
            // 请求服务端扫描该范围的危险方块 / 特殊实体，供预览渲染橙色 / 蓝色框
            BlueprintNetwork.CHANNEL.sendToServer(
                    new com.xioyim.spatialblueprint.network.C2S_RequestIllegalBlocksMessage(min, max));
            // 跨界胶水判定用的是打开界面时那次静默扫描的结果（这一发的回包还没到）
            crossingGlue = com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage
                    .hasCrossingBoxEntityIn(min, max);
        }
        this.onClose();
        // 界面已关、玩家正对着世界看范围 —— 横幅没有宿主界面，走独立的 ErrorToastScreen，
        // 点掉之后回到游戏，预览框（跨界胶水是红的）还在
        if (crossingGlue) {
            com.xioyim.spatialblueprint.util.NotifySound.clientError();
            ErrorToastScreen.show(Component.translatable("spatial_blueprint.message.glue_crossing"));
        }
    }

    private void onStore() {
        BlockPos min = VoidDimensionalBlueprintItem.getCaptureMin(stack);
        BlockPos max = VoidDimensionalBlueprintItem.getCaptureMax(stack);
        // 与空白蓝图一致：选区有未加载区块时拒绝，避免服务端为这些坐标加载/生成区块。
        // 按钮在 init() 里已置灰，这里防的是「按钮状态过期 / 改包」之外的正常路径兜底。
        if (PointSelectionScreen.hasUnloadedChunksIn(
                net.minecraft.client.Minecraft.getInstance().level, min, max)) {
            com.xioyim.spatialblueprint.util.NotifySound.clientError();
            setErrorToast(Component.translatable(
                    "spatial_blueprint.gui.point_selection.error.unloaded_chunks"));
            return;
        }
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_VoidRecaptureMessage());
        BlueprintSelectionRenderer.clearVoidPreview();
        this.onClose();
    }

    private void onReset() {
        showResetConfirm = true;
        clearWidgets();
        init();
    }

    private void onCancel() {
        BlueprintSelectionRenderer.clearVoidPreview();
        this.onClose();
    }

    private void confirmReset() {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_VoidResetMessage());
        BlueprintSelectionRenderer.clearVoidPreview();
        this.onClose();
    }

    private void cancelResetConfirm() {
        showResetConfirm = false;
        clearWidgets();
        init();
    }
}
