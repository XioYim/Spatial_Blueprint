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

    public record ValidationResult(boolean valid, String failKey, String detailKey, Object[] detailArgs) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "", "", new Object[0]);
        }
        public static ValidationResult fail(String key) {
            return new ValidationResult(false, key, "", new Object[0]);
        }
        public static ValidationResult fail(String key, String detailKey, Object... detailArgs) {
            return new ValidationResult(false, key, detailKey, detailArgs);
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

    /**
     * 槽位校验：该玩家在此分类下是否还有空位。
     *
     * <p><b>必须在任何破坏性操作之前调用</b> —— 次元蓝图捕获会清除世界方块，
     * 等清完了才发现存不下就晚了。</p>
     *
     * <p>OP 豁免；上限为 0 表示不限。无主的旧蓝图不计入（见
     * {@link com.xioyim.spatialblueprint.util.BlueprintStorage#countSlots}）。</p>
     */
    public static ValidationResult checkSlot(ServerPlayer player, String category) {
        if (player.hasPermissions(2)) return ValidationResult.ok();
        int limit = BlueprintConfig.getSlotLimitFor(category);
        if (limit <= 0) return ValidationResult.ok();
        int used = com.xioyim.spatialblueprint.util.BlueprintStorage
                .countSlots(player.getUUID().toString(), category);
        if (used < limit) return ValidationResult.ok();
        return ValidationResult.fail("spatial_blueprint.error.slot_full",
                "spatial_blueprint.detail.slot_full", used, limit);
    }

    /**
     * 蓝图管理权判定：<b>主人本人，或 OP</b>。
     *
     * <p>用于删除 / 改名 / 改属性这类会写别人文件的操作。以前这几条只查
     * {@code player.isCreative()} —— 在开放创造的服上等于谁都能改谁的蓝图。</p>
     *
     * <p>无主蓝图（{@code ownerUuid} 为空的旧数据）只有 OP 能碰，与
     * {@link com.xioyim.spatialblueprint.util.BlueprintData#isOwnedBy} 和
     * {@link com.xioyim.spatialblueprint.util.BlueprintStorage#countSlots}
     * 的既定语义一致 —— 那批蓝图不属于任何人，不该因为「谁都不是主人」
     * 就反过来变成谁都能删。</p>
     *
     * <p>找不到文件时返回 {@code false}：调用方拿到的就是「无权 / 不存在」，
     * 不必再分两种失败去区分，也不会因为一个不存在的 id 就放行后续写操作。</p>
     */
    public static boolean canManageBlueprint(ServerPlayer player, String blueprintId) {
        if (player == null || blueprintId == null || blueprintId.isEmpty()) return false;
        if (player.hasPermissions(2)) return true;
        String owner = com.xioyim.spatialblueprint.util.BlueprintStorage.ownerUuidOf(blueprintId);
        return !owner.isEmpty() && owner.equals(player.getUUID().toString());
    }

    public static ValidationResult validateCaptureArea(ServerPlayer player, BlockPos p1, BlockPos p2) {
        return validateCaptureArea(player, p1, p2, FluidCaptureFilter.ALL, "create");
    }

    public static ValidationResult validateCaptureArea(ServerPlayer player, BlockPos p1, BlockPos p2,
                                                        FluidCaptureFilter filter) {
        return validateCaptureArea(player, p1, p2, filter, "create");
    }

    /**
     * @param category 目标蓝图分类，决定体积上限（{@code create} / {@code project} /
     *                 {@code dimensional} / {@code engineering} / {@code template}）。
     *                 上限不再看游戏模式 —— 结构能有多大取决于要存成哪种蓝图。
     */
    public static ValidationResult validateCaptureArea(ServerPlayer player, BlockPos p1, BlockPos p2,
                                                        FluidCaptureFilter filter, String category) {
        ServerLevel level = player.serverLevel();

        if (!level.isInWorldBounds(p1) || !level.isInWorldBounds(p2)) {
            return ValidationResult.fail("spatial_blueprint.error.out_of_bounds");
        }

        int sx = Math.abs(p1.getX() - p2.getX()) + 1;
        int sy = Math.abs(p1.getY() - p2.getY()) + 1;
        int sz = Math.abs(p1.getZ() - p2.getZ()) + 1;

        // 包围盒体积预检：超过绝对上限 1.6M 直接拒绝（包含 OP），避免遍历超大区域
        // 之前 OP 跳过此预检导致选区>1.6M 仍可无限制创建空间/投影/次元/工程四类
        long bbox = volume(p1, p2);
        if (bbox > BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME) {
            return ValidationResult.fail("spatial_blueprint.error.area_too_large",
                    "spatial_blueprint.detail.area_too_large_bbox",
                    sx, sy, sz, bbox, BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME);
        }

        // 统计非空气方块数量（尊重流体过滤，与客户端计数一致）
        long nonAir = countNonAir(level, p1, p2, filter);
        // OP 豁免；其余按目标蓝图分类取上限（与游戏模式无关）
        long effectiveLimit = player.hasPermissions(2)
                ? BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME
                : BlueprintConfig.getMaxVolumeFor(category);
        if (nonAir > effectiveLimit) {
            return ValidationResult.fail("spatial_blueprint.error.area_too_large",
                    "spatial_blueprint.detail.area_too_large",
                    nonAir, effectiveLimit, sx, sy, sz, bbox);
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
