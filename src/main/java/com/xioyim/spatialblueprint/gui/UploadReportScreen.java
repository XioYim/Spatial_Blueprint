package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ConfirmUploadMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 上传文件的检测报告页。
 *
 * <p>与捕获用的 {@link BlueprintReportScreen} 同一套视觉，但内容完全不同 ——
 * 那个扫的是世界里的选区（世界坐标、可点击复制、能跑过去看），
 * 这个扫的是<b>还没落盘的上传文件</b>，坐标是蓝图内<b>相对坐标</b>，跑不过去。
 * 因此本页不做格子点击跳坐标，只悬停显示 {@code id @ (x,y,z)}。</p>
 *
 * <p>此刻文件已在服务端暂存但尚未保存，玩家在这里决定它的去向。</p>
 */
@OnlyIn(Dist.CLIENT)
public class UploadReportScreen extends Screen {

    // 排版与 BlueprintReportScreen 对齐：全屏宽横幅、左右超出屏幕、同样的高度与行高。
    private static final int BANNER_H    = 240;
    private static final int TITLE_GAP   = 15;
    private static final int OVERFLOW    = 24;
    private static final int CONTENT_PAD = 14;
    private static final int LINE_H      = 20;
    private static final int BLOCK_ROW_H = 20;
    private static final int BTN_H       = 20;
    private static final int BTN_GAP     = 6;
    private static final int CELL        = 20;
    private static final int CELL_GAP    = 3;
    private static final int CELLS_PER_ROW = 10;

    // 已知崩溃物品集合（BEWLR 破损等），首次抛异常后加入，后续帧改用 BARRIER 占位
    private static final java.util.Set<String> KNOWN_CRASH =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static final int COLOR_RED    = 0xFF5555;
    private static final int COLOR_YELLOW = 0xFFFF55;
    private static final int COLOR_GREEN  = 0x55FF55;
    private static final int COLOR_WHITE  = 0xFFFFFF;
    private static final int COLOR_CYAN   = 0x55FFFF;
    private static final int COLOR_GRAY   = 0xAAAAAA;

    private final String  blueprintName;
    private final int     totalBlocks;
    private final boolean canForce;
    private final int     sizeX;
    private final int     sizeY;
    private final int     sizeZ;
    private final int     compressedBytes;
    private final long    nbtTotalBytes;
    private final long    nbtMaxSingleBytes;
    private final int     outOfRangeDropped;
    private final List<BlueprintSanitizer.ScanEntry> illegal;
    private final List<BlueprintSanitizer.ScanEntry> dangerous;
    private final List<BlueprintSanitizer.ScanEntry> oversized;
    private final List<BlueprintSanitizer.ScanEntry> deepNbt;
    private final List<BlueprintSanitizer.ScanEntity> dropped;
    private final List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions;
    private final List<String> freeBlocks;
    private final int illegalTotal;
    private final int dangerousTotal;
    private final int oversizedTotal;
    private final int deepNbtTotal;
    private final int droppedTotal;
    private final int conversionsTotal;
    private final int freeTotal;
    /** 客户端本地文件信息：服务端看不到原始文件名 / 扩展名 / 磁盘字节数。 */
    private final String  fileName;
    private final String  fileExt;
    private final long    fileSize;
    /** 上传分类（空间 / 投影 / 投影模板），服务端下发，用于显示体积上限。 */
    private final String  category;
    /** 服务端下发的体积上限，报告页直接显示该值，不再本地推导。 */
    private final int     volumeLimit;
    /**
     * 服务端实际执行的两个 NBT 阈值。多人服上客户端的 toml 与服务端可以完全不同，
     * 而这两个值又不在 {@code S2C_ConfigSyncMessage} 的同步范围内（那里只同步
     * volume / slot 两组），所以必须随报告一起下发 —— 否则报告页会拿本地配置
     * 去解释服务端的判定结果，上限、超限红字、≥ 前缀三处都可能与服务端不一致。
     */
    private final int     nbtPerEntryLimit;
    private final int     nbtTotalLimit;
    /** 打开本页的上传页（UploadBlueprintScreen）；确认/取消后退回此页，而非关闭全部 UI。 */
    private final Screen  parent;
    /** true = 本次是 OP 发起的强制上传流程；报告底部仅显示[强制上传][取消]。 */
    private final boolean forceRequested;
    /**
     * 宿主确认回调（工程蓝图 ⌘ 报告专用）：非 null 时 [净化并继续]/[强制] 不走上传确认，
     * 直接把所选模式交回宿主（宿主自己开创建页）。普通上传为 null。
     */
    @Nullable
    private final java.util.function.Consumer<com.xioyim.spatialblueprint.util.CaptureMode> confirmHost;

    private boolean dirty;
    /** true = 上传中加载态：等 S2C_UploadReportMessage 到达，本页只显示「文件状态(上传中…)」。 */
    private boolean uploading = false;

    private int scrollOffset = 0;
    /** 已经点过确认：避免重复发包（暂存是取出即消费的，第二次必然报「已失效」）。 */
    private boolean submitted = false;

    public UploadReportScreen(S2C_UploadReportMessage msg, Screen parent,
                              String fileName, String fileExt, long fileSize, String category) {
        this(msg, parent, fileName, fileExt, fileSize, category, false, null);
    }

