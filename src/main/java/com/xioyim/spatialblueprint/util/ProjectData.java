package com.xioyim.spatialblueprint.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目蓝图元数据，对应 projects/{uuid}/project.dat。
 *
 * <p>子工程文件（{eng-uuid}.litematic）单独存放在同一目录下，
 * 本类仅记录它们的元信息（文件名、相对坐标、颜色、标签、备注）。</p>
 */
public class ProjectData {

    /** 在导入界面的哪个 tab：蓝图站（OP 开发中）/ 共用（全服一份进度）/ 自用（每人一份）。 */
    public enum Kind { STATION, PUBLIC, PRIVATE }

    /**
     * 手动阶段，决定列表名字后缀。只存这四个；「构建中 N%」「已完成」由进度算出来，
     * 不落盘（见 {@code C2S_RequestProjectListMessage}）。
     */
    public enum Stage { PREPARING, LAYOUT, PLANNING, REMOVED }

    // ── 项目元信息 ─────────────────────────────────────────────────────────────

    /** 项目名称（显示用）。 */
    public String name = "";

    /** 新建默认 STATION：只在蓝图站 tab 出现。 */
    public Kind kind = Kind.STATION;

    /** 新建默认 PREPARING（准备中）。 */
    public Stage stage = Stage.PREPARING;

    /** 短备注（列表行内显示，≤15 字）。 */
    public String noteShort = "";

    /** 详细备注（tooltip 显示，≤200 字）。 */
    public String noteLong = "";

    /** 创建者 UUID 字符串；空 = 本字段之前建的旧项目。归属判断用它，不用 ownerName（T5）。 */
    public String ownerUuid = "";

    /** 创建者当时的玩家名，仅显示用。 */
    public String ownerName = "";

    /** 创建时刻（epoch millis）；0 = 旧项目未记。 */
    public long createdAt = 0L;

    /**
     * 项目所在维度（ResourceLocation 字符串，如 {@code minecraft:overworld}）：创建时取创建者当时所在维度，
     * 从此<b>固定</b>。规划手柄（未激活项目蓝图）只能在这个维度打开 / 设范围 / 建子工程，
     * 编辑态白框也只在这个维度画（e37）。旧文件没有这个键一律按主世界（此前的项目都建在主世界）。
     */
    public String dimension = DEFAULT_DIMENSION;

    public static final String DEFAULT_DIMENSION = "minecraft:overworld";

    /** 维度键的字符串形式；两端判「玩家在不在项目维度」都用它，别各写一套。 */
    public static String dimensionOf(net.minecraft.world.level.Level level) {
        return level.dimension().location().toString();
    }

    /** 玩家（或任意 Level）是否在本项目的维度。 */
    public boolean isInDimension(net.minecraft.world.level.Level level) {
        return dimension != null && !dimension.isEmpty() && dimension.equals(dimensionOf(level));
    }

    /**
     * 维度 id → 显示名：原版三个维度走 lang（{@code spatial_blueprint.dimension.overworld / nether / end}），
     * 其余显示原始 id。全仓显示维度名只此一处（工程创建页 / 安全坐标页 / 项目维度横幅都用它）。
     */
    public static net.minecraft.network.chat.MutableComponent dimensionName(String dim) {
        String id = dim == null ? "" : dim;
        return switch (id) {
            case "minecraft:overworld"  -> net.minecraft.network.chat.Component.translatable("spatial_blueprint.dimension.overworld");
            case "minecraft:the_nether" -> net.minecraft.network.chat.Component.translatable("spatial_blueprint.dimension.nether");
            case "minecraft:the_end"    -> net.minecraft.network.chat.Component.translatable("spatial_blueprint.dimension.end");
            default -> net.minecraft.network.chat.Component.literal(id);
        };
    }

    /** 项目包围盒左下角绝对坐标（锁定后写入）。 */
    public BlockPos origin = BlockPos.ZERO;

    /** 项目包围盒尺寸（X/Y/Z）。 */
    public BlockPos size = BlockPos.ZERO;

    /**
     * 基准点（anchor，界面叫「中心点」）：第一次保存布局时 = 范围两点的中点（{@link #centerOf}），从此<b>只读</b>。
     * 子工程的 relP1/relP2 相对它；激活后的世界定位（claim.anchor）也由它换算。
     * 扩建只动 origin/size 与 claim 的盒，anchor 永不改 —— 已有子工程位置因此不变。
     * 2026-09-17 之前首次保存写的是 min 角；旧数据照读，anchor 本来就只是任意固定参考点。
     */
    public BlockPos anchor = BlockPos.ZERO;

