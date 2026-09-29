package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectProgressData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * C2S：规划模式界面 [提交修改] —— 一次提交三类本地暂存改动（仅 OP、项目无任务集占用）。
 *
 * <ul>
 *   <li>{@code deletes} — 要删除的子工程序号；</li>
 *   <li>{@code order} — 提交后应存活的<b>全部</b>条目序号，按新顺序（= 当前显示顺序去掉删除项）；</li>
 *   <li>{@code materials} — 有材料清单改动的条目（整份清单覆盖写）。</li>
 * </ul>
 *
 * <p>服务端按顺序逐项校验，<b>任一失败立刻回横幅并 return，一个字节都不写</b>；全过才：
 * ① 三清删除（进度 → 文件 → 条目）；② 整份覆盖材料清单；③ 重排 + 整表重编号 1..N +
 * 前置引用重映射；④ 落盘并推最新状态。</p>
 */
public class C2S_SubmitProjectChangesMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_SubmitProjectChangesMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_submit_project_changes_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_SubmitProjectChangesMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_SubmitProjectChangesMessage::encode, C2S_SubmitProjectChangesMessage::decode);

    /** 一条材料改动：条目序号 + 整份清单（blockCounts / keyTags 与 {@link C2S_SaveMaterialListMessage} 同编码）。 */
    public record MaterialChange(int entryIndex,
                                 LinkedHashMap<String, Integer> blockCounts,
                                 LinkedHashMap<String, CompoundTag> keyTags) {}

    private final String projectId;
    private final List<Integer> order;
    private final List<Integer> deletes;
    private final List<MaterialChange> materials;

    public C2S_SubmitProjectChangesMessage(String projectId, List<Integer> order, List<Integer> deletes,
                                           Map<Integer, MaterialList> materials) {
        this.projectId = projectId == null ? "" : projectId;
        this.order = order == null ? List.of() : List.copyOf(order);
        this.deletes = deletes == null ? List.of() : List.copyOf(deletes);
        List<MaterialChange> list = new ArrayList<>();
        if (materials != null) {
            for (Map.Entry<Integer, MaterialList> e : materials.entrySet()) {
                MaterialList ml = e.getValue();
                if (ml == null) continue;
                list.add(new MaterialChange(e.getKey(),
                        new LinkedHashMap<>(ml.blockCounts),
                        new LinkedHashMap<>(ml.keyTags)));
            }
        }
        this.materials = List.copyOf(list);
    }

    @Override
    public CustomPacketPayload.Type<C2S_SubmitProjectChangesMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_SubmitProjectChangesMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.projectId, 64);
        buf.writeVarInt(msg.order.size());
        for (int index : msg.order) buf.writeVarInt(index);
        buf.writeVarInt(msg.deletes.size());
        for (int index : msg.deletes) buf.writeVarInt(index);
        buf.writeVarInt(msg.materials.size());
        for (MaterialChange change : msg.materials) {
            buf.writeVarInt(change.entryIndex());
            buf.writeVarInt(change.blockCounts().size());
            for (Map.Entry<String, Integer> e : change.blockCounts().entrySet()) {
                buf.writeUtf(e.getKey(), 128);
                buf.writeInt(e.getValue());
            }
            buf.writeVarInt(change.keyTags().size());
            for (Map.Entry<String, CompoundTag> e : change.keyTags().entrySet()) {
                buf.writeUtf(e.getKey(), 128);
                buf.writeNbt(e.getValue());
            }
        }
    }

    public static C2S_SubmitProjectChangesMessage decode(FriendlyByteBuf buf) {
        String projectId = buf.readUtf(64);
        // 计数先过 checkedCount（上限 = 剩余字节数：每个 VarInt 元素至少 1 字节）再预分配。
        // decode 在服务端、权限判定之前执行，1.20.1 原样按客户端计数预分配，一个 5 字节 VarInt
        // 就能让服务端尝试分配 20 亿元素 —— SBN-008 验收修正，越界整包拒收。
        int orderCount = com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult
                .checkedCount(buf, buf.readableBytes(), "orderCount");
        List<Integer> order = new ArrayList<>(orderCount);
        for (int i = 0; i < orderCount; i++) order.add(buf.readVarInt());
        int deleteCount = com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult
                .checkedCount(buf, buf.readableBytes(), "deleteCount");
        List<Integer> deletes = new ArrayList<>(deleteCount);
        for (int i = 0; i < deleteCount; i++) deletes.add(buf.readVarInt());
        int matCount = buf.readVarInt();
        LinkedHashMap<Integer, MaterialList> materials = new LinkedHashMap<>();
        for (int i = 0; i < matCount; i++) {
            int entryIndex = buf.readVarInt();
            int size = buf.readVarInt();
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            for (int j = 0; j < size; j++) {
                counts.put(buf.readUtf(128), buf.readInt());
            }
            int tagCount = buf.readVarInt();
            LinkedHashMap<String, CompoundTag> tags = new LinkedHashMap<>();
            for (int j = 0; j < tagCount; j++) {
                String key = buf.readUtf(128);
                CompoundTag tag = buf.readNbt();
                if (tag != null) tags.put(key, tag);
            }
            MaterialList ml = new MaterialList();
            ml.blockCounts.putAll(counts);
            ml.keyTags.putAll(tags);
            materials.put(entryIndex, ml);
        }
        return new C2S_SubmitProjectChangesMessage(projectId, order, deletes, materials);
    }

    public static void handle(C2S_SubmitProjectChangesMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_SubmitProjectChangesMessage msg, ServerPlayer player) {
        if (player == null) return;

        // 1. 仅 OP
        if (!player.hasPermissions(2)) {
            sendFail(player, "spatial_blueprint.message.subproject.need_op");
            return;
        }
        // 2. 主手（或副手，副手直开流程）必须是该项目绑定的项目蓝图
        ItemStack main = ProjectBlueprintItem.findHeld(player);
        if (main.isEmpty()
                || !msg.projectId.equals(ProjectBlueprintItem.getProjectId(main))) {
            sendFail(player, "spatial_blueprint.message.subproject.need_item");
            return;
        }
        // 3. 项目必须存在
        UUID projectUuid;
        ProjectData project;
        try {
            projectUuid = UUID.fromString(msg.projectId);
            project = ProjectStorage.loadProject(projectUuid);
        } catch (IllegalArgumentException e) {
            projectUuid = null;
            project = null;
        }
        if (project == null) {
            sendFail(player, "spatial_blueprint.message.subproject.project_missing");
            return;
        }
        // 4. 有任务集占用 = 进度 / 已发蓝图按序号记，整单拒
        if (ProjectClaimRegistry.get(player.serverLevel()).hasAnyClaimForProject(msg.projectId)) {
            sendFail(player, "spatial_blueprint.message.project.order.activated");
            return;
        }
        // 5. 序号集合校验：deletes ⊆ 现有序号；order = 现有序号 − deletes 的排列；materials ⊆ order
        Map<Integer, ProjectData.EntryData> byIndex = new HashMap<>();
        for (ProjectData.EntryData entry : project.entries) byIndex.put(entry.index, entry);
        Set<Integer> deleteSet = new HashSet<>();
        for (int index : msg.deletes) {
            if (!byIndex.containsKey(index) || !deleteSet.add(index)) {
                sendFail(player, "spatial_blueprint.message.project.order.stale");
                return;
            }
        }
        Set<Integer> expected = new HashSet<>(byIndex.keySet());
        expected.removeAll(deleteSet);
        Set<Integer> orderSet = new HashSet<>();
        for (int index : msg.order) {
            if (!expected.contains(index) || !orderSet.add(index)) {
                sendFail(player, "spatial_blueprint.message.project.order.stale");
                return;
            }
        }
        if (msg.order.size() != expected.size()) {
            sendFail(player, "spatial_blueprint.message.project.order.stale");
            return;
        }
        for (MaterialChange change : msg.materials) {
            if (!orderSet.contains(change.entryIndex())) {
                sendFail(player, "spatial_blueprint.message.project.order.stale");
                return;
            }
        }
        // 6. 存活条目里若有前置指向被删序号 → 整单拒（detail 带被删序号与引用者）
        for (int index : msg.order) {
            ProjectData.EntryData entry = byIndex.get(index);
            if (entry == null || entry.requiresEntry == 0 || !deleteSet.contains(entry.requiresEntry)) {
                continue;
            }
            List<String> dependents = new ArrayList<>();
            for (int other : msg.order) {
                ProjectData.EntryData candidate = byIndex.get(other);
                if (candidate != null && candidate.requiresEntry == entry.requiresEntry) {
                    dependents.add(String.valueOf(candidate.index));
                }
            }
            sendFail(player, "spatial_blueprint.message.project.changes.prereq_ref",
                    "spatial_blueprint.detail.project.changes.prereq_ref",
                    new Object[]{String.valueOf(entry.requiresEntry), String.join(", ", dependents)});
            return;
        }

        // 7. 材料清单非法方块检查（与放置界面 C2S_SaveMaterialListMessage 同口径，e32 补）：
        //    键可能是带 NBT 摘要后缀的复合键，先还原为注册 ID 再查；命中整单拒，一个字节都不写
        for (MaterialChange change : msg.materials) {
            for (String materialKey : change.blockCounts().keySet()) {
                if (BlueprintSanitizer.isIllegalBlockId(MaterialKey.idOf(materialKey))) {
                    sendFail(player, "spatial_blueprint.error.illegal_material");
                    return;
                }
            }
        }

        // ── 校验全过：以下才开始写盘 ────────────────────────────────────
        // ① 三清删除（进度 → 文件 → 条目）；该项目此刻没有 claim，集合传空但调用保留
        ProjectClaimRegistry registry = ProjectClaimRegistry.get(player.serverLevel());
        List<String> taskSetIds = new ArrayList<>();
        for (ProjectClaimRegistry.Claim claim : registry.findByProject(msg.projectId)) {
            taskSetIds.add(claim.taskSetId);
        }
        for (int index : msg.deletes) {
            ProjectData.EntryData entry = byIndex.get(index);
            if (entry == null) continue;
            ProjectProgressData.get(player.serverLevel()).forgetEntry(taskSetIds, index);
            ProjectStorage.deleteEntryFile(projectUuid, entry.filename);
            project.entries.removeIf(candidate -> candidate.index == index);
        }
        // ② 材料清单整份覆盖（文件读不到只记 warn，不算失败）
        int materialChanged = 0;
        for (MaterialChange change : msg.materials) {
            ProjectData.EntryData entry = byIndex.get(change.entryIndex());
            if (entry == null) continue;
            BlueprintData data = ProjectStorage.loadEntry(projectUuid, entry.filename);
            if (data == null) {
                SpatialBlueprintMod.LOGGER.warn("[Project] Submit changes: entry file missing, skip materials pid={} index={}",
                        msg.projectId, change.entryIndex());
                continue;
            }
            if (data.materialList == null) {
                data.materialList = new MaterialList();
                data.materialList.blueprintName = data.name == null ? "" : data.name;
            }
            data.materialList.blockCounts.clear();
            data.materialList.blockCounts.putAll(change.blockCounts());
            // tag 表整体替换：清单里已删除的条目不该留下孤儿 tag
            data.materialList.keyTags.clear();
            for (Map.Entry<String, CompoundTag> e : change.keyTags().entrySet()) {
                if (change.blockCounts().containsKey(e.getKey())) {
                    data.materialList.keyTags.put(e.getKey(), e.getValue());
                }
            }
            String filename = entry.filename == null ? "" : entry.filename;
            String engId = filename.endsWith(".litematic")
                    ? filename.substring(0, filename.length() - ".litematic".length()) : "";
            if (engId.isEmpty()) continue;
            try {
                ProjectStorage.saveEntry(projectUuid, UUID.fromString(engId), data);
                materialChanged++;
            } catch (IllegalArgumentException e) {
                SpatialBlueprintMod.LOGGER.warn("[Project] Submit changes: bad entry id '{}' pid={}",
                        engId, msg.projectId);
            }
        }
        // ③ 按 order 重排 + 整表重编号 1..N + 前置引用重映射
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < msg.order.size(); i++) remap.put(msg.order.get(i), i + 1);
        List<ProjectData.EntryData> reordered = new ArrayList<>(msg.order.size());
        for (int index : msg.order) reordered.add(byIndex.get(index));
        for (int i = 0; i < reordered.size(); i++) {
            ProjectData.EntryData entry = reordered.get(i);
            entry.index = i + 1;
            Integer mappedRequires = remap.get(entry.requiresEntry);
            if (mappedRequires != null) entry.requiresEntry = mappedRequires;
        }
        project.entries.clear();
        project.entries.addAll(reordered);
        // ④ 落盘 ⑤ 推状态 ⑥ 日志
        ProjectStorage.saveProject(projectUuid, project);
        C2S_RequestProjectStatusMessage.sendStatus(player);
        SpatialBlueprintMod.LOGGER.info("[Project] Changes submitted project='{}' id={} deletes={} materials={} order={} by {}",
                project.name, msg.projectId, msg.deletes.size(), materialChanged,
                msg.order.size(), player.getGameProfile().getName());
    }

    private static void sendFail(ServerPlayer player, String key) {
        sendFail(player, key, "", null);
    }

    /** messageKey 必须无参数；带参数的说明放在 detail 键里，由客户端用 args 渲染。 */
    private static void sendFail(ServerPlayer player, String key, String detailKey, Object[] args) {
        BlueprintNetwork.sendToPlayer(player,
                new S2C_OperationResult(false, key, detailKey, args));
    }
}
