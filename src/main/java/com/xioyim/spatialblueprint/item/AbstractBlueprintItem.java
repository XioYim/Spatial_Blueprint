package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 公共基类，消除 {@link ProjectionBlueprintItem} 与 {@link EngineeringBlueprintItem}
 * 之间大量重复的组件辅助方法。
 *
 * <p>所有方法均为静态，因此子类名称仍可直接用于调用（Java 会沿继承链解析静态方法）：
 * {@code ProjectionBlueprintItem.getBlueprintName(stack)} → 实际执行本类方法。</p>
 */
public abstract class AbstractBlueprintItem extends Item {

    protected AbstractBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        LegacyItemMigration.migrate(stack);
    }

    // ── 蓝图名称 ──────────────────────────────────────────────────────────────

    public static String getBlueprintName(ItemStack stack) {
        return stack.getOrDefault(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), "");
    }

    public static void setBlueprintName(ItemStack stack, String name) {
        stack.set(SpatialBlueprintComponents.BLUEPRINT_NAME.get(), name);
    }

    // ── 蓝图 ID ───────────────────────────────────────────────────────────────

    @Nullable
    public static String getBlueprintId(ItemStack stack) {
        return stack.has(SpatialBlueprintComponents.BLUEPRINT_ID.get())
                ? stack.get(SpatialBlueprintComponents.BLUEPRINT_ID.get()) : null;
    }

    public static void setBlueprintId(ItemStack stack, String id) {
        stack.set(SpatialBlueprintComponents.BLUEPRINT_ID.get(), id);
    }

    // ── 蓝图数据（只认文件，D1）──────────────────────────────────────────────

    /**
     * 服务端读法：<b>文件是唯一权威</b>，没有再回退内联 NBT。
     *
     * <p>服务端逻辑一律走这条，包括单人与局域网主机的集成服务器 —— 那两种情况下
     * 服务端跑在客户端进程里，但它要读的仍然是自己硬盘上的文件，不是某个玩家的内存缓存。</p>
     */
    @Nullable
    public static BlueprintData getBlueprintDataServer(ItemStack stack) {
        String id = getBlueprintId(stack);
        if (id == null || id.isEmpty()) {
            // SB-TODO(P4): 旧物品内联 blueprintData 转存为蓝图文件（D1 / D2，需 DataFixer）
            return null;
        }
        return BlueprintStorage.load(id);
    }

    /**
     * 客户端读法：只认 S2C 包填进来的内存缓存。
     *
     * <p>客户端在多人游戏里没有蓝图文件，因此<b>永远不读本地文件</b>。</p>
     */
    @Nullable
    public static BlueprintData getBlueprintDataClient(ItemStack stack) {
        // SB-TODO(P5): ClientBlueprintCache 读取（S2C 蓝图数据缓存）
        return null;
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
        return net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                ? getMaterialListClient(stack)
                : getMaterialListServer(stack);
    }

    // ── 已存入材料 ────────────────────────────────────────────────────────────
    // 已迁移至世界级 MaterialSpace（按玩家 UUID 分表），不再存于物品数据里。
    // 服务端查询走 MaterialSpace.count() / canAfford()，客户端显示走 ClientMaterialSpaceCache。
    // 这样材料不再随蓝图物品丢失，且多个蓝图共用同一个池。

}
