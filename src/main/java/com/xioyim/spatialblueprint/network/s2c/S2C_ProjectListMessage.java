package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.ProjectData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：导入项目界面某个 tab 的项目清单（对 {@code C2S_RequestProjectListMessage} 的应答）。
 *
 * <p>每条只带列表行与 tooltip 要用的字段；子工程明细、坐标都不带。
 * 「构建中 N%」「已完成」由 {@code completedCount / entryCount} 在客户端算，
 * 私人项目的进度是<b>查看者自己</b>那份任务集的。</p>
 */
public class S2C_ProjectListMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_ProjectListMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_project_list_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_ProjectListMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_ProjectListMessage::encode, S2C_ProjectListMessage::decode);

    /**
     * @param completedCount 已完成子工程数；<b>-1 = 没有进度可看</b>（蓝图站项目 /
     *                       公用项目尚未激活 / 私人项目查看者没激活过），此时后缀只看 stage
     * @param activated      公用项目是否已有全服任务集；私人 / 蓝图站恒 false
     */
    public record ProjectSummary(String projectId, String name,
                                 ProjectData.Kind kind, ProjectData.Stage stage,
                                 String noteShort, String noteLong,
                                 String ownerName, long createdAt,
                                 int entryCount, int completedCount, boolean activated) {}

    public final String tab;
    public final List<ProjectSummary> projects;

    public S2C_ProjectListMessage(String tab, List<ProjectSummary> projects) {
        this.tab = tab == null ? "" : tab;
        this.projects = projects == null ? List.of() : projects;
    }

    @Override
    public CustomPacketPayload.Type<S2C_ProjectListMessage> type() {
        return TYPE;
    }

    public static void encode(S2C_ProjectListMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.tab, 16);
        buf.writeVarInt(msg.projects.size());
        for (ProjectSummary p : msg.projects) {
            buf.writeUtf(p.projectId(), 64);
            buf.writeUtf(p.name(), 128);
            buf.writeEnum(p.kind());
            buf.writeEnum(p.stage());
            buf.writeUtf(p.noteShort(), 15);
            buf.writeUtf(p.noteLong(), 200);
            buf.writeUtf(p.ownerName(), 64);
            buf.writeLong(p.createdAt());
            buf.writeVarInt(p.entryCount());
            buf.writeInt(p.completedCount());   // 可为 -1，不走 VarInt
            buf.writeBoolean(p.activated());
        }
    }

    public static S2C_ProjectListMessage decode(FriendlyByteBuf buf) {
        String tab = buf.readUtf(16);
        // A3 纵深防御：项目文件数正常几百，异常计数整包拒收（守卫见 S2C_OperationResult.checkedCount）。
        int count = S2C_OperationResult.checkedCount(buf, 4096, "projects");
        List<ProjectSummary> projects = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            projects.add(new ProjectSummary(
                    buf.readUtf(64), buf.readUtf(128),
                    buf.readEnum(ProjectData.Kind.class), buf.readEnum(ProjectData.Stage.class),
                    buf.readUtf(15), buf.readUtf(200),
                    buf.readUtf(64), buf.readLong(),
                    buf.readVarInt(), buf.readInt(), buf.readBoolean()));
        }
        return new S2C_ProjectListMessage(tab, projects);
    }

    public static void handle(S2C_ProjectListMessage msg, IPayloadContext ctx) {
        // SB-TODO(P5): 客户端项目列表缓存（原 handleClient：ClientProjectListCache.onReceive(msg)）
    }
}
