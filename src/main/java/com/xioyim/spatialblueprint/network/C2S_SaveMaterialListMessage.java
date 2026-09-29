package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C2S：创造模式 OP 玩家点击 ⎙ 按钮，将当前材料清单（需要数量）整体保存至服务端蓝图文件。
 *
 * <p>界面上的增 / 删 / 改数量都只改客户端的本地副本，本消息是<b>唯一</b>的写入路径 ——
 * 不点 ⎙ 就关界面的话，改动不会落盘。</p>
 *
 * <p>例外：奖励蓝图材料面板的 ✎ 提取并载入 / ✖ 清空全部走
 * {@link C2S_RewardMaterialsMessage} 直接写盘（生成要用服务端配置算），不经本消息。</p>
 *
 * <p>{@code keyTags} 必须一并发送：OP 可以添加带 NBT 的物品（附魔装备等），
 * 只发 {@code blockCounts} 的话服务端会留下一个没有 tag 的复合键，
 * 重新加载后那件物品会退化成无 NBT 的普通物品。</p>
 */
public class C2S_SaveMaterialListMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_SaveMaterialListMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_save_material_list_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_SaveMaterialListMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_SaveMaterialListMessage::encode, C2S_SaveMaterialListMessage::decode);

    public final String blueprintId;
    public final LinkedHashMap<String, Integer> blockCounts;
    /** 复合键 → 规范化 NBT；只包含带 NBT 的条目。 */
    public final LinkedHashMap<String, net.minecraft.nbt.CompoundTag> keyTags;

    public C2S_SaveMaterialListMessage(String blueprintId, Map<String, Integer> blockCounts,
                                       Map<String, net.minecraft.nbt.CompoundTag> keyTags) {
        this.blueprintId = blueprintId;
        this.blockCounts = new LinkedHashMap<>(blockCounts);
        this.keyTags     = new LinkedHashMap<>(keyTags);
    }

    @Override
    public CustomPacketPayload.Type<C2S_SaveMaterialListMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_SaveMaterialListMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeVarInt(msg.blockCounts.size());
        for (Map.Entry<String, Integer> entry : msg.blockCounts.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeInt(entry.getValue());
        }
        buf.writeVarInt(msg.keyTags.size());
        for (Map.Entry<String, net.minecraft.nbt.CompoundTag> entry : msg.keyTags.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeNbt(entry.getValue());
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
        int tagCount = buf.readVarInt();
        LinkedHashMap<String, net.minecraft.nbt.CompoundTag> tags = new LinkedHashMap<>();
        for (int i = 0; i < tagCount; i++) {
            String key = buf.readUtf(128);
            net.minecraft.nbt.CompoundTag tag = buf.readNbt();
            if (tag != null) tags.put(key, tag);
        }
        return new C2S_SaveMaterialListMessage(blueprintId, counts, tags);
    }

    public static void handle(C2S_SaveMaterialListMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_SaveMaterialListMessage msg, ServerPlayer player) {
        if (player == null) return;

        // ── 必须是创造模式 + OP ────────────────────────────────────────
        if (!player.hasPermissions(2) || !player.isCreative()) return;
        if (msg.blueprintId.isEmpty()) return;

        // ── 非法方块检查：材料清单中不允许出现命令方块等禁用方块 ─────────
        // 注意：不走 S2C_OperationResult——它的客户端处理器会在 PlacementScreen
        // 打开时强制关闭该界面，这里只是校验失败，不应把玩家踢出材料清单界面。
        for (String materialKey : msg.blockCounts.keySet()) {
            // 键可能是带 NBT 摘要后缀的复合键，先还原为注册 ID 再检查，
            // 否则带 NBT 的非法方块（如展示框内的命令方块）会绕过校验
            String blockId = com.xioyim.spatialblueprint.util.MaterialKey.idOf(materialKey);
            if (BlueprintSanitizer.isIllegalBlockId(blockId)) {
                BlueprintNetwork.sendToPlayer(player,
                        new S2C_OperationResult(false, "spatial_blueprint.error.illegal_material"));
                // 回推服务端真实清单，覆盖客户端界面上的非法条目
                pushData(player, msg.blueprintId);
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
        // tag 表整体替换：清单里已删除的条目不该留下孤儿 tag
        data.materialList.keyTags.clear();
        for (Map.Entry<String, net.minecraft.nbt.CompoundTag> e : msg.keyTags.entrySet()) {
            if (msg.blockCounts.containsKey(e.getKey())) {
                data.materialList.keyTags.put(e.getKey(), e.getValue());
            }
        }

        try {
            BlueprintStorage.save(UUID.fromString(msg.blueprintId), data);
            NotifySound.server(player);
        } catch (IllegalArgumentException ignored) {}

        // 回推最新数据，让客户端界面以服务端结果为准
        pushData(player, msg.blueprintId);
    }

    /** 把服务端文件里的蓝图数据推回客户端缓存，使界面显示服务端权威结果。 */
    private static void pushData(ServerPlayer player, String blueprintId) {
        BlueprintData fresh = BlueprintStorage.load(blueprintId);
        if (fresh == null) return;
        BlueprintNetwork.sendBlueprintTo(player, blueprintId, fresh);
    }
}
