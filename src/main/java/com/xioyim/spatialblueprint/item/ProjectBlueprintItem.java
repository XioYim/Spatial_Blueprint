package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.gui.ProjectBlueprintScreen;
import com.xioyim.spatialblueprint.gui.ProjectRangeScreen;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 项目蓝图：纯显示 / 总览道具，不放置任何方块。
 *
 * <p>NBT：
 * <ul>
 *   <li>{@code projectId} — 项目 UUID 字符串，空表示未绑定（草稿空白状态）</li>
 *   <li>{@code taskSetId} — 激活后的任务集 UUID 字符串</li>
 *   <li>{@code projectStage} — 项目阶段名（服务端在取得 / 保存范围 / 推状态时同步；
 *       副手直开门槛用它判「布局中 / 规划中」）</li>
 *   <li>{@code projectDim} — 项目所在维度（创建时固定；同步时机与 stage 相同）。规划手柄只能在这个
 *       维度打开，编辑态白框也只在这个维度画；空 = 旧物品还没同步过，改看状态缓存（e37）</li>
 * </ul>
 * </p>
 *
 * <p>右键：主手 = 项目页；副手 = 跳过项目页直接进范围页（「副手直开」流程，仅未激活 + OP +
 * 已划定范围（stage 布局中 / 规划中），关闭 / 取消直接回游戏；没划定范围时弹横幅不进入）。
 * 两条路都先过「项目已删除」与「不在项目维度」两道横幅拦截。</p>
 */
public class ProjectBlueprintItem extends Item {

    public ProjectBlueprintItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    /** true = 公用项目（全服共享一个 taskSetId）；false = 私人项目（每人一份）。 */
    public boolean isPublicProject() { return false; }

