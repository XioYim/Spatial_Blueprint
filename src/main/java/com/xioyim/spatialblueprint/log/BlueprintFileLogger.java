package com.xioyim.spatialblueprint.log;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * 磁盘日志：两条独立通道，各写各的文件。
 *
 * <ul>
 *   <li>{@link Channel#PLACEMENT} — 常规放置记录。<b>只记成功</b>，且<b>只记 SAFE / SANITIZE</b>
 *       捕获的蓝图；强制捕获(FORCE)走管理员通道。</li>
 *   <li>{@link Channel#ADMIN} — 管理员追查用：次元蓝图归档、FORCE 蓝图放置、
 *       材料永久销毁、上传被拒等。</li>
 * </ul>
 *
 * <p>与 {@link BlueprintLogger} 的区别：那个只往游戏主日志（{@code latest.log}）打印，
 * 会淹没在 Forge 的日志洪流里；本类写<b>独立文件</b>，按日期滚动，供事后审计。</p>
 *
 * <p><b>同步写 + 每条 flush</b>：放置蓝图不是每 tick 的高频事件，这点 IO 换的是
 * 服务器崩溃时日志不丢。</p>
 */
public final class BlueprintFileLogger {

    public enum Channel {
        /** 常规放置记录，保留天数较短。 */
        PLACEMENT("placement"),
        /** 管理员事件，保留天数较长（体积小、价值高）。 */
        ADMIN("admin");

        final String prefix;
        Channel(String prefix) { this.prefix = prefix; }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE  = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static Path logDir;

    // 每个通道各自持有一个 writer 与它对应的日期，跨天时换文件
    private static final BufferedWriter[] writers  = new BufferedWriter[Channel.values().length];
    private static final LocalDate[]      openDays = new LocalDate[Channel.values().length];

    private BlueprintFileLogger() {}

    /** 与 {@code BlueprintStorage.init()} 同期调用：建目录并清理超期文件。 */
    public static void init() {
        logDir = FMLPaths.CONFIGDIR.get().resolve("spatial_blueprint").resolve("logs");
        try {
            Files.createDirectories(logDir);
            SpatialBlueprintMod.LOGGER.info("[BlueprintFileLogger] Log directory: {}", logDir);
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.error("[BlueprintFileLogger] Failed to create log dir: {}", e.getMessage());
            logDir = null;
            return;
        }
        purgeExpired();
    }

    /** 服务器关闭时释放句柄。每条已经 flush 过，这里只是收尾。 */
    public static void close() {
        for (int i = 0; i < writers.length; i++) {
            if (writers[i] != null) {
                try { writers[i].close(); } catch (IOException ignored) {}
                writers[i] = null;
                openDays[i] = null;
            }
        }
    }

    // ── 对外记录方法 ──────────────────────────────────────────────────────

    /**
     * 常规放置成功。
     *
     * <p>调用方需自行确保 {@code saveMode != FORCE} —— FORCE 走
     * {@link #forcePlace}，两条通道不重复记。</p>
     */
    public static void placement(ServerPlayer player, String blueprintId, String blueprintName,
                                 String category, String saveMode, String dimension,
                                 BlockPos origin, BlockPos end, int blocks) {
        write(Channel.PLACEMENT, "PLACE",
                "Player: " + who(player) + " | Blueprint: " + quoted(blueprintName) + " (" + blueprintId + ")",
                "  Dimension: " + dimension + " | Origin: " + xyz(origin) + " | End: " + xyz(end),
                "  Blocks: " + blocks + " | Mode: " + saveMode + " | Category: " + category);
    }

    /** 强制捕获(FORCE)蓝图的放置 —— 内容未经净化，必须留档。 */
    public static void forcePlace(ServerPlayer player, String blueprintId, String blueprintName,
                                  String category, String dimension,
                                  BlockPos origin, BlockPos end, int blocks) {
        write(Channel.ADMIN, "FORCE_PLACE",
                "Player: " + who(player) + " | Blueprint: " + quoted(blueprintName) + " (" + blueprintId + ")",
                "  Dimension: " + dimension + " | Origin: " + xyz(origin) + " | End: " + xyz(end),
                "  Blocks: " + blocks + " | Mode: FORCE | Category: " + category,
                "  Warning: captured with OP bypass — contents were never sanitized");
    }

    /** OP 归档次元蓝图（文件改名 .bak，不是真删）。 */
    public static void archiveDimensional(ServerPlayer op, String blueprintId, String blueprintName,
                                          String ownerName, String ownerUuid, String bakName) {
        write(Channel.ADMIN, "ARCHIVE_DIMENSIONAL",
                "OP: " + who(op) + " | Blueprint: " + quoted(blueprintName) + " (" + blueprintId + ")",
                "  Owner: " + (ownerName.isEmpty() ? "<unknown>" : ownerName)
                        + " (" + (ownerUuid.isEmpty() ? "<no owner>" : ownerUuid) + ")",
                "  Archived to: " + bakName);
    }

    /** 玩家丢弃孤儿次元袋 —— 材料永久消失，事后申诉需要凭据。 */
    public static void discardOrphanBag(ServerPlayer player, String blueprintId,
                                        String blueprintName, int itemCount) {
        write(Channel.ADMIN, "DISCARD_ORPHAN_BAG",
                "Player: " + who(player) + " | Blueprint: " + quoted(blueprintName) + " (" + blueprintId + ")",
                "  Items permanently lost: " + itemCount);
    }

    /**
     * 玩家上线时发现名下有孤儿次元袋。
     *
     * <p>孤儿袋只可能由「管理员删掉了蓝图文件」产生，记进管理员日志才能让 OP 看到
     * 自己的清理操作影响了谁。</p>
     */
    public static void orphanBagsDetected(ServerPlayer player, int count) {
        write(Channel.ADMIN, "ORPHAN_BAGS_DETECTED",
                "Player: " + who(player),
                "  Orphaned void bags on login: " + count,
                "  Cause: the matching blueprint files no longer exist (deleted or archived)");
    }

    /** 非 OP 手持 FORCE 蓝图被销毁。 */
    public static void forceBlueprintDestroyed(ServerPlayer player, String blueprintId,
                                               String blueprintName) {
        write(Channel.ADMIN, "FORCE_BLUEPRINT_DESTROYED",
                "Player: " + who(player) + " | Blueprint: " + quoted(blueprintName) + " (" + blueprintId + ")",
                "  Reason: force-captured blueprint held by a non-OP player; item destroyed",
                "  Note: the blueprint file itself was kept and can still be retrieved by an OP");
    }

    /**
     * OP 强制上传 —— 跳过净化，非法方块与危险 NBT 原样入库。
     *
     * <p>与放置端的 FORCE 一样，这类内容从未经过净化，必须留档。
     * 日志位置：{@code config/spatial_blueprint/logs/admin_YYYY-MM-DD.log}（独立于 latest.log）。</p>
     */
    public static void uploadForced(ServerPlayer op, String blueprintName,
                                    int illegalCount, int dangerousCount) {
        write(Channel.ADMIN, "UPLOAD_FORCED",
                "OP: " + who(op) + " | Blueprint: " + quoted(blueprintName),
                "  Kept without sanitizing: " + illegalCount + " illegal block(s), "
                        + dangerousCount + " block(s) with dangerous NBT",
                "  Warning: this file was stored exactly as uploaded");
    }

    /**
     * OP 强制上传（带完整扫描摘要）。
     *
     * <p>在原有 illegal/dangerous 基础上追加 oversized/deep/dropped 与分类信息，
     * 供事后审计「谁传了什么脏东西、强制保留了多少」。</p>
     */
    public static void uploadForced(ServerPlayer op, String blueprintName, String category,
                                    int totalBlocks, int illegalCount, int dangerousCount,
                                    int oversizedCount, int deepCount, int droppedCount) {
        write(Channel.ADMIN, "UPLOAD_FORCED",
                "OP: " + who(op) + " | Blueprint: " + quoted(blueprintName) + " | Category: " + category,
                "  Blocks: " + totalBlocks
                        + " | Illegal: " + illegalCount
                        + " | Dangerous: " + dangerousCount
                        + " | Oversized NBT: " + oversizedCount
                        + " | Deep NBT: " + deepCount
                        + " | Entities to drop: " + droppedCount,
                "  Kept without sanitizing — stored exactly as uploaded");
    }

    /**
     * 上传文件扫出了问题（还没决定怎么处理）。
     *
     * <p>记在这里是为了留下「谁传了什么脏东西」的痕迹 —— 哪怕玩家最后点了取消，
     * 反复上传含命令方块的文件本身就值得关注。</p>
     */
    public static void uploadFlagged(ServerPlayer player, String blueprintName,
                                     int illegalCount, int dangerousCount, int droppedEntities) {
        write(Channel.ADMIN, "UPLOAD_FLAGGED",
                "Player: " + who(player) + " | Blueprint: " + quoted(blueprintName),
                "  Illegal blocks: " + illegalCount
                        + " | Dangerous NBT: " + dangerousCount
                        + " | Entities to drop: " + droppedEntities,
                "  Awaiting player decision (sanitize / force / cancel)");
    }

    /** 上传被拒（含非法方块、体积超限等）—— 用于判断是否有人在试探。 */
    public static void uploadRejected(ServerPlayer player, String blueprintName, String reason) {
        write(Channel.ADMIN, "UPLOAD_REJECTED",
                "Player: " + who(player) + " | Blueprint: " + quoted(blueprintName),
                "  Reason: " + reason);
    }

    /**
     * 手动批准上传 —— <b>预留</b>，等审核队列（方案书第 5 节）落地后接入。
     *
     * @param reviewCode 审核码
     */
    public static void uploadApproved(ServerPlayer op, String blueprintId, String blueprintName,
                                      String uploaderName, String reviewCode) {
        write(Channel.ADMIN, "UPLOAD_APPROVED",
                "OP: " + who(op) + " | Blueprint: " + quoted(blueprintName) + " (" + blueprintId + ")",
                "  Uploader: " + uploaderName + " | Review code: " + reviewCode);
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 写一条多行记录：首行带时间戳与事件类型，后续行按调用方给的缩进原样输出。
     *
     * <p>任何 IO 异常只警告一次，绝不抛出 —— 日志写不下去不该影响玩家的正常操作。</p>
     */
    private static synchronized void write(Channel ch, String event, String head, String... extra) {
        if (logDir == null) return;
        try {
            BufferedWriter w = writerFor(ch);
            if (w == null) return;
            w.write("[" + LocalDateTime.now().format(STAMP) + "] " + event + " | " + head);
            w.newLine();
            for (String line : extra) {
                w.write(line);
                w.newLine();
            }
            w.flush();   // 每条落盘：崩溃时不丢
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.warn("[BlueprintFileLogger] write failed ({}): {}",
                    ch.prefix, e.getMessage());
        }
    }

    /** 取该通道的 writer，跨天时自动换文件。 */
    @Nullable
    private static BufferedWriter writerFor(Channel ch) throws IOException {
        int idx = ch.ordinal();
        LocalDate today = LocalDate.now();
        if (writers[idx] != null && today.equals(openDays[idx])) return writers[idx];

        if (writers[idx] != null) {
            try { writers[idx].close(); } catch (IOException ignored) {}
            writers[idx] = null;
        }
        Path file = logDir.resolve(ch.prefix + "_" + today.format(DATE) + ".log");
        writers[idx]  = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        openDays[idx] = today;
        return writers[idx];
    }

    /** 启动时清理超期日志。两条通道各有各的保留天数。 */
    private static void purgeExpired() {
        try (Stream<Path> files = Files.list(logDir)) {
            LocalDate today = LocalDate.now();
            files.filter(Files::isRegularFile).forEach(p -> {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".log")) return;
                Channel ch = null;
                for (Channel c : Channel.values()) {
                    if (fn.startsWith(c.prefix + "_")) { ch = c; break; }
                }
                if (ch == null) return;
                int keepDays = ch == Channel.ADMIN
                        ? BlueprintConfig.LOG_RETENTION_DAYS_ADMIN.get()
                        : BlueprintConfig.LOG_RETENTION_DAYS_PLACEMENT.get();
                if (keepDays <= 0) return;   // 0 = 永久保留
                try {
                    String dateStr = fn.substring(ch.prefix.length() + 1, fn.length() - 4);
                    LocalDate fileDate = LocalDate.parse(dateStr, DATE);
                    if (fileDate.isBefore(today.minusDays(keepDays))) {
                        Files.deleteIfExists(p);
                        SpatialBlueprintMod.LOGGER.info("[BlueprintFileLogger] Purged expired log: {}", fn);
                    }
                } catch (Exception ignored) {
                    // 文件名不合规就跳过，不猜测、不删除
                }
            });
        } catch (IOException e) {
            SpatialBlueprintMod.LOGGER.warn("[BlueprintFileLogger] Purge failed: {}", e.getMessage());
        }
    }

    private static String who(ServerPlayer player) {
        return player.getScoreboardName() + " (" + player.getStringUUID() + ")";
    }

    private static String xyz(@Nullable BlockPos pos) {
        return pos == null ? "(?)" : "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    private static String quoted(String s) {
        return "\"" + (s == null || s.isEmpty() ? "<unnamed>" : s) + "\"";
    }
}
