package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.SubProjectClaimHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** C2S：从项目蓝图界面领取指定序号的子工程。 */
public class C2S_ClaimSubProjectMessage {

    private final int entryIndex;

    public C2S_ClaimSubProjectMessage(int entryIndex) {
        this.entryIndex = entryIndex;
    }

    public static void encode(C2S_ClaimSubProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entryIndex);
    }

    public static C2S_ClaimSubProjectMessage decode(FriendlyByteBuf buf) {
        return new C2S_ClaimSubProjectMessage(buf.readVarInt());
    }

    public static void handle(C2S_ClaimSubProjectMessage msg,
                              Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            SubProjectClaimHandler.Result result =
                    SubProjectClaimHandler.claim(player, msg.entryIndex);
            sendResult(player, result);
            if (result.success()) {
                C2S_RequestProjectStatusMessage.sendStatus(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** messageKey 不带参数；有参数的原始指令 key 放进 detail，由客户端本地化。 */
    private static void sendResult(ServerPlayer player, SubProjectClaimHandler.Result result) {
        String messageKey = result.messageKey();
        String detailKey = "";
        Object[] detailArgs = new Object[0];

        if (result.alreadyDone()) {
            messageKey = "spatial_blueprint.command.project.claim.already_done";
            detailKey = result.messageKey();
            detailArgs = result.args();
        } else if (result.args().length > 0) {
            messageKey = "spatial_blueprint.gui.project_screen.btn_claim";
            detailKey = result.messageKey();
            detailArgs = result.args();
        }

        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(
                        result.success(), messageKey, detailKey, detailArgs));
    }
}
