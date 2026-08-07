package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：创造模式 OP 玩家点击 ⎙ 按钮，将当前材料清单（需要数量）整体保存至服务端蓝图文件。
 *
 * <p>相比逐条的 {@link C2S_EditMaterialMessage}，本消息一次性用客户端当前的完整快照
 * 覆盖服务端 {@code materialList.blockCounts}，用于向 OP 玩家提供一个明确、可见的
 * "保存至服务器文件" 操作反馈。</p>
 */
public class C2S_SaveMaterialListMessage {

    public final String blueprintId;
    public final LinkedHashMap<String, Integer> blockCounts;

    public C2S_SaveMaterialListMessage(String blueprintId, Map<String, Integer> blockCounts) {
        this.blueprintId = blueprintId;
        this.blockCounts = new LinkedHashMap<>(blockCounts);
    }

    public static void encode(C2S_SaveMaterialListMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeVarInt(msg.blockCounts.size());
        for (Map.Entry<String, Integer> entry : msg.blockCounts.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeInt(entry.getValue());
        }
    }

    public static C2S_SaveMaterialListMessage decode(FriendlyByteBuf buf) {
        String blueprintId = buf.readUtf(64);
        int size = buf.readVarInt();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String blockId = buf.readUtf(128);
            int    count   = buf.readInt();
            counts.put(blockId, count);
        }
        return new C2S_SaveMaterialListMessage(blueprintId, counts);
    }

    public static void handle(C2S_SaveMaterialListMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // ── 必须是创造模式 + OP ────────────────────────────────────────
            if (!player.hasPermissions(2) || !player.isCreative()) return;
            if (msg.blueprintId.isEmpty()) return;

            // ── 非法方块检查：材料清单中不允许出现命令方块等禁用方块 ─────────
            // 注意：不走 S2C_OperationResult——它的客户端处理器会在 PlacementScreen
            // 打开时强制关闭该界面，这里只是校验失败，不应把玩家踢出材料清单界面。
            for (String blockId : msg.blockCounts.keySet()) {
                if (BlueprintSanitizer.isIllegalBlockId(blockId)) {
                    BlueprintNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new S2C_OperationResult(false, "spatial_blueprint.error.illegal_material"));
                    return;
                }
            }

            BlueprintData data = BlueprintStorage.load(msg.blueprintId);
            if (data == null) return;

            if (data.materialList == null) {
                data.materialList = new MaterialList();
                data.materialList.blueprintName = data.name != null ? data.name : "";
            }
            data.materialList.blockCounts.clear();
            data.materialList.blockCounts.putAll(msg.blockCounts);

            try {
                BlueprintStorage.save(UUID.fromString(msg.blueprintId), data);
                NotifySound.server(player);
            } catch (IllegalArgumentException ignored) {}
        });
        ctx.get().setPacketHandled(true);
    }
}
