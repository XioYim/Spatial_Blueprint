package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：登录时把「本玩家是否能在服务器上打开/编辑配置」的结论推给客户端。
 *
 * <p>配置界面读写的是服务端配置（OP 专属），客户端自己算不出自己是不是 OP ——
 * 不推这份结论，非 OP 也能点开 Mod 列表里的 Config 按钮，看到一个读着本地 toml、
 * 保存又被服务端拒绝的假页面。真正的拦截仍在服务端（C2S 权限校验），这里只是别让页面出现。</p>
 */
public class S2C_ConfigPermMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_ConfigPermMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_config_perm_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_ConfigPermMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_ConfigPermMessage::encode, S2C_ConfigPermMessage::decode);

    private final boolean allowed;

    public S2C_ConfigPermMessage(boolean allowed) {
        this.allowed = allowed;
    }

    public static void encode(S2C_ConfigPermMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.allowed);
    }

    public static S2C_ConfigPermMessage decode(FriendlyByteBuf buf) {
        return new S2C_ConfigPermMessage(buf.readBoolean());
    }

    @Override
    public CustomPacketPayload.Type<S2C_ConfigPermMessage> type() {
        return TYPE;
    }

    public static void handle(S2C_ConfigPermMessage msg, IPayloadContext ctx) {
        com.xioyim.spatialblueprint.client.ClientConfigPermCache.set(msg.allowed);
    }
}
