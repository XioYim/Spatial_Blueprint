package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * C2S：请求某玩家的「次元蓝图材料空间」内容（材料空间界面的 ✦ 按钮）。
 *
 * <p>列出当前持有次元袋及已放置后消耗过次元袋的蓝图。FORCE 捕获的蓝图本就不入袋，
 * 因此不出现。</p>
 *
 * <p>查看他人需要 OP 权限，与 {@code /sb space <玩家>} 一致。</p>
 */
public class C2S_RequestVoidBagsMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_RequestVoidBagsMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_request_void_bags_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestVoidBagsMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_RequestVoidBagsMessage::encode, C2S_RequestVoidBagsMessage::decode);

    /** 被查看玩家的 UUID 字符串。 */
    private final String targetUuid;
    /** 被查看玩家名，仅用于界面标题；由客户端透传，服务端无需再查一次档案缓存。 */
    private final String targetName;

    public C2S_RequestVoidBagsMessage(String targetUuid, String targetName) {
        this.targetUuid = targetUuid == null ? "" : targetUuid;
        this.targetName = targetName == null ? "" : targetName;
    }

    @Override
    public CustomPacketPayload.Type<C2S_RequestVoidBagsMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_RequestVoidBagsMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.targetUuid, 64);
        buf.writeUtf(msg.targetName, 64);
    }

    public static C2S_RequestVoidBagsMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestVoidBagsMessage(buf.readUtf(64), buf.readUtf(64));
    }

    public static void handle(C2S_RequestVoidBagsMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_RequestVoidBagsMessage msg, ServerPlayer player) {
        if (player == null) return;

        UUID target;
        try {
            target = msg.targetUuid.isEmpty() ? player.getUUID() : UUID.fromString(msg.targetUuid);
        } catch (IllegalArgumentException e) {
            return;
        }
        // 查看他人需要 OP
        if (!target.equals(player.getUUID()) && !player.hasPermissions(2)) return;

        sendVoidBags(player, target, msg.targetName);
    }

    /**
     * 构建并推送某玩家的次元袋列表。
     *
     * <p>抽成公开方法供「丢弃孤儿袋」后复用 —— 那边删完需要让界面立刻少掉一行。</p>
     *
     * @param viewer     接收界面的玩家
     * @param target     被查看玩家的 UUID
     * @param targetName 被查看玩家名（仅用于界面标题）
     */
    public static void sendVoidBags(ServerPlayer viewer, UUID target, String targetName) {
        {
            ServerPlayer player = viewer;
            MaterialSpace space = MaterialSpace.get(player.serverLevel());
            // 按 ID 建索引，避免每个袋子都全表扫一遍摘要列表
            List<BlueprintStorage.BlueprintSummary> summaries = BlueprintStorage.listSummaries();
            Map<String, BlueprintStorage.BlueprintSummary> byId = new java.util.HashMap<>();
            for (BlueprintStorage.BlueprintSummary s : summaries) {
                byId.put(s.id(), s);
            }

            Set<String> blueprintIds = new LinkedHashSet<>(space.dimensionalBlueprintIds(target));

            // 兼容本功能加入前已经消耗掉袋子的已放置蓝图。旧数据没有保留玩家 UUID，
            // 只能按捕获者/最近储存者名称做只读补充；新放置记录均走上面的精确 UUID 索引。
            String legacyOwnerName = target.equals(player.getUUID())
                    ? player.getScoreboardName() : targetName;
            if (!legacyOwnerName.isEmpty()) {
                for (BlueprintStorage.BlueprintSummary s : summaries) {
                    if (!blueprintIds.contains(s.id())
                            && s.placed() && "dimensional".equals(s.category())
                            && (legacyOwnerName.equalsIgnoreCase(s.creatorName())
                                || legacyOwnerName.equalsIgnoreCase(s.sourceName()))) {
                        blueprintIds.add(s.id());
                    }
                }
            }

            List<S2C_VoidBagSyncMessage.Entry> entries = new ArrayList<>();
            for (String bpId : blueprintIds) {
                BlueprintStorage.BlueprintSummary s = byId.get(bpId);
                int count = space.bagTotal(target, bpId);
                if (s == null) {
                    // 蓝图文件已被删除或归档 —— 孤儿袋。
                    // 以前直接跳过不显示，结果这袋材料永久占着空间、玩家却看不到它。
                    // 现在照常列出（灰色删除线 + 「蓝图已丢失」），让玩家自己决定要不要丢弃。
                    // 袋子已空的（放置时消耗过）不算丢失，直接跳过。
                    if (count <= 0) continue;
                    entries.add(new S2C_VoidBagSyncMessage.Entry(
                            null, count, bpId, space.bagName(bpId)));
                    continue;
                }
                entries.add(new S2C_VoidBagSyncMessage.Entry(s, count, bpId, ""));
            }

            S2C_VoidBagSyncMessage.sendTo(player, entries, targetName);
        }
    }
}
