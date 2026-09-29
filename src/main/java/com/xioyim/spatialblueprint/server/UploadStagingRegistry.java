package com.xioyim.spatialblueprint.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上传分片组包注册表（SB-013，服务端）。
 *
 * <p>大文件上传时客户端先发 {@code C2S_UploadBeginMessage} 声明 uploadId / 总块数 /
 * 总字节数，随后逐块发 {@code C2S_UploadChunkMessage}；全部到齐后组装出完整
 * gzip 字节并交回 {@code C2S_UploadProjectionTemplateMessage.handleAssembled}，
 * 走与单包完全相同的校验 → 扫描 → 写入 → 报告流程。</p>
 *
 * <p>配额与清理：同玩家最多 1 个未完成组包（Begin 覆盖旧的）；组包 5 分钟 TTL，
 * begin/append 进入时顺手扫除过期项。块大小上限 30000 字节、总字节上限走配置，
 * 均在消息入口校验，这里只做组装。</p>
 */
public final class UploadStagingRegistry {

    private static final long TTL_MS = 5L * 60L * 1000L;

    private static final class Staging {
        final UUID playerId;
        final int totalChunks;
        final long totalBytes;
        final String category;
        final boolean forceRequested;
        final long createdAt;
        final List<byte[]> chunks;

        Staging(UUID playerId, int totalChunks, long totalBytes, String category, boolean forceRequested) {
            this.playerId = playerId;
            this.totalChunks = totalChunks;
            this.totalBytes = totalBytes;
            this.category = category;
            this.forceRequested = forceRequested;
            this.createdAt = System.currentTimeMillis();
            this.chunks = new ArrayList<>(totalChunks);
            for (int i = 0; i < totalChunks; i++) this.chunks.add(null);
        }
    }

    private static final Map<UUID, Staging> STAGINGS = new ConcurrentHashMap<>();

    private UploadStagingRegistry() {}

    /** Begin：声明一个组包；同玩家旧组包直接丢弃。返回 false 表示参数非法。 */
    public static synchronized boolean begin(UUID uploadId, UUID playerId, int totalChunks,
                                             long totalBytes, String category, boolean forceRequested) {
        if (uploadId == null || playerId == null || totalChunks < 2 || totalBytes <= 0
                || category == null || category.isEmpty()) return false;
        sweep();
        // 同玩家同时只允许一个未完成组包：先清掉该玩家名下旧的。
        STAGINGS.entrySet().removeIf(e -> e.getValue().playerId.equals(playerId));
        STAGINGS.put(uploadId, new Staging(playerId, totalChunks, totalBytes, category, forceRequested));
        return true;
    }

    public enum AppendResult { OK, UNKNOWN_ID, WRONG_PLAYER, BAD_INDEX, DUPLICATE, OVERSIZE, COMPLETE }

    public static final class AppendOutcome {
        public final AppendResult result;
        /** COMPLETE 时为组装好的完整字节，否则为 null。 */
        public final byte[] assembled;
        public final String category;
        public final boolean forceRequested;

        AppendOutcome(AppendResult result) {
            this(result, null, null, false);
        }

        AppendOutcome(AppendResult result, byte[] assembled, String category, boolean forceRequested) {
            this.result = result;
            this.assembled = assembled;
            this.category = category;
            this.forceRequested = forceRequested;
        }
    }

    /**
     * 追加一块。最后一块到齐时组装并移除组包，返回 COMPLETE + 完整字节；
     * 调用方随后走 handleAssembled（完整流程）。
     */
    public static synchronized AppendOutcome append(UUID uploadId, UUID playerId, int index,
                                                    byte[] payload, long maxTotalBytes) {
        sweep();
        Staging s = STAGINGS.get(uploadId);
        if (s == null) return new AppendOutcome(AppendResult.UNKNOWN_ID);
        if (!s.playerId.equals(playerId)) return new AppendOutcome(AppendResult.WRONG_PLAYER);
        if (System.currentTimeMillis() - s.createdAt > TTL_MS) {
            STAGINGS.remove(uploadId);
            return new AppendOutcome(AppendResult.UNKNOWN_ID);
        }
        if (index < 0 || index >= s.totalChunks) return new AppendOutcome(AppendResult.BAD_INDEX);
        if (s.chunks.get(index) != null) return new AppendOutcome(AppendResult.DUPLICATE);
        if (payload == null || payload.length == 0 || payload.length > 30000) {
            return new AppendOutcome(AppendResult.OVERSIZE);
        }
        long running = payload.length;
        for (byte[] c : s.chunks) if (c != null) running += c.length;
        if (running > s.totalBytes || running > maxTotalBytes) {
            STAGINGS.remove(uploadId);
            return new AppendOutcome(AppendResult.OVERSIZE);
        }
        s.chunks.set(index, payload);
        for (byte[] c : s.chunks) if (c == null) return new AppendOutcome(AppendResult.OK);
        // 到齐：组装
        int total = 0;
        for (byte[] c : s.chunks) total += c.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] c : s.chunks) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length;
        }
        STAGINGS.remove(uploadId);
        return new AppendOutcome(AppendResult.COMPLETE, out, s.category, s.forceRequested);
    }

    /** 取消组包（客户端取消 / Begin 覆盖失败时清理）。 */
    public static synchronized void cancel(UUID uploadId, UUID playerId) {
        Staging s = STAGINGS.get(uploadId);
        if (s != null && s.playerId.equals(playerId)) STAGINGS.remove(uploadId);
    }

    /** 每 tick 调用：清掉超 5 分钟未完成的组包（备用；主路径靠 begin/append 顺手清）。 */
    public static synchronized void tick() {
        sweep();
    }

    private static void sweep() {
        long now = System.currentTimeMillis();
        STAGINGS.entrySet().removeIf(e -> now - e.getValue().createdAt > TTL_MS);
    }
}
