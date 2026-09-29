package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.client.ClientProjectEntriesCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：回复 {@code C2S_RequestProjectEntriesMessage}，携带项目的条目清单
 * 与作者参考系（origin / size），供「创建子工程蓝图」页使用。
 */
public class S2C_ProjectEntriesMessage {

    public record Entry(int index, String label) {}

    public final String     projectId;
    /** false = 项目不存在（或条目读取失败），此时其余字段无效。 */
    public final boolean    found;
    public final String     projectName;
    /** 作者参考系原点（项目选区最小角）。 */
    public final BlockPos   origin;
    /** 作者参考系尺寸（X/Y/Z）。 */
    public final BlockPos   size;
    public final List<Entry> entries;

    public S2C_ProjectEntriesMessage(String projectId, boolean found, String projectName,
                                     BlockPos origin, BlockPos size, List<Entry> entries) {
        this.projectId    = projectId == null ? "" : projectId;
        this.found        = found;
        this.projectName  = projectName == null ? "" : projectName;
        this.origin       = origin;
        this.size         = size;
        this.entries      = entries == null ? List.of() : entries;
    }

    public static void encode(S2C_ProjectEntriesMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.projectId, 64);
        buf.writeBoolean(msg.found);
        if (!msg.found) return;   // found=false 短路，后面什么都不写
        buf.writeUtf(msg.projectName, 128);
        buf.writeBlockPos(msg.origin);
        buf.writeBlockPos(msg.size);
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeVarInt(e.index());
            buf.writeUtf(e.label() == null ? "" : e.label(), 128);
        }
    }

    public static S2C_ProjectEntriesMessage decode(FriendlyByteBuf buf) {
        String projectId = buf.readUtf(64);
        boolean found = buf.readBoolean();
        if (!found) return new S2C_ProjectEntriesMessage(projectId, false, "", null, null, List.of());
        String projectName = buf.readUtf(128);
        BlockPos origin = buf.readBlockPos();
        BlockPos size = buf.readBlockPos();
        // A3 纵深防御：子工程数正常几十，异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int count = S2C_OperationResult.checkedCount(buf, 4096, "entries");
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readUtf(128)));
        }
        return new S2C_ProjectEntriesMessage(projectId, true, projectName, origin, size, entries);
    }

    public static void handle(S2C_ProjectEntriesMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 只写客户端缓存（纯静态字段），不碰任何客户端专属类型，无需 @OnlyIn
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientProjectEntriesCache.onReceive(msg);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
