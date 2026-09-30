package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * C2S：规划模式界面 [提交修改] —— 用整份新顺序重排子工程（仅 OP）。
 *
 * <p>载荷 = 新顺序的序号列表（当前全部条目 index 的一个排列）。服务端校验通过后：
 * ① 按顺序重排 {@code project.entries}；② 按位置整表重编号 1..N（顺便压平删除留下的空洞）；
 * ③ 按「旧序号 → 新序号」重映射各条目的 {@code requiresEntry}；④ 落盘并推最新状态。</p>
 *
 * <p>有任务集占用（{@link ProjectClaimRegistry#hasAnyClaimForProject}）时整单拒绝：
 * 进度键 {@code (taskSetId, entryIndex)} 与玩家手里已领蓝图的 {@code sbEntryIndex}
 * 都按序号存，重编号会让它们指向别的条目。</p>
 */
public class C2S_SubmitProjectOrderMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_SubmitProjectOrderMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_submit_project_order_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_SubmitProjectOrderMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_SubmitProjectOrderMessage::encode, C2S_SubmitProjectOrderMessage::decode);

    /** 防呆上限（服务端还会按 project.entries.size() 严格校验）。 */
    private static final int MAX_ORDER = 1024;

    private final List<Integer> order;

    public C2S_SubmitProjectOrderMessage(List<Integer> order) {
        this.order = order == null ? List.of() : List.copyOf(order);
    }

    @Override
    public CustomPacketPayload.Type<C2S_SubmitProjectOrderMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_SubmitProjectOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.order.size());
        for (int index : msg.order) buf.writeVarInt(index);
    }

    public static C2S_SubmitProjectOrderMessage decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ORDER) return new C2S_SubmitProjectOrderMessage(List.of());
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) order.add(buf.readVarInt());
        return new C2S_SubmitProjectOrderMessage(order);
    }

    public static void handle(C2S_SubmitProjectOrderMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_SubmitProjectOrderMessage msg, ServerPlayer player) {
        if (player == null) return;

        // 1. 仅 OP
        if (!player.hasPermissions(2)) {
            sendFail(player, "spatial_blueprint.message.subproject.need_op");
            return;
        }
        // 2. 主手必须是项目蓝图
        ItemStack main = player.getMainHandItem();
        if (!(main.getItem() instanceof ProjectBlueprintItem)) {
            sendFail(player, "spatial_blueprint.message.subproject.need_item");
            return;
        }
        // 3. 项目必须存在
        String projectIdString = ProjectBlueprintItem.getProjectId(main);
        UUID projectUuid;
        ProjectData project;
        try {
            projectUuid = UUID.fromString(projectIdString == null ? "" : projectIdString);
            project = ProjectStorage.loadProject(projectUuid);
        } catch (IllegalArgumentException e) {
            projectUuid = null;
            project = null;
        }
        if (project == null) {
            sendFail(player, "spatial_blueprint.message.subproject.project_missing");
            return;
        }
        // 4. 有任务集占用 = 进度 / 已发蓝图按序号记，重编号会错位 → 整单拒
        if (ProjectClaimRegistry.get(player.serverLevel())
                .hasAnyClaimForProject(projectUuid.toString())) {
            sendFail(player, "spatial_blueprint.message.project.order.activated");
            return;
        }
        // 5. 载荷必须是当前全部序号的一个排列（数量、取值范围、不重复）
        List<Integer> order = msg.order;
        if (order.size() != project.entries.size()) {
            sendFail(player, "spatial_blueprint.message.project.order.stale");
            return;
        }
        Map<Integer, ProjectData.EntryData> byIndex = new HashMap<>();
        for (ProjectData.EntryData entry : project.entries) byIndex.put(entry.index, entry);
        Set<Integer> seen = new HashSet<>();
        for (int index : order) {
            if (!byIndex.containsKey(index) || !seen.add(index)) {
                sendFail(player, "spatial_blueprint.message.project.order.stale");
                return;
            }
        }
        // 6. 重排 + 整表重编号 1..N + 前置引用重映射
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < order.size(); i++) remap.put(order.get(i), i + 1);
        List<ProjectData.EntryData> reordered = new ArrayList<>(order.size());
        for (int index : order) reordered.add(byIndex.get(index));
        for (int i = 0; i < reordered.size(); i++) {
            ProjectData.EntryData entry = reordered.get(i);
            entry.index = i + 1;
            Integer mappedRequires = remap.get(entry.requiresEntry);
            if (mappedRequires != null) entry.requiresEntry = mappedRequires;
        }
        project.entries.clear();
        project.entries.addAll(reordered);
        ProjectStorage.saveProject(projectUuid, project);

        player.displayClientMessage(Component.translatable(
                "spatial_blueprint.message.project.order.saved", project.name), false);
        C2S_RequestProjectStatusMessage.sendStatus(player);
    }

    private static void sendFail(ServerPlayer player, String key) {
        BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(false, key));
    }
}
