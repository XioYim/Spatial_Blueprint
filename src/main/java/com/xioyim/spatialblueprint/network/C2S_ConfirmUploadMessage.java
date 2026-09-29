package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.log.BlueprintFileLogger;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.PendingUploadRegistry;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：玩家在上传检测报告页上按下确认。
 *
 * <p>取出 {@code PendingUploadRegistry} 里那份已扫描的数据，按选择的模式处理后保存：</p>
 *
 * <ul>
 *   <li>{@code SANITIZE} — 非法方块整块删除、危险 NBT 清空、白名单外实体丢弃，然后保存</li>
 *   <li>{@code FORCE} — 原样保存（<b>仅 OP</b>，服务端重新校验权限，不信任客户端）</li>
 * </ul>
 *
 * <p>暂存是<b>取出即消费</b>的，确认只能生效一次，同一份数据不会被存成多个蓝图。</p>
 */
public class C2S_ConfirmUploadMessage {

    /** true = 强制上传（仅 OP），false = 净化并上传。 */
    private final boolean force;

    public C2S_ConfirmUploadMessage(boolean force) {
        this.force = force;
    }

    public static void encode(C2S_ConfirmUploadMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.force);
    }

    public static C2S_ConfirmUploadMessage decode(FriendlyByteBuf buf) {
        return new C2S_ConfirmUploadMessage(buf.readBoolean());
    }

    public static void handle(C2S_ConfirmUploadMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            PendingUploadRegistry.Pending p = PendingUploadRegistry.take(player.getUUID());
            if (p == null) {
                // 超时、服务器重启，或重复点击
                sendResult(player, false, "spatial_blueprint.error.upload_expired");
                return;
            }

            BlueprintData data = p.data();

            // 强制上传只有 OP 能用 —— 客户端按钮已隐藏，这里是防改包的底线
            boolean force = msg.force && player.hasPermissions(2);
            if (msg.force && !force) {
                sendResult(player, false, "spatial_blueprint.error.upload_force_no_permission");
                return;
            }

            if (force) {
                BlueprintSanitizer.UploadScan s = p.scan();
                BlueprintFileLogger.uploadForced(player, data.name, data.category,
                        data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size(),
                        s.illegalTotal(), s.dangerousTotal(),
                        s.oversizedTotal(), s.deepNbtTotal(), s.droppedTotal());
            } else {
                BlueprintSanitizer.sanitizeForUploadInPlace(data,
                        BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                        BlueprintConfig.MAX_NBT_DEPTH.get());
                // 净化会删方块，尺寸与材料清单必须重算，否则和实际内容对不上
                recalcSizeAndMaterials(data);
            }

            // 归属由服务端强制覆盖，不信任客户端。
            // category 在上传时就由服务端按 tab 覆写过了（见 C2S_UploadProjectionTemplateMessage），
            // 这里沿用暂存里的值 —— 空间 / 投影 / 投影模板三种都走这条路。
            data.creatorName = player.getScoreboardName();
            data.sourceName  = player.getScoreboardName();
            data.ownerUuid   = player.getUUID().toString();
            com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, player.serverLevel());
            data.createdAt   = System.currentTimeMillis();

            // 上传的构建时长不信任文件里写的值，统一按净化后的方块数自动分档
            // （与捕获/工程/子工程一致）。force 同样覆盖：时长是功能性元数据，与
            // 「原样保存」承诺的内容/NBT 净化无关；否则 0 值的文件在库 tooltip 会显示「-」。
            int storedBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            data.animDuration = BlueprintConfig.getAutoDuration(storedBlocks);

            // 记录上传来源：1=安全上传 2=强制上传。捕获/工程/次元走其它创建路径，
            // 此字段保持 0。列表区分色与「仅 OP」门槛（强制上传）都以它为准。
            data.uploadMode = force ? 2 : 1;

            UUID blueprintId = UUID.randomUUID();
            BlueprintStorage.save(blueprintId, data);
            BlueprintStorage.invalidateSummaryCache();

            // 不再自动发放蓝图物品 —— 文件已存入蓝图库，玩家从库中取用。
            // H2：过滤后再推，避免上传一次就把全量列表泄一次。
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(C2S_RequestLibraryMessage.buildFilteredSummaries(player)));

            // 成功横幅：按分类给主句，带该分类槽位占用（已用/上限；无上限分类不带槽位行）
            String mainKey = switch (data.category) {
                case "create" -> "spatial_blueprint.message.upload_success.create";
                case "project" -> "spatial_blueprint.message.upload_success.project";
                default       -> "spatial_blueprint.message.upload_success.template";
            };
            // 上限必须读服务端真实配置 BlueprintConfig.getSlotLimitFor —— 不能用
            // S2C_ConfigSyncMessage.slotLimitFor()，那是客户端侧镜像（服务端从未收到同步包，
            // 恒为类默认值，改过 toml 后横幅会显示错的上限）。已用也改用与服务端槽位判定
            // （BlueprintValidator.checkSlot）同源的 countSlots，保证横幅数字 = 真实判定口径。
            int used = BlueprintStorage.countSlots(player.getUUID().toString(), data.category);
            int limit = BlueprintConfig.getSlotLimitFor(data.category);
            String slotsKey = switch (data.category) {
                case "create" -> "spatial_blueprint.message.upload_success.slots.create";
                case "project" -> "spatial_blueprint.message.upload_success.slots.project";
                default -> "spatial_blueprint.message.upload_success.slots.template";
            };
            if (limit > 0) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(true, mainKey,
                                slotsKey,
                                new Object[]{ used, limit }));
            } else {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(true, mainKey));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 按实际方块列表重算尺寸与材料清单。
     *
     * <p>客户端给的 {@code size} / {@code materialList} 一律不信任；净化删过方块之后
     * 更是必须重算，否则蓝图会声称自己比实际大、或列出根本不存在的材料。</p>
     */
    private static void recalcSizeAndMaterials(BlueprintData data) {
        int maxX = 0, maxY = 0, maxZ = 0;
        boolean any = false;
        for (BlueprintData.BlockEntry e : data.allBlocks()) {
            any = true;
            if (e.pos.getX() > maxX) maxX = e.pos.getX();
            if (e.pos.getY() > maxY) maxY = e.pos.getY();
            if (e.pos.getZ() > maxZ) maxZ = e.pos.getZ();
        }
        data.size = any
                ? new net.minecraft.core.BlockPos(maxX + 1, maxY + 1, maxZ + 1)
                : net.minecraft.core.BlockPos.ZERO;
        data.materialList = MaterialList.fromBlueprintData(data);
    }

    private static void sendResult(ServerPlayer player, boolean success, String key) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, key));
    }
}
