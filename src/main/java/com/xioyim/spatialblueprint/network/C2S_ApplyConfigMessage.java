package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.config.ConfigRegistry;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C2S：配置界面点「保存」后，把改动过的项提交给服务端写入。
 *
 * <p>只发<b>改过的</b>项，不是整份快照 —— 两个 OP 同时开着配置界面时，
 * 各自只会覆盖自己动过的那几行，不会拿一份陈旧的全量值把对方的修改压回去。</p>
 *
 * <p>服务端逐项校验：</p>
 * <ol>
 *   <li>操作者必须是 OP</li>
 *   <li>id 必须在 {@link ConfigRegistry} 登记过 —— 没登记的直接忽略</li>
 *   <li>值必须通过该项自己的 spec（{@code defineInRange} 的上下界）——
 *       写进一个越界值会让 ModConfigSpec 下次校验时把<b>整个配置文件重置为默认</b>，
 *       一个伪造的包就能抹掉服主的全部配置</li>
 * </ol>
 *
 * <p>写完立刻热重载并回推一份新快照，界面上看到的就是真正落盘的值
 * （被夹过范围的项会自己变回去，玩家能看出来）。</p>
 */
public class C2S_ApplyConfigMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_ApplyConfigMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_apply_config_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ApplyConfigMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_ApplyConfigMessage::encode, C2S_ApplyConfigMessage::decode);

    private final Map<String, Integer>      ints;
    private final Map<String, Boolean>      bools;
    private final Map<String, List<String>> lists;

    public C2S_ApplyConfigMessage(Map<String, Integer> ints, Map<String, Boolean> bools,
                                   Map<String, List<String>> lists) {
        this.ints  = ints;
        this.bools = bools;
        this.lists = lists;
    }

    private static final int MAX_LIST_ELEM = 4096;

    public static void encode(C2S_ApplyConfigMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.ints.size());
        for (Map.Entry<String, Integer> e : msg.ints.entrySet()) {
            buf.writeUtf(e.getKey(), 128);
            buf.writeVarInt(e.getValue());
        }
        buf.writeVarInt(msg.bools.size());
        for (Map.Entry<String, Boolean> e : msg.bools.entrySet()) {
            buf.writeUtf(e.getKey(), 128);
            buf.writeBoolean(e.getValue());
        }
        buf.writeVarInt(msg.lists.size());
        for (Map.Entry<String, List<String>> e : msg.lists.entrySet()) {
            buf.writeUtf(e.getKey(), 128);
            List<String> list = e.getValue();
            buf.writeVarInt(list.size());
            for (String s : list) buf.writeUtf(s, MAX_LIST_ELEM);
        }
    }

    public static C2S_ApplyConfigMessage decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
        Map<String, Integer> ints = new HashMap<>();
        for (int i = 0; i < n; i++) ints.put(buf.readUtf(128), buf.readVarInt());
        int m = Math.min(buf.readVarInt(), MAX_ENTRIES);
        Map<String, Boolean> bools = new HashMap<>();
        for (int i = 0; i < m; i++) bools.put(buf.readUtf(128), buf.readBoolean());
        int l = Math.min(buf.readVarInt(), MAX_ENTRIES);
        Map<String, List<String>> lists = new HashMap<>();
        for (int i = 0; i < l; i++) {
            String key = buf.readUtf(128);
            int sz = Math.min(buf.readVarInt(), 65536);
            List<String> list = new ArrayList<>(sz);
            for (int j = 0; j < sz; j++) list.add(buf.readUtf(MAX_LIST_ELEM));
            lists.put(key, list);
        }
        return new C2S_ApplyConfigMessage(ints, bools, lists);
    }

    /** 一个包里最多接受多少项 —— 登记表本来也就几十项，多出来的必然是伪造包。 */
    private static final int MAX_ENTRIES = 256;

    @Override
    public CustomPacketPayload.Type<C2S_ApplyConfigMessage> type() {
        return TYPE;
    }

    public static void handle(C2S_ApplyConfigMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_ApplyConfigMessage msg, ServerPlayer player) {
        if (player == null) return;
        if (!player.hasPermissions(2)) {
            sendResult(player, "spatial_blueprint.config.error.no_permission");
            return;
        }

        int applied = 0, rejected = 0;
        boolean modifiedBlueprint = false, modifiedSubstitutions = false;
        for (Map.Entry<String, Integer> e : msg.ints.entrySet()) {
            Integer result = ConfigRegistry.applyInt(e.getKey(), e.getValue());
            if (result == null) { rejected++; continue; }
            if (!result.equals(e.getValue())) rejected++; else { applied++; modifiedBlueprint = true; }
        }
        for (Map.Entry<String, Boolean> e : msg.bools.entrySet()) {
            Boolean result = ConfigRegistry.applyBool(e.getKey(), e.getValue());
            if (result == null) rejected++; else { applied++; modifiedBlueprint = true; }
        }
        for (Map.Entry<String, List<String>> e : msg.lists.entrySet()) {
            List<String> result = ConfigRegistry.applyList(e.getKey(), e.getValue());
            if (result == null) { rejected++; continue; }
            applied++;
            // 判断归属哪个 spec（路径前缀区分）
            String id = e.getKey();
            if (id.startsWith("blockSubstitutions") || id.startsWith("bulkConversions")
                    || id.startsWith("generated")) {
                modifiedSubstitutions = true;
            } else {
                modifiedBlueprint = true;
            }
        }

        if (modifiedBlueprint) {
            com.xioyim.spatialblueprint.BlueprintConfig.MAX_VOLUME_SPATIAL.save();
            com.xioyim.spatialblueprint.BlueprintConfig.clearCache();
            com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToAllPlayers(
                    com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage.fromConfig());
        }
        if (modifiedSubstitutions) {
            com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS.save();
            com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig.invalidateCache();
        }

        SpatialBlueprintMod.LOGGER.info(
                "[Config] {} 通过 /sb config 修改配置：写入 {} 项，拒绝 {} 项",
                player.getScoreboardName(), applied, rejected);

        // 回推真正落盘的值：被夹过范围的项会在界面上自己变回去
        S2C_ConfigValuesMessage.sendTo(player);
        sendResult(player, rejected > 0
                ? "spatial_blueprint.config.saved_partial"
                : "spatial_blueprint.config.saved");
    }

    private static void sendResult(ServerPlayer player, String key) {
        com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(
                player,
                new S2C_OperationResult(false, key));
    }
}
