package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：上传文件的检测报告。
 *
 * <p>服务端解析并扫描完待上传的蓝图后推这一包，客户端据此打开
 * {@code UploadReportScreen}。此时文件已在服务端暂存
 * （{@code PendingUploadRegistry}），但<b>尚未落盘</b> —— 玩家看过报告点确认才真正保存。</p>
 *
 * <p>坐标全部是<b>蓝图内相对坐标</b>：这片结构还不在世界里，跟捕获报告的世界坐标不是一回事。</p>
 *
 * <p>原始文件名 / 扩展名 / 磁盘字节数只有客户端知道，不在这包里 —— 由
 * {@code UploadBlueprintScreen} 在打开报告页时直接传给 {@code UploadReportScreen}。</p>
 */
public class S2C_UploadReportMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_UploadReportMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_upload_report_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_UploadReportMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_UploadReportMessage::encode, S2C_UploadReportMessage::decode);

    private final String blueprintName;
    private final int    totalBlocks;
    private final boolean canForce;
    private final int    sizeX;
    private final int    sizeY;
    private final int    sizeZ;
    private final int    compressedBytes;
    private final long   nbtTotalBytes;
    private final long   nbtMaxSingleBytes;
    private final int    outOfRangeDropped;
    private final List<BlueprintSanitizer.ScanEntry> illegal;
    private final List<BlueprintSanitizer.ScanEntry> dangerous;
    private final List<BlueprintSanitizer.ScanEntry> oversized;
    private final List<BlueprintSanitizer.ScanEntry> deepNbt;
    private final List<BlueprintSanitizer.ScanEntity> dropped;
    private final List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions;
    private final List<String> freeBlocks;
    private final int illegalTotal;
    private final int dangerousTotal;
    private final int oversizedTotal;
    private final int deepNbtTotal;
    private final int droppedTotal;
    private final int conversionsTotal;
    private final int freeTotal;
    private final String category;
    private final int volumeLimit;
    private final int nbtPerEntryLimit;
    private final int nbtTotalLimit;

    public S2C_UploadReportMessage(String blueprintName, int totalBlocks, boolean canForce,
                                   int sizeX, int sizeY, int sizeZ,
                                   int compressedBytes, long nbtTotalBytes, long nbtMaxSingleBytes,
                                   int outOfRangeDropped,
                                   List<BlueprintSanitizer.ScanEntry> illegal,
                                   List<BlueprintSanitizer.ScanEntry> dangerous,
                                   List<BlueprintSanitizer.ScanEntry> oversized,
                                   List<BlueprintSanitizer.ScanEntry> deepNbt,
                                   List<BlueprintSanitizer.ScanEntity> dropped,
                                   List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions,
                                   List<String> freeBlocks,
                                   int illegalTotal, int dangerousTotal, int oversizedTotal,
                                   int deepNbtTotal, int droppedTotal,
                                   int conversionsTotal, int freeTotal,
                                   String category, int volumeLimit,
                                   int nbtPerEntryLimit, int nbtTotalLimit) {
        this.blueprintName = blueprintName == null ? "" : blueprintName;
        this.totalBlocks   = totalBlocks;
        this.canForce      = canForce;
        this.sizeX         = sizeX;
        this.sizeY         = sizeY;
        this.sizeZ         = sizeZ;
        this.compressedBytes = compressedBytes;
        this.nbtTotalBytes = nbtTotalBytes;
        this.nbtMaxSingleBytes = nbtMaxSingleBytes;
        this.outOfRangeDropped = outOfRangeDropped;
        this.illegal       = illegal;
        this.dangerous     = dangerous;
        this.oversized     = oversized;
        this.deepNbt       = deepNbt;
        this.dropped       = dropped;
        this.conversions   = conversions;
        this.freeBlocks    = freeBlocks;
        this.illegalTotal  = illegalTotal;
        this.dangerousTotal = dangerousTotal;
        this.oversizedTotal = oversizedTotal;
        this.deepNbtTotal  = deepNbtTotal;
        this.droppedTotal  = droppedTotal;
        this.conversionsTotal = conversionsTotal;
        this.freeTotal     = freeTotal;
        this.category      = category == null ? "" : category;
        this.volumeLimit   = volumeLimit;
        this.nbtPerEntryLimit = nbtPerEntryLimit;
        this.nbtTotalLimit = nbtTotalLimit;
    }

    @Override
    public CustomPacketPayload.Type<S2C_UploadReportMessage> type() {
        return TYPE;
    }

    public String getBlueprintName() { return blueprintName; }
    public int    getTotalBlocks()   { return totalBlocks; }
    public boolean canForce()        { return canForce; }
    public int    getSizeX()         { return sizeX; }
    public int    getSizeY()         { return sizeY; }
    public int    getSizeZ()         { return sizeZ; }
    public int    getCompressedBytes()     { return compressedBytes; }
    public long   getNbtTotalBytes()       { return nbtTotalBytes; }
    public long   getNbtMaxSingleBytes()   { return nbtMaxSingleBytes; }
    public int    getOutOfRangeDropped()   { return outOfRangeDropped; }
    public List<BlueprintSanitizer.ScanEntry> getIllegal()   { return illegal; }
    public List<BlueprintSanitizer.ScanEntry> getDangerous() { return dangerous; }
    public List<BlueprintSanitizer.ScanEntry> getOversized() { return oversized; }
    public List<BlueprintSanitizer.ScanEntry> getDeepNbt()   { return deepNbt; }
    public List<BlueprintSanitizer.ScanEntity> getDropped()  { return dropped; }
    public List<S2C_IllegalBlocksListMessage.ConversionEntry> getConversions() { return conversions; }
    public List<String> getFreeBlocks() { return freeBlocks; }
    public int getIllegalTotal()   { return illegalTotal; }
    public int getDangerousTotal() { return dangerousTotal; }
    public int getOversizedTotal() { return oversizedTotal; }
    public int getDeepNbtTotal()   { return deepNbtTotal; }
    public int getDroppedTotal()   { return droppedTotal; }
    public int getConversionsTotal() { return conversionsTotal; }
    public int getFreeTotal()      { return freeTotal; }
    public String getCategory()    { return category; }
    public int getVolumeLimit()    { return volumeLimit; }
    public int getNbtPerEntryLimit() { return nbtPerEntryLimit; }
    public int getNbtTotalLimit()    { return nbtTotalLimit; }

    public static void encode(S2C_UploadReportMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintName, 128);
        buf.writeVarInt(msg.totalBlocks);
        buf.writeBoolean(msg.canForce);
        buf.writeVarInt(msg.sizeX);
        buf.writeVarInt(msg.sizeY);
        buf.writeVarInt(msg.sizeZ);
        buf.writeVarInt(msg.compressedBytes);
        buf.writeVarLong(msg.nbtTotalBytes);
        buf.writeVarLong(msg.nbtMaxSingleBytes);
        buf.writeVarInt(msg.outOfRangeDropped);
        writeScanEntries(buf, msg.illegal);
        writeScanEntries(buf, msg.dangerous);
        writeScanEntries(buf, msg.oversized);
        writeScanEntries(buf, msg.deepNbt);
        writeScanEntities(buf, msg.dropped);
        writeConversions(buf, msg.conversions);
        writeStrings(buf, msg.freeBlocks);
        buf.writeVarInt(msg.illegalTotal);
        buf.writeVarInt(msg.dangerousTotal);
        buf.writeVarInt(msg.oversizedTotal);
        buf.writeVarInt(msg.deepNbtTotal);
        buf.writeVarInt(msg.droppedTotal);
        buf.writeVarInt(msg.conversionsTotal);
        buf.writeVarInt(msg.freeTotal);
        buf.writeUtf(msg.category, 32);
        buf.writeVarInt(msg.volumeLimit);
        buf.writeVarInt(msg.nbtPerEntryLimit);
        buf.writeVarInt(msg.nbtTotalLimit);
    }

    public static S2C_UploadReportMessage decode(FriendlyByteBuf buf) {
        String name  = buf.readUtf(128);
        int    total = buf.readVarInt();
        boolean force = buf.readBoolean();
        int    sx = buf.readVarInt();
        int    sy = buf.readVarInt();
        int    sz = buf.readVarInt();
        int    compressed = buf.readVarInt();
        long   nbtTotal = buf.readVarLong();
        long   nbtMax   = buf.readVarLong();
        int    outOfRange = buf.readVarInt();
        // 具名读取：这七个调用里有四个字面完全相同、返回类型也相同，
        // 写在参数列表里只能靠位置区分 illegal / dangerous / oversized / deepNbt。
        // Java 保证参数从左到右求值，所以那样写也是对的 —— 但重排一次编译器不会报错，
        // 报告分类会静默错乱。读取顺序即线序，不得改动。
        List<BlueprintSanitizer.ScanEntry>  illegal    = readScanEntries(buf);
        List<BlueprintSanitizer.ScanEntry>  dangerous  = readScanEntries(buf);
        List<BlueprintSanitizer.ScanEntry>  oversized  = readScanEntries(buf);
        List<BlueprintSanitizer.ScanEntry>  deepNbt    = readScanEntries(buf);
        List<BlueprintSanitizer.ScanEntity> dropped    = readScanEntities(buf);
        List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions = readConversions(buf);
        List<String> freeBlocks = readStrings(buf);
        int illegalTotal   = buf.readVarInt();
        int dangerousTotal = buf.readVarInt();
        int oversizedTotal = buf.readVarInt();
        int deepNbtTotal   = buf.readVarInt();
        int droppedTotal   = buf.readVarInt();
        int conversionsTotal = buf.readVarInt();
        int freeTotal      = buf.readVarInt();
        String category    = buf.readUtf(32);
        int volumeLimit    = buf.readVarInt();
        int nbtPerEntryLim = buf.readVarInt();
        int nbtTotalLim    = buf.readVarInt();
        return new S2C_UploadReportMessage(name, total, force, sx, sy, sz,
                compressed, nbtTotal, nbtMax, outOfRange,
                illegal, dangerous, oversized, deepNbt, dropped, conversions, freeBlocks,
                illegalTotal, dangerousTotal, oversizedTotal, deepNbtTotal, droppedTotal,
                conversionsTotal, freeTotal, category, volumeLimit,
                nbtPerEntryLim, nbtTotalLim);
    }

    private static void writeScanEntries(FriendlyByteBuf buf, List<BlueprintSanitizer.ScanEntry> list) {
        buf.writeVarInt(list.size());
        for (BlueprintSanitizer.ScanEntry e : list) {
            buf.writeVarInt(e.x());
            buf.writeVarInt(e.y());
            buf.writeVarInt(e.z());
            buf.writeUtf(e.id(), 128);
        }
    }

    // A3 纵深防御：发包方是可信服务端，扫描类列表服务端本就截断（MAX_SCAN_ENTRIES 量级），
    // 异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount，与分片/整包消息同一口径）。
    private static final int MAX_SCAN_LIST = 4096;

    private static int capped(FriendlyByteBuf buf, String what) {
        return S2C_OperationResult.checkedCount(buf, MAX_SCAN_LIST, what);
    }

    private static List<BlueprintSanitizer.ScanEntry> readScanEntries(FriendlyByteBuf buf) {
        int n = capped(buf, "scanEntries");
        List<BlueprintSanitizer.ScanEntry> list = new ArrayList<>(Math.max(8, n));
        for (int i = 0; i < n; i++) {
            list.add(new BlueprintSanitizer.ScanEntry(
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(128)));
        }
        return list;
    }

    private static void writeScanEntities(FriendlyByteBuf buf, List<BlueprintSanitizer.ScanEntity> list) {
        buf.writeVarInt(list.size());
        for (BlueprintSanitizer.ScanEntity e : list) {
            buf.writeVarInt(e.x());
            buf.writeVarInt(e.y());
            buf.writeVarInt(e.z());
            buf.writeUtf(e.kind());
            buf.writeUtf(e.entityId());
            buf.writeUtf(e.itemId());
            buf.writeBoolean(e.hasEquipment());
        }
    }

    private static List<BlueprintSanitizer.ScanEntity> readScanEntities(FriendlyByteBuf buf) {
        int n = capped(buf, "scanEntities");
        List<BlueprintSanitizer.ScanEntity> list = new ArrayList<>(Math.max(8, n));
        for (int i = 0; i < n; i++) {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            String kind = buf.readUtf();
            String entityId = buf.readUtf();
            String itemId = buf.readUtf();
            boolean hasEquipment = buf.readBoolean();
            list.add(new BlueprintSanitizer.ScanEntity(x, y, z, kind, entityId, itemId, hasEquipment));
        }
        return list;
    }

    private static void writeConversions(FriendlyByteBuf buf,
                                         List<S2C_IllegalBlocksListMessage.ConversionEntry> list) {
        buf.writeVarInt(list.size());
        for (S2C_IllegalBlocksListMessage.ConversionEntry e : list) {
            buf.writeUtf(e.sourceBlockId());
            buf.writeVarInt(e.capturedCount());
            buf.writeVarInt(e.threshold());
            buf.writeVarInt(e.targets().size());
            for (S2C_IllegalBlocksListMessage.TargetItem t : e.targets()) {
                buf.writeUtf(t.itemId());
                buf.writeVarInt(t.count());
            }
        }
    }

    private static List<S2C_IllegalBlocksListMessage.ConversionEntry> readConversions(FriendlyByteBuf buf) {
        int n = capped(buf, "conversions");
        List<S2C_IllegalBlocksListMessage.ConversionEntry> list = new ArrayList<>(Math.max(8, n));
        for (int i = 0; i < n; i++) {
            String source = buf.readUtf();
            int captured = buf.readVarInt();
            int threshold = buf.readVarInt();
            int tSize = capped(buf, "targets");
            List<S2C_IllegalBlocksListMessage.TargetItem> targets = new ArrayList<>(tSize);
            for (int j = 0; j < tSize; j++) {
                targets.add(new S2C_IllegalBlocksListMessage.TargetItem(buf.readUtf(), buf.readVarInt()));
            }
            list.add(new S2C_IllegalBlocksListMessage.ConversionEntry(source, captured, threshold, targets));
        }
        return list;
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<String> readStrings(FriendlyByteBuf buf) {
        int n = capped(buf, "strings");
        List<String> list = new ArrayList<>(Math.max(8, n));
        for (int i = 0; i < n; i++) list.add(buf.readUtf());
        return list;
    }

    public static void handle(S2C_UploadReportMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端上传报告界面（原 handleClient：loading 态换真实报告 / 常规路径
        // 以当前上传页为 parent 打开 UploadReportScreen）
    }
}
