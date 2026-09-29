package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：登录时把当前存档的身份（UUID + 存档名）推给客户端。
 *
 * <p>蓝图存在 {@code config/spatial_blueprint/} 下，是所有单人存档共用的一个目录，
 * 而工程 / 次元蓝图绑定了固定坐标与维度 —— 换个存档那些坐标毫无意义。
 * 客户端拿到这份身份后，右键时就能立刻判断手里这张蓝图是不是本档的，
 * 不必先开界面再被服务端赶出来。</p>
 */
public class S2C_WorldIdentityMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_WorldIdentityMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_world_identity_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_WorldIdentityMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_WorldIdentityMessage::encode, S2C_WorldIdentityMessage::decode);

    private final String uuid;
    private final String name;

    public S2C_WorldIdentityMessage(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public String getUuid() { return uuid; }

    public String getName() { return name; }

    @Override
    public CustomPacketPayload.Type<S2C_WorldIdentityMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_WorldIdentityMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.uuid, 64);
        buf.writeUtf(msg.name, 64);
    }

    public static S2C_WorldIdentityMessage decode(FriendlyByteBuf buf) {
        return new S2C_WorldIdentityMessage(buf.readUtf(64), buf.readUtf(64));
    }

    public static void handle(S2C_WorldIdentityMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端存档身份缓存（原 handleClient：ClientWorldIdentity.set(msg.getUuid(), msg.getName())）
    }
}
