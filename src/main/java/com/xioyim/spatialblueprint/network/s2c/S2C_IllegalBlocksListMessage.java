package com.xioyim.spatialblueprint.network.s2c;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：选区内的非法方块 / 危险 NBT 方块 / 特殊实体列表（供检测报告展示）。
 * 客户端缓存到静态字段，由 {@code BlueprintReportScreen} 读取渲染。
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

    /** 客户端缓存 — 最近一次请求返回的非法方块列表。 */
    public static volatile List<IllegalBlock> clientIllegalBlocks = List.of();
    /** 客户端缓存 — 最近一次请求返回的危险 NBT 方块列表。 */
    public static volatile List<IllegalBlock> clientDangerousBlocks = List.of();
    /** 客户端缓存 — 最近一次请求返回的特殊实体列表。 */
    public static volatile List<SpecialEntity> clientSpecialEntities = List.of();
    /** 客户端缓存 — 最近一次请求返回的命中批量转换列表。 */
    public static volatile List<ConversionEntry> clientConversions = List.of();
    /** 客户端缓存 — 最近一次请求返回的免费方块 ID 列表。 */
    public static volatile List<String> clientFreeBlocks = List.of();
    /** 客户端缓存 — 最近一次请求返回的盒型实体（胶水）包围盒列表。 */
    public static volatile List<BoxEntity> clientBoxEntities = List.of();

    /**
     * 请求者<b>没有破坏权限</b>的区块（区块坐标）。服务端对选区内每个区块采样一格非空气方块
     * post {@code BlockEvent.BreakEvent}，被领地 mod 取消即记入 —— 次元蓝图储存到这种区块会被拒。
     * 按区块给是因为 FTB Chunks 的领地就是按区块划的；非区块制的领地 mod 只能拿到近似结果。
     */
    public record ProtectedChunk(int cx, int cz) {}

    /** 客户端缓存 — 最近一次请求返回的无破坏权限区块列表。 */
    public static volatile List<ProtectedChunk> clientProtectedChunks = List.of();

    /**
     * 最近一次回包里，是否有<b>与给定选区相交且跨界</b>的胶水。
     *
     * <p>按选区过滤是因为 {@link #clientBoxEntities} 是全局缓存 —— 上一次可能是别的选区
     * 扫出来的，直接看 {@code inside} 会把旧结果当成这次的警告。选区按方块闭区间给，
     * 与扫描端一样转成 {@code [min, max+1)} 再比。</p>
     */
    public static boolean hasCrossingBoxEntityIn(net.minecraft.core.BlockPos min, net.minecraft.core.BlockPos max) {
        double selMinX = Math.min(min.getX(), max.getX()), selMaxX = Math.max(min.getX(), max.getX()) + 1.0;
        double selMinY = Math.min(min.getY(), max.getY()), selMaxY = Math.max(min.getY(), max.getY()) + 1.0;
        double selMinZ = Math.min(min.getZ(), max.getZ()), selMaxZ = Math.max(min.getZ(), max.getZ()) + 1.0;
        for (BoxEntity e : clientBoxEntities) {
            if (e.inside()) continue;
            boolean intersects = e.minX() < selMaxX && e.maxX() > selMinX
                    && e.minY() < selMaxY && e.maxY() > selMinY
                    && e.minZ() < selMaxZ && e.maxZ() > selMinZ;
            if (intersects) return true;
        }
        return false;
    }
    /** 客户端缓存 — 最近一次接收时间戳，用于区分新旧结果。 */
    public static volatile long clientReceiveTime = 0L;
    /** 客户端缓存 — 最近一次扫描选区的非空气方块总数（-1 = 没扫过 / 旧包）；子工程新页的自动耗时预填用它。 */
    public static volatile int clientTotalBlocks = -1;

    private final List<IllegalBlock> illegalBlocks;
    private final List<IllegalBlock> dangerousBlocks;
    private final List<SpecialEntity> specialEntities;
    private final List<ConversionEntry> conversions;
    private final List<String> freeBlocks;
    private final List<BoxEntity> boxEntities;
    private final List<ProtectedChunk> protectedChunks;
    /** 选区非空气方块总数（扫描时顺手统计）；-1 = 旧包 / 未提供。 */
    private final int totalBlocks;

    /** 旧签名：无盒型实体（保持既有调用点不变）。 */
    public S2C_IllegalBlocksListMessage(List<IllegalBlock> illegalBlocks, List<IllegalBlock> dangerousBlocks,
                                        List<SpecialEntity> specialEntities,
                                        List<ConversionEntry> conversions, List<String> freeBlocks) {
        this(illegalBlocks, dangerousBlocks, specialEntities, conversions, freeBlocks, List.of());
    }

    /** 六参：无领地区块（保持 TASK-009 的调用形态可用）。 */
    public S2C_IllegalBlocksListMessage(List<IllegalBlock> illegalBlocks, List<IllegalBlock> dangerousBlocks,
                                        List<SpecialEntity> specialEntities,
                                        List<ConversionEntry> conversions, List<String> freeBlocks,
                                        List<BoxEntity> boxEntities) {
        this(illegalBlocks, dangerousBlocks, specialEntities, conversions, freeBlocks, boxEntities, List.of(), -1);
    }

    public S2C_IllegalBlocksListMessage(List<IllegalBlock> illegalBlocks, List<IllegalBlock> dangerousBlocks,
                                        List<SpecialEntity> specialEntities,
                                        List<ConversionEntry> conversions, List<String> freeBlocks,
                                        List<BoxEntity> boxEntities, List<ProtectedChunk> protectedChunks) {
        this(illegalBlocks, dangerousBlocks, specialEntities, conversions, freeBlocks, boxEntities, protectedChunks, -1);
    }

    public S2C_IllegalBlocksListMessage(List<IllegalBlock> illegalBlocks, List<IllegalBlock> dangerousBlocks,
                                        List<SpecialEntity> specialEntities,
                                        List<ConversionEntry> conversions, List<String> freeBlocks,
                                        List<BoxEntity> boxEntities, List<ProtectedChunk> protectedChunks,
                                        int totalBlocks) {
        this.illegalBlocks = illegalBlocks;
        this.dangerousBlocks = dangerousBlocks;
        this.specialEntities = specialEntities;
        this.conversions = conversions;
        this.freeBlocks = freeBlocks;
        this.boxEntities = boxEntities;
        this.protectedChunks = protectedChunks;
        this.totalBlocks = totalBlocks;
    }

    public static void encode(S2C_IllegalBlocksListMessage msg, FriendlyByteBuf buf) {
        writeBlocks(buf, msg.illegalBlocks);
        writeBlocks(buf, msg.dangerousBlocks);
        writeEntities(buf, msg.specialEntities);
        writeConversions(buf, msg.conversions);
        buf.writeVarInt(msg.freeBlocks.size());
        for (String id : msg.freeBlocks) buf.writeUtf(id);
        writeBoxEntities(buf, msg.boxEntities);
        buf.writeVarInt(msg.protectedChunks.size());
        for (ProtectedChunk c : msg.protectedChunks) {
            buf.writeVarInt(c.cx());
            buf.writeVarInt(c.cz());
        }
        // 新字段一律追加在末尾（T3）
        buf.writeVarInt(msg.totalBlocks);
    }

    private static void writeBoxEntities(FriendlyByteBuf buf, List<BoxEntity> list) {
        buf.writeVarInt(list.size());
        for (BoxEntity e : list) {
            buf.writeDouble(e.minX());
            buf.writeDouble(e.minY());
            buf.writeDouble(e.minZ());
            buf.writeDouble(e.maxX());
            buf.writeDouble(e.maxY());
            buf.writeDouble(e.maxZ());
            buf.writeBoolean(e.inside());
        }
    }

    private static void writeConversions(FriendlyByteBuf buf, List<ConversionEntry> list) {
        buf.writeVarInt(list.size());
        for (ConversionEntry e : list) {
            buf.writeUtf(e.sourceBlockId());
            buf.writeVarInt(e.capturedCount());
            buf.writeVarInt(e.threshold());
            buf.writeVarInt(e.targets().size());
            for (TargetItem t : e.targets()) {
                buf.writeUtf(t.itemId());
                buf.writeVarInt(t.count());
            }
        }
    }

    private static void writeBlocks(FriendlyByteBuf buf, List<IllegalBlock> list) {
        buf.writeVarInt(list.size());
        for (IllegalBlock e : list) {
            buf.writeInt(e.x());
            buf.writeInt(e.y());
            buf.writeInt(e.z());
            buf.writeUtf(e.blockId());
            buf.writeUtf(e.itemId());
        }
    }

    private static void writeEntities(FriendlyByteBuf buf, List<SpecialEntity> list) {
        buf.writeVarInt(list.size());
        for (SpecialEntity e : list) {
            buf.writeInt(e.x());
            buf.writeInt(e.y());
            buf.writeInt(e.z());
            buf.writeUtf(e.kind());
            buf.writeUtf(e.entityId());
            buf.writeUtf(e.itemId());
            buf.writeBoolean(e.hasEquipment());
        }
    }

    public static S2C_IllegalBlocksListMessage decode(FriendlyByteBuf buf) {
        List<IllegalBlock> illegal = readBlocks(buf);
        List<IllegalBlock> dangerous = readBlocks(buf);
        List<SpecialEntity> special = readEntities(buf);
        List<ConversionEntry> conversions = readConversions(buf);
        int freeSize = capped(buf, MAX_LIST, "free");
        List<String> free = new ArrayList<>(freeSize);
        for (int i = 0; i < freeSize; i++) free.add(buf.readUtf());
        List<BoxEntity> boxEntities = readBoxEntities(buf);
        int pcSize = capped(buf, MAX_CHUNKS, "protectedChunks");
        List<ProtectedChunk> protectedChunks = new ArrayList<>(pcSize);
        for (int i = 0; i < pcSize; i++) protectedChunks.add(new ProtectedChunk(buf.readVarInt(), buf.readVarInt()));
        int totalBlocks = buf.readVarInt();
        return new S2C_IllegalBlocksListMessage(illegal, dangerous, special, conversions, free, boxEntities,
                protectedChunks, totalBlocks);
    }

    // A3 纵深防御：发包方是可信服务端，扫描类列表服务端本就截断（MAX_SCAN_ENTRIES 量级），
    // 异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount，与分片/整包消息同一口径）。
    // 区块表按极端细长选区放宽一档（1.6M 格单列 ≈ 10 万 chunk），仍远到不了 OOM。
    private static final int MAX_LIST = 4096;
    private static final int MAX_CHUNKS = 1048576;

    private static int capped(FriendlyByteBuf buf, int cap, String what) {
        return S2C_OperationResult.checkedCount(buf, cap, what);
    }

    private static List<BoxEntity> readBoxEntities(FriendlyByteBuf buf) {
        int size = capped(buf, MAX_LIST, "boxEntities");
        List<BoxEntity> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double minX = buf.readDouble();
            double minY = buf.readDouble();
            double minZ = buf.readDouble();
            double maxX = buf.readDouble();
            double maxY = buf.readDouble();
            double maxZ = buf.readDouble();
            boolean inside = buf.readBoolean();
            list.add(new BoxEntity(minX, minY, minZ, maxX, maxY, maxZ, inside));
        }
        return list;
    }

    private static List<ConversionEntry> readConversions(FriendlyByteBuf buf) {
        int size = capped(buf, MAX_LIST, "conversions");
        List<ConversionEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String source = buf.readUtf();
            int captured = buf.readVarInt();
            int threshold = buf.readVarInt();
            int tSize = capped(buf, MAX_LIST, "targets");
            List<TargetItem> targets = new ArrayList<>(tSize);
            for (int j = 0; j < tSize; j++) targets.add(new TargetItem(buf.readUtf(), buf.readVarInt()));
            list.add(new ConversionEntry(source, captured, threshold, targets));
        }
        return list;
    }

    private static List<IllegalBlock> readBlocks(FriendlyByteBuf buf) {
        int size = capped(buf, MAX_LIST, "blocks");
        List<IllegalBlock> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            String id = buf.readUtf();
            String itemId = buf.readUtf();
            list.add(new IllegalBlock(x, y, z, id, itemId));
        }
        return list;
    }

    private static List<SpecialEntity> readEntities(FriendlyByteBuf buf) {
        int size = capped(buf, MAX_LIST, "entities");
        List<SpecialEntity> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            String kind = buf.readUtf();
            String entityId = buf.readUtf();
            String itemId = buf.readUtf();
            boolean hasEquipment = buf.readBoolean();
            list.add(new SpecialEntity(x, y, z, kind, entityId, itemId, hasEquipment));
        }
        return list;
    }

    public static void handle(S2C_IllegalBlocksListMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            clientIllegalBlocks = msg.illegalBlocks;
            clientDangerousBlocks = msg.dangerousBlocks;
            clientSpecialEntities = msg.specialEntities;
            clientConversions = msg.conversions;
            clientFreeBlocks = msg.freeBlocks;
            clientBoxEntities = msg.boxEntities;
            clientProtectedChunks = msg.protectedChunks;
            clientTotalBlocks = msg.totalBlocks;
            clientReceiveTime = System.currentTimeMillis();
        });
        ctx.get().setPacketHandled(true);
    }
}