    /**
     * 范围两点的中点（三轴各取 {@code floorDiv(a + b, 2)}，偶数边长偏向小的那格）。
     * 首次保存布局时服务端拿它定 anchor，范围界面「中心点（保存后锁定）」那行也用它预告 —— 两端必须同一公式。
     */
    public static BlockPos centerOf(BlockPos p1, BlockPos p2) {
        return new BlockPos(
                Math.floorDiv(p1.getX() + p2.getX(), 2),
                Math.floorDiv(p1.getY() + p2.getY(), 2),
                Math.floorDiv(p1.getZ() + p2.getZ(), 2));
    }

    /** 锁定时的旋转角度（锁定后不可更改）。 */
    public Rotation rotation = Rotation.NONE;

    /** 子工程完成时执行的指令模板（可空）。
     *  占位符：{player} {project_id} {entry_index} */
    public String onSubProjectComplete = "";

    /** 子工程条目列表。 */
    public final List<EntryData> entries = new ArrayList<>();

    // ── 子工程条目 ─────────────────────────────────────────────────────────────

    public static class EntryData {
        /** 编号从 1 开始，= 列表顺序（重排提交后整表重编号，不留空洞）。 */
        public int index;

        /** 子工程文件名：{eng-uuid}.litematic（不含序号，重排不改文件）。 */
        public String filename;

        /** 子工程显示名称。 */
        public String label;

        /** 子工程备注（列表行中间显示；空 = 界面显示「无备注」）。 */
        public String note = "";

        /** 子工程详细备注（名称 tooltip 显示；≤200 字；旧数据缺键回 ""）。 */
        public String noteLong = "";

        /** 相对 anchor 的子工程包围盒两顶点（已取 min/max）。 */
        public BlockPos relP1;
        public BlockPos relP2;

        /** 线框颜色（ARGB int，透明度固定 255）。按导入序号从调色板循环分配。 */
        public int color;

        /** 前置条目编号；0 表示无前置。领取时校验（本阶段只存储，不校验）。 */
        public int requiresEntry = 0;

        /**
         * 子工程方块数（创建时扫描得到，界面名称 tooltip 显示）。
         * -1 = 旧数据没有这个键（首次读状态时按子工程文件补算一次）；-2 = 补算失败，不再重试。
         */
        public int blockCount = -1;

        /**
         * 名称 tooltip 用的子工程文件元数据缓存：建造时长（tick，-1 = 未记）/ 划定者 / 划定时间（epoch millis，0 = 未记）。
         * 创建时直接写；旧数据在首次读状态时读一次子工程文件补上，成功失败都置 {@link #metaLoaded}，不再重试。
         */
        public int animDuration = -1;
        public String creatorName = "";
        public long createdAt = 0L;
        /** false = 还没从子工程文件补过上面三项（旧 project.dat 没这些键）。 */
        public boolean metaLoaded = false;

        public EntryData() {}

        public EntryData(int index, String filename, String label,
                         BlockPos relP1, BlockPos relP2, int color) {
            this.index    = index;
            this.filename = filename;
            this.label    = label;
            this.relP1    = relP1;
            this.relP2    = relP2;
            this.color    = color;
        }

        public CompoundTag serialize() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("index", index);
            tag.putString("filename", filename);
            tag.putString("label", label);
            tag.putString("note", note);
            tag.putString("noteLong", noteLong);
            tag.put("relP1", NbtCompat.writeBlockPos(relP1));
            tag.put("relP2", NbtCompat.writeBlockPos(relP2));
            tag.putInt("color", color);
            tag.putInt("requiresEntry", requiresEntry);
            tag.putInt("blockCount", blockCount);
            tag.putInt("animDuration", animDuration);
            tag.putString("creatorName", creatorName == null ? "" : creatorName);
            tag.putLong("createdAt", createdAt);
            tag.putBoolean("metaLoaded", metaLoaded);
            return tag;
        }

