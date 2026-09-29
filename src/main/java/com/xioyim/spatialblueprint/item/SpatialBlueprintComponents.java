package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.CaptureMode;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 蓝图物品的自定义数据组件（39 个，一键一组件）。
 *
 * <p>1.20.1 线把状态存在物品 NBT 的自由键里；1.21 物品没有 NBT，这里按
 * {@code D1}（组件只存引用与小字段，方块数据留在服务端文件里）为每个旧键建一个组件。
 * 每个 setter 是一次原子 {@code stack.set}，不存在「取出副本改完忘写回」的坑；
 * 旧键 → 组件的对照表见 {@link LegacyItemMigration}。</p>
 *
 * <p>全部 persistent + networkSynchronized：1.20.1 的物品 NBT 本来就整份同步给客户端
 * （界面要读 blueprint_id / 名称等），少一个就是客户端看不见 —— 双端不同步。</p>
 */
public final class SpatialBlueprintComponents {

    private SpatialBlueprintComponents() {}

    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, SpatialBlueprintMod.MODID);

    // ── 字符串 ──────────────────────────────────────────────────────────────
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BLUEPRINT_ID =
            registerString("blueprint_id");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BLUEPRINT_NAME =
            registerString("blueprint_name");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> CREATOR_NAME =
            registerString("creator_name");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> ALIGN_MODE =
            registerString("align_mode");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SOURCE_TYPE =
            registerString("source_type");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> TP_DIMENSION =
            registerString("tp_dimension");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> WORLD_UUID =
            registerString("world_uuid");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> WORLD_NAME =
            registerString("world_name");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> ENG_PROJECT_ID =
            registerString("eng_project_id");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> ENG_PROJECT_NAME =
            registerString("eng_project_name");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> ENG_DIMENSION =
            registerString("eng_dimension");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SB_TASK_SET_ID =
            registerString("sb_task_set_id");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> PROJECT_ID =
            registerString("project_id");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> TASK_SET_ID =
            registerString("task_set_id");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> PROJECT_NAME =
            registerString("project_name");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> PROJECT_STAGE =
            registerString("project_stage");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> PROJECT_DIM =
            registerString("project_dim");

    // ── 整数 / 长整数 / 双精度 / 布尔 ────────────────────────────────────────
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ANIM_DURATION =
            registerInt("anim_duration");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> X_OFFSET =
            registerInt("x_offset");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> Y_OFFSET =
            registerInt("y_offset");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> Z_OFFSET =
            registerInt("z_offset");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SB_ENTRY_INDEX =
            registerInt("sb_entry_index");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> COMPLETION_TIME =
            registerLong("completion_time");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Double>> TP_X =
            registerDouble("tp_x");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Double>> TP_Y =
            registerDouble("tp_y");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Double>> TP_Z =
            registerDouble("tp_z");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LOCKED =
            registerBoolean("locked");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> CLEAR_FIRST =
            registerBoolean("clear_first");

    // ── 坐标 / 枚举 ─────────────────────────────────────────────────────────
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> LOCKED_POS =
            registerBlockPos("locked_pos");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> POINT1 =
            registerBlockPos("point1");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> POINT2 =
            registerBlockPos("point2");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> CAPTURE_MIN =
            registerBlockPos("capture_min");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> CAPTURE_MAX =
            registerBlockPos("capture_max");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> ENG_START =
            registerBlockPos("eng_start");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> ENG_END =
            registerBlockPos("eng_end");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> RANGE_P1 =
            registerBlockPos("range_p1");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> RANGE_P2 =
            registerBlockPos("range_p2");

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Rotation>> ROTATION =
            COMPONENTS.registerComponentType("rotation",
                    b -> b.persistent(Rotation.CODEC)
                          .networkSynchronized(NeoForgeStreamCodecs.<RegistryFriendlyByteBuf, Rotation>enumCodec(Rotation.class)));

    /**
     * 与 1.20.1 物品 NBT、蓝图文件里的 saveMode 同一个口径（ordinal）。
     * 越界值回退 SAFE（同 1.20.1 getSaveMode）：{@code CaptureMode.fromOrdinal} 不防越界，
     * 而越界值可经 /give、损坏存档或客户端构造的创造栏包进到解码器，抛出去就是整栈解析失败。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CaptureMode>> SAVE_MODE =
            COMPONENTS.registerComponentType("save_mode",
                    b -> b.persistent(Codec.INT.xmap(SpatialBlueprintComponents::captureModeOf, CaptureMode::ordinal))
                          .networkSynchronized(ByteBufCodecs.VAR_INT.map(SpatialBlueprintComponents::captureModeOf, CaptureMode::ordinal)));

    private static CaptureMode captureModeOf(int ordinal) {
        CaptureMode[] modes = CaptureMode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : CaptureMode.SAFE;
    }

    // ── 注册辅助 ────────────────────────────────────────────────────────────

    private static DeferredHolder<DataComponentType<?>, DataComponentType<String>> registerString(String id) {
        return COMPONENTS.registerComponentType(id,
                b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    }

    private static DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> registerInt(String id) {
        return COMPONENTS.registerComponentType(id,
                b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    }

    private static DeferredHolder<DataComponentType<?>, DataComponentType<Long>> registerLong(String id) {
        return COMPONENTS.registerComponentType(id,
                b -> b.persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));
    }

    private static DeferredHolder<DataComponentType<?>, DataComponentType<Double>> registerDouble(String id) {
        return COMPONENTS.registerComponentType(id,
                b -> b.persistent(Codec.DOUBLE).networkSynchronized(ByteBufCodecs.DOUBLE));
    }

    private static DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> registerBoolean(String id) {
        return COMPONENTS.registerComponentType(id,
                b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    }

    private static DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> registerBlockPos(String id) {
        return COMPONENTS.registerComponentType(id,
                b -> b.persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC));
    }

    public static void register(IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }
}
