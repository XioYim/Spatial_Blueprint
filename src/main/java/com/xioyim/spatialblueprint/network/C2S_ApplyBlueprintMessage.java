package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.BlueprintData;
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
 * 客户端 → 服务端：从蓝图库应用指定蓝图，替换玩家手中的蓝图物品。
 *
 * <p>物品类型只由蓝图的 {@code category} 决定，与手里拿的是哪种蓝图无关：
 * <ul>
 *   <li>{@code "project"} / {@code "template"} → {@link ProjectionBlueprintItem}</li>
 *   <li>{@code "dimensional"} → 次元蓝图（仅主人）</li>
 *   <li>{@code "engineering"} → {@link EngineeringBlueprintItem}（带固定坐标绑定）</li>
 *   <li>其余（{@code "create"}、旧格式空字符串）→ {@link SpatialBlueprintItem}</li>
 * </ul>
 * 优先替换主手（空白 / 空间 / 投影 / 工程蓝图之一），主手不是蓝图而副手是时替副手。</p>
 */
public class C2S_ApplyBlueprintMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_ApplyBlueprintMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_apply_blueprint_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ApplyBlueprintMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_ApplyBlueprintMessage::encode, C2S_ApplyBlueprintMessage::decode);

    private final String blueprintId;

    public C2S_ApplyBlueprintMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    @Override
    public CustomPacketPayload.Type<C2S_ApplyBlueprintMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_ApplyBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_ApplyBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_ApplyBlueprintMessage(buf.readUtf(64));
    }

    public static void handle(C2S_ApplyBlueprintMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_ApplyBlueprintMessage msg, ServerPlayer player) {
        if (player == null) return;
        // 应用操作不限创造模式

        // ── 加载蓝图数据 ──────────────────────────────────────────────
        BlueprintData data = BlueprintStorage.load(msg.blueprintId);
        if (data == null) {
            sendResult(player, false, "spatial_blueprint.error.no_blueprint_data");
            return;
        }

        // ── 先找到合适的手 ────────────────────────────────────────────
        // 蓝图库只能从选点 / 放置 / 工程三个界面打开，主手必是空白 / 空间 / 次元 /
        // 投影 / 奖励 / 工程蓝图之一；空白蓝图也要算进来，否则主手拿空白、副手恰好
        // 拿着别的蓝图时会误替副手。SpatialBlueprintItem 是 DimensionalBlueprintItem
        // 的父类、ProjectionBlueprintItem 是 RewardBlueprintItem 的父类，instanceof 自动覆盖。
        InteractionHand targetHand = replaceTargetHand(player);

        // ── 已放置的次元蓝图禁止取出 ──────────────────────────────────
        // 结构已经还原回世界、材料已从次元袋扣除，此时再取出一份就是白嫖同一份结构。
        if (data.placed) {
            sendResult(player, false, "spatial_blueprint.error.blueprint_already_placed");
            return;
        }

        // ── 次元蓝图归属校验：任何人（含 OP）都不能取出别人的 ──────────────
        // 次元蓝图与主人的次元袋一一绑定；别人取走后材料仍锁在原主人的袋子里，
        // 蓝图归属与材料空间会彻底对不上。无主的旧蓝图同样不允许取出。
        if ("dimensional".equals(data.category) && !data.isOwnedBy(player.getUUID())) {
            sendResult(player, false, "spatial_blueprint.message.void_blueprint.not_owner");
            return;
        }

        // ── 物品类型只看所选蓝图的分类，与手里拿的是什么无关 ──────────────
        // 曾有一条「持工程蓝图时把任意分类复制成一份新 engineering 文件」的路：绕过报告页
        // 与净化、不带固定坐标、还占工程槽位，玩家拿到的是一枚「未绑定工程坐标」的废件。
        // 工程蓝图只能经 C2S_CreateEngineeringBlueprintMessage（报告 → 创建页）产生，
        // 这里只负责把已有文件换到手上。
        net.minecraft.world.item.Item targetItem =
                switch (data.category == null ? "" : data.category) {
                    case "project", "template" -> SpatialBlueprintItems.PROJECTION_BLUEPRINT.get();
                    case "dimensional"  -> SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get();
                    case "engineering"  -> SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get();
                    default             -> SpatialBlueprintItems.SPATIAL_BLUEPRINT.get();
                };
        String effectiveBpId = msg.blueprintId;
        ItemStack newStack = new ItemStack(targetItem);
        // 1.20.1 的 getOrCreateTag + putString("blueprintName"/"blueprintId") + putInt("animDuration")
        // 在 1.21 一律走物品类 public static 访问器（C12，组件由访问器统一写）
        AbstractBlueprintItem.setBlueprintName(newStack, data.name);
        AbstractBlueprintItem.setBlueprintId(newStack, effectiveBpId);
        SpatialBlueprintItem.setSaveMode(newStack, data.saveMode);
        if (data.animDuration > 0) SpatialBlueprintItem.setAnimDuration(newStack, data.animDuration);

        // 次元蓝图无需在此恢复"已存入"记录：材料存在世界级材料空间的次元袋里，
        // 按蓝图 ID 绑定，重新应用时 blueprintId 不变，袋子自然还在。
        // （旧版会往物品 NBT 写 depositedMaterials，该字段已随材料空间重构废弃。）

        // ── 工程蓝图：写入固定坐标绑定数据 ─────────────────────────────
        if (targetItem == SpatialBlueprintItems.ENGINEERING_BLUEPRINT.get()
                && !data.fixedDimension.isEmpty()
                && data.fixedStartPos != null && data.fixedEndPos != null) {
            EngineeringBlueprintItem.setBoundProject(newStack, effectiveBpId,
                    data.fixedDimension, data.fixedStartPos, data.fixedEndPos);
        }

        player.setItemInHand(targetHand, newStack);
        ItemSyncUtil.syncHand(player, targetHand);

        // 主动推送蓝图数据到客户端缓存，使其无需手动点击"下载数据"
        BlueprintNetwork.sendBlueprintTo(player, effectiveBpId, data);
        sendResult(player, true, "");
    }

    /**
     * 「换手上的物品」类操作（蓝图库 ✔ 应用、项目浏览 [规划项目]）要替哪只手：
     * 主手是蓝图类物品就替主手；主手不是而副手是 → 替副手；都不是 → 主手。
     * 这些界面都只能从手持蓝图的界面打开，所以正常走不到「都不是」。
     */
    public static InteractionHand replaceTargetHand(ServerPlayer player) {
        return !isBlueprintLike(player.getMainHandItem()) && isBlueprintLike(player.getOffhandItem())
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** 可被替换的手持物：空白 / 空间（含次元）/ 投影（含奖励）/ 工程 / 项目（公用 / 自用）蓝图。 */
    private static boolean isBlueprintLike(ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        return item instanceof BlankBlueprintItem
                || item instanceof SpatialBlueprintItem
                || item instanceof ProjectionBlueprintItem
                || item instanceof EngineeringBlueprintItem
                || item instanceof com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.sendToPlayer(player, new S2C_OperationResult(success, messageKey));
    }
}
