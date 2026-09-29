package com.xioyim.spatialblueprint.network.s2c;

import java.util.List;

// SB-TODO(P3): S2C_IllegalBlocksListMessage 消息本体（StreamCodec / handler）

/**
 * 服务端 → 客户端：选区内的非法方块 / 危险 NBT 方块 / 特殊实体列表（供检测报告展示）。
 * 本任务（SBN-002 / P1b）只放嵌套 record，消息本体（编解码与 handler）留给 P3。
 */
public class S2C_IllegalBlocksListMessage {

    /** 单个方块条目：世界坐标 + 方块/实体注册 ID + 展示框内嵌物品 ID（非展示框为空串）。 */
    public record IllegalBlock(int x, int y, int z, String blockId, String itemId) {}

    /** 特殊实体条目：kind = armor_stand / mob / item_frame。 */
    public record SpecialEntity(int x, int y, int z, String kind, String entityId, String itemId,
                                boolean hasEquipment) {}

    /** 批量转换产出的单个目标物品。 */
    public record TargetItem(String itemId, int count) {}

    /** 命中的批量转换规则：选区内 {@code capturedCount} 个源方块 → 固定产出 {@code targets}。 */
    public record ConversionEntry(String sourceBlockId, int capturedCount, int threshold,
                                  List<TargetItem> targets) {}

    /** 盒型实体（Create 超级胶水）的世界坐标包围盒；{@code inside} = 整盒都在选区内。 */
    public record BoxEntity(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ, boolean inside) {}

    /**
     * 请求者<b>没有破坏权限</b>的区块（区块坐标）。服务端对选区内每个区块采样一格非空气方块
     * post {@code BlockEvent.BreakEvent}，被领地 mod 取消即记入 —— 次元蓝图储存到这种区块会被拒。
     * 按区块给是因为 FTB Chunks 的领地就是按区块划的；非区块制的领地 mod 只能拿到近似结果。
     */
    public record ProtectedChunk(int cx, int cz) {}
}
