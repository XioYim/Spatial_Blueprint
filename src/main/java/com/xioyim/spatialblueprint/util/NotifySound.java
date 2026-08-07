package com.xioyim.spatialblueprint.util;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 播放通知音效。
 *
 * <ul>
 *   <li>{@link #server(Player)}      — 服务端，成功/中性音效（pling 高音）</li>
 *   <li>{@link #client()}            — 客户端，成功/中性音效（pling 高音）</li>
 *   <li>{@link #serverError(Player)} — 服务端，错误音效（pling 低音）</li>
 *   <li>{@link #clientError()}       — 客户端，错误音效（pling 低音）</li>
 * </ul>
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

    /**
     * 客户端：在本地玩家位置播放成功/中性音效。
     */
    @OnlyIn(Dist.CLIENT)
    public static void client() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            mc.level.playLocalSound(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    SoundEvents.NOTE_BLOCK_PLING.value(),  // unwrap Holder → SoundEvent
                    SoundSource.PLAYERS,
                    VOLUME, PITCH, false);
        }
    }

    /**
     * 客户端：在本地玩家位置播放错误音效。
     */
    @OnlyIn(Dist.CLIENT)
    public static void clientError() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            mc.level.playLocalSound(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    SoundEvents.NOTE_BLOCK_PLING.value(),
                    SoundSource.PLAYERS,
                    VOLUME, PITCH_ERROR, false);
        }
    }

    private NotifySound() {}
}
