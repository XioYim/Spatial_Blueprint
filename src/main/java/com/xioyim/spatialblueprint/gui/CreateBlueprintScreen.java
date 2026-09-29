package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.ClientLibraryCache;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 「选择创建类型」界面：从 PointSelectionScreen 跳入，填名称/时长/备注并选择蓝图类型。
 *
 * <p>本页<b>只选类型</b>，不再承载捕获模式 —— 普通 / 净化 / 强制的选择在下一步的
 * 检测报告界面。因此标题也从原来的三种（按模式变色）合并为一个。</p>
 *
 * <p>流程：选点 → <b>本页选类型</b> → 检测报告（选模式）→ 真正捕获。</p>
 */
@OnlyIn(Dist.CLIENT)
public class CreateBlueprintScreen extends Screen {

    private final PointSelectionScreen parent;
    private final Player player;

    /** 上次同步到的蓝图列表版本号；变化即说明槽位数据到了，需要重建按钮。 */
    private int libraryVersion = -1;

    /** 标题基线 Y：跟随实际布局算出，避免与名称框重叠。 */
    private int titleY = 0;

    private EditBox nameBox;
    private EditBox timeBox;
    private EditBox shortNoteBox;
    private EditBox longNoteBox;

    public CreateBlueprintScreen(PointSelectionScreen parent, Player player) {
        super(Component.translatable("spatial_blueprint.gui.create_blueprint.title"));
        this.parent = parent;
        this.player = player;
        // 强制重拉：requestIfNeeded 只在缓存为空时才发请求，玩家上一次捕获之后
        // 缓存里还是旧列表，槽位数会算少。放构造器而不是 init()——
        // init() 会被版本跟随反复调用，放那儿会变成请求死循环。
        ClientLibraryCache.forceRefresh();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        super.init();

        // 槽位判定要用蓝图列表算已用数 —— 玩家可能一次都没打开过蓝图库，
        // 缓存为空时槽位会算成 0、按钮显示可点，点下去才被服务端拒绝。
        // 这里主动拉一次，数据到达后由 render() 的版本跟随重建按钮。
        // 兜底：万一构造器那次请求丢了（掉线重连等），这里补一次
        ClientLibraryCache.requestIfNeeded();

        int cx = this.width  / 2;
        int cy = this.height / 2;
        int btnW = 160, btnH = 20, gap = 6, inputH = 20, inputGap = 4;
        // 四个类型按钮 + 返回按钮 = 5 行按钮
        int totalH = btnH * 5 + gap * 5 + inputH * 3 + inputGap * 3;
        int startY = cy - totalH / 2;
        // 标题放在名称框上方 16px —— 写死的偏移会随布局变化压到输入框上
        titleY = Math.max(4, startY - 16);

        boolean isOp     = player.hasPermissions(2);
        boolean creative = player.isCreative();
        int blocks       = parent.getCachedBlockCount();

        // ── 名称框 + 时长框（同一行）──────────────────────────────────────
        int timeW = 40;
        int nameW = btnW - timeW - 4 - 6;   // 6px 留给 "s" 标签
        nameBox = new EditBox(this.font, cx - btnW / 2, startY, nameW, inputH,
                Component.translatable("spatial_blueprint.gui.point_selection.name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("spatial_blueprint.gui.point_selection.name_hint"));
        String saved = parent.getSavedName();
        nameBox.setValue(saved.isEmpty()
                ? Component.translatable("spatial_blueprint.gui.point_selection.default_name").getString()
                : saved);
        nameBox.setFocused(true);
        nameBox.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.point_selection.tooltip.name_required")));
        addRenderableWidget(nameBox);

        // 时长：只有 OP 能改，其他人看到的是按方块数自动分档的只读值
        timeBox = new EditBox(this.font, cx - btnW / 2 + nameW + 4, startY, timeW, inputH,
                Component.literal("s"));
        timeBox.setMaxLength(4);
        timeBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        timeBox.setEditable(isOp);
        timeBox.setValue(String.valueOf(parent.autoDurationSec()));
        timeBox.setTooltip(Tooltip.create(Component.translatable(isOp
                ? "spatial_blueprint.gui.point_selection.tooltip.duration"
                : "spatial_blueprint.gui.point_selection.tooltip.duration_readonly",
                ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MIN))));
        addRenderableWidget(timeBox);

        // ── 四个类型按钮 ──────────────────────────────────────────────────
        // 模式门槛：空间 / 工程 = 创造专用；OP 一律豁免。
        // 投影与次元不再限制生存模式 —— 创造模式照样能创建。
        int row = 0;
        addTypeButton("create", "spatial_blueprint.gui.create_blueprint.btn.spatial",
                cx, startY + inputH + inputGap + (btnH + gap) * row++, btnW, btnH,
                isOp || creative, blocks, isOp);
        addTypeButton("project", "spatial_blueprint.gui.create_blueprint.btn.projection",
                cx, startY + inputH + inputGap + (btnH + gap) * row++, btnW, btnH,
                true, blocks, isOp);
        addTypeButton("dimensional", "spatial_blueprint.gui.create_blueprint.btn.void",
                cx, startY + inputH + inputGap + (btnH + gap) * row++, btnW, btnH,
                true, blocks, isOp);
        addTypeButton("engineering", "spatial_blueprint.gui.create_blueprint.btn.engineering",
                cx, startY + inputH + inputGap + (btnH + gap) * row++, btnW, btnH,
                isOp || creative, blocks, isOp);

        // ── 两个备注框 ────────────────────────────────────────────────────
        int shortNoteY = startY + inputH + inputGap + (btnH + gap) * row;
        shortNoteBox = new EditBox(this.font, cx - btnW / 2, shortNoteY, btnW, inputH,
                Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.label"));
        shortNoteBox.setMaxLength(15);
        shortNoteBox.setHint(Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        shortNoteBox.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.tooltip")));
        addRenderableWidget(shortNoteBox);

        int longNoteY = shortNoteY + inputH + inputGap;
        longNoteBox = new EditBox(this.font, cx - btnW / 2, longNoteY, btnW, inputH,
                Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.label"));
        longNoteBox.setMaxLength(200);
        longNoteBox.setHint(Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        longNoteBox.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.tooltip")));
        addRenderableWidget(longNoteBox);

        // ── 返回 ──────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.btn.back"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - btnW / 2, longNoteY + inputH + gap, btnW, btnH)
                .tooltip(Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.create_blueprint.tooltip.back")))
                .build());
    }

    /**
     * 建一个类型按钮，并按「游戏模式门槛」与「选区方块数」决定能否点击。
     *
     * @param modeOk          游戏模式是否允许（OP 已豁免）
     *                        （唯一剩下的模式门槛是「空间 / 工程需要创造模式」——
     *                        投影与次元的生存模式限制已取消）
     * @param blocks          当前选区非空气方块数
     * @param isOp            OP 不受体积上限约束
     */
    private void addTypeButton(String category, String labelKey, int cx, int y, int w, int h,
                               boolean modeOk, int blocks, boolean isOp) {
        int limit = S2C_ConfigSyncMessage.maxVolumeFor(category);
        boolean sizeOk = isOp || blocks <= limit;

        // 槽位：客户端先挡一道，服务端仍会重新校验（防改包）
        int slotLimit = S2C_ConfigSyncMessage.slotLimitFor(category);
        int slotUsed  = com.xioyim.spatialblueprint.client.ClientLibraryCache.countOwnSlots(category);
        // slotUsed < 0 = 蓝图列表还没拉到。「不知道」不等于「一个都没占」——
        // 此时放行并显示「?」，最终由服务端判定
        boolean slotKnown = slotUsed >= 0;
        boolean slotOk = isOp || slotLimit <= 0 || !slotKnown || slotUsed < slotLimit;
        String usedStr = slotKnown ? String.valueOf(slotUsed) : "?";

        boolean enabled = modeOk && sizeOk && slotOk;

        Component tip;
        if (!modeOk) {
            tip = Component.translatable("spatial_blueprint.gui.create_blueprint.need_creative")
                    .withStyle(ChatFormatting.RED);
        } else if (!slotOk) {
            tip = Component.translatable("spatial_blueprint.gui.create_blueprint.slot_full",
                            usedStr, slotLimit)
                    .withStyle(ChatFormatting.RED);
        } else if (!sizeOk) {
            tip = Component.translatable("spatial_blueprint.gui.create_blueprint.too_large", blocks, limit)
                    .withStyle(ChatFormatting.RED);
        } else {
            MutableComponent t = Component.translatable(
                            "spatial_blueprint.gui.create_blueprint.size_ok", blocks, limit)
                    .withStyle(ChatFormatting.GRAY);
            if (slotLimit > 0) {
                t.append(Component.literal("\n"))
                 .append(Component.translatable("spatial_blueprint.gui.library.tab.slots",
                                 usedStr, slotLimit)
                         .withStyle(ChatFormatting.GRAY));
            }
            tip = t;
        }

        Button btn = addRenderableWidget(Button.builder(
                        Component.translatable(labelKey),
                        b -> proceedToReport(category))
                .bounds(cx - w / 2, y, w, h)
                .tooltip(Tooltip.create(tip))
                .build());
        btn.active = enabled;
    }

    /** 把本页填好的参数写回父界面，然后进入检测报告。 */
    private void proceedToReport(String category) {
        parent.pendingName      = nameBox.getValue().trim();
        parent.pendingNoteShort = shortNoteBox.getValue().trim();
        parent.pendingNoteLong  = longNoteBox.getValue().trim();
        try {
            parent.pendingDuration = Integer.parseInt(timeBox.getValue().trim());
        } catch (NumberFormatException e) {
            parent.pendingDuration = 0;   // 0 = 交给 getTimeSec() 按方块数自动分档
        }
        Minecraft.getInstance().setScreen(
                new BlueprintReportScreen(parent, player, parent.getCachedBlockCount(), category));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // 蓝图列表到达后重建按钮：槽位是否已满是在 init() 里算好的，不重建就一直停在旧结论
        int lv = ClientLibraryCache.version();
        if (lv != libraryVersion) {
            libraryVersion = lv;
            rebuildWidgets();
        }
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, titleY, 0xFFFFAA);
        super.render(g, mx, my, pt);
        // 时长框右侧的 "s" 标签
        if (timeBox != null) {
            g.drawString(this.font, "s", timeBox.getX() + timeBox.getWidth() + 2,
                    timeBox.getY() + (20 - font.lineHeight) / 2 + 1, 0xAAAAAA, false);
        }
    }
}
