package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

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
     * 服务端读法：<b>文件是唯一权威</b>，没有再回退内联 NBT。
     *
     * <p>服务端逻辑一律走这条，包括单人与局域网主机的集成服务器 —— 那两种情况下
     * 服务端跑在客户端进程里，但它要读的仍然是自己硬盘上的文件，不是某个玩家的内存缓存。</p>
     */
    @Nullable
    public static BlueprintData getBlueprintDataServer(ItemStack stack) {
        if (!stack.hasTag()) return null;
        var tag = stack.getTag();
        if (tag.contains("blueprintId")) {
            BlueprintData fromFile = BlueprintStorage.load(tag.getString("blueprintId"));
            if (fromFile != null) return fromFile;
        }
        return readInlineData(tag);
    }

    /**
     * 客户端读法：只认 S2C 包填进来的内存缓存。
     *
     * <p>客户端在多人游戏里没有蓝图文件，因此<b>永远不读本地文件</b>。
     * 带 blueprintId 却没缓存时返回 null（界面会显示「下载数据」按钮），
     * 不回退内联 NBT —— 带 id 的蓝图早就不内联存数据了。</p>
     *
     * <p>只能在客户端线程调用。方法体里引用了 {@code ClientBlueprintCache}，
     * 专用服务端只要不执行到就不会去加载那个类。</p>
     */
    @Nullable
    public static BlueprintData getBlueprintDataClient(ItemStack stack) {
        if (!stack.hasTag()) return null;
        var tag = stack.getTag();
        if (tag.contains("blueprintId")) {
            return com.xioyim.spatialblueprint.client.ClientBlueprintCache
                    .get(tag.getString("blueprintId"));
        }
        return readInlineData(tag);
    }

    /** 内联 NBT 回退。反序列化失败时记 warn 并返回 null，不吞掉异常。 */
    @Nullable
    private static BlueprintData readInlineData(net.minecraft.nbt.CompoundTag tag) {
        if (!tag.contains("blueprintData")) return null;
        try {
            return BlueprintData.deserialize(tag.getCompound("blueprintData"));
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn(
                    "[AbstractBlueprintItem] Failed to deserialize inline blueprintData: {}",
                    e.getMessage());
            return null;
        }
    }

    // ── 材料清单（内嵌于 BlueprintData）──────────────────────────────────────

    /** 服务端：从文件读出的清单。扣料 / 校验一律走这条。 */
    @Nullable
    public static MaterialList getMaterialListServer(ItemStack stack) {
        BlueprintData data = getBlueprintDataServer(stack);
        return data != null ? data.materialList : null;
    }

    /** 客户端：从内存缓存读出的清单，只用于界面显示。 */
    @Nullable
    public static MaterialList getMaterialListClient(ItemStack stack) {
        BlueprintData data = getBlueprintDataClient(stack);
        return data != null ? data.materialList : null;
    }

    /**
     * tooltip 专用：按<b>物理端</b>选缓存或文件。
     *
     * <p>这里用物理端判断是对的，别照抄到别处 —— tooltip 是纯客户端功能，专用服务端
     * 根本不渲染它。真有别的 mod 在专服上问一枚蓝图的 tooltip，走文件读也答得出来，
     * 不会因为碰 {@code ClientBlueprintCache} 而 {@code NoClassDefFoundError}。</p>
     *
     * <p>服务端的扣料 / 校验<b>不能</b>用这个，必须显式走
     * {@link #getMaterialListServer} —— 那正是 H3 修掉的坑：拿物理端当逻辑端用，
     * 单人和局域网主机的集成服务器会跑进客户端分支。</p>
     */
    @Nullable
    public static MaterialList getMaterialListForTooltip(ItemStack stack) {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()
                ? getMaterialListClient(stack)
                : getMaterialListServer(stack);
    }

    // ── 已存入材料 ────────────────────────────────────────────────────────────
    // 已迁移至世界级 MaterialSpace（按玩家 UUID 分表），不再存于物品 NBT。
    // 服务端查询走 MaterialSpace.count() / canAfford()，客户端显示走 ClientMaterialSpaceCache。
    // 这样材料不再随蓝图物品丢失，且多个蓝图共用同一个池。

}
