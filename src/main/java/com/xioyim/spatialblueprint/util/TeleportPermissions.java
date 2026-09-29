package com.xioyim.spatialblueprint.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界级存储：被单独授予「蓝图传送权限」的玩家名单。
 *
 * <p>传送按钮原先硬性要求 OP，服主想让某个建筑师用就只能给他整套 OP 权限。
 * 改为独立的权限位后，OP 用 {@code /sb tpperm grant <玩家>} 授予即可，
 * 拥有该权限的玩家点传送按钮时由服务端代为传送 —— 玩家本身不需要任何 OP 权限。</p>
 *
 * <p>本权限是<b>通用</b>的，次元蓝图与工程蓝图的传送共用它；以后新增的传送入口
 * 直接调用 {@link #canTeleport(ServerPlayer)} 即可。</p>
 *
 * <p>存储与 {@code MaterialSpace} 一样挂在主世界的 {@code DataStorage} 上，
 * 因此对离线玩家也能授予（按 UUID 记录）。</p>
 */
public class TeleportPermissions extends SavedData {

    private static final String SAVE_KEY = "sb_teleport_perms";
    private static final String LIST_KEY = "allowed";

    private final Set<UUID> allowed = new LinkedHashSet<>();

    public static TeleportPermissions get(ServerLevel level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(TeleportPermissions::load, TeleportPermissions::new, SAVE_KEY);
    }

    private TeleportPermissions() {}

    /**
     * 该玩家能否使用蓝图传送。
     *
     * <p>OP 与创造模式玩家天然拥有（沿用改动前的行为），其余玩家看权限名单。</p>
     */
    public static boolean canTeleport(ServerPlayer player) {
        if (player.hasPermissions(2) || player.isCreative()) return true;
        return get(player.serverLevel()).has(player.getUUID());
    }

    public boolean has(UUID player) {
        return allowed.contains(player);
    }

    // ── 传送冷却（服务端强制，e77） ──────────────────────────────────────

    /**
     * 次元 / 工程两类传送共用的服务端冷却（6 秒）。
     *
     * <p>e72 起落点给 5 秒保护效果，冷却必须比效果长，否则连点就能刷出常驻；
     * 客户端 6 秒置灰只是体验，改包连发必须由这道拦。两类共用一张表 ——
     * 分开记的话交替点两边一样能常驻。内存态（重启清零），冷却本就是防连点的短窗。</p>
     */
    public static final long TP_COOLDOWN_MS = 6000L;
    private static final Map<UUID, Long> LAST_TP = new ConcurrentHashMap<>();

    /**
     * 尝试占用一次传送；成功返回 0，冷却中返回剩余毫秒。
     */
    public static long tryAcquireTeleport(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_TP.get(player.getUUID());
        if (last != null && now - last < TP_COOLDOWN_MS) {
            return TP_COOLDOWN_MS - (now - last);
        }
        LAST_TP.put(player.getUUID(), now);
        return 0;
    }

    /** @return {@code true} 表示确实新增了（此前没有） */
    public boolean grant(UUID player) {
        if (!allowed.add(player)) return false;
        setDirty();
        return true;
    }

    /** @return {@code true} 表示确实移除了（此前有） */
    public boolean revoke(UUID player) {
        if (!allowed.remove(player)) return false;
        setDirty();
        return true;
    }

    /** 名单快照（只读）。 */
    public Set<UUID> all() {
        return Collections.unmodifiableSet(allowed);
    }

    // ── 序列化 ────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : allowed) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put(LIST_KEY, list);
        return tag;
    }

    private static TeleportPermissions load(CompoundTag tag) {
        TeleportPermissions data = new TeleportPermissions();
        ListTag list = tag.getList(LIST_KEY, net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.allowed.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // 手改存档写坏的条目直接跳过，不让整份权限表加载失败
            }
        }
        return data;
    }
}
