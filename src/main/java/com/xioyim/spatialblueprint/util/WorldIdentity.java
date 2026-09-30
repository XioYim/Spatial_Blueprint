package com.xioyim.spatialblueprint.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 存档身份：给每个世界一个稳定的 UUID 和名字。
 *
 * <p>Minecraft 本身没有「存档 ID」这种东西 —— 世界名可以改、存档目录可以复制，
 * 都不足以判断「这份蓝图是不是这个存档的」。这里在世界数据里落一个随机 UUID，
 * 第一次访问时生成，之后永不改变；复制存档会连 UUID 一起复制，但那本来就是同一份世界。</p>
 *
 * <p>工程蓝图与次元蓝图靠它判断归属：两者都绑定了固定坐标与维度，换个存档那些坐标
 * 毫无意义，放出来只会砸坏别人的建筑。</p>
 */
public class WorldIdentity extends SavedData {

    private static final String SAVE_KEY = "sb_world_identity";

    private UUID uuid = UUID.randomUUID();

    /**
     * 当前存档快照，按 {@link MinecraftServer} 实例缓存。
     *
     * <p>{@code BlueprintStorage.listSummaries()} 是静态的、拿不到 {@code ServerLevel}，
     * 所以需要这么一份。换存档时服务器实例会换新，{@link #refresh} 据此自动失效重取，
     * 不会把上一个存档的 UUID 带过来。</p>
     */
    @Nullable private static MinecraftServer cachedServer;
    @Nullable private static UUID            cachedUuid;
    @Nullable private static String          cachedName;

    public static WorldIdentity get(ServerLevel level) {
        MinecraftServer server = level.getServer();
        WorldIdentity data = server.overworld()
                .getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(WorldIdentity::new, WorldIdentity::load, null), SAVE_KEY);
        // 立即落盘（必须先标脏再 save：DimensionDataStorage.save() 只写 dirty 条目，
        // computeIfAbsent 新建/载入后不会自动标脏，不标就等于没写）。蓝图 .litematic 创建
        // 即写盘，而身份 dat 若等世界级保存，中途未保存就退出会导致重进换 UUID、同档工程/
        // 次元蓝图被误判成「不属于本档」。访问频率低（换 server / 盖戳），全量刷一次可接受。
        try {
            data.setDirty();
            server.overworld().getDataStorage().save();
        } catch (Exception ignored) {
            // 落盘失败不应阻断蓝图流程；大不了下次访问再刷
        }
        cachedServer = server;
        cachedUuid   = data.uuid;
        cachedName   = server.getWorldData().getLevelName();
        return data;
    }

    /**
     * 给蓝图盖上「产自哪个存档」的戳。
     *
     * <p>蓝图存在 { config/spatial_blueprint/} 下，是所有单人存档共用的一个目录 ——
     * 不盖戳就没法区分手里这份工程 / 次元蓝图是不是本档的。</p>
     */
    public static void stamp(BlueprintData data, ServerLevel level) {
        data.worldUuid = get(level).uuid().toString();
        data.worldName = level.getServer().getWorldData().getLevelName();
        com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.info(
                "[WorldOwn] 创建盖戳 category={} name={} dim={} worldUuid={} worldName={}",
                data.category, data.name, level.dimension().location(),
                data.worldUuid, data.worldName);
    }

    /** 确保静态快照对应当前运行中的服务器；单人存档来回进出也能自动换新。 */
    private static void refresh() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            cachedServer = null;
            cachedUuid   = null;
            cachedName   = null;
            return;
        }
        if (server != cachedServer) get(server.overworld());
    }

    public UUID uuid() {
        return uuid;
    }

    /** 当前存档 UUID 字符串；服务器不在运行时（纯客户端）返回空串。 */
    public static String currentUuidString() {
        refresh();
        return cachedUuid == null ? "" : cachedUuid.toString();
    }

    /** 当前存档名；未知时返回空串。 */
    public static String currentName() {
        refresh();
        return cachedName == null ? "" : cachedName;
    }

    /**
     * 该蓝图记录的存档 UUID 是否属于当前存档。
     *
     * <p>蓝图没记 UUID（本机制加入前创建的）时返回 {@code true} —— 不去为难旧数据。
     * 服务器信息取不到时同样返回 {@code true}，宁可漏判也不能凭空判定玩家的蓝图不属于本档。</p>
     */
    public static boolean belongsToCurrentWorld(String worldUuid) {
        if (worldUuid == null || worldUuid.isEmpty()) return true;
        String cur = currentUuidString();
        if (cur.isEmpty()) return true;
        // 曾在此打逐条 INFO 排查「同档换会话 uuid 变」，bug 已修；
        // 此方法被 listSummaries 对每份蓝图调用，留着会在每次刷库时刷屏。
        return cur.equals(worldUuid);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putString("uuid", uuid.toString());
        return tag;
    }

    private static WorldIdentity load(CompoundTag tag, HolderLookup.Provider provider) {
        WorldIdentity data = new WorldIdentity();
        try {
            data.uuid = UUID.fromString(tag.getString("uuid"));
        } catch (IllegalArgumentException ignored) {
            // 存档里的值写坏了就重新生成一个，不让整个世界数据加载失败
        }
        return data;
    }
}
