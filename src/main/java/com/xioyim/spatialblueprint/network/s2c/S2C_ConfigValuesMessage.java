package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.config.ConfigRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S2C：把<b>服务端</b>当前的配置值推给客户端的配置界面。
 *
 * <p>配置是 COMMON 型的，两端各有一份 toml —— 不推这一份，客户端界面读到的是自己那份，
 * 服主改的也只是自己那份。</p>
 */
public class S2C_ConfigValuesMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_ConfigValuesMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_config_values_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_ConfigValuesMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_ConfigValuesMessage::encode, S2C_ConfigValuesMessage::decode);

    private final Map<String, Integer>      ints;
    private final Map<String, Boolean>      bools;
    private final Map<String, List<String>> lists;

    public S2C_ConfigValuesMessage(Map<String, Integer> ints, Map<String, Boolean> bools,
                                   Map<String, List<String>> lists) {
        this.ints  = ints;
        this.bools = bools;
        this.lists = lists;
    }

    /** 从服务端当前配置生成一份完整快照。 */
    public static S2C_ConfigValuesMessage snapshot() {
        Map<String, Integer> ints = new HashMap<>();
        for (Map.Entry<String, ModConfigSpec.IntValue> e : ConfigRegistry.ints().entrySet()) {
            ints.put(e.getKey(), e.getValue().get());
        }
        Map<String, Boolean> bools = new HashMap<>();
        for (Map.Entry<String, ModConfigSpec.BooleanValue> e : ConfigRegistry.bools().entrySet()) {
            bools.put(e.getKey(), e.getValue().get());
        }
        Map<String, List<String>> lists = new HashMap<>();
        for (Map.Entry<String, ModConfigSpec.ConfigValue<List<String>>> e : ConfigRegistry.lists().entrySet()) {
            lists.put(e.getKey(), new ArrayList<>(e.getValue().get()));
        }
        return new S2C_ConfigValuesMessage(ints, bools, lists);
    }

    /** 发一份最新快照给这名玩家。 */
    public static void sendTo(ServerPlayer player) {
        com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(player, snapshot());
    }

    private static final int MAX_LIST_ELEM = 4096; // 单个列表元素最大字符数

    public static void encode(S2C_ConfigValuesMessage msg, FriendlyByteBuf buf) {
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

    public static S2C_ConfigValuesMessage decode(FriendlyByteBuf buf) {
        // A3 纵深防御：配置键按 SPEC 也就几百，异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int n = S2C_OperationResult.checkedCount(buf, 4096, "ints");
        Map<String, Integer> ints = new HashMap<>();
        for (int i = 0; i < n; i++) ints.put(buf.readUtf(128), buf.readVarInt());
        int m = S2C_OperationResult.checkedCount(buf, 4096, "bools");
        Map<String, Boolean> bools = new HashMap<>();
        for (int i = 0; i < m; i++) bools.put(buf.readUtf(128), buf.readBoolean());
        int l = S2C_OperationResult.checkedCount(buf, 4096, "lists");
        Map<String, List<String>> lists = new HashMap<>();
        for (int i = 0; i < l; i++) {
            String key = buf.readUtf(128);
            int sz = S2C_OperationResult.checkedCount(buf, 4096, "listElems");
            List<String> list = new ArrayList<>(sz);
            for (int j = 0; j < sz; j++) list.add(buf.readUtf(MAX_LIST_ELEM));
            lists.put(key, list);
        }
        return new S2C_ConfigValuesMessage(ints, bools, lists);
    }

    public Map<String, Integer>      getInts()  { return ints;  }
    public Map<String, Boolean>      getBools() { return bools; }
    public Map<String, List<String>> getLists() { return lists; }

    @Override
    public CustomPacketPayload.Type<S2C_ConfigValuesMessage> type() {
        return TYPE;
    }

    public static void handle(S2C_ConfigValuesMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端配置镜像（ClientConfigMirror.onReceive(msg.getInts(), msg.getBools(), msg.getLists())）
    }
}
