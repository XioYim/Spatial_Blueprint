package com.xioyim.spatialblueprint.gui;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图摘要 tooltip 的统一构建入口。
 *
 * <p>蓝图列表与「次元蓝图材料空间」两处需要显示完全一致的蓝图信息，
 * 因此把行内容集中在这里，避免两边各写一份而逐渐分叉。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BlueprintTooltips {

    private BlueprintTooltips() {}

    /**
     * 构建蓝图摘要的 tooltip 行。
     *
     * @param showUuid   是否追加一行蓝图 UUID
     */
    public static List<FormattedCharSequence> build(BlueprintStorage.BlueprintSummary s,
                                                    boolean showUuid) {
        List<FormattedCharSequence> lines = new ArrayList<>();

        String displayName = s.name().isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : s.name();
        // 第一行与列表行显示一致：已放置时同样带「(已放置)」后缀
        MutableComponent titleLine = Component.literal(displayName).withStyle(ChatFormatting.WHITE);
        if ("reward".equals(s.category())) {
            titleLine.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            titleLine.append(Component.translatable("spatial_blueprint.gui.library.reward_suffix")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        if (s.placed()) {
            titleLine.append(Component.translatable("spatial_blueprint.gui.library.placed_suffix")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        }
        lines.add(titleLine.getVisualOrderText());

        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.block_count", s.blockCount())
                .withStyle(ChatFormatting.GREEN).getVisualOrderText());

        int dur = s.animDuration();
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.anim_duration",
                dur > 0 ? dur : "-").withStyle(ChatFormatting.AQUA).getVisualOrderText());

        if (s.saveMode() != null && s.uploadMode() == 0) {
            // 上传蓝图的模式在下方「上传模式」行体现，这里不再显示捕获用「保存模式」
            String modeKey = switch (s.saveMode()) {
                case SANITIZE -> "spatial_blueprint.gui.report.save_mode_sanitize";
                case FORCE    -> "spatial_blueprint.gui.report.save_mode_force";
                default       -> "spatial_blueprint.gui.report.save_mode_safe";
            };
            ChatFormatting modeColor = switch (s.saveMode()) {
                case SANITIZE -> ChatFormatting.YELLOW;
                case FORCE    -> ChatFormatting.RED;
                default       -> ChatFormatting.WHITE;
            };
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.save_mode",
                    Component.translatable(modeKey).getString())
                    .withStyle(modeColor).getVisualOrderText());
        }
        if (s.uploadMode() > 0) {
            // 上传来源：1=安全上传（黄），2=强制上传（红粗）。仅上传蓝图显示。
            boolean forced = s.uploadMode() == 2;
            ChatFormatting upColor = forced ? ChatFormatting.RED : ChatFormatting.YELLOW;
            Component upStyle = Component.translatable(forced
                    ? "spatial_blueprint.gui.library.tooltip.upload_mode_forced"
                    : "spatial_blueprint.gui.library.tooltip.upload_mode_safe");
            MutableComponent upLine = Component.translatable(
                    "spatial_blueprint.gui.library.tooltip.upload_mode",
                    upStyle.getString());
            if (forced) upLine.withStyle(ChatFormatting.BOLD);
            lines.add(upLine.withStyle(upColor).getVisualOrderText());
        }
        if (!s.creatorName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.creator", s.creatorName())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
        }
        if (!s.sourceName().isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.source", s.sourceName())
                    .withStyle(ChatFormatting.GOLD).getVisualOrderText());
        }
        if (s.createdAt() > 0) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.created_at",
                    formatDateFull(s.createdAt())).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        if (!s.worldName().isEmpty()
                && ("engineering".equals(s.category()) || "dimensional".equals(s.category()))) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.world", s.worldName())
                    .withStyle(s.worldLocked() ? ChatFormatting.RED : ChatFormatting.GRAY)
                    .getVisualOrderText());
        }
        if (s.worldLocked()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.foreign_world")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD).getVisualOrderText());
        }
        if (showUuid) {
            lines.add(Component.empty().getVisualOrderText());
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.uuid", s.id())
                    .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        }
        // 已放置的次元蓝图：空行分隔后附上放置点与维度
        if (s.placed()) {
            lines.add(Component.empty().getVisualOrderText());
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.placed_pos",
                    s.placedX(), s.placedY(), s.placedZ())
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.placed_dimension",
                    s.placedDimension().isEmpty() ? "-" : s.placedDimension())
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        return lines;
    }

    /**
     * 直接持有 {@link BlueprintData} 时的摘要 tooltip（蓝图列表同款），
     * 供放置 / 工程界面的材料清单标题悬浮复用。文件名依赖 blueprintId 而 data 不带 id，
     * 因此这里不渲染文件名字。
     */
    public static List<FormattedCharSequence> buildDataTip(BlueprintData data) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        String displayName = data.name.isEmpty()
                ? Component.translatable("spatial_blueprint.gui.library.unnamed").getString()
                : data.name;
        lines.add(Component.literal(displayName).withStyle(ChatFormatting.WHITE).getVisualOrderText());

        int totalMaterials = data.materialList != null ? data.materialList.totalCount() : 0;
        lines.add(Component.translatable("spatial_blueprint.gui.upload.meta.material", totalMaterials)
                .withStyle(ChatFormatting.GREEN).getVisualOrderText());
        lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.anim_duration",
                data.animDuration > 0 ? data.animDuration : "-")
                .withStyle(ChatFormatting.AQUA).getVisualOrderText());

        if (data.saveMode != null && data.uploadMode == 0) {
            // 同 summary：上传蓝图不显示捕获用「保存模式」
            String modeKey = switch (data.saveMode) {
                case SANITIZE -> "spatial_blueprint.gui.report.save_mode_sanitize";
                case FORCE    -> "spatial_blueprint.gui.report.save_mode_force";
                default       -> "spatial_blueprint.gui.report.save_mode_safe";
            };
            ChatFormatting modeColor = switch (data.saveMode) {
                case SANITIZE -> ChatFormatting.YELLOW;
                case FORCE    -> ChatFormatting.RED;
                default       -> ChatFormatting.WHITE;
            };
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.save_mode",
                    Component.translatable(modeKey).getString())
                    .withStyle(modeColor).getVisualOrderText());
        }
        if (data.uploadMode > 0) {
            boolean forced = data.uploadMode == 2;
            ChatFormatting upColor = forced ? ChatFormatting.RED : ChatFormatting.YELLOW;
            Component upStyle = Component.translatable(forced
                    ? "spatial_blueprint.gui.library.tooltip.upload_mode_forced"
                    : "spatial_blueprint.gui.library.tooltip.upload_mode_safe");
            MutableComponent upLine = Component.translatable(
                    "spatial_blueprint.gui.library.tooltip.upload_mode",
                    upStyle.getString());
            if (forced) upLine.withStyle(ChatFormatting.BOLD);
            lines.add(upLine.withStyle(upColor).getVisualOrderText());
        }
        if (!data.creatorName.isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.creator", data.creatorName)
                    .withStyle(ChatFormatting.YELLOW).getVisualOrderText());
        }
        if (!data.sourceName.isEmpty()) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.source", data.sourceName)
                    .withStyle(ChatFormatting.GOLD).getVisualOrderText());
        }
        if (data.createdAt > 0) {
            lines.add(Component.translatable("spatial_blueprint.gui.library.tooltip.created_at",
                    formatDateFull(data.createdAt)).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        return lines;
    }

    public static String formatDateFull(long epochMillis) {
        if (epochMillis <= 0) return "-";
        java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return dt.getYear() + "/" + dt.getMonthValue() + "/" + dt.getDayOfMonth()
                + " " + String.format("%02d:%02d:%02d", dt.getHour(), dt.getMinute(), dt.getSecond());
    }
}
