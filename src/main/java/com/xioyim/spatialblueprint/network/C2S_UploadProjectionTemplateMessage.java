package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.SafeNbtIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：上传本地文件，存成<b>空间蓝图 / 投影蓝图 / 投影模板</b>之一。
 *
 * <p>目标分类由上传界面的 tab 决定并随包传来（{@link #category}），服务端据此取
 * 体积上限与槽位上限，并强制覆写 {@code data.category} —— dat 内写的分类一律不信任。</p>
 *
 * <p><b>本消息不再直接保存。</b>它只做前置校验与扫描，然后把数据存进
 * {@code PendingUploadRegistry} 并回推 {@code S2C_UploadReportMessage}；
 * 玩家在检测报告页确认后，由 {@code C2S_ConfirmUploadMessage} 完成净化与落盘。</p>
 *
 * <p>本阶段的校验顺序：</p>
 * <ul>
 *   <li>仅创造模式</li>
 *   <li>上传限流（在任何解压之前 —— 否则光靠后面几道检测的成本就能被刷垮）</li>
 *   <li>原始字节数 ≤ {@link BlueprintConfig#MAX_UPLOAD_FILE_BYTES}（反序列化前检测，防内存炸弹）</li>
 *   <li>NBT 嵌套深度扫描（迭代 BFS，逐方块实体测深度；深度 &gt; {@link BlueprintConfig#MAX_NBT_DEPTH} 者于报告中列为“深层 NBT”，净化时清 NBT 保留方块，不再直接拒收整文件）</li>
 *   <li>包围盒体积 ≤ {@link BlueprintConfig#MAX_BOUNDING_VOLUME}</li>
 *   <li>坐标越界（[0,2048) 之外）的方块直接丢弃；名称与模板专属字段净化</li>
 *   <li>该分类的槽位未满（OP 豁免）</li>
 *   <li>方块总数 ≤ 该分类的体积上限（OP 豁免）</li>
 * </ul>
 *
 * <p>⚠ <b>容器清空与实体过滤不在这里做</b> —— 它们会让检测报告永远显示「干净」。
 * 那两步移到了确认环节。</p>
 *
 * <p>后续阶段将补齐 hasIllegalBlocks 标记（存服务器索引，供危险放置广播使用）/
 * 手动审核队列，详见项目根目录《上传文件检测细节和方案计划.md》第 5、11.8 节。</p>
 */
public class C2S_UploadProjectionTemplateMessage {

    private final byte[] compressedData;
    /**
     * 目标分类：{@code create}（空间）/ {@code project}（投影）/ {@code template}（投影模板）。
     *
     * <p>决定体积上限、槽位上限与最终发给玩家的物品类型。服务端不信任 dat 里写的
     * {@code category}，一律以本字段为准并强制覆写。</p>
     */
    private final String category;
    /** true = OP 发起的强制上传流程，FORCE 蓝图在此流程下不被拦截。 */
    private final boolean forceRequested;

    public C2S_UploadProjectionTemplateMessage(BlueprintData data, String category) {
        this(data, category, false);
    }

    public C2S_UploadProjectionTemplateMessage(BlueprintData data, String category, boolean forceRequested) {
        this.compressedData = compressPayload(data);
        this.category = normalize(category);
        this.forceRequested = forceRequested;
    }

    /** 分片组包完成后的入口：与单包 handle 走同一套流程（调用方已在服务端线程，嵌套 enqueue 下一 tick 执行）。 */
    static void handleAssembled(byte[] compressedData, String category, boolean forceRequested,
                                Supplier<NetworkEvent.Context> ctx) {
        handle(new C2S_UploadProjectionTemplateMessage(compressedData, category, forceRequested), ctx);
    }

    /** 供分片上传取原始压缩字节（与构造器同一压缩逻辑）。 */
    public static byte[] compressPayload(BlueprintData data) {
        return compress(data.serialize());
    }

    /** 已有压缩字节时直接构造（客户端小文件路径用，避免重复 serialize+gzip）。 */
    public static C2S_UploadProjectionTemplateMessage fromCompressed(byte[] compressedData,
                                                                     String category,
                                                                     boolean forceRequested) {
        return new C2S_UploadProjectionTemplateMessage(compressedData, category, forceRequested);
    }

    private C2S_UploadProjectionTemplateMessage(byte[] compressedData, String category, boolean forceRequested) {
        this.compressedData = compressedData;
        this.category = normalize(category);
        this.forceRequested = forceRequested;
    }

    /** 只认这三种分类，其余一律按投影模板（最严格的那一档）处理。 */
    private static String normalize(String c) {
        if ("create".equals(c) || "project".equals(c)) return c;
        return "template";
    }

    private static byte[] compress(CompoundTag tag) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.writeCompressed(tag, dos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static CompoundTag decompress(byte[] bytes) throws Exception {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return SafeNbtIo.readCompressed(dis, SafeNbtIo.MAX_UNCOMPRESSED_BYTES_UNTRUSTED);
        }
    }

    public static void encode(C2S_UploadProjectionTemplateMessage msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.compressedData);
        buf.writeUtf(msg.category, 32);
        buf.writeBoolean(msg.forceRequested);
    }

    /**
     * 网络层硬上限（32MB）——仅用于防止畸形数据包本身撑爆内存，
     * 真正生效的大小限制由 {@link BlueprintConfig#MAX_UPLOAD_FILE_BYTES} 在
     * {@link #handle} 中校验（可配置，默认 20MB）。
     */
    private static final int NETWORK_HARD_CAP_BYTES = 32 * 1024 * 1024;

    public static C2S_UploadProjectionTemplateMessage decode(FriendlyByteBuf buf) {
        byte[] data = buf.readByteArray(NETWORK_HARD_CAP_BYTES);
        String cat = buf.readUtf(32);
        boolean force = false;
        // 兼容旧包：剩余可读时才读 force 标志，否则按普通上传处理
        if (buf.isReadable()) {
            try { force = buf.readBoolean(); } catch (Exception ignored) {}
        }
        return new C2S_UploadProjectionTemplateMessage(data, cat, force);
    }

    public static void handle(C2S_UploadProjectionTemplateMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 创造模式限制只对「空间蓝图」这一类 —— 它是创造专属的蓝图类型。
            // 投影蓝图与投影模板生存玩家也能上传。
            if ("create".equals(msg.category) && !player.isCreative()) {
                sendResult(player, false, "spatial_blueprint.error.creative_only");
                return;
            }

            // ── [检测0] 上传限流（冷却 + 每分钟上限）───────────────────────
            // 必须在任何解压/反序列化之前：没有限流的话，光靠后面几道检测本身
            // 的处理成本（主线程同步执行）就能被刷包刷到卡垮服务器。
            if (!com.xioyim.spatialblueprint.server.BlueprintValidator.checkAndUpdateUploadRateLimit(player)) {
                sendResult(player, false, "spatial_blueprint.error.upload_rate_limited");
                return;
            }

            // ── [检测0.5] 槽位预检（最先：解压 / 净化 / 扫描全部不做，直接拒）─────
            // 槽位机制见 BlueprintValidator.checkSlot：按「上传者 + 目标分类」计数，
            // 上限在配置 slotLimits（create 默认 0=不限 / project、template 30 /
            // dimensional 10 / engineering 0），OP 豁免，上限 0 表示不限。
            // 上传落盘归属人就是上传者本人（见 C2S_ConfirmUploadMessage），此刻即可定准；
            // 满槽时连文件都不用解压，直接横幅「槽位不足，无法上传新蓝图」并打断后续全部检查。
            com.xioyim.spatialblueprint.server.BlueprintValidator.ValidationResult slotEarly =
                    com.xioyim.spatialblueprint.server.BlueprintValidator.checkSlot(player, msg.category);
            if (!slotEarly.valid()) {
                sendResult(player, false, "spatial_blueprint.error.upload_slot_full",
                        "spatial_blueprint.detail.slot_full", slotEarly.detailArgs());
                return;
            }

            // ── [检测1] 原始字节数检测（必须在反序列化之前，防止内存炸弹）─────
            // 高压缩比 NBT 可将几十 KB 的压缩数据展开为数百 MB 内存，
            // 先解压再检测大小会被绕过，因此必须检查压缩后的原始字节数。
            if (msg.compressedData.length > BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get()) {
                sendResult(player, false, "spatial_blueprint.error.upload_file_too_large",
                        "spatial_blueprint.error.detail.file_size",
                        fmtBytes(msg.compressedData.length),
                        fmtBytes(BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get()));
                return;
            }

            CompoundTag rootTag;
            try {
                rootTag = decompress(msg.compressedData);
            } catch (Exception e) {
                sendResult(player, false, "spatial_blueprint.error.invalid_blueprint_data");
                return;
            }

            // ── [检测2] NBT 嵌套深度（原整包拒收已移除）──────────────
            // 单体过深者改为逐方块实体的深层 NBT 报告/净化路径（见扫描阶段 scanBlueprintData），
            // 此处不再以 exceedsMaxDepth(rootTag, ...) 直接拒收整文件；深度测量保持迭代 BFS，
            // 避免栈溢出。可按需保留非拒收的深度上限以约束扫描成本，当前不额外兜底。
            BlueprintData data;
            try {
                data = BlueprintData.deserialize(rootTag);
            } catch (Exception e) {
                sendResult(player, false, "spatial_blueprint.error.invalid_blueprint_data");
                return;
            }

            // ── [检测3] 包围盒体积检测（独立于非空气方块数量）───────────────
            // litematic 存储格式要求给整个包围盒铺一份密集索引数组（含空气），
            // 稀疏但坐标跨度极大的结构可能非空气方块很少却仍能把这个数组撑爆内存，
            // 必须单独限制包围盒体积，不能只看方块总数。
            long boundingVolume = (long) data.size.getX() * data.size.getY() * data.size.getZ();
            if (boundingVolume > BlueprintConfig.MAX_BOUNDING_VOLUME.get()) {
                sendResult(player, false, "spatial_blueprint.error.bounding_volume_too_large",
                        "spatial_blueprint.error.detail.bounding_volume",
                        fmtInt(boundingVolume),
                        String.valueOf(data.size.getX()),
                        String.valueOf(data.size.getY()),
                        String.valueOf(data.size.getZ()),
                        fmtInt(BlueprintConfig.MAX_BOUNDING_VOLUME.get()));
                return;
            }

            // ── [检测4] 方块实体 NBT 体积检测 ─────────────────────────────
            // 前三项检测量的是压缩字节数、嵌套深度和包围盒，都不量 NBT 的字节数。
            // 命令方块单块 Command 上限 32500 字符、玩家头颅的 base64 皮肤各不相同
            // 无法去重、上千条附魔的列表深度只有 3 层 —— 既不算危险内容也不占方块数，
            // 却能把存档撑大几个数量级。
            //
            // 只在上传侧做：捕获路径必须放行 OP 强制蓝图与次元蓝图的完整 NBT，
            // 而那两类都不会走到这里（FORCE 会被拦，次元文件三个 tab 都不收）。
            //
            // 单体与总量均不再拒收：超预算的条目在净化时清 NBT、保留方块
            // （见扫描报告「超大 NBT」分类）。这里仍统计体积与最大单体，供报告页展示。
            BlueprintSanitizer.NbtBudgetResult budget = BlueprintSanitizer.checkNbtBudget(
                    data,
                    BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                    BlueprintConfig.MAX_TOTAL_NBT_BYTES.get());

            // ── [净化] 只做「不影响检测报告」的那几项 ──────────────────────
            //
            // ⚠ 容器清空与实体过滤**不能**放在这里：它们一执行，箱子就空了、实体就没了，
            // 后面扫出来的报告永远是「干净」，玩家根本看不到文件里原本有什么。
            // 那两步已移到确认环节（C2S_ConfirmUploadMessage 的净化分支）。
            //
            // 坐标越界的方块直接丢弃：这是畸形数据，不该拿给玩家选择保留与否
            // 强制捕获（FORCE）的蓝图：普通上传一律拒绝（绕过检测），
            // 但 OP 的强制上传流程（✈）不被此拦截，允许原样上传。
            if (data.saveMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE) {
                boolean allowForce = msg.forceRequested && player.hasPermissions(2);
                if (!allowForce) {
                    sendResult(player, false, "spatial_blueprint.gui.upload.error.force_blocked");
                    return;
                }
            }

            int outOfRangeDropped = BlueprintSanitizer.filterOutOfRangeBlocksInPlace(data);
            // 字符串/数值字段净化：名称过滤非法字符；取出经验/次数非 OP 强制 -1；
            // 工程蓝图专属字段（投影模板用不到）一律清空
            data.name = com.xioyim.spatialblueprint.server.BlueprintValidator.sanitizeName(data.name);
            BlueprintSanitizer.enforceTemplateFieldRules(data, player.hasPermissions(2));

            // 槽位预检已在 handle 最前段完成（见 [检测0.5]），此处不再重复检查。

            // ── 方块总数校验：按目标分类取上限（OP 豁免，与捕获一致）────────
            int totalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            int volumeLimit = player.hasPermissions(2)
                    ? BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME
                    : BlueprintConfig.getMaxVolumeFor(msg.category);
            if (totalBlocks > volumeLimit) {
                sendResult(player, false, "spatial_blueprint.error.too_many_blocks",
                        "spatial_blueprint.error.detail.block_count",
                        fmtInt(totalBlocks), fmtInt(volumeLimit));
                return;
            }

            // ── 扫描内容，生成检测报告 ────────────────────────────────────
            // 非法方块不再直接拒收整个文件 —— 改为列进报告，由玩家决定
            // 「净化并上传」（删掉那些方块）还是取消。OP 另有「强制上传」。
            //
            // 扫描必须在服务端做：非法方块/物品列表是服务端配置，客户端本地的 toml
            // 在多人服上可能完全不同，客户端自己扫出「没问题」只会误导玩家。
            // 分类以消息里的目标分类为准，不信任 dat 内写的 category
            data.category = msg.category;

            BlueprintSanitizer.UploadScan scan = BlueprintSanitizer.scanBlueprintData(
                    data, BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                    BlueprintConfig.MAX_NBT_DEPTH.get());

            // 文件先暂存，等玩家在报告页确认后才真正落盘
            com.xioyim.spatialblueprint.server.PendingUploadRegistry.put(
                    player.getUUID(), data, scan);

            if (!scan.isClean()) {
                com.xioyim.spatialblueprint.log.BlueprintFileLogger.uploadFlagged(
                        player, data.name,
                        scan.illegalBlocks().size(), scan.dangerousBlocks().size(),
                        scan.droppedEntities().size());
            }

            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage(
                            data.name, totalBlocks, player.hasPermissions(2),
                            data.size.getX(), data.size.getY(), data.size.getZ(),
                            msg.compressedData.length, budget.totalBytes(), budget.maxSingle(),
                            outOfRangeDropped,
                            scan.illegalBlocks(), scan.dangerousBlocks(), scan.oversizedNbt(),
                            scan.deepNbt(), scan.droppedEntities(), scan.conversions(), scan.freeBlocks(),
                            scan.illegalTotal(), scan.dangerousTotal(), scan.oversizedTotal(),
                            scan.deepNbtTotal(), scan.droppedTotal(), scan.conversionsTotal(), scan.freeTotal(),
                            msg.category, volumeLimit,
                            BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                            BlueprintConfig.MAX_TOTAL_NBT_BYTES.get()));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, key));
    }

    /** 带详情第二行的失败横幅：messageKey 说明被什么拦，detailKey + args 给出当前值 / 上限。 */
    private static void sendResult(ServerPlayer player, boolean success, String key,
                                   String detailKey, Object... detailArgs) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, key, detailKey, detailArgs));
    }

    private static String fmtBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            String s = String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + " MB";
        }
        String s = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + " KB";
    }

    private static String fmtInt(long v) {
        return String.format(java.util.Locale.ROOT, "%,d", v);
    }
}
