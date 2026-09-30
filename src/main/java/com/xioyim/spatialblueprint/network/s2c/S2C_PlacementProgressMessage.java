package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端 → 客户端：放置加载动画进度（-1 = 拒绝并清除；0~1 = 真实进度）。 */
public class S2C_PlacementProgressMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_PlacementProgressMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_placement_progress_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_PlacementProgressMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_PlacementProgressMessage::encode, S2C_PlacementProgressMessage::decode);

    public final float progress;

    public S2C_PlacementProgressMessage(float progress) {
        this.progress = progress;
    }

    @Override
    public CustomPacketPayload.Type<S2C_PlacementProgressMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_PlacementProgressMessage msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.progress);
    }

    public static S2C_PlacementProgressMessage decode(FriendlyByteBuf buf) {
        return new S2C_PlacementProgressMessage(buf.readFloat());
    }

    public static void handle(S2C_PlacementProgressMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端放置进度（原 handleClient：progress < 0 → BlueprintSelectionRenderer.stopPlacementAnimation()，
        // 否则 setPlacementServerProgress(msg.progress)）
    }
}
