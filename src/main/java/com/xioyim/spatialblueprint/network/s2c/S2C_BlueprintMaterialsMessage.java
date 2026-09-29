package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.gui.MaterialsViewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：某蓝图的材料清单条目（category + key + count），响应
 * {@code C2S_RequestBlueprintMaterialsMessage}。客户端据此打开「查看材料清单」只读页。
 */
public class S2C_BlueprintMaterialsMessage {

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

    public static void handle(S2C_BlueprintMaterialsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void handleClient(S2C_BlueprintMaterialsMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new MaterialsViewScreen(mc.screen, msg.category, msg.keys, msg.counts));
    }
}
