package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.util.CaptureMode;
import com.xioyim.spatialblueprint.util.NbtCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Rotation;

import java.util.function.Function;

/**
 * 1.20.1 旧物品的迁移：把 {@code minecraft:custom_data} 里的旧 NBT 键搬进本 mod 组件。
 *
 * <p>1.20.1 存档 / 蓝图文件里的物品先经原版 DataFixer（{@code ItemStackComponentizationFix}）
 * 升级：本 mod 的自定义键它不认识，整体落进 {@code minecraft:custom_data}。原版在物品堆构造
 * （磁盘解码、网络解码）与 {@code applyComponents} 时会调 {@link net.minecraft.world.item.Item#verifyComponentsAfterLoad(ItemStack)}
 * （{@code PlayerHeadItem} 就是在那里改组件的先例），六个「根」物品类覆写该方法调用本类。</p>
 *
 * <p>迁移是纯函数（同输入同输出）：服务端与客户端各自解码时算出同一结果，不发包、不读文件、不读配置，
 * 不引入不同步。幂等：没有 custom_data 或没有对照表里的键就直接返回（热路径，每次构造都会调）。</p>
 *
 * <p>{@code blueprintData}（内联蓝图数据）<b>不动</b>，留在 custom_data：它是整份方块数据，
 * D1 要求物品只存引用，转存成蓝图文件要先过 DataFixer（P4，见 AbstractBlueprintItem 的桩）。</p>
 */
public final class LegacyItemMigration {

    private LegacyItemMigration() {}

    /** 会被迁移（或删除）的旧键；任何一个存在才进入迁移。 */
    private static final String[] OLD_KEYS = {
            "blueprintId", "blueprintName", "saveMode", "animDuration", "creatorName", "rotation",
            "xOffset", "yOffset", "zOffset", "alignMode", "locked", "lockedPos", "clearFirst",
            "point1", "point2", "completionTime", "sourceType",
            "captureMin", "captureMax", "tpX", "tpY", "tpZ", "tpDimension", "worldUuid", "worldName",
            "engProjectId", "engProjectName", "engDimension",
            "engStartX", "engStartY", "engStartZ", "engEndX", "engEndY", "engEndZ",
            "sbTaskSetId", "sbEntryIndex",
            "projectId", "taskSetId", "projectName", "projectStage", "projectDim",
            "rangeP1", "rangeP2",
            "forceCancelled",
    };

    /** 该 custom_data 里是否有需要迁移 / 删除的本 mod 旧键。 */
    public static boolean needsMigration(CustomData data) {
        if (data == null || data.isEmpty()) return false;
        CompoundTag tag = data.copyTag();
        for (String key : OLD_KEYS) {
            if (tag.contains(key)) return true;
        }
        return false;
    }

