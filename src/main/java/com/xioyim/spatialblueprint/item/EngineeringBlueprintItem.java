package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 工程蓝图：由创造模式玩家从蓝图库导入内容，供生存玩家使用。
 *
 * <p>NBT 结构与 {@link ProjectionBlueprintItem} 相同：
 * <ul>
 *   <li>{@code blueprintName}  — 蓝图显示名称</li>
 *   <li>{@code blueprintId}    — {@link com.xioyim.spatialblueprint.util.BlueprintStorage} 文件 UUID</li>
 *   <li>{@code blueprintData}  — 内联蓝图数据（多人客户端回退）</li>
 *   <li>{@code depositedMaterials} — 已存入材料（blockId → count）</li>
 * </ul>
 * </p>
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

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openEngineeringScreen(Player player, ItemStack stack) {
        // 生存模式：无 blueprintId（空白工程蓝图）才拒绝打开界面
        // 有 blueprintId 但数据未缓存时仍允许打开（玩家可点击"下载数据"获取）
        if (!player.isCreative() && getBlueprintDataClient(stack) == null && getBlueprintId(stack) == null) {
            NotifySound.clientError();
            com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(
                    Component.translatable("spatial_blueprint.message.no_data"));
            return;
        }
        // 强制捕获(FORCE)的蓝图：仅 OP 创造模式可用
        if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard.intercept(
                player, stack, getBlueprintDataClient(stack))) return;
        // 存档归属校验：不属于本档的工程蓝图直接销毁，OP 也不例外
        if (com.xioyim.spatialblueprint.client.ForceBlueprintGuard.interceptForeignWorld(
                player, stack, getBlueprintDataClient(stack))) return;
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.xioyim.spatialblueprint.gui.EngineeringBlueprintScreen(player, stack));
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
    public void appendHoverText(ItemStack stack, @Nullable Level level,
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

    // ── 工程项目绑定 NBT ──────────────────────────────────────────────────────

    /** Returns true if this item has an engineering project binding. */
    public static boolean hasBoundProject(ItemStack stack) {
        if (!stack.hasTag()) return false;
        String id = stack.getTag().getString("engProjectId");
        return !id.isEmpty();
    }

    public static String getBoundProjectId(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getString("engProjectId") : "";
    }

    public static String getBoundProjectName(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getString("engProjectName") : "";
    }

    public static String getBoundDimension(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getString("engDimension") : "";
    }

    public static BlockPos getBoundStartPos(ItemStack stack) {
        if (!stack.hasTag()) return BlockPos.ZERO;
        net.minecraft.nbt.CompoundTag t = stack.getTag();
        return new BlockPos(t.getInt("engStartX"), t.getInt("engStartY"), t.getInt("engStartZ"));
    }

    public static BlockPos getBoundEndPos(ItemStack stack) {
        if (!stack.hasTag()) return BlockPos.ZERO;
        net.minecraft.nbt.CompoundTag t = stack.getTag();
        return new BlockPos(t.getInt("engEndX"), t.getInt("engEndY"), t.getInt("engEndZ"));
    }

    public static void setBoundProject(ItemStack stack, String projectId, String projectName,
                                       String dimension, BlockPos startPos, BlockPos endPos) {
        net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
        tag.putString("engProjectId",   projectId);
        tag.putString("engProjectName", projectName);
        tag.putString("engDimension",   dimension);
        tag.putInt("engStartX", startPos.getX());
        tag.putInt("engStartY", startPos.getY());
        tag.putInt("engStartZ", startPos.getZ());
        tag.putInt("engEndX",   endPos.getX());
        tag.putInt("engEndY",   endPos.getY());
        tag.putInt("engEndZ",   endPos.getZ());
    }

    /** Overload used by C2S_SaveEngineeringProjectMessage (name not yet in item before save). */
    public static void setBoundProject(ItemStack stack, String projectId, String dimension,
                                       BlockPos startPos, BlockPos endPos) {
        setBoundProject(stack, projectId, "", dimension, startPos, endPos);
    }

    public static void clearBoundProject(ItemStack stack) {
        if (!stack.hasTag()) return;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        tag.remove("engProjectId");
        tag.remove("engProjectName");
        tag.remove("engDimension");
        tag.remove("engStartX"); tag.remove("engStartY"); tag.remove("engStartZ");
        tag.remove("engEndX");   tag.remove("engEndY");   tag.remove("engEndZ");
    }

    /** 子工程所属任务集；普通独立工程蓝图没有该字段。 */
    @Nullable
    public static String getTaskSetId(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains("sbTaskSetId")) return null;
        String taskSetId = stack.getTag().getString("sbTaskSetId");
        return taskSetId.isEmpty() ? null : taskSetId;
    }

    public static void setTaskSetId(ItemStack stack, String taskSetId) {
        stack.getOrCreateTag().putString("sbTaskSetId", taskSetId);
    }

    /** 子工程在项目中的条目编号；普通独立工程蓝图返回 0。 */
    public static int getEntryIndex(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt("sbEntryIndex") : 0;
    }

    public static void setEntryIndex(ItemStack stack, int entryIndex) {
        stack.getOrCreateTag().putInt("sbEntryIndex", entryIndex);
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

    // ── NBT 辅助（共用方法继承自 AbstractBlueprintItem）──────────────────────
    // getBlueprintName / setBlueprintName / getBlueprintId / setBlueprintId /
    // getBlueprintData / getMaterialList / getDepositedMaterials / getDepositedCount /
    // setDepositedCount / clearDepositedMaterials / hasSufficientMaterials
}
