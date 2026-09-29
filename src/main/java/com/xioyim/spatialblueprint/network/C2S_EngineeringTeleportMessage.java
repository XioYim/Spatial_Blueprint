package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S: 玩家请求传送到绑定的工程蓝图位置。
 *
 * <p>传送权限由 tpperm 这套判定决定（OP / 创造模式 / {@code /sb tpperm} 授予的名单）；
 * 落点：OP 设过安全传送坐标（{@code SBSafeCoordData}）就是那个点，否则绑定工程的起点 / 终点中离玩家近的那个。</p>
 */
public class C2S_EngineeringTeleportMessage {

    public static void encode(C2S_EngineeringTeleportMessage msg, FriendlyByteBuf buf) {}

    public static C2S_EngineeringTeleportMessage decode(FriendlyByteBuf buf) {
        return new C2S_EngineeringTeleportMessage();
    }

    public static void handle(C2S_EngineeringTeleportMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Find held engineering blueprint
            ItemStack stack = null;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack s = player.getItemInHand(hand);
                if (s.getItem() instanceof EngineeringBlueprintItem
                        && EngineeringBlueprintItem.hasBoundProject(s)) {
                    stack = s;
                    break;
                }
            }
            if (stack == null) {
                sendResult(player, false, "spatial_blueprint.gui.engineering.btn.tp.no_bound");
                return;
            }

            // 传送权限：OP / 创造模式天然拥有，其余看 /sb tpperm 授予的名单
            if (!com.xioyim.spatialblueprint.util.TeleportPermissions.canTeleport(player)) {
                sendResult(player, false, "spatial_blueprint.message.teleport.no_permission");
                return;
            }

            // 传送冷却（服务端强制，e77）：与客户端 6 秒置灰同口径，防连点刷落地保护效果
            long cdLeft = com.xioyim.spatialblueprint.util.TeleportPermissions.tryAcquireTeleport(player);
            if (cdLeft > 0) {
                long secs = (cdLeft + 999L) / 1000L;
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(false,
                                "spatial_blueprint.message.teleport.cooldown",
                                "spatial_blueprint.gui.teleport.cooldown", new Object[]{secs}));
                return;
            }

            // Dimension check
            String boundDim = EngineeringBlueprintItem.getBoundDimension(stack);
            String currentDim = player.serverLevel().dimension().location().toString();
            if (!boundDim.isEmpty() && !boundDim.equals(currentDim)) {
                sendResult(player, false, "spatial_blueprint.gui.engineering.btn.tp.wrong_dim");
                return;
            }

            // 落点：OP 设了安全传送坐标就传那个点；否则绑定工程起点 / 终点中离玩家近的那个
            String blueprintId = EngineeringBlueprintItem.getBlueprintId(stack);
            com.xioyim.spatialblueprint.util.SBSafeCoordData.SafeEntry safe =
                    blueprintId != null && !blueprintId.isEmpty()
                            ? com.xioyim.spatialblueprint.util.SBSafeCoordData.get(player.serverLevel()).getEntry(blueprintId)
                            : null;
            BlockPos dest;
            if (safe != null && safe.hasCoord()) {
                dest = new BlockPos(safe.x(), safe.y(), safe.z());
            } else {
                BlockPos startPos = EngineeringBlueprintItem.getBoundStartPos(stack);
                BlockPos endPos   = EngineeringBlueprintItem.getBoundEndPos(stack);
                Vec3 pos = player.position();
                double dStart = pos.distanceToSqr(startPos.getX() + 0.5, startPos.getY(), startPos.getZ() + 0.5);
                double dEnd   = pos.distanceToSqr(endPos.getX() + 0.5,   endPos.getY(),   endPos.getZ() + 0.5);
                dest = dStart <= dEnd ? startPos : endPos;
            }

            player.teleportTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
            // L4 同款：落点无安全检查，落地给 5 秒保护（抗性提升 5 / 缓降 / 抗火）
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4));
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0));
            player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 0));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey));
    }
}
