package com.xioyim.spatialblueprint.client;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * 客户端提示音（1.20.1 util/NotifySound 的客户端两个方法，迁到 client 包）。
 *
 * <ul>
 *   <li>{@link #client()}      — 成功/中性音效（pling 高音）</li>
 *   <li>{@link #clientError()} — 错误音效（pling 低音）</li>
 * </ul>
 */
public class ClientNotifySound {

    private static final float VOLUME       = 999f;
    private static final float PITCH        = 1.5f;
    private static final float PITCH_ERROR  = 1.0f;

    /**
     * 客户端：在本地玩家位置播放成功/中性音效。
     */
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

    private ClientNotifySound() {}
}
