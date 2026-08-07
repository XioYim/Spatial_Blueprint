package com.xioyim.spatialblueprint.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目蓝图元数据，对应 projects/{uuid}/project.dat。
 *
 * <p>子工程文件（{index}_{eng-uuid}.litematic）单独存放在同一目录下，
 * 本类仅记录它们的元信息（文件名、相对坐标、颜色、标签）。</p>
 */
public class ProjectData {

    public enum State { DRAFT, PUBLISHED }

    // ── 项目元信息 ─────────────────────────────────────────────────────────────

    /** 项目名称（显示用）。 */
    public String name = "";

    /** 草稿 or 已发布。 */
    public State state = State.DRAFT;

    /** 项目所在维度 ID（如 "minecraft:overworld"）。 */
    public String dimension = "minecraft:overworld";

    /** 项目包围盒左下角绝对坐标（锁定后写入）。 */
    public BlockPos origin = BlockPos.ZERO;

    /** 项目包围盒尺寸（X/Y/Z）。 */
    public BlockPos size = BlockPos.ZERO;

    /** 锁定时的旋转角度（锁定后不可更改）。 */
    public Rotation rotation = Rotation.NONE;

    /** 子工程完成时执行的指令模板（可空）。
     *  占位符：{player} {project_id} {entry_index} */
    public String onSubProjectComplete = "";

    /** 子工程条目列表。 */
    public final List<EntryData> entries = new ArrayList<>();

    // ── 子工程条目 ─────────────────────────────────────────────────────────────

    public static class EntryData {
        /** 编号从 1 开始，按导入顺序递增。 */
        public int index;

        /** 文件名格式：{index}_{eng-uuid}.litematic */
        public String filename;

        /** 子工程显示名称。 */
        public String label;

        /** 相对于 origin 的子工程包围盒两顶点（已取 min/max）。 */
        public BlockPos relP1;
        public BlockPos relP2;

        /** 线框颜色（ARGB int，透明度固定 255）。按导入序号从调色板循环分配。 */
        public int color;

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
            tag.put("relP1", NbtUtils.writeBlockPos(relP1));
            tag.put("relP2", NbtUtils.writeBlockPos(relP2));
            tag.putInt("color", color);
            return tag;
        }

        public static EntryData deserialize(CompoundTag tag) {
            EntryData e = new EntryData();
            e.index    = tag.getInt("index");
            e.filename = tag.getString("filename");
            e.label    = tag.getString("label");
            e.relP1    = NbtUtils.readBlockPos(tag.getCompound("relP1"));
            e.relP2    = NbtUtils.readBlockPos(tag.getCompound("relP2"));
            e.color    = tag.getInt("color");
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
    };

    public static int paletteColor(int zeroBasedIndex) {
        return PALETTE[zeroBasedIndex % PALETTE.length];
    }

    // ── 序列化 ─────────────────────────────────────────────────────────────────

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("state", state.name());
        tag.putString("dimension", dimension);
        tag.put("origin", NbtUtils.writeBlockPos(origin));
        tag.put("size", NbtUtils.writeBlockPos(size));
        tag.putString("rotation", rotation.name());
        tag.putString("onSubProjectComplete", onSubProjectComplete);

        ListTag entriesTag = new ListTag();
        for (EntryData e : entries) entriesTag.add(e.serialize());
        tag.put("entries", entriesTag);

        return tag;
    }

    public static ProjectData deserialize(CompoundTag tag) {
        ProjectData d = new ProjectData();
        d.name      = tag.getString("name");
        d.state     = parseState(tag.getString("state"));
        d.dimension = tag.getString("dimension");
        d.origin    = NbtUtils.readBlockPos(tag.getCompound("origin"));
        d.size      = NbtUtils.readBlockPos(tag.getCompound("size"));
        d.rotation  = parseRotation(tag.getString("rotation"));
        d.onSubProjectComplete = tag.getString("onSubProjectComplete");

        ListTag entriesTag = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entriesTag.size(); i++) {
            d.entries.add(EntryData.deserialize(entriesTag.getCompound(i)));
        }
        return d;
    }

    private static State parseState(String s) {
        try { return State.valueOf(s); } catch (Exception e) { return State.DRAFT; }
    }

    private static Rotation parseRotation(String s) {
        try { return Rotation.valueOf(s); } catch (Exception e) { return Rotation.NONE; }
    }
}
