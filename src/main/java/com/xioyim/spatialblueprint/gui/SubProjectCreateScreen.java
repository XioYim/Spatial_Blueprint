package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.ClientProjectEntriesCache;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_CreateSubProjectMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 「创建子工程蓝图(序号:x)」：范围页 ✚ → 基础检测 → 检测报告确认后进来（e27 重做），
 * 只填名字 / 构建耗时 / 简单备注 / 详细备注，其余全部自动。
 *
 * <p>序号与线框颜色自动分配：序号 = 条目缓存里的「最大序号 +1」，颜色 = 列表同款调色板
 * （{@link ProjectData#paletteColor}），标题的「(序号:x)」段用这个颜色显示。耗时按报告
 * 扫描到的非空气方块数 {@link BlueprintConfig#getAutoDuration} 自动匹配填入，可改；
 * 清空 = 服务端按实际扫描自动分档。[创建] 发 {@link C2S_CreateSubProjectMessage} 后回范围页，
 * [取消] 直接回范围页（两条路径行为一致，不依赖主手 / 副手）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class SubProjectCreateScreen extends Screen {

    private final Screen parent;
    private final Player player;
    private final String projectId;

    /** 上次同步到的条目版本号；变化即条目数据到了，需要重算序号 / 颜色。 */
    private int entriesVersion = -1;
    /** 自动分配的子工程序号；0 = 条目还没到（创建按钮置灰）。 */
    private int assignedIndex = 0;
    /** 自动分配的线框颜色（列表同款调色板，ARGB）。 */
    private int assignedColor = 0xFFFFFF;

    private int titleY = 0;
    private EditBox nameBox;
    private EditBox timeBox;
    private EditBox noteShortBox;
    private EditBox noteLongBox;
    private Button createBtn = null;

    public SubProjectCreateScreen(Screen parent, Player player, String projectId) {
        super(Component.translatable("spatial_blueprint.gui.subproject.title"));
        this.parent = parent;
        this.player = player;
        this.projectId = projectId == null ? "" : projectId;
        // 一进来就拉条目清单：自动序号与分配色都靠它
        if (!this.projectId.isEmpty()) {
            ClientProjectEntriesCache.requestFor(this.projectId);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        super.init();
        int cx = this.width  / 2;
        int cy = this.height / 2;
        int rowW = 200;
        int inputH = 20;
        int gap = 6;
        int btnH = 20;
        // 布局：标题 + [名字/耗时] + [简单备注] + [详细备注] + [创建][取消]
        int totalH = inputH * 3 + 4 * 2 + gap + btnH;
        int startY = cy - totalH / 2;
        titleY = Math.max(4, startY - 20);

        refreshAssignedEntry();

        // ── 名字 / 构建耗时（同一行）────────────────────────────────────
        int timeW = 40;
        int nameW = rowW - timeW - 4 - 6;   // 6px 留给 "s" 标签
        nameBox = new EditBox(this.font, cx - rowW / 2, startY, nameW, inputH,
                Component.translatable("spatial_blueprint.gui.point_selection.name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("spatial_blueprint.gui.point_selection.name_hint"));
        nameBox.setValue(Component.translatable("spatial_blueprint.gui.point_selection.default_name").getString());
        nameBox.setFocused(true);
        nameBox.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.point_selection.tooltip.name_required")));
        addRenderableWidget(nameBox);

        timeBox = new EditBox(this.font, cx - rowW / 2 + nameW + 4, startY, timeW, inputH,
                Component.literal("s"));
        timeBox.setMaxLength(4);
        timeBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        timeBox.setHint(Component.translatable("spatial_blueprint.gui.subproject.duration.auto_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        // 自动匹配填入：报告扫描到的非空气方块数 → 自动分档；没扫到就留空（服务端按实际扫描自动）
        int scanned = S2C_IllegalBlocksListMessage.clientTotalBlocks;
        if (scanned >= 0) {
            int n = scanned;
            int autoDur;
            if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER1_MAX_BLOCKS)) autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER1_SECONDS);
            else if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER2_MAX_BLOCKS)) autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER2_SECONDS);
            else if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER3_MAX_BLOCKS)) autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER3_SECONDS);
            else if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER4_MAX_BLOCKS)) autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER4_SECONDS);
            else if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER5_MAX_BLOCKS)) autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER5_SECONDS);
            else if (n < ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER6_MAX_BLOCKS)) autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER6_SECONDS);
            else autoDur = ConfigAccess.get(BlueprintConfig.BUILD_TIME_TIER7_SECONDS);
            timeBox.setValue(String.valueOf(autoDur));
        }
        timeBox.setTooltip(Tooltip.create(Component.translatable(
                "spatial_blueprint.gui.point_selection.tooltip.duration",
                ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MIN))));
        addRenderableWidget(timeBox);

        // ── 简单备注（15 字，列表行）/ 详细备注（200 字，名称 tooltip）──────
        noteShortBox = new EditBox(this.font, cx - rowW / 2, startY + inputH + 4, rowW, inputH,
                Component.translatable("spatial_blueprint.gui.subproject.note_short_hint"));
        noteShortBox.setMaxLength(15);
        noteShortBox.setHint(Component.translatable("spatial_blueprint.gui.subproject.note_short_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        noteShortBox.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.subproject.tooltip.note_short")));
        addRenderableWidget(noteShortBox);

        noteLongBox = new EditBox(this.font, cx - rowW / 2, startY + (inputH + 4) * 2, rowW, inputH,
                Component.translatable("spatial_blueprint.gui.subproject.note_long_hint"));
        noteLongBox.setMaxLength(200);
        noteLongBox.setHint(Component.translatable("spatial_blueprint.gui.subproject.note_long_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        noteLongBox.setTooltip(Tooltip.create(
                Component.translatable("spatial_blueprint.gui.subproject.tooltip.note_long")));
        addRenderableWidget(noteLongBox);

        // ── [创建] [取消] ─────────────────────────────────────────────
        int btnY = startY + (inputH + 4) * 2 + inputH + gap;
        int btnW = (rowW - gap) / 2;
        createBtn = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.subproject.btn.create"),
                        b -> create())
                .bounds(cx - rowW / 2, btnY, btnW, btnH)
                .build());
        createBtn.active = assignedIndex > 0;

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.subproject.btn.cancel"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(cx - rowW / 2 + btnW + gap, btnY, btnW, btnH)
                .build());
    }

    /** 从条目缓存取「最大序号 +1」与调色板颜色；数据没到 / 不是本项目时序号 0（创建按钮置灰）。 */
    private void refreshAssignedEntry() {
        S2C_ProjectEntriesMessage entries = ClientProjectEntriesCache.getFor(projectId);
        if (entries == null) {
            assignedIndex = 0;
            assignedColor = 0xFFFFFF;
            return;
        }
        int maxIndex = 0;
        for (S2C_ProjectEntriesMessage.Entry e : entries.entries) {
            maxIndex = Math.max(maxIndex, e.index());
        }
        assignedIndex = maxIndex + 1;
        assignedColor = ProjectData.paletteColor(entries.entries.size());
    }

    /** [创建]：把表单值发出去（序号 / 颜色 / 落盘都由服务端按项目现状自己算）。 */
    private void create() {
        if (assignedIndex <= 0) return;
        int duration;
        try {
            duration = Integer.parseInt(timeBox.getValue().trim());
        } catch (NumberFormatException e) {
            duration = 0;   // 空 = 交给服务端按实际扫描自动分档
        }
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_CreateSubProjectMessage(
                nameBox.getValue().trim(), duration, assignedIndex, projectId,
                noteShortBox.getValue().trim(), noteLongBox.getValue().trim()));
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // 条目数据到达 / 刷新：重算序号与颜色（控件不重建，玩家已填的字不丢）
        int v = ClientProjectEntriesCache.version();
        if (v != entriesVersion) {
            entriesVersion = v;
            refreshAssignedEntry();
        }
        if (createBtn != null) {
            createBtn.active = assignedIndex > 0;
            if (assignedIndex > 0) {
                createBtn.setTooltip(null);
            } else {
                createBtn.setTooltip(Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.subproject.loading")
                                .withStyle(ChatFormatting.RED)));
            }
        }

        renderBackground(g);

        // 标题：创建子工程蓝图(序号:x) —— 序号段用分配色（与项目列表序号列同色）
        MutableComponent title = Component.translatable("spatial_blueprint.gui.subproject.title");
        title.append(Component.translatable("spatial_blueprint.gui.subproject.title_suffix",
                        assignedIndex > 0 ? String.valueOf(assignedIndex) : "-")
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(assignedColor & 0xFFFFFF))));
        g.drawCenteredString(this.font, title, this.width / 2, titleY, 0xFFFFAA);

        super.render(g, mx, my, pt);
        // 耗时框右侧的 "s" 标签
        if (timeBox != null) {
            g.drawString(this.font, "s", timeBox.getX() + timeBox.getWidth() + 2,
                    timeBox.getY() + (20 - font.lineHeight) / 2 + 1, 0xAAAAAA, false);
        }
    }
}
