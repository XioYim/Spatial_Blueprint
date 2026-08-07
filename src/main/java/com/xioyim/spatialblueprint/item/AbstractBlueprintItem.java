package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公共基类，消除 {@link ProjectionBlueprintItem} 与 {@link EngineeringBlueprintItem}
 * 之间大量重复的 NBT 辅助方法。
 *
 * <p>所有方法均为静态，因此子类名称仍可直接用于调用（Java 会沿继承链解析静态方法）：
 * {@code ProjectionBlueprintItem.getBlueprintName(stack)} → 实际执行本类方法。</p>
 */
public abstract class AbstractBlueprintItem extends Item {

    protected AbstractBlueprintItem(Properties properties) {
        super(properties);
    }

    // ── 蓝图名称 ──────────────────────────────────────────────────────────────

    public static String getBlueprintName(ItemStack stack) {
        return (stack.hasTag() && stack.getTag().contains("blueprintName"))
                ? stack.getTag().getString("blueprintName") : "";
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString("blueprintName", name);
    }

    // ── 蓝图 ID ───────────────────────────────────────────────────────────────

    @Nullable
    public static String getBlueprintId(ItemStack stack) {
        return (stack.hasTag() && stack.getTag().contains("blueprintId"))
                ? stack.getTag().getString("blueprintId") : null;
    }

    public static void setBlueprintId(ItemStack stack, String id) {
        stack.getOrCreateTag().putString("blueprintId", id);
    }

    // ── 蓝图数据（优先文件，回退内联 NBT）────────────────────────────────────

    /**
     * 读取完整蓝图数据。服务端优先走文件；多人客户端无文件则走内联 NBT。
     * 反序列化失败时记录 warn 日志并返回 null，不吞掉异常。
     */
    @Nullable
    public static BlueprintData getBlueprintData(ItemStack stack) {
        if (!stack.hasTag()) return null;
        var tag = stack.getTag();

        if (tag.contains("blueprintId")) {
            String id = tag.getString("blueprintId");
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                // Client: only use the in-memory cache populated by S2C packets.
                // Never read local files — client has no blueprint files in multiplayer,
                // and in singleplayer all reads must go through the server.
                return com.xioyim.spatialblueprint.client.ClientBlueprintCache.get(id);
            }
            // Server-side: read from file store.
            BlueprintData fromFile = BlueprintStorage.load(id);
            if (fromFile != null) return fromFile;
        }
        if (tag.contains("blueprintData")) {
            try {
                return BlueprintData.deserialize(tag.getCompound("blueprintData"));
            } catch (Exception e) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[AbstractBlueprintItem] Failed to deserialize inline blueprintData: {}",
                        e.getMessage());
            }
        }
        return null;
    }

    // ── 材料清单（内嵌于 BlueprintData）──────────────────────────────────────

    @Nullable
    public static MaterialList getMaterialList(ItemStack stack) {
        BlueprintData data = getBlueprintData(stack);
        return data != null ? data.materialList : null;
    }

    // ── 已存入材料 NBT ────────────────────────────────────────────────────────

    public static Map<String, Integer> getDepositedMaterials(ItemStack stack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!stack.hasTag()) return result;
        CompoundTag dep = stack.getTag().getCompound("depositedMaterials");
        for (String key : dep.getAllKeys()) {
            int v = dep.getInt(key);
            if (v > 0) result.put(key, v);
        }
        return result;
    }

    public static int getDepositedCount(ItemStack stack, String blockId) {
        if (!stack.hasTag()) return 0;
        CompoundTag tag = stack.getTag();
        if (!tag.contains("depositedMaterials")) return 0;
        return tag.getCompound("depositedMaterials").getInt(blockId);
    }

    public static void setDepositedCount(ItemStack stack, String blockId, int count) {
        CompoundTag dep;
        if (stack.hasTag() && stack.getTag().contains("depositedMaterials")) {
            dep = stack.getTag().getCompound("depositedMaterials").copy();
        } else {
            dep = new CompoundTag();
        }
        if (count <= 0) dep.remove(blockId);
        else dep.putInt(blockId, count);
        stack.getOrCreateTag().put("depositedMaterials", dep);
    }

    public static void clearDepositedMaterials(ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove("depositedMaterials");
    }

    public static boolean hasSufficientMaterials(ItemStack stack, MaterialList ml) {
        if (ml == null || ml.blockCounts.isEmpty()) return true;
        for (Map.Entry<String, Integer> entry : ml.blockCounts.entrySet()) {
            if (getDepositedCount(stack, entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    // ── 取出次数 & 自定义配置 NBT ─────────────────────────────────────────────

    public static int getWithdrawCount(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt("withdrawCount") : 0;
    }

    public static void setWithdrawCount(ItemStack stack, int count) {
        stack.getOrCreateTag().putInt("withdrawCount", count);
    }

    public static int incrementWithdrawCount(ItemStack stack) {
        int n = getWithdrawCount(stack) + 1;
        setWithdrawCount(stack, n);
        return n;
    }

    public static int getCustomWithdrawXp(ItemStack stack) {
        return (stack.hasTag() && stack.getTag().contains("customWithdrawXp"))
                ? stack.getTag().getInt("customWithdrawXp") : -1;
    }

    public static void setCustomWithdrawXp(ItemStack stack, int xp) {
        if (xp < 0) { if (stack.hasTag()) stack.getTag().remove("customWithdrawXp"); }
        else stack.getOrCreateTag().putInt("customWithdrawXp", xp);
    }

    public static int getCustomWithdrawLimit(ItemStack stack) {
        return (stack.hasTag() && stack.getTag().contains("customWithdrawLimit"))
                ? stack.getTag().getInt("customWithdrawLimit") : -1;
    }

    public static void setCustomWithdrawLimit(ItemStack stack, int limit) {
        if (limit < 0) { if (stack.hasTag()) stack.getTag().remove("customWithdrawLimit"); }
        else stack.getOrCreateTag().putInt("customWithdrawLimit", limit);
    }
}
