package com.xioyim.spatialblueprint.item;

import net.minecraft.world.item.ItemStack;

/**
 * 公用项目蓝图：全服共享一份进度。
 *
 * <p>由 OP 在创造模式激活一次，之后所有副本共享同一个 taskSetId。
 * 未激活的公用蓝图落到非 OP 手里视为非法蓝图。</p>
 */
public class PublicProjectBlueprintItem extends ProjectBlueprintItem {

    public PublicProjectBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) { return true; }

    @Override
    public boolean isPublicProject() { return true; }
}
