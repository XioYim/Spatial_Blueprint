package com.xioyim.spatialblueprint;

import com.xioyim.spatialblueprint.block.RenderBlock;
import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class SpatialBlueprintRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, SpatialBlueprintMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SpatialBlueprintMod.MODID);

    public static final RegistryObject<Block> RENDER_BLOCK = BLOCKS.register("render_block", RenderBlock::new);
    public static final RegistryObject<BlockEntityType<RenderBlockEntity>> RENDER_BLOCK_ENTITY = BLOCK_ENTITIES.register("render_block",
            () -> BlockEntityType.Builder.of(RenderBlockEntity::new, RENDER_BLOCK.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
    }
}
