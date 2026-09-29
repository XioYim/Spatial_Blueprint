package com.xioyim.spatialblueprint.compat.jei;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * JEI 插件：唯一目的是把 {@link IJeiRuntime} 交给 {@link JeiBridge}。
 *
 * <p>本 mod 不向 JEI 注册任何配方或分类，只是想在材料清单里调用
 * 「查看来源 / 查看用途」——那需要拿到运行时实例。</p>
 *
 * <p>整个 {@code compat.jei} 包只在 JEI 存在时才会被加载：
 * {@link JeiPlugin} 注解由 JEI 自己扫描，JEI 不在场时这个类根本不会被实例化。
 * 其余代码一律通过 {@link JeiBridge} 间接调用，不直接引用 JEI 类型。</p>
 */
@JeiPlugin
@OnlyIn(Dist.CLIENT)
public class SBJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            new ResourceLocation(SpatialBlueprintMod.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiBridge.setRuntime(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiBridge.setRuntime(null);
    }
}
