package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：推送「次元蓝图材料空间」内容并打开查看界面。
 *
 * <p>每条对应一个当前次元袋或已消耗袋记录：携带完整的蓝图摘要（用于显示与 tooltip）
 * 和袋内剩余总件数。</p>
 */
public class S2C_VoidBagSyncMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_VoidBagSyncMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_void_bag_sync_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_VoidBagSyncMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_VoidBagSyncMessage::encode, S2C_VoidBagSyncMessage::decode);

    /**
     * 一条次元蓝图材料记录。
     *
     * <p>蓝图文件被删除或归档后，这袋材料就成了<b>孤儿</b> —— 再也没有任何东西能消耗它。
     * 这种记录的 {@code summary} 为 {@code null}，界面把它画成灰色删除线并给出
     * 「点击删除清单」。名字取自次元袋里的快照（{@code MaterialSpace.bagName}），
     * 因为蓝图文件已经没了，名字没别处可查。</p>
     *
     * @param summary     蓝图摘要；{@code null} = 蓝图已丢失（孤儿袋）
     * @param itemCount   袋内材料总件数
     * @param blueprintId 蓝图 ID，孤儿袋靠它发删除请求
     * @param lostName    孤儿袋的名字快照；非孤儿时为空串
     */
    public record Entry(@javax.annotation.Nullable BlueprintStorage.BlueprintSummary summary,
                        int itemCount, String blueprintId, String lostName) {

        public boolean isLost() { return summary == null; }
    }

    private final List<Entry> entries;
    private final String      targetName;

    public S2C_VoidBagSyncMessage(List<Entry> entries, String targetName) {
        this.entries    = entries;
        this.targetName = targetName == null ? "" : targetName;
    }

    public List<Entry> getEntries()   { return entries; }
    public String      getTargetName() { return targetName; }

    public static void sendTo(ServerPlayer viewer, List<Entry> entries, String targetName) {
        BlueprintNetwork.sendToPlayer(viewer,
                new S2C_VoidBagSyncMessage(entries, targetName));
    }

    @Override
    public CustomPacketPayload.Type<S2C_VoidBagSyncMessage> type() {
        return TYPE;
    }

    // ── Encode / Decode ───────────────────────────────────────────────────

    public static void encode(S2C_VoidBagSyncMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            boolean hasSummary = e.summary() != null;
            buf.writeBoolean(hasSummary);
            // 复用蓝图列表的摘要编码，保证两处字段顺序永远一致
            if (hasSummary) S2C_LibrarySyncMessage.writeSummary(buf, e.summary());
            buf.writeVarInt(e.itemCount());
            buf.writeUtf(e.blueprintId() == null ? "" : e.blueprintId(), 64);
            buf.writeUtf(e.lostName() == null ? "" : e.lostName(), 128);
        }
        buf.writeUtf(msg.targetName, 64);
    }

    public static S2C_VoidBagSyncMessage decode(FriendlyByteBuf buf) {
        // A3 纵深防御：袋记录数正常几十，异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int n = S2C_OperationResult.checkedCount(buf, 65536, "entries");
        List<Entry> entries = new ArrayList<>(Math.max(8, n));
        for (int i = 0; i < n; i++) {
            boolean hasSummary = buf.readBoolean();
            BlueprintStorage.BlueprintSummary s =
                    hasSummary ? S2C_LibrarySyncMessage.readSummary(buf) : null;
            int count      = buf.readVarInt();
            String bpId    = buf.readUtf(64);
            String lostName = buf.readUtf(128);
            entries.add(new Entry(s, count, bpId, lostName));
        }
        String targetName = buf.readUtf(64);
        return new S2C_VoidBagSyncMessage(entries, targetName);
    }

    // ── Handler ───────────────────────────────────────────────────────────

    public static void handle(S2C_VoidBagSyncMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端打开次元袋空间查看界面（原 openScreen：Minecraft.getInstance().setScreen(new gui.VoidBagSpaceScreen(msg.getTargetName(), msg.getEntries()))）
    }
}
