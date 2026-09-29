package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：一次<b>真正成功</b>的蓝图创建之后，通知客户端开始捕获冷却。
 *
 * <p>以前冷却是客户端在 [创建] 按钮被按下的那一刻自己起算的 —— 而那一步只是打开
 * 「选择创建类型」界面，玩家还没选类型、没看检测报告，更没真正捕获。
 * 结果就是点开看一眼再取消，照样要等 15 秒。而且那是个客户端静态字段，OP 也一样被卡。</p>
 *
 * <p>改由服务端在捕获全部成功、蓝图落盘之后才发这个包：中途取消、体积超限、
 * 槽位已满、检测被拒 —— 一律不会收到，也就不进冷却。<b>OP 直接不发</b>。</p>
 */
public class S2C_CaptureCooldownMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_CaptureCooldownMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_capture_cooldown_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_CaptureCooldownMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_CaptureCooldownMessage::encode, S2C_CaptureCooldownMessage::decode);

    /** 捕获冷却时长（秒）。非 OP 玩家一次成功创建之后要等这么久才能再来一次。 */
    public static final int COOLDOWN_SECONDS = 15;

    private final int seconds;

    public S2C_CaptureCooldownMessage(int seconds) {
        this.seconds = seconds;
    }

    public int getSeconds() {
        return seconds;
    }

    @Override
    public CustomPacketPayload.Type<S2C_CaptureCooldownMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_CaptureCooldownMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.seconds);
    }

    public static S2C_CaptureCooldownMessage decode(FriendlyByteBuf buf) {
        return new S2C_CaptureCooldownMessage(buf.readVarInt());
    }

    public static void handle(S2C_CaptureCooldownMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端捕获冷却（原 handleClient：ClientCaptureCooldown.start(msg.getSeconds())）
    }

    /**
     * 服务端侧的统一发送口 —— 所有创建入口成功后都调它。
     *
     * <p>OP 不发：OP 本来就豁免体积、槽位、模式门槛，冷却没有理由是例外。</p>
     */
    public static void notifySuccess(net.minecraft.server.level.ServerPlayer player) {
        if (player == null || player.hasPermissions(2)) return;
        com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(
                player, new S2C_CaptureCooldownMessage(COOLDOWN_SECONDS));
    }
}
