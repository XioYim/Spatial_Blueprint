package com.xioyim.spatialblueprint;

import com.xioyim.spatialblueprint.block.RenderBlock;
import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class SpatialBlueprintRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SpatialBlueprintMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SpatialBlueprintMod.MODID);

    public static final DeferredBlock<Block> RENDER_BLOCK = BLOCKS.register("render_block", RenderBlock::new);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RenderBlockEntity>> RENDER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("render_block",
                    () -> BlockEntityType.Builder.of(RenderBlockEntity::new, RENDER_BLOCK.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
    }
}
