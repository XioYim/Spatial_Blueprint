package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: creative-mode client requests deleting a server-side blueprint.
 *
 * <p>次元蓝图按「是否已放置」与「是否本人」分四种情况：</p>
 * <table>
 *   <tr><th>状态</th><th>操作者</th><th>行为</th></tr>
 *   <tr><td>未放置</td><td>任何人（含 OP）</td><td><b>一律拒绝</b> —— 材料还锁在主人的次元袋里，
 *       文件一没结构与材料就一起蒸发。真要清理只能停服后手动进服务器目录。</td></tr>
 *   <tr><td>已放置</td><td>主人</td><td>真正删除 —— 结构已还原回世界，删的只是云端槽位</td></tr>
 *   <tr><td>已放置</td><td>OP（非主人）</td><td>改名为 {@code .bak} 归档，写日志记录</td></tr>
 *   <tr><td>已放置</td><td>其他玩家</td><td>拒绝</td></tr>
 * </table>
 *
 * <p>删除（或归档）次元蓝图时会一并清掉它的次元袋，否则袋子会成为永久占用材料空间的孤儿。</p>
 *
 * <p>After deletion the server pushes a fresh {@link S2C_LibrarySyncMessage}
 * so the client list stays in sync.</p>
 */
public class C2S_DeleteBlueprintMessage {

    private final String blueprintId;

    public C2S_DeleteBlueprintMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    public static void encode(C2S_DeleteBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_DeleteBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_DeleteBlueprintMessage(buf.readUtf(64));
    }

    public static void handle(C2S_DeleteBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (msg.blueprintId.isEmpty()) return;

            BlueprintData data = BlueprintStorage.load(msg.blueprintId);
            boolean isDimensional = data != null && "dimensional".equals(data.category);

            // 蓝图库管理本来就是创造侧的事，非次元蓝图沿用创造模式限制。
            // 次元蓝图不能卡这一条 —— 它本来就是给生存玩家用的，主人删自己已放置的那份、
            // OP 归档别人的，都跟游戏模式无关；卡了等于主人永远删不掉自己的蓝图。
            if (!isDimensional && !player.isCreative()) return;

            if (isDimensional) {
                boolean isOwner = data.isOwnedBy(player.getUUID());

                // ① 未放置 —— 任何人都不能删，OP 也不能归档。
                //    材料还锁在主人的次元袋里，文件一消失结构与材料就一起蒸发了。
                //    真要清理只能停服后手动进服务器目录删（提示文案里已写明）。
                if (!data.placed) {
                    sendResult(player, false,
                            "spatial_blueprint.error.dimensional_unplaced_no_delete");
                    return;
                }

                // ② 已放置 · 主人 —— 真正删除。结构已经在世界里了，删的只是云端槽位。
                if (isOwner) {
                    BlueprintStorage.delete(msg.blueprintId);
                    MaterialSpace.get(player.serverLevel()).removeBagEverywhere(msg.blueprintId);
                }
                // ③ 已放置 · OP（非主人）—— 归档为 .bak，不真正删除，并留下操作记录
                else if (player.hasPermissions(2)) {
                    String bakName = BlueprintStorage.renameToBak(msg.blueprintId);
                    if (bakName == null) {
                        sendResult(player, false, "spatial_blueprint.error.blueprint_archive_failed");
                        return;
                    }
                    MaterialSpace.get(player.serverLevel()).removeBagEverywhere(msg.blueprintId);
                    com.xioyim.spatialblueprint.log.BlueprintFileLogger.archiveDimensional(
                            player, msg.blueprintId, data.name,
                            data.creatorName, data.ownerUuid, bakName);
                    // 用横幅明确告知：文件只是改名了，没有被删除
                    sendResult(player, false, "spatial_blueprint.message.blueprint_archived");
                }
                // ④ 已放置 · 既不是主人也不是 OP —— 拒绝
                else {
                    sendResult(player, false, "spatial_blueprint.message.void_blueprint.not_owner");
                    return;
                }
            } else {
                // ── 归属校验：主人或 OP 才能删 ────────────────────────────
                // 上面的 isCreative() 只说明「这是蓝图库管理场景」，不代表有权删这一份。
                // 少了这道校验，开放创造的服上任何人都能删掉别人的空间 / 投影 / 工程蓝图，
                // 且非次元蓝图是真删（不像次元那样归档成 .bak），删掉就找不回来了。
                if (!BlueprintValidator.canManageBlueprint(player, msg.blueprintId)) {
                    sendResult(player, false, "spatial_blueprint.error.blueprint_not_owner");
                    return;
                }
                BlueprintStorage.delete(msg.blueprintId);
            }

            // Push the updated list back so the client refreshes immediately.
            // H2：按下发口径过滤后再推，避免删一次就把全量列表泄一次。
            List<BlueprintStorage.BlueprintSummary> updated = C2S_RequestLibraryMessage.buildFilteredSummaries(player);
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(updated));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey));
    }
}
