package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.ClientLibraryCache;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestLibraryMessage;
import com.xioyim.spatialblueprint.network.C2S_ApplyBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_ConvertBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_CreateProjectMessage;
import com.xioyim.spatialblueprint.network.C2S_DeleteBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_RenameBlueprintMessage;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintMaterialsMessage;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一蓝图库界面，含两排 Tab（共 6 个）。
 *
 * <p>Row 1：空间蓝图 / 投影蓝图 / 次元蓝图<br>
 * Row 2：工程蓝图 / 投影源文件 / 投影模板</p>
 *
 * <p>默认不选中任何 Tab，内容区显示"先选择一个选项"。
 * 所有写操作均通过 C2S 包路由至服务端。</p>
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintLibraryScreen extends Screen implements ErrorToastSupport {

    // ── 布局常量 ──────────────────────────────────────────────────────────
    static final int PANEL_W   = 364;
    // PANEL_H 是动态的，见 getPanelH()
    private static final int ROW_H     = 18;
    private static final int PAD       = 4;

    // ── 列表行的定宽列 ────────────────────────────────────────────────────
    // 名字列原来是按「日期宽 - 备注宽」反推的，于是每一行的备注都落在不同的 X 上，
    // 整列看起来是散的。改成两列都定宽 + 定点，备注在所有行对齐。
    private static final int NAME_COL_W = 132;
    private static final int NOTE_COL_W = 108;
    /** 名字 / 备注的最大字符数；超出截断为 …（像素宽度再由 {@link #truncate} 兜一次底）。 */
    private static final int MAX_NAME_CHARS = 16;
    private static final int MAX_NOTE_CHARS = 16;

    // Row 1（创造专属）：空间蓝图 / 工程蓝图 / 项目蓝图
    private static final String[] ROW1_IDS  = { "create", "engineering", "projectset" };
    private static final String[] ROW1_KEYS = {
        "spatial_blueprint.gui.library.tab.create",
        "spatial_blueprint.gui.library.tab.engineering",
        "spatial_blueprint.gui.library.tab.projectset"
    };
    // Row 2（所有玩家）：投影蓝图 / 次元蓝图 / 投影模板
    private static final String[] ROW2_IDS  = { "project", "dimensional", "template" };
    private static final String[] ROW2_KEYS = {
        "spatial_blueprint.gui.library.tab.project",
        "spatial_blueprint.gui.library.tab.dimensional",
        "spatial_blueprint.gui.proj_template.tab.template"
    };

    // ── 状态 ──────────────────────────────────────────────────────────────
    private final Screen parent;
    /** null = 未选中任何 Tab，显示"先选择一个选项"。 */
    @Nullable private String activeTab = null;

    private List<BlueprintStorage.BlueprintSummary> summaries = new ArrayList<>();
    private List<BlueprintStorage.BlueprintSummary> filtered  = new ArrayList<>();

    // I1：逐字符截断（truncate）的按文本缓存 —— render 每帧逐行调，循环里全是 font.width；
    // 文本只在数据/过滤变化时变，rebuildFiltered 里清掉。单次 font.width 不缓存（O(1)，可忽略）。
    private final java.util.Map<String, String> truncateCache = new java.util.HashMap<>();

    private int    scrollRow    = 0;
    private int    maxScrollRow = 0;
    private String searchQuery  = "";

    /**
     * false = 私人模式（仅自己的蓝图，默认）；true = 服务器模式（全部蓝图，仅 OP 可切换）。
     * 非 static，每次打开界面重置为 false。
     */
    private boolean serverMode = false;
    private Component displayTitle = Component.translatable("spatial_blueprint.gui.library.title.private");

    // Title segment pixel bounds (set each frame in render, used for hover/click)
    private int titlePrefixX, titlePrefixW, titleMainX, titleMainW, titleY;

    @Nullable private EditBox searchBox = null;
    private int panelX, panelY;

    // ── 弹窗状态 ──────────────────────────────────────────────────────────
    @Nullable private BlueprintStorage.BlueprintSummary confirmDelete        = null;
    @Nullable private BlueprintStorage.BlueprintSummary settingsTarget       = null;
    @Nullable private EditBox                           settingsNameBox      = null;
    @Nullable private EditBox                           settingsDurationBox  = null;
    @Nullable private EditBox                           settingsNoteShortBox = null;
    @Nullable private EditBox                           settingsNoteLongBox  = null;
    /** 「设定蓝图」弹窗的确认按钮 —— 时长输入越界/非法时置灰。 */
    @Nullable private Button settingsConfirmButton = null;

    // 「创建项目蓝图」覆盖层（projectset tab 左下角 ✚ 打开；与设定蓝图 / 删除确认互斥）
    private boolean creatingProject = false;
    @Nullable private EditBox createProjectNameBox      = null;
    @Nullable private EditBox createProjectNoteShortBox = null;
    @Nullable private EditBox createProjectNoteLongBox  = null;
    /** 名字为空时置灰。 */
    @Nullable private Button  createProjectConfirmButton = null;

    // ── 选中状态 ──────────────────────────────────────────────────────────
    @Nullable private BlueprintStorage.BlueprintSummary selectedSummary = null;

    /**
     * tab 提示 + 该分类的槽位占用。
     *
     * <p>上限为 0（不限）的分类不追加这一行 —— 显示「3/0」只会让人困惑。
     * 已用 ≥ 上限时标红。</p>
     */
    private Component buildTabTooltip(String tabId, String tipKey) {
        Component base = Component.translatable(tipKey);
        int limit = com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage.slotLimitFor(tabId);
        if (limit <= 0) return base;
        int used = ClientLibraryCache.countOwnSlots(tabId);
        // used < 0 = 列表还没拉到，显示「?」而不是一个会误导人的 0
        String usedStr = used >= 0 ? String.valueOf(used) : "?";
        return base.copy()
                .append(Component.literal("\n"))
                .append(Component.translatable("spatial_blueprint.gui.library.tab.slots", usedStr, limit)
                        .withStyle(used >= 0 && used >= limit ? ChatFormatting.RED : ChatFormatting.GRAY));
    }

    /**
     * 当前选中的蓝图是否属于本玩家。
     *
     * <p>无主蓝图（{@code ownerUuid} 为空的旧数据）对任何人都返回 {@code false} ——
     * 次元蓝图与次元袋一一绑定，认错主人会让材料归属彻底错乱。</p>
     */
    private boolean isOwnerOfSelection() {
        if (selectedSummary == null) return false;
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        String owner = selectedSummary.ownerUuid();
        return owner != null && !owner.isEmpty() && owner.equals(p.getUUID().toString());
    }

    /**
     * 私人列表的「这是我的吗」——<b>只管看不看得见，不管能不能改</b>。
     *
     * <p>主判据是 {@code ownerUuid}：玩家改名后 {@code creatorName} 就对不上了，
     * 自己的蓝图会整批从私人列表里消失（文件还在，OP 切服务器模式看得到）。
     * UUID 不变，正是 {@link com.xioyim.spatialblueprint.util.BlueprintData#ownerUuid}
     * 当初引入的理由，只是列表过滤这一处当时漏改了。</p>
     *
     * <p><b>无主蓝图（{@code ownerUuid} 为空的旧数据）回退按名字认</b> —— 归属制度之前
     * 捕获的那批本来就没有 UUID，一律判否会让它们从每个人的私人列表里集体消失，
     * 比改名对不上更糟。</p>
     *
     * <p>与 {@link #isOwnerOfSelection()} 语义<b>不同，别混用</b>：那边是操作权限
     * （删除 / 取出），对无主蓝图一律返回 false，与服务端
     * {@code BlueprintValidator.canManageBlueprint} 对齐。这里放宽只影响可见性 ——
     * 就算无主蓝图因为重名被显示出来，真去删它照样会被服务端挡回。</p>
     */
    private boolean isMineForListing(BlueprintStorage.BlueprintSummary s) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        String owner = s.ownerUuid();
        if (owner != null && !owner.isEmpty()) {
            return owner.equals(p.getUUID().toString());
        }
        return p.getGameProfile().getName().equals(s.creatorName());
    }

    @Nullable private Component errorToast;

    // ── 悬浮状态（每帧更新）────────────────────────────────────────────────
    private int     hoveredNameRow  = -1;
    private int     hoveredStatsRow = -1;
    @Nullable private String hoveredNoteTip = null;

    // ── 构造 ──────────────────────────────────────────────────────────────

    public BlueprintLibraryScreen(Screen parent) {
        super(Component.translatable("spatial_blueprint.gui.library.title.private"));
        this.parent = parent;
    }

    // ── Screen 生命周期 ───────────────────────────────────────────────────

    /** 上次同步到的列表版本号；与缓存版本不一致时用新数据刷新。 */
    private int libraryVersion = -1;

    /**
     * 用服务端推来的新列表刷新界面。
     *
     * <p>{@link #selectedSummary} 持有的是旧的 record 实例，列表刷新后它的字段（尤其
     * {@code placed}）已经过期 —— 必须按 id 重新绑定到新对象，否则 ✔ 按钮的置灰判断
     * 仍基于旧值。找不到（蓝图被删）时清空选中。</p>
     *
     * <p>有弹层打开时只更新数据不重建控件，避免打断玩家正在进行的重命名 / 改时长输入。</p>
     */
    private void refreshFromLibraryCache() {
        List<BlueprintStorage.BlueprintSummary> cached = ClientLibraryCache.get();
        if (cached == null) return;
        summaries = cached;
        if (selectedSummary != null) {
            String selId = selectedSummary.id();
            selectedSummary = summaries.stream()
                    .filter(s -> s.id().equals(selId))
                    .findFirst().orElse(null);
        }
        rebuildFiltered();
        if (!isOverlayActive()) rebuildWidgets();
    }

    @Override
    protected void init() {
        super.init();

        List<BlueprintStorage.BlueprintSummary> cached = ClientLibraryCache.get();
        summaries = cached != null ? cached : new ArrayList<>();
        if (cached == null) ClientLibraryCache.requestIfNeeded();
        libraryVersion = ClientLibraryCache.version();
        rebuildFiltered();

        int cx = this.width  / 2;
        int cy = this.height / 2;
        panelX = cx - PANEL_W / 2;
        panelY = cy - getPanelH() / 2;

        // 生存模式下空间蓝图（create）不可用，若已选中则重置；
        // 工程蓝图 / 项目蓝图生存模式仍可进入
        if (!isCreative() && "create".equals(activeTab)) {
            activeTab = null;
        }

        if (settingsTarget != null) {
            int[] panel = settingsOverlayPanelBounds();
            int padX = panel[0] + 12;
            int row1Y = panel[1] + 28;
            int nameW = 150;
            int durX = panel[0] + 174;
            int durW = panel[2] - (durX - panel[0]) - 12;
            int fullW = panel[2] - 24;

            settingsNameBox = new EditBox(font, padX, row1Y, nameW, 20,
                    Component.translatable("spatial_blueprint.gui.library.settings.name"));
            settingsNameBox.setValue(settingsTarget.name());
            settingsNameBox.setMaxLength(64);
            addRenderableWidget(settingsNameBox);

            settingsDurationBox = new EditBox(font, durX, row1Y, durW, 20,
                    Component.translatable("spatial_blueprint.gui.library.settings.duration"));
            int cur = settingsTarget.animDuration();
            settingsDurationBox.setValue(cur > 0 ? String.valueOf(cur) : "");
            settingsDurationBox.setMaxLength(4);
            settingsDurationBox.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
            settingsDurationBox.setEditable(isOp());
            settingsDurationBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.library.settings.duration.op_only",
                            ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MIN),
                            ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MAX))));
            addRenderableWidget(settingsDurationBox);

            settingsNoteShortBox = new EditBox(font, padX, row1Y + 26, fullW, 20,
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.label"));
            settingsNoteShortBox.setMaxLength(15);
            settingsNoteShortBox.setValue(settingsTarget.noteShort() != null ? settingsTarget.noteShort() : "");
            settingsNoteShortBox.setHint(Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            settingsNoteShortBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.tooltip")));
            addRenderableWidget(settingsNoteShortBox);

            settingsNoteLongBox = new EditBox(font, padX, row1Y + 52, fullW, 20,
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.label"));
            settingsNoteLongBox.setMaxLength(200);
            settingsNoteLongBox.setValue(settingsTarget.noteLong() != null ? settingsTarget.noteLong() : "");
            settingsNoteLongBox.setHint(Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            settingsNoteLongBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.tooltip")));
            addRenderableWidget(settingsNoteLongBox);

            setFocused(settingsNameBox);

            addSettingsButtons();
            // 时长输入实时校验：越界/非数字时红字并禁用「确认」
            settingsDurationBox.setResponder(s -> refreshDurationValidity());
            refreshDurationValidity();
            return;
        }

        if (creatingProject) {
            // 版式照「设定蓝图」：同一块 300×140 面板，去掉时长框，名字占满一行
            int[] panel = settingsOverlayPanelBounds();
            int padX = panel[0] + 12;
            int row1Y = panel[1] + 28;
            int fullW = panel[2] - 24;

            createProjectNameBox = new EditBox(font, padX, row1Y, fullW, 20,
                    Component.translatable("spatial_blueprint.gui.library.settings.name"));
            createProjectNameBox.setMaxLength(64);
            createProjectNameBox.setHint(Component.translatable("spatial_blueprint.gui.point_selection.name_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            // 默认名走 lang（中文「新项目蓝图」/ 英文 New Project Blueprint），玩家可改
            createProjectNameBox.setValue(
                    Component.translatable("spatial_blueprint.gui.library.create_project.default_name").getString());
            addRenderableWidget(createProjectNameBox);

            createProjectNoteShortBox = new EditBox(font, padX, row1Y + 26, fullW, 20,
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.label"));
            createProjectNoteShortBox.setMaxLength(15);
            createProjectNoteShortBox.setHint(Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            createProjectNoteShortBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_short.tooltip")));
            addRenderableWidget(createProjectNoteShortBox);

            createProjectNoteLongBox = new EditBox(font, padX, row1Y + 52, fullW, 20,
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.label"));
            createProjectNoteLongBox.setMaxLength(200);
            createProjectNoteLongBox.setHint(Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            createProjectNoteLongBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.create_blueprint.note_long.tooltip")));
            addRenderableWidget(createProjectNoteLongBox);

            setFocused(createProjectNameBox);

            addCreateProjectButtons();
            createProjectNameBox.setResponder(s -> refreshCreateProjectValidity());
            refreshCreateProjectValidity();
            return;
        }

        if (confirmDelete != null) {
            addOverlayButtons(deleteConfirmBtnBounds(), deleteCancelBtnBounds(),
                    this::confirmDelete, this::cancelDelete);
            return;
        }

        // ── Row 1 tabs（所有模式显示；空间蓝图仅创造可点）────────────────
        {
            int n1 = ROW1_IDS.length;
            int tabW1 = (PANEL_W - 4 * 2 - 2 * (n1 - 1)) / n1;
            String[] row1Tips = {
                "spatial_blueprint.gui.library.tab.create.tip",
                "spatial_blueprint.gui.library.tab.engineering.tip",
                "spatial_blueprint.gui.library.tab.projectset.tip"
            };
            for (int i = 0; i < n1; i++) {
                final String tab = ROW1_IDS[i];
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable(ROW1_KEYS[i]),
                        b -> { switchTab(tab); })
                    .bounds(panelX + 4 + i * (tabW1 + 2), panelY + 18, tabW1, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            buildTabTooltip(tab, row1Tips[i])))
                    .build());
                // 空间蓝图（create）生存模式置灰不可点；其余按选中态切换
                btn.active = "create".equals(tab) && !isCreative()
                        ? false
                        : !tab.equals(activeTab);
            }
        }

        // ── Row 2 tabs（所有玩家）────────────────────────────────────────
        {
            int n2    = ROW2_IDS.length;
            int tabW2 = (PANEL_W - 4 * 2 - 2 * (n2 - 1)) / n2;
            int row2Y = panelY + 36;
            String[] row2Tips = {
                "spatial_blueprint.gui.library.tab.project.tip",
                "spatial_blueprint.gui.library.tab.dimensional.tip",
                "spatial_blueprint.gui.library.tab.template.tip"
            };
            for (int i = 0; i < n2; i++) {
                final String tab = ROW2_IDS[i];
                Button btn = addRenderableWidget(Button.builder(
                        Component.translatable(ROW2_KEYS[i]),
                        b -> { switchTab(tab); })
                    .bounds(panelX + 4 + i * (tabW2 + 2), row2Y, tabW2, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            buildTabTooltip(tab, row2Tips[i])))
                    .build());
                btn.active = !tab.equals(activeTab);
            }
        }

        // ── 底部按钮行（18px 迷你方钮，右→左：[❌ 关闭] [❖ 上传]）────────────
        // 「刷新」按钮已移除 —— 改为每次切换 Tab 时自动拉取最新列表（见 switchTab）
        int miniSz   = 18;
        int botBtnY   = panelY + getPanelH() - 22;
        int rightEdge = panelX + PANEL_W - PAD;

        // [❌ 关闭]
        addRenderableWidget(Button.builder(
                Component.literal("❌"),
                b -> onClose())
            .bounds(rightEdge - miniSz, botBtnY, miniSz, miniSz)
            .build());

        // [❖ 上传]（全玩家可见）
        addRenderableWidget(Button.builder(
                Component.literal("❖"),
                b -> Minecraft.getInstance().setScreen(new UploadBlueprintScreen(BlueprintLibraryScreen.this)))
            .bounds(rightEdge - miniSz * 2 - 2, botBtnY, miniSz, miniSz)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("spatial_blueprint.gui.upload.btn.open.tooltip")))
            .build());

        // ── 搜索框（仅在 Tab 已选中时显示）──────────────────────────────
        if (activeTab != null) {
            searchBox = new EditBox(font,
                    panelX + PAD + 1, getSearchBoxY(),
                    PANEL_W - PAD * 2 - 2, 14,
                    Component.empty());
            searchBox.setMaxLength(64);
            searchBox.setHint(Component.translatable("spatial_blueprint.gui.library.search_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            searchBox.setValue(searchQuery);
            searchBox.setResponder(text -> {
                if (!text.equals(searchQuery)) {
                    searchQuery = text;
                    scrollRow = 0;
                    // 搜索改变了可见集合：清掉选中并重建，避免底部迷你按钮还挂在已消失的行上
                    if (selectedSummary != null) {
                        selectedSummary = null;
                        clearWidgets(); init();
                    } else {
                        rebuildFiltered();
                    }
                }
            });
            addRenderableWidget(searchBox);
        }

        // ── ✚ 创建项目（projectset tab 常显，不依赖选中行；仅 OP 可点）────────
        // 放在操作按钮行最左端；同 tab 之后若有选中行的迷你按钮，从它右边接着排。
        int createProjectShift = 0;
        if ("projectset".equals(activeTab)) {
            int btnSz = 18;
            int abY   = panelY + getPanelH() - 22;
            boolean opUser = isOp();
            Button btnCreate = addRenderableWidget(Button.builder(
                    Component.literal("✚"), b -> openCreateProject())
                .bounds(panelX + PAD, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(opUser
                        ? Component.translatable("spatial_blueprint.gui.library.btn.create_project_tip")
                        : Component.translatable("spatial_blueprint.gui.library.btn.create_project_tip")
                                .append("\n")
                                .append(Component.translatable("spatial_blueprint.gui.library.btn.create_project_tip.op_only")
                                        .withStyle(ChatFormatting.RED))))
                .build());
            btnCreate.active = opUser;
            createProjectShift = btnSz + 2;

            // ◈ 导入项目：所有玩家可点（OP 门禁在里面的蓝图站 tab 上）
            addRenderableWidget(Button.builder(
                    Component.literal("◈"),
                    b -> Minecraft.getInstance().setScreen(new ProjectBrowserScreen(BlueprintLibraryScreen.this)))
                .bounds(panelX + PAD + createProjectShift, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.library.btn.import_project_tip")))
                .build());
            createProjectShift += btnSz + 2;
        }

        // ── 操作按钮行（左对齐，仅选中蓝图时显示）─────────────────────────
        // 选中的蓝图不属于本存档时整行隐藏：它在这个档里做不了任何事，
        // 留一排点了没反应（或更糟，真生效了）的按钮只会误导人。
        if (activeTab != null && selectedSummary != null && !selectedSummary.worldLocked()) {
            boolean hasSelection  = true;
            boolean opUser        = isOp();
            int btnSz = 18;
            int abY   = panelY + getPanelH() - 22;
            int abX   = panelX + PAD + createProjectShift;
            int abGap = 2;

            // [TP] 安全传送坐标（只在工程 Tab；仅 OP；在 ✎ 左边）—— 打开 SafeCoordScreen，
            // tooltip 第二行显示摘要里合并进来的现值。
            if ("engineering".equals(activeTab)) {
                net.minecraft.network.chat.MutableComponent tpTip = Component.translatable(
                        "spatial_blueprint.gui.library.btn.safe_coord_tip");
                tpTip.append("\n").append(selectedSummary.safeCoordSet()
                        ? Component.translatable("spatial_blueprint.gui.library.btn.safe_coord_tip.current",
                                selectedSummary.safeX(), selectedSummary.safeY(), selectedSummary.safeZ())
                                .withStyle(ChatFormatting.GRAY)
                        : Component.translatable("spatial_blueprint.gui.library.btn.safe_coord_tip.unset")
                                .withStyle(ChatFormatting.GRAY));
                if (!opUser) {
                    tpTip.append("\n").append(Component.translatable(
                            "spatial_blueprint.gui.library.btn.create_project_tip.op_only").withStyle(ChatFormatting.RED));
                }
                Button btnSafeCoord = addRenderableWidget(Button.builder(
                        Component.literal("TP"), b -> {
                            if (selectedSummary == null) return;
                            Minecraft.getInstance().setScreen(new SafeCoordScreen(this, selectedSummary));
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(tpTip))
                    .build());
                btnSafeCoord.active = opUser;
                abX += btnSz + abGap;
            }

            // ✎ 设定蓝图
            Button btnSettings = addRenderableWidget(Button.builder(
                    Component.literal("✎"), b -> {
                        if (selectedSummary == null) return;
                        settingsTarget = selectedSummary;
                        selectedSummary = null;
                        clearWidgets(); init();
                    })
                .bounds(abX, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("spatial_blueprint.gui.library.btn.settings_tip")))
                .build());
            btnSettings.active = hasSelection;
            abX += btnSz + abGap;

            // 已放置的次元蓝图：结构已还原回世界、材料已从次元袋扣除，禁止再次取出
            boolean placedLocked = hasSelection && selectedSummary.placed();
            boolean isDimensionalSel = hasSelection && "dimensional".equals(selectedSummary.category());
            boolean ownsSelection    = isOwnerOfSelection();

            // ✔ 应用 —— 次元蓝图只有主人才看得到这个按钮。
            // 次元蓝图与主人的次元袋一一绑定，别人取走后材料仍锁在原主人袋里，
            // 归属和材料空间会彻底对不上；无主的旧蓝图同样不允许取出。
            if (!isDimensionalSel || ownsSelection) {
                Button btnApply = addRenderableWidget(Button.builder(
                        Component.literal("✔"), b -> {
                            if (selectedSummary == null || selectedSummary.placed()) return;
                            BlueprintNetwork.CHANNEL.sendToServer(new C2S_ApplyBlueprintMessage(selectedSummary.id()));
                            Minecraft.getInstance().setScreen(null);
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(placedLocked
                                    ? "spatial_blueprint.gui.library.btn.apply_tip.placed"
                                    : "spatial_blueprint.gui.library.btn.apply_tip")))
                    .build());
                btnApply.active = hasSelection && !placedLocked;
                abX += btnSz + abGap;
            }

            // ✖ 删除 / 归档 —— 次元蓝图的规则：
            //
            //   未放置        任何人都不能删，OP 也不能归档（材料还锁在次元袋里，
            //                 删掉等于结构与材料一起蒸发）→ 红色不可点击按钮 + 说明
            //   已放置 · 主人  真正删除（结构已在世界里，删的只是云端槽位）
            //   已放置 · OP    归档为 .bak，留操作记录
            //   已放置 · 其他  不显示
            boolean lockedUnplacedDim = isDimensionalSel && !selectedSummary.placed();
            boolean archiveMode = isDimensionalSel && selectedSummary.placed() && !ownsSelection && opUser;

            if (lockedUnplacedDim) {
                // 禁用态下 MC 会把默认文字色刷成灰色，但 Component 自带的 style 优先级更高，
                // 因此这里能画出一个红色的、点不动的 ✖
                Button btnLocked = addRenderableWidget(Button.builder(
                        Component.literal("✖").withStyle(net.minecraft.ChatFormatting.RED), b -> {})
                    .bounds(abX, abY, btnSz, btnSz)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(
                                    "spatial_blueprint.gui.library.btn.delete_tip.unplaced_locked")))
                    .build());
                btnLocked.active = false;
                abX += btnSz + abGap;
            } else if (!isDimensionalSel || ownsSelection || opUser) {
                // 归档（次元已放置、非主人、OP）用 ⎙ 图标；其余真删除仍用 ✖
                Button btnDelete = addRenderableWidget(Button.builder(
                        Component.literal(archiveMode ? "⎙" : "✖"), b -> {
                            if (selectedSummary == null) return;
                            confirmDelete = selectedSummary;
                            clearWidgets(); init();
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable(archiveMode
                                    ? "spatial_blueprint.gui.library.btn.archive_tip"
                                    : "spatial_blueprint.gui.library.btn.delete_tip")))
                    .build());
                btnDelete.active = hasSelection;
                abX += btnSz + abGap;
            }

            // ♻ 类型互转：空间/投影蓝图，OP，且非奖励、非强制捕获、非强制上传
            String selCategory = selectedSummary.category();
            boolean convertible = opUser
                    && ("create".equals(selCategory) || "project".equals(selCategory))
                    && selectedSummary.saveMode() != CaptureMode.FORCE
                    && selectedSummary.uploadMode() != 2;
            if (convertible) {
                Button btnConvert = addRenderableWidget(Button.builder(
                        Component.literal("♻"), b -> {
                            if (selectedSummary == null) return;
                            doConvert(selectedSummary.id());
                        })
                    .bounds(abX, abY, btnSz, btnSz)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("spatial_blueprint.gui.library.btn.convert_tip")))
                    .build());
                btnConvert.active = hasSelection;
                abX += btnSz + abGap;
            }

            // ☄ 查看材料清单（所有 tab；该蓝图无材料清单时置灰）
            int matCount = selectedSummary.materialCount();
            Button btnMaterials = addRenderableWidget(Button.builder(
                    Component.literal("☄"), b -> {
                        if (selectedSummary == null) return;
                        doViewMaterials(selectedSummary.id());
                    })
                .bounds(abX, abY, btnSz, btnSz)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable(matCount > 0
                                ? "spatial_blueprint.gui.library.btn.materials_tip"
                                : "spatial_blueprint.gui.library.btn.materials_tip.empty")))
                .build());
            btnMaterials.active = matCount > 0;
            abX += btnSz + abGap;

        }

    }

    /** 点击 ☄：向服务端轻量请求该蓝图的材料清单，回包后拉起只读页面。 */
    private void doViewMaterials(String blueprintId) {
        BlueprintNetwork.CHANNEL.sendToServer(
                new C2S_RequestBlueprintMaterialsMessage(blueprintId));
    }

    private void addOverlayButtons(int[] confirmBounds, int[] cancelBounds,
                                   Runnable confirmAction, Runnable cancelAction) {
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.library.confirm_delete.ok"),
                b -> confirmAction.run())
                .bounds(confirmBounds[0], confirmBounds[1], confirmBounds[2], confirmBounds[3]).build());
        addRenderableWidget(Button.builder(
                Component.translatable("spatial_blueprint.gui.library.confirm_delete.cancel"),
                b -> cancelAction.run())
                .bounds(cancelBounds[0], cancelBounds[1], cancelBounds[2], cancelBounds[3]).build());
    }

    private void switchTab(String tab) {
        activeTab = tab;
        scrollRow = 0;
        searchQuery = "";
        confirmDelete = null;
        selectedSummary = null;
        // 切 Tab 即刷新（原「刷新」按钮已移除）。
        // 这里只发请求、不清缓存 —— 旧数据先留着显示，新列表到达后由 render() 的
        // 版本号跟随机制自动替换，避免每次切页都闪一下「加载中」。
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestLibraryMessage());
        clearWidgets();
        init();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        List<BlueprintStorage.BlueprintSummary> fresh = ClientLibraryCache.get();
        if (fresh != null && fresh != summaries) {
            summaries = fresh;
            rebuildFiltered();
        }
    }

    // ── 键盘 ──────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }

        if (settingsTarget != null) {
            if (keyCode == 257 || keyCode == 335) { confirmSettings(); return true; }
            if (keyCode == 256) { cancelSettings(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (creatingProject) {
            if (keyCode == 257 || keyCode == 335) { confirmCreateProject(); return true; }
            if (keyCode == 256) { cancelCreateProject(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (confirmDelete != null && keyCode == 256) {
            cancelDelete();
            return true;
        }
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    // ── 鼠标 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isOverlayActive()) return true;
        if (activeTab == null) return true;
        int listTop    = getListTop();
        int listBottom = getListBottom();
        if (mouseX >= panelX && mouseX < panelX + PANEL_W
                && mouseY >= listTop && mouseY < listBottom) {
            scrollRow = clamp(scrollRow - (int) Math.signum(delta), 0, maxScrollRow);
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (settingsTarget != null || creatingProject) {
            if (!inRect(mouseX, mouseY, settingsOverlayPanelBounds())) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (confirmDelete != null) {
            if (!inRect(mouseX, mouseY, deleteOverlayPanelBounds())) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // ── 标题前缀（⎘/⎗）点击 → 切换服务器/私人模式 ────────────────────
        if (errorToast != null) {
            errorToast = null;
            if (button == 0) return true;
        }

        if (button == 0 && isOp()
                && mouseY >= titleY && mouseY < titleY + font.lineHeight
                && mouseX >= titlePrefixX && mouseX < titlePrefixX + titlePrefixW) {
            toggleServerMode();
            return true;
        }

        // ── 行选中（单击切换选中）──
        if (button == 0 && activeTab != null && confirmDelete == null) {
            int listTop      = getListTop();
            int listBottom   = getListBottom();
            int contentRight = panelX + PANEL_W - PAD;
            if (mouseX >= panelX + PAD && mouseX < contentRight
                    && mouseY >= listTop && mouseY < listBottom) {
                int clickedVis = (int)((mouseY - listTop) / ROW_H);
                int idx = scrollRow + clickedVis;
                if (idx >= 0 && idx < filtered.size()) {
                    BlueprintStorage.BlueprintSummary clicked = filtered.get(idx);
                    selectedSummary = clicked.equals(selectedSummary) ? null : clicked;
                    clearWidgets(); init();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 服务端推来新列表时跟随刷新（例如放置次元蓝图后列表会带上「已放置」标记）
        int lv = ClientLibraryCache.version();
        if (lv != libraryVersion) {
            libraryVersion = lv;
            refreshFromLibraryCache();
        }

        this.renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }

        boolean inOverlay = isOverlayActive();

        // 弹窗（设定蓝图 / 删除确认）打开时不画主列表面板 —— 背景只剩整块遮罩 + 弹窗
        if (!inOverlay) {
            int pH = getPanelH();

            // 面板背景 + 边框
            graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + pH, 0xD0101010);
            drawBorder(graphics, panelX, panelY, PANEL_W, pH);

            renderTitle(graphics, mouseX, mouseY);

            // Tab 区域分割线（最后一排 Tab 下方）
            graphics.fill(panelX + 4, getSepY(), panelX + PANEL_W - 4, getSepY() + 1, 0xFF444444);
        }

        if (activeTab != null && !inOverlay) {
            // 搜索框下方分割线
            graphics.fill(panelX + 4, getListSepY(), panelX + PANEL_W - 4, getListSepY() + 1, 0xFF444444);
        }

        // 重置悬浮状态
        hoveredNameRow  = -1;
        hoveredStatsRow = -1;
        hoveredNoteTip = null;

        if (!inOverlay) {
            if (activeTab == null) {
                // 未选 Tab：提示文本
                String hint = Component.translatable("spatial_blueprint.gui.library.select_hint").getString();
                int hintX   = panelX + PANEL_W / 2;
                int sepYTop = getSepY() + 1;
                int hintY   = sepYTop + (getPanelH() - (sepYTop - panelY) - 26 - font.lineHeight) / 2;
                graphics.drawCenteredString(font, hint, hintX, hintY, 0x666666);
            } else {
                renderServerRows(graphics, mouseX, mouseY);
            }
        }

        // Overlay backgrounds are drawn before widgets so vanilla buttons stay visible.
        if (settingsTarget != null) {
            renderOverlayBackground(graphics, "spatial_blueprint.gui.library.settings_title",
                    settingsOverlayPanelBounds());
        }
        if (creatingProject) {
            renderOverlayBackground(graphics, "spatial_blueprint.gui.library.create_project.title",
                    settingsOverlayPanelBounds());
        }
        if (confirmDelete != null) {
            renderConfirmOverlayBackground(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // ── Tooltip ───────────────────────────────────────────────────────
        if (!inOverlay && confirmDelete == null) {
            renderTooltips(graphics, mouseX, mouseY);
        }

    }

    // ── 服务器列表行渲染（含 Tab 逻辑）──────────────────────────────────

    private void renderServerRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int listTop      = getListTop();
        int listBottom   = getListBottom();
        int contentRight = panelX + PANEL_W - PAD;

        graphics.enableScissor(panelX + PAD, listTop, contentRight, listBottom);

        if (filtered.isEmpty()) {
            String empty = ClientLibraryCache.isLoading()
                    ? Component.translatable("spatial_blueprint.gui.library.loading").getString()
                    : Component.translatable("spatial_blueprint.gui.library.empty").getString();
            graphics.drawCenteredString(font, empty,
                    panelX + PAD + (contentRight - panelX - PAD) / 2,
                    listTop + (listBottom - listTop) / 2 - font.lineHeight / 2,
                    0x888888);
        } else {
            int vis = Math.max(1, (listBottom - listTop) / ROW_H);
            for (int i = 0; i < vis; i++) {
                int idx = scrollRow + i;
                if (idx >= filtered.size()) break;
                BlueprintStorage.BlueprintSummary s = filtered.get(idx);
                int rowY = listTop + i * ROW_H;
                boolean isSelected = s.equals(selectedSummary);
                boolean hoverableRow = confirmDelete == null
                        && mouseX >= panelX + PAD && mouseX < contentRight
                        && mouseY >= rowY && mouseY < rowY + ROW_H;

                // 行背景：选中行用较亮的底色
                graphics.fill(panelX + PAD, rowY, contentRight, rowY + ROW_H,
                        isSelected ? 0x30FFFFFF : (i % 2 == 0 ? 0x18FFFFFF : 0x08FFFFFF));

                String statsStr = formatDateShort(s.createdAt());
                int dateW = font.width(statsStr);
                int dateX = contentRight - dateW - 3;
                String noteDisplay;
                String noteTip = s.noteLong().isEmpty() ? null : s.noteLong();
                if (s.noteShort().isEmpty() && s.noteLong().isEmpty()) {
                    noteDisplay = Component.translatable("spatial_blueprint.gui.library.note.none").getString();
                    noteTip = null;
                } else if (s.noteShort().isEmpty()) {
                    noteDisplay = Component.translatable("spatial_blueprint.gui.library.note.default").getString();
                } else {
                    noteDisplay = s.noteShort();
                }
                // 不属于本存档时强制覆盖备注 —— 这条比玩家自己写的备注重要得多
                int noteColor = 0x888888;
                if (s.worldLocked()) {
                    noteDisplay = Component.translatable("spatial_blueprint.gui.library.foreign_world").getString();
                    noteTip = null;
                    noteColor = 0xFF5555;
                }
                int nameX = panelX + PAD + 3;
                boolean isReward = "reward".equals(s.category());
                // 名字后的后缀：已放置的次元蓝图「(已放置)」、奖励蓝图「(奖励)」，需要预留宽度
                Component nameSuffix = s.placed()
                        ? Component.translatable("spatial_blueprint.gui.library.placed_suffix")
                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                        : isReward
                                ? Component.translatable("spatial_blueprint.gui.library.reward_suffix")
                                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                                : null;
                int suffixW = nameSuffix != null ? font.width(nameSuffix) : 0;
                // 名字列定宽。后缀「(已放置)」「(奖励)」是状态信息，任何情况下都要完整显示 ——
                // 字符预算与像素宽度都先扣掉它，让位的永远是名字：xxxxx…(奖励)
                String rawName = s.name().isEmpty()
                        ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                        : s.name();
                int suffixChars = nameSuffix == null ? 0
                        : nameSuffix.getString().codePointCount(0, nameSuffix.getString().length());
                int nameChars = Math.max(6, MAX_NAME_CHARS - suffixChars);
                String displayName = truncate(truncateChars(rawName, nameChars),
                        Math.max(0, NAME_COL_W - suffixW));
                // 备注同样定宽；截断后没有别的 tooltip 时，把完整备注挂上去，信息不至于丢失
                String fullNote = noteDisplay;
                noteDisplay = truncate(truncateChars(noteDisplay, MAX_NOTE_CHARS), NOTE_COL_W);
                if (noteTip == null && !noteDisplay.equals(fullNote)) noteTip = fullNote;
                int noteW = font.width(noteDisplay);
                int textY = rowY + (ROW_H - font.lineHeight) / 2;
                // 颜色优先级：上传来源 > 奖励金色 > saveMode。
                // 安全上传(1) 用青 0x55FFFF 岔开与 SANITIZE 的黄；强制上传(2) 红+加粗，
                // 与 saveMode=FORCE 的红同色但额外加粗以示「上传强制」。
                boolean nameBold  = false;
                int nameColor;
                if (isReward) {
                    nameColor = 0xFFAA00;
                } else if (s.uploadMode() == 2) {
                    nameColor = 0xFF5555; nameBold = true;
                } else if (s.uploadMode() == 1) {
                    nameColor = 0x55FFFF;
                } else if (s.saveMode() == null) {
                    nameColor = 0xEEEEEE;
                } else {
                    nameColor = switch (s.saveMode()) {
                        case FORCE    -> 0xFF5555;
                        case SANITIZE -> 0xFFFF55;
                        default       -> 0xEEEEEE;
                    };
                }
                if (nameBold) {
                    graphics.drawString(font,
                            Component.literal(displayName).withStyle(
                                    net.minecraft.network.chat.Style.EMPTY
                                            .withColor(net.minecraft.network.chat.TextColor.fromRgb(nameColor))
                                            .withBold(true)),
                            nameX, textY, nameColor, false);
                } else {
                    graphics.drawString(font, displayName, nameX, textY, nameColor, false);
                }
                int nameDrawnW = font.width(displayName);
                if (nameSuffix != null) {
                    graphics.drawString(font, nameSuffix, nameX + nameDrawnW, textY, 0xFFFFFF, false);
                    nameDrawnW += suffixW;
                }

                // 日期（右对齐，贴面板右边缘）
                graphics.drawString(font, statsStr, dateX, textY, 0x888888, false);

                // 备注水平居中于整个面板（与标题、空列表提示同一中心线），
                // 跨存档(foreign_world)等重要提示也保持居中，视觉上一目了然
                int noteX = panelX + PANEL_W / 2 - noteW / 2;
                graphics.drawString(font, noteDisplay, noteX, textY, noteColor, false);
                if (noteTip != null && hoverableRow
                        && mouseX >= noteX && mouseX < noteX + noteW
                        && mouseY >= textY && mouseY < textY + font.lineHeight) {
                    hoveredNoteTip = noteTip;
                }

                // 名称 / 日期 hover 检测
                if (hoverableRow && mouseY >= textY && mouseY < textY + font.lineHeight) {
                    int nameEndX = nameX + nameDrawnW;
                    if (mouseX >= nameX && mouseX < nameEndX) {
                        hoveredNameRow = i;
                    } else if (mouseX >= dateX && mouseX < dateX + font.width(statsStr)) {
                        hoveredStatsRow = i;
                    }
                }

                // 选中边框（白色 1px）
                if (isSelected) {
                    int sc = 0xFFFFFFFF;
                    graphics.fill(panelX + PAD,     rowY,             contentRight,    rowY + 1,         sc);
                    graphics.fill(panelX + PAD,     rowY + ROW_H - 1, contentRight,    rowY + ROW_H,     sc);
                    graphics.fill(panelX + PAD,     rowY,             panelX + PAD + 1, rowY + ROW_H,    sc);
                    graphics.fill(contentRight - 1, rowY,             contentRight,    rowY + ROW_H,     sc);
                }
            }
        }
        graphics.disableScissor();
    }

    // ── Tooltip ───────────────────────────────────────────────────────────

    private void renderTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredNoteTip != null) {
            graphics.renderTooltip(font, Component.literal(hoveredNoteTip), mouseX, mouseY);
            return;
        }
        // 标题前缀（⎘/⎗）tooltip
        if (isOp() && mouseY >= titleY && mouseY < titleY + font.lineHeight
                && mouseX >= titlePrefixX && mouseX < titlePrefixX + titlePrefixW) {
            String tipKey = serverMode
                    ? "spatial_blueprint.gui.library.title.prefix.to_private"
                    : "spatial_blueprint.gui.library.title.prefix.to_server";
            graphics.renderTooltip(font, Component.translatable(tipKey), mouseX, mouseY);
            return;
        }
        // 主标题文本 tooltip
        if (mouseY >= titleY && mouseY < titleY + font.lineHeight
                && mouseX >= titleMainX && mouseX < titleMainX + titleMainW) {
            String tipKey = serverMode
                    ? "spatial_blueprint.gui.library.title.main.tooltip.server"
                    : "spatial_blueprint.gui.library.title.main.tooltip.private";
            graphics.renderTooltip(font, Component.translatable(tipKey), mouseX, mouseY);
            return;
        }

        // 名称 tooltip
        if (hoveredNameRow >= 0) {
            int tipIdx = scrollRow + hoveredNameRow;
            if (tipIdx < filtered.size()) renderSummaryTooltip(graphics, filtered.get(tipIdx), mouseX, mouseY);
            return;
        }
        // 日期列 tooltip
        if (hoveredStatsRow >= 0) {
            int tipIdx = scrollRow + hoveredStatsRow;
            if (tipIdx < filtered.size()) renderSummaryTooltip(graphics, filtered.get(tipIdx), mouseX, mouseY);
            return;
        }

    }

    private void renderSummaryTooltip(GuiGraphics g, BlueprintStorage.BlueprintSummary s, int mx, int my) {
        // 行内容集中在 BlueprintTooltips，与「次元蓝图材料空间」界面共用同一套
        g.renderTooltip(font,
                BlueprintTooltips.build(s, false),
                mx, my);
    }

    // ── 弹窗渲染 ──────────────────────────────────────────────────────────

    private void renderOverlayBackground(GuiGraphics graphics, String titleKey, int[] panelBounds) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        int[] p = panelBounds;
        graphics.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], 0xFF1A1A1A);
        drawBorder(graphics, p[0], p[1], p[2], p[3]);
        graphics.drawCenteredString(font, Component.translatable(titleKey).getString(),
                this.width / 2, p[1] + 10, 0xFFFFAA);
    }

    private void renderConfirmOverlayBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        int[] panel = deleteOverlayPanelBounds();
        int cpX = panel[0], cpY = panel[1], cpW = panel[2], cpH = panel[3];
        int cx = cpX + cpW / 2;
        graphics.fill(cpX, cpY, cpX + cpW, cpY + cpH, 0xFF1A1A1A);
        drawBorder(graphics, cpX, cpY, cpW, cpH);

        boolean archive = isDeleteArchiveMode();
        String title = Component.translatable(archive
                ? "spatial_blueprint.gui.library.confirm_archive.title"
                : "spatial_blueprint.gui.library.confirm_delete.title").getString();
        String nameStr = truncate(confirmDelete.name().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : confirmDelete.name(), cpW - 20);

        graphics.drawCenteredString(font, title,   cx, cpY + 10, 0xFF5555);
        graphics.drawCenteredString(font, nameStr, cx, cpY + 24, 0xFFFFFF);

        java.util.List<String> lines = deleteConfirmLines();
        int lineY = cpY + 38;
        for (int i = 0; i < lines.size(); i++) {
            int color = i < deleteConfirmLineColors.size() ? deleteConfirmLineColors.get(i)[0] : 0xAAAAAA;
            graphics.drawCenteredString(font, lines.get(i), cx, lineY, color);
            lineY += 12;
        }
    }

    // ── 设定蓝图 ──────────────────────────────────────────────────────────

    private void confirmSettings() {
        if (settingsTarget == null || settingsNameBox == null) return;
        String newName = settingsNameBox.getValue().trim();
        String newNoteShort = settingsNoteShortBox != null ? settingsNoteShortBox.getValue().trim() : "";
        String newNoteLong = settingsNoteLongBox != null ? settingsNoteLongBox.getValue().trim() : "";

        // 名字 + 备注（一起提交）
        if (!newName.isEmpty()) {
            BlueprintNetwork.CHANNEL.sendToServer(new C2S_RenameBlueprintMessage(
                    settingsTarget.id(), newName, newNoteShort, newNoteLong));
            ClientLibraryCache.invalidate();
            summaries = new ArrayList<>();
        }

        // 时长（仅 OP 且值有变化时提交）
        if (isOp() && settingsDurationBox != null) {
            String durVal = settingsDurationBox.getValue().trim();
            if (!durVal.isEmpty()) {
                try {
                    int dur = Math.max(ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MIN),
                            Math.min(ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MAX), Integer.parseInt(durVal)));
                    if (dur != settingsTarget.animDuration()) {
                        BlueprintNetwork.CHANNEL.sendToServer(
                                new com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage(
                                        settingsTarget.id(),
                                        com.xioyim.spatialblueprint.network.C2S_SetWithdrawPropertyMessage.PROP_DURATION,
                                        dur));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        clearSettings();
        clearWidgets(); init();
    }

    private void cancelSettings() {
        clearSettings();
        clearWidgets(); init();
    }

    private void clearSettings() {
        settingsTarget = null;
        settingsNameBox = null;
        settingsDurationBox = null;
        settingsNoteShortBox = null;
        settingsNoteLongBox = null;
    }

    /** 复制文件信息：OP 复制完整绝对路径（游戏目录 + config/spatial_blueprint/…）；普通玩家只复制文件名。 */
    private void copyPath() {
        if (settingsTarget == null) return;
        String text = isOp() ? getSummaryAbsPath(settingsTarget) : getSummaryFilename(settingsTarget);
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        NotifySound.client();
    }

    private void addSettingsButtons() {
        int[] panel = settingsOverlayPanelBounds();
        int btnY = panel[1] + 108;
            Component confirmLabel = Component.translatable("spatial_blueprint.gui.library.settings.confirm");
            // OP 复制完整路径，普通玩家只能拿到文件名 —— 按钮文案跟着变
            Component copyLabel    = Component.translatable(isOp()
                    ? "spatial_blueprint.gui.library.settings.copy_path"
                    : "spatial_blueprint.gui.library.settings.copy_filename");
            Component cancelLabel  = Component.translatable("spatial_blueprint.gui.library.settings.cancel");
        // 三个按钮等宽、按最长的文字（英文 Copy path 往往最长）留足内边距，
        // 避免不同语言下文字贴边/截断。
        int maxTextW = Math.max(font.width(confirmLabel),
                Math.max(font.width(copyLabel), font.width(cancelLabel)));
        int btnW = maxTextW + 24;
        int gap = 6;
        int bx = panel[0] + (panel[2] - (btnW * 3 + gap * 2)) / 2;

        settingsConfirmButton = addRenderableWidget(Button.builder(confirmLabel, b -> confirmSettings())
                .bounds(bx, btnY, btnW, 18).build());
        bx += btnW + gap;
        addRenderableWidget(Button.builder(copyLabel, b -> copyPath())
                .bounds(bx, btnY, btnW, 18).build());
        bx += btnW + gap;
        addRenderableWidget(Button.builder(cancelLabel, b -> cancelSettings())
                .bounds(bx, btnY, btnW, 18).build());
    }

    // ── 创建项目蓝图 ──────────────────────────────────────────────────────

    private void openCreateProject() {
        if (!isOp()) return;   // 按钮已置灰，这里只是防御；真正的门禁在服务端
        creatingProject = true;
        selectedSummary = null;
        clearWidgets(); init();
    }

    private void confirmCreateProject() {
        if (!creatingProject || createProjectNameBox == null) return;
        String name = createProjectNameBox.getValue().trim();
        if (name.isEmpty()) return;   // 与置灰的「创建」按钮同一条件；回车也走这里
        String noteShort = createProjectNoteShortBox != null ? createProjectNoteShortBox.getValue().trim() : "";
        String noteLong  = createProjectNoteLongBox  != null ? createProjectNoteLongBox.getValue().trim()  : "";

        BlueprintNetwork.CHANNEL.sendToServer(new C2S_CreateProjectMessage(name, noteShort, noteLong));

        clearCreateProject();
        clearWidgets(); init();
    }

    private void cancelCreateProject() {
        clearCreateProject();
        clearWidgets(); init();
    }

    private void clearCreateProject() {
        creatingProject = false;
        createProjectNameBox = null;
        createProjectNoteShortBox = null;
        createProjectNoteLongBox = null;
        createProjectConfirmButton = null;
    }

    private void addCreateProjectButtons() {
        int[] panel = settingsOverlayPanelBounds();
        int btnY = panel[1] + 108;
        Component confirmLabel = Component.translatable("spatial_blueprint.gui.library.create_project.confirm");
        Component cancelLabel  = Component.translatable("spatial_blueprint.gui.library.create_project.cancel");
        int btnW = Math.max(font.width(confirmLabel), font.width(cancelLabel)) + 24;
        int gap = 6;
        int bx = panel[0] + (panel[2] - (btnW * 2 + gap)) / 2;

        createProjectConfirmButton = addRenderableWidget(Button.builder(confirmLabel, b -> confirmCreateProject())
                .bounds(bx, btnY, btnW, 18).build());
        bx += btnW + gap;
        addRenderableWidget(Button.builder(cancelLabel, b -> cancelCreateProject())
                .bounds(bx, btnY, btnW, 18).build());
    }

    /** 名字为空时「创建」置灰。 */
    private void refreshCreateProjectValidity() {
        if (createProjectConfirmButton == null || createProjectNameBox == null) return;
        createProjectConfirmButton.active = !createProjectNameBox.getValue().trim().isEmpty();
    }

    /**
     * 时长输入实时校验：值不在 [ANIMATION_DURATION_MIN, MAX] 内或非法时，
     * 输入文字变红并把「确认」置灰；为空（不改）或合法时恢复正常。
     * 非 OP 的时长框只读、不改时长，确认始终可点。
     */
    private void refreshDurationValidity() {
        if (settingsConfirmButton == null) return;
        if (!isOp() || settingsDurationBox == null) {
            settingsConfirmButton.active = true;
            return;
        }
        int normalColor = 14737632; // 0xE0E0E0，EditBox 默认文字色
        int errorColor  = 16733525; // 0xFF5555
        String val = settingsDurationBox.getValue().trim();
        if (val.isEmpty()) {
            settingsDurationBox.setTextColor(normalColor);
            settingsConfirmButton.active = true;
            return;
        }
        try {
            int dur = Integer.parseInt(val);
            int min = ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MIN);
            int max = ConfigAccess.get(BlueprintConfig.ANIMATION_DURATION_MAX);
            boolean ok = dur >= min && dur <= max;
            settingsDurationBox.setTextColor(ok ? normalColor : errorColor);
            settingsConfirmButton.active = ok;
        } catch (NumberFormatException ex) {
            settingsDurationBox.setTextColor(errorColor);
            settingsConfirmButton.active = false;
        }
    }

    private void confirmDelete() {
        if (confirmDelete == null) return;
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_DeleteBlueprintMessage(confirmDelete.id()));
        ClientLibraryCache.invalidate();
        summaries = new ArrayList<>();
        rebuildFiltered();
        confirmDelete = null;
        clearWidgets(); init();
    }

    private void cancelDelete() {
        confirmDelete = null;
        clearWidgets(); init();
    }

    private void doConvert(String id) {
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_ConvertBlueprintMessage(id));
        ClientLibraryCache.invalidate();
        summaries = new ArrayList<>();
        rebuildFiltered();
        selectedSummary = null;
        clearWidgets(); init();
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────────

    // ── 动态布局辅助 ──────────────────────────────────────────────────────
    /** 面板高度：恒为两排 Tab，264。 */
    private int getPanelH()      { return 264; }
    /** 最后一排 Tab 底部 Y。 */
    private int getLastTabRowBottom() { return panelY + 52; }
    /** Tab 区域与搜索框之间的分割线 Y。 */
    private int getSepY()        { return getLastTabRowBottom() + 3; }
    /** 搜索框顶部 Y（分割线下 3px）。 */
    private int getSearchBoxY()  { return getSepY() + 3; }
    /** 搜索框下方分割线 Y（搜索框 14px 高 + 2px 间距）。 */
    private int getListSepY()    { return getSearchBoxY() + 14 + 2; }
    private int getListTop()     { return getListSepY() + 1; }
    private int getListBottom()  { return panelY + getPanelH() - 26; }

    private boolean isOp() {
        return Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.hasPermissions(2);
    }

    private void toggleServerMode() {
        serverMode = !serverMode;
        displayTitle = serverMode
                ? Component.translatable("spatial_blueprint.gui.library.title.server")
                : Component.translatable("spatial_blueprint.gui.library.title.private");
        scrollRow = 0;
        ClientLibraryCache.invalidate();
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestLibraryMessage());
        rebuildFiltered();
        clearWidgets(); init();
    }

    private void renderTitle(GuiGraphics graphics, int mouseX, int mouseY) {
        int ty = panelY + 5;
        titleY = ty;
        int titleColor = serverMode ? 0x55DDFF : 0xFFFFAA;
        String mainStr = displayTitle.getString();
        int mainW = font.width(mainStr);

        if (isOp()) {
            String prefix = serverMode ? "⎗" : "⎘";
            String hash   = "#";
            int prefixW = font.width(prefix);
            int hashW   = font.width(hash);
            int totalW  = prefixW + 2 + hashW + 2 + mainW;
            int startX  = panelX + PANEL_W / 2 - totalW / 2;

            titlePrefixX = startX;
            titlePrefixW = prefixW;
            titleMainX   = startX + prefixW + 2 + hashW + 2;
            titleMainW   = mainW;

            boolean hoverPrefix = mouseX >= titlePrefixX && mouseX < titlePrefixX + titlePrefixW
                    && mouseY >= ty && mouseY < ty + font.lineHeight;
            Component prefixComp = hoverPrefix
                    ? Component.literal(prefix).withStyle(net.minecraft.ChatFormatting.UNDERLINE)
                    : Component.literal(prefix);
            graphics.drawString(font, prefixComp, startX, ty, titleColor, false);
            graphics.drawString(font, hash, startX + prefixW + 2, ty, titleColor, false);
            graphics.drawString(font, mainStr, titleMainX, ty, titleColor, false);
        } else {
            int startX = panelX + PANEL_W / 2 - mainW / 2;
            titlePrefixX = -999; titlePrefixW = 0;
            titleMainX   = startX; titleMainW = mainW;
            graphics.drawString(font, mainStr, startX, ty, titleColor, false);
        }
    }

    private boolean isOverlayActive() {
        return settingsTarget != null || confirmDelete != null || creatingProject;
    }

    private void rebuildFiltered() {
        truncateCache.clear();
        String lowerQuery = searchQuery.toLowerCase(java.util.Locale.ROOT);

        filtered = new ArrayList<>();
        for (BlueprintStorage.BlueprintSummary s : summaries) {
            String cat = s.category().isEmpty() ? "create" : s.category();
            // 奖励蓝图没有自己的 tab：它归到「空间蓝图」里，且<b>只有 OP 看得见</b> ——
            // 那是 OP 自己造出来发给玩家的东西，普通玩家的列表里不该出现一堆奖励条目。
            if ("reward".equals(cat)) {
                if (!isOp() || !"create".equals(activeTab)) continue;
            } else if (!cat.equals(activeTab)) {
                continue;
            }
            // 私人模式或 template tab：只显示自己的
            if (!serverMode || "template".equals(activeTab)) {
                if (!isMineForListing(s)) continue;
            }
            filtered.add(s);
        }
        if (!lowerQuery.isEmpty()) {
            if (lowerQuery.endsWith(".litematic")) {
                // 完整文件名精确匹配
                filtered.removeIf(s -> !getSummaryFilename(s).toLowerCase(java.util.Locale.ROOT).equals(lowerQuery));
            } else {
                filtered.removeIf(s -> !s.name().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery));
            }
        }
        filtered.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));

        // 校验选中项仍在过滤结果中，若缓存刷新则更新到新实例
        if (selectedSummary != null) {
            String selId = selectedSummary.id();
            selectedSummary = null;
            for (BlueprintStorage.BlueprintSummary s : filtered) {
                if (s.id().equals(selId)) { selectedSummary = s; break; }
            }
        }

        int vis = Math.max(1, (getListBottom() - getListTop()) / ROW_H);
        maxScrollRow = Math.max(0, filtered.size() - vis);
        scrollRow    = clamp(scrollRow, 0, maxScrollRow);
    }

    private int[] settingsOverlayPanelBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        return new int[]{ cx - 150, cy - 70, 300, 140 };
    }
    /**
     * 次元蓝图的删除确认需要第二行警告（材料会被一并清空 / 空置蓝图会失效），
     * 面板相应加高，按钮位置随面板底部走。
     */
    /**
     * 删除 / 归档确认框的正文行：说明这次操作到底做了什么、有没有释放槽位、还会顺带毁掉什么。
     *
     * <p>原来只有一句「删除后无法恢复」——玩家看不出这是真删还是归档，
     * 也不知道会不会腾出一个槽位，而次元蓝图这两点恰恰完全不同。</p>
     */
    private java.util.List<int[]> deleteConfirmLineColors = new ArrayList<>();

    private java.util.List<String> deleteConfirmLines() {
        java.util.List<String> lines = new ArrayList<>();
        deleteConfirmLineColors = new ArrayList<>();
        if (confirmDelete == null) return lines;

        boolean isDim  = "dimensional".equals(confirmDelete.category());
        boolean archive = isDeleteArchiveMode();

        // ① 这次操作到底做了什么
        add(lines, archive
                ? "spatial_blueprint.gui.library.confirm_archive.action"
                : "spatial_blueprint.gui.library.confirm_delete.action", 0xAAAAAA);

        // ② 槽位。空间蓝图与工程蓝图不限槽位，删了也腾不出东西来，说清楚免得玩家白删
        String slotKey = switch (confirmDelete.category()) {
            case "dimensional" -> "spatial_blueprint.gui.library.confirm_delete.slot.dimensional";
            case "project"     -> "spatial_blueprint.gui.library.confirm_delete.slot.project";
            case "template"    -> "spatial_blueprint.gui.library.confirm_delete.slot.template";
            default            -> "spatial_blueprint.gui.library.confirm_delete.slot.none";
        };
        boolean freesSlot = !slotKey.endsWith(".none");
        add(lines, slotKey, freesSlot ? 0x77DD77 : 0x888888);

        // ③ 次元蓝图的连带后果：袋里的材料与玩家手上的空置蓝图
        if (isDim) {
            add(lines, "spatial_blueprint.gui.library.confirm_delete.void_bag", 0xFF7777);
            add(lines, "spatial_blueprint.gui.library.confirm_delete.void_item", 0xFF7777);
        }
        return lines;
    }

    private void add(java.util.List<String> lines, String key, int color) {
        lines.add(Component.translatable(key).getString());
        deleteConfirmLineColors.add(new int[]{color});
    }

    /**
     * 这次 ✖ 是归档而不是删除：OP 处理别人已放置的次元蓝图时只改名为 {@code .bak}。
     *
     * <p>必须与 {@link com.xioyim.spatialblueprint.network.C2S_DeleteBlueprintMessage}
     * 的分支判断保持一致，否则确认框会承诺一件服务端不会做的事。未放置的次元蓝图
     * 走不到这里 —— 列表里那个 ✖ 是红色不可点的。</p>
     */
    private boolean isDeleteArchiveMode() {
        if (confirmDelete == null) return false;
        if (!"dimensional".equals(confirmDelete.category())) return false;
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        String owner = confirmDelete.ownerUuid();
        boolean isOwner = owner != null && !owner.isEmpty() && owner.equals(p.getUUID().toString());
        return confirmDelete.placed() && !isOwner && isOp();
    }

    private int[] deleteOverlayPanelBounds() {
        int cx = this.width / 2, cy = this.height / 2;
        java.util.List<String> lines = deleteConfirmLines();
        int textW = 0;
        for (String line : lines) textW = Math.max(textW, font.width(line));
        int w = Math.min(Math.max(this.width - 20, 120), Math.max(220, textW + 20));
        int h = 38 + lines.size() * 12 + 12 + 18 + 12;
        return new int[]{ cx - w / 2, cy - h / 2, w, h };
    }
    private int[] deleteConfirmBtnBounds() {
        int[] p = deleteOverlayPanelBounds();
        return new int[]{ p[0] + p[2] / 2 - 80, p[1] + p[3] - 30, 75, 18 };
    }
    private int[] deleteCancelBtnBounds() {
        int[] p = deleteOverlayPanelBounds();
        return new int[]{ p[0] + p[2] / 2 + 5, p[1] + p[3] - 30, 75, 18 };
    }

    private static boolean inRect(double mx, double my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    /** 按字符数截断（一个汉字算一个），超出补 …；不切开代理对。 */
    private static String truncateChars(String s, int maxChars) {
        if (s.codePointCount(0, s.length()) <= maxChars) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxChars)) + "…";
    }

    private String truncate(String s, int maxPx) {
        String key = maxPx + "|" + s;
        String hit = truncateCache.get(key);
        if (hit != null) return hit;
        String out = s;
        if (font.width(out) <= maxPx) { truncateCache.put(key, out); return out; }
        while (out.length() > 1 && font.width(out + "…") > maxPx) out = out.substring(0, out.length() - 1);
        out = out + "…";
        truncateCache.put(key, out);
        return out;
    }

    private static String clientSanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.replaceAll("[^A-Za-z0-9_]", "").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "unknown" : s;
    }

    private static String getSummaryFilename(BlueprintStorage.BlueprintSummary s) {
        String cat = clientSanitizeFilename(s.category().isEmpty() ? "unknown" : s.category());
        String cre = clientSanitizeFilename(s.creatorName().isBlank() ? "unknown" : s.creatorName());
        return cat + "_" + cre + "_" + s.id() + ".litematic";
    }

    /** 蓝图文件的完整相对路径（相对 Minecraft 运行目录）。 */
    private static String getSummaryPath(BlueprintStorage.BlueprintSummary s) {
        String sub = subdirForCategory(s.category());
        String filename = getSummaryFilename(s);
        return sub.isEmpty()
                ? "config/spatial_blueprint/" + filename
                : "config/spatial_blueprint/" + sub + "/" + filename;
    }

    /**
     * 蓝图文件的完整绝对路径：客户端游戏目录 + 上一条的相对路径（{@code File.separator} 连接，
     * 形如 {@code D:\...\versions\1.20.1 Forge\config/spatial_blueprint/engineering/xxx.litematic}）。
     * 单机下游戏目录就是服务端目录、即真实文件位置；连专服时这只是本机前缀，文件在服务端机器上。
     */
    private static String getSummaryAbsPath(BlueprintStorage.BlueprintSummary s) {
        java.io.File gameDir = Minecraft.getInstance().gameDirectory;
        return gameDir.getAbsolutePath() + java.io.File.separator + getSummaryPath(s);
    }

    /** 分类 → 存储子目录名（与 {@code BlueprintStorage.dirFor} 一致）。 */
    private static String subdirForCategory(String category) {
        return switch (category == null ? "" : category) {
            case "create"      -> "create";
            case "project"     -> "projections";
            case "template"    -> "templates";
            case "dimensional" -> "dimensional";
            case "engineering" -> "engineering";
            case "reward"      -> "reward";
            default            -> "";
        };
    }

    private static String formatDateShort(long epochMillis) {
        if (epochMillis <= 0) return "-";
        java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return dt.getMonthValue() + "/" + dt.getDayOfMonth()
                + " " + String.format("%02d:%02d:%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    private boolean isCreative() {
        LocalPlayer lp = Minecraft.getInstance().player;
        return lp != null && lp.isCreative();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0xFF888888;
        g.fill(x,         y,         x + w,     y + 1,     c);
        g.fill(x,         y + h - 1, x + w,     y + h,     c);
        g.fill(x,         y,         x + 1,     y + h,     c);
        g.fill(x + w - 1, y,         x + w,     y + h,     c);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
