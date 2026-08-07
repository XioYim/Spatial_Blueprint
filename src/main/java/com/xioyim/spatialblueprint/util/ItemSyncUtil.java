package com.xioyim.spatialblueprint.util;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * 使用容器 ID -2 直接写入玩家背包，绕过当前容器的 stateId 检查。
 *
 * 槽位映射（容器 ID -2 使用玩家 Inventory 原始槽位）：
 *   0-8   = 快捷栏（主手 = selected）
 *   9-35  = 主背包
 *   36-39 = 盔甲
 *   40    = 副手
 */
public class ItemSyncUtil {

    public static void syncMainHand(ServerPlayer player) {
        int slot = player.getInventory().selected;
        player.connection.send(new ClientboundContainerSetSlotPacket(
                -2, 0, slot, player.getMainHandItem()
        ));
    }

    public static void syncOffHand(ServerPlayer player) {
        player.connection.send(new ClientboundContainerSetSlotPacket(
                -2, 0, 40, player.getOffhandItem()
        ));
    }

    public static void syncHands(ServerPlayer player) {
        syncMainHand(player);
        syncOffHand(player);
    }

    public static void syncHand(ServerPlayer player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            syncMainHand(player);
        } else {
            syncOffHand(player);
        }
    }

    public static void syncSlot(ServerPlayer player, int inventorySlot) {
        player.connection.send(new ClientboundContainerSetSlotPacket(
                -2, 0, inventorySlot, player.getInventory().getItem(inventorySlot)
        ));
    }
}
