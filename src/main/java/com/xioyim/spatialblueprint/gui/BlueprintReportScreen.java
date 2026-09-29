package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_RequestIllegalBlocksMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图检测报告界面。
 *
 * <p>套用 ErrorToast 横幅模板样式（全屏遮罩 + 深色横幅 + 上下边缘线）；外置标题按检测
 * 结果着色（红=危险 / 黄=注意 / 绿=安全）；主内容区支持滚轮逐排滚动。内容包含结构状态、
 * 权限检测、非法方块网格、危险方块网格。底部提供 [继续创建] [取消创建] [OP强制创建]。</p>
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintReportScreen extends Screen {

    /**
     * 报告状态：安全（绿）/ 注意（黄）/ 超额（黄）/ 危险（红）。
     *
     * <p>{@code OVER_LIMIT} 只有 OP 会看到：方块数超过这种蓝图的类型上限、但选区里
     * 没有非法/危险方块。类型上限对 OP 只是提示（服务端
     * {@code validateCaptureArea} 对 OP 放到硬底线），所以标题黄字提醒、
     * 「继续创建」照常可点。非 OP 超额仍是 {@code DANGER}。</p>
     */
    private enum ReportState { SAFE, WARN, OVER_LIMIT, DANGER }

    // ── 样式常量 ─────────────────────────────────────────────────────────
    private static final int BANNER_H = 240;
    private static final int TITLE_GAP = 15;
    private static final int OVERFLOW = 24;
    private static final int LINE_H = 20;
    private static final int BLOCK_ROW_H = 20;
    private static final int CONTENT_PAD = 14;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 6;
    private static final int CELL = 20;
    private static final int CELL_GAP = 3;
    private static final int CELLS_PER_ROW = 10;

    // 已知崩溃物品集合（BEWLR 破损等），首次抛异常后加入，后续帧改用 BARRIER 占位
    private static final java.util.Set<String> KNOWN_CRASH =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static final int COLOR_RED    = 0xFF5555;
    private static final int COLOR_YELLOW = 0xFFFF55;
    private static final int COLOR_GREEN  = 0x55FF55;
    private static final int COLOR_WHITE  = 0xFFFFFF;
    private static final int COLOR_CYAN   = 0x55FFFF;

    private final ReportHost host;
    private final Player player;
    private final int blockCount;
    private final BlockPos p1;
    private final BlockPos p2;

    private final boolean isOp;
    /** 两个 OP 强制创建按钮的启用前提：OP。 */
    private final boolean opAllowed;
    private final boolean isCreative;
    private final int limit;
    private ReportState state;

    private int scrollOffset = 0;

    /** [调试] 上次打印行布局日志时的滚动偏移：只在滚动变化时打一次，避免每帧刷屏。 */
    private int loggedScroll = Integer.MIN_VALUE;

    // ── 服务端下发（非法方块 + 危险方块 + 特殊实体）─────────────────────────
    private final long requestTime;
    private List<S2C_IllegalBlocksListMessage.IllegalBlock> illegalBlocks = List.of();
    private List<S2C_IllegalBlocksListMessage.IllegalBlock> dangerousBlocks = List.of();
    private List<S2C_IllegalBlocksListMessage.SpecialEntity> specialEntities = List.of();
    private List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions = List.of();
    private List<String> freeBlocks = List.of();
    private boolean received = false;
    /** 每帧重建：当前帧各方块方框的屏幕矩形，供 hover / 点击。 */
    private final List<CellRect> cellRects = new ArrayList<>();
    private record CellRect(int x, int y, int w, int h,
                            S2C_IllegalBlocksListMessage.IllegalBlock entry, boolean dangerous) {}
    /** 特殊实体单元格矩形。 */
    private final List<SpecialCellRect> specialCellRects = new ArrayList<>();
    private record SpecialCellRect(int x, int y, int w, int h,
                                   S2C_IllegalBlocksListMessage.SpecialEntity entry) {}
    /** 转换 / 免费格子矩形：tooltip 文本已在渲染时算好，无点击行为（不对应具体坐标）。 */
    private final List<InfoCellRect> infoCellRects = new ArrayList<>();
    private record InfoCellRect(int x, int y, int w, int h, Component tip) {}
    /** 特殊实体结论行 Y 坐标（hover 显示 tooltip），-1 = 未渲染。 */
    private int specialConclusionY = -1;

    private enum RowKind { TEXT, BLOCK_GRID, SPECIAL_GRID, CONVERSION, FREE_GRID }

    private record ContentRow(RowKind kind, Component text, int color,
                              List<S2C_IllegalBlocksListMessage.IllegalBlock> grid, boolean dangerous,
                              List<S2C_IllegalBlocksListMessage.SpecialEntity> specialGrid,
                              S2C_IllegalBlocksListMessage.ConversionEntry conversion,
                              List<String> freeGrid) {

        static ContentRow text(Component text, int color) {
            return new ContentRow(RowKind.TEXT, text, color, List.of(), false, null, null, null);
        }

        static ContentRow blockGrid(List<S2C_IllegalBlocksListMessage.IllegalBlock> grid, boolean dangerous) {
            return new ContentRow(RowKind.BLOCK_GRID, null, 0, grid, dangerous, null, null, null);
        }

        static ContentRow specialGrid(List<S2C_IllegalBlocksListMessage.SpecialEntity> grid) {
            return new ContentRow(RowKind.SPECIAL_GRID, null, 0, List.of(), false, grid, null, null);
        }

        static ContentRow conversion(S2C_IllegalBlocksListMessage.ConversionEntry entry) {
            return new ContentRow(RowKind.CONVERSION, null, 0, List.of(), false, null, entry, null);
        }

        static ContentRow freeGrid(List<String> ids) {
            return new ContentRow(RowKind.FREE_GRID, null, 0, List.of(), false, null, null, ids);
        }

        boolean isGrid() { return kind != RowKind.TEXT; }
    }

    /** 分隔线行（各区块之间）。 */
    private static ContentRow separator() {
        return ContentRow.text(Component.literal("----------------------------------------"), 0x888888);
    }

    /** 分类 → 显示名 lang key，用于标题「XX蓝图检测报告」。 */
    private static String categoryNameKey(String category) {
        return switch (category == null ? "" : category) {
            case "project"     -> "spatial_blueprint.gui.create_blueprint.btn.projection";
            case "dimensional" -> "spatial_blueprint.gui.create_blueprint.btn.void";
            case "engineering" -> "spatial_blueprint.gui.create_blueprint.btn.engineering";
            case "subproject"  -> "spatial_blueprint.gui.subproject.title";
            default            -> "spatial_blueprint.gui.create_blueprint.btn.spatial";
        };
    }

    /** 本次要创建的蓝图分类（由「选择创建类型」界面传入），决定标题与体积上限。 */
    private final String category;

    public BlueprintReportScreen(ReportHost host, Player player, int blockCount,
                                 String category) {
        super(Component.translatable("spatial_blueprint.gui.report.title",
                Component.translatable(categoryNameKey(category))));
        this.host = host;
        this.player = player;
        this.blockCount = blockCount;
        this.category = category == null ? "create" : category;

        BlockPos rawP1 = host.point1();
        BlockPos rawP2 = host.point2();
        // 防御：未选点时不进入此界面（创建按钮已拦截）；此处兜底避免 NPE
        this.p1 = rawP1 != null ? rawP1 : BlockPos.ZERO;
        this.p2 = rawP2 != null ? rawP2 : BlockPos.ZERO;

        this.isOp = player.hasPermissions(2);
        this.opAllowed = isOp;
        this.isCreative = player.isCreative();
        // 类型已在上一步选定，直接按该类型的上限判定 SAFE/DANGER。
        // OP 豁免，但显示的仍是这种蓝图的实际上限，而不是 160 万的硬底线 ——
        // 后者对玩家没有参考价值。
        this.limit = com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage
                .maxVolumeFor(this.category);

        this.state = blockCount > limit
                ? (isOp ? ReportState.OVER_LIMIT : ReportState.DANGER)
                : ReportState.SAFE;

        // 请求服务端扫描选区非法/危险方块
        this.requestTime = S2C_IllegalBlocksListMessage.clientReceiveTime;
        if (p1 != null && p2 != null) {
            int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
            int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
            int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
            BlueprintNetwork.CHANNEL.sendToServer(
                    new C2S_RequestIllegalBlocksMessage(new BlockPos(minX, minY, minZ),
                            new BlockPos(maxX, maxY, maxZ)));
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** 每 tick 检查服务端响应到达。 */
    @Override
    public void tick() {
        super.tick();
        if (!received && S2C_IllegalBlocksListMessage.clientReceiveTime > requestTime) {
            illegalBlocks = S2C_IllegalBlocksListMessage.clientIllegalBlocks;
            dangerousBlocks = S2C_IllegalBlocksListMessage.clientDangerousBlocks;
            specialEntities = S2C_IllegalBlocksListMessage.clientSpecialEntities;
            conversions = S2C_IllegalBlocksListMessage.clientConversions;
            freeBlocks = S2C_IllegalBlocksListMessage.clientFreeBlocks;
            received = true;
            // 超过类型上限：非 OP 危险；OP 只是超额（服务端对 OP 放到硬底线），
            // 与特殊实体同级 —— 两者都是「提醒但可继续」，非法/危险方块才锁按钮。
            boolean hasBad = !illegalBlocks.isEmpty() || !dangerousBlocks.isEmpty();
            boolean overLimit = blockCount > limit;
            if (hasBad || (overLimit && !isOp)) {
                state = ReportState.DANGER;
            } else if (overLimit) {
                state = ReportState.OVER_LIMIT;
            } else if (!specialEntities.isEmpty()) {
                // 存在特殊实体（带装备盔甲架/有物品展示框/白名单生物）→ 注意
                state = ReportState.WARN;
            } else {
                state = ReportState.SAFE;
            }
            updateButtons();
        }
    }

    private void updateButtons() {
        if (continueButton != null && sanitizeButton != null) {
            continueButton.active = state != ReportState.DANGER;
            continueButton.setTooltip(Tooltip.create(Component.translatable(
                    state == ReportState.DANGER
                            ? "spatial_blueprint.gui.report.tooltip.continue_disabled"
                            : "spatial_blueprint.gui.report.tooltip.continue")));
            // 两个 OP 强制创建按钮只在 OP 的界面里存在（init 里按 opAllowed 决定加不加）
            if (opButton != null) opButton.active = opAllowed;
            if (opRewardButton != null) opRewardButton.active = opAllowed;
            // 净化并继续：非次元蓝图任何状态下均可点击（净化后捕获）；
            // 次元蓝图没有这条路，恒灰。
            sanitizeButton.active = !isDimensional();
        }
    }

    // ── 内容行构建（含非法/危险方块区块）─────────────────────────────────

    private List<ContentRow> buildRows() {
        List<ContentRow> rows = new ArrayList<>();
        rows.add(ContentRow.text(textRow(0), COLOR_WHITE));
        rows.add(ContentRow.text(textRow(1), COLOR_WHITE));
        rows.add(ContentRow.text(textRow(2), COLOR_WHITE));
        rows.add(ContentRow.text(textRow(3), COLOR_WHITE));

        // 非法方块区块
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.report.illegal_blocks"), COLOR_RED));
        appendBlockSection(rows, illegalBlocks, false);

        // 危险方块区块
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.report.dangerous_blocks"), COLOR_RED));
        appendBlockSection(rows, dangerousBlocks, true);

        // 特殊实体区块
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.report.special_entities"), COLOR_RED));
        if (!received) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.illegal_scanning"), 0xAAAAAA));
        } else if (specialEntities.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.special_ok"), COLOR_GREEN));
        } else {
            int count = specialEntities.size();
            int gridRows = (count + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
            for (int r = 0; r < gridRows; r++) {
                int start = r * CELLS_PER_ROW;
                int end = Math.min(count, start + CELLS_PER_ROW);
                rows.add(ContentRow.specialGrid(specialEntities.subList(start, end)));
            }
            // 结论行（hover 显示 tooltip）
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.special_conclusion"), COLOR_YELLOW));
        }

        // 物品转换区块
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.report.conversions"), COLOR_CYAN));
        if (!received) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.illegal_scanning"), 0xAAAAAA));
        } else if (conversions.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.conversions_ok"), COLOR_GREEN));
        } else {
            for (S2C_IllegalBlocksListMessage.ConversionEntry e : conversions) {
                rows.add(ContentRow.conversion(e));
            }
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.conversions_conclusion"), COLOR_YELLOW));
        }

        // 免费方块区块
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.report.free_blocks"), COLOR_CYAN));
        if (!received) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.illegal_scanning"), 0xAAAAAA));
        } else if (freeBlocks.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.free_ok"), COLOR_GREEN));
        } else {
            int count = freeBlocks.size();
            int gridRows = (count + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
            for (int r = 0; r < gridRows; r++) {
                int start = r * CELLS_PER_ROW;
                int end = Math.min(count, start + CELLS_PER_ROW);
                rows.add(ContentRow.freeGrid(freeBlocks.subList(start, end)));
            }
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.free_conclusion"), COLOR_YELLOW));
        }

        return rows;
    }

    private void appendBlockSection(List<ContentRow> rows,
                                    List<S2C_IllegalBlocksListMessage.IllegalBlock> blocks,
                                    boolean dangerous) {
        if (!received) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.illegal_scanning"), 0xAAAAAA));
        } else if (blocks.isEmpty()) {
            rows.add(ContentRow.text(Component.translatable(dangerous
                    ? "spatial_blueprint.gui.report.dangerous_ok"
                    : "spatial_blueprint.gui.report.illegal_ok"), COLOR_GREEN));
        } else {
            int count = blocks.size();
            int gridRows = (count + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
            for (int r = 0; r < gridRows; r++) {
                int start = r * CELLS_PER_ROW;
                int end = Math.min(count, start + CELLS_PER_ROW);
                rows.add(ContentRow.blockGrid(blocks.subList(start, end), dangerous));
            }
            // 网格下方单独一行结论文本（非法移除 / 危险净化）
            rows.add(ContentRow.text(Component.translatable(dangerous
                    ? "spatial_blueprint.gui.report.dangerous_conclusion"
                    : "spatial_blueprint.gui.report.illegal_conclusion"), COLOR_YELLOW));
        }
    }

    private Component textRow(int index) {
        return switch (index) {
            case 0 -> Component.translatable("spatial_blueprint.gui.report.structure_status");
            case 1 -> {
                int sx = Math.abs(p2.getX() - p1.getX()) + 1;
                int sy = Math.abs(p2.getY() - p1.getY()) + 1;
                int sz = Math.abs(p2.getZ() - p1.getZ()) + 1;
                MutableComponent sizeNum = Component.literal(sx + "x" + sy + "x" + sz)
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_CYAN)));
                MutableComponent count = Component.literal(String.valueOf(blockCount))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(
                                blockCount > limit ? COLOR_RED : COLOR_YELLOW)));
                MutableComponent line = Component.translatable("spatial_blueprint.gui.report.range")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE)));
                line.append(sizeNum);
                line.append(Component.translatable("spatial_blueprint.gui.report.range_sep")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE))));
                line.append(count);
                line.append(Component.translatable("spatial_blueprint.gui.report.range_suffix")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE))));
                yield line;
            }
            case 2 -> Component.translatable("spatial_blueprint.gui.report.permission");
            case 3 -> {
                int permColor = isOp ? 0xFFAA00
                        : isCreative ? COLOR_CYAN
                        : 0xAAAAAA;
                String perm = isOp ? Component.translatable("spatial_blueprint.gui.report.permission.op").getString()
                        : isCreative ? Component.translatable("spatial_blueprint.gui.report.permission.creative").getString()
                        : Component.translatable("spatial_blueprint.gui.report.permission.none").getString();
                // OP 豁免体积上限；显示的 limit 仍是这种蓝图的实际上限（供参考）
                boolean pass = isOp || blockCount <= limit;
                String passText = Component.translatable(pass
                        ? "spatial_blueprint.gui.report.permission.pass"
                        : "spatial_blueprint.gui.report.permission.fail").getString();
                MutableComponent nameComp = Component.literal(player.getScoreboardName())
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE)));
                MutableComponent permComp = Component.literal(perm)
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(permColor)));
                MutableComponent limitComp = Component.literal(String.valueOf(limit))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_YELLOW)));
                MutableComponent resultComp = Component.literal(passText)
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(pass ? COLOR_GREEN : COLOR_RED)));

                MutableComponent line = Component.empty();
                line.append(nameComp);
                line.append(Component.translatable("spatial_blueprint.gui.report.permission_sep")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE))));
                line.append(permComp);
                line.append(Component.translatable("spatial_blueprint.gui.report.permission_limit_pre")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE))));
                line.append(limitComp);
                line.append(Component.translatable("spatial_blueprint.gui.report.permission_limit_suf")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WHITE))));
                line.append(resultComp);
                yield line;
            }
            default -> Component.literal("");
        };
    }

    private int viewHeight() {
        return BANNER_H - CONTENT_PAD * 2;
    }

    private int totalContentHeight(List<ContentRow> rows) {
        int h = 0;
        for (ContentRow r : rows) {
            h += r.isGrid() ? BLOCK_ROW_H : LINE_H;
        }
        return h;
    }

    /**
     * 滚动上限，<b>向上取整到 {@link #LINE_H} 的倍数</b>。
     *
     * <p>可视高（212）不是行高的整数倍，直接用差值当上限会把滚到底的位置钳在一个非整行的
     * 偏移上，之后每次上滚都带着这个零头，行与行之间再也对不齐。取整后所有可达偏移都是整行，
     * 代价只是滚到底时底部多出不到一行的空白。</p>
     */
    private int maxScroll(List<ContentRow> rows) {
        int overflow = totalContentHeight(rows) - viewHeight();
        if (overflow <= 0) return 0;
        return (overflow + LINE_H - 1) / LINE_H * LINE_H;
    }

    // ── 按钮 ──────────────────────────────────────────────────────────────

    private Button continueButton;
    private Button opButton;
    /** 第二排的 [(OP)奖励蓝图]。 */
    private Button opRewardButton;
    private Button sanitizeButton;

    @Override
    protected void init() {
        super.init();

        int cx = this.width / 2;
        int bannerY = (this.height - BANNER_H) / 2;
        // 按钮区与标题对称，移到横幅（UI）下方外侧
        int btnY = bannerY + BANNER_H + TITLE_GAP;

        String sanitizeKey = "spatial_blueprint.gui.report.btn.sanitize";
        String contKey     = "spatial_blueprint.gui.report.btn.continue";
        String cancelKey   = "spatial_blueprint.gui.report.btn.cancel";
        String opSpatialKey = "spatial_blueprint.gui.report.btn.op_spatial";
        String opRewardKey  = "spatial_blueprint.gui.report.btn.op_reward";

        // ── 第一排：净化并继续 / 继续创建 / 取消创建 ──────────────────────
        int[] row1 = { (font.width(Component.translatable(sanitizeKey)) + 20) * 2,
                       (font.width(Component.translatable(contKey))     + 20) * 2,
                       (font.width(Component.translatable(cancelKey))   + 20) * 2 };
        int row1W = row1[0] + row1[1] + row1[2] + BTN_GAP * 2;
        int x1 = cx - row1W / 2;

        // 次元蓝图没有净化这条路：它把结构从世界里搬走，蓝图里存的必须就是世界里
        // 消失的那份，任何人一样。选区不合规就只能取消，玩家自己清理干净再来。
        // 服务端 CaptureAreaMessage 对次元 + SANITIZE 还会再拒一次（防改包）。
        boolean hasSanitizeTarget = !isDimensional() && received
                && (!illegalBlocks.isEmpty() || !dangerousBlocks.isEmpty());
        sanitizeButton = Button.builder(
                        Component.translatable(sanitizeKey),
                        b -> sanitizeCreate())
                .bounds(x1, btnY, row1[0], BTN_H)
                .tooltip(Tooltip.create(Component.translatable(isDimensional()
                        ? "spatial_blueprint.gui.report.tooltip.sanitize_dimensional_disabled"
                        : "spatial_blueprint.gui.report.tooltip.sanitize")))
                .build();
        sanitizeButton.active = hasSanitizeTarget;
        addRenderableWidget(sanitizeButton);

        continueButton = Button.builder(
                        Component.translatable(contKey),
                        b -> continueCreate())
                .bounds(x1 + row1[0] + BTN_GAP, btnY, row1[1], BTN_H)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.gui.report.tooltip.continue_disabled")))
                .build();
        continueButton.active = false;
        addRenderableWidget(continueButton);

        addRenderableWidget(Button.builder(
                        Component.translatable(cancelKey),
                        b -> this.onClose())
                .bounds(x1 + row1[0] + row1[1] + BTN_GAP * 2, btnY, row1[2], BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.report.tooltip.cancel")))
                .build());

        // ── 第二排：(OP)空间蓝图 / (OP)奖励蓝图 —— 只对 OP 显示 ─────────────
        // 两个都是 FORCE（不受任何限制、完整捕获），并且<b>覆盖上一步选的类型</b> ——
        // 上一屏定的是「建哪种」，这两个按钮自己决定建成空间还是奖励。
        // 非 OP 以前也画出来只是灰着，等于告诉玩家「这里有个你点不了的东西」，
        // 现在干脆不加进界面。opButton / opRewardButton 对非 OP 保持 null，
        // updateButtons 里已按 null 跳过。
        // 子工程没有空白蓝图，出这两个按钮就是坑；category == "subproject" 时一律不加
        if (opAllowed && !"subproject".equals(category)) {
            int btnY2 = btnY + BTN_H + BTN_GAP;
            int[] row2 = { (font.width(Component.translatable(opSpatialKey)) + 20) * 2,
                           (font.width(Component.translatable(opRewardKey))  + 20) * 2 };
            int row2W = row2[0] + row2[1] + BTN_GAP;
            int x2 = cx - row2W / 2;

            opButton = Button.builder(
                            Component.translatable(opSpatialKey),
                            b -> startCapture(com.xioyim.spatialblueprint.util.CaptureMode.FORCE, "create"))
                    .bounds(x2, btnY2, row2[0], BTN_H)
                    .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.report.tooltip.op_spatial")))
                    .build();
            // OP 按钮不依赖扫描结果，扫描完成前也应立即可点
            opButton.active = true;
            addRenderableWidget(opButton);

            opRewardButton = Button.builder(
                            Component.translatable(opRewardKey),
                            b -> startCapture(com.xioyim.spatialblueprint.util.CaptureMode.FORCE, "reward"))
                    .bounds(x2 + row2[0] + BTN_GAP, btnY2, row2[1], BTN_H)
                    .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.report.tooltip.op_reward")))
                    .build();
            opRewardButton.active = true;
            addRenderableWidget(opRewardButton);
        }
    }

    /** 上一步选的是不是次元蓝图 —— 次元蓝图没有净化路径，也不看 OP。 */
    private boolean isDimensional() {
        return "dimensional".equals(category);
    }

    private void continueCreate() {
        startCapture(com.xioyim.spatialblueprint.util.CaptureMode.SAFE);
    }

    /** [净化并继续]：净化非法/危险方块后创建。 */
    private void sanitizeCreate() {
        startCapture(com.xioyim.spatialblueprint.util.CaptureMode.SANITIZE);
    }

    /**
     * 第一排三个按钮的出口：写入捕获模式，按上一步选定的类型发起捕获。
     *
     * <p>类型在「选择创建类型」界面就定好了，这三个按钮只决定<b>模式</b>
     * （普通安全 / 净化）。</p>
     */
    private void startCapture(com.xioyim.spatialblueprint.util.CaptureMode mode) {
        startCapture(mode, category);
    }

    /**
     * 带类型覆盖的出口 —— 第二排两个 OP 按钮用。
     *
     * <p>它们同时决定模式（恒为 FORCE）与类型（空间 / 奖励），
     * <b>覆盖上一步选的类型</b>：点「(OP)奖励蓝图」的意思就是「不管前面选的是什么，
     * 给我建成奖励蓝图」。</p>
     */
    private void startCapture(com.xioyim.spatialblueprint.util.CaptureMode mode, String targetCategory) {
        host.onReportConfirmed(mode, targetCategory);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int screenW = this.width;
        int screenH = this.height;
        int cx = screenW / 2;
        int bannerY = (screenH - BANNER_H) / 2;

        // 标题（横幅上方，按状态着色）
        int titleColor = switch (state) {
            case DANGER            -> COLOR_RED;
            case WARN, OVER_LIMIT  -> COLOR_YELLOW;
            default                -> COLOR_GREEN;
        };
        String suffix = switch (state) {
            case DANGER     -> Component.translatable("spatial_blueprint.gui.report.state_danger").getString();
            case WARN       -> Component.translatable("spatial_blueprint.gui.report.state_warn").getString();
            case OVER_LIMIT -> Component.translatable("spatial_blueprint.gui.report.state_overlimit").getString();
            default         -> Component.translatable("spatial_blueprint.gui.report.state_safe").getString();
        };
        MutableComponent title = Component.translatable("spatial_blueprint.gui.report.title",
                        Component.translatable(categoryNameKey(category)))
                .append(Component.translatable("spatial_blueprint.gui.report.title_state", suffix))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(titleColor)));

        // 全屏遮罩
        graphics.fill(0, 0, screenW, screenH, 0xA0000000);

        // 横幅背景（左右超出屏幕）
        graphics.fill(-OVERFLOW, bannerY, screenW + OVERFLOW, bannerY + BANNER_H, 0xFF141414);

        // 横幅上下边缘线
        int lc = 0xFF3A3A3A;
        graphics.fill(-OVERFLOW, bannerY,               screenW + OVERFLOW, bannerY + 1,        lc);
        graphics.fill(-OVERFLOW, bannerY + BANNER_H - 1, screenW + OVERFLOW, bannerY + BANNER_H, lc);

        // 外置标题（在遮罩/横幅之后绘制，位于横幅上方，不被遮挡）
        graphics.drawCenteredString(this.font, title, cx, bannerY - TITLE_GAP, titleColor);

        // 内容区（可滚动）
        int contentTop = bannerY + CONTENT_PAD;
        int contentBottom = bannerY + BANNER_H - CONTENT_PAD;
        List<ContentRow> rows = buildRows();
        int maxScr = maxScroll(rows);
        if (scrollOffset > maxScr) scrollOffset = maxScr;
        logRowLayout(rows, contentTop, contentBottom, maxScr);

        cellRects.clear();
        specialCellRects.clear();
        infoCellRects.clear();
        specialConclusionY = -1;

        // 像素级裁剪：光靠下面那两个 if 只能决定「整行画不画」，卡在边界上的那一行
        // 会被整行画出来、越过横幅边缘（物品图标尤其明显）。scissor 才能真正切掉溢出部分。
        graphics.enableScissor(0, contentTop, screenW, contentBottom);

        int cursorY = contentTop - scrollOffset;
        for (ContentRow row : rows) {
            if (row.isGrid()) {
                if (cursorY + CELL >= contentTop && cursorY <= contentBottom) {
                    switch (row.kind()) {
                        case SPECIAL_GRID -> renderSpecialGrid(graphics, cx, cursorY, row.specialGrid(), mouseX, mouseY);
                        case CONVERSION   -> renderConversionRow(graphics, cx, cursorY, row.conversion(), mouseX, mouseY);
                        case FREE_GRID    -> renderFreeGrid(graphics, cx, cursorY, row.freeGrid(), mouseX, mouseY);
                        default           -> renderBlockGrid(graphics, cx, cursorY, row.grid(), row.dangerous(), mouseX, mouseY);
                    }
                }
                cursorY += BLOCK_ROW_H;
            } else {
                boolean isSpecialConclusion = row.text() != null
                        && row.text().getString().equals(
                                Component.translatable("spatial_blueprint.gui.report.special_conclusion").getString());
                if (cursorY < contentBottom && cursorY + LINE_H > contentTop) {
                    // 文本行在 20px 槽位内垂直居中，与方块格子行保持同一节奏
                    graphics.drawCenteredString(this.font, row.text(), cx,
                            cursorY + (LINE_H - this.font.lineHeight) / 2, row.color());
                    if (isSpecialConclusion) specialConclusionY = cursorY;
                }
                cursorY += LINE_H;
            }
        }

        graphics.disableScissor();

        // 底部按钮（super.render 在最后绘制，位于遮罩/横幅之上，可见）
        super.render(graphics, mouseX, mouseY, partialTick);

        // tooltip（悬浮方块）
        renderCellTooltip(graphics, mouseX, mouseY);
    }

    /**
     * [调试] 行布局日志：内容区滚动后打印每一行的类型 / 推进高度 / 起始 Y，
     * 并标注网格行的格子实际尺寸（CELL）。用于核对滚动步进与各行行高是否对齐、
     * 每行是否都按固定像素推进 —— 只在 scrollOffset 变化时打一次，避免每帧刷屏。
     */
    private void logRowLayout(List<ContentRow> rows, int contentTop, int contentBottom, int maxScr) {
        if (scrollOffset == loggedScroll) return;
        loggedScroll = scrollOffset;
        StringBuilder sb = new StringBuilder();
        sb.append("scroll=").append(scrollOffset)
          .append(" maxScroll=").append(maxScr)
          .append(" viewH=").append(contentBottom - contentTop)
          .append(" top=").append(contentTop)
          .append(" | rows:");
        int y = contentTop - scrollOffset;
        for (ContentRow r : rows) {
            int h = r.isGrid() ? BLOCK_ROW_H : LINE_H;
            sb.append(" [").append(r.kind()).append(" h=").append(h);
            if (r.isGrid()) sb.append(" cell=").append(CELL);
            sb.append(" y=").append(y).append("]");
            y += h;
        }
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info("[ReportLayout] {}", sb);
    }

    private void renderBlockGrid(GuiGraphics graphics, int cx, int rowY,
                                 List<S2C_IllegalBlocksListMessage.IllegalBlock> entries,
                                 boolean dangerous, int mouseX, int mouseY) {
        int count = entries.size();
        int inRow = Math.min(CELLS_PER_ROW, count);
        int actualRowW = inRow * CELL + (inRow - 1) * CELL_GAP;
        int rowStartX = cx - actualRowW / 2;
        for (int i = 0; i < inRow; i++) {
            S2C_IllegalBlocksListMessage.IllegalBlock entry = entries.get(i);
            int cellX = rowStartX + i * (CELL + CELL_GAP);
            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL
                    && mouseY >= rowY && mouseY < rowY + CELL;
            drawBlockCell(graphics, cellX, rowY, entry, hovered, dangerous);
            cellRects.add(new CellRect(cellX, rowY, CELL, CELL, entry, dangerous));
        }
    }

    private void drawBlockCell(GuiGraphics graphics, int x, int y,
                               S2C_IllegalBlocksListMessage.IllegalBlock entry, boolean hovered,
                               boolean dangerous) {
        int border = hovered ? COLOR_WHITE : 0x666666;
        graphics.fill(x, y, x + CELL, y + 1, border);
        graphics.fill(x, y + CELL - 1, x + CELL, y + CELL, border);
        graphics.fill(x, y, x + 1, y + CELL, border);
        graphics.fill(x + CELL - 1, y, x + CELL, y + CELL, border);

        ItemStack stack = itemStackFor(entry.blockId(), dangerous);
        if (!stack.isEmpty()) {
            renderItemSafe(graphics, stack, x + 2, y + 2);
        } else {
            // 未知方块（无物品图标且无 lang key）：红色斜体"未知"文本
            String unk = Component.translatable("spatial_blueprint.gui.report.unknown_block").getString();
            if (this.font.width(unk) > CELL - 4) {
                unk = Component.translatable("spatial_blueprint.gui.report.unknown_block_short").getString();
            }
            MutableComponent txt = Component.literal(unk)
                    .withStyle(style -> style.withColor(TextColor.fromRgb(COLOR_RED)).withItalic(true));
            graphics.drawCenteredString(this.font, txt, x + CELL / 2, y + (CELL - this.font.lineHeight) / 2 + 1, COLOR_RED);
        }
    }

    private static ItemStack itemStackFor(String blockId, boolean dangerous) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) return new ItemStack(item);
        }
        // 危险方块条目无图标（如展示框）→ 回退到展示框图标
        if (dangerous) {
            Item frame = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft:item_frame"));
            if (frame != null) return new ItemStack(frame);
        }
        // 有 lang 名但无物品图标（bedrock 等）→ 回退到屏障方块图标
        if (rl != null && blockHasLangName(blockId)) {
            Item barrier = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft:barrier"));
            if (barrier != null) return new ItemStack(barrier);
        }
        // 未知方块（无 lang key）→ EMPTY，渲染"未知方块"红斜体文本
        return ItemStack.EMPTY;
    }

    /** 判断方块注册 id 是否有 lang 翻译名（未知模组方块无）。 */
    private static boolean blockHasLangName(String blockId) {
        String key = "block." + blockId.replace(':', '.');
        return net.minecraft.client.resources.language.I18n.exists(key);
    }

    private void renderSpecialGrid(GuiGraphics graphics, int cx, int rowY,
                                   List<S2C_IllegalBlocksListMessage.SpecialEntity> entries,
                                   int mouseX, int mouseY) {
        int count = entries.size();
        int inRow = Math.min(CELLS_PER_ROW, count);
        int actualRowW = inRow * CELL + (inRow - 1) * CELL_GAP;
        int rowStartX = cx - actualRowW / 2;
        for (int i = 0; i < inRow; i++) {
            S2C_IllegalBlocksListMessage.SpecialEntity entry = entries.get(i);
            int cellX = rowStartX + i * (CELL + CELL_GAP);
            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL
                    && mouseY >= rowY && mouseY < rowY + CELL;
            int border = hovered ? COLOR_WHITE : 0x666666;
            graphics.fill(cellX, rowY, cellX + CELL, rowY + 1, border);
            graphics.fill(cellX, rowY + CELL - 1, cellX + CELL, rowY + CELL, border);
            graphics.fill(cellX, rowY, cellX + 1, rowY + CELL, border);
            graphics.fill(cellX + CELL - 1, rowY, cellX + CELL, rowY + CELL, border);

            ItemStack stack = specialItemStack(entry);
            if (!stack.isEmpty()) {
                renderItemSafe(graphics, stack, cellX + 2, rowY + 2);
            }
            specialCellRects.add(new SpecialCellRect(cellX, rowY, CELL, CELL, entry));
        }
    }

    /**
     * 一条批量转换：{@code [源图标] ×N  →  [目标图标] ×M [目标图标] ×M}，整行居中。
     *
     * <p>左侧数量是选区里<b>实际捕获</b>的格数，右侧是该规则<b>固定产出</b>的件数 ——
     * 产出不随捕获量成比例增长，所以两个数字都要显示，否则玩家会以为是倍率关系。</p>
     */
    private void renderConversionRow(GuiGraphics graphics, int cx, int rowY,
                                     S2C_IllegalBlocksListMessage.ConversionEntry entry,
                                     int mouseX, int mouseY) {
        String srcNum = "x" + entry.capturedCount();
        String arrow = " ➡ ";
        int totalW = CELL + 2 + this.font.width(srcNum) + this.font.width(arrow);
        for (S2C_IllegalBlocksListMessage.TargetItem t : entry.targets()) {
            totalW += CELL_GAP + CELL + 2 + this.font.width("x" + t.count());
        }

        int x = cx - totalW / 2;
        int textY = rowY + (CELL - this.font.lineHeight) / 2 + 1;

        drawItemCell(graphics, x, rowY, sourceIcon(entry.sourceBlockId()),
                mouseX, mouseY, conversionSourceTip(entry));
        x += CELL + 2;
        graphics.drawString(this.font, srcNum, x, textY, COLOR_WHITE, false);
        x += this.font.width(srcNum);
        graphics.drawString(this.font, arrow, x, textY, COLOR_CYAN, false);
        x += this.font.width(arrow);

        for (S2C_IllegalBlocksListMessage.TargetItem t : entry.targets()) {
            drawItemCell(graphics, x, rowY, itemStackFor(t.itemId(), false),
                    mouseX, mouseY, Component.translatable(
                            "spatial_blueprint.gui.report.conversion_tip_target",
                            t.itemId(), itemLangName(t.itemId()), t.count()));
            x += CELL + 2;
            String num = "x" + t.count();
            graphics.drawString(this.font, num, x, textY, COLOR_GREEN, false);
            x += this.font.width(num) + CELL_GAP;
        }
    }

    /**
     * 转换源 / 免费方块的图标。
     *
     * <p>流体方块没有物品形式，{@link #itemStackFor} 会把它们回退成屏障 —— 那个图标在这里
     * 读起来像「被禁止」。改用对应的桶，与「方块替换表」界面同一套约定。</p>
     */
    private static ItemStack sourceIcon(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl != null) {
            var fluid = BuiltInRegistries.BLOCK.get(rl).defaultBlockState().getFluidState();
            if (fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                return new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET);
            }
            if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) {
                return new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET);
            }
        }
        return itemStackFor(blockId, false);
    }

    private static Component conversionSourceTip(S2C_IllegalBlocksListMessage.ConversionEntry entry) {
        return Component.translatable("spatial_blueprint.gui.report.conversion_tip_source",
                entry.sourceBlockId(), itemLangName(entry.sourceBlockId()),
                entry.capturedCount(), entry.threshold());
    }

    /** 免费方块网格：与非法/危险网格同款排布，无坐标（是规则而非具体位置）。 */
    private void renderFreeGrid(GuiGraphics graphics, int cx, int rowY,
                                List<String> ids, int mouseX, int mouseY) {
        int inRow = Math.min(CELLS_PER_ROW, ids.size());
        int actualRowW = inRow * CELL + (inRow - 1) * CELL_GAP;
        int rowStartX = cx - actualRowW / 2;
        for (int i = 0; i < inRow; i++) {
            String id = ids.get(i);
            drawItemCell(graphics, rowStartX + i * (CELL + CELL_GAP), rowY, sourceIcon(id),
                    mouseX, mouseY, Component.translatable(
                            "spatial_blueprint.gui.report.free_tip", id, itemLangName(id)));
        }
    }

    /** 画一个带边框的物品格子并登记 tooltip 矩形。 */
    private void drawItemCell(GuiGraphics graphics, int x, int y, ItemStack stack,
                              int mouseX, int mouseY, Component tip) {
        boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
        int border = hovered ? COLOR_WHITE : 0x666666;
        graphics.fill(x, y, x + CELL, y + 1, border);
        graphics.fill(x, y + CELL - 1, x + CELL, y + CELL, border);
        graphics.fill(x, y, x + 1, y + CELL, border);
        graphics.fill(x + CELL - 1, y, x + CELL, y + CELL, border);
        if (!stack.isEmpty()) renderItemSafe(graphics, stack, x + 2, y + 2);
        infoCellRects.add(new InfoCellRect(x, y, CELL, CELL, tip));
    }

    /** 特殊实体图标：盔甲架 / 生物蛋 / 物品展示框。 */
    private static ItemStack specialItemStack(S2C_IllegalBlocksListMessage.SpecialEntity entry) {
        switch (entry.kind()) {
            case "armor_stand" -> {
                Item st = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft:armor_stand"));
                if (st != null) return new ItemStack(st);
            }
            case "mob" -> {
                ResourceLocation egg = new ResourceLocation(entry.entityId() + "_spawn_egg");
                Item spawn = BuiltInRegistries.ITEM.get(egg);
                if (spawn != null && spawn != net.minecraft.world.item.Items.AIR) return new ItemStack(spawn);
                // 无生物蛋的生物 → 通用蛋
                Item generic = BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft:cow_spawn_egg"));
                if (generic != null) return new ItemStack(generic);
            }
            case "item_frame" -> {
                Item frame = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.entityId()));
                if (frame != null) return new ItemStack(frame);
            }
            default -> {}
        }
        return ItemStack.EMPTY;
    }

    private static void renderItemSafe(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (KNOWN_CRASH.contains(key)) {
            try {
                graphics.renderItem(new ItemStack(net.minecraft.world.level.block.Blocks.BARRIER), x, y);
            } catch (Throwable ignored) {}
            return;
        }
        try {
            graphics.renderItem(stack, x, y);
        } catch (Throwable ex) {
            KNOWN_CRASH.add(key);
            try {
                Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } catch (Throwable ignored) {}
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.warn(
                "[BlueprintReport][renderItem] threw for {}: {}", key, ex.toString());
        }
    }

    private void renderCellTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        // 转换 / 免费方块单元格
        for (InfoCellRect r : infoCellRects) {
            if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                drawMultilineTooltip(graphics, r.tip(), mouseX, mouseY);
                return;
            }
        }
        // 特殊实体单元格
        for (SpecialCellRect r : specialCellRects) {
            if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                S2C_IllegalBlocksListMessage.SpecialEntity e = r.entry();
                Component tip;
                switch (e.kind()) {
                    case "armor_stand" -> tip = Component.translatable(
                            "spatial_blueprint.gui.report.special_tip_armor",
                            e.x(), e.y(), e.z(),
                            e.hasEquipment()
                                    ? Component.translatable("spatial_blueprint.gui.report.special_armor_equipped").getString()
                                    : Component.translatable("spatial_blueprint.gui.report.special_armor_plain").getString(),
                            "minecraft:armor_stand");
                    case "mob" -> tip = Component.translatable(
                            "spatial_blueprint.gui.report.special_tip_mob",
                            e.x(), e.y(), e.z(), e.entityId(), itemLangName(e.entityId()));
                    default -> tip = Component.translatable(
                            "spatial_blueprint.gui.report.special_tip_frame",
                            e.x(), e.y(), e.z(), e.entityId(), itemLangName(e.entityId()),
                            e.itemId(), itemLangName(e.itemId()));
                }
                drawMultilineTooltip(graphics, tip, mouseX, mouseY);
                return;
            }
        }
        // 特殊实体结论行
        if (specialConclusionY >= 0 && mouseY >= specialConclusionY
                && mouseY < specialConclusionY + this.font.lineHeight) {
            drawMultilineTooltip(graphics,
                    Component.translatable("spatial_blueprint.gui.report.special_conclusion_tip"),
                    mouseX, mouseY);
            return;
        }
        // 方块单元格
        for (CellRect r : cellRects) {
            if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                S2C_IllegalBlocksListMessage.IllegalBlock e = r.entry();
                String blockName = itemLangName(e.blockId());
                Component tip;
                if (r.dangerous() && !e.itemId().isEmpty()) {
                    // 展示框：内嵌非法物品
                    tip = Component.translatable("spatial_blueprint.gui.report.dangerous_tip_frame",
                            e.x(), e.y(), e.z(), e.itemId(), itemLangName(e.itemId()), e.blockId(), blockName);
                } else if (r.dangerous()) {
                    tip = Component.translatable("spatial_blueprint.gui.report.dangerous_tip_block",
                            e.x(), e.y(), e.z(), e.blockId(), blockName);
                } else {
                    tip = Component.translatable("spatial_blueprint.gui.report.illegal_tip",
                            e.x(), e.y(), e.z(), e.blockId(), blockName);
                }
                drawMultilineTooltip(graphics, tip, mouseX, mouseY);
                return;
            }
        }
    }

    /**
     * 按 {@code \n} 拆行绘制 tooltip，每行再按屏宽自动折行。
     *
     * <p>lang 里的 {@code \n} 是作者定的段落分隔，必须保留；但单段本身可能很长
     * （特殊实体那条尤其），只按 {@code \n} 拆会渲染成顶出屏幕的一整行，所以每段再交给
     * {@code font.split} 按上限折一次。§ 颜色码由 StringSplitter 自己处理，折行后仍然延续。</p>
     */
    private void drawMultilineTooltip(GuiGraphics graphics, Component tip, int mouseX, int mouseY) {
        int maxWidth = Math.max(160, this.width / 2);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : tip.getString().split("\n")) {
            if (line.isEmpty()) {
                // font.split 对空串返回空列表，段落间的空行会被吃掉，手动补回
                lines.add(FormattedCharSequence.EMPTY);
            } else {
                lines.addAll(this.font.split(Component.literal(line), maxWidth));
            }
        }
        graphics.renderTooltip(this.font, lines, mouseX, mouseY);
    }

    /** 返回物品/方块/实体的语言显示名；无法解析时回退到注册 ID。 */
    private static String itemLangName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(item).getHoverName().getString();
            }
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                // 取方块自己的译名，不走 asItem() —— 水/火/气泡柱这类没有物品形式的方块
                // asItem() 返回空气，名字会显示成「空气」
                return block.getName().getString();
            }
        }
        // 生物蛋/特殊实体 tooltip 里的实体 ID（minecraft:cow 等）不是物品也不是方块，
        // 落到这里必须按实体译名解析，否则显示成一串原始 key
        String entity = entityLangName(id);
        if (entity != null) return entity;
        return id;
    }

    /** 把实体注册 ID 解析成本地化显示名（如 minecraft:cow → 牛）；无此实体时返回 null。 */
    @javax.annotation.Nullable
    private static String entityLangName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        var type = net.minecraft.world.entity.EntityType.byString(id).orElse(null);
        if (type == null) return null;
        ResourceLocation typeKey = net.minecraft.world.entity.EntityType.getKey(type);
        String langKey = "entity." + typeKey.getNamespace() + "." + typeKey.getPath();
        if (!net.minecraft.client.resources.language.I18n.exists(langKey)) return null;
        return net.minecraft.client.resources.language.I18n.get(langKey);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<ContentRow> rows = buildRows();
        int maxScr = maxScroll(rows);
        scrollOffset = Math.max(0, Math.min(maxScr, scrollOffset - (int) Math.round(delta) * LINE_H));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (SpecialCellRect r : specialCellRects) {
                if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                    S2C_IllegalBlocksListMessage.SpecialEntity e = r.entry();
                    Minecraft.getInstance().keyboardHandler.setClipboard(e.x() + " " + e.y() + " " + e.z());
                    return true;
                }
            }
            for (CellRect r : cellRects) {
                if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                    S2C_IllegalBlocksListMessage.IllegalBlock e = r.entry();
                    Minecraft.getInstance().keyboardHandler.setClipboard(e.x() + " " + e.y() + " " + e.z());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { this.onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
