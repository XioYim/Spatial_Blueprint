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
import net.minecraft.world.phys.Vec3;

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
        if (!player.isCreative() && getBlueprintData(stack) == null && getBlueprintId(stack) == null) {
            NotifySound.clientError();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof com.xioyim.spatialblueprint.gui.ErrorToastSupport ts) {
                ts.setErrorToast(Component.translatable("spatial_blueprint.message.no_data"));
            } else {
                player.displayClientMessage(Component.translatable("spatial_blueprint.message.no_data"), false);
            }
            return;
        }
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.xioyim.spatialblueprint.gui.EngineeringBlueprintScreen(player, stack));
    }

    // ── 物品名称 ──────────────────────────────────────────────────────────────

    @Override
    public Component getName(ItemStack stack) {
        String name = getBlueprintName(stack);
        if (!name.isEmpty()) return Component.translatable(getDescriptionId())
                .append(" - ")
                .append(name);
        return super.getName(stack);
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        MaterialList ml = getMaterialList(stack);
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

    public static int getBoundMaxDistance(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("engMaxDistance")
                ? stack.getTag().getInt("engMaxDistance") : 100;
    }

    public static void setBoundProject(ItemStack stack, String projectId, String projectName,
                                       String dimension, BlockPos startPos, BlockPos endPos, int maxDistance) {
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
        tag.putInt("engMaxDistance", maxDistance);
    }

    /** Overload used by C2S_SaveEngineeringProjectMessage (name not yet in item before save). */
    public static void setBoundProject(ItemStack stack, String projectId, String dimension,
                                       BlockPos startPos, BlockPos endPos, int maxDistance) {
        setBoundProject(stack, projectId, "", dimension, startPos, endPos, maxDistance);
    }

    public static void clearBoundProject(ItemStack stack) {
        if (!stack.hasTag()) return;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        tag.remove("engProjectId");
        tag.remove("engProjectName");
        tag.remove("engDimension");
        tag.remove("engStartX"); tag.remove("engStartY"); tag.remove("engStartZ");
        tag.remove("engEndX");   tag.remove("engEndY");   tag.remove("engEndZ");
        tag.remove("engMaxDistance");
    }

    // ── 工程项目边界检查 ──────────────────────────────────────────────────────

    public enum ProjectBoundsResult { OK, WRONG_DIM, TOO_FAR }

    /**
     * Checks whether the player satisfies the engineering project's dimension and
     * distance constraints.  Returns {@link ProjectBoundsResult#OK} when either
     * no project is bound or all constraints pass.
     */
    public static ProjectBoundsResult checkProjectBounds(ItemStack stack, Player player, Level level) {
        if (!hasBoundProject(stack)) return ProjectBoundsResult.OK;

        String boundDim = getBoundDimension(stack);
        if (!boundDim.isEmpty()) {
            String currentDim = level.dimension().location().toString();
            if (!boundDim.equals(currentDim)) return ProjectBoundsResult.WRONG_DIM;
        }

        int maxDist = getBoundMaxDistance(stack);
        if (maxDist > 0) {
            BlockPos start = getBoundStartPos(stack);
            BlockPos end   = getBoundEndPos(stack);
            Vec3 pos = player.position();
            double distToStart = pos.distanceTo(Vec3.atCenterOf(start));
            double distToEnd   = pos.distanceTo(Vec3.atCenterOf(end));
            if (Math.min(distToStart, distToEnd) > maxDist) return ProjectBoundsResult.TOO_FAR;
        }

        return ProjectBoundsResult.OK;
    }

    // ── NBT 辅助（共用方法继承自 AbstractBlueprintItem）──────────────────────
    // getBlueprintName / setBlueprintName / getBlueprintId / setBlueprintId /
    // getBlueprintData / getMaterialList / getDepositedMaterials / getDepositedCount /
    // setDepositedCount / clearDepositedMaterials / hasSufficientMaterials /
    // getWithdrawCount / setWithdrawCount / incrementWithdrawCount /
    // getCustomWithdrawXp / setCustomWithdrawXp / getCustomWithdrawLimit / setCustomWithdrawLimit
}
