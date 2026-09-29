package com.xioyim.spatialblueprint.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：把「本玩家能否使用蓝图传送」的结论推给客户端。
 *
 * <p>客户端自己算不出这个结论 —— 权限名单存在服务端的世界数据里。
 * 没有它的话传送按钮无法正确置灰，tooltip 也没法区分「点击即可传送」与「你没有传送权限」。</p>
 *
 * <p>推送时机：玩家登录、被 {@code /sb tpperm} 授予或撤销、切换游戏模式。
 * 这里推的是<b>最终结论</b>（已把 OP 与创造模式算进去），客户端只存一个布尔值。</p>
 */
public class S2C_TeleportPermMessage {

    private final boolean allowed;

    public S2C_TeleportPermMessage(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public static void encode(S2C_TeleportPermMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.allowed);
    }

    public static S2C_TeleportPermMessage decode(FriendlyByteBuf buf) {
        return new S2C_TeleportPermMessage(buf.readBoolean());
    }

    public static void handle(S2C_TeleportPermMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    // 必须标 @OnlyIn：lambda 体只调用本方法，客户端类型只出现在这里，
    // 否则专用服务端注册消息（创建 ::handle 方法引用）时就会去加载客户端类。
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void handleClient(S2C_TeleportPermMessage msg) {
        com.xioyim.spatialblueprint.client.ClientTeleportPermCache.set(msg.isAllowed());
    }
}
