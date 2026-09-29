package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.client.ClientProjectPreview;
import com.xioyim.spatialblueprint.client.ClientProjectStatusCache;
import com.xioyim.spatialblueprint.client.ClientSubProjectPreview;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestBlueprintDataMessage;
import com.xioyim.spatialblueprint.network.C2S_SubmitProjectChangesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 项目蓝图右键界面 · <b>规划模式</b>（OP 专用，见 待办/记忆：建造模式是另一套 UI）。
 *
 * <p>列表行 = 序号 | 名称 …备注… 状态，行样式照蓝图库（点行选中，白框 + 亮底）；
 * 底部迷你按钮（上移 / 下移 / 设定 / 预览 / 材料 / 删除）+ 文本 [提交修改]，最下一排 [规划项目][预览][关闭] 不动。</p>
 *
 * <p>三类改动全部本地暂存、点 [提交修改] 才发
 * {@link C2S_SubmitProjectChangesMessage} 一次落盘：顺序（上移/下移）、删除标记（删除按钮）、
 * 材料清单（材料按钮打开的子界面 [确定]）；任一未提交就关界面会先弹提示。
 * 有任务集占用（{@code orderEditable=false}）时这些按钮一律置灰。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ProjectBlueprintScreen extends Screen implements ErrorToastSupport {

    private static final String KEY_TITLE = "spatial_blueprint.gui.project_screen.title_planning";

    // 列表版式 = 蓝图库同款（PANEL_W / ROW_H / PAD / 备注列宽 / 行内截断规则都对齐），只多一列序号
    private static final int PANEL_W = 364;
    private static final int HEADER_H = 28;
    private static final int ROW_H = 18;
    private static final int MAX_ROWS = 9;
    private static final int PAD = 4;
    private static final int MINI_SZ = 18;
    private static final int MINI_ROW_H = 24;
    private static final int FOOTER_H = 30;
    /** 序号列（蓝图库没有这一列）。 */
    private static final int INDEX_W = 22;
    /** 名称列：加上序号列后仍让名称结束在蓝图库的同一 X 上（panelX+139）。 */
    private static final int NAME_W = 110;
    /** 备注列定宽 + 截断字符数，与蓝图库一致。 */
    private static final int NOTE_COL_W = 108;
    private static final int MAX_NAME_CHARS = 16;
    private static final int MAX_NOTE_CHARS = 16;
    /** 状态列（取代蓝图库的日期列），右对齐贴面板右缘。 */
    private static final int STATUS_W = 96;
    /** 被标记删除的行的灰（序号 / 名称 / 备注 / 状态与删除线都用它附近的值）。 */
    private static final int MARKED_GRAY = 0x666666;

    private final Player player;
    /** 打开时物品上的 projectId；预览开关按它记。 */
    @Nullable private final String projectId;
    private final String fallbackName;
    private Component displayTitle;

    private S2C_ProjectStatusMessage status;
    private long statusReceivedAtMillis = 0L;
    private int statusVersion = -1;
    private int scrollRow = 0;
    private int maxScrollRow = 0;

    // ── 布局 ──────────────────────────────────────────────────────────────
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int visibleRows;
    private int nameX;
    private int noteX;
    private int statusX;
    /** 列表行的左右缘：铺满面板内缘（左右各留 0；面板宽不变）。 */
    private int rowLeft;
    private int rowRight;
    private int miniRowY;

    // ── 选中 / 三类未提交暂存 ─────────────────────────────────────────────
    /** 选中的条目 index（服务端编号）；-1 = 未选中。 */
    private int selectedEntryIndex = -1;
    /** 本地缓存的顺序（条目 index 列表）；null = 与服务端一致。 */
    @Nullable private List<Integer> pendingOrder = null;
    /** 标记删除的条目序号（提交前不真删）。 */
    private final Set<Integer> pendingDeletes = new HashSet<>();
    /** 材料清单暂存：条目序号 → 改过的整份清单（只有改过的条目在表里）。 */
    private final Map<Integer, MaterialList> pendingMaterials = new HashMap<>();
    /** 打开材料界面前的起始清单；[确定] 回来比对用（与起始相同 → 不算改动）。 */
    @Nullable private MaterialList materialBase = null;
    private int materialBaseIndex = -1;
    /** 提交成功后选中项的新编号（等状态回来时套用）；-1 = 无。 */
    private int selectedAfterSubmit = -1;
    /** 刚点过 [提交修改]，等下一份状态回来对账（用标签序列确认是不是提交的结果）。 */
    private boolean awaitingSubmit = false;
    /** 提交时按新顺序算出的标签序列；状态回来的标签序列与它一致 = 提交被接受。 */
    @Nullable private List<String> expectedLabelsAfterSubmit = null;

    private Button upButton;
    private Button downButton;
    private Button editButton;
    private Button previewEntryButton;
    private Button materialButton;
    private Button deleteButton;
    private Button submitButton;
    /** ➤ 下载全部；每次 init() 重建，状态由下面三个字段驱动。 */
    @Nullable private Button downloadAllButton;
    /** 顺序下载队列是否在跑（点 ➤ 起、队列清空止）。 */
    private boolean downloadAllRunning = false;
    /** 队列当前在飞的 engId；null = 没挂请求。 */
    @Nullable private String downloadInFlight = null;
    /** 上一次发数据请求的时刻，服务端限速 500 ms / 玩家，留一点余量。 */
    private long downloadLastSentMs = 0L;
    /** 本轮点 ➤ 时要下载的份数，收尾通知用。 */
    private int downloadAllTotal = 0;
    private static final long DOWNLOAD_GAP_MS = 600L;

    /** 有未提交修改时点关闭 → 先弹提示（再点一次关闭按钮 / 选提交或放弃）。 */
    private boolean closePrompt = false;

    @Nullable private Component errorToast;
    /** 悬停 tooltip（蓝图库同款：按列挂）。同一时刻只可能命中一处。 */
    @Nullable private List<net.minecraft.util.FormattedCharSequence> hoveredNameTip;
    @Nullable private String hoveredNoteTip;
    /** 悬停备注列的详细备注（多行折行；e28 起优先于单行 noteTip）。 */
    @Nullable private List<net.minecraft.util.FormattedCharSequence> hoveredNoteTipLines;
    @Nullable private Component hoveredStatusTip;
    @Nullable private Component hoveredIndexTip;

    public ProjectBlueprintScreen(Player player, ItemStack stack) {
        super(Component.translatable(KEY_TITLE, titleName(stack)));
        this.player = player;
        this.projectId = ProjectBlueprintItem.getProjectId(stack);
        this.fallbackName = titleName(stack);
        this.displayTitle = Component.translatable(KEY_TITLE, fallbackName);
        ClientProjectStatusCache.request();
    }

    private static String titleName(ItemStack stack) {
        String name = ProjectBlueprintItem.getProjectName(stack);
        return name.isEmpty() ? stack.getHoverName().getString() : name;
    }

    @Override
    protected void init() {
        super.init();

        panelW = PANEL_W;
        int availableRows = (height - 20 - HEADER_H - MINI_ROW_H - FOOTER_H) / ROW_H;
        visibleRows = Math.max(3, Math.min(MAX_ROWS, availableRows));
        panelH = HEADER_H + visibleRows * ROW_H + MINI_ROW_H + FOOTER_H;
        panelX = (width - panelW) / 2;
        panelY = Math.max(8, (height - panelH) / 2);
        listX = panelX + PAD;
        listY = panelY + HEADER_H;
        listW = panelW - PAD * 2;
        rowLeft = panelX;
        rowRight = panelX + panelW;

        nameX = rowLeft + INDEX_W + 3;
        statusX = rowRight - STATUS_W;
        // 备注居中于面板中心（蓝图库同款），名字列结束位置也与蓝图库一致
        noteX = panelX + panelW / 2 - NOTE_COL_W / 2;
        miniRowY = listY + visibleRows * ROW_H + 3;

        if (closePrompt) {
            addClosePromptButtons(closePromptBounds());
            return;
        }

        List<S2C_ProjectStatusMessage.EntryStatus> entries = entries();
        maxScrollRow = Math.max(0, entries.size() - visibleRows);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScrollRow));

        // ── 迷你按钮行：左簇 上移/下移/设定/删除；右簇 预览/材料 + [提交修改]（右缘与底栏按钮对齐）──
        int bx = listX;
        upButton = addRenderableWidget(Button.builder(Component.literal("▲"), b -> moveSelected(-1))
                .bounds(bx, miniRowY, MINI_SZ, MINI_SZ).build());
        bx += MINI_SZ + 2;
        downButton = addRenderableWidget(Button.builder(Component.literal("▼"), b -> moveSelected(1))
                .bounds(bx, miniRowY, MINI_SZ, MINI_SZ).build());
        bx += MINI_SZ + 2;
        editButton = addRenderableWidget(Button.builder(Component.literal("✎"), b -> {})
                .bounds(bx, miniRowY, MINI_SZ, MINI_SZ).build());
        bx += MINI_SZ + 2;
        deleteButton = addRenderableWidget(Button.builder(Component.literal("✖"), b -> togglePendingDelete())
                .bounds(bx, miniRowY, MINI_SZ, MINI_SZ).build());

        // 右簇右对齐：右缘 = 底栏三按钮的右缘（面板内缘 − 4）
        Component submitLabel = Component.translatable("spatial_blueprint.gui.project_screen.btn_submit");
        int submitW = font.width(submitLabel) + 12;
        int rightEdge = rowRight - PAD;
        int submitX = rightEdge - submitW;
        int materialX = submitX - 2 - MINI_SZ;
        int previewX = materialX - 2 - MINI_SZ;
        previewEntryButton = addRenderableWidget(Button.builder(Component.literal("◎"), b -> toggleEntryPreview())
                .bounds(previewX, miniRowY, MINI_SZ, MINI_SZ).build());
        materialButton = addRenderableWidget(Button.builder(Component.literal("☄"), b -> openMaterialEditor())
                .bounds(materialX, miniRowY, MINI_SZ, MINI_SZ).build());
        submitButton = addRenderableWidget(Button.builder(submitLabel, b -> submitChanges())
                .bounds(submitX, miniRowY, submitW, MINI_SZ).build());

        // ── 底部：三个等宽按钮 [规划项目] [开启/关闭预览] [关闭] + 右侧两个方形迷你按钮 ➤ ✚ ──
        // 迷你按钮右缘与迷你行右簇一样贴列表右缘；三个等宽按钮分剩下的宽度。
        int footY = panelY + panelH - FOOTER_H + 6;
        int footGap = 4;
        int footW = (listW - footGap * 4 - MINI_SZ * 2) / 3;
        int footX = listX;

        boolean isOp = player.hasPermissions(2);
        // [规划项目] 预填用作者参考系（不是激活后的 claim 盒）；无效范围传 null
        boolean hasAuthorRange = status != null && status.found
                && status.authorSize.getX() > 0 && status.authorSize.getY() > 0
                && status.authorSize.getZ() > 0;
        Button planButton = addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_screen.btn_plan"),
                        b -> Minecraft.getInstance().setScreen(new ProjectRangeScreen(player, this,
                                status != null && status.found ? status.projectName : "",
                                hasAuthorRange ? status.authorOrigin : null,
                                hasAuthorRange ? status.authorSize : null,
                                // 范围界面编辑的是作者盒，中心点也给作者 anchor
                                status != null && status.found && status.hasAnchor ? status.authorAnchor : null)))
                .bounds(footX, footY, footW, 18)
                .tooltip(Tooltip.create(!isOp
                        ? Component.translatable("spatial_blueprint.gui.project_screen.tooltip_plan_op_only")
                        : Component.translatable("spatial_blueprint.gui.project_screen.tooltip_plan")))
                .build());
        // 仅 OP；激活后仍可扩建（只扩不缩）；状态没到之前先不放行
        planButton.active = isOp && status != null && status.found;
        footX += footW + footGap;

        boolean previewOn = ClientProjectPreview.isEnabled(projectId);
        boolean hasRange = status != null && status.found && status.hasRange;
        Button previewButton = addRenderableWidget(Button.builder(
                        Component.translatable(previewOn
                                ? "spatial_blueprint.gui.project_screen.btn_preview_off"
                                : "spatial_blueprint.gui.project_screen.btn_preview_on"),
                        b -> togglePreview())
                .bounds(footX, footY, footW, 18)
                .tooltip(Tooltip.create(hasRange
                        ? Component.translatable("spatial_blueprint.gui.project_screen.tooltip_preview")
                        : Component.translatable("spatial_blueprint.gui.project_screen.tooltip_preview_no_range")))
                .build());
        // 开着的允许随时关；没范围就没东西可预览，不让开
        previewButton.active = previewOn || hasRange;
        footX += footW + footGap;

        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_screen.btn_close"),
                        b -> onClose())
                .bounds(footX, footY, footW, 18)
                .build());
        footX += footW + footGap;

        // ➤ 下载全部子工程的蓝图数据到客户端缓存（材料编辑不用再「点一下等一下再点一下」）。
        // 服务端每玩家 500 ms 只收一个数据请求，所以是顺序队列：tick 里一次只挂一个，到货 / 失败再发下一个。
        // 可用性与 tooltip 在 updateMiniButtons 里按缓存状态每帧刷。
        downloadAllButton = addRenderableWidget(Button.builder(Component.literal("➤"), b -> startDownloadAll())
                .bounds(footX, footY, MINI_SZ, 18)
                .build());
        footX += MINI_SZ + footGap;

        // ✚ 蓝图列表（与放置 / 工程界面同款；应用会换掉手里的项目蓝图，与其它界面一致）
        addRenderableWidget(Button.builder(Component.literal("✚"),
                        b -> Minecraft.getInstance().setScreen(new BlueprintLibraryScreen(this)))
                .bounds(footX, footY, MINI_SZ, 18)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.placement.tooltip.library")))
                .build());

        updateMiniButtons();
    }

    // ── 关闭提示（有未提交修改时）─────────────────────────────────────────

    private int[] closePromptBounds() {
        int w = Math.min(240, panelW - 20);
        int h = 80;
        return new int[]{panelX + (panelW - w) / 2, panelY + (panelH - h) / 2, w, h};
    }

    private void addClosePromptButtons(int[] b) {
        int gap = 4;
        int bw = (b[2] - gap * 3) / 2;
        int by = b[1] + b[3] - MINI_SZ - 6;
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_screen.btn_submit"),
                        btn -> {
                            submitChanges();
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(b[0] + gap, by, bw, MINI_SZ).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_screen.close_pending.discard"),
                        btn -> {
                            clearPendingChanges();
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(b[0] + gap * 2 + bw, by, bw, MINI_SZ).build());
    }

    private void renderClosePrompt(GuiGraphics graphics) {
        int[] b = closePromptBounds();
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1] + b[3] + 1, 0xFF666666);
        graphics.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xFF161616);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.project_screen.close_pending.title"),
                b[0] + b[2] / 2, b[1] + 10, 0xFFFFAA);
        graphics.drawCenteredString(font,
                Component.translatable("spatial_blueprint.gui.project_screen.close_pending.hint"),
                b[0] + b[2] / 2, b[1] + 10 + font.lineHeight + 4, 0xAAAAAA);
    }

    // ── 顺序 / 暂存 + 提交 ────────────────────────────────────────────────

    private List<S2C_ProjectStatusMessage.EntryStatus> entries() {
        return status != null && status.found ? status.entries : List.of();
    }

    /** 服务端当前顺序（条目 index 列表）。 */
    private List<Integer> serverOrder() {
        List<Integer> order = new ArrayList<>();
        for (S2C_ProjectStatusMessage.EntryStatus entry : entries()) order.add(entry.index());
        return order;
    }

    /** 界面实际显示顺序：有缓存用缓存，否则用服务端顺序。 */
    private List<S2C_ProjectStatusMessage.EntryStatus> orderedEntries() {
        List<S2C_ProjectStatusMessage.EntryStatus> raw = entries();
        if (pendingOrder == null || raw.isEmpty()) return raw;
        Map<Integer, S2C_ProjectStatusMessage.EntryStatus> byIndex = new HashMap<>();
        for (S2C_ProjectStatusMessage.EntryStatus entry : raw) byIndex.put(entry.index(), entry);
        List<S2C_ProjectStatusMessage.EntryStatus> out = new ArrayList<>(raw.size());
        for (int index : pendingOrder) {
            S2C_ProjectStatusMessage.EntryStatus entry = byIndex.get(index);
            if (entry != null) out.add(entry);
        }
        // 兜底：缓存残缺（状态被别的界面改过）时把剩下的条目补上，列表不能少行
        for (S2C_ProjectStatusMessage.EntryStatus entry : raw) {
            if (!out.contains(entry)) out.add(entry);
        }
        return out;
    }

    private List<Integer> currentOrder() {
        if (pendingOrder != null) return List.copyOf(pendingOrder);
        return serverOrder();
    }

    private boolean orderChanged() {
        return pendingOrder != null && !pendingOrder.equals(serverOrder());
    }

    /** 三类暂存任一非空 = 有未提交修改。 */
    private boolean hasPendingChanges() {
        return orderChanged() || !pendingDeletes.isEmpty() || !pendingMaterials.isEmpty();
    }

    private void clearPendingChanges() {
        pendingOrder = null;
        pendingDeletes.clear();
        pendingMaterials.clear();
        materialBase = null;
        materialBaseIndex = -1;
        selectedAfterSubmit = -1;
    }

    /** 可编辑 = 项目没有任何任务集占用（服务端也会再校验一遍）。 */
    private boolean canEdit() {
        return status != null && status.found && status.orderEditable;
    }

    @Nullable
    private S2C_ProjectStatusMessage.EntryStatus selectedEntry() {
        if (selectedEntryIndex < 1) return null;
        for (S2C_ProjectStatusMessage.EntryStatus entry : orderedEntries()) {
            if (entry.index() == selectedEntryIndex) return entry;
        }
        return null;
    }

    private int selectedPositionInOrder() {
        return currentOrder().indexOf(selectedEntryIndex);
    }

    private void moveSelected(int delta) {
        if (!canEdit()) return;
        int pos = selectedPositionInOrder();
        if (pos < 0) return;
        List<Integer> order = new ArrayList<>(currentOrder());
        int target = pos + delta;
        if (target < 0 || target >= order.size()) return;
        Collections.swap(order, pos, target);
        pendingOrder = order;
    }

    private void submitChanges() {
        if (!canEdit() || !hasPendingChanges()) return;
        // order = 当前显示顺序里去掉删除标记后剩余的条目（提交后应存活的全部条目）
        List<Integer> order = new ArrayList<>();
        for (S2C_ProjectStatusMessage.EntryStatus entry : orderedEntries()) {
            if (!pendingDeletes.contains(entry.index())) order.add(entry.index());
        }
        List<String> labels = new ArrayList<>(order.size());
        Map<Integer, S2C_ProjectStatusMessage.EntryStatus> byIndex = new HashMap<>();
        for (S2C_ProjectStatusMessage.EntryStatus entry : entries()) byIndex.put(entry.index(), entry);
        for (int index : order) {
            S2C_ProjectStatusMessage.EntryStatus entry = byIndex.get(index);
            labels.add(entry == null || entry.label() == null ? "" : entry.label());
        }
        expectedLabelsAfterSubmit = labels;
        awaitingSubmit = true;
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_SubmitProjectChangesMessage(
                projectId, order, new ArrayList<>(pendingDeletes), new HashMap<>(pendingMaterials)));
        // 服务端会按新顺序整表重编号（删掉的条目消失）：选中项的新编号 = 它在提交顺序里的位置 + 1。
        int pos = order.indexOf(selectedEntryIndex);
        selectedAfterSubmit = pos >= 0 ? pos + 1 : -1;
    }

    /**
     * 状态刷新后的对账。
     *
     * <p>提交后：新状态的标签序列与提交时算的一致 = 被接受 → 清三类暂存 + 选中项换新编号；
     * 对不上 = 被驳回（服务端失败不会推状态）或收到的是别的变更 → 保留暂存。</p>
     *
     * <p>没提交时：服务端集合变了（别的界面增删了条目）→ 丢弃不再是排列的顺序缓存，
     * 以及序号已不存在的删除 / 材料暂存。</p>
     */
    private void reconcilePending() {
        List<Integer> server = serverOrder();

        if (awaitingSubmit) {
            awaitingSubmit = false;
            List<String> expected = expectedLabelsAfterSubmit;
            expectedLabelsAfterSubmit = null;
            if (expected != null && expected.equals(labelsOf(entries()))) {
                pendingOrder = null;
                pendingDeletes.clear();
                pendingMaterials.clear();
                if (selectedAfterSubmit >= 1) selectedEntryIndex = selectedAfterSubmit;
                selectedAfterSubmit = -1;
                if (selectedEntryIndex >= 1 && !server.contains(selectedEntryIndex)) {
                    selectedEntryIndex = -1;
                }
                return;
            }
        }

        if (pendingOrder != null) {
            Set<Integer> pendingSet = new HashSet<>(pendingOrder);
            Set<Integer> serverSet = new HashSet<>(server);
            if (pendingOrder.size() != server.size() || !pendingSet.equals(serverSet)) {
                pendingOrder = null;
                selectedAfterSubmit = -1;
            }
        }
        pendingDeletes.removeIf(index -> !server.contains(index));
        pendingMaterials.keySet().removeIf(index -> !server.contains(index));
        if (selectedEntryIndex >= 1 && !server.contains(selectedEntryIndex)) selectedEntryIndex = -1;
    }

    private static List<String> labelsOf(List<S2C_ProjectStatusMessage.EntryStatus> list) {
        List<String> labels = new ArrayList<>(list.size());
        for (S2C_ProjectStatusMessage.EntryStatus entry : list) {
            labels.add(entry.label() == null ? "" : entry.label());
        }
        return labels;
    }

    // ── 操作 ──────────────────────────────────────────────────────────────

    /** ✖：标记删除 / 撤销标记（提交前不真删、行灰掉 + 删除线）。 */
    private void togglePendingDelete() {
        S2C_ProjectStatusMessage.EntryStatus entry = selectedEntry();
        if (entry == null || !canEdit()) return;
        if (!pendingDeletes.remove(entry.index())) pendingDeletes.add(entry.index());
    }

    /** 预览：开 / 关选中子工程的预览线（可同时开多个；不发包）。 */
    private void toggleEntryPreview() {
        S2C_ProjectStatusMessage.EntryStatus entry = selectedEntry();
        if (entry == null || projectId == null) return;
        if (ClientSubProjectPreview.isEnabled(projectId, entry.index())) {
            ClientSubProjectPreview.toggle(projectId, entry, null);
            return;
        }
        // 距离预检：太远的话渲染器下一帧就会自动关掉，这里先提示、别让它白开
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        String dim = level != null ? level.dimension().location().toString() : null;
        net.minecraft.core.BlockPos min = entry.absMin();
        net.minecraft.core.BlockPos max = entry.absMax();
        if (min != null && max != null) {
            ClientSubProjectPreview.Box probe = new ClientSubProjectPreview.Box(min, max,
                    entry.color(), dim, entry.engId() == null ? "" : entry.engId());
            if (!ClientSubProjectPreview.withinRenderDistance(probe,
                    player.getX(), player.getY(), player.getZ())) {
                player.displayClientMessage(Component.translatable(
                        "spatial_blueprint.gui.project_screen.hint_preview_too_far"), false);
                return;
            }
        }
        ClientSubProjectPreview.toggle(projectId, entry, dim);
    }

    // ── ➤ 下载全部子工程数据（顺序队列）────────────────────────────────────

    /** 有 engId 的条目里，缓存还没有的（含失败的）。 */
    private List<String> pendingEngIds() {
        List<String> out = new ArrayList<>();
        for (S2C_ProjectStatusMessage.EntryStatus e : entries()) {
            String id = e.engId();
            if (id == null || id.isEmpty()) continue;
            if (ClientBlueprintCache.get(id) != null) continue;
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    private int engIdCount() {
        int n = 0;
        for (S2C_ProjectStatusMessage.EntryStatus e : entries()) {
            if (e.engId() != null && !e.engId().isEmpty()) n++;
        }
        return n;
    }

    /** 点 ➤：把上次失败的清掉重来，然后启动队列（真正发包在 tick 的 pumpDownloadQueue）。 */
    private void startDownloadAll() {
        if (downloadAllRunning) return;
        for (String id : pendingEngIds()) {
            if (ClientBlueprintCache.isFailed(id)) ClientBlueprintCache.clearFailed(id);
        }
        downloadAllRunning = true;
        downloadInFlight = null;
        downloadAllTotal = pendingEngIds().size();
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[ProjectUI] download-all start pid={} pending={}", projectId, downloadAllTotal);
        // 开始：聊天栏一行（灰），不用横幅 —— 横幅要点掉，会挡住正在看的列表
        player.displayClientMessage(Component.translatable(
                "spatial_blueprint.gui.project_screen.download_all.started", downloadAllTotal), false);
        pumpDownloadQueue();
    }

    /**
     * 队列跑完的收尾通知（T9：失败走横幅，成功走聊天栏）：
     * 有失败 → 红横幅「k 份下载失败」+ 第一条原因 + 错误音；全到 → 聊天栏绿字 + 提示音。
     */
    private void finishDownloadAll() {
        downloadAllRunning = false;
        List<String> pending = pendingEngIds();
        int failed = 0;
        String firstReason = null;
        for (String id : pending) {
            if (!ClientBlueprintCache.isFailed(id)) continue;
            failed++;
            if (firstReason == null) firstReason = ClientBlueprintCache.getFailReason(id);
        }
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[ProjectUI] download-all done pid={} total={} failed={} stillPending={}",
                projectId, downloadAllTotal, failed, pending.size());
        if (failed > 0) {
            errorToast = Component.translatable("spatial_blueprint.gui.project_screen.download_all.failed",
                    failed, downloadAllTotal,
                    Component.translatable(firstReason != null ? firstReason : "spatial_blueprint.error.download_failed"));
            NotifySound.clientError();
        } else {
            NotifySound.client();
            player.displayClientMessage(Component.translatable(
                    "spatial_blueprint.gui.project_screen.download_all.done", downloadAllTotal), false);
        }
    }

    /**
     * 队列推进：在飞的到货 / 失败就放下；没在飞、且距上次发送 ≥ {@link #DOWNLOAD_GAP_MS}，
     * 就挑下一份还没缓存、也没被别处（☄）请求中的发出去；没得发了就停。
     * 失败的这一轮不重试（留给下一次点 ➤），否则一份坏文件会让队列原地打转。
     */
    private void pumpDownloadQueue() {
        if (!downloadAllRunning) return;
        if (downloadInFlight != null) {
            boolean arrived = ClientBlueprintCache.get(downloadInFlight) != null;
            boolean failed  = ClientBlueprintCache.isFailed(downloadInFlight);
            if (!arrived && !failed && ClientBlueprintCache.isDownloading(downloadInFlight)) return;
            downloadInFlight = null;
        }
        long now = System.currentTimeMillis();
        if (now - downloadLastSentMs < DOWNLOAD_GAP_MS) return;
        for (String id : pendingEngIds()) {
            if (ClientBlueprintCache.isFailed(id)) continue;
            if (ClientBlueprintCache.isDownloading(id)) return;   // ☄ 已经在请求它：等它，别并发
            ClientBlueprintCache.startDownload(id);
            BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestBlueprintDataMessage(id));
            downloadInFlight = id;
            downloadLastSentMs = now;
            return;
        }
        finishDownloadAll();
    }

    @Override
    public void tick() {
        super.tick();
        if (downloadAllRunning) pumpDownloadQueue();
    }

    /** ➤ 的可用性与 tooltip（每帧由 updateMiniButtons 调）。 */
    private void updateDownloadAllButton() {
        if (downloadAllButton == null) return;
        int total = engIdCount();
        List<String> pending = pendingEngIds();
        int failed = 0;
        for (String id : pending) if (ClientBlueprintCache.isFailed(id)) failed++;
        Component tip;
        boolean active;
        if (total == 0) {
            tip = Component.translatable("spatial_blueprint.gui.project_screen.tooltip_download_all_none");
            active = false;
        } else if (downloadAllRunning) {
            tip = Component.translatable("spatial_blueprint.gui.project_screen.tooltip_download_all_running",
                    total - pending.size(), total);
            active = false;
        } else if (pending.isEmpty()) {
            tip = Component.translatable("spatial_blueprint.gui.project_screen.tooltip_download_all_done", total);
            active = false;
        } else if (failed > 0) {
            tip = Component.translatable("spatial_blueprint.gui.project_screen.tooltip_download_all_retry",
                    pending.size(), failed);
            active = true;
        } else {
            tip = Component.translatable("spatial_blueprint.gui.project_screen.tooltip_download_all",
                    pending.size());
            active = true;
        }
        downloadAllButton.active = active;
        downloadAllButton.setTooltip(Tooltip.create(tip));
    }

    /**
     * 材料：打开选中子工程的材料编辑界面（改的是本地暂存，随 [提交修改] 落盘）。
     * 缓存里没有子工程数据就先请求下载：下载中按钮置灰，到了再点一次；失败弹横幅。
     */
    private void openMaterialEditor() {
        S2C_ProjectStatusMessage.EntryStatus entry = selectedEntry();
        if (entry == null || !canEdit()) return;
        int index = entry.index();

        MaterialList staged = pendingMaterials.get(index);
        if (staged != null) {
            BlueprintData data = ClientBlueprintCache.get(entry.engId());
            materialBase = data != null && data.materialList != null ? data.materialList : null;
            materialBaseIndex = index;
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                    "[ProjectUI] material editor open pid={} index={} from=staged", projectId, index);
            Minecraft.getInstance().setScreen(new SubProjectMaterialScreen(this, entry, staged.copy()));
            return;
        }

        String engId = entry.engId();
        if (engId.isEmpty()) {
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                    "[ProjectUI] material click pid={} index={} skipped=noEngId", projectId, index);
            return;
        }
        BlueprintData data = ClientBlueprintCache.get(engId);
        if (data != null) {
            materialBase = data.materialList != null ? data.materialList : new MaterialList();
            materialBaseIndex = index;
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                    "[ProjectUI] material editor open pid={} index={} from=cache engId={}", projectId, index, engId);
            Minecraft.getInstance().setScreen(new SubProjectMaterialScreen(this, entry,
                    data.materialList != null ? data.materialList.copy() : new MaterialList()));
            return;
        }
        if (ClientBlueprintCache.isFailed(engId)) {
            String reason = ClientBlueprintCache.getFailReason(engId);
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                    "[ProjectUI] material click pid={} index={} engId={} downloadFailed={}", projectId, index, engId, reason);
            errorToast = Component.translatable(reason != null
                    ? reason : "spatial_blueprint.error.download_failed");
            NotifySound.clientError();
            ClientBlueprintCache.clearFailed(engId);
            return;
        }
        // 第一次点只是发起下载（不弹界面）：按钮会置灰，到货后再点一次；横幅提示（点一下 / ESC 关掉）
        ClientBlueprintCache.startDownload(engId);
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_RequestBlueprintDataMessage(engId));
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[ProjectUI] material click pid={} index={} engId={} -> download started", projectId, index, engId);
        errorToast = Component.translatable(
                "spatial_blueprint.gui.project_screen.hint_material_downloading");
    }

    /** 子工程材料界面 [确定] 的回调：与起始清单一致 → 撤销暂存；不一致 → 存副本。 */
    void applyMaterialEdit(int entryIndex, MaterialList edited) {
        MaterialList base = materialBaseIndex == entryIndex ? materialBase : null;
        if (base != null && sameMaterials(base, edited)) {
            pendingMaterials.remove(entryIndex);
        } else {
            pendingMaterials.put(entryIndex, edited.copy());
        }
        materialBase = null;
        materialBaseIndex = -1;
    }

    private static boolean sameMaterials(MaterialList a, MaterialList b) {
        return Objects.equals(a.blockCounts, b.blockCounts)
                && Objects.equals(a.keyTags, b.keyTags);
    }

    private void togglePreview() {
        if (projectId == null) return;
        if (ClientProjectPreview.isEnabled(projectId)) {
            ClientProjectPreview.disable(projectId);
        } else {
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            String dim = level != null ? level.dimension().location().toString() : null;
            ClientProjectPreview.enable(projectId,
                    status != null && status.found && status.hasRange
                            ? new ClientProjectPreview.Range(status.origin, status.size, dim,
                                    status.hasAnchor ? status.worldAnchor : null)
                            : null,
                    dim);
        }
        rebuildWidgets();
    }

    private void refreshFromCache() {
        status = ClientProjectStatusCache.get();
        statusReceivedAtMillis = ClientProjectStatusCache.receivedAtMillis();
        // 迟到守卫（e34）：界面开着时项目被删 / 蓝图指向的项目已不存在 → 关掉界面并弹横幅
        if (status != null && !status.found && projectId != null && projectId.equals(status.projectId)) {
            com.xioyim.spatialblueprint.util.NotifySound.clientError();
            com.xioyim.spatialblueprint.gui.ErrorToastScreen.showExclusive(Component.translatable(
                    "spatial_blueprint.message.project.missing_blocked"));
            return;
        }
        // 迟到守卫（e37）：右键时物品还没同步过维度（旧物品）放行了，状态一到发现不在项目维度 → 关界面 + 横幅
        if (status != null && status.found && projectId != null && projectId.equals(status.projectId)
                && !status.dimension.isEmpty() && Minecraft.getInstance().level != null
                && !status.dimension.equals(ProjectData.dimensionOf(Minecraft.getInstance().level))) {
            com.xioyim.spatialblueprint.util.NotifySound.clientError();
            com.xioyim.spatialblueprint.gui.ErrorToastScreen.showExclusive(
                    ProjectBlueprintItem.wrongDimensionMessage(status.dimension));
            return;
        }
        String name = status != null && status.found && !status.projectName.isEmpty()
                ? status.projectName : fallbackName;
        displayTitle = Component.translatable(KEY_TITLE, name);
        reconcilePending();
        rebuildWidgets();
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int currentVersion = ClientProjectStatusCache.version();
        if (currentVersion != statusVersion) {
            statusVersion = currentVersion;
            refreshFromCache();
        }
        if (!closePrompt) updateMiniButtons();
        hoveredNameTip = null;
        hoveredNoteTip = null;
        hoveredNoteTipLines = null;
        hoveredStatusTip = null;
        hoveredIndexTip = null;

        renderBackground(graphics);

        if (errorToast != null) {
            ErrorToast.render(graphics, font, width, height, errorToast);
            return;
        }

        graphics.fill(panelX - 1, panelY - 1,
                panelX + panelW + 1, panelY + panelH + 1, 0xFF666666);
        graphics.fill(panelX, panelY,
                panelX + panelW, panelY + panelH, 0xFF161616);
        graphics.fill(panelX, panelY,
                panelX + panelW, panelY + HEADER_H, 0xFF101010);
        graphics.fill(panelX, listY + visibleRows * ROW_H,
                panelX + panelW, panelY + panelH, 0xFF101010);

        graphics.drawCenteredString(font, displayTitle,
                panelX + panelW / 2,
                panelY + (HEADER_H - font.lineHeight) / 2, 0xFFFFAA);

        if (closePrompt) {
            renderClosePrompt(graphics);
        } else {
            renderRows(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!closePrompt) {
            if (hoveredNameTip != null) {
                graphics.renderTooltip(font, hoveredNameTip, mouseX, mouseY);
            } else if (hoveredIndexTip != null) {
                graphics.renderTooltip(font, hoveredIndexTip, mouseX, mouseY);
            } else if (hoveredNoteTipLines != null) {
                graphics.renderTooltip(font, hoveredNoteTipLines, mouseX, mouseY);
            } else if (hoveredNoteTip != null) {
                graphics.renderTooltip(font, Component.literal(hoveredNoteTip), mouseX, mouseY);
            } else if (hoveredStatusTip != null) {
                graphics.renderTooltip(font, hoveredStatusTip, mouseX, mouseY);
            }
        }
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        List<S2C_ProjectStatusMessage.EntryStatus> entries = orderedEntries();
        if (status == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("spatial_blueprint.gui.project_screen.loading"),
                    panelX + panelW / 2,
                    listY + visibleRows * ROW_H / 2 - font.lineHeight / 2, 0xAAAAAA);
            return;
        }
        if (!status.found || entries.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("spatial_blueprint.gui.project_screen.empty"),
                    panelX + panelW / 2,
                    listY + visibleRows * ROW_H / 2 - font.lineHeight / 2, 0x888888);
            return;
        }

        for (int slot = 0; slot < visibleRows; slot++) {
            int entryPos = scrollRow + slot;
            if (entryPos >= entries.size()) break;
            S2C_ProjectStatusMessage.EntryStatus entry = entries.get(entryPos);
            int rowY = listY + slot * ROW_H;
            int textY = rowY + (ROW_H - font.lineHeight) / 2;
            boolean isSelected = entry.index() == selectedEntryIndex;
            boolean marked = pendingDeletes.contains(entry.index());

            // 行背景：选中行较亮（蓝图库同款）；铺满面板内缘（用户要求：列表行不留左右内边距）
            graphics.fill(rowLeft, rowY, rowRight, rowY + ROW_H,
                    isSelected ? 0x30FFFFFF : (slot % 2 == 0 ? 0x18FFFFFF : 0x08FFFFFF));

            // 序号（= 显示顺序；用条目线框颜色着色，被标记删除时灰掉；悬停出颜色块 tooltip）
            int indexColor = marked ? MARKED_GRAY : (entry.color() | 0xFF000000);
            graphics.drawCenteredString(font, String.valueOf(entryPos + 1),
                    rowLeft + INDEX_W / 2, textY, indexColor);
            if (mouseX >= rowLeft && mouseX < rowLeft + INDEX_W
                    && mouseY >= textY && mouseY < textY + font.lineHeight) {
                hoveredIndexTip = Component.literal("██").withStyle(Style.EMPTY
                        .withColor(TextColor.fromRgb(entry.color() & 0xFFFFFF)));
            }

            // 名称（字符 16 / 像素 110 两道截断，蓝图库同规则；悬停出完整摘要；标记删除加删除线）
            String rawName = entry.label().isEmpty()
                    ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                    : entry.label();
            String nameDisplay = truncate(truncateChars(rawName, MAX_NAME_CHARS), NAME_W);
            int nameColor = marked ? MARKED_GRAY : 0xEEEEEE;
            graphics.drawString(font, nameDisplay, nameX, textY, nameColor, false);
            int nameDrawnW = font.width(nameDisplay);
            if (marked) {
                int strikeY = textY + font.lineHeight / 2;
                graphics.fill(nameX, strikeY, nameX + nameDrawnW, strikeY + 1, 0xFF888888);
            }
            if (mouseX >= nameX && mouseX < nameX + nameDrawnW
                    && mouseY >= textY && mouseY < textY + font.lineHeight) {
                hoveredNameTip = entryTooltip(entry);
            }

            // 备注（居中于面板中心，蓝图库同款；空显示「无备注」）。
            // 悬停备注列：有详细备注 → 出详细备注（多行折行）；否则截断出全文（与蓝图库同款）
            String note = entry.note();
            String noteDisplay = note.isEmpty()
                    ? Component.translatable("spatial_blueprint.gui.library.note.none").getString()
                    : note;
            String noteFull = noteDisplay;
            noteDisplay = truncate(truncateChars(noteDisplay, MAX_NOTE_CHARS), NOTE_COL_W);
            int noteDrawnW = font.width(noteDisplay);
            int noteDrawX = noteX + (NOTE_COL_W - noteDrawnW) / 2;
            graphics.drawString(font, noteDisplay, noteDrawX, textY, marked ? MARKED_GRAY : 0x888888, false);
            if (mouseX >= noteDrawX && mouseX < noteDrawX + noteDrawnW
                    && mouseY >= textY && mouseY < textY + font.lineHeight) {
                if (!entry.noteLong().isEmpty()) {
                    hoveredNoteTipLines = font.split(
                            Component.literal(entry.noteLong()).withStyle(ChatFormatting.GRAY), 200);
                } else if (!noteDisplay.equals(noteFull)) {
                    hoveredNoteTip = noteFull;
                }
            }

            // 状态（右对齐贴行右缘；悬停出状态说明，对应蓝图库的日期列）
            Component statusText = rowStatus(entry);
            if (marked) statusText = statusText.copy().withStyle(ChatFormatting.DARK_GRAY);
            int statusTextW = font.width(statusText);
            int statusDrawX = Math.max(statusX, rowRight - 3 - statusTextW);
            graphics.enableScissor(statusX, rowY, rowRight, rowY + ROW_H);
            graphics.drawString(font, statusText, statusDrawX, textY, 0xFFFFFF, false);
            graphics.disableScissor();
            if (mouseX >= statusDrawX && mouseX < statusDrawX + statusTextW
                    && mouseY >= textY && mouseY < textY + font.lineHeight) {
                hoveredStatusTip = statusTooltip(entry);
            }

            // 选中边框（白色 1px，蓝图库同款）
            if (isSelected) {
                int sc = 0xFFFFFFFF;
                graphics.fill(rowLeft,  rowY,             rowRight,    rowY + 1,         sc);
                graphics.fill(rowLeft,  rowY + ROW_H - 1, rowRight,    rowY + ROW_H,     sc);
                graphics.fill(rowLeft,  rowY,             rowLeft + 1, rowY + ROW_H,     sc);
                graphics.fill(rowRight - 1, rowY,         rowRight,    rowY + ROW_H,     sc);
            }
        }
    }

    /**
     * 名称 tooltip：统一的标准显示模式（与蓝图库 / 材料清单标题同款标签）——
     * 名字 / 材料清单（方块数）/ 构建耗时 / 创建玩家 / 创建时间。
     * 详细备注不在 tooltip 里 —— 悬停行的备注文本才出（见 {@link #renderRows}）。
     */
    private List<net.minecraft.util.FormattedCharSequence> entryTooltip(
            S2C_ProjectStatusMessage.EntryStatus entry) {
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        String name = entry.label().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : entry.label();
        lines.add(Component.literal(name).withStyle(ChatFormatting.WHITE).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.block_count",
                        entry.blockCount() < 0 ? "-" : String.valueOf(entry.blockCount()))
                .withStyle(ChatFormatting.GREEN).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.anim_duration",
                        entry.animDuration() > 0 ? String.valueOf(entry.animDuration()) : "-")
                .withStyle(ChatFormatting.AQUA).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.creator",
                        entry.creatorName().isEmpty() ? "-" : entry.creatorName())
                .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.created_at",
                        BlueprintTooltips.formatDateFull(entry.createdAt()))
                .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        return lines;
    }

    /** 每帧刷新迷你按钮：可用性、tooltip，以及预览按钮的颜色 / 材料按钮的下载态（按字段恢复，重建控件不丢）。 */
    private void updateMiniButtons() {
        if (upButton == null) return;
        updateDownloadAllButton();

        S2C_ProjectStatusMessage.EntryStatus sel = selectedEntry();
        boolean hasSel = sel != null;
        boolean editable = canEdit();
        // 规划模式是 OP 专用界面（建造模式是另一套 UI）：迷你按钮一律加 OP 门禁
        boolean isOp = player.hasPermissions(2);
        int pos = selectedPositionInOrder();
        int size = orderedEntries().size();

        Component lockedTip = Component.translatable(
                "spatial_blueprint.gui.project_screen.tooltip_order_locked");
        Component selectRowTip = Component.translatable(
                "spatial_blueprint.gui.project_screen.tooltip_select_row");
        upButton.active = isOp && hasSel && editable && pos > 0;
        upButton.setTooltip(Tooltip.create(!hasSel ? selectRowTip : (editable
                ? Component.translatable("spatial_blueprint.gui.project_screen.tooltip_move_up")
                : lockedTip)));
        downButton.active = isOp && hasSel && editable && pos >= 0 && pos < size - 1;
        downButton.setTooltip(Tooltip.create(!hasSel ? selectRowTip : (editable
                ? Component.translatable("spatial_blueprint.gui.project_screen.tooltip_move_down")
                : lockedTip)));

        // 设定子工程：界面还没做，先只占位（恒灰）
        editButton.active = false;
        editButton.setTooltip(Tooltip.create(Component.translatable(
                "spatial_blueprint.gui.project_screen.tooltip_edit_entry")));

        // 预览：必须项目总预览开着（下面的 [开启预览]），且选中行才可用；开着时文字用条目颜色
        boolean projectPreviewOn = projectId != null && ClientProjectPreview.isEnabled(projectId);
        boolean entryPreviewOn = hasSel && projectPreviewOn
                && ClientSubProjectPreview.isEnabled(projectId, sel.index());
        previewEntryButton.active = isOp && hasSel && projectPreviewOn;
        // setFGColor 只收 int：三元给 null 会在拆箱时 NPE（验收 SB-008 时抓到），关着时走 clearFGColor
        if (entryPreviewOn) previewEntryButton.setFGColor(sel.color() & 0xFFFFFF);
        else previewEntryButton.clearFGColor();
        previewEntryButton.setTooltip(Tooltip.create(!hasSel ? selectRowTip
                : (!projectPreviewOn
                        ? Component.translatable("spatial_blueprint.gui.project_screen.tooltip_preview_needs_project")
                        : Component.translatable(entryPreviewOn
                                ? "spatial_blueprint.gui.project_screen.tooltip_preview_entry_off"
                                : "spatial_blueprint.gui.project_screen.tooltip_preview_entry_on"))));

        // 材料：选中 + 可编辑；数据下载中置灰（到了再点一次，不自动弹界面）
        boolean staged = hasSel && pendingMaterials.containsKey(sel.index());
        boolean hasData = hasSel && !sel.engId().isEmpty()
                && ClientBlueprintCache.get(sel.engId()) != null;
        boolean downloading = hasSel && !staged && !hasData && !sel.engId().isEmpty()
                && ClientBlueprintCache.isDownloading(sel.engId());
        materialButton.active = isOp && hasSel && editable && !downloading;
        materialButton.setTooltip(Tooltip.create(!hasSel ? selectRowTip : Component.translatable(downloading
                ? "spatial_blueprint.gui.project_screen.tooltip_material_downloading"
                : "spatial_blueprint.gui.project_screen.tooltip_material_entry")));

        // ✖ 标记删除 / 撤销标记
        boolean marked = hasSel && pendingDeletes.contains(sel.index());
        deleteButton.active = isOp && hasSel && editable;
        deleteButton.setMessage(marked
                ? Component.literal("✖").withStyle(ChatFormatting.RED)
                : Component.literal("✖"));
        deleteButton.setTooltip(Tooltip.create(!hasSel ? selectRowTip : Component.translatable(marked
                ? "spatial_blueprint.gui.project_screen.tooltip_unmark_delete"
                : "spatial_blueprint.gui.project_screen.tooltip_mark_delete")));

        boolean pending = hasPendingChanges();
        submitButton.active = isOp && editable && pending;
        submitButton.setTooltip(Tooltip.create(Component.translatable(pending
                ? "spatial_blueprint.gui.project_screen.tooltip_submit"
                : "spatial_blueprint.gui.project_screen.tooltip_submit_no_changes")));
    }

    /** 状态列（与名称 tooltip 的状态行）的说明；激活态本界面不会打开，防御性兜底。 */
    private Component statusTooltip(@Nullable S2C_ProjectStatusMessage.EntryStatus entry) {
        if (entry == null || status == null || !status.activated) {
            return Component.translatable("spatial_blueprint.gui.project_screen.tooltip_inactive");
        }
        return rowStatus(entry);
    }

    private Component rowStatus(S2C_ProjectStatusMessage.EntryStatus entry) {
        if (status == null || !status.activated) {
            // 规划模式专属状态：该子工程的预览线开着 → 「未锁定(预览中)」，文字用预览线同色
            if (ClientSubProjectPreview.isEnabled(projectId, entry.index())) {
                return Component.translatable("spatial_blueprint.gui.project_screen.status_previewing")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(entry.color() & 0xFFFFFF)));
            }
            return Component.translatable("spatial_blueprint.gui.project_screen.status_inactive")
                    .withStyle(ChatFormatting.GRAY);
        }
        if (entry.completed()) {
            return Component.translatable("spatial_blueprint.gui.project_screen.status_completed")
                    .withStyle(ChatFormatting.GREEN);
        }
        if (!entry.prereqMet()) {
            return Component.translatable("spatial_blueprint.gui.project_screen.status_prereq",
                            entry.requiresEntry())
                    .withStyle(ChatFormatting.GRAY);
        }
        int cooldown = remainingCooldown(entry);
        if (cooldown > 0) {
            return Component.translatable("spatial_blueprint.gui.project_screen.status_cooldown",
                            formatCooldown(cooldown))
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.translatable("spatial_blueprint.gui.project_screen.status_available")
                .withStyle(ChatFormatting.WHITE);
    }

    private int remainingCooldown(S2C_ProjectStatusMessage.EntryStatus entry) {
        int initialSeconds = Math.max(0, entry.cooldownRemainSec());
        if (initialSeconds == 0 || statusReceivedAtMillis <= 0) return initialSeconds;
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - statusReceivedAtMillis);
        long remainingMs = Math.max(0L, initialSeconds * 1000L - elapsedMs);
        return (int) ((remainingMs + 999L) / 1000L);
    }

    private static String formatCooldown(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** 与蓝图库同款：先按字符数截断（省略号计一个字符之外）。 */
    private static String truncateChars(String s, int maxChars) {
        if (s.codePointCount(0, s.length()) <= maxChars) return s;
        return s.substring(0, s.offsetByCodePoints(0, maxChars)) + "…";
    }

    /** 与蓝图库同款：像素宽度兜底截断。 */
    private String truncate(String s, int maxPx) {
        if (font.width(s) <= maxPx) return s;
        while (s.length() > 1 && font.width(s + "…") > maxPx) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ── 输入 ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) {
            errorToast = null;
            return true;
        }
        if (closePrompt) {
            int[] b = closePromptBounds();
            if (mouseX < b[0] || mouseX >= b[0] + b[2] || mouseY < b[1] || mouseY >= b[1] + b[3]) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && mouseX >= rowLeft && mouseX < rowRight
                && mouseY >= listY && mouseY < listY + visibleRows * ROW_H) {
            int slot = (int) ((mouseY - listY) / ROW_H);
            S2C_ProjectStatusMessage.EntryStatus entry = entryAt(slot);
            if (entry != null) {
                selectedEntryIndex = entry.index() == selectedEntryIndex ? -1 : entry.index();
                com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                        "[ProjectUI] select pid={} index={}", projectId, selectedEntryIndex);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        if (closePrompt) {
            if (keyCode == 256) {
                closePrompt = false;
                rebuildWidgets();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private S2C_ProjectStatusMessage.EntryStatus entryAt(int slot) {
        List<S2C_ProjectStatusMessage.EntryStatus> entries = orderedEntries();
        int index = scrollRow + slot;
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (errorToast != null || closePrompt) return true;
        if (mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + visibleRows * ROW_H) {
            scrollRow = Math.max(0, Math.min(maxScrollRow,
                    scrollRow - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (!closePrompt && hasPendingChanges()) {
            closePrompt = true;
            rebuildWidgets();
            return;
        }
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
