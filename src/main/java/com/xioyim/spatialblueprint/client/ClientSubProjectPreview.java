package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 规划模式「◎ 预览」的客户端状态：按 (projectId, entryIndex) 记开着的子工程预览线。
 *
 * <p>与 {@link ClientProjectPreview}（整个项目的范围框）分开：这里一条预览线 = 一个子工程
 * 包围盒的 12 条边、条目颜色、深度测试，可同时开多个。只改客户端，不发包、不碰服务端；
 * 换服整份清空（由 {@code ClientProjectStatusCache.onLoggingOut} 调 {@link #clear()}）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientSubProjectPreview {

    private ClientSubProjectPreview() {}

    /**
     * 一条开着的预览线：绝对包围盒两角（含端点）+ 线框颜色 + 开启时玩家所在维度 + 子工程文件 id。
     * engId 是提交重编号后认「还是同一条子工程」的依据（序号会变，文件 id 不变）。
     */
    public record Box(BlockPos min, BlockPos max, int color, @Nullable String dimension, String engId) {}

    private static final Map<String, Map<Integer, Box>> enabled = new HashMap<>();

    public static boolean isEnabled(String projectId, int entryIndex) {
        Map<Integer, Box> perProject = projectId == null ? null : enabled.get(projectId);
        return perProject != null && perProject.containsKey(entryIndex);
    }

    /** 开关选中子工程的预览线：开着 → 关掉；关着 → 按当前状态记下两角 / 颜色 / 维度。 */
    public static void toggle(String projectId, S2C_ProjectStatusMessage.EntryStatus entry,
                              @Nullable String dimension) {
        if (projectId == null || projectId.isEmpty() || entry == null) return;
        if (isEnabled(projectId, entry.index())) {
            disable(projectId, entry.index());
            SpatialBlueprintMod.LOGGER.info("[SubPreview] toggle off pid={} index={}", projectId, entry.index());
            return;
        }
        BlockPos min = entry.absMin();
        BlockPos max = entry.absMax();
        if (min == null || max == null || min.getX() > max.getX()
                || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            SpatialBlueprintMod.LOGGER.info("[SubPreview] toggle refused pid={} index={} reason=badBounds min={} max={}",
                    projectId, entry.index(), min, max);
            return;
        }
        enabled.computeIfAbsent(projectId, k -> new HashMap<>())
                .put(entry.index(), new Box(min, max, entry.color(), dimension,
                        entry.engId() == null ? "" : entry.engId()));
        SpatialBlueprintMod.LOGGER.info("[SubPreview] toggle on pid={} index={} box={}~{} dim={} color={:#x}",
                projectId, entry.index(), min, max, dimension, entry.color());
    }

    /**
     * 与渲染器同一套公式：盒中心 + 半对角线 + {@code RENDER_CANCEL_MARGIN} 之内才算「看得见」。
     * 渲染器用它决定自动关掉；界面用它做点击时的即时反馈（太远就不给开）。
     */
    public static boolean withinRenderDistance(Box box, double px, double py, double pz) {
        if (box == null || box.min() == null || box.max() == null) return false;
        int sx = box.max().getX() - box.min().getX() + 1;
        int sy = box.max().getY() - box.min().getY() + 1;
        int sz = box.max().getZ() - box.min().getZ() + 1;
        double cx = box.min().getX() + sx / 2.0;
        double cy = box.min().getY() + sy / 2.0;
        double cz = box.min().getZ() + sz / 2.0;
        double halfDiag = Math.sqrt((double) sx * sx + (double) sy * sy + (double) sz * sz) / 2.0;
        double limit = halfDiag + ConfigAccess.get(com.xioyim.spatialblueprint.BlueprintConfig.RENDER_CANCEL_MARGIN);
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return dx * dx + dy * dy + dz * dz <= limit * limit;
    }

    public static void disable(String projectId, int entryIndex) {
        if (projectId == null) return;
        Map<Integer, Box> perProject = enabled.get(projectId);
        if (perProject == null) return;
        perProject.remove(entryIndex);
        if (perProject.isEmpty()) enabled.remove(projectId);
    }

    public static void disableProject(String projectId) {
        if (projectId != null) enabled.remove(projectId);
    }

    /** 背包核查：不在集合里的项目整份移除（那枚项目蓝图不在背包里了）。 */
    public static void retainProjects(Set<String> projectIds) {
        if (projectIds == null) {
            enabled.clear();
            return;
        }
        enabled.keySet().removeIf(pid -> !projectIds.contains(pid));
    }

    /** 渲染器每帧遍历用：一份不可变快照。 */
    public static Map<String, Map<Integer, Box>> all() {
        Map<String, Map<Integer, Box>> copy = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Box>> e : enabled.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 状态到达：开着的条目按新状态刷新两角 / 颜色，并<b>按 engId 重新挂到新序号上</b> ——
     * [提交修改] 会整表重编号，按序号对会让预览线跳到别的子工程；状态里已不存在的子工程移除。
     * engId 为空的旧数据退回按序号对。状态里没有项目 id（found=false）时无从定位，
     * 交给背包核查与渲染器的维度判断兜底。
     */
    public static void onStatus(S2C_ProjectStatusMessage msg) {
        if (msg == null || !msg.found) return;
        Map<Integer, Box> perProject = enabled.get(msg.projectId);
        if (perProject == null || perProject.isEmpty()) return;

        Map<String, Box> byEngId = new HashMap<>();
        for (Box box : perProject.values()) {
            if (box.engId() != null && !box.engId().isEmpty()) byEngId.put(box.engId(), box);
        }
        Map<Integer, Box> rebuilt = new HashMap<>();
        for (S2C_ProjectStatusMessage.EntryStatus entry : msg.entries) {
            String engId = entry.engId() == null ? "" : entry.engId();
            Box old = engId.isEmpty() ? perProject.get(entry.index()) : byEngId.get(engId);
            if (old == null) continue;
            BlockPos min = entry.absMin();
            BlockPos max = entry.absMax();
            if (min == null || max == null) continue;
            rebuilt.put(entry.index(), new Box(min, max, entry.color(), old.dimension(), engId));
        }
        perProject.clear();
        perProject.putAll(rebuilt);
        if (perProject.isEmpty()) enabled.remove(msg.projectId);
    }

    public static void clear() {
        enabled.clear();
    }
}
