package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S：客户端打开放置 / 工程界面时请求同步材料空间。
 *
 * <p>已存入数量的权威数据在服务端的 {@code MaterialSpace}，客户端没有本地副本。
 * 服务端只在存入 / 消耗后主动推送增量，因此界面首次打开时必须由客户端主动拉一次，
 * 否则玩家会看到「0/N」而实际上池里已有材料。</p>
 *
 * <p>响应内容按<b>当前手持蓝图的材料清单</b>过滤，不会推送整个池。</p>
 */
public class C2S_RequestMaterialSpaceMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_RequestMaterialSpaceMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_request_material_space_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestMaterialSpaceMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_RequestMaterialSpaceMessage::encode, C2S_RequestMaterialSpaceMessage::decode);

    @Override
    public CustomPacketPayload.Type<C2S_RequestMaterialSpaceMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_RequestMaterialSpaceMessage msg, FriendlyByteBuf buf) {}

    public static C2S_RequestMaterialSpaceMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestMaterialSpaceMessage();
    }

    public static void handle(C2S_RequestMaterialSpaceMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_RequestMaterialSpaceMessage msg, ServerPlayer player) {
        if (player == null) return;

        ItemStack stack = null;
        ItemStack main = player.getMainHandItem();
        ItemStack off  = player.getOffhandItem();
        if (isBlueprint(main))      stack = main;
        else if (isBlueprint(off))  stack = off;
        if (stack == null) return;

        MaterialList ml = ProjectionBlueprintItem.getMaterialListServer(stack);
        if (ml == null) return;

        // 次元蓝图的材料在按蓝图 ID 绑定的次元袋里，不在普通池
        String voidBagId = (stack.getItem() instanceof DimensionalBlueprintItem)
                ? SpatialBlueprintItem.getBlueprintId(stack) : null;
        S2C_MaterialSpaceSyncMessage.sendFor(player, ml, voidBagId);
    }

    private static boolean isBlueprint(ItemStack stack) {
        return stack.getItem() instanceof ProjectionBlueprintItem
                || stack.getItem() instanceof EngineeringBlueprintItem
                || stack.getItem() instanceof DimensionalBlueprintItem;
    }
}
