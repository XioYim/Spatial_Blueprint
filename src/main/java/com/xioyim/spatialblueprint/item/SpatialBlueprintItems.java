package com.xioyim.spatialblueprint.item;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class SpatialBlueprintItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SpatialBlueprintMod.MODID);

    public static final RegistryObject<Item> BLANK_BLUEPRINT = ITEMS.register("blank_blueprint",
            () -> new BlankBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> SPATIAL_BLUEPRINT = ITEMS.register("spatial_blueprint",
            () -> new SpatialBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> BUILD_BLUEPRINT = ITEMS.register("build_blueprint",
            () -> new BuildBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> PROJECTION_BLUEPRINT = ITEMS.register("projection_blueprint",
            () -> new ProjectionBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> DIMENSIONAL_BLUEPRINT = ITEMS.register("dimensional_blueprint",
            () -> new DimensionalBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> VOID_DIMENSIONAL_BLUEPRINT = ITEMS.register("void_dimensional_blueprint",
            () -> new VoidDimensionalBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> ENGINEERING_BLUEPRINT = ITEMS.register("engineering_blueprint",
            () -> new EngineeringBlueprintItem(new Item.Properties().stacksTo(1)));

    /** 奖励蓝图：投影蓝图的特殊变体，OP 专属创建，免费放置、用后销毁。 */
    public static final RegistryObject<Item> REWARD_BLUEPRINT = ITEMS.register("reward_blueprint",
            () -> new RewardBlueprintItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> PUBLIC_PROJECT_BLUEPRINT =
            ITEMS.register("public_project_blueprint",
                    () -> new PublicProjectBlueprintItem(new Item.Properties()));

    public static final RegistryObject<Item> PRIVATE_PROJECT_BLUEPRINT =
            ITEMS.register("private_project_blueprint",
                    () -> new PrivateProjectBlueprintItem(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