    /** 把旧键搬进组件；没有旧键时直接返回。类型不对的旧值跳过不迁，旧键照样删掉，不抛异常、不刷日志。 */
    public static void migrate(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (!needsMigration(data)) return;
        CompoundTag tag = data.copyTag();

        // ── 字符串 ──────────────────────────────────────────────────────────
        moveKey(stack, tag, "blueprintId", SpatialBlueprintComponents.BLUEPRINT_ID.get(), t -> t.getString("blueprintId"));
        moveKey(stack, tag, "blueprintName", SpatialBlueprintComponents.BLUEPRINT_NAME.get(), t -> t.getString("blueprintName"));
        moveKey(stack, tag, "creatorName", SpatialBlueprintComponents.CREATOR_NAME.get(), t -> t.getString("creatorName"));
        moveKey(stack, tag, "alignMode", SpatialBlueprintComponents.ALIGN_MODE.get(), t -> t.getString("alignMode"));
        moveKey(stack, tag, "sourceType", SpatialBlueprintComponents.SOURCE_TYPE.get(), t -> t.getString("sourceType"));
        moveKey(stack, tag, "tpDimension", SpatialBlueprintComponents.TP_DIMENSION.get(), t -> t.getString("tpDimension"));
        moveKey(stack, tag, "worldUuid", SpatialBlueprintComponents.WORLD_UUID.get(), t -> t.getString("worldUuid"));
        moveKey(stack, tag, "worldName", SpatialBlueprintComponents.WORLD_NAME.get(), t -> t.getString("worldName"));
        moveKey(stack, tag, "engProjectId", SpatialBlueprintComponents.ENG_PROJECT_ID.get(), t -> t.getString("engProjectId"));
        moveKey(stack, tag, "engProjectName", SpatialBlueprintComponents.ENG_PROJECT_NAME.get(), t -> t.getString("engProjectName"));
        moveKey(stack, tag, "engDimension", SpatialBlueprintComponents.ENG_DIMENSION.get(), t -> t.getString("engDimension"));
        moveKey(stack, tag, "sbTaskSetId", SpatialBlueprintComponents.SB_TASK_SET_ID.get(), t -> t.getString("sbTaskSetId"));
        moveKey(stack, tag, "projectId", SpatialBlueprintComponents.PROJECT_ID.get(), t -> t.getString("projectId"));
        moveKey(stack, tag, "taskSetId", SpatialBlueprintComponents.TASK_SET_ID.get(), t -> t.getString("taskSetId"));
        moveKey(stack, tag, "projectName", SpatialBlueprintComponents.PROJECT_NAME.get(), t -> t.getString("projectName"));
        moveKey(stack, tag, "projectStage", SpatialBlueprintComponents.PROJECT_STAGE.get(), t -> t.getString("projectStage"));
        moveKey(stack, tag, "projectDim", SpatialBlueprintComponents.PROJECT_DIM.get(), t -> t.getString("projectDim"));

        // ── 数值 / 布尔 ─────────────────────────────────────────────────────
        moveKey(stack, tag, "animDuration", SpatialBlueprintComponents.ANIM_DURATION.get(), t -> t.getInt("animDuration"));
        moveKey(stack, tag, "xOffset", SpatialBlueprintComponents.X_OFFSET.get(), t -> t.getInt("xOffset"));
        moveKey(stack, tag, "yOffset", SpatialBlueprintComponents.Y_OFFSET.get(), t -> t.getInt("yOffset"));
        moveKey(stack, tag, "zOffset", SpatialBlueprintComponents.Z_OFFSET.get(), t -> t.getInt("zOffset"));
        moveKey(stack, tag, "sbEntryIndex", SpatialBlueprintComponents.SB_ENTRY_INDEX.get(), t -> t.getInt("sbEntryIndex"));
        moveKey(stack, tag, "completionTime", SpatialBlueprintComponents.COMPLETION_TIME.get(), t -> t.getLong("completionTime"));
        moveKey(stack, tag, "tpX", SpatialBlueprintComponents.TP_X.get(), t -> t.getDouble("tpX"));
        moveKey(stack, tag, "tpY", SpatialBlueprintComponents.TP_Y.get(), t -> t.getDouble("tpY"));
        moveKey(stack, tag, "tpZ", SpatialBlueprintComponents.TP_Z.get(), t -> t.getDouble("tpZ"));
        moveKey(stack, tag, "locked", SpatialBlueprintComponents.LOCKED.get(), t -> t.getBoolean("locked"));
        moveKey(stack, tag, "clearFirst", SpatialBlueprintComponents.CLEAR_FIRST.get(), t -> t.getBoolean("clearFirst"));

        // ── 枚举 ────────────────────────────────────────────────────────────
        moveKey(stack, tag, "rotation", SpatialBlueprintComponents.ROTATION.get(), t -> {
            try {
                return Rotation.valueOf(t.getString("rotation"));
            } catch (IllegalArgumentException e) {
                return null; // 非法名字：跳过不迁，旧键照样删
            }
        });
        moveKey(stack, tag, "saveMode", SpatialBlueprintComponents.SAVE_MODE.get(), t -> {
            int ordinal = t.getInt("saveMode");
            CaptureMode[] modes = CaptureMode.values();
            return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : CaptureMode.SAFE;
        });

        // ── 坐标：小写 {x,y,z}（物品自己写的）──────────────────────────────
        moveKey(stack, tag, "lockedPos", SpatialBlueprintComponents.LOCKED_POS.get(), t -> lowerCasePos(t, "lockedPos"));
        moveKey(stack, tag, "point1", SpatialBlueprintComponents.POINT1.get(), t -> lowerCasePos(t, "point1"));
        moveKey(stack, tag, "point2", SpatialBlueprintComponents.POINT2.get(), t -> lowerCasePos(t, "point2"));

        // ── 坐标：{X,Y,Z}（NbtUtils.writeBlockPos）──────────────────────────
        moveKey(stack, tag, "captureMin", SpatialBlueprintComponents.CAPTURE_MIN.get(), t -> NbtCompat.readBlockPos(t, "captureMin"));
        moveKey(stack, tag, "captureMax", SpatialBlueprintComponents.CAPTURE_MAX.get(), t -> NbtCompat.readBlockPos(t, "captureMax"));
        moveKey(stack, tag, "rangeP1", SpatialBlueprintComponents.RANGE_P1.get(), t -> NbtCompat.readBlockPos(t, "rangeP1"));
        moveKey(stack, tag, "rangeP2", SpatialBlueprintComponents.RANGE_P2.get(), t -> NbtCompat.readBlockPos(t, "rangeP2"));

        // ── 工程起终点：三个 int 合成一个 BlockPos（缺键按 0 补，与 1.20.1 getInt 一致）──
        moveEngPos(stack, tag, "engStartX", "engStartY", "engStartZ", SpatialBlueprintComponents.ENG_START.get());
        moveEngPos(stack, tag, "engEndX", "engEndY", "engEndZ", SpatialBlueprintComponents.ENG_END.get());

        // ── 废键：全仓库只有 BuildBlueprintItem 一处 remove、没有写入方 ──────
        tag.remove("forceCancelled");

        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    private static <T> void moveKey(ItemStack stack, CompoundTag tag, String key,
                                    DataComponentType<T> type, Function<CompoundTag, T> reader) {
        if (!tag.contains(key)) return;
        T value = reader.apply(tag);
        if (value != null && !stack.has(type)) {
            stack.set(type, value);
        }
        tag.remove(key);
    }

    private static BlockPos lowerCasePos(CompoundTag tag, String key) {
        CompoundTag c = tag.getCompound(key);
        return new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z"));
    }

    /** 三个 int 都缺 → 不迁；任一存在 → 按 0 补成 BlockPos，三个旧键都删。 */
    private static void moveEngPos(ItemStack stack, CompoundTag tag,
                                   String xKey, String yKey, String zKey, DataComponentType<BlockPos> type) {
        if (!tag.contains(xKey) && !tag.contains(yKey) && !tag.contains(zKey)) return;
        if (!stack.has(type)) {
            stack.set(type, new BlockPos(tag.getInt(xKey), tag.getInt(yKey), tag.getInt(zKey)));
        }
        tag.remove(xKey);
        tag.remove(yKey);
        tag.remove(zKey);
    }
}
