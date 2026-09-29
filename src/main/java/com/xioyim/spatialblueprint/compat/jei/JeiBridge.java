package com.xioyim.spatialblueprint.compat.jei;

import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * JEI 的唯一调用出口。
 *
 * <p>界面代码只调 {@link SBJeiSupport}，那一层再转到这里 —— 这样
 * <b>JEI 不在场时，本类根本不会被类加载</b>，也就不会因为找不到 {@code mezz.jei.*}
 * 而崩溃。（JEI 是可选依赖：{@code compileOnly}，运行时可能没有。）</p>
 */
@OnlyIn(Dist.CLIENT)
final class JeiBridge {

    @Nullable
    private static IJeiRuntime runtime;

    private JeiBridge() {}

    static void setRuntime(@Nullable IJeiRuntime rt) {
        runtime = rt;
    }

    static boolean isReady() {
        return runtime != null;
    }

    /** JEI 这个 mod 是否装了 —— 只查 ModList，不触碰任何 JEI 类型。 */
    static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded("jei");
    }

    /**
     * 打开配方页。
     *
     * @param uses {@code true} = 用途（该物品作为输入），{@code false} = 来源（该物品作为产物）
     * @return 是否成功打开
     */
    static boolean show(ItemStack stack, boolean uses) {
        IJeiRuntime rt = runtime;
        if (rt == null || stack == null || stack.isEmpty()) return false;
        try {
            IFocus<ItemStack> focus = rt.getJeiHelpers().getFocusFactory().createFocus(
                    uses ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT,
                    mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                    stack);
            rt.getRecipesGui().show(focus);
            return true;
        } catch (Throwable t) {
            // JEI 版本差异导致 API 不兼容时不要连累主界面
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.warn(
                    "[JEI] show recipe failed: {}", t.toString());
            return false;
        }
    }
}
