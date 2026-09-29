package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：把「本玩家能否使用蓝图传送」的结论推给客户端。
 *
 * <p>客户端自己算不出这个结论 —— 权限名单存在服务端的世界数据里。
 * 没有它的话传送按钮无法正确置灰，tooltip 也没法区分「点击即可传送」与「你没有传送权限」。</p>
 *
 * <p>推送时机：玩家登录、被 {@code /sb tpperm} 授予或撤销、切换游戏模式。
 * 这里推的是<b>最终结论</b>（已把 OP 与创造模式算进去），客户端只存一个布尔值。</p>
 */
public class S2C_TeleportPermMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_TeleportPermMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_teleport_perm_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_TeleportPermMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_TeleportPermMessage::encode, S2C_TeleportPermMessage::decode);

    private final boolean allowed;

    public S2C_TeleportPermMessage(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean isAllowed() {
        return allowed;
    }

    @Override
    public CustomPacketPayload.Type<S2C_TeleportPermMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_TeleportPermMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.allowed);
    }

    public static S2C_TeleportPermMessage decode(FriendlyByteBuf buf) {
        return new S2C_TeleportPermMessage(buf.readBoolean());
    }

    public static void handle(S2C_TeleportPermMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端传送权限缓存（原 handleClient：ClientTeleportPermCache.set(msg.isAllowed())）
    }
}
