package com.xioyim.spatialblueprint.server;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class BlueprintValidator {

    public record ValidationResult(boolean valid, String failKey, String detail) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "", "");
        }
        public static ValidationResult fail(String key) {
            return new ValidationResult(false, key, "");
        }
        public static ValidationResult fail(String key, String detail) {
            return new ValidationResult(false, key, detail);
        }
    }

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_OFFSET = 512;

    // ── Global per-player action cooldown (Bug 5) ─────────────────────────
    /** Ticks between allowed blueprint actions per player (2 seconds at 20 TPS). */
    private static final long ACTION_COOLDOWN_TICKS = 40L;
    private static final Map<UUID, Long> lastActionTime = new ConcurrentHashMap<>();

    /**
     * Returns true and records the action time if the player is not on cooldown.
     * Returns false (cooldown still active) without updating the map if blocked.
     */
    /** Removes the cooldown entry for a player (call on logout to prevent unbounded map growth). */
    public static void clearCooldown(UUID playerId) {
        lastActionTime.remove(playerId);
    }

    public static boolean checkAndUpdateCooldown(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        boolean[] allowed = {false};
        lastActionTime.compute(player.getUUID(), (uuid, last) -> {
            if (last == null || now - last >= ACTION_COOLDOWN_TICKS) {
                allowed[0] = true;
                return now;
            }
            return last; // still on cooldown, do not update
        });
        return allowed[0];
    }

    // ── 蓝图上传限流（冷却 + 每分钟上限）──────────────────────────────────────
    // 每次上传都要在主线程同步做解压/净化/落盘/回传，没有限流的话刷包能直接卡垮服务器。
    private static final Map<UUID, Long> lastUploadTime = new ConcurrentHashMap<>();
    private static final Map<UUID, ArrayDeque<Long>> uploadHistory = new ConcurrentHashMap<>();

    /** 玩家登出时清理上传限流记录，防止 map 无限增长。 */
    public static void clearUploadRateLimit(UUID playerId) {
        lastUploadTime.remove(playerId);
        uploadHistory.remove(playerId);
    }

    /**
     * 检查上传冷却与滚动 60 秒窗口内的次数上限。允许则记录本次并返回 true；
     * 命中限流则不修改任何状态，返回 false。
     *
     * <p>OP 玩家用更短的冷却（{@link BlueprintConfig#UPLOAD_COOLDOWN_SECONDS_OP}，默认 1 秒），
     * 并且完全不受每分钟次数上限约束——只保留最低限度的冷却，防止卡死的客户端/宏每 tick 发包。</p>
     */
    public static boolean checkAndUpdateUploadRateLimit(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        UUID id = player.getUUID();
        boolean isOp = player.hasPermissions(2);

        long cooldownTicks = (isOp
                ? BlueprintConfig.UPLOAD_COOLDOWN_SECONDS_OP.get()
                : BlueprintConfig.UPLOAD_COOLDOWN_SECONDS.get()) * 20L;
        Long last = lastUploadTime.get(id);
        if (last != null && now - last < cooldownTicks) return false;

        if (!isOp) {
            ArrayDeque<Long> history = uploadHistory.computeIfAbsent(id, k -> new ArrayDeque<>());
            final long windowTicks = 60L * 20L;
            while (!history.isEmpty() && now - history.peekFirst() > windowTicks) {
                history.pollFirst();
            }
            if (history.size() >= BlueprintConfig.UPLOAD_MAX_PER_MINUTE.get()) return false;
            history.addLast(now);
        }

        lastUploadTime.put(id, now);
        return true;
    }

    public static ValidationResult validateCaptureArea(ServerPlayer player, BlockPos p1, BlockPos p2) {
        return validateCaptureArea(player, p1, p2, FluidCaptureFilter.ALL);
    }

    public static ValidationResult validateCaptureArea(ServerPlayer player, BlockPos p1, BlockPos p2, FluidCaptureFilter filter) {
        ServerLevel level = player.serverLevel();

        if (!level.isInWorldBounds(p1) || !level.isInWorldBounds(p2)) {
            return ValidationResult.fail("spatial_blueprint.error.out_of_bounds");
        }

        int sx = Math.abs(p1.getX() - p2.getX()) + 1;
        int sy = Math.abs(p1.getY() - p2.getY()) + 1;
        int sz = Math.abs(p1.getZ() - p2.getZ()) + 1;

        // 包围盒体积预检（非 OP：超过绝对上限直接拒绝，避免遍历超大区域）
        // OP 跳过此预检，直接走非空气方块计数（OP 上限 = ABSOLUTE_MAX_CAPTURE_VOLUME）
        long bbox = volume(p1, p2);
        if (!player.hasPermissions(2) && bbox > BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME) {
            String detail = "空间盒: " + sx + "×" + sy + "×" + sz + " = " + bbox + " 格"
                    + "，上限: " + BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME + " 格";
            return ValidationResult.fail("spatial_blueprint.error.area_too_large", detail);
        }

        // 统计非空气方块数量（尊重流体过滤，与客户端计数一致）
        long nonAir = countNonAir(level, p1, p2, filter);
        long effectiveLimit;
        if (player.hasPermissions(2)) {
            effectiveLimit = BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME;
        } else {
            int configLimit = player.isCreative()
                    ? BlueprintConfig.MAX_CAPTURE_VOLUME_CREATIVE.get()
                    : BlueprintConfig.MAX_CAPTURE_VOLUME_SURVIVAL.get();
            effectiveLimit = Math.min(configLimit, BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME);
        }
        if (nonAir > effectiveLimit) {
            String detail = "方块数: " + nonAir + "，上限: " + effectiveLimit
                    + "\n空间盒: " + sx + "×" + sy + "×" + sz + " = " + bbox + " 格";
            return ValidationResult.fail("spatial_blueprint.error.area_too_large", detail);
        }

        return ValidationResult.ok();
    }

    /** 统计选区内非空气方块数量（含流体）。 */
    public static long countNonAir(ServerLevel level, BlockPos p1, BlockPos p2) {
        return countNonAir(level, p1, p2, FluidCaptureFilter.ALL);
    }

    /** 统计选区内非空气方块数量，尊重流体过滤。 */
    public static long countNonAir(ServerLevel level, BlockPos p1, BlockPos p2, FluidCaptureFilter filter) {
        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
        long count = 0;
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    net.minecraft.world.level.block.state.BlockState bs = level.getBlockState(new BlockPos(x, y, z));
                    if (!bs.isAir() && !filter.shouldSkip(bs)) count++;
                }
        return count;
    }

    public static ValidationResult validateBlockPos(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) {
            return ValidationResult.fail("spatial_blueprint.error.out_of_bounds");
        }
        return ValidationResult.ok();
    }

    /**
     * 校验放置目标点是否在玩家的合法范围内。
     * 允许距离 = previewDistance + MAX_OFFSET，涵盖预览距离 + 最大偏移。
     */
    public static ValidationResult validatePlacementDistance(ServerPlayer player, BlockPos targetPos) {
        int maxDist = BlueprintConfig.PREVIEW_DISTANCE.get() + MAX_OFFSET;
        Vec3 center = Vec3.atCenterOf(targetPos);
        double distSq = player.position().distanceToSqr(center);
        if (distSq > (double) maxDist * maxDist) {
            return ValidationResult.fail("spatial_blueprint.error.target_too_far");
        }
        return ValidationResult.ok();
    }

    /** Pre-compiled pattern for {@link #sanitizeName}: avoids recompiling on every call. */
    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[^\\p{L}\\p{N}\\s\\-._]");

    public static String sanitizeName(String name) {
        if (name == null) return "";
        String trimmed = name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
        // \p{L} = Unicode letters (includes Chinese/Japanese/Korean), \p{N} = Unicode digits
        return INVALID_NAME_CHARS.matcher(trimmed).replaceAll("");
    }

    public static int clampOffset(int value) {
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, value));
    }

    public static long volume(BlockPos p1, BlockPos p2) {
        return (long)(Math.abs(p1.getX() - p2.getX()) + 1)
             * (Math.abs(p1.getY() - p2.getY()) + 1)
             * (Math.abs(p1.getZ() - p2.getZ()) + 1);
    }
}
