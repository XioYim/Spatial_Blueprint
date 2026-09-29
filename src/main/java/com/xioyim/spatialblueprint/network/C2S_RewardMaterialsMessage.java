package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
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
 * C2S：奖励蓝图材料面板的两个专属操作（仅创造模式 OP）。
 *
 * <ul>
 *   <li>{@link #MODE_GENERATE} ✎ 提取并载入材料：按蓝图结构生成清单（服务端替换表 /
 *       批量转换 / 非法表都在这边算），剔掉非法方块后直接写盘。</li>
 *   <li>{@link #MODE_CLEAR} ✖ 清空全部材料：清单清空（= 放置免费），同样直接写盘。</li>
 * </ul>
 *
 * <p>两个动作都不经 ⎙（{@code C2S_SaveMaterialListMessage}）—— 生成必须用服务端配置算，
 * 清空为对称也走服务端；写盘后 {@code sendBlueprintTo} 回推，客户端面板以服务端结果为准。</p>
 */
public class C2S_RewardMaterialsMessage {

    public static final int MODE_GENERATE = 0;
    public static final int MODE_CLEAR    = 1;

    public final String blueprintId;
    public final int    mode;

    public C2S_RewardMaterialsMessage(String blueprintId, int mode) {
        this.blueprintId = blueprintId == null ? "" : blueprintId;
        this.mode        = mode;
    }

    public static void encode(C2S_RewardMaterialsMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeVarInt(msg.mode);
    }

    public static C2S_RewardMaterialsMessage decode(FriendlyByteBuf buf) {
        return new C2S_RewardMaterialsMessage(buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(C2S_RewardMaterialsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || msg.blueprintId.isEmpty()) return;

            if (!player.hasPermissions(2) || !player.isCreative()) {
                sendResult(player, "spatial_blueprint.message.reward_materials.need_op");
                return;
            }

            BlueprintData data = BlueprintStorage.load(msg.blueprintId);
            if (data == null) {
                sendResult(player, "spatial_blueprint.error.no_blueprint_data");
                return;
            }
            if (!"reward".equals(data.category)) {
                sendResult(player, "spatial_blueprint.message.reward_materials.not_reward");
                return;
            }

            int removed = 0;
            if (msg.mode == MODE_GENERATE) {
                MaterialList ml = MaterialList.fromBlueprintData(data);
                removed = BlueprintSanitizer.removeIllegalMaterialsInPlace(ml);
                data.materialList = ml;
            } else if (msg.mode == MODE_CLEAR) {
                data.materialList = new MaterialList();
                data.materialList.blueprintName = data.name != null ? data.name : "";
            } else {
                return;
            }

            try {
                BlueprintStorage.save(UUID.fromString(msg.blueprintId), data);
            } catch (IllegalArgumentException ignored) {
                // 与 C2S_SaveMaterialListMessage 同款：坏 id 静默丢弃（写盘失败由 save 内部记日志）
            }

            // 回推最新数据，让客户端面板以服务端结果为准（T10：只能走 sendBlueprintTo）
            BlueprintNetwork.sendBlueprintTo(player, msg.blueprintId, data);

            // 成功不走 S2C_OperationResult（它的成功分支会关 PlacementScreen），走聊天栏
            if (msg.mode == MODE_GENERATE) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "spatial_blueprint.message.reward_materials.generated",
                        data.materialList.blockCounts.size(), removed), false);
            } else {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "spatial_blueprint.message.reward_materials.cleared"), false);
            }

            SpatialBlueprintMod.LOGGER.info("[Reward] {} {} id={} entries={} removed={}",
                    player.getScoreboardName(),
                    msg.mode == MODE_GENERATE ? "generate" : "clear",
                    msg.blueprintId, data.materialList.blockCounts.size(), removed);
        });
        ctx.get().setPacketHandled(true);
    }

    /** 失败横幅：主键无参（T9），成功不走这条。 */
    private static void sendResult(ServerPlayer player, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(false, messageKey));
    }
}
