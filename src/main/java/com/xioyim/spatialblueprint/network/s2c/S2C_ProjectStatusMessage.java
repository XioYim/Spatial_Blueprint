package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** S2C：项目蓝图界面所需的项目元信息与每个子工程的实时领取状态。 */
public class S2C_ProjectStatusMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_ProjectStatusMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_project_status_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_ProjectStatusMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_ProjectStatusMessage::encode, S2C_ProjectStatusMessage::decode);

    /**
     * 一条子工程的实时状态。
     *
     * <p>2.7.9 起条目字段直接长在这个 record 上（2.7.8 的末尾平行备注表已撤）：
     * note / sizeX-Y-Z（相对包围盒尺寸）/ blockCount（-1 = 未记，界面显示 "-"）。
     * 版本门禁保证两端同版本（T3：encode/decode 对称、bump mod_version）。</p>
     */
    public record EntryStatus(int index, String label, String note,
                              int sizeX, int sizeY, int sizeZ, int blockCount,
                              boolean completed, int cooldownRemainSec,
                              int requiresEntry, boolean prereqMet,
                              /** 2.7.10 起（e18，名称 tooltip 扩行）：建造时长 tick（-1 = 未记）/ 划定者 / 划定时间（0 = 未记）。 */
                              int animDuration, String creatorName, long createdAt,
                              /** 子工程绝对包围盒两角（已激活 = 任务集参考系，否则 = 作者参考系）。 */
                              BlockPos absMin, BlockPos absMax,
                              /** 2.7.10 起（SB-008）：条目线框颜色（ARGB）/ 子工程文件 id（{engUuid}，文件名不合规时空串）。 */
                              int color, String engId,
                              /** 2.7.17 起（e27）：详细备注（名称 tooltip 显示；旧数据为空串）。 */
                              String noteLong) {}

    public final boolean found;
    public final String projectId;
    public final String projectName;
    public final String taskSetId;
    public final boolean activated;
    public final List<EntryStatus> entries;
    /** 项目是否已设定范围（size 三轴都 > 0）；供项目界面的「预览」画范围框。 */
    public final boolean hasRange;
    /** 范围 min 角与尺寸：未激活 = project.dat 的 origin/size；已激活 = 任务集的实际占用。 */
    public final BlockPos origin;
    public final BlockPos size;
    /** 作者参考系（project.dat 的 origin/size），[规划项目] 预填要用；size 无效时为 ZERO。 */
    public final BlockPos authorOrigin;
    public final BlockPos authorSize;
    /**
     * 基准点（SB-005 的 anchor，一格方块）：hasAnchor = 项目已保存过布局；
     * authorAnchor = project.dat 的 anchor（范围界面显示 / 编辑态画框用）；
     * worldAnchor = 已激活时该任务集的世界基准点（预览用），未激活时 = authorAnchor。
     */
    public final boolean hasAnchor;
    public final BlockPos authorAnchor;
    public final BlockPos worldAnchor;
    /** 序号是否可重排：该项目还没有任何任务集占用才为 true（2.7.8 追加，只许放末尾）。 */
    public final boolean orderEditable;
    /**
     * 项目所在维度（ResourceLocation 字符串；2.7.27 / e37 追加，只许放末尾）：创建时固定。
     * 界面的迟到守卫用它判「玩家不在项目维度」并关界面；物品 NBT 还没同步到的旧物品，右键拦截也拿它兜底。
     */
    public final String dimension;

    public S2C_ProjectStatusMessage(boolean found, String projectId, String projectName,
                                    String taskSetId, boolean activated,
                                    List<EntryStatus> entries) {
        this(found, projectId, projectName, taskSetId, activated, entries, false, BlockPos.ZERO, BlockPos.ZERO);
    }

    public S2C_ProjectStatusMessage(boolean found, String projectId, String projectName,
                                    String taskSetId, boolean activated,
                                    List<EntryStatus> entries,
                                    boolean hasRange, BlockPos origin, BlockPos size) {
        this(found, projectId, projectName, taskSetId, activated, entries,
                hasRange, origin, size, BlockPos.ZERO, BlockPos.ZERO);
    }

    public S2C_ProjectStatusMessage(boolean found, String projectId, String projectName,
                                    String taskSetId, boolean activated,
                                    List<EntryStatus> entries,
                                    boolean hasRange, BlockPos origin, BlockPos size,
                                    BlockPos authorOrigin, BlockPos authorSize) {
        this(found, projectId, projectName, taskSetId, activated, entries,
                hasRange, origin, size, authorOrigin, authorSize,
                false, BlockPos.ZERO, BlockPos.ZERO);
    }

    public S2C_ProjectStatusMessage(boolean found, String projectId, String projectName,
                                    String taskSetId, boolean activated,
                                    List<EntryStatus> entries,
                                    boolean hasRange, BlockPos origin, BlockPos size,
                                    BlockPos authorOrigin, BlockPos authorSize,
                                    boolean hasAnchor, BlockPos authorAnchor, BlockPos worldAnchor) {
        this(found, projectId, projectName, taskSetId, activated, entries,
                hasRange, origin, size, authorOrigin, authorSize,
                hasAnchor, authorAnchor, worldAnchor, false);
    }

    public S2C_ProjectStatusMessage(boolean found, String projectId, String projectName,
                                    String taskSetId, boolean activated,
                                    List<EntryStatus> entries,
                                    boolean hasRange, BlockPos origin, BlockPos size,
                                    BlockPos authorOrigin, BlockPos authorSize,
                                    boolean hasAnchor, BlockPos authorAnchor, BlockPos worldAnchor,
                                    boolean orderEditable) {
        this(found, projectId, projectName, taskSetId, activated, entries,
                hasRange, origin, size, authorOrigin, authorSize,
                hasAnchor, authorAnchor, worldAnchor, orderEditable, "");
    }

    public S2C_ProjectStatusMessage(boolean found, String projectId, String projectName,
                                    String taskSetId, boolean activated,
                                    List<EntryStatus> entries,
                                    boolean hasRange, BlockPos origin, BlockPos size,
                                    BlockPos authorOrigin, BlockPos authorSize,
                                    boolean hasAnchor, BlockPos authorAnchor, BlockPos worldAnchor,
                                    boolean orderEditable, String dimension) {
        this.found = found;
        this.projectId = projectId == null ? "" : projectId;
        this.projectName = projectName == null ? "" : projectName;
        this.taskSetId = taskSetId == null ? "" : taskSetId;
        this.activated = activated;
        this.entries = entries == null ? List.of() : entries;
        this.hasRange = hasRange;
        this.origin = origin == null ? BlockPos.ZERO : origin;
        this.size = size == null ? BlockPos.ZERO : size;
        this.authorOrigin = authorOrigin == null ? BlockPos.ZERO : authorOrigin;
        this.authorSize = authorSize == null ? BlockPos.ZERO : authorSize;
        this.hasAnchor = hasAnchor;
        this.authorAnchor = authorAnchor == null ? BlockPos.ZERO : authorAnchor;
        this.worldAnchor = worldAnchor == null ? BlockPos.ZERO : worldAnchor;
        this.orderEditable = orderEditable;
        this.dimension = dimension == null ? "" : dimension;
    }

    /** 2.7.24：带上 projectId —— 客户端据此判断「手上这枚蓝图的 项目已被删除」并弹横幅拦截。 */
    public static S2C_ProjectStatusMessage notFound() {
        return new S2C_ProjectStatusMessage(false, "", "", "", false, List.of());
    }

    public static S2C_ProjectStatusMessage notFound(String projectId) {
        return new S2C_ProjectStatusMessage(false, projectId, "", "", false, List.of());
    }

    @Override
    public CustomPacketPayload.Type<S2C_ProjectStatusMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_ProjectStatusMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.found);
        if (!msg.found) {
            // 2.7.24：未找到时也带 projectId（末尾追加，T3；found=true 的布局不受影响）
            buf.writeUtf(msg.projectId, 64);
            return;
        }

        buf.writeUtf(msg.projectId, 64);
        buf.writeUtf(msg.projectName, 128);
        buf.writeUtf(msg.taskSetId, 64);
        buf.writeBoolean(msg.activated);
        buf.writeVarInt(msg.entries.size());
        for (EntryStatus entry : msg.entries) {
            buf.writeVarInt(entry.index());
            buf.writeUtf(entry.label() == null ? "" : entry.label(), 128);
            // 2.7.9：条目字段扩展（备注 / 尺寸 / 方块数）—— 不做平行表，直接长在条目上；
            // 版本门禁保证两端同版本（T3），不会出现半新半旧解析
            buf.writeUtf(entry.note() == null ? "" : entry.note(), 128);
            buf.writeVarInt(Math.max(0, entry.sizeX()));
            buf.writeVarInt(Math.max(0, entry.sizeY()));
            buf.writeVarInt(Math.max(0, entry.sizeZ()));
            buf.writeInt(entry.blockCount());
            buf.writeBoolean(entry.completed());
            buf.writeVarInt(entry.cooldownRemainSec());
            buf.writeVarInt(entry.requiresEntry());
            buf.writeBoolean(entry.prereqMet());
            // 2.7.10（e18）：条目末尾追加 建造时长 / 划定者 / 划定时间 / 绝对两角
            buf.writeInt(entry.animDuration());
            buf.writeUtf(entry.creatorName() == null ? "" : entry.creatorName(), 64);
            buf.writeLong(entry.createdAt());
            buf.writeBlockPos(entry.absMin() == null ? BlockPos.ZERO : entry.absMin());
            buf.writeBlockPos(entry.absMax() == null ? BlockPos.ZERO : entry.absMax());
            // 2.7.10（SB-008）：条目末尾追加 线框颜色 / 子工程文件 id
            buf.writeInt(entry.color());
            buf.writeUtf(entry.engId() == null ? "" : entry.engId(), 64);
            // 2.7.17（e27）：条目末尾追加 详细备注
            buf.writeUtf(entry.noteLong() == null ? "" : entry.noteLong(), 200);
        }
        // 2.7.x 追加：范围（新字段只许放末尾，见 T3）
        buf.writeBoolean(msg.hasRange);
        buf.writeBlockPos(msg.origin);
        buf.writeBlockPos(msg.size);
        // 2.7.x 追加：作者参考系（同样只许放末尾）
        buf.writeBlockPos(msg.authorOrigin);
        buf.writeBlockPos(msg.authorSize);
        // 2.7.5 追加：基准点（只许放末尾）
        buf.writeBoolean(msg.hasAnchor);
        buf.writeBlockPos(msg.authorAnchor);
        buf.writeBlockPos(msg.worldAnchor);
        // 2.7.8 追加：序号可编辑标记（只许放末尾）
        buf.writeBoolean(msg.orderEditable);
        // 2.7.27 追加：项目所在维度（只许放末尾）
        buf.writeUtf(msg.dimension, 128);
    }

    public static S2C_ProjectStatusMessage decode(FriendlyByteBuf buf) {
        boolean found = buf.readBoolean();
        if (!found) return notFound(buf.readUtf(64));

        String projectId = buf.readUtf(64);
        String projectName = buf.readUtf(128);
        String taskSetId = buf.readUtf(64);
        boolean activated = buf.readBoolean();
        // A3 纵深防御：子工程状态数正常几十，异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int count = S2C_OperationResult.checkedCount(buf, 4096, "entryStatuses");
        List<EntryStatus> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new EntryStatus(
                    buf.readVarInt(), buf.readUtf(128), buf.readUtf(128),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(),
                    buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readInt(), buf.readUtf(64), buf.readLong(),
                    buf.readBlockPos(), buf.readBlockPos(),
                    buf.readInt(), buf.readUtf(64), buf.readUtf(200)));
        }
        boolean hasRange = buf.readBoolean();
        BlockPos origin = buf.readBlockPos();
        BlockPos size = buf.readBlockPos();
        BlockPos authorOrigin = buf.readBlockPos();
        BlockPos authorSize = buf.readBlockPos();
        boolean hasAnchor = buf.readBoolean();
        BlockPos authorAnchor = buf.readBlockPos();
        BlockPos worldAnchor = buf.readBlockPos();
        boolean orderEditable = buf.readBoolean();
        String dimension = buf.readUtf(128);
        return new S2C_ProjectStatusMessage(found, projectId, projectName,
                taskSetId, activated, entries, hasRange, origin, size,
                authorOrigin, authorSize, hasAnchor, authorAnchor, worldAnchor,
                orderEditable, dimension);
    }

    public static void handle(S2C_ProjectStatusMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端项目状态缓存（原 handleClient：ClientProjectStatusCache.onReceive(msg)）
    }
}
