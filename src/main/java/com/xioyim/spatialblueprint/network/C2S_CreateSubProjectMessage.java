package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：OP 在项目「设定范围」界面点 [划定工程]、检测报告确认后，创建该项目的子工程。
 *
 * <p>载荷不带两点：选区就是主手项目蓝图上的临时两点（rangeP1/rangeP2，服务端自己写的 NBT），
 * 与 {@code C2S_SaveProjectRangeMessage} 同一原则 —— 不信任客户端包。</p>
 *
 * <p>世界扫描逻辑与 {@code C2S_CreateEngineeringBlueprintMessage} 一致（防复制清容器、
 * 依赖/迟置分类、装饰实体、材料清单），然后把工程数据落盘为项目的子工程文件，
 * 并往 project.dat 的条目表追加一条 EntryData。relP1/relP2 相对<b>基准点 anchor</b>：
 * 选区落在作者盒内 → project.anchor；落在任一 claim 盒内（维度也要对）→ 该 claim 的世界 anchor。
 * 扩建只动盒不动 anchor，已有子工程的世界位置因此不变。</p>
 *
 * <p>激活后仍可添加（条目表不再冻结）；主手的临时两点<b>不清</b>：OP 可以立刻调一调框下一块。</p>
 */
public class C2S_CreateSubProjectMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_CreateSubProjectMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_create_sub_project_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_CreateSubProjectMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_CreateSubProjectMessage::encode, C2S_CreateSubProjectMessage::decode);

    @Override
    public CustomPacketPayload.Type<C2S_CreateSubProjectMessage> type() {
        return TYPE;
    }


    private final String name;
    private final int duration;
    private final int index;
    private final String projectId;
    /** 2.7.17 起（e27）：简单备注 ≤15 字（列表行）+ 详细备注 ≤200 字（名称 tooltip）。 */
    private final String noteShort;
    private final String noteLong;

    public C2S_CreateSubProjectMessage(String name, int duration, int index, String projectId) {
        this(name, duration, index, projectId, "", "");
    }

    public C2S_CreateSubProjectMessage(String name, int duration, int index, String projectId,
                                       String noteShort, String noteLong) {
        this.name = name == null ? "" : name;
        this.duration = duration;
        this.index = index;
        this.projectId = projectId == null ? "" : projectId;
        this.noteShort = noteShort == null ? "" : noteShort;
        this.noteLong = noteLong == null ? "" : noteLong;
    }

    public static void encode(C2S_CreateSubProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name, 128);
        buf.writeInt(msg.duration);
        buf.writeInt(msg.index);
        buf.writeUtf(msg.projectId, 64);
        // 2.7.17 追加：备注（只许放末尾，见 T3）
        buf.writeUtf(msg.noteShort, 15);
        buf.writeUtf(msg.noteLong, 200);
    }

    public static C2S_CreateSubProjectMessage decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(128);
        int duration = buf.readInt();
        int index = buf.readInt();
        String projectId = buf.readUtf(64);
        String noteShort = buf.readUtf(15);
        String noteLong = buf.readUtf(200);
        return new C2S_CreateSubProjectMessage(name, duration, index, projectId, noteShort, noteLong);
    }

    public static void handle(C2S_CreateSubProjectMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_CreateSubProjectMessage msg, ServerPlayer player) {
    if (player == null) return;

        // 1. 仅 OP
        if (!player.hasPermissions(2)) {
            sendFail(player, "spatial_blueprint.message.subproject.need_op");
            return;
        }
        // 2. 主手（或副手，副手直开流程）必须是该项目绑定的项目蓝图 —— 服务端重读物品，不信任包里的 id
        ItemStack main = ProjectBlueprintItem.findHeld(player);
        if (main.isEmpty()
                || !msg.projectId.equals(ProjectBlueprintItem.getProjectId(main))) {
            sendFail(player, "spatial_blueprint.message.subproject.need_item");
            return;
        }
        // 3. 项目必须存在
        ServerLevel level = player.serverLevel();
        UUID projectUuid;
        try {
            projectUuid = UUID.fromString(msg.projectId);
        } catch (Exception e) {
            return;   // 非法 id：静默丢弃
        }
        ProjectData project = ProjectStorage.loadProject(projectUuid);
        if (project == null) {
            sendFail(player, "spatial_blueprint.message.subproject.project_missing");
            return;
        }
        // 3.5 必须在项目维度里：下面按绝对坐标扫的是 player.serverLevel()，扫错维度就是一份错的子工程（e37）
        if (!project.isInDimension(level)) {
            sendFail(player, "spatial_blueprint.message.project.wrong_dimension_blocked",
                    "spatial_blueprint.detail.project.wrong_dimension",
                    new Object[]{project.dimension, ProjectData.dimensionOf(level)});
            return;
        }
        // 4. 项目必须已保存布局（三轴都 > 0），否则子工程没有可校验的边界
        BlockPos o = project.origin;
        BlockPos sz = project.size;
        if (o == null || sz == null || sz.getX() <= 0 || sz.getY() <= 0 || sz.getZ() <= 0) {
            sendFail(player, "spatial_blueprint.message.subproject.no_range");
            return;
        }
        // 5. 两点：从服务端自己写进物品 NBT 的临时两点读，任一缺失拒绝
        BlockPos p1 = ProjectBlueprintItem.getRangePoint(main, 1);
        BlockPos p2 = ProjectBlueprintItem.getRangePoint(main, 2);
        if (p1 == null || p2 == null) {
            sendFail(player, "spatial_blueprint.message.project.range.missing_points");
            return;
        }
        // 6. 序号：>= 1 且未被占用
        if (msg.index < 1) {
            sendFail(player, "spatial_blueprint.message.subproject.index_taken");
            return;
        }
        for (ProjectData.EntryData e : project.entries) {
            if (e.index == msg.index) {
                sendFail(player, "spatial_blueprint.message.subproject.index_taken");
                return;
            }
        }

        BlockPos sMin = new BlockPos(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ()));
        BlockPos sMax = new BlockPos(
                Math.max(p1.getX(), p2.getX()),
                Math.max(p1.getY(), p2.getY()),
                Math.max(p1.getZ(), p2.getZ()));

        // 6.5 子工程不能和项目范围完全一致（客户端 ✚ 已拦，这里防改包绕过）
        if (sMin.equals(o)
                && sMax.equals(new BlockPos(o.getX() + sz.getX() - 1,
                        o.getY() + sz.getY() - 1, o.getZ() + sz.getZ() - 1))) {
            sendFail(player, "spatial_blueprint.message.subproject.same_as_project");
            return;
        }

        // 7. 选区必须完全落在作者盒（origin..origin+size-1）或本项目任一 claim 盒内，三轴都要判；
        //    base = 选区所属参考系的基准点（作者盒 → project.anchor；claim 盒 → 该 claim 的世界 anchor）
        BlockPos base = null;
        if (sMin.getX() >= o.getX() && sMax.getX() <= o.getX() + sz.getX() - 1
                && sMin.getY() >= o.getY() && sMax.getY() <= o.getY() + sz.getY() - 1
                && sMin.getZ() >= o.getZ() && sMax.getZ() <= o.getZ() + sz.getZ() - 1) {
            base = project.anchor == null ? o : project.anchor;
        } else {
            String dim = level.dimension().location().toString();
            for (ProjectClaimRegistry.Claim claim
                    : ProjectClaimRegistry.get(level).findByProject(msg.projectId)) {
                if (!dim.equals(claim.dimension)) continue;
                if (sMin.getX() >= claim.minX && sMax.getX() <= claim.maxX
                        && sMin.getY() >= claim.minY && sMax.getY() <= claim.maxY
                        && sMin.getZ() >= claim.minZ && sMax.getZ() <= claim.maxZ) {
                    base = ProjectClaimRegistry.anchorOf(claim, project);
                    break;
                }
            }
        }
        if (base == null) {
            sendFail(player, "spatial_blueprint.message.subproject.out_of_bounds",
                    "spatial_blueprint.detail.subproject.out_of_bounds",
                    new Object[]{
                            o.toShortString(),
                            new BlockPos(o.getX() + sz.getX() - 1,
                                    o.getY() + sz.getY() - 1,
                                    o.getZ() + sz.getZ() - 1).toShortString()
                    });
            return;
        }

        // 8. 子工程之间不能重叠：已有条目的绝对包围盒用状态包同一个参考系基准
        //    （玩家任务集的 claim anchor，未激活则作者 anchor），三轴都相交即拒
        String taskSetId = ProjectBlueprintItem.getTaskSetId(main);
        ProjectClaimRegistry.Claim holderClaim = taskSetId == null ? null
                : ProjectClaimRegistry.get(level).findByTaskSet(taskSetId);
        BlockPos entryBase = ProjectClaimRegistry.entryBase(holderClaim, project);
        for (ProjectData.EntryData e : project.entries) {
            if (e.relP1 == null || e.relP2 == null) continue;
            BlockPos eMin = entryBase.offset(e.relP1);
            BlockPos eMax = entryBase.offset(e.relP2);
            int minX = Math.min(eMin.getX(), eMax.getX()), maxX = Math.max(eMin.getX(), eMax.getX());
            int minY = Math.min(eMin.getY(), eMax.getY()), maxY = Math.max(eMin.getY(), eMax.getY());
            int minZ = Math.min(eMin.getZ(), eMax.getZ()), maxZ = Math.max(eMin.getZ(), eMax.getZ());
            boolean overlaps = sMin.getX() <= maxX && sMax.getX() >= minX
                    && sMin.getY() <= maxY && sMax.getY() >= minY
                    && sMin.getZ() <= maxZ && sMax.getZ() >= minZ;
            if (overlaps) {
                sendFail(player, "spatial_blueprint.message.subproject.overlap");
                return;
            }
        }

        // ── 扫描选区（与工程蓝图同款）────────────────────────────────
        BlueprintData data = new BlueprintData();
        data.size = new BlockPos(
                sMax.getX() - sMin.getX() + 1,
                sMax.getY() - sMin.getY() + 1,
                sMax.getZ() - sMin.getZ() + 1);

        java.util.Set<String> latePlaceIds = BlueprintConfig.getLatePlaceBlocks();
        for (int x = sMin.getX(); x <= sMax.getX(); x++) {
            for (int y = sMin.getY(); y <= sMax.getY(); y++) {
                for (int z = sMin.getZ(); z <= sMax.getZ(); z++) {
                    BlockPos wPos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(wPos);
                    if (state.isAir()) continue;
                    BlockEntity be = level.getBlockEntity(wPos);
                    // 无条件清空容器内容（防复制）：子工程不清除源区域，
                    // 绑定固定坐标可反复领取放置，留着箱内物品就是一台复制机
                    CompoundTag nbt = be == null ? null
                            : BlueprintSanitizer.stripItemContents(be.saveWithFullMetadata(level.registryAccess()));
                    BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(wPos.subtract(sMin), state, nbt);
                    String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock()).toString();
                    if (latePlaceIds.contains(blockId)) {
                        data.lateBlocks.add(entry);
                    } else if (BlueprintData.isDependent(state)) {
                        data.dependentBlocks.add(entry);
                    } else {
                        data.normalBlocks.add(entry);
                    }
                }
            }
        }

        // 装饰实体（画、展示框）：非 bypass，展示框内危险物品会被过滤
        AABB captureBox = new AABB(
                sMin.getX(), sMin.getY(), sMin.getZ(),
                sMax.getX() + 1.0, sMax.getY() + 1.0, sMax.getZ() + 1.0);
        level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                .forEach(e -> CaptureAreaMessage.captureHangingEntity(e, sMin, data, false));
        level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                .forEach(e -> CaptureAreaMessage.captureHangingEntity(e, sMin, data, false));

        int totalBlocks = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();

        // 9. 子工程上限按「非空气方块数」计（不是选区体积）
        int limit = BlueprintConfig.getMaxVolumeFor("subproject");
        if (totalBlocks > limit) {
            sendFail(player, "spatial_blueprint.message.subproject.too_large",
                    "spatial_blueprint.detail.subproject.too_large",
                    new Object[]{String.valueOf(totalBlocks), String.valueOf(limit)});
            return;
        }

        // ── 成功路径 ──────────────────────────────────────────────
        data.materialList = MaterialList.fromBlueprintData(data);
        data.animDuration = msg.duration > 0 ? msg.duration : BlueprintConfig.getAutoDuration(totalBlocks);
        data.category     = "subproject";
        data.parentProjectId = msg.projectId;
        data.name         = BlueprintValidator.sanitizeName(msg.name);
        data.noteShort    = msg.noteShort.trim();
        data.noteLong     = msg.noteLong.trim();
        data.creatorName  = player.getScoreboardName();
        data.sourceName   = player.getScoreboardName();
        data.ownerUuid    = player.getUUID().toString();
        com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, level);
        data.createdAt    = System.currentTimeMillis();

        UUID engId = UUID.randomUUID();
        String filename = ProjectStorage.saveEntry(projectUuid, engId, data);

        ProjectData.EntryData entry = new ProjectData.EntryData();
        entry.index    = msg.index;
        entry.filename = filename;
        entry.label    = data.name;
        entry.note     = data.noteShort;
        entry.noteLong = data.noteLong;
        entry.relP1    = sMin.subtract(base);
        entry.relP2    = sMax.subtract(base);
        entry.color    = ProjectData.paletteColor(project.entries.size());
        entry.requiresEntry = 0;
        entry.blockCount = totalBlocks;
        // 名称 tooltip 的元数据缓存：创建时数据就在手上，直接写进条目，状态请求不必再读文件
        entry.animDuration = data.animDuration;
        entry.creatorName  = data.creatorName;
        entry.createdAt    = data.createdAt;
        entry.metaLoaded   = true;
        project.entries.add(entry);
        ProjectStorage.saveProject(projectUuid, project);

        // 主手的临时两点原样不动：OP 可以立刻调一调框下一块。
        // 子工程不占槽位（DS-103 已设），无需槽位校验。
        player.displayClientMessage(
                Component.translatable("spatial_blueprint.message.subproject.created",
                        data.name, msg.index), false);
        // 回到项目界面要能看到新条目：主动推一份最新状态
        C2S_RequestProjectStatusMessage.sendStatus(player);
    }

    private static void sendFail(ServerPlayer player, String key) {
        sendFail(player, key, "", null);
    }

    /** messageKey 必须无参数；带参数的说明放在 detail 键里，由客户端用 args 渲染。 */
    private static void sendFail(ServerPlayer player, String key, String detailKey, Object[] args) {
        BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(false, key, detailKey, args));
    }
}
