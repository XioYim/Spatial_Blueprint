package com.xioyim.spatialblueprint.util;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * 播放通知音效。
 *
 * <ul>
 *   <li>{@link #server(Player)}      — 服务端，成功/中性音效（pling 高音）</li>
 *   <li>{@link #serverError(Player)} — 服务端，错误音效（pling 低音）</li>
 * </ul>
 *
 * <p>客户端两个（client / clientError）在 {@link com.xioyim.spatialblueprint.client.ClientNotifySound}。</p>
 */
public class NotifySound {

    private static final float VOLUME       = 999f;
    private static final float PITCH        = 1.5f;
    private static final float PITCH_ERROR  = 1.0f;

    /**
     * 服务端：仅向指定玩家发送成功/中性音效包。
     */
    public static void server(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        sp.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_PLING,   // already a Holder<SoundEvent> in 1.20.1
                SoundSource.PLAYERS,
                sp.getX(), sp.getY(), sp.getZ(),
                VOLUME, PITCH,
                sp.getRandom().nextLong()));
    }

    /**
     * 服务端：仅向指定玩家发送错误音效包。
     */
    public static void serverError(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        sp.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_PLING,
                SoundSource.PLAYERS,
                sp.getX(), sp.getY(), sp.getZ(),
                VOLUME, PITCH_ERROR,
                sp.getRandom().nextLong()));
    }

    private NotifySound() {}
}
