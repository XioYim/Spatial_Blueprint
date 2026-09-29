package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.client.BlueprintSelectionRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端 → 客户端：放置加载动画进度（-1 = 拒绝并清除；0~1 = 真实进度）。 */
public class S2C_PlacementProgressMessage {

    public final float progress;

    public S2C_PlacementProgressMessage(float progress) {
        this.progress = progress;
    }

    public static void encode(S2C_PlacementProgressMessage msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.progress);
    }

    public static S2C_PlacementProgressMessage decode(FriendlyByteBuf buf) {
        return new S2C_PlacementProgressMessage(buf.readFloat());
    }

    public static void handle(S2C_PlacementProgressMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void handleClient(S2C_PlacementProgressMessage msg) {
        if (msg.progress < 0f) {
            BlueprintSelectionRenderer.stopPlacementAnimation();
        } else {
            BlueprintSelectionRenderer.setPlacementServerProgress(msg.progress);
        }
    }
}
