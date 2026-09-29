package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.SBSafeCoordData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S：保存 / 清除某份工程蓝图的安全传送坐标（蓝图库工程 Tab [TP] → SafeCoordScreen [保存]）。
 * 仅 OP；非 OP 回横幅拒绝，不静默丢（T9）。{@code hasCoord=false} = 清除，[Tp] 回到起 / 终点取近。
 */
public class C2S_SafeCoordMessage {

    private final String  blueprintId;
    private final boolean hasCoord;
    private final int     x, y, z;

    public C2S_SafeCoordMessage(String blueprintId, boolean hasCoord, int x, int y, int z) {
        this.blueprintId = blueprintId;
        this.hasCoord    = hasCoord;
        this.x           = x;
        this.y           = y;
        this.z           = z;
    }

    public static void encode(C2S_SafeCoordMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeBoolean(msg.hasCoord);
        buf.writeInt(msg.x);
        buf.writeInt(msg.y);
        buf.writeInt(msg.z);
    }

    public static C2S_SafeCoordMessage decode(FriendlyByteBuf buf) {
        String  id       = buf.readUtf(64);
        boolean hasCoord = buf.readBoolean();
        int     x        = buf.readInt();
        int     y        = buf.readInt();
        int     z        = buf.readInt();
        return new C2S_SafeCoordMessage(id, hasCoord, x, y, z);
    }

    public static void handle(C2S_SafeCoordMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || msg.blueprintId.isEmpty()) return;
            if (!player.hasPermissions(2)) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(false, "spatial_blueprint.message.safe_coord.need_op"));
                return;
            }
            // 坐标不做维度 / 距离校验：这是 OP 手填的点，传过去发现不对再改就是；越界的 Y
            // 由 teleportTo 自己兜（世界高度外会卡在边界），不值得在这里再限。
            SBSafeCoordData.get(player.serverLevel())
                    .setEntry(msg.blueprintId, msg.hasCoord, msg.x, msg.y, msg.z);

            player.displayClientMessage(
                    Component.translatable(msg.hasCoord
                            ? "spatial_blueprint.gui.safe_coord.saved"
                            : "spatial_blueprint.gui.safe_coord.cleared"), false);
        });
        ctx.get().setPacketHandled(true);
    }
}
