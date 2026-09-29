package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ClientProjectStatusCache;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.network.C2S_ProjectRangePointMessage;
import com.xioyim.spatialblueprint.network.C2S_SaveProjectRangeMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.util.NotifySound;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * 「设定项目蓝图范围」：项目界面点 [规划项目] 进来，版式照 {@link PointSelectionScreen}：
 *
 * <pre>
 *  ▲ ←↑→↓  ✚  ←↑→↓ ▲        ← 第一排（✚ = 规划新的子工程，原底部 [创建]；中间原 ♨ 流体过滤去掉：项目不捕方块）
 *  ▼ [起点]  [✎]  [终点] ▼    ← 第二排（✎ 坐标编辑，位置与选点界面一致）
 *  [划定项目范围] [取消] [关闭]  ← 第三排（原 [保存] 改名；[创建] 已移到第一排 ✚）
 * </pre>
 *
 * <p>两点存主手项目蓝图的临时 NBT（经 {@link C2S_ProjectRangePointMessage} 由服务端写），
 * 渲染器据此在世界里画框；[保存] 让服务端把两点落进 project.dat（副手直开的快捷规划模式里置灰：
 * 范围不能在快捷模式设定）。✚ 先过基础检测横幅，全过后直接进检测报告，报告确认后在
 * 「创建子工程蓝图(序号:x)」页填名字 / 耗时 / 备注（e27 重做）；[取消] 丢弃本次编辑并返回，
 * [关闭] 返回但保留两点。标题括号里是尺寸与体积（项目不捕方块，用体积代替方块数）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class ProjectRangeScreen extends Screen implements ErrorToastSupport, PointHost, ReportHost {

    private static final int BTN_W  = 20;
    private static final int BTN_H  = 20;
    private static final int V_GAP  = 2;
    private static final int PT_GAP = 2;

    private static int p1UpDownX(int cx) { return cx - 122; }
    private static int p2UpDownX(int cx) { return cx + 100; }
    private static int rowY(int cy)      { return cy + 51; }

    /** 底部四按钮颜色，与选点界面一致：[保存]亮绿 / [关闭]橙 / [取消]红 / [创建]默认（置灰）。 */
    private static final int SAVE_BTN_COLOR   = 0x55FF55;
    private static final int CLOSE_BTN_COLOR  = 0xFFAA00;
    private static final int CANCEL_BTN_COLOR = 0xFF5555;
    /** 中心点那行与首次保存横幅的金色，= 渲染器 {@code GHOST_COLOR_ANCHOR}（世界里那条竖线）。 */
    private static final int ANCHOR_COLOR     = 0xFFD54F;

    private final Player player;
    @Nullable private final Screen parent;
    /** 副手直开：没有项目页父屏，[关闭] / ESC / [取消] 直接回游戏（❖ 可主动进项目页）。 */
    private final boolean directOpen;
    private final String projectName;
    /** 项目已保存的范围（可空）：首次进来时自动灌进临时两点，省得重新框。 */
    @Nullable private final BlockPos rangeOrigin;
    @Nullable private final BlockPos rangeSize;
    /** 只在首次 init 时自动灌入，避免重建控件时覆盖玩家正在编辑的点。 */
    private boolean prefilled = false;
    /**
     * 项目的作者 anchor（一格方块，首次保存布局时 = 两点中点，此后永不改）；null = 还没保存过布局。
     * 从项目页进来时构造器给；副手直开时按状态（缓存快照 / 新到的包）回填，否则「中心点」那行会显示成未设定。
     */
    @Nullable private BlockPos anchor;
    /** 首次保存的「中心点此后无法修改」横幅只弹一次：弹过之后再点 [保存] 直接发。 */
    private boolean anchorNoticeShown = false;
    /** 最近处理过的状态版本：两种入口都用它过维度迟到守卫；副手直开还在到货后把作者盒灌进两点。 */
    private int statusVersion = -1;
    /** 副手直开：构造时缓存里已有的本项目状态快照（先拿去即时显示范围与中心点），随后仍会拉新的一份。 */
    @Nullable private final S2C_ProjectStatusMessage directStatusSnapshot;

    @Nullable private Component errorToast = null;
    @Nullable private Button saveButton = null;
    /** 第一排空位的 ✚（原底部 [创建]）：进子工程检测报告。 */
    @Nullable private Button createSubProjectButton = null;

    public ProjectRangeScreen(Player player, Screen parent, String projectName) {
        this(player, parent, projectName, null, null, null);
    }

    public ProjectRangeScreen(Player player, Screen parent, String projectName,
                              @Nullable BlockPos rangeOrigin, @Nullable BlockPos rangeSize) {
        this(player, parent, projectName, rangeOrigin, rangeSize, null);
    }

    /** 副手直开（{@link ProjectBlueprintItem} 副手右键）：没有项目页，关闭 / 取消直接回游戏。 */
    public ProjectRangeScreen(Player player, String projectName) {
        this(player, null, projectName, null, null, null);
    }

    public ProjectRangeScreen(Player player, @Nullable Screen parent, String projectName,
                              @Nullable BlockPos rangeOrigin, @Nullable BlockPos rangeSize,
                              @Nullable BlockPos anchor) {
        super(Component.translatable("spatial_blueprint.gui.project_range.title",
                projectName == null ? "" : projectName));
        this.player = player;
        this.parent = parent;
        this.directOpen = parent == null;
        this.projectName = projectName == null ? "" : projectName;
        this.rangeOrigin = rangeOrigin;
        this.rangeSize = rangeSize;
        this.anchor = anchor;
        if (this.directOpen) {
            // 副手直开没有项目页推状态：先扣下缓存里本项目那份做即时显示（request() 会清空缓存，
            // 否则打开的一瞬间范围与中心点都是空的），再拉一份最新的
            String pid = ProjectBlueprintItem.getProjectId(getStack());
            S2C_ProjectStatusMessage cached = ClientProjectStatusCache.get();
            this.directStatusSnapshot = cached != null && cached.found && pid != null
                    && pid.equals(cached.projectId) ? cached : null;
            ClientProjectStatusCache.request();
        } else {
            this.directStatusSnapshot = null;
        }
    }

    /** 主手优先、其次副手的项目蓝图；都没有就是 EMPTY，各处按钮据此拒绝操作。 */
    private ItemStack getStack() {
        return ProjectBlueprintItem.findHeld(player);
    }

    private boolean stillHolding() {
        if (!getStack().isEmpty()) return true;
        errorToast = Component.translatable("spatial_blueprint.message.project.range.need_item");
        NotifySound.clientError();
        return false;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width  / 2;
        int cy = this.height / 2;

        // 副手直开：先按构造时的缓存快照把范围两点与中心点显示出来（没快照就等 render 里的状态包）
        if (directOpen && directStatusSnapshot != null) applyStatus(directStatusSnapshot);

        // 首次进来且项目已有保存范围、又没有编辑中的临时两点 → 自动灌入（applyPoint 会发服务端）
        if (!prefilled && rangeOrigin != null && rangeSize != null
                && !ProjectBlueprintItem.hasRangePoints(getStack())) {
            applyPoint(1, rangeOrigin);
            applyPoint(2, rangeOrigin.offset(rangeSize.getX() - 1,
                    rangeSize.getY() - 1, rangeSize.getZ() - 1));
            prefilled = true;
        }

        int rowY  = rowY(cy);
        int p1X   = cx - 100;
        int p2X   = cx + 12;
        int p1UDX = p1UpDownX(cx);
        int p2UDX = p2UpDownX(cx);

        // ── 第一排：方向按钮 ─────────────────────────────────────────────
        addDirRow(1, p1X, rowY);
        addDirBtn("▲", "spatial_blueprint.gui.point_selection.tooltip.dir.up",
                p1UDX, rowY, () -> adjustPoint(1, "up"));
        addDirBtn("▼", "spatial_blueprint.gui.point_selection.tooltip.dir.down",
                p1UDX, rowY + BTN_H + V_GAP, () -> adjustPoint(1, "down"));

        addDirRow(2, p2X, rowY);
        addDirBtn("▲", "spatial_blueprint.gui.point_selection.tooltip.dir.up",
                p2UDX, rowY, () -> adjustPoint(2, "up"));
        addDirBtn("▼", "spatial_blueprint.gui.point_selection.tooltip.dir.down",
                p2UDX, rowY + BTN_H + V_GAP, () -> adjustPoint(2, "down"));

        // ── 第一排空位：✚ 规划新的子工程（原底部 [创建]，正下方是 ✎）──────
        int midX = p1X + BTN_W * 4 + PT_GAP * 3 + 2;
        int midW = p2X - midX - 2;
        createSubProjectButton = Button.builder(Component.literal("✚"), b -> openSubProjectCreate())
                .bounds(midX, rowY, midW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.gui.project_range.tooltip.create")))
                .build();
        createSubProjectButton.setFGColor(0xFFAA00);
        addRenderableWidget(createSubProjectButton);

        // ── 第二排：起点 / 终点 ──────────────────────────────────────────
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.set_point1"),
                        b -> setPoint(1))
                .bounds(p1X, cy + 73, BTN_W * 4 + PT_GAP * 3, 20)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.set_point1")))
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.set_point2"),
                        b -> setPoint(2))
                .bounds(p2X, cy + 73, BTN_W * 4 + PT_GAP * 3, 20)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.set_point2")))
                .build());

        // ✎ 坐标编辑：起点与终点之间，两侧各 2px（与选点界面同位置同尺寸：22×20）
        int editBtnX = p1X + BTN_W * 4 + PT_GAP * 3 + 2;
        int editBtnW = p2X - editBtnX - 2;
        addRenderableWidget(Button.builder(
                        Component.translatable("spatial_blueprint.gui.point_selection.btn.coord_edit"),
                        b -> Minecraft.getInstance().setScreen(new CoordinateEditScreen(player, this, this)))
                .bounds(editBtnX, cy + 73, editBtnW, 20)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.point_selection.tooltip.coord_edit")))
                .build());

        // ── 第三排：[划定项目范围] ❖ [取消] [关闭] —— ❖ 与 ✎/✚ 同列；左侧按钮缩短给它让位 ──
        int bottomY  = cy + 95;
        int bx       = p1UpDownX(cx);
        int gap      = 3;
        int firstW   = (midX - gap + 1) - bx;   // 右缘退到 ❖ 左边留一个间隔（右缘再扩 1px）
        int restX    = midX + midW + gap;   // 右侧起点与改版前一致
        int restW    = ((p2UpDownX(cx) + BTN_W) - restX - gap) / 2;

        saveButton = Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_range.btn.save"),
                        b -> save())
                .bounds(bx, bottomY, firstW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable(directOpen
                        ? "spatial_blueprint.gui.project_range.tooltip.save_quick_disabled"
                        : "spatial_blueprint.gui.project_range.tooltip.save")))
                .build();
        saveButton.setFGColor(SAVE_BTN_COLOR);
        addRenderableWidget(saveButton);

        // ❖ 返回项目页（图标按钮，白字）：副手直开进来的想回项目页就走它
        addRenderableWidget(Button.builder(Component.literal("❖"),
                        b -> Minecraft.getInstance().setScreen(new ProjectBlueprintScreen(player, getStack())))
                .bounds(midX, bottomY, midW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable(
                        "spatial_blueprint.gui.project_range.tooltip.back_to_project")))
                .build());

        Button cancelButton = Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_range.btn.cancel"),
                        b -> cancel())
                .bounds(restX - 1, bottomY, restW + 1, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.project_range.tooltip.cancel")))
                .build();
        cancelButton.setFGColor(CANCEL_BTN_COLOR);
        addRenderableWidget(cancelButton);

        // [关闭] 保留两点、只关界面（ESC 同义）
        Button closeButton = Button.builder(
                        Component.translatable("spatial_blueprint.gui.project_range.btn.close"),
                        b -> onClose())
                .bounds(restX + restW + gap, bottomY, restW, BTN_H)
                .tooltip(Tooltip.create(Component.translatable("spatial_blueprint.gui.project_range.tooltip.close")))
                .build();
        closeButton.setFGColor(CLOSE_BTN_COLOR);
        addRenderableWidget(closeButton);

        refreshSaveButton();
    }

    private void addDirRow(int index, int x, int y) {
        addDirBtn("←", "spatial_blueprint.gui.point_selection.tooltip.dir.west",  x,                          y, () -> adjustPoint(index, "west"));
        addDirBtn("↑", "spatial_blueprint.gui.point_selection.tooltip.dir.south", x + (BTN_W + PT_GAP),       y, () -> adjustPoint(index, "south"));
        addDirBtn("→", "spatial_blueprint.gui.point_selection.tooltip.dir.east",  x + (BTN_W + PT_GAP) * 2,   y, () -> adjustPoint(index, "east"));
        addDirBtn("↓", "spatial_blueprint.gui.point_selection.tooltip.dir.north", x + (BTN_W + PT_GAP) * 3,   y, () -> adjustPoint(index, "north"));
    }

    private void addDirBtn(String label, String tooltipKey, int x, int y, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, BTN_W, BTN_H)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build());
    }

    // ── 两点操作 ─────────────────────────────────────────────────────────

    private void setPoint(int index) {
        if (!stillHolding()) return;
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK)
                ? ((BlockHitResult) mc.hitResult).getBlockPos()
                : BlockPos.containing(player.getX(), player.getY(), player.getZ());
        applyPoint(index, pos);
    }

    private void adjustPoint(int index, String direction) {
        if (!stillHolding()) return;
        BlockPos cur = ProjectBlueprintItem.getRangePoint(getStack(), index);
        if (cur == null) {
            errorToast = Component.translatable("spatial_blueprint.message.project.range.missing_points");
            NotifySound.clientError();
            return;
        }
        BlockPos next = switch (direction) {
            case "east"  -> cur.east();
            case "west"  -> cur.west();
            case "south" -> cur.south();
            case "north" -> cur.north();
            case "up"    -> cur.above();
            case "down"  -> cur.below();
            default      -> cur;
        };
        applyPoint(index, next);
    }

    private void save() {
        if (!stillHolding()) return;
        ItemStack stack = getStack();
        if (!ProjectBlueprintItem.hasRangePoints(stack)) {
            errorToast = Component.translatable("spatial_blueprint.message.project.range.missing_points");
            NotifySound.clientError();
            return;
        }
        // 还没保存过布局：第一次点 [保存] 只弹金色横幅（首次设定范围会锁定中心点），不发；再点才真保存
        if (anchor == null && !anchorNoticeShown) {
            anchorNoticeShown = true;
            errorToast = Component.translatable("spatial_blueprint.gui.project_range.anchor_notice")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(ANCHOR_COLOR)));
            NotifySound.client();
            return;
        }
        // 服务端从物品 NBT 读两点、做全部校验；成功会推一份最新状态给项目界面
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_SaveProjectRangeMessage());
        Minecraft.getInstance().setScreen(parent);
    }

    /** ✚（原底部 [创建]）：先过基础横幅，全过后直接进检测报告；报告确认后才填名字 / 备注 / 耗时。 */
    private void openSubProjectCreate() {
        if (!stillHolding()) return;
        String pid = ProjectBlueprintItem.getProjectId(getStack());
        if (pid == null) {
            errorToast = Component.translatable(
                    "spatial_blueprint.message.project.range.need_item");
            NotifySound.clientError();
            return;
        }
        if (!checkSubProjectSelection(pid)) return;
        // 报告确认 → onReportConfirmed() 打开「创建子工程蓝图(序号:x)」新页
        Minecraft.getInstance().setScreen(new BlueprintReportScreen(this, player, 0, "subproject"));
    }

    /**
     * ✚ 点下去立即拦的本地校验（与服务端 {@code C2S_CreateSubProjectMessage} 同口径，那边仍会复判）：
     * ① 选区与项目范围完全一致 —— 默认预填就是整个项目范围，直接创建会得到一条和项目一样大的子工程；
     * ② 选区必须落在项目范围内（作者盒，或已激活时该任务集的占用盒）；
     * ③ 选区与任一已有子工程的绝对包围盒相交。
     * 状态包还没到时拉一次并提示稍后重试（防止绕过校验进报告）。
     */
    private boolean checkSubProjectSelection(String pid) {
        ItemStack stack = getStack();
        BlockPos p1 = ProjectBlueprintItem.getRangePoint(stack, 1);
        BlockPos p2 = ProjectBlueprintItem.getRangePoint(stack, 2);
        if (p1 == null || p2 == null) {
            errorToast = Component.translatable("spatial_blueprint.message.project.range.missing_points");
            NotifySound.clientError();
            return false;
        }
        S2C_ProjectStatusMessage st = ClientProjectStatusCache.get();
        if (st == null || !st.found || !pid.equals(st.projectId)) {
            ClientProjectStatusCache.request();
            errorToast = Component.translatable("spatial_blueprint.gui.project_range.loading_status");
            NotifySound.clientError();
            return false;
        }
        BlockPos selMin = new BlockPos(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ()));
        BlockPos selMax = new BlockPos(
                Math.max(p1.getX(), p2.getX()),
                Math.max(p1.getY(), p2.getY()),
                Math.max(p1.getZ(), p2.getZ()));

        // ① 与项目范围（作者盒）完全一致：不允许
        BlockPos size = st.authorSize;
        if (size.getX() > 0 && size.getY() > 0 && size.getZ() > 0 && selMin.equals(st.authorOrigin)
                && selMax.equals(st.authorOrigin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            errorToast = Component.translatable("spatial_blueprint.message.subproject.same_as_project");
            NotifySound.clientError();
            return false;
        }
        // ② 选区必须落在项目范围内：作者盒，或已激活时该任务集的占用盒
        //    （已激活 = 状态包的 origin/size 就是 claim 盒，与服务端「作者盒或任一 claim 盒」同口径）
        boolean inside = insideBox(selMin, selMax, st.authorOrigin, st.authorSize);
        if (!inside && st.activated && st.hasRange) {
            inside = insideBox(selMin, selMax, st.origin, st.size);
        }
        if (!inside) {
            errorToast = Component.translatable("spatial_blueprint.message.subproject.out_of_bounds");
            NotifySound.clientError();
            return false;
        }
        // ③ 与已有子工程重叠：不允许（两角与状态包、预览线同口径）
        for (S2C_ProjectStatusMessage.EntryStatus e : st.entries) {
            BlockPos a = e.absMin();
            BlockPos b = e.absMax();
            if (a == null || b == null) continue;
            int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
            int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
            int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
            boolean overlaps = selMin.getX() <= maxX && selMax.getX() >= minX
                    && selMin.getY() <= maxY && selMax.getY() >= minY
                    && selMin.getZ() <= maxZ && selMax.getZ() >= minZ;
            if (overlaps) {
                errorToast = Component.translatable("spatial_blueprint.message.subproject.overlap");
                NotifySound.clientError();
                return false;
            }
        }
        return true;
    }

    /** 选区（min/max 含端点）是否完全落在 origin..origin+size-1 内，三轴都判；size 无效返回 false。 */
    private static boolean insideBox(BlockPos selMin, BlockPos selMax, BlockPos origin, BlockPos size) {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return false;
        return selMin.getX() >= origin.getX() && selMax.getX() <= origin.getX() + size.getX() - 1
            && selMin.getY() >= origin.getY() && selMax.getY() <= origin.getY() + size.getY() - 1
            && selMin.getZ() >= origin.getZ() && selMax.getZ() <= origin.getZ() + size.getZ() - 1;
    }

    // ── ReportHost（检测报告页宿主）──────────────────────────────────────

    /** 报告确认：子工程走「名字 / 备注 / 耗时」新页；类别不对（防御）不处理。 */
    @Override
    public void onReportConfirmed(com.xioyim.spatialblueprint.util.CaptureMode mode, String targetCategory) {
        if (!"subproject".equals(targetCategory)) return;
        String pid = ProjectBlueprintItem.getProjectId(getStack());
        if (pid == null) {
            errorToast = Component.translatable("spatial_blueprint.message.project.range.need_item");
            NotifySound.clientError();
            Minecraft.getInstance().setScreen(this);
            return;
        }
        Minecraft.getInstance().setScreen(new SubProjectCreateScreen(this, player, pid));
    }

    /** 只丢弃本次编辑的临时两点；磁盘上已保存的范围不动。 */
    private void cancel() {
        ItemStack stack = getStack();
        if (!stack.isEmpty()) {
            ProjectBlueprintItem.clearRangePoints(stack);
            BlueprintNetwork.CHANNEL.sendToServer(C2S_ProjectRangePointMessage.clear());
        }
        Minecraft.getInstance().setScreen(directOpen ? null : parent);
    }

    private void refreshSaveButton() {
        boolean hasBoth = ProjectBlueprintItem.hasRangePoints(getStack());
        // 快捷规划模式（副手直开）不能设定 / 扩建范围：只能看与建子工程，范围要回项目页走 [规划项目]
        if (saveButton != null) saveButton.active = hasBoth && !directOpen;
        if (createSubProjectButton != null) createSubProjectButton.active = hasBoth;
    }

    /**
     * 迟到守卫（e37）：状态里是本项目、带维度、且玩家不在那个维度 → 关界面 + 横幅，返回 true。
     * 右键拦截靠物品 NBT 的 projectDim，旧物品还没同步时会放行，这里补拦；两个入口（项目页 / 副手直开）都走。
     */
    private boolean blockedByDimension(@Nullable S2C_ProjectStatusMessage st) {
        if (st == null || !st.found || st.dimension.isEmpty()) return false;
        String pid = ProjectBlueprintItem.getProjectId(getStack());
        if (pid == null || !pid.equals(st.projectId)) return false;
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null || st.dimension.equals(ProjectData.dimensionOf(level))) return false;
        NotifySound.clientError();
        ErrorToastScreen.showExclusive(ProjectBlueprintItem.wrongDimensionMessage(st.dimension));
        return true;
    }

    /**
     * 副手直开专用：状态（构造时的缓存快照，或新到的状态包）里是本项目才有用 ——
     * 记下作者 anchor 供「中心点」那行显示（金色）；物品上两点缺失时把作者盒灌进两点
     * （与从项目页进来时 init 的自动灌入等价）。物品上已有编辑中的两点时不覆盖。
     */
    private void applyStatus(S2C_ProjectStatusMessage st) {
        if (st == null) return;
        ItemStack stack = getStack();
        if (stack.isEmpty()) return;
        String pid = ProjectBlueprintItem.getProjectId(stack);
        if (pid == null || !pid.equals(st.projectId)) return;
        // 迟到守卫（e34）：界面开着时项目被删 → 关界面 + 横幅，不再按未保存布局处理
        if (!st.found) {
            NotifySound.clientError();
            ErrorToastScreen.showExclusive(Component.translatable(
                    "spatial_blueprint.message.project.missing_blocked"));
            return;
        }
        if (blockedByDimension(st)) return;
        if (st.hasAnchor) anchor = st.authorAnchor;
        if (prefilled) return;
        if (st.authorSize.getX() <= 0 || st.authorSize.getY() <= 0 || st.authorSize.getZ() <= 0) return;
        if (ProjectBlueprintItem.hasRangePoints(stack)) return;
        applyPoint(1, st.authorOrigin);
        applyPoint(2, st.authorOrigin.offset(
                st.authorSize.getX() - 1, st.authorSize.getY() - 1, st.authorSize.getZ() - 1));
        prefilled = true;
    }

    // ── PointHost（供 ✎ 坐标编辑复用）────────────────────────────────────

    @Override
    public BlockPos point1() { return ProjectBlueprintItem.getRangePoint(getStack(), 1); }

    @Override
    public BlockPos point2() { return ProjectBlueprintItem.getRangePoint(getStack(), 2); }

    @Override
    public void applyPoint(int index, BlockPos pos) {
        ItemStack stack = getStack();
        if (stack.isEmpty()) return;
        // 本地先写，界面与渲染器立刻能看到；服务端那份随 syncHand 回来覆盖
        ProjectBlueprintItem.setRangePoint(stack, index, pos);
        BlueprintNetwork.CHANNEL.sendToServer(new C2S_ProjectRangePointMessage(index, pos));
        refreshSaveButton();
    }

    @Override
    public long volumeLimit() {
        int limit = S2C_ConfigSyncMessage.maxVolumeFor("projectset");
        return limit > 0 ? limit : BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME;
    }

    @Override
    public Component saveTooltip() {
        return Component.translatable("spatial_blueprint.gui.project_range.tooltip.limit", volumeLimit());
    }

    // ── 渲染 ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (errorToast != null) {
            ErrorToast.render(graphics, this.font, this.width, this.height, errorToast);
            return;
        }
        // 状态到货：先过维度迟到守卫（两种入口都要，e37）；副手直开再按本项目状态回填中心点、
        // 把作者盒灌进两点（从项目页进来的走 init 里那套 prefill）
        int v = ClientProjectStatusCache.version();
        if (v != statusVersion) {
            statusVersion = v;
            S2C_ProjectStatusMessage st = ClientProjectStatusCache.get();
            if (blockedByDimension(st)) return;
            if (directOpen) applyStatus(st);
        }
        refreshSaveButton();
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx   = this.width  / 2;
        int cy   = this.height / 2;
        int rowY = rowY(cy);

        ItemStack stack = getStack();
        BlockPos p1 = ProjectBlueprintItem.getRangePoint(stack, 1);
        BlockPos p2 = ProjectBlueprintItem.getRangePoint(stack, 2);

        // ── 标题：设定项目蓝图范围(项目名) (X×Z×Y) (体积) ──────────────────
        final int titleY = cy - 130;
        MutableComponent titleComp = this.title.copy()
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
        if (p1 != null && p2 != null) {
            int sX = Math.abs(p2.getX() - p1.getX()) + 1;
            int sY = Math.abs(p2.getY() - p1.getY()) + 1;
            int sZ = Math.abs(p2.getZ() - p1.getZ()) + 1;
            long volume = (long) sX * sY * sZ;
            long limit = volumeLimit();
            // 与选点界面一致：尺寸黄、数值橙；超限深红加粗
            Style volStyle = Style.EMPTY.withColor(TextColor.fromRgb(volume > limit ? 0x8B0000 : 0xFFAA00));
            if (volume > limit) volStyle = volStyle.withBold(true);
            titleComp = titleComp
                    .append(Component.literal(" (" + sX + "×" + sZ + "×" + sY + ")")
                            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55))))
                    .append(Component.literal(" (" + volume + ")").withStyle(volStyle));
        } else {
            String nullStr = Component.translatable("spatial_blueprint.gui.point_selection.count.null").getString();
            titleComp = titleComp.append(Component.literal(" (" + nullStr + ")")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA))));
        }
        graphics.drawCenteredString(this.font, titleComp, cx, titleY, 0xFFFFFF);

        // ── 第二行：中心点（anchor，一格方块）────────────────────────────
        // 已有 → 金色（与世界里的竖线同色）；未保存过布局 → 当前两点的中点就是保存后会锁定的那一格
        //（与服务端同一个 ProjectData.centerOf，预告值 = 落盘值）
        Component anchorLine;
        int anchorColor;
        if (anchor != null) {
            anchorLine = Component.translatable("spatial_blueprint.gui.project_range.anchor",
                    anchor.getX(), anchor.getY(), anchor.getZ());
            anchorColor = ANCHOR_COLOR;
        } else if (p1 != null && p2 != null) {
            BlockPos pending = ProjectData.centerOf(p1, p2);
            anchorLine = Component.translatable("spatial_blueprint.gui.project_range.anchor_pending",
                    pending.getX(), pending.getY(), pending.getZ());
            anchorColor = 0xAAAAAA;
        } else {
            anchorLine = Component.translatable("spatial_blueprint.gui.project_range.anchor_none");
            anchorColor = 0xAAAAAA;
        }
        graphics.drawCenteredString(this.font, anchorLine, cx, titleY + 12, anchorColor);

        // ── 两点坐标（各自按钮组上方）──────────────────────────────────────
        int coordY = rowY - 13;
        if (p1 != null) {
            String coord = "X:" + p1.getX() + ",Y:" + p1.getY() + ",Z:" + p1.getZ();
            graphics.drawString(this.font, coord, p1UpDownX(cx), coordY, 0xFFFFFF);
        }
        if (p2 != null) {
            String coord = "X:" + p2.getX() + ",Y:" + p2.getY() + ",Z:" + p2.getZ();
            int rightEdge = p2UpDownX(cx) + BTN_W;
            graphics.drawString(this.font, coord, rightEdge - this.font.width(coord), coordY, 0xFFFFFF);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (errorToast != null) {
            if (keyCode == 256) errorToast = null;
            return true;
        }
        if (keyCode == 256) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (errorToast != null) {
            errorToast = null;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** ESC = 关闭但保留临时两点（下次进来接着调）；只有 [取消操作] 才清点。副手直开时直接回游戏。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(directOpen ? null : parent);
    }

    @Override
    public void setErrorToast(Component message) {
        this.errorToast = message;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