    public UploadReportScreen(S2C_UploadReportMessage msg, Screen parent,
                              String fileName, String fileExt, long fileSize, String category,
                              boolean forceRequested) {
        this(msg, parent, fileName, fileExt, fileSize, category, forceRequested, null);
    }

    public UploadReportScreen(S2C_UploadReportMessage msg, Screen parent,
                              String fileName, String fileExt, long fileSize, String category,
                              boolean forceRequested,
                              @Nullable java.util.function.Consumer<com.xioyim.spatialblueprint.util.CaptureMode> confirmHost) {
        super(Component.translatable("spatial_blueprint.gui.upload_report.title"));
        this.blueprintName = msg.getBlueprintName();
        this.totalBlocks   = msg.getTotalBlocks();
        this.canForce      = msg.canForce();
        this.sizeX         = msg.getSizeX();
        this.sizeY         = msg.getSizeY();
        this.sizeZ         = msg.getSizeZ();
        this.compressedBytes = msg.getCompressedBytes();
        this.nbtTotalBytes = msg.getNbtTotalBytes();
        this.nbtMaxSingleBytes = msg.getNbtMaxSingleBytes();
        this.outOfRangeDropped = msg.getOutOfRangeDropped();
        this.illegal       = msg.getIllegal();
        this.dangerous     = msg.getDangerous();
        this.oversized     = msg.getOversized();
        this.deepNbt       = msg.getDeepNbt();
        this.dropped       = msg.getDropped();
        this.conversions   = msg.getConversions();
        this.freeBlocks    = msg.getFreeBlocks();
        this.illegalTotal  = msg.getIllegalTotal();
        this.dangerousTotal = msg.getDangerousTotal();
        this.oversizedTotal = msg.getOversizedTotal();
        this.deepNbtTotal  = msg.getDeepNbtTotal();
        this.droppedTotal  = msg.getDroppedTotal();
        this.conversionsTotal = msg.getConversionsTotal();
        this.freeTotal     = msg.getFreeTotal();
        this.fileName      = fileName == null ? "" : fileName;
        this.fileExt       = fileExt == null ? "" : fileExt;
        this.fileSize      = fileSize;
        this.category      = msg.getCategory() == null || msg.getCategory().isEmpty() ? "template" : msg.getCategory();
        this.volumeLimit   = msg.getVolumeLimit();
        this.nbtPerEntryLimit = msg.getNbtPerEntryLimit();
        this.nbtTotalLimit = msg.getNbtTotalLimit();
        this.parent        = parent;
        this.forceRequested = forceRequested;
        this.confirmHost   = confirmHost;
        this.dirty = !illegal.isEmpty() || !dangerous.isEmpty()
                || !oversized.isEmpty() || !deepNbt.isEmpty() || !dropped.isEmpty();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── 上传中加载态 ──────────────────────────────────────────────────────

    public boolean isLoading() { return uploading; }

    public Screen getParentScreen() { return parent; }
    public String getCategory() { return category; }
    public String getPendingFileName() { return fileName; }
    public String getPendingFileExt()  { return fileExt; }
    public long getPendingFileSize()   { return fileSize; }
    public boolean isForceRequested()  { return forceRequested; }
    @Nullable
    public java.util.function.Consumer<com.xioyim.spatialblueprint.util.CaptureMode> getConfirmHost() {
        return confirmHost;
    }

    /** 点上传后立刻打开的加载态：扫描结果还没回来，其它数据全部留空。 */
    public UploadReportScreen(Screen parent, String category,
                              String fileName, String fileExt, long fileSize) {
        this(parent, category, fileName, fileExt, fileSize, false, null);
    }

    public UploadReportScreen(Screen parent, String category,
                              String fileName, String fileExt, long fileSize, boolean forceRequested) {
        this(parent, category, fileName, fileExt, fileSize, forceRequested, null);
    }

    public UploadReportScreen(Screen parent, String category,
                              String fileName, String fileExt, long fileSize, boolean forceRequested,
                              @Nullable java.util.function.Consumer<com.xioyim.spatialblueprint.util.CaptureMode> confirmHost) {
        super(Component.translatable("spatial_blueprint.gui.upload_report.title"));
        this.blueprintName = "";
        this.totalBlocks   = 0;
        this.canForce      = false;
        this.sizeX = this.sizeY = this.sizeZ = 0;
        this.compressedBytes = 0;
        this.nbtTotalBytes = 0;
        this.nbtMaxSingleBytes = 0;
        this.outOfRangeDropped = 0;
        this.illegal       = List.of();
        this.dangerous     = List.of();
        this.oversized     = List.of();
        this.deepNbt       = List.of();
        this.dropped       = List.of();
        this.conversions   = List.of();
        this.freeBlocks    = List.of();
        this.illegalTotal  = 0;
        this.dangerousTotal = 0;
        this.oversizedTotal = 0;
        this.deepNbtTotal  = 0;
        this.droppedTotal  = 0;
        this.conversionsTotal = 0;
        this.freeTotal     = 0;
        this.fileName      = fileName == null ? "" : fileName;
        this.fileExt       = fileExt == null ? "" : fileExt;
        this.fileSize      = fileSize;
        this.category      = category == null ? "template" : category;
        this.volumeLimit   = 0;
        this.nbtPerEntryLimit = 0;
        this.nbtTotalLimit = 0;
        this.parent        = parent;
        this.forceRequested = forceRequested;
        this.confirmHost   = confirmHost;
        this.dirty         = false;
        this.uploading     = true;
    }

    // ── 内容行 ────────────────────────────────────────────────────────────

    private enum RowKind { TEXT, BLOCK_GRID, SPECIAL_GRID, CONVERSION, FREE_GRID }

    private record ContentRow(RowKind kind, Component text, int color,
                              List<BlueprintSanitizer.ScanEntry> grid,
                              List<BlueprintSanitizer.ScanEntity> specialGrid,
                              S2C_IllegalBlocksListMessage.ConversionEntry conversion,
                              List<String> freeGrid) {

        static ContentRow text(Component text, int color) {
            return new ContentRow(RowKind.TEXT, text, color, List.of(), null, null, null);
        }

        static ContentRow blockGrid(List<BlueprintSanitizer.ScanEntry> grid) {
            return new ContentRow(RowKind.BLOCK_GRID, null, 0, grid, null, null, null);
        }

        static ContentRow specialGrid(List<BlueprintSanitizer.ScanEntity> grid) {
            return new ContentRow(RowKind.SPECIAL_GRID, null, 0, List.of(), grid, null, null);
        }

        static ContentRow conversion(S2C_IllegalBlocksListMessage.ConversionEntry entry) {
            return new ContentRow(RowKind.CONVERSION, null, 0, List.of(), null, entry, null);
        }

        static ContentRow freeGrid(List<String> ids) {
            return new ContentRow(RowKind.FREE_GRID, null, 0, List.of(), null, null, ids);
        }

        boolean isGrid() { return kind != RowKind.TEXT; }
    }

    private static ContentRow separator() {
        return ContentRow.text(Component.literal("----------------------------------------"), 0x888888);
    }

    private List<ContentRow> buildRows() {
        List<ContentRow> rows = new ArrayList<>();
        // 上传中：只显示一行「文件状态(上传中…)」，等 S2C_UploadReportMessage 换真实内容
        if (uploading) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.upload_report.file.uploading"), COLOR_WHITE));
            return rows;
        }

        // ── 文件状态 ────────────────────────────────────────────────────
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.section.file")
                        .withStyle(ChatFormatting.BOLD), COLOR_WHITE));
        String format = fileExt.startsWith(".") ? fileExt.substring(1) : fileExt;
        String fileNameDisplay = fileName.isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : fileName;
        // 手拼 Component：translatable 的参数塞彩色 Component 在 1.20.1 会把内部表示
        // 当普通文本打出来（实测乱码），必须 literal 分段 + withStyle 逐个上色。
        MutableComponent detailRow = Component.literal(fileNameDisplay).withStyle(ChatFormatting.YELLOW);
        // 工程蓝图报告（宿主回调，无磁盘文件）不画扩展名 / 原始字节数，免得出现「?」「0 B」
        if (!fileExt.isEmpty()) {
            detailRow = detailRow
                    .append(lit(" · "))
                    .append(lit(format).withStyle(ChatFormatting.AQUA))
                    .append(lit(" · "))
                    .append(Component.translatable("spatial_blueprint.gui.upload_report.label.orig"))
                    .append(lit(" "))
                    .append(lit(fmtBytes(fileSize)))
                    .append(lit(" / "))
                    .append(Component.translatable("spatial_blueprint.gui.upload_report.label.limit"))
                    .append(lit(" "))
                    .append(lit(fmtBytes(ConfigAccess.get(BlueprintConfig.MAX_UPLOAD_FILE_BYTES))).withStyle(ChatFormatting.GREEN));
        }
        rows.add(ContentRow.text(detailRow, COLOR_WHITE));

        if (!fileExt.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.upload_report.label.compressed")
                            .append(lit(" "))
                            .append(lit(fmtBytes(compressedBytes)).withStyle(ChatFormatting.AQUA)), COLOR_WHITE));
        }

        long boundingVolume = (long) sizeX * sizeY * sizeZ;
        Component sizeRow = Component.translatable("spatial_blueprint.gui.upload_report.label.size")
                .append(lit(" "))
                .append(lit(sizeX + "x" + sizeY + "x" + sizeZ).withStyle(ChatFormatting.AQUA))
                .append(lit(" · "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.blocks"))
                .append(lit(" "))
                .append(lit(fmtInt(totalBlocks)).withStyle(ChatFormatting.YELLOW))
                .append(lit(" · "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.bbox"))
                .append(lit(" "))
                .append(lit(fmtInt(boundingVolume)))
                .append(lit(" / "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.limit"))
                .append(lit(" "))
                .append(lit(fmtInt(ConfigAccess.get(BlueprintConfig.MAX_BOUNDING_VOLUME))).withStyle(ChatFormatting.GREEN));
        rows.add(ContentRow.text(sizeRow, COLOR_WHITE));

        long perEntry   = perEntryLimit();
        long totalLimit = totalNbtLimit();
        boolean totalOver  = nbtTotalBytes > totalLimit;
        boolean singleOver = nbtMaxSingleBytes > perEntry;
        long diagnosticCap = perEntry * BlueprintSanitizer.DIAGNOSTIC_CAP_MULTIPLIER;
        boolean isLowerBound = nbtMaxSingleBytes >= diagnosticCap;
        String maxSingleDisplay = (isLowerBound ? "≥" : "") + fmtBytes(nbtMaxSingleBytes);
        Component nbtRow = Component.translatable("spatial_blueprint.gui.upload_report.label.nbt_vol")
                .append(lit(" "))
                .append(lit(fmtBytes(nbtTotalBytes))
                        .withStyle(totalOver ? ChatFormatting.RED : ChatFormatting.WHITE))
                .append(lit(" / "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.limit"))
                .append(lit(" "))
                .append(lit(fmtBytes(totalLimit)).withStyle(ChatFormatting.GREEN))
                .append(lit(" · "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.max_single"))
                .append(lit(" "))
                .append(lit(maxSingleDisplay)
                        .withStyle(singleOver ? ChatFormatting.YELLOW : ChatFormatting.WHITE))
                .append(lit(" / "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.limit"))
                .append(lit(" "))
                .append(lit(fmtBytes(perEntry)).withStyle(ChatFormatting.GREEN));
        rows.add(ContentRow.text(nbtRow, COLOR_WHITE));

        // ── 上传权限 ────────────────────────────────────────────────────
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.section.permission")
                        .withStyle(ChatFormatting.BOLD), COLOR_WHITE));
        rows.add(ContentRow.text(buildPermissionRow(), COLOR_WHITE));

        // ── 非法方块 ────────────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.illegal", illegalTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_RED));
        appendBlockSection(rows, illegal, illegalTotal,
                "spatial_blueprint.gui.upload_report.illegal_ok",
                "spatial_blueprint.gui.upload_report.illegal_conclusion");

        // ── 危险方块 ────────────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.dangerous", dangerousTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_YELLOW));
        appendBlockSection(rows, dangerous, dangerousTotal,
                "spatial_blueprint.gui.upload_report.dangerous_ok",
                "spatial_blueprint.gui.upload_report.dangerous_conclusion");

        // ── 超大 NBT 方块 ───────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.oversized", oversizedTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_YELLOW));
        appendBlockSection(rows, oversized, oversizedTotal,
                "spatial_blueprint.gui.upload_report.oversized_ok",
                "spatial_blueprint.gui.upload_report.oversized_conclusion");

        // ── 深层 NBT 方块 ───────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.deep_nbt", deepNbtTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_YELLOW));
        appendBlockSection(rows, deepNbt, deepNbtTotal,
                "spatial_blueprint.gui.upload_report.deep_nbt_ok",
                "spatial_blueprint.gui.upload_report.deep_nbt_conclusion");

        // ── 特殊实体 ────────────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.special", droppedTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_YELLOW));
        if (dropped.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.upload_report.entities_ok"), COLOR_GREEN));
        } else {
            int gridRows = (dropped.size() + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
            for (int r = 0; r < gridRows; r++) {
                int start = r * CELLS_PER_ROW;
                int end = Math.min(dropped.size(), start + CELLS_PER_ROW);
                rows.add(ContentRow.specialGrid(dropped.subList(start, end)));
            }
            if (droppedTotal > dropped.size()) {
                rows.add(ContentRow.text(Component.translatable(
                        "spatial_blueprint.gui.upload_report.truncated",
                        dropped.size(), droppedTotal), COLOR_GRAY));
            }
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.upload_report.special_conclusion"), COLOR_YELLOW));
        }

        // ── 物品转换 ────────────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.conversions", conversionsTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_CYAN));
        if (conversions.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.conversions_ok"), COLOR_GREEN));
        } else {
            for (S2C_IllegalBlocksListMessage.ConversionEntry e : conversions) {
                rows.add(ContentRow.conversion(e));
            }
            if (conversionsTotal > conversions.size()) {
                rows.add(ContentRow.text(Component.translatable(
                        "spatial_blueprint.gui.upload_report.truncated",
                        conversions.size(), conversionsTotal), COLOR_GRAY));
            }
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.upload_report.conversions_conclusion"), COLOR_YELLOW));
        }

        // ── 免费方块 ────────────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(
                Component.translatable("spatial_blueprint.gui.upload_report.free", freeTotal)
                        .withStyle(ChatFormatting.BOLD), COLOR_CYAN));
        if (freeBlocks.isEmpty()) {
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.report.free_ok"), COLOR_GREEN));
        } else {
            int gridRows = (freeBlocks.size() + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
            for (int r = 0; r < gridRows; r++) {
                int start = r * CELLS_PER_ROW;
                int end = Math.min(freeBlocks.size(), start + CELLS_PER_ROW);
                rows.add(ContentRow.freeGrid(freeBlocks.subList(start, end)));
            }
            if (freeTotal > freeBlocks.size()) {
                rows.add(ContentRow.text(Component.translatable(
                        "spatial_blueprint.gui.upload_report.truncated",
                        freeBlocks.size(), freeTotal), COLOR_GRAY));
            }
            rows.add(ContentRow.text(
                    Component.translatable("spatial_blueprint.gui.upload_report.free_conclusion"), COLOR_YELLOW));
        }

        // ── 越界丢弃 ────────────────────────────────────────────────────
        rows.add(separator());
        rows.add(ContentRow.text(Component.translatable(
                "spatial_blueprint.gui.upload_report.out_of_range", outOfRangeDropped), COLOR_GRAY));

        return rows;
    }

    /** 服务端阈值优先；只有加载态（报告还没到）才回落到本地配置。 */
    private long perEntryLimit() {
        return nbtPerEntryLimit > 0 ? nbtPerEntryLimit
                : ConfigAccess.get(BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES);
    }

    private long totalNbtLimit() {
        return nbtTotalLimit > 0 ? nbtTotalLimit
                : ConfigAccess.get(BlueprintConfig.MAX_TOTAL_NBT_BYTES);
    }

    private Component buildPermissionRow() {
        String player = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getScoreboardName() : "?";
        String perm = canForce
                ? Component.translatable("spatial_blueprint.gui.upload_report.permission.op").getString()
                : Component.translatable("spatial_blueprint.gui.upload_report.permission.normal").getString();
        int displayLimit = volumeLimit;
        if (displayLimit == 0) {
            displayLimit = canForce
                    ? BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME
                    : S2C_ConfigSyncMessage.maxVolumeFor(category);
        }
        boolean pass = canForce || totalBlocks <= displayLimit;
        String passText = Component.translatable(pass
                ? "spatial_blueprint.gui.upload_report.permission.pass"
                : "spatial_blueprint.gui.upload_report.permission.fail").getString();
        return Component.literal(player).withStyle(ChatFormatting.YELLOW)
                .append(lit(" · "))
                .append(lit(perm).withStyle(canForce ? ChatFormatting.GOLD : ChatFormatting.GRAY))
                .append(lit(" · "))
                .append(Component.translatable("spatial_blueprint.gui.upload_report.label.vol_limit"))
                .append(lit(" "))
                .append(lit(fmtInt(displayLimit)).withStyle(ChatFormatting.AQUA))
                .append(lit(" · "))
                .append(lit(passText).withStyle(pass
                        ? ChatFormatting.GREEN : ChatFormatting.RED,
                        ChatFormatting.BOLD));
    }

    private void appendBlockSection(List<ContentRow> rows, List<BlueprintSanitizer.ScanEntry> blocks,
                                    int total, String okKey, String conclusionKey) {
        if (blocks.isEmpty()) {
            rows.add(ContentRow.text(Component.translatable(okKey), COLOR_GREEN));
            return;
        }
        int gridRows = (blocks.size() + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
        for (int r = 0; r < gridRows; r++) {
            int start = r * CELLS_PER_ROW;
            int end = Math.min(blocks.size(), start + CELLS_PER_ROW);
            rows.add(ContentRow.blockGrid(blocks.subList(start, end)));
        }
        if (total > blocks.size()) {
            rows.add(ContentRow.text(Component.translatable(
                    "spatial_blueprint.gui.upload_report.truncated",
                    blocks.size(), total), COLOR_GRAY));
        }
        rows.add(ContentRow.text(Component.translatable(conclusionKey), COLOR_YELLOW));
    }

    // ── 布局 ──────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        if (uploading) return;   // 加载态不放任何按钮，只等扫描结果
        int cx = this.width / 2;
        int bannerY = (this.height - BANNER_H) / 2;
        int btnY = bannerY + BANNER_H + TITLE_GAP;

        int wSanitize = 120, wForce = 100, wCancel = 80;
        if (forceRequested) {
            // 强制上传报告：仅 [强制上传][取消]，OP 强制原样保存
            int totalW = wForce + wCancel + BTN_GAP;
            int x = cx - totalW / 2;
            addRenderableWidget(Button.builder(
                            Component.translatable("spatial_blueprint.gui.upload_report.btn.force")
                                    .withStyle(ChatFormatting.RED),
                            b -> confirm(true))
                    .bounds(x, btnY, wForce, BTN_H)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.upload_report.tooltip.force")))
                    .build());
            x += wForce + BTN_GAP;
            addRenderableWidget(Button.builder(
                            Component.translatable("spatial_blueprint.gui.upload_report.btn.cancel"),
                            b -> onClose())
                    .bounds(x, btnY, wCancel, BTN_H)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.upload_report.tooltip.cancel")))
                    .build());
        } else {
            // 普通上传：仅 [净化并上传/上传][取消]，不显示强制按钮
            int totalW = wSanitize + wCancel + BTN_GAP;
            int x = cx - totalW / 2;
            addRenderableWidget(Button.builder(
                            Component.translatable(dirty
                                    ? "spatial_blueprint.gui.upload_report.btn.sanitize"
                                    : "spatial_blueprint.gui.upload_report.btn.upload"),
                            b -> confirm(false))
                    .bounds(x, btnY, wSanitize, BTN_H)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.upload_report.tooltip.sanitize")))
                    .build());
            x += wSanitize + BTN_GAP;
            addRenderableWidget(Button.builder(
                            Component.translatable("spatial_blueprint.gui.upload_report.btn.cancel"),
                            b -> onClose())
                    .bounds(x, btnY, wCancel, BTN_H)
                    .tooltip(Tooltip.create(Component.translatable(
                            "spatial_blueprint.gui.upload_report.tooltip.cancel")))
                    .build());
        }
    }

    private void confirm(boolean force) {
        if (submitted) return;
        if (confirmHost != null) {
            // 工程蓝图报告：不发上传确认、不退回 parent，由宿主回调开创建页
            submitted = true;
            confirmHost.accept(force
                    ? com.xioyim.spatialblueprint.util.CaptureMode.FORCE
                    : com.xioyim.spatialblueprint.util.CaptureMode.SANITIZE);
            return;
        }
        submitted = true;
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_ConfirmUploadMessage(force));
        Minecraft.getInstance().setScreen(parent);
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    private final List<CellRect> cellRects = new ArrayList<>();
    private record CellRect(int x, int y, int w, int h, BlueprintSanitizer.ScanEntry entry) {}
    private final List<SpecialCellRect> specialCellRects = new ArrayList<>();
    private record SpecialCellRect(int x, int y, int w, int h, BlueprintSanitizer.ScanEntity entry) {}
    private final List<InfoCellRect> infoCellRects = new ArrayList<>();
    private record InfoCellRect(int x, int y, int w, int h, Component tip) {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);

        int screenW = this.width;
        int cx      = screenW / 2;
        int bannerY = (this.height - BANNER_H) / 2;

        int titleColor;
        Component title;
        if (uploading) {
            titleColor = COLOR_YELLOW;
            title = Component.translatable("spatial_blueprint.gui.upload_report.title")
                    .append(Component.translatable("spatial_blueprint.gui.upload_report.uploading_suffix"));
        } else {
            String stateKey;
            if (!illegal.isEmpty()) {
                titleColor = COLOR_RED;
                stateKey   = "spatial_blueprint.gui.report.state_danger";
            } else if (dirty) {
                titleColor = COLOR_YELLOW;
                stateKey   = "spatial_blueprint.gui.report.state_warn";
            } else {
                titleColor = COLOR_GREEN;
                stateKey   = "spatial_blueprint.gui.report.state_safe";
            }
            title = Component.translatable("spatial_blueprint.gui.upload_report.title")
                    .append(Component.translatable("spatial_blueprint.gui.report.title_state",
                            Component.translatable(stateKey).getString()));
        }

        g.fill(0, 0, screenW, this.height, 0xA0000000);
        g.fill(-OVERFLOW, bannerY, screenW + OVERFLOW, bannerY + BANNER_H, 0xFF141414);
        int lc = 0xFF3A3A3A;
        g.fill(-OVERFLOW, bannerY,                screenW + OVERFLOW, bannerY + 1,        lc);
        g.fill(-OVERFLOW, bannerY + BANNER_H - 1, screenW + OVERFLOW, bannerY + BANNER_H, lc);

        g.drawCenteredString(this.font, title, cx, bannerY - TITLE_GAP, titleColor);

        int contentTop    = bannerY + CONTENT_PAD;
        int contentBottom = bannerY + BANNER_H - CONTENT_PAD;
        List<ContentRow> rows = buildRows();
        int maxScr = maxScroll(rows);
        if (scrollOffset > maxScr) scrollOffset = maxScr;

        cellRects.clear();
        specialCellRects.clear();
        infoCellRects.clear();

        g.enableScissor(0, contentTop, screenW, contentBottom);
        int cursorY = contentTop - scrollOffset;
        for (ContentRow row : rows) {
            if (row.isGrid()) {
                if (cursorY + CELL >= contentTop && cursorY <= contentBottom) {
                    switch (row.kind()) {
                        case SPECIAL_GRID -> renderSpecialGrid(g, cx, cursorY, row.specialGrid(), mx, my);
                        case CONVERSION   -> renderConversionRow(g, cx, cursorY, row.conversion(), mx, my);
                        case FREE_GRID    -> renderFreeGrid(g, cx, cursorY, row.freeGrid(), mx, my);
                        default           -> renderBlockGrid(g, cx, cursorY, row.grid(), mx, my);
                    }
                }
                cursorY += BLOCK_ROW_H;
            } else {
                if (cursorY < contentBottom && cursorY + LINE_H > contentTop) {
                    // 文本行也在 20px 槽位内垂直居中：与方块格子的 20px 节奏对齐，
                    // 避免 9px 高的字顶在槽位上沿、视觉上像「占不满一行」。
                    g.drawCenteredString(this.font, row.text(), cx,
                            cursorY + (LINE_H - this.font.lineHeight) / 2, row.color());
                }
                cursorY += LINE_H;
            }
        }
        g.disableScissor();

        super.render(g, mx, my, pt);
        renderCellTooltip(g, mx, my);
    }

    private void renderBlockGrid(GuiGraphics g, int cx, int rowY,
                                 List<BlueprintSanitizer.ScanEntry> entries, int mouseX, int mouseY) {
        int inRow = Math.min(CELLS_PER_ROW, entries.size());
        int actualRowW = inRow * CELL + (inRow - 1) * CELL_GAP;
        int rowStartX = cx - actualRowW / 2;
        for (int i = 0; i < inRow; i++) {
            BlueprintSanitizer.ScanEntry entry = entries.get(i);
            int cellX = rowStartX + i * (CELL + CELL_GAP);
            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL
                    && mouseY >= rowY && mouseY < rowY + CELL;
            drawBlockCell(g, cellX, rowY, entry, hovered);
            cellRects.add(new CellRect(cellX, rowY, CELL, CELL, entry));
        }
    }

    private void drawBlockCell(GuiGraphics g, int x, int y,
                               BlueprintSanitizer.ScanEntry entry, boolean hovered) {
        int border = hovered ? COLOR_WHITE : 0x666666;
        g.fill(x, y, x + CELL, y + 1, border);
        g.fill(x, y + CELL - 1, x + CELL, y + CELL, border);
        g.fill(x, y, x + 1, y + CELL, border);
        g.fill(x + CELL - 1, y, x + CELL, y + CELL, border);

        ItemStack stack = itemStackFor(entry.id());
        if (!stack.isEmpty()) {
            renderItemSafe(g, stack, x + 2, y + 2);
        } else {
            String unk = Component.translatable("spatial_blueprint.gui.report.unknown_block").getString();
            if (this.font.width(unk) > CELL - 4) {
                unk = Component.translatable("spatial_blueprint.gui.report.unknown_block_short").getString();
            }
            g.drawCenteredString(this.font, Component.literal(unk), x + CELL / 2,
                    y + (CELL - this.font.lineHeight) / 2 + 1, COLOR_RED);
        }
    }

    private static Item itemFor(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.get(rl);
    }

    private static ItemStack itemStackFor(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) return new ItemStack(item);
        }
        if (rl != null && blockHasLangName(blockId)) {
            Item barrier = itemFor("minecraft:barrier");
            if (barrier != null) return new ItemStack(barrier);
        }
        return ItemStack.EMPTY;
    }

    private static boolean blockHasLangName(String blockId) {
        String key = "block." + blockId.replace(':', '.');
        return net.minecraft.client.resources.language.I18n.exists(key);
    }

    private void renderSpecialGrid(GuiGraphics g, int cx, int rowY,
                                   List<BlueprintSanitizer.ScanEntity> entries, int mouseX, int mouseY) {
        int inRow = Math.min(CELLS_PER_ROW, entries.size());
        int actualRowW = inRow * CELL + (inRow - 1) * CELL_GAP;
        int rowStartX = cx - actualRowW / 2;
        for (int i = 0; i < inRow; i++) {
            BlueprintSanitizer.ScanEntity entry = entries.get(i);
            int cellX = rowStartX + i * (CELL + CELL_GAP);
            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL
                    && mouseY >= rowY && mouseY < rowY + CELL;
            int border = hovered ? COLOR_WHITE : 0x666666;
            g.fill(cellX, rowY, cellX + CELL, rowY + 1, border);
            g.fill(cellX, rowY + CELL - 1, cellX + CELL, rowY + CELL, border);
            g.fill(cellX, rowY, cellX + 1, rowY + CELL, border);
            g.fill(cellX + CELL - 1, rowY, cellX + CELL, rowY + CELL, border);

            ItemStack stack = specialItemStack(entry);
            if (!stack.isEmpty()) {
                renderItemSafe(g, stack, cellX + 2, rowY + 2);
            }
            specialCellRects.add(new SpecialCellRect(cellX, rowY, CELL, CELL, entry));
        }
    }

    private static ItemStack specialItemStack(BlueprintSanitizer.ScanEntity entry) {
        switch (entry.kind()) {
            case "armor_stand" -> {
                Item st = itemFor("minecraft:armor_stand");
                if (st != null) return new ItemStack(st);
            }
            case "mob" -> {
                Item spawn = itemFor(entry.entityId() + "_spawn_egg");
                if (spawn != null && spawn != net.minecraft.world.item.Items.AIR) return new ItemStack(spawn);
                Item generic = itemFor("minecraft:cow_spawn_egg");
                if (generic != null) return new ItemStack(generic);
            }
            case "item_frame" -> {
                Item frame = itemFor(entry.entityId());
                if (frame != null) return new ItemStack(frame);
            }
            default -> {}
        }
        return ItemStack.EMPTY;
    }

    private void renderConversionRow(GuiGraphics g, int cx, int rowY,
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

        drawItemCell(g, x, rowY, sourceIcon(entry.sourceBlockId()),
                mouseX, mouseY, Component.translatable(
                        "spatial_blueprint.gui.report.conversion_tip_source",
                        entry.sourceBlockId(), itemLangName(entry.sourceBlockId()),
                        entry.capturedCount(), entry.threshold()));
        x += CELL + 2;
        g.drawString(this.font, srcNum, x, textY, COLOR_WHITE, false);
        x += this.font.width(srcNum);
        g.drawString(this.font, arrow, x, textY, COLOR_CYAN, false);
        x += this.font.width(arrow);

        for (S2C_IllegalBlocksListMessage.TargetItem t : entry.targets()) {
            drawItemCell(g, x, rowY, itemStackFor(t.itemId()),
                    mouseX, mouseY, Component.translatable(
                            "spatial_blueprint.gui.report.conversion_tip_target",
                            t.itemId(), itemLangName(t.itemId()), t.count()));
            x += CELL + 2;
            String num = "x" + t.count();
            g.drawString(this.font, num, x, textY, COLOR_GREEN, false);
            x += this.font.width(num) + CELL_GAP;
        }
    }

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
        return itemStackFor(blockId);
    }

    private void renderFreeGrid(GuiGraphics g, int cx, int rowY,
                                List<String> ids, int mouseX, int mouseY) {
        int inRow = Math.min(CELLS_PER_ROW, ids.size());
        int actualRowW = inRow * CELL + (inRow - 1) * CELL_GAP;
        int rowStartX = cx - actualRowW / 2;
        for (int i = 0; i < inRow; i++) {
            String id = ids.get(i);
            drawItemCell(g, rowStartX + i * (CELL + CELL_GAP), rowY, sourceIcon(id),
                    mouseX, mouseY, Component.translatable(
                            "spatial_blueprint.gui.report.free_tip", id, itemLangName(id)));
        }
    }

    private void drawItemCell(GuiGraphics g, int x, int y, ItemStack stack,
                              int mouseX, int mouseY, Component tip) {
        boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
        int border = hovered ? COLOR_WHITE : 0x666666;
        g.fill(x, y, x + CELL, y + 1, border);
        g.fill(x, y + CELL - 1, x + CELL, y + CELL, border);
        g.fill(x, y, x + 1, y + CELL, border);
        g.fill(x + CELL - 1, y, x + CELL, y + CELL, border);
        if (!stack.isEmpty()) renderItemSafe(g, stack, x + 2, y + 2);
        infoCellRects.add(new InfoCellRect(x, y, CELL, CELL, tip));
    }

    private static void renderItemSafe(GuiGraphics g, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (KNOWN_CRASH.contains(key)) {
            try {
                g.renderItem(new ItemStack(net.minecraft.world.level.block.Blocks.BARRIER), x, y);
            } catch (Throwable ignored) {}
            return;
        }
        try {
            g.renderItem(stack, x, y);
        } catch (Throwable ex) {
            KNOWN_CRASH.add(key);
            try {
                Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } catch (Throwable ignored) {}
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.warn(
                "[UploadReport][renderItem] threw for {}: {}", key, ex.toString());
        }
    }

    private void renderCellTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (InfoCellRect r : infoCellRects) {
            if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                drawMultilineTooltip(g, r.tip(), mouseX, mouseY);
                return;
            }
        }
        for (SpecialCellRect r : specialCellRects) {
            if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                BlueprintSanitizer.ScanEntity e = r.entry();
                Component tip;
                switch (e.kind()) {
                    case "armor_stand" -> tip = Component.translatable(
                            "spatial_blueprint.gui.upload_report.special_tip_armor",
                            e.entityId(), e.x(), e.y(), e.z(),
                            Component.translatable(e.hasEquipment()
                                    ? "spatial_blueprint.gui.upload_report.special_armor_equipped"
                                    : "spatial_blueprint.gui.upload_report.special_armor_plain").getString());
                    case "mob" -> tip = Component.translatable(
                            "spatial_blueprint.gui.upload_report.special_tip_mob",
                            e.entityId(), itemLangName(e.entityId()), e.x(), e.y(), e.z());
                    default -> tip = Component.translatable(
                            "spatial_blueprint.gui.upload_report.special_tip_frame",
                            e.entityId(), e.x(), e.y(), e.z(), e.itemId());
                }
                drawMultilineTooltip(g, tip, mouseX, mouseY);
                return;
            }
        }
        for (CellRect r : cellRects) {
            if (mouseX >= r.x() && mouseX < r.x() + r.w() && mouseY >= r.y() && mouseY < r.y() + r.h()) {
                BlueprintSanitizer.ScanEntry e = r.entry();
                String name = itemLangName(e.id());
                String idLabel = name.equals(e.id()) ? e.id() : e.id() + " (" + name + ")";
                drawMultilineTooltip(g, Component.translatable(
                        "spatial_blueprint.gui.upload_report.tooltip.block",
                        idLabel, e.x(), e.y(), e.z()), mouseX, mouseY);
                return;
            }
        }
    }

    private void drawMultilineTooltip(GuiGraphics g, Component tip, int mouseX, int mouseY) {
        int maxWidth = Math.max(160, this.width / 2);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : tip.getString().split("\n")) {
            if (line.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            } else {
                lines.addAll(this.font.split(Component.literal(line), maxWidth));
            }
        }
        g.renderTooltip(this.font, lines, mouseX, mouseY);
    }

    private static String itemLangName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(item).getHoverName().getString();
            }
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                return block.getName().getString();
            }
        }
        // 特殊实体里的实体 ID（minecraft:cow 等）既不是物品也不是方块，按实体译名解析
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

    /** literal 段：手拼彩色行时用，避免把 Component 塞进 translatable 参数。返回 MutableComponent 以便链式 withStyle。 */
    private static net.minecraft.network.chat.MutableComponent lit(String s) {
        return Component.literal(s);
    }

    private static String fmtBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            String s = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + " MB";
        }
        String s = String.format(Locale.ROOT, "%.1f", bytes / 1024.0);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + " KB";
    }

    private static String fmtInt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    // ── 滚动 ──────────────────────────────────────────────────────────────

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

    private int maxScroll(List<ContentRow> rows) {
        int overflow = totalContentHeight(rows) - viewHeight();
        if (overflow <= 0) return 0;
        return (overflow + LINE_H - 1) / LINE_H * LINE_H;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<ContentRow> rows = buildRows();
        int maxScr = maxScroll(rows);
        scrollOffset = Math.max(0, Math.min(maxScr, scrollOffset - (int) Math.round(delta) * LINE_H));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { this.onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // 不显式通知服务端取消：暂存有 TTL，到点自动作废，少一条消息少一处状态。
        // 退回上传页面，而不是关闭全部 UI。
        Minecraft.getInstance().setScreen(parent);
    }
}