    // ── 右键：主手打开项目界面；副手直开范围界面 ─────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 副手：布局中 / 规划中的规划手柄跳过项目页，直接进范围页；其余情况不接管右键
        if (hand == InteractionHand.OFF_HAND) {
            if (level.isClientSide && !isActivated(stack) && player.hasPermissions(2)) {
                // 项目已被删除 → 横幅拦住（先于 stage 门槛，旧物品 stage 可能是过期的「布局中」）
                if (isProjectMissing(stack)) {
                    notifyProjectMissing();
                    return InteractionResultHolder.pass(stack);
                }
                // 不在项目维度 → 横幅拦住（项目维度创建时固定，e37）
                if (isOutsideProjectDimension(stack, level)) {
                    notifyWrongDimension(stack);
                    return InteractionResultHolder.pass(stack);
                }
                // 没划定主范围（准备中 / 旧物品没同步 stage）不能进快捷规划模式：弹横幅，不静默
                if (!canEnterQuickPlan(stack)) {
                    if (getProjectId(stack) != null) notifyQuickPlanNoRange();
                    return InteractionResultHolder.pass(stack);
                }
                openRangeScreenDirect(player, stack);
                return InteractionResultHolder.success(stack);
            }
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            // 规划模式 UI：仅 OP 可用；已激活的（构建中 / 已完成）属于建造模式 UI，
            // 那套还没做 —— 这里直接不开。服务端门禁不受影响（各 C2S 自己校验）。
            if (!isActivated(stack) && player.hasPermissions(2)) {
                // 项目已被删除（状态包 found=false）→ 横幅拦住，不开界面（迟到拦截见 ProjectBlueprintScreen）
                if (isProjectMissing(stack)) {
                    notifyProjectMissing();
                } else if (isOutsideProjectDimension(stack, level)) {
                    notifyWrongDimension(stack);
                } else {
                    openProjectScreen(player, stack);
                }
            }
        }
        return InteractionResultHolder.success(stack);
    }

    /**
     * 项目是否已被删除：这枚蓝图的 projectId 与状态缓存对得上、且服务端回 found=false。
     * 缓存里是别的项目 / 还没收到过状态时返回 false（无从判断，交给界面打开后补判）。
     */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static boolean isProjectMissing(ItemStack stack) {
        String pid = getProjectId(stack);
        if (pid == null) return false;
        com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage st =
                com.xioyim.spatialblueprint.client.ClientProjectStatusCache.get();
        return st != null && !st.found && pid.equals(st.projectId);
    }

    /** 项目已被删除的拦截横幅（右键没有宿主界面，走独立横幅）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void notifyProjectMissing() {
        com.xioyim.spatialblueprint.util.NotifySound.clientError();
        com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(Component.translatable(
                "spatial_blueprint.message.project.missing_blocked"));
    }

    /**
     * 这枚蓝图的项目维度（客户端视角）：物品 NBT 的 {@code projectDim} 优先；旧物品还没同步过时
     * 拿状态缓存兜底（同一项目、found、带维度）；两处都没有返回 null（无从判断，交给界面的迟到守卫）。
     */
    @Nullable
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static String knownProjectDimension(ItemStack stack) {
        String dim = getDimension(stack);
        if (!dim.isEmpty()) return dim;
        String pid = getProjectId(stack);
        if (pid == null) return null;
        com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage st =
                com.xioyim.spatialblueprint.client.ClientProjectStatusCache.get();
        if (st != null && st.found && pid.equals(st.projectId) && !st.dimension.isEmpty()) return st.dimension;
        return null;
    }

    /**
     * 玩家不在这枚蓝图的项目维度：右键拦截与编辑态白框（{@code renderProjectBlueprint}）共用这一判定。
     * 维度未知（旧物品 + 没缓存）按「不拦」处理 —— 界面打开后状态一到，迟到守卫会补拦。
     */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static boolean isOutsideProjectDimension(ItemStack stack, Level level) {
        if (level == null) return false;
        String dim = knownProjectDimension(stack);
        return dim != null && !dim.equals(ProjectData.dimensionOf(level));
    }

    /** 不在项目维度的拦截横幅（右键没有宿主界面，走独立横幅）；文案带项目所在维度的名字。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void notifyWrongDimension(ItemStack stack) {
        com.xioyim.spatialblueprint.util.NotifySound.clientError();
        com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(wrongDimensionMessage(knownProjectDimension(stack)));
    }

    /**
     * 「该项目固定在 X 维度，不能在其他维度打开」的横幅文案；右键拦截与两个界面的迟到守卫共用。
     * 维度名走 {@link ProjectData#dimensionName}（原版三个维度有中文，其余原始 id）。
     */
    public static Component wrongDimensionMessage(@Nullable String dimensionId) {
        String id = dimensionId == null || dimensionId.isEmpty() ? ProjectData.DEFAULT_DIMENSION : dimensionId;
        return Component.translatable("spatial_blueprint.message.project.wrong_dimension",
                ProjectData.dimensionName(id));
    }

    /** 副手直开门槛：项目处于布局中 / 规划中（stage 由服务端随状态写进 NBT；旧数据没有就不生效）。 */
    private static boolean isRangeEditableStage(ItemStack stack) {
        String stage = getStage(stack);
        return ProjectData.Stage.LAYOUT.name().equals(stage)
                || ProjectData.Stage.PLANNING.name().equals(stage);
    }

    /**
     * 快捷规划模式能不能进：stage 布局中 / 规划中即可 —— 服务端只在首次保存范围时把
     * 「准备中」推进到「布局中」，所以「没划定主范围」= 准备中；
     * 旧物品没同步过 stage（空串）时拿状态包兜底：本项目有有效作者盒（已划定范围）才放行。
     */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static boolean canEnterQuickPlan(ItemStack stack) {
        if (isRangeEditableStage(stack)) return true;
        String pid = getProjectId(stack);
        if (pid == null) return false;
        com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage st =
                com.xioyim.spatialblueprint.client.ClientProjectStatusCache.get();
        return st != null && st.found && pid.equals(st.projectId)
                && st.authorSize.getX() > 0 && st.authorSize.getY() > 0 && st.authorSize.getZ() > 0;
    }

    /** 未划定项目范围的拦截横幅（世界中右键没有宿主界面，走独立横幅）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void notifyQuickPlanNoRange() {
        com.xioyim.spatialblueprint.util.NotifySound.clientError();
        com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(Component.translatable(
                "spatial_blueprint.message.project.range.quick_no_range"));
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openProjectScreen(Player player, ItemStack stack) {
        Minecraft.getInstance().setScreen(new ProjectBlueprintScreen(player, stack));
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openRangeScreenDirect(Player player, ItemStack stack) {
        Minecraft.getInstance().setScreen(new ProjectRangeScreen(player, getProjectName(stack)));
    }

    // ── 主手 / 副手取物（副手直开流程：服务端各校验统一用它）──────────────────

    /** 主手优先、其次副手的项目蓝图；都没有返回 EMPTY。 */
    public static ItemStack findHeld(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof ProjectBlueprintItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof ProjectBlueprintItem) return off;
        return ItemStack.EMPTY;
    }

    /** {@link #findHeld} 对应的那只手；都没有返回 null。 */
    @Nullable
    public static InteractionHand findHeldHand(Player player) {
        if (player.getMainHandItem().getItem() instanceof ProjectBlueprintItem) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().getItem() instanceof ProjectBlueprintItem) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        String id = getProjectId(stack);
        if (id == null) {
            tooltip.add(Component.translatable("spatial_blueprint.item.project_blueprint.tooltip.empty")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("spatial_blueprint.item.project_blueprint.tooltip.id", id)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable(isActivated(stack)
                        ? "spatial_blueprint.item.project_blueprint.tooltip.activated"
                        : "spatial_blueprint.item.project_blueprint.tooltip.inactive")
                .withStyle(isActivated(stack) ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    // ── NBT 访问器 ────────────────────────────────────────────────────────────

    @Nullable
    public static String getProjectId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("projectId")) return null;
        String id = tag.getString("projectId");
        return id.isEmpty() ? null : id;
    }

    public static void setProjectId(ItemStack stack, UUID projectId) {
        stack.getOrCreateTag().putString("projectId", projectId.toString());
    }

    /** 任务集 ID —— 激活时生成。为空表示这枚蓝图<b>尚未激活</b>。 */
    @Nullable
    public static String getTaskSetId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("taskSetId")) return null;
        String id = tag.getString("taskSetId");
        return id.isEmpty() ? null : id;
    }

    public static void setTaskSetId(ItemStack stack, UUID taskSetId) {
        stack.getOrCreateTag().putString("taskSetId", taskSetId.toString());
    }

    /** 已激活 = 有 taskSetId。 */
    public static boolean isActivated(ItemStack stack) {
        return getTaskSetId(stack) != null;
    }

    // ── 「设定范围」编辑中的临时两点（保存 / 取消后清掉；不是项目的正式范围）──────
    // 与空白蓝图的选区点同一套做法：存物品 NBT、经服务端写、渲染器直接从主手物品读。

    private static final String TAG_RANGE_P1 = "rangeP1";
    private static final String TAG_RANGE_P2 = "rangeP2";

    @Nullable
    public static net.minecraft.core.BlockPos getRangePoint(ItemStack stack, int index) {
        CompoundTag tag = stack.getTag();
        String key = index == 1 ? TAG_RANGE_P1 : TAG_RANGE_P2;
        if (tag == null || !tag.contains(key)) return null;
        return net.minecraft.nbt.NbtUtils.readBlockPos(tag.getCompound(key));
    }

    public static void setRangePoint(ItemStack stack, int index, net.minecraft.core.BlockPos pos) {
        stack.getOrCreateTag().put(index == 1 ? TAG_RANGE_P1 : TAG_RANGE_P2,
                net.minecraft.nbt.NbtUtils.writeBlockPos(pos));
    }

    public static boolean hasRangePoints(ItemStack stack) {
        return getRangePoint(stack, 1) != null && getRangePoint(stack, 2) != null;
    }

    public static void clearRangePoints(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        tag.remove(TAG_RANGE_P1);
        tag.remove(TAG_RANGE_P2);
    }

    /** 道具名称：有项目名时显示项目名，否则显示默认名。 */
    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("projectName")) {
            String pname = tag.getString("projectName");
            if (!pname.isEmpty()) return Component.translatable(getDescriptionId())
                    .append(" - ")
                    .append(pname);
        }
        return super.getName(stack);
    }

    public static void setProjectName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString("projectName", name);
    }

    /** 物品 NBT 里的项目名（拿物品时还没收到服务端状态，界面标题的兜底用它）。 */
    public static String getProjectName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString("projectName");
    }

    /** 项目阶段名（{@link ProjectData.Stage} 的名字）；空串 = 还没同步过（旧数据）。 */
    public static String getStage(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString("projectStage");
    }

    /** 服务端写阶段：取得物品 / 保存范围 / 推状态时同步，副手直开门槛与界面判断用。 */
    public static void setStage(ItemStack stack, String stage) {
        stack.getOrCreateTag().putString("projectStage", stage == null ? "" : stage);
    }

    /** 项目所在维度（ResourceLocation 字符串）；空串 = 旧物品还没同步过。 */
    public static String getDimension(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString("projectDim");
    }

    /** 服务端写维度：与 {@link #setStage} 同一批同步点（取得物品 / 保存范围 / 推状态）。 */
    public static void setDimension(ItemStack stack, String dimension) {
        stack.getOrCreateTag().putString("projectDim", dimension == null ? "" : dimension);
    }
}
