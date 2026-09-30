package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.SubProjectClaimHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S：从项目蓝图界面领取指定序号的子工程。 */
public class C2S_ClaimSubProjectMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_ClaimSubProjectMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_claim_sub_project_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ClaimSubProjectMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_ClaimSubProjectMessage::encode, C2S_ClaimSubProjectMessage::decode);

    private final int entryIndex;

    public C2S_ClaimSubProjectMessage(int entryIndex) {
        this.entryIndex = entryIndex;
    }

    @Override
    public CustomPacketPayload.Type<C2S_ClaimSubProjectMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_ClaimSubProjectMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entryIndex);
    }

    public static C2S_ClaimSubProjectMessage decode(FriendlyByteBuf buf) {
        return new C2S_ClaimSubProjectMessage(buf.readVarInt());
    }

    public static void handle(C2S_ClaimSubProjectMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_ClaimSubProjectMessage msg, ServerPlayer player) {
        if (player == null) return;

        SubProjectClaimHandler.Result result =
                SubProjectClaimHandler.claim(player, msg.entryIndex);
        sendResult(player, result);
        if (result.success()) {
            C2S_RequestProjectStatusMessage.sendStatus(player);
        }
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

        BlueprintNetwork.sendToPlayer(player,
                new S2C_OperationResult(
                        result.success(), messageKey, detailKey, detailArgs));
    }
}
