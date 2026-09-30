package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：更新蓝图的动画时长。
 */
public class C2S_SetWithdrawPropertyMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_SetWithdrawPropertyMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_set_withdraw_property_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_SetWithdrawPropertyMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_SetWithdrawPropertyMessage::encode, C2S_SetWithdrawPropertyMessage::decode);

    /** Updates animDuration in the blueprint file and the held item's root NBT. Available to all players. */
    public static final int PROP_DURATION = 2;

    private final String blueprintId;
    private final int    property;
    private final int    value;

    public C2S_SetWithdrawPropertyMessage(String blueprintId, int property, int value) {
        this.blueprintId = blueprintId;
        this.property    = property;
        this.value       = value;
    }

    @Override
    public CustomPacketPayload.Type<C2S_SetWithdrawPropertyMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_SetWithdrawPropertyMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeInt(msg.property);
        buf.writeInt(msg.value);
    }

    public static C2S_SetWithdrawPropertyMessage decode(FriendlyByteBuf buf) {
        String bpId = buf.readUtf(64);
        int    prop = buf.readInt();
        int    val  = buf.readInt();
        return new C2S_SetWithdrawPropertyMessage(bpId, prop, val);
    }

    public static void handle(C2S_SetWithdrawPropertyMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_SetWithdrawPropertyMessage msg, ServerPlayer player) {
        if (player == null) return;

        if (msg.property != PROP_DURATION) return;

        // ── 归属校验：主人或 OP 才能改 ────────────────────────────────
        // 以前这里没有任何校验：blueprintId 直接来自客户端，随便填一个
        // 别人的 id 就能改掉别人蓝图的动画时长，还会顺带触发一次全库摘要推送。
        if (!BlueprintValidator.canManageBlueprint(player, msg.blueprintId)) {
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_OperationResult(false, "spatial_blueprint.error.blueprint_not_owner"));
            return;
        }

        // ── 对 PROP_DURATION 做服务端二次钳位（防止客户端绕过上限）──
        int effectiveValue = msg.value;
        if (msg.property == PROP_DURATION) {
            effectiveValue = Math.max(com.xioyim.spatialblueprint.BlueprintConfig.ANIMATION_DURATION_MIN.get(), effectiveValue);
            effectiveValue = Math.min(effectiveValue, com.xioyim.spatialblueprint.BlueprintConfig.ANIMATION_DURATION_MAX.get());
        }

        // ── 更新蓝图文件（PROP_DURATION 失败时发 S2C 错误反馈）──────
        boolean fileOk;
        switch (msg.property) {
            case PROP_DURATION -> { fileOk = BlueprintStorage.setAnimDuration(msg.blueprintId, effectiveValue); }
            default            -> { fileOk = false; }
        }
        if (!fileOk) {
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_OperationResult(false, "spatial_blueprint.error.blueprint_file_not_found"));
            return;
        }

        // ── 更新手中蓝图的物品 NBT（若持有该蓝图）────────────────────
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            String heldId = null;
            if (stack.getItem() instanceof SpatialBlueprintItem) {
                heldId = SpatialBlueprintItem.getBlueprintId(stack);
            }
            if (!msg.blueprintId.equals(heldId)) continue;

            switch (msg.property) {
                case PROP_DURATION -> SpatialBlueprintItem.setAnimDuration(stack, effectiveValue);
            }
            ItemSyncUtil.syncHand(player, hand);
            break;
        }

        // ── 推送最新摘要列表，让客户端 ClientLibraryCache 同步更新 ──
        // H2：过滤后再推。
        BlueprintNetwork.sendToPlayer(player,
                new S2C_LibrarySyncMessage(C2S_RequestLibraryMessage.buildFilteredSummaries(player)));
    }
}
