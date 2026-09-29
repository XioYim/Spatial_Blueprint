package com.xioyim.spatialblueprint.client;

import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * 工程 / 子工程蓝图预览的客户端状态：单格登记 —— 同一时间只显示一个工程预览，
 * 现有 engSlot VBO 也只有一个；开新的顶掉旧的。
 *
 * <p>开启后<b>脱离手持</b>一直显示，直到玩家自己关、走远被自动取消、换维度或登出。
 * 换服整份清空（T4）。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientEngineeringPreview {

    private ClientEngineeringPreview() {}

    @Nullable private static String blueprintId = null;
    @Nullable private static BlockPos min = null;
    @Nullable private static BlockPos max = null;
    /** 开启时玩家所在维度（ResourceLocation 字符串）。 */
    @Nullable private static String dimension = null;

    /** 登记一个预览；dimension = 开启时玩家所在维度。 */
    public static void set(String blueprintId, BlockPos min, BlockPos max, String dimension) {
        ClientEngineeringPreview.blueprintId = blueprintId;
        ClientEngineeringPreview.min = min;
        ClientEngineeringPreview.max = max;
        ClientEngineeringPreview.dimension = dimension;
    }

    public static void clear() {
        blueprintId = null;
        min = null;
        max = null;
        dimension = null;
    }

    public static boolean isActive() {
        return blueprintId != null && min != null && max != null;
    }

    /** 当前预览的是不是这份蓝图。 */
    public static boolean isFor(String blueprintId) {
        return blueprintId != null && blueprintId.equals(ClientEngineeringPreview.blueprintId);
    }

    @Nullable public static String blueprintId() { return blueprintId; }
    @Nullable public static BlockPos min() { return min; }
    @Nullable public static BlockPos max() { return max; }
    @Nullable public static String dimension() { return dimension; }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
