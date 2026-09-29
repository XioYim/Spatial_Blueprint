package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：某蓝图的材料清单条目（category + key + count），响应
 * {@code C2S_RequestBlueprintMaterialsMessage}。客户端据此打开「查看材料清单」只读页。
 */
public class S2C_BlueprintMaterialsMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_BlueprintMaterialsMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_blueprint_materials_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_BlueprintMaterialsMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_BlueprintMaterialsMessage::encode, S2C_BlueprintMaterialsMessage::decode);

    private final String blueprintId;
    /** 蓝图分类（create/dimensional 等），客户端据此决定是否显示「空间已有/需求」。 */
    private final String category;
    private final List<String> keys;
    private final List<Integer> counts;

    public S2C_BlueprintMaterialsMessage(String blueprintId, String category,
                                         List<String> keys, List<Integer> counts) {
        this.blueprintId = blueprintId == null ? "" : blueprintId;
        this.category = category == null ? "" : category;
        this.keys = keys != null ? keys : List.of();
        this.counts = counts != null ? counts : List.of();
    }

    @Override
    public CustomPacketPayload.Type<S2C_BlueprintMaterialsMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_BlueprintMaterialsMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.category, 32);
        buf.writeVarInt(msg.keys.size());
        for (int i = 0; i < msg.keys.size(); i++) {
            buf.writeUtf(msg.keys.get(i), 256);
            buf.writeVarInt(msg.counts.get(i));
        }
    }

    public static S2C_BlueprintMaterialsMessage decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        String category = buf.readUtf(32);
        int n = buf.readVarInt();
        n = Math.min(n, 300);
        List<String> keys = new ArrayList<>(n);
        List<Integer> counts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(buf.readUtf(256));
            counts.add(buf.readVarInt());
        }
        return new S2C_BlueprintMaterialsMessage(id, category, keys, counts);
    }

    public static void handle(S2C_BlueprintMaterialsMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端打开「查看材料清单」只读页（原 handleClient：Minecraft.setScreen(new MaterialsViewScreen(...))）
    }
}
