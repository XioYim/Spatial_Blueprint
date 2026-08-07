package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.item.AbstractBlueprintItem;
import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：投影蓝图材料存入 / 取出操作。
 *
 * <ul>
 *   <li>{@link #OP_QUICK_DEPOSIT}  — 一键将背包中所有需要的材料存入蓝图</li>
 *   <li>{@link #OP_QUICK_WITHDRAW} — 一键将蓝图中已存入的全部材料退回背包</li>
 *   <li>{@link #OP_DEPOSIT}        — 将指定方块的指定数量存入蓝图</li>
 *   <li>{@link #OP_WITHDRAW}       — 将指定方块的指定数量从蓝图取出</li>
 * </ul>
 */
public class C2S_MaterialDepositMessage {

    public static final int OP_QUICK_DEPOSIT  = 0;
    public static final int OP_QUICK_WITHDRAW = 1;
    public static final int OP_DEPOSIT        = 2;
    public static final int OP_WITHDRAW       = 3;
    /** OP 专属：清空全部已存入材料，不返还物品。 */
    public static final int OP_CLEAR_MATERIALS = 4;

    private final int    operation;
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

    public static void handle(C2S_MaterialDepositMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // ── 找手中的投影蓝图或工程蓝图 ───────────────────────────────
            ItemStack stack = null;
            InteractionHand hand = null;
            ItemStack main = player.getMainHandItem();
            ItemStack off  = player.getOffhandItem();
            if (main.getItem() instanceof ProjectionBlueprintItem
                    || main.getItem() instanceof EngineeringBlueprintItem
                    || main.getItem() instanceof DimensionalBlueprintItem) {
                stack = main; hand = InteractionHand.MAIN_HAND;
            } else if (off.getItem() instanceof ProjectionBlueprintItem
                    || off.getItem() instanceof EngineeringBlueprintItem
                    || off.getItem() instanceof DimensionalBlueprintItem) {
                stack = off; hand = InteractionHand.OFF_HAND;
            }
            if (stack == null) return;

            MaterialList ml = ProjectionBlueprintItem.getMaterialList(stack);
            if (ml == null) return;

            switch (msg.operation) {
                case OP_QUICK_DEPOSIT   -> quickDeposit(player, stack, ml);
                case OP_QUICK_WITHDRAW  -> quickWithdraw(player, stack, ml);
                case OP_DEPOSIT         -> depositOne(player, stack, ml, msg.blockId, msg.amount);
                case OP_WITHDRAW        -> withdrawOne(player, stack, msg.blockId, msg.amount);
                case OP_CLEAR_MATERIALS -> { if (player.hasPermissions(2) && player.isCreative()) clearMaterials(stack); }
            }

            // ── Bug-1 fix: restore client-only rendering state before syncing ─────────
            // The client embeds alignMode / rotation / offsets so the ItemStack returned
            // via ClientboundContainerSetSlotPacket still has them — preventing the
            // renderer from switching to AlignMode.OFF and the preview from disappearing.
            SpatialBlueprintItem.setAlignModeStr(stack, msg.alignMode);
            try {
                SpatialBlueprintItem.setRotation(stack,
                        net.minecraft.world.level.block.Rotation.valueOf(msg.rotation));
            } catch (IllegalArgumentException ignored) {}
            SpatialBlueprintItem.setXOffset(stack, msg.xOffset);
            SpatialBlueprintItem.setYOffset(stack, msg.yOffset);
            SpatialBlueprintItem.setZOffset(stack, msg.zOffset);

            ItemSyncUtil.syncHand(player, hand);
        });
        ctx.get().setPacketHandled(true);
    }

    // ── 操作实现 ──────────────────────────────────────────────────────────

    private static void quickDeposit(ServerPlayer player, ItemStack stack, MaterialList ml) {
        for (Map.Entry<String, Integer> entry : ml.blockCounts.entrySet()) {
            String blockId = entry.getKey();
            int needed    = entry.getValue();
            int deposited = ProjectionBlueprintItem.getDepositedCount(stack, blockId);
            int stillNeed = needed - deposited;
            if (stillNeed <= 0) continue;

            if (player.isCreative()) {
                ProjectionBlueprintItem.setDepositedCount(stack, blockId, needed);
            } else {
                net.minecraft.world.item.Item item = findItem(blockId);
                if (item == null) continue;
                int taken = takeFromInventory(player, item, stillNeed);
                if (taken > 0) {
                    ProjectionBlueprintItem.setDepositedCount(stack, blockId, deposited + taken);
                }
            }
        }
    }

    private static void quickWithdraw(ServerPlayer player, ItemStack stack, MaterialList ml) {
        Map<String, Integer> deposited = ProjectionBlueprintItem.getDepositedMaterials(stack);

        // 筛出实际有存量的条目
        long types = deposited.entrySet().stream().filter(e -> e.getValue() > 0).count();
        if (types == 0) return; // 没有存量，直接跳过

        if (!player.isCreative()) {
            // ── 取出次数限制 ──────────────────────────────────────────────
            int effectiveLimit = ProjectionBlueprintItem.getCustomWithdrawLimit(stack);
            if (effectiveLimit < 0) effectiveLimit = BlueprintConfig.MAX_WITHDRAW_COUNT.get();
            int currentCount = ProjectionBlueprintItem.getWithdrawCount(stack);
            if (currentCount >= effectiveLimit) {
                sendResult(player, false, "spatial_blueprint.error.withdraw_limit_reached");
                return;
            }

            // ── 经验等级限制 ──────────────────────────────────────────────
            int xpCost = ProjectionBlueprintItem.getCustomWithdrawXp(stack);
            if (xpCost < 0) {
                int ratio = BlueprintConfig.WITHDRAW_XP_RATIO.get();
                if (ratio == 0) {
                    xpCost = 0; // ratio=0 → 免费取出
                } else {
                    xpCost = Math.max(1, ((int) types + ratio - 1) / ratio); // ceil(types/ratio)
                    xpCost = Math.min(xpCost, BlueprintConfig.MAX_WITHDRAW_XP_COST.get());
                }
            }
            if (xpCost > 0 && player.experienceLevel < xpCost) {
                sendResult(player, false, "spatial_blueprint.error.withdraw_xp_insufficient");
                return;
            }

            // ── 执行取出：两阶段提交（#15）──────────────────────────────────
            // Phase 1: return all items first.  If the server crashes mid-loop the
            // deposited counts are still intact, so the player can safely retry.
            for (Map.Entry<String, Integer> entry : deposited.entrySet()) {
                int amount = entry.getValue();
                if (amount <= 0) continue;
                returnToInventory(player, entry.getKey(), amount);
            }
            // Phase 2: only after all items are safely in the inventory, clear counts,
            // deduct XP, and bump the withdrawal counter.
            for (Map.Entry<String, Integer> entry : deposited.entrySet()) {
                if (entry.getValue() > 0)
                    ProjectionBlueprintItem.setDepositedCount(stack, entry.getKey(), 0);
            }
            // 扣除经验
            player.giveExperienceLevels(-xpCost);
            // 递增取出次数
            ProjectionBlueprintItem.incrementWithdrawCount(stack);
        } else {
            // 创造模式：无限制，不返还物品（物品本来就不从创造背包扣除）
            for (Map.Entry<String, Integer> entry : deposited.entrySet()) {
                int amount = entry.getValue();
                if (amount <= 0) continue;
                ProjectionBlueprintItem.setDepositedCount(stack, entry.getKey(), 0);
            }
        }
    }

    private static void depositOne(ServerPlayer player, ItemStack stack, MaterialList ml,
                                   String blockId, int amount) {
        if (amount <= 0) return;
        int needed    = ml.blockCounts.getOrDefault(blockId, 0);
        if (needed <= 0) return;
        int deposited = ProjectionBlueprintItem.getDepositedCount(stack, blockId);
        int canAccept = needed - deposited;
        if (canAccept <= 0) return;

        int toDeposit = Math.min(amount, canAccept);

        if (player.isCreative()) {
            ProjectionBlueprintItem.setDepositedCount(stack, blockId, deposited + toDeposit);
            return;
        }

        net.minecraft.world.item.Item item = findItem(blockId);
        if (item == null) return;
        int taken = takeFromInventory(player, item, toDeposit);
        if (taken > 0) {
            ProjectionBlueprintItem.setDepositedCount(stack, blockId, deposited + taken);
        }
    }

    private static void withdrawOne(ServerPlayer player, ItemStack stack,
                                    String blockId, int amount) {
        // 生存模式禁止逐条取出（仅创造模式可用）
        if (!player.isCreative()) return;
        if (amount <= 0) return;
        int deposited = ProjectionBlueprintItem.getDepositedCount(stack, blockId);
        if (deposited <= 0) return;

        int toWithdraw = Math.min(amount, deposited);
        // 创造模式：不返还物品（物品本来就不从创造背包扣除），直接清空存量记录
        ProjectionBlueprintItem.setDepositedCount(stack, blockId, deposited - toWithdraw);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    @Nullable
    private static net.minecraft.world.item.Item findItem(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return null;
        var blockOpt = BuiltInRegistries.BLOCK.getOptional(rl);
        if (blockOpt.isPresent()) {
            net.minecraft.world.item.Item bi = blockOpt.get().asItem();
            if (bi != Items.AIR) return bi;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    /** 从玩家背包中消耗指定物品最多 {@code amount} 个，返回实际消耗数量。 */
    private static int takeFromInventory(ServerPlayer player,
                                         net.minecraft.world.item.Item item, int amount) {
        int remaining = amount;
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize() && remaining > 0; slot++) {
            ItemStack slotStack = inv.getItem(slot);
            if (!slotStack.isEmpty() && slotStack.is(item)) {
                int take = Math.min(slotStack.getCount(), remaining);
                slotStack.shrink(take);
                remaining -= take;
            }
        }
        return amount - remaining;
    }

    /** 将物品返回玩家背包；若背包满则丢弃到地面。 */
    private static void returnToInventory(ServerPlayer player, String blockId, int amount) {
        net.minecraft.world.item.Item item = findItem(blockId);
        if (item == null || item == Items.AIR) return;

        int remaining = amount;
        int maxStack  = item.getMaxStackSize();
        while (remaining > 0) {
            int batch       = Math.min(remaining, maxStack);
            ItemStack toAdd = new ItemStack(item, batch);
            if (!player.getInventory().add(toAdd)) {
                player.drop(toAdd, false);
            }
            remaining -= batch;
        }
    }

    private static void clearMaterials(ItemStack stack) {
        AbstractBlueprintItem.clearDepositedMaterials(stack);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey));
    }
}
