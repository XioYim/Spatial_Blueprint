package com.xioyim.spatialblueprint.log;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public class BlueprintLogger {

    public enum Action {
        SET_POINT,
        CLEAR_POINTS,
        CAPTURE,
        PLACE,
        UPDATE_OFFSET,
        UPDATE_SETTINGS,
        SET_NAME
    }

    public static void intent(ServerPlayer player, Action action, String detail) {
        SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint/{}] player={} {}",
                action.name(), playerName(player), detail);
    }

    public static void success(ServerPlayer player, Action action, String detail) {
        SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint/{}] player={} {}  result=SUCCESS",
                action.name(), playerName(player), detail);
    }

    public static void failed(ServerPlayer player, Action action, String detail, String reason) {
        SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint/{}] player={} {}  result=FAILED  reason={}",
                action.name(), playerName(player), detail, reason);
    }

    public static String pos(BlockPos pos) {
        return "pos=(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    public static String area(BlockPos p1, BlockPos p2) {
        return "area=(" + p1.getX() + "," + p1.getY() + "," + p1.getZ() + ")"
             + "→(" + p2.getX() + "," + p2.getY() + "," + p2.getZ() + ")";
    }

    public static String volume(BlockPos p1, BlockPos p2) {
        long v = (long)(Math.abs(p1.getX() - p2.getX()) + 1)
               * (Math.abs(p1.getY() - p2.getY()) + 1)
               * (Math.abs(p1.getZ() - p2.getZ()) + 1);
        return "volume=" + v;
    }

    public static String name(String n) {
        return "name=\"" + n + "\"";
    }

    private static String playerName(ServerPlayer player) {
        return player.getName().getString() + "/" + player.getStringUUID().substring(0, 8);
    }
}
