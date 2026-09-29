package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 项目界面「开启预览 / 关闭预览」的客户端状态：按 projectId 记开关、范围框与开启时的维度。
 *
 * <p>开启后<b>脱离手持</b>显示（渲染器每帧遍历 {@link #all()}）；范围随
 * {@link S2C_ProjectStatusMessage} 到达自动刷新（保存范围后服务端会推一份）。
 * 自动关掉的三种情况：走远 / 换维度（渲染器每帧判）、<b>背包里没了那枚项目蓝图</b>（本类每 5 秒查一次）。
 * 换服整份清空（T4）。这与「线框全服可见」（广播给所有人）是两回事，那个另做。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientProjectPreview {

    private ClientProjectPreview() {}

    /**
     * 范围框：min 角 + 尺寸（均为方块坐标）+ 开启时玩家所在维度（ResourceLocation 字符串）
     * + 世界基准点 anchor（已激活 = 该任务集实例的那一格，未激活 = 作者 anchor；null = 项目还没保存过布局）。
     */
    public record Range(@Nullable BlockPos origin, @Nullable BlockPos size, @Nullable String dimension,
                        @Nullable BlockPos anchor) {
        /** 旧四段式调用保留：没有 anchor。 */
        public Range(@Nullable BlockPos origin, @Nullable BlockPos size, @Nullable String dimension) {
            this(origin, size, dimension, null);
        }

        /** 范围齐全且三轴都 &gt; 0 才画框。 */
        public boolean hasBounds() {
            return origin != null && size != null
                    && size.getX() > 0 && size.getY() > 0 && size.getZ() > 0;
        }
    }

    private static final Map<String, Range> enabled = new HashMap<>();

    public static boolean isEnabled(String projectId) {
        return projectId != null && enabled.containsKey(projectId);
    }

    /** 开启；没范围（还没设定）也记进去（留位 + 记维度），等状态到了再补。 */
    public static void enable(String projectId, @Nullable Range range, String dimension) {
        if (projectId == null || projectId.isEmpty()) return;
        enabled.put(projectId, new Range(
                range == null ? null : range.origin(),
                range == null ? null : range.size(),
                dimension,
                range == null ? null : range.anchor()));
    }

    public static void disable(String projectId) {
        if (projectId == null) return;
        enabled.remove(projectId);
        // 总预览关掉时，该项目的子工程预览线一起关（按钮手动关 / 走远 / 换维度都经过这里；
        // 背包核查那条路走 retainProjects，登出走 clear）
        ClientSubProjectPreview.disableProject(projectId);
    }

    /** 开着且有范围才返回；否则 null。 */
    @Nullable
    public static Range get(String projectId) {
        return projectId == null ? null : enabled.get(projectId);
    }

    /** 渲染器每帧遍历用：一份不可变的 (projectId → Range) 视图。 */
    public static Map<String, Range> all() {
        return Collections.unmodifiableMap(new HashMap<>(enabled));
    }

    /** 状态到达：预览开着的项目顺手把范围换成最新的（维度沿用开启时记下的）。 */
    public static void onStatus(S2C_ProjectStatusMessage msg) {
        if (msg == null || !msg.found) return;
        Range old = enabled.get(msg.projectId);
        if (old == null) return;
        enabled.put(msg.projectId, new Range(
                msg.hasRange ? msg.origin : null,
                msg.hasRange ? msg.size : null,
                old.dimension(),
                msg.hasAnchor ? msg.worldAnchor : null));
    }

    // ── 背包核查：每 5 秒一次，项目蓝图不在背包里了就关掉它的预览 ──────────────

    /** 核查周期（tick）：100 = 5 秒。预览脱手常显，靠这个把「蓝图丢了 / 存进箱子了」的预览收掉。 */
    private static final int INVENTORY_CHECK_INTERVAL = 100;
    private static int inventoryCheckTicks = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (enabled.isEmpty()) { inventoryCheckTicks = 0; return; }
        if (++inventoryCheckTicks < INVENTORY_CHECK_INTERVAL) return;
        inventoryCheckTicks = 0;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        // 背包全部槽位（含快捷栏 / 副手 / 盔甲）+ 鼠标上拖着的那格：整理背包时别误关
        Set<String> held = new HashSet<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) collectProjectId(inv.getItem(i), held);
        if (player.containerMenu != null) collectProjectId(player.containerMenu.getCarried(), held);
        enabled.keySet().removeIf(pid -> !held.contains(pid));
        // 子工程预览线跟着项目蓝图走：同一份 held 集合，整项目移除
        ClientSubProjectPreview.retainProjects(held);
    }

    private static void collectProjectId(ItemStack stack, Set<String> out) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ProjectBlueprintItem)) return;
        String pid = ProjectBlueprintItem.getProjectId(stack);
        if (pid != null) out.add(pid);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled.clear();
        inventoryCheckTicks = 0;
    }
}
