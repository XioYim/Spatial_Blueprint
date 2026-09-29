package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 工程蓝图：由创造模式玩家从蓝图库导入内容，供生存玩家使用。
 *
 * <p>数据存法与投影蓝图相同（{@code blueprint_id} 引用 + 文件），
 * 另有工程绑定字段（{@code eng_project_id} 等组件）。</p>
 */
public class EngineeringBlueprintItem extends AbstractBlueprintItem {

    public EngineeringBlueprintItem(Properties properties) {
        super(properties);
    }

    // ── 右键打开工程蓝图界面 ──────────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 蓝图一律只认主手 —— 副手要留给「主手空白蓝图 + 副手项目蓝图」这类组合用法
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            openEngineeringScreen(player, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    private static void openEngineeringScreen(Player player, ItemStack stack) {
        // SB-TODO(P6): 客户端打开工程蓝图界面（无数据横幅、ForceBlueprintGuard 拦截、EngineeringBlueprintScreen）
    }

    // ── 物品名称 ──────────────────────────────────────────────────────────────

    @Override
    public Component getName(ItemStack stack) {
        String name = getBlueprintName(stack);
        if (!name.isEmpty()) return SpatialBlueprintItem.applySaveModeColor(stack,
                Component.translatable(getDescriptionId())
                .append(" - ")
                .append(name));
        return SpatialBlueprintItem.applySaveModeColor(stack, super.getName(stack));
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        MaterialList ml = getMaterialListForTooltip(stack);
        if (ml != null) {
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.engineering_blueprint.tooltip",
                    ml.blockCounts.size(), ml.totalCount()).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.engineering_blueprint.tooltip.empty")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        // 固定坐标（绑定工程后显示）
        if (hasBoundProject(stack)) {
            BlockPos sp = getBoundStartPos(stack);
            BlockPos ep = getBoundEndPos(stack);
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.engineering_blueprint.tooltip.start",
                    sp.getX(), sp.getY(), sp.getZ()).withStyle(ChatFormatting.DARK_AQUA));
            tooltip.add(Component.translatable(
                    "spatial_blueprint.item.engineering_blueprint.tooltip.end",
                    ep.getX(), ep.getY(), ep.getZ()).withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    // ── 工程项目绑定组件 ──────────────────────────────────────────────────────

    /** Returns true if this item has an engineering project binding. */
    public static boolean hasBoundProject(ItemStack stack) {
        return !getBoundProjectId(stack).isEmpty();
    }

    public static String getBoundProjectId(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.ENG_PROJECT_ID.get(), "");
    }

    public static String getBoundProjectName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.ENG_PROJECT_NAME.get(), "");
    }

    public static String getBoundDimension(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.ENG_DIMENSION.get(), "");
    }

    public static BlockPos getBoundStartPos(ItemStack stack) {
        BlockPos pos = stack.get(SpatialBlueprintComponents.ENG_START.get());
        return pos == null ? BlockPos.ZERO : pos;
    }

    public static BlockPos getBoundEndPos(ItemStack stack) {
        BlockPos pos = stack.get(SpatialBlueprintComponents.ENG_END.get());
        return pos == null ? BlockPos.ZERO : pos;
    }

    public static void setBoundProject(ItemStack stack, String projectId, String projectName,
                                       String dimension, BlockPos startPos, BlockPos endPos) {
        stack.set(SpatialBlueprintComponents.ENG_PROJECT_ID.get(), projectId);
        stack.set(SpatialBlueprintComponents.ENG_PROJECT_NAME.get(), projectName);
        stack.set(SpatialBlueprintComponents.ENG_DIMENSION.get(), dimension);
        stack.set(SpatialBlueprintComponents.ENG_START.get(), startPos.immutable());
        stack.set(SpatialBlueprintComponents.ENG_END.get(), endPos.immutable());
    }

    /** Overload used by C2S_SaveEngineeringProjectMessage (name not yet in item before save). */
    public static void setBoundProject(ItemStack stack, String projectId, String dimension,
                                       BlockPos startPos, BlockPos endPos) {
        setBoundProject(stack, projectId, "", dimension, startPos, endPos);
    }

    public static void clearBoundProject(ItemStack stack) {
        stack.remove(SpatialBlueprintComponents.ENG_PROJECT_ID.get());
        stack.remove(SpatialBlueprintComponents.ENG_PROJECT_NAME.get());
        stack.remove(SpatialBlueprintComponents.ENG_DIMENSION.get());
        stack.remove(SpatialBlueprintComponents.ENG_START.get());
        stack.remove(SpatialBlueprintComponents.ENG_END.get());
    }

    /** 子工程所属任务集；普通独立工程蓝图没有该字段。 */
    @javax.annotation.Nullable
    public static String getTaskSetId(ItemStack stack) {
        String taskSetId = stack.getOrDefault(SpatialBlueprintComponents.SB_TASK_SET_ID.get(), "");
        return taskSetId.isEmpty() ? null : taskSetId;
    }

    public static void setTaskSetId(ItemStack stack, String taskSetId) {
        stack.set(SpatialBlueprintComponents.SB_TASK_SET_ID.get(), taskSetId);
    }

    /** 子工程在项目中的条目编号；普通独立工程蓝图返回 0。 */
    public static int getEntryIndex(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.SB_ENTRY_INDEX.get(), 0);
    }

    public static void setEntryIndex(ItemStack stack, int entryIndex) {
        stack.set(SpatialBlueprintComponents.SB_ENTRY_INDEX.get(), entryIndex);
    }

    // ── 工程项目边界检查 ──────────────────────────────────────────────────────

    public enum ProjectBoundsResult { OK, WRONG_DIM }

    /**
     * Checks whether the player satisfies the engineering project's dimension constraint.
     * Returns {@link ProjectBoundsResult#OK} when either no project is bound or the
     * constraint passes. 距离不再是这里的判定项 —— 唯一的距离口径是全局
     * previewDistance（见 EngineeringBlueprintScreen 的 [开启预览] 置灰）。
     */
    public static ProjectBoundsResult checkProjectBounds(ItemStack stack, Player player, Level level) {
        if (!hasBoundProject(stack)) return ProjectBoundsResult.OK;

        String boundDim = getBoundDimension(stack);
        if (!boundDim.isEmpty()) {
            String currentDim = level.dimension().location().toString();
            if (!boundDim.equals(currentDim)) return ProjectBoundsResult.WRONG_DIM;
        }

        return ProjectBoundsResult.OK;
    }
}
