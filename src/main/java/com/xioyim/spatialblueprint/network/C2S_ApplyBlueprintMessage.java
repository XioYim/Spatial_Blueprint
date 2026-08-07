package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
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
 * 客户端 → 服务端：从蓝图库应用指定蓝图，替换玩家手中的蓝图物品。
 *
 * <p>根据蓝图的 {@code category} 字段决定物品类型：
 * <ul>
 *   <li>{@code "project"} → {@link ProjectionBlueprintItem}</li>
 *   <li>其余（{@code "create"}、{@code "store"}、旧格式空字符串）→ {@link SpatialBlueprintItem}</li>
 * </ul>
 * 优先替换手中已有蓝图物品的那只手；若均无，默认替换主手。</p>
 */
public class C2S_ApplyBlueprintMessage {

    private final String blueprintId;

    public C2S_ApplyBlueprintMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    public static void encode(C2S_ApplyBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_ApplyBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_ApplyBlueprintMessage(buf.readUtf(64));
    }

    public static void handle(C2S_ApplyBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            // 应用操作不限创造模式

            // ── 加载蓝图数据 ──────────────────────────────────────────────
            BlueprintData data = BlueprintStorage.load(msg.blueprintId);
            if (data == null) {
                sendResult(player, false, "spatial_blueprint.error.no_blueprint_data");
                return;
            }

            // ── 先找到合适的手 ────────────────────────────────────────────
            // （需要先确定手，才能判断是否持有工程蓝图并保留物品类型）
            // SpatialBlueprintItem 是 DimensionalBlueprintItem 的父类，instanceof 自动覆盖
            InteractionHand targetHand = InteractionHand.MAIN_HAND;
            boolean mainHasBP = player.getMainHandItem().getItem() instanceof SpatialBlueprintItem
                    || player.getMainHandItem().getItem() instanceof ProjectionBlueprintItem
                    || player.getMainHandItem().getItem() instanceof EngineeringBlueprintItem;
            boolean offHasBP  = player.getOffhandItem().getItem()  instanceof SpatialBlueprintItem
                    || player.getOffhandItem().getItem()  instanceof ProjectionBlueprintItem
                    || player.getOffhandItem().getItem()  instanceof EngineeringBlueprintItem;
            if (!mainHasBP && offHasBP) {
                targetHand = InteractionHand.OFF_HAND;
            }

            // ── 根据分类（或持有物品）创建对应物品 ──────────────────────
            boolean holdingEngineering =
                    player.getItemInHand(targetHand).getItem() instanceof EngineeringBlueprintItem;

            // 持有工程蓝图时：若源文件不是 engineering 分类，
            // 复制全部结构/方块数据到 engineering/ 子目录，生成独立工程文件。
            String effectiveBpId = msg.blueprintId;
            if (holdingEngineering && !"engineering".equals(data.category)) {
                data.category = "engineering";
                java.util.UUID newId = java.util.UUID.randomUUID();
                BlueprintStorage.save(newId, data);
                effectiveBpId = newId.toString();
            }

            net.minecraft.world.item.Item targetItem = holdingEngineering
                    ? SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get()
                    : switch (data.category == null ? "" : data.category) {
                        case "project", "template" -> SpatialBlueprintItems.PROJECTION_BLUEPRINT.get();
                        case "dimensional"  -> SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get();
                        case "engineering"  -> SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get();
                        default             -> SpatialBlueprintItems.SPATIAL_BLUEPRINT.get();
                    };
            ItemStack newStack = new ItemStack(targetItem);
            net.minecraft.nbt.CompoundTag nTag = newStack.getOrCreateTag();
            nTag.putString("blueprintName", data.name);
            nTag.putString("blueprintId",   effectiveBpId);
            if (data.animDuration > 0) nTag.putInt("animDuration", data.animDuration);

            // 次元蓝图：捕捉时方块已从世界清除（材料已由玩家支付），从库重新应用时恢复满额存入记录
            if (targetItem == SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get()
                    && data.materialList != null && !data.materialList.blockCounts.isEmpty()) {
                net.minecraft.nbt.CompoundTag depTag = new net.minecraft.nbt.CompoundTag();
                for (java.util.Map.Entry<String, Integer> entry : data.materialList.blockCounts.entrySet()) {
                    depTag.putInt(entry.getKey(), entry.getValue());
                }
                nTag.put("depositedMaterials", depTag);
            }

            // ── 工程蓝图：写入固定坐标绑定数据 ─────────────────────────────
            if (targetItem == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get()
                    && !data.fixedDimension.isEmpty()
                    && data.fixedStartPos != null && data.fixedEndPos != null) {
                EngineeringBlueprintItem.setBoundProject(newStack, effectiveBpId,
                        data.fixedDimension, data.fixedStartPos, data.fixedEndPos,
                        data.maxDistance >= 0 ? data.maxDistance : 64);
            }

            player.setItemInHand(targetHand, newStack);
            ItemSyncUtil.syncHand(player, targetHand);

            // 主动推送蓝图数据到客户端缓存，使其无需手动点击"下载数据"
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_BlueprintDataMessage(effectiveBpId, data));
            sendResult(player, true, "");
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey));
    }
}
