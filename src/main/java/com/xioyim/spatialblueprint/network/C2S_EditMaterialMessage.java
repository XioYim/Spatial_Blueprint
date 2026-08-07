package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：创造模式玩家在材料清单 UI 中修改或删除某条材料。
 *
 * <p>{@code newCount < 0} 表示删除该条目；{@code newCount > 0} 表示更新数量。</p>
 */
public class C2S_EditMaterialMessage {

    public final String blueprintId;
    public final String blockId;
    /** 新数量；< 0 表示删除该条目。 */
    public final int newCount;

    public C2S_EditMaterialMessage(String blueprintId, String blockId, int newCount) {
        this.blueprintId = blueprintId;
        this.blockId     = blockId;
        this.newCount    = newCount;
    }

    public static void encode(C2S_EditMaterialMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.blockId, 128);
        buf.writeInt(msg.newCount);
    }

    public static C2S_EditMaterialMessage decode(FriendlyByteBuf buf) {
        return new C2S_EditMaterialMessage(
                buf.readUtf(64),
                buf.readUtf(128),
                buf.readInt()
        );
    }

    public static void handle(C2S_EditMaterialMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // ── 必须是创造模式 + OP ────────────────────────────────────────
            if (!player.hasPermissions(2) || !player.isCreative()) return;

            // ── 基本校验 ────────────────────────────────────────────────────
            if (msg.blueprintId.isEmpty() || msg.blockId.isEmpty()) return;

            // ── 非法方块检查：新增/修改条目时不允许写入命令方块等禁用方块 ────
            // 删除条目（newCount < 0）不受影响，永远允许清理掉已存在的非法条目。
            if (msg.newCount >= 0 && BlueprintSanitizer.isIllegalBlockId(msg.blockId)) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(false, "spatial_blueprint.error.illegal_material"));
                return;
            }

            // ── 加载蓝图数据 ────────────────────────────────────────────────
            BlueprintData data = BlueprintStorage.load(msg.blueprintId);
            if (data == null) return;

            // 如果还没有材料清单则创建一个空的（支持从零新增条目）
            if (data.materialList == null) {
                data.materialList = new MaterialList();
                data.materialList.blueprintName = data.name != null ? data.name : "";
            }

            // ── 应用修改 ────────────────────────────────────────────────────
            if (msg.newCount < 0) {
                data.materialList.blockCounts.remove(msg.blockId);
            } else {
                // 允许新增条目或更新已有条目
                data.materialList.blockCounts.put(msg.blockId, msg.newCount);
            }

            // ── 回写 dat 文件 ────────────────────────────────────────────────
            try {
                BlueprintStorage.save(UUID.fromString(msg.blueprintId), data);
            } catch (IllegalArgumentException ignored) {}
        });
        ctx.get().setPacketHandled(true);
    }
}
