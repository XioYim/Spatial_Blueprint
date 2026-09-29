package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.util.MaterialKey;
import com.xioyim.spatialblueprint.util.MaterialList;
import com.xioyim.spatialblueprint.util.MaterialSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;

/**
 * 客户端 → 服务端：材料存入操作。
 *
 * <ul>
 *   <li>{@link #OP_QUICK_DEPOSIT}   — 一键将背包中当前蓝图所需的材料存入材料空间</li>
 *   <li>{@link #OP_DEPOSIT}         — 将指定材料的指定数量存入材料空间</li>
 * </ul>
 *
 * <p>材料存入的是玩家的<b>材料空间普通池</b>（{@link MaterialSpace}），而非蓝图物品 NBT。
 * 存入按<b>当前手持蓝图的材料清单</b>过滤，清单之外的物品一律不收。</p>
 *
 * <p>创造模式放置时完全跳过材料校验，因此创造模式下的存入操作直接忽略 —— 不会往池里凭空塞货。</p>
 *
 * <p>取出功能已整体移除，后续将以严格机制重做。次元袋的材料永久不可取出。</p>
 */
public class C2S_MaterialDepositMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_MaterialDepositMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_material_deposit_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_MaterialDepositMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_MaterialDepositMessage::encode, C2S_MaterialDepositMessage::decode);

    public static final int OP_QUICK_DEPOSIT   = 0;
    public static final int OP_DEPOSIT         = 2;

    private final int    operation;
    /** {@link MaterialKey} 复合键。 */
    private final String blockId;
    private final int    amount;

    // ── Client-only rendering state — server writes these back before ItemSyncUtil.syncHand()
    // so the synced ItemStack retains alignMode / offsets / rotation (Bug-1 fix).
    /** Current AlignMode name on the client (e.g. "CENTER").  "OFF" if no preview active. */
    private final String alignMode;
    /** Current rotation name (e.g. "CLOCKWISE_90").  "NONE" if no rotation. */
    private final String rotation;
    private final int xOffset;
    private final int yOffset;
    private final int zOffset;

    /** Convenience constructor used when there is no preview / offsets to preserve. */
    public C2S_MaterialDepositMessage(int operation, String blockId, int amount) {
        this(operation, blockId, amount, "OFF", "NONE", 0, 0, 0);
    }

    /** Full constructor — includes client-side rendering state so it survives the server sync. */
    public C2S_MaterialDepositMessage(int operation, String blockId, int amount,
                                      String alignMode, String rotation,
                                      int xOffset, int yOffset, int zOffset) {
        this.operation = operation;
        this.blockId   = blockId;
        this.amount    = amount;
        this.alignMode = alignMode;
        this.rotation  = rotation;
        this.xOffset   = xOffset;
        this.yOffset   = yOffset;
        this.zOffset   = zOffset;
    }

    @Override
    public CustomPacketPayload.Type<C2S_MaterialDepositMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_MaterialDepositMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.operation);
        buf.writeUtf(msg.blockId, 512);
        buf.writeInt(msg.amount);
        buf.writeUtf(msg.alignMode, 16);
        buf.writeUtf(msg.rotation,  32);
        buf.writeInt(msg.xOffset);
        buf.writeInt(msg.yOffset);
        buf.writeInt(msg.zOffset);
    }

    public static C2S_MaterialDepositMessage decode(FriendlyByteBuf buf) {
        int    op        = buf.readInt();
        String blockId   = buf.readUtf(512);
        int    amount    = buf.readInt();
        String alignMode = buf.readUtf(16);
        String rotation  = buf.readUtf(32);
        int    xOff      = buf.readInt();
        int    yOff      = buf.readInt();
        int    zOff      = buf.readInt();
        return new C2S_MaterialDepositMessage(op, blockId, amount, alignMode, rotation, xOff, yOff, zOff);
    }

    public static void handle(C2S_MaterialDepositMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_MaterialDepositMessage msg, ServerPlayer player) {
        if (player == null) return;

        MaterialSpace space = MaterialSpace.get(player.serverLevel());

        // ── 找手中的投影蓝图 / 工程蓝图 / 次元蓝图 ─────────────────────
        ItemStack stack = null;
        InteractionHand hand = null;
        ItemStack main = player.getMainHandItem();
        ItemStack off  = player.getOffhandItem();
        if (isBlueprint(main)) {
            stack = main; hand = InteractionHand.MAIN_HAND;
        } else if (isBlueprint(off)) {
            stack = off; hand = InteractionHand.OFF_HAND;
        }
        if (stack == null) return;

        MaterialList ml = ProjectionBlueprintItem.getMaterialListServer(stack);
        if (ml == null) return;

        // 创造模式放置不校验材料，故不接受存入 —— 否则等于凭空往池里塞货
        if (player.isCreative()) return;

        switch (msg.operation) {
            case OP_QUICK_DEPOSIT -> quickDeposit(player, space, ml);
            case OP_DEPOSIT       -> depositOne(player, space, ml, msg.blockId, msg.amount);
            default               -> { return; }
        }

        S2C_MaterialSpaceSyncMessage.sendFor(player, ml);

        // ── Bug-1 fix: restore client-only rendering state before syncing ─────────
        // The client embeds alignMode / rotation / offsets so the ItemStack returned
        // via ClientboundContainerSetSlotPacket still has them — preventing the
        // renderer from switching to AlignMode.OFF and the preview from disappearing.
        // E1: 回写前与 UpdateOffsetMessage 同口径 —— 偏移走 clampOffset，alignMode 白名单，
        // 改包夹带的超大偏移 / 非法字符串落不了盘（放置距离校验照常在放置时兜底）。
        String mode = msg.alignMode;
        if (!"OFF".equals(mode) && !"CENTER".equals(mode)
                && !"START".equals(mode) && !"END".equals(mode)) {
            mode = "OFF";
        }
        SpatialBlueprintItem.setAlignModeStr(stack, mode);
        try {
            SpatialBlueprintItem.setRotation(stack,
                    net.minecraft.world.level.block.Rotation.valueOf(msg.rotation));
        } catch (IllegalArgumentException ignored) {}
        SpatialBlueprintItem.setXOffset(stack, BlueprintValidator.clampOffset(msg.xOffset));
        SpatialBlueprintItem.setYOffset(stack, BlueprintValidator.clampOffset(msg.yOffset));
        SpatialBlueprintItem.setZOffset(stack, BlueprintValidator.clampOffset(msg.zOffset));

        ItemSyncUtil.syncHand(player, hand);
    }

    private static boolean isBlueprint(ItemStack stack) {
        return stack.getItem() instanceof ProjectionBlueprintItem
                || stack.getItem() instanceof EngineeringBlueprintItem
                || stack.getItem() instanceof DimensionalBlueprintItem;
    }

    // ── 操作实现 ──────────────────────────────────────────────────────────

    /** 一键存入：按当前蓝图清单，存到刚好满足即停。 */
    private static void quickDeposit(ServerPlayer player, MaterialSpace space, MaterialList ml) {
        boolean hitCap = false;
        for (Map.Entry<String, Integer> entry : ml.blockCounts.entrySet()) {
            String key = entry.getKey();
            int stillNeed = entry.getValue() - space.count(player.getUUID(), key);
            if (stillNeed <= 0) continue;

            int taken = takeFromInventory(player, key, stillNeed);
            if (taken <= 0) continue;

            int accepted = space.deposit(player.getUUID(), key, taken, ml.tagFor(key));
            if (accepted < taken) {
                // 触顶：把没收下的部分退回背包，避免物品凭空消失
                returnToInventory(player, key, taken - accepted, ml.tagFor(key));
                hitCap = true;
                break;
            }
        }
        if (hitCap) sendResult(player, false, "spatial_blueprint.error.material_space_full");
    }

    /** 单项存入指定数量；上限为「当前蓝图还需要的量」。 */
    private static void depositOne(ServerPlayer player, MaterialSpace space, MaterialList ml,
                                   String key, int amount) {
        if (amount <= 0) return;
        int needed = ml.blockCounts.getOrDefault(key, 0);
        if (needed <= 0) return; // 清单之外的物品不收
        int canAccept = needed - space.count(player.getUUID(), key);
        if (canAccept <= 0) return;

        int toDeposit = Math.min(amount, canAccept);
        int taken = takeFromInventory(player, key, toDeposit);
        if (taken <= 0) return;

        int accepted = space.deposit(player.getUUID(), key, taken, ml.tagFor(key));
        if (accepted < taken) {
            returnToInventory(player, key, taken - accepted, ml.tagFor(key));
            sendResult(player, false, "spatial_blueprint.error.material_space_full");
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    /**
     * 从玩家背包中消耗与复合键<b>完全匹配</b>的物品最多 {@code amount} 个，返回实际消耗数量。
     *
     * <p>匹配基于 {@link MaterialKey#of(ItemStack, net.minecraft.core.HolderLookup.Provider)}，
     * 即物品类型 + 规范化 NBT。附魔、耐久
     * 不同的同种物品会得到不同的键，因此不会互相顶替。</p>
     */
    private static int takeFromInventory(ServerPlayer player, String key, int amount) {
        int remaining = amount;
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            // L2：跳过盔甲槽 36-39（0-35 背包 + 40 副手照常收），免得把戴着的南瓜头扒下来
            if (slot >= 36 && slot <= 39) continue;
            ItemStack slotStack = inv.getItem(slot);
            if (slotStack.isEmpty()) continue;
            if (!key.equals(MaterialKey.of(slotStack, player.registryAccess()))) continue;
            int take = Math.min(slotStack.getCount(), remaining);
            slotStack.shrink(take);
            remaining -= take;
        }
        return amount - remaining;
    }

    /** 把未被材料空间收下的物品退回背包；背包满则丢到地面。 */
    private static void returnToInventory(ServerPlayer player, String key, int amount,
                                          net.minecraft.nbt.CompoundTag tag) {
        if (amount <= 0) return;
        ItemStack template = MaterialList.buildDisplayStack(key, tag, player.registryAccess());
        if (template.isEmpty()) return;

        int remaining = amount;
        int maxStack  = template.getMaxStackSize();
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            ItemStack toAdd = template.copy();
            toAdd.setCount(batch);
            if (!player.getInventory().add(toAdd)) {
                player.drop(toAdd, false);
            }
            remaining -= batch;
        }
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.sendToPlayer(player,
                new S2C_OperationResult(success, messageKey));
    }
}
