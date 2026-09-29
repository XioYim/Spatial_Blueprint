package com.xioyim.spatialblueprint.config;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可在游戏内编辑的配置项清单 —— 按 toml 路径索引。
 *
 * <p>配置注册的是 {@link net.minecraftforge.fml.config.ModConfig.Type#COMMON}，
 * 也就是说服务端和客户端<b>各有一份自己的 toml</b>。而 {@code /sb config} 只是让客户端
 * 打开界面，界面里直接 {@code BlueprintConfig.XXX.get()/set()} 读写的是<b>客户端那份</b> ——
 * 服主在服务器上敲 {@code /sb config} 改半天，改的其实是自己客户端的文件，服务器毫不知情。</p>
 *
 * <p>修法是让配置界面走一趟网络：拉服务端的值 → 本地编辑 → 提交回服务端保存。
 * 两端要能对上「哪一项」，就需要一个稳定的标识 —— 这里用
 * {@link ForgeConfigSpec.ConfigValue#getPath()} 拼出来的 toml 路径
 * （如 {@code volumeLimits.maxVolumeSpatial}），它本来就是配置文件里的真实位置，
 * 不需要额外维护一张对照表。</p>
 */
public final class ConfigRegistry {

    private ConfigRegistry() {}

    /** 路径 → 整数配置项。顺序无关紧要，用 LinkedHashMap 只是为了日志好看。 */
    private static final Map<String, ForgeConfigSpec.IntValue> INTS = new LinkedHashMap<>();
    /** 路径 → 布尔配置项。 */
    private static final Map<String, ForgeConfigSpec.BooleanValue> BOOLS = new LinkedHashMap<>();
    /** 路径 → 字符串列表配置项。 */
    @SuppressWarnings("unchecked")
    private static final Map<String, ForgeConfigSpec.ConfigValue<List<String>>> LISTS = new LinkedHashMap<>();

    static {
        for (ForgeConfigSpec.IntValue v : new ForgeConfigSpec.IntValue[]{
                BlueprintConfig.MAX_VOLUME_SPATIAL,
                BlueprintConfig.MAX_VOLUME_PROJECTION,
                BlueprintConfig.MAX_VOLUME_DIMENSIONAL,
                BlueprintConfig.MAX_VOLUME_ENGINEERING,
                BlueprintConfig.MAX_VOLUME_TEMPLATE,
                BlueprintConfig.MAX_VOLUME_PROJECTSET,
                BlueprintConfig.MAX_VOLUME_SUBPROJECT,
                BlueprintConfig.SLOT_LIMIT_SPATIAL,
                BlueprintConfig.SLOT_LIMIT_PROJECTION,
                BlueprintConfig.SLOT_LIMIT_DIMENSIONAL,
                BlueprintConfig.SLOT_LIMIT_ENGINEERING,
                BlueprintConfig.SLOT_LIMIT_TEMPLATE,
                BlueprintConfig.PREVIEW_DISTANCE,
                BlueprintConfig.RENDER_CANCEL_MARGIN,
                BlueprintConfig.PREVIEW_BROADCAST_RADIUS,
                BlueprintConfig.PREVIEW_BROADCAST_INTERVAL,
                BlueprintConfig.LOG_RETENTION_DAYS_PLACEMENT,
                BlueprintConfig.LOG_RETENTION_DAYS_ADMIN,
                BlueprintConfig.MATERIAL_SPACE_MAX_COUNT,
                BlueprintConfig.ANIMATION_SPEED,
                BlueprintConfig.ANIMATION_DURATION_MIN,
                BlueprintConfig.ANIMATION_DURATION_MAX,
                BlueprintConfig.EXTRA_COOLDOWN_SECONDS,
                BlueprintConfig.MAX_UNDO_HISTORY,
                BlueprintConfig.BUILD_TIME_TIER1_MAX_BLOCKS,
                BlueprintConfig.BUILD_TIME_TIER1_SECONDS,
                BlueprintConfig.BUILD_TIME_TIER2_MAX_BLOCKS,
                BlueprintConfig.BUILD_TIME_TIER2_SECONDS,
                BlueprintConfig.BUILD_TIME_TIER3_MAX_BLOCKS,
                BlueprintConfig.BUILD_TIME_TIER3_SECONDS,
                BlueprintConfig.BUILD_TIME_TIER4_MAX_BLOCKS,
                BlueprintConfig.BUILD_TIME_TIER4_SECONDS,
                BlueprintConfig.BUILD_TIME_TIER5_MAX_BLOCKS,
                BlueprintConfig.BUILD_TIME_TIER5_SECONDS,
                BlueprintConfig.BUILD_TIME_TIER6_MAX_BLOCKS,
                BlueprintConfig.BUILD_TIME_TIER6_SECONDS,
                BlueprintConfig.BUILD_TIME_TIER7_SECONDS,
                BlueprintConfig.SUBPROJECT_CLAIM_COOLDOWN,
                BlueprintConfig.LARGE_BLUEPRINT_BROADCAST_THRESHOLD,
                BlueprintConfig.MAX_UPLOAD_FILE_BYTES,
                BlueprintConfig.MAX_NBT_DEPTH,
                BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES,
                BlueprintConfig.MAX_TOTAL_NBT_BYTES,
                BlueprintConfig.MAX_BOUNDING_VOLUME,
                BlueprintConfig.UPLOAD_COOLDOWN_SECONDS,
                BlueprintConfig.UPLOAD_COOLDOWN_SECONDS_OP,
                BlueprintConfig.UPLOAD_MAX_PER_MINUTE,
        }) {
            INTS.put(idOf(v), v);
        }
        for (ForgeConfigSpec.BooleanValue v : new ForgeConfigSpec.BooleanValue[]{
                BlueprintConfig.LARGE_BLUEPRINT_BROADCAST_ENABLED,
                BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA,
        }) {
            BOOLS.put(idOf(v), v);
        }
        for (@SuppressWarnings("unchecked") ForgeConfigSpec.ConfigValue<List<String>> v :
                new ForgeConfigSpec.ConfigValue[]{
                BlueprintConfig.EXCLUDED_BLOCKS,
                BlueprintConfig.EXCLUDED_ITEMS,
                BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS,
                BlueprintConfig.LATE_PLACE_BLOCKS,
                BlueprintConfig.HIDDEN_PREVIEW_BLOCKS,
                BlueprintConfig.SPECIAL_DROP_ITEMS,
                GeneratedSubstitutionsConfig.BLOCK_SUBSTITUTIONS,
                GeneratedSubstitutionsConfig.BULK_CONVERSIONS,
                GeneratedSubstitutionsConfig.GENERATED,
        }) {
            LISTS.put(idOf(v), v);
        }
    }

    /** 配置项的稳定标识 —— 就是它在 toml 里的路径。 */
    public static String idOf(ForgeConfigSpec.ConfigValue<?> value) {
        return String.join(".", value.getPath());
    }

    public static Map<String, ForgeConfigSpec.IntValue> ints() {
        return Collections.unmodifiableMap(INTS);
    }

    public static Map<String, ForgeConfigSpec.BooleanValue> bools() {
        return Collections.unmodifiableMap(BOOLS);
    }

    @Nullable
    public static ForgeConfigSpec.IntValue intAt(String id) {
        return INTS.get(id);
    }

    @Nullable
    public static ForgeConfigSpec.BooleanValue boolAt(String id) {
        return BOOLS.get(id);
    }

    public static Map<String, ForgeConfigSpec.ConfigValue<List<String>>> lists() {
        return Collections.unmodifiableMap(LISTS);
    }

    @Nullable
    public static ForgeConfigSpec.ConfigValue<List<String>> listAt(String id) {
        return LISTS.get(id);
    }

    /** 写入列表值。不做范围校验（列表项无 defineInRange）。null = id 不存在。 */
    @Nullable
    public static List<String> applyList(String id, List<String> raw) {
        ForgeConfigSpec.ConfigValue<List<String>> v = LISTS.get(id);
        if (v == null) return null;
        v.set(new java.util.ArrayList<>(raw));
        return raw;
    }

    /**
     * 把一个整数值写进配置，<b>先用该项自己的 spec 校验一遍</b>。
     *
     * <p>客户端提交的数字不可信：超出 {@code defineInRange} 范围的值写进去，会让
     * ForgeConfigSpec 在下次校验时把<b>整个配置文件</b>重置为默认值 ——
     * 一个伪造的包能顺手抹掉服主的全部配置。</p>
     *
     * @return 实际写入的值；校验不通过时保持原值并返回它，该 id 不存在时返回 {@code null}
     */
    @Nullable
    public static Integer applyInt(String id, int raw) {
        ForgeConfigSpec.IntValue v = INTS.get(id);
        if (v == null) return null;
        if (!accepts(v, raw)) return v.get();
        v.set(raw);
        return raw;
    }

    /** 把一个布尔值写进配置。 */
    @Nullable
    public static Boolean applyBool(String id, boolean raw) {
        ForgeConfigSpec.BooleanValue v = BOOLS.get(id);
        if (v == null) return null;
        v.set(raw);
        return raw;
    }

    /**
     * 该值是否通过这一项的 spec 校验（{@code defineInRange} 的上下界就在里面）。
     *
     * <p>{@code ConfigValue} 本身不暴露取值范围，得从 spec 里按路径把 {@code ValueSpec}
     * 取回来 —— 那才是 {@code defineInRange} 真正登记范围的地方。</p>
     */
    private static boolean accepts(ForgeConfigSpec.ConfigValue<?> value, Object candidate) {
        try {
            // 必须是 getSpec()：getValues() 那张表放的是 ConfigValue，不是 ValueSpec，
            // 用错表 instanceof 永远不中 → 这道范围校验等于从来没生效过。
            Object spec = BlueprintConfig.SPEC.getSpec().get(value.getPath());
            if (spec instanceof ForgeConfigSpec.ValueSpec vs) return vs.test(candidate);
        } catch (Exception ignored) {
            // 取不到 spec 就不拦 —— 宁可放过一个也不要因为反射细节把正常保存卡死
        }
        return true;
    }
    /** 所有登记项的路径（调试用）。 */
    public static List<String> allIds() {
        List<String> ids = new java.util.ArrayList<>(INTS.keySet());
        ids.addAll(BOOLS.keySet());
        return ids;
    }

    /**
     * 该项在代码里的默认值（{@code defineInRange} 的首参）。
     *
     * <p>「重置为默认」按钮要用它 —— 注意不是当前配置值，是 spec 里登记的出厂默认。</p>
     */
    public static int defaultInt(ForgeConfigSpec.IntValue value) {
        // ConfigValue 自己就记着 defineInRange 的首参（defaultSupplier），不依赖配置是否已加载。
        // 以前从 SPEC.getValues() 按路径找 ValueSpec —— 那张表里放的是 ConfigValue，
        // instanceof 永远不中，于是「默认值」实际返回的是当前值：玩家把 1 改成 5 保存后
        // 再开界面，♻ 就把 5 当默认了。
        return value.getDefault();
    }
}
