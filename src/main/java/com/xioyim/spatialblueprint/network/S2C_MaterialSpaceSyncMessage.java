package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * S2C：服务端推送玩家材料空间（普通池）的内容。
 *
 * <p>两种用途，由 {@link #openScreen} 区分：</p>
 * <ul>
 *   <li>{@code false} —— 增量同步。只推送<b>当前蓝图材料清单涉及的键</b>，供放置界面显示
 *       「已存入 / 需要」。池可能有上千种物品，全量推送每次变更会拖垮客户端。</li>
 *   <li>{@code true} —— 由 {@code /sb space} 触发，推送整个普通池并打开查看界面。
 *       次元袋不在推送范围内。</li>
 * </ul>
 */
public class S2C_MaterialSpaceSyncMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_MaterialSpaceSyncMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_material_space_sync_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_MaterialSpaceSyncMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_MaterialSpaceSyncMessage::encode, S2C_MaterialSpaceSyncMessage::decode);

    private final Map<String, Integer>     counts;
    private final Map<String, CompoundTag> tags;
    private final boolean                  openScreen;
    /** 查看界面标题用的玩家名；增量同步时为空串。 */
    private final String                   targetName;
    /** 被查看玩家的 UUID 字符串 —— 「次元蓝图材料空间」按钮要带着它继续查同一个人。 */
    private final String                   targetUuid;
    /** 普通池总件数与上限，供查看界面显示占用情况。 */
    private final int                      total;
    private final int                      capacity;

    public S2C_MaterialSpaceSyncMessage(Map<String, Integer> counts, Map<String, CompoundTag> tags,
                                        boolean openScreen, String targetName, String targetUuid,
                                        int total, int capacity) {
        this.counts     = counts;
        this.tags       = tags;
        this.openScreen = openScreen;
        this.targetName = targetName == null ? "" : targetName;
        this.targetUuid = targetUuid == null ? "" : targetUuid;
        this.total      = total;
        this.capacity   = capacity;
    }

    @Override
    public CustomPacketPayload.Type<S2C_MaterialSpaceSyncMessage> type() {
        return TYPE;
    }

    public Map<String, Integer>     getCounts()     { return counts; }
    public Map<String, CompoundTag> getTags()       { return tags; }
    public boolean                  isOpenScreen()  { return openScreen; }
    public String                   getTargetName() { return targetName; }
    public String                   getTargetUuid() { return targetUuid; }
    public int                      getTotal()      { return total; }
    public int                      getCapacity()   { return capacity; }

    // ── 服务端发送辅助 ────────────────────────────────────────────────────

    /**
     * 增量同步：只推送 {@code ml} 清单涉及的键。{@code ml} 为 {@code null} 时推送空表
     * （用于「清空池」后让客户端立即归零）。
     */
    public static void sendFor(ServerPlayer player, @Nullable MaterialList ml) {
        sendFor(player, ml, null);
    }

    /**
     * 增量同步。{@code voidBagId} 非空时读取该蓝图的<b>次元袋</b>而非普通池 ——
     * 次元蓝图的材料存在按蓝图 ID 绑定的袋子里，不在普通池，
     * 否则放置界面会一直显示 0/N。
     */
    public static void sendFor(ServerPlayer player, @Nullable MaterialList ml,
                               @Nullable String voidBagId) {
        MaterialSpace space = MaterialSpace.get(player.serverLevel());
        UUID id = player.getUUID();
        Map<String, Integer>     counts = new LinkedHashMap<>();
        Map<String, CompoundTag> tags   = new LinkedHashMap<>();
        if (ml != null) {
            Map<String, Integer> bag = (voidBagId != null && !voidBagId.isEmpty())
                    ? space.bag(id, voidBagId) : null;
            for (String key : ml.blockCounts.keySet()) {
                counts.put(key, bag != null ? bag.getOrDefault(key, 0) : space.count(id, key));
                CompoundTag t = space.tagFor(key);
                if (t == null) t = ml.tagFor(key);
                if (t != null) tags.put(key, t);
            }
        }
        BlueprintNetwork.sendToPlayer(player,
                new S2C_MaterialSpaceSyncMessage(counts, tags, false, "", id.toString(),
                        space.poolTotal(id), com.xioyim.spatialblueprint.BlueprintConfig.materialSpaceMaxCount()));
    }

    /** 全量推送 {@code target} 的普通池并在 {@code viewer} 客户端打开查看界面。 */
    public static void sendFullAndOpen(ServerPlayer viewer, UUID target, String targetName) {
        MaterialSpace space = MaterialSpace.get(viewer.serverLevel());
        Map<String, Integer>     counts = new LinkedHashMap<>(space.pool(target));
        Map<String, CompoundTag> tags   = new LinkedHashMap<>();
        for (String key : counts.keySet()) {
            CompoundTag t = space.tagFor(key);
            if (t != null) tags.put(key, t);
        }
        BlueprintNetwork.sendToPlayer(viewer,
                new S2C_MaterialSpaceSyncMessage(counts, tags, true, targetName, target.toString(),
                        space.poolTotal(target), com.xioyim.spatialblueprint.BlueprintConfig.materialSpaceMaxCount()));
    }

    // ── Encode / Decode ───────────────────────────────────────────────────

    public static void encode(S2C_MaterialSpaceSyncMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.counts.size());
        for (Map.Entry<String, Integer> e : msg.counts.entrySet()) {
            buf.writeUtf(e.getKey(), 512);
            buf.writeVarInt(e.getValue());
        }
        buf.writeVarInt(msg.tags.size());
        for (Map.Entry<String, CompoundTag> e : msg.tags.entrySet()) {
            buf.writeUtf(e.getKey(), 512);
            buf.writeNbt(e.getValue());
        }
        buf.writeBoolean(msg.openScreen);
        buf.writeUtf(msg.targetName, 64);
        buf.writeUtf(msg.targetUuid, 64);
        buf.writeVarInt(msg.total);
        buf.writeVarInt(msg.capacity);
    }

    public static S2C_MaterialSpaceSyncMessage decode(FriendlyByteBuf buf) {
        // A3 纵深防御：池正常上千种，异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int n = S2C_OperationResult.checkedCount(buf, 65536, "counts");
        Map<String, Integer> counts = new LinkedHashMap<>(Math.max(16, n));
        for (int i = 0; i < n; i++) {
            String key = buf.readUtf(512);
            counts.put(key, buf.readVarInt());
        }
        int m = S2C_OperationResult.checkedCount(buf, 65536, "tags");
        Map<String, CompoundTag> tags = new LinkedHashMap<>(Math.max(16, m));
        for (int i = 0; i < m; i++) {
            String key = buf.readUtf(512);
            CompoundTag tag = buf.readNbt();
            if (tag != null) tags.put(key, tag);
        }
        boolean open = buf.readBoolean();
        String  name = buf.readUtf(64);
        String  uuid = buf.readUtf(64);
        int total    = buf.readVarInt();
        int capacity = buf.readVarInt();
        return new S2C_MaterialSpaceSyncMessage(counts, tags, open, name, uuid, total, capacity);
    }

    // ── Handler ───────────────────────────────────────────────────────────

    public static void handle(S2C_MaterialSpaceSyncMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端材料空间缓存（ClientMaterialSpaceCache.onReceive：增量刷新「已存入 / 需要」，openScreen 时打开查看界面）
    }

    /** 便于调试：键的可读形式（带 NBT 的键会保留摘要后缀）。 */
    public static String describe(String key) {
        return MaterialKey.hasTag(key) ? key : MaterialKey.idOf(key);
    }
}
