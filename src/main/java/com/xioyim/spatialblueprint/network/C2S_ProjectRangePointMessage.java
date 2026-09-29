package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：「设定项目蓝图范围」界面改动临时两点。
 *
 * <p>与 {@link SetPointMessage} 同一套路，只是写到<b>主手项目蓝图</b>的 rangeP1/rangeP2；
 * index 0 = 清掉两点（取消操作）。仅 OP，且蓝图未激活（激活后范围锁死）。
 * 静默拒绝：客户端按钮已经按同样条件置灰，走到这里的只有改包。</p>
 */
public class C2S_ProjectRangePointMessage {

    public static final int CLEAR = 0;

    private final int pointIndex;
    private final BlockPos pos;

    public C2S_ProjectRangePointMessage(int pointIndex, BlockPos pos) {
        this.pointIndex = pointIndex;
        this.pos = pos == null ? BlockPos.ZERO : pos;
    }

    public static C2S_ProjectRangePointMessage clear() {
        return new C2S_ProjectRangePointMessage(CLEAR, BlockPos.ZERO);
    }

    public static void encode(C2S_ProjectRangePointMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.pointIndex);
        buf.writeBlockPos(msg.pos);
    }

    public static C2S_ProjectRangePointMessage decode(FriendlyByteBuf buf) {
        return new C2S_ProjectRangePointMessage(buf.readVarInt(), buf.readBlockPos());
    }

    public static void handle(C2S_ProjectRangePointMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;

            // 主手优先、其次副手（副手直开流程）
            InteractionHand hand = ProjectBlueprintItem.findHeldHand(player);
            if (hand == null) return;
            ItemStack stack = player.getItemInHand(hand);
            if (ProjectBlueprintItem.isActivated(stack)) return;

            if (msg.pointIndex == CLEAR) {
                ProjectBlueprintItem.clearRangePoints(stack);
            } else if (msg.pointIndex == 1 || msg.pointIndex == 2) {
                if (!BlueprintValidator.validateBlockPos(player.serverLevel(), msg.pos).valid()) return;
                // 只能在项目维度里设点（清点不限，取消操作在哪都该能做；e37）
                if (!inProjectDimension(player, stack)) return;
                ProjectBlueprintItem.setRangePoint(stack, msg.pointIndex, msg.pos);
            } else {
                return;
            }
            ItemSyncUtil.syncHand(player, hand);
        });
        ctx.get().setPacketHandled(true);
    }

    /** 以 project.dat 为准（不信物品 NBT：创造模式客户端能改物品 NBT）；项目不存在也拒。 */
    private static boolean inProjectDimension(ServerPlayer player, ItemStack stack) {
        String pid = ProjectBlueprintItem.getProjectId(stack);
        if (pid == null) return false;
        com.xioyim.spatialblueprint.util.ProjectData project;
        try {
            project = com.xioyim.spatialblueprint.util.ProjectStorage.loadProject(java.util.UUID.fromString(pid));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return project != null && project.isInDimension(player.serverLevel());
    }
}