        public static EntryData deserialize(CompoundTag tag) {
            EntryData e = new EntryData();
            e.index    = tag.getInt("index");
            e.filename = tag.getString("filename");
            e.label    = tag.getString("label");
            e.note     = tag.getString("note");
            e.noteLong = tag.getString("noteLong");
            e.relP1    = java.util.Objects.requireNonNullElse(NbtCompat.readBlockPos(tag, "relP1"), BlockPos.ZERO);
            e.relP2    = java.util.Objects.requireNonNullElse(NbtCompat.readBlockPos(tag, "relP2"), BlockPos.ZERO);
            e.color    = tag.getInt("color");
            e.requiresEntry = tag.getInt("requiresEntry");
            e.blockCount = tag.contains("blockCount") ? tag.getInt("blockCount") : -1;
            // 旧文件没有这几个键：animDuration 回 -1、createdAt 回 0、metaLoaded 回 false → 首次读状态时补
            e.animDuration = tag.contains("animDuration") ? tag.getInt("animDuration") : -1;
            e.creatorName  = tag.getString("creatorName");
            e.createdAt    = tag.getLong("createdAt");
            e.metaLoaded   = tag.getBoolean("metaLoaded");
            return e;
        }
    }

    // ── 调色板：按导入序号循环分配线框颜色（ARGB，Alpha=255）─────────────────

    private static final int[] PALETTE = {
        0xFF4FC3F7, // 天蓝
        0xFF81C784, // 草绿
        0xFFFFB74D, // 橙
        0xFFE57373, // 红
        0xFFBA68C8, // 紫
        0xFF4DD0E1, // 青
        0xFFF06292, // 粉
        0xFFFFD54F, // 黄
        0xFFA1887F, // 棕
        0xFF9CCC65, // 黄绿
        0xFF7986CB, // 靛
        0xFF4DB6AC, // 蓝绿
        0xFFFF8A65, // 珊瑚
        0xFFDCE775, // 柠檬
        0xFF90A4AE, // 蓝灰
        0xFFCE93D8, // 淡紫
    };

    public static int paletteColor(int zeroBasedIndex) {
        return PALETTE[zeroBasedIndex % PALETTE.length];
    }

    // ── 序列化 ─────────────────────────────────────────────────────────────────

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("kind", kind.name());
        tag.putString("stage", stage.name());
        tag.putString("noteShort", noteShort);
        tag.putString("noteLong", noteLong);
        tag.putString("ownerUuid", ownerUuid);
        tag.putString("ownerName", ownerName);
        tag.putLong("createdAt", createdAt);
        tag.putString("dimension", dimension == null || dimension.isEmpty() ? DEFAULT_DIMENSION : dimension);
        tag.put("origin", NbtCompat.writeBlockPos(origin));
        tag.put("size", NbtCompat.writeBlockPos(size));
        tag.put("anchor", NbtCompat.writeBlockPos(anchor));
        tag.putString("rotation", rotation.name());
        tag.putString("onSubProjectComplete", onSubProjectComplete);

        ListTag entriesTag = new ListTag();
        for (EntryData e : entries) entriesTag.add(e.serialize());
        tag.put("entries", entriesTag);

        return tag;
    }

    public static ProjectData deserialize(CompoundTag tag) {
        ProjectData d = new ProjectData();
        // 旧文件没有备注 / 归属字段：getString 缺键返回 ""、getLong 返回 0，正好是默认值
        d.name      = tag.getString("name");
        d.kind      = parseEnum(Kind.class, tag.getString("kind"), Kind.STATION);
        d.stage     = parseEnum(Stage.class, tag.getString("stage"), Stage.PREPARING);
        d.noteShort = tag.getString("noteShort");
        d.noteLong  = tag.getString("noteLong");
        d.ownerUuid = tag.getString("ownerUuid");
        d.ownerName = tag.getString("ownerName");
        d.createdAt = tag.getLong("createdAt");
        // 旧文件没有 dimension：此前的项目都建在主世界，按主世界补
        String dim  = tag.getString("dimension");
        d.dimension = dim.isEmpty() ? DEFAULT_DIMENSION : dim;
        d.origin    = java.util.Objects.requireNonNullElse(NbtCompat.readBlockPos(tag, "origin"), BlockPos.ZERO);
        d.size      = java.util.Objects.requireNonNullElse(NbtCompat.readBlockPos(tag, "size"), BlockPos.ZERO);
        // 旧文件没有 anchor：从没扩建过，旧 rel 本来就相对 origin，等价
        d.anchor    = tag.contains("anchor")
                ? java.util.Objects.requireNonNullElse(NbtCompat.readBlockPos(tag, "anchor"), d.origin)
                : d.origin;
        d.rotation  = parseRotation(tag.getString("rotation"));
        d.onSubProjectComplete = tag.getString("onSubProjectComplete");

        ListTag entriesTag = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entriesTag.size(); i++) {
            d.entries.add(EntryData.deserialize(entriesTag.getCompound(i)));
        }
        return d;
    }

    private static Rotation parseRotation(String s) {
        try { return Rotation.valueOf(s); } catch (Exception e) { return Rotation.NONE; }
    }

    /**
     * anchor 是否有效：判据 = size 三轴都 &gt; 0（有效范围一旦保存过，anchor 必然已写入，
     * 或能从旧文件的 origin 补出）。不用「anchor != ZERO」是因为 (0,0,0) 可以恰好是合法基准点。
     */
    public boolean hasAnchor() {
        return size != null && size.getX() > 0 && size.getY() > 0 && size.getZ() > 0;
    }

    /** 缺键 / 未知值一律回默认，旧文件与将来加枚举值都不会把整份 project.dat 读挂。 */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String s, E fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Enum.valueOf(type, s); } catch (IllegalArgumentException e) { return fallback; }
    }
}
