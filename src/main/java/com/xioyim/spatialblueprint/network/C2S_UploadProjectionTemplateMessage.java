package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：上传本地 schematic 转换的投影模板，由服务器校验后存入蓝图库。
 *
 * <p>服务端校验/净化（按顺序）：
 * <ul>
 *   <li>仅创造模式</li>
 *   <li>原始字节数 ≤ {@link BlueprintConfig#MAX_UPLOAD_FILE_BYTES}（反序列化前检测，防内存炸弹）</li>
 *   <li>NBT 嵌套深度 ≤ {@link BlueprintConfig#MAX_NBT_DEPTH}（迭代 BFS，不豁免）</li>
 *   <li>无条件清空所有容器 Items / 唱片机 RecordItem / 告示牌文字</li>
 *   <li>坐标越界（[0,2048) 之外）的方块直接丢弃</li>
 *   <li>实体白名单过滤（仅画/展示框/荧光展示框，交叉校验 NBT 内真正的 {@code id} 与坐标/朝向范围）</li>
 *   <li>实体内容净化（画只留 id+variant；展示框危险物品丢弃 id，安全物品丢弃 tag）</li>
 *   <li>名称过滤非法字符并截断长度；取出经验/次数非 OP 强制 -1；工程蓝图专属字段一律清空</li>
 *   <li>方块总数 ≤ {@link BlueprintConfig#ABSOLUTE_MAX_CAPTURE_VOLUME}</li>
 *   <li>不含 {@link BlueprintConfig#EXCLUDED_BLOCKS} 中的禁用方块</li>
 * </ul>
 * 校验通过后以新 UUID 保存，给予玩家投影蓝图物品，推送数据缓存与库同步。</p>
 *
 * <p>后续阶段将补齐 hasIllegalBlocks 标记（存服务器索引，供危险放置广播使用）/
 * 手动审核队列，详见项目根目录《上传文件检测细节和方案计划.md》第 11.8 节。</p>
 */
public class C2S_UploadProjectionTemplateMessage {

    private final byte[] compressedData;

    public C2S_UploadProjectionTemplateMessage(BlueprintData data) {
        this.compressedData = compress(data.serialize());
    }

    private C2S_UploadProjectionTemplateMessage(byte[] compressedData) {
        this.compressedData = compressedData;
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
            return NbtIo.readCompressed(dis);
        }
    }

    public static void encode(C2S_UploadProjectionTemplateMessage msg, FriendlyByteBuf buf) {
        buf.writeByteArray(msg.compressedData);
    }

    /**
     * 网络层硬上限（32MB）——仅用于防止畸形数据包本身撑爆内存，
     * 真正生效的大小限制由 {@link BlueprintConfig#MAX_UPLOAD_FILE_BYTES} 在
     * {@link #handle} 中校验（可配置，默认 5MB）。
     */
    private static final int NETWORK_HARD_CAP_BYTES = 32 * 1024 * 1024;

    public static C2S_UploadProjectionTemplateMessage decode(FriendlyByteBuf buf) {
        return new C2S_UploadProjectionTemplateMessage(buf.readByteArray(NETWORK_HARD_CAP_BYTES));
    }

    public static void handle(C2S_UploadProjectionTemplateMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!player.isCreative()) {
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

            // ── [检测1] 原始字节数检测（必须在反序列化之前，防止内存炸弹）─────
            // 高压缩比 NBT 可将几十 KB 的压缩数据展开为数百 MB 内存，
            // 先解压再检测大小会被绕过，因此必须检查压缩后的原始字节数。
            if (msg.compressedData.length > BlueprintConfig.MAX_UPLOAD_FILE_BYTES.get()) {
                sendResult(player, false, "spatial_blueprint.error.upload_file_too_large");
                return;
            }

            CompoundTag rootTag;
            try {
                rootTag = decompress(msg.compressedData);
            } catch (Exception e) {
                sendResult(player, false, "spatial_blueprint.error.invalid_blueprint_data");
                return;
            }

            // ── [检测2] NBT 嵌套深度检测（迭代 BFS，不豁免，OP 也不能跳过）───
            if (BlueprintSanitizer.exceedsMaxDepth(rootTag, BlueprintConfig.MAX_NBT_DEPTH.get())) {
                sendResult(player, false, "spatial_blueprint.error.nbt_too_deep");
                return;
            }

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
                sendResult(player, false, "spatial_blueprint.error.bounding_volume_too_large");
                return;
            }

            // ── [净化] 数据来自不受信任的客户端字节流，无条件清空危险内容 ─────
            // 容器 Items / 唱片机 RecordItem / 告示牌文字：不判断是否"危险"，直接清空
            BlueprintSanitizer.stripContainerAndSignNbtInPlace(data);
            // 坐标越界的方块直接丢弃（越界方块，不是拒绝整个文件）
            BlueprintSanitizer.filterOutOfRangeBlocksInPlace(data);
            // 实体白名单 + 坐标/朝向范围校验：只保留画/展示框/荧光展示框，其余一律丢弃
            BlueprintSanitizer.filterEntitiesInPlace(data);
            // 实体内容净化：画只保留 id+variant；展示框危险物品丢弃 id，安全物品丢弃 tag
            for (BlueprintData.EntityEntry entry : data.entities) {
                if (entry.nbt == null) continue;
                if (entry.type.equals("minecraft:painting")) {
                    BlueprintSanitizer.sanitizePaintingNbt(entry.nbt);
                } else {
                    BlueprintSanitizer.sanitizeItemFrameContentInPlace(entry.nbt);
                }
            }
            // 字符串/数值字段净化：名称过滤非法字符；取出经验/次数非 OP 强制 -1；
            // 工程蓝图专属字段（投影模板用不到）一律清空
            data.name = com.xioyim.spatialblueprint.server.BlueprintValidator.sanitizeName(data.name);
            BlueprintSanitizer.enforceTemplateFieldRules(data, player.hasPermissions(2));

            // ── 方块总数校验 ──────────────────────────────────────────────
            int totalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            if (totalBlocks > BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME) {
                sendResult(player, false, "spatial_blueprint.error.too_many_blocks");
                return;
            }

            // ── 禁用方块校验 ──────────────────────────────────────────────
            List<BlueprintData.BlockEntry> allEntries = new ArrayList<>();
            allEntries.addAll(data.normalBlocks);
            allEntries.addAll(data.dependentBlocks);
            allEntries.addAll(data.lateBlocks);
            for (BlueprintData.BlockEntry entry : allEntries) {
                if (BlueprintSanitizer.isIllegalBlock(entry.state)) {
                    sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                    return;
                }
            }

            // ── 强制修正分类和归属 ────────────────────────────────────────
            data.category    = "template";
            data.creatorName = player.getScoreboardName();
            data.sourceName  = player.getScoreboardName();
            data.createdAt   = System.currentTimeMillis();

            // ── 保存蓝图文件 ──────────────────────────────────────────────
            UUID blueprintId = UUID.randomUUID();
            BlueprintStorage.save(blueprintId, data);

            // ── 给予玩家投影蓝图物品 ──────────────────────────────────────
            ItemStack stack = new ItemStack(SpatialBlueprintItems.PROJECTION_BLUEPRINT.get());
            net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
            tag.putString("blueprintId",   blueprintId.toString());
            tag.putString("blueprintName", data.name);
            if (data.animDuration > 0) tag.putInt("animDuration", data.animDuration);

            if (!player.addItem(stack)) {
                // 背包满：先保存文件（玩家可从库中取），仍告知成功
                // （物品给不了不影响蓝图已存档）
            }

            // ── 推送数据缓存到客户端 ──────────────────────────────────────
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_BlueprintDataMessage(blueprintId.toString(), data));

            // ── 刷新客户端蓝图库 ──────────────────────────────────────────
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(BlueprintStorage.listSummaries()));

            sendResult(player, true, "");
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, key));
    }
}
