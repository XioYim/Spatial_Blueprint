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

    public static final RegistryObject<Item> PROJECT_BLUEPRINT = ITEMS.register("project_blueprint",
            () -> new ProjectBlueprintItem(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
