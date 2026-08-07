package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：创造模式玩家为指定投影蓝图设置自定义取出经验等级或取出次数上限。
 *
 * <ul>
 *   <li>{@link #PROP_XP}    — 设定 {@code customWithdrawXp}（-1 表示清除，恢复公式计算）</li>
 *   <li>{@link #PROP_LIMIT} — 设定 {@code customWithdrawLimit}（-1 表示清除，恢复配置默认值）</li>
 * </ul>
 *
 * <p>服务端同步更新蓝图文件；若玩家当前手中持有该蓝图，也同步更新物品 NBT。</p>
 */
public class C2S_SetWithdrawPropertyMessage {

    public static final int PROP_XP       = 0;
    public static final int PROP_LIMIT    = 1;
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

    public static void handle(C2S_SetWithdrawPropertyMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // PROP_XP and PROP_LIMIT are economy-sensitive — creative-only.
            // PROP_DURATION may be set by any player.
            if (msg.property != PROP_DURATION && !player.isCreative()) return;

            // ── 对 PROP_DURATION 做服务端二次钳位（防止客户端绕过上限）──
            int effectiveValue = msg.value;
            if (msg.property == PROP_DURATION) {
                effectiveValue = Math.max(com.xioyim.spatialblueprint.BlueprintConfig.ANIMATION_DURATION_MIN.get(), effectiveValue);
                effectiveValue = Math.min(effectiveValue, com.xioyim.spatialblueprint.BlueprintConfig.ANIMATION_DURATION_MAX.get());
            }

            // ── 更新蓝图文件（PROP_DURATION 失败时发 S2C 错误反馈）──────
            boolean fileOk;
            switch (msg.property) {
                case PROP_XP       -> { fileOk = BlueprintStorage.setCustomWithdrawXp(msg.blueprintId, effectiveValue); }
                case PROP_LIMIT    -> { fileOk = BlueprintStorage.setCustomWithdrawLimit(msg.blueprintId, effectiveValue); }
                case PROP_DURATION -> { fileOk = BlueprintStorage.setAnimDuration(msg.blueprintId, effectiveValue); }
                default            -> { fileOk = false; }
            }
            if (!fileOk) {
                BlueprintNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(false, "spatial_blueprint.error.blueprint_file_not_found"));
                return;
            }

            // ── 更新手中蓝图的物品 NBT（若持有该蓝图）────────────────────
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                String heldId = null;
                if (stack.getItem() instanceof ProjectionBlueprintItem) {
                    heldId = ProjectionBlueprintItem.getBlueprintId(stack);
                } else if (stack.getItem() instanceof SpatialBlueprintItem) {
                    heldId = SpatialBlueprintItem.getBlueprintId(stack);
                }
                if (!msg.blueprintId.equals(heldId)) continue;

                switch (msg.property) {
                    case PROP_XP    -> ProjectionBlueprintItem.setCustomWithdrawXp(stack, effectiveValue);
                    case PROP_LIMIT -> ProjectionBlueprintItem.setCustomWithdrawLimit(stack, effectiveValue);
                    case PROP_DURATION -> SpatialBlueprintItem.setAnimDuration(stack, effectiveValue);
                }
                ItemSyncUtil.syncHand(player, hand);
                break;
            }

            // ── 推送最新摘要列表，让客户端 ClientLibraryCache 同步更新 ──
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(BlueprintStorage.listSummaries()));
        });
        ctx.get().setPacketHandled(true);
    }
}
