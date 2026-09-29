package com.xioyim.spatialblueprint.compat.jei;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

/**
 * 界面代码调用 JEI 的入口 —— <b>本类不引用任何 {@code mezz.jei.*} 类型</b>。
 *
 * <p>JEI 是 {@code compileOnly} 依赖，运行时可能不存在。所有真正接触 JEI API 的代码
 * 都在 {@link JeiBridge} 里，本类先用 {@link ModList} 判断 JEI 是否加载，
 * 只有加载了才去碰那个类 —— 否则 JVM 解析 {@code JeiBridge} 时就会
 * {@code NoClassDefFoundError}。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SBJeiSupport {

    /** 缓存 JEI 是否加载：ModList 查询不算贵，但这是每帧都可能问到的判断。 */
    private static Boolean loaded = null;

    private SBJeiSupport() {}

    /** JEI 是否可用（已安装且运行时就绪）。 */
    public static boolean isAvailable() {
        if (loaded == null) {
            loaded = ModList.get() != null && ModList.get().isLoaded("jei");
        }
        if (!loaded) return false;
        return JeiBridge.isReady();
    }

    /**
     * 打开该物品的配方页。
     *
     * @param uses {@code true} = 用途页（拿它能做什么），{@code false} = 来源页（它怎么来的）
     * @return 是否成功打开；JEI 不在场时返回 {@code false}，调用方据此决定要不要给别的反馈
     */
    public static boolean showRecipe(ItemStack stack, boolean uses) {
        if (!isAvailable()) return false;
        return JeiBridge.show(stack, uses);
    }
}
