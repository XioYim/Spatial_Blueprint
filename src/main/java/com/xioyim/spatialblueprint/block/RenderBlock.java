package com.xioyim.spatialblueprint.block;

import com.xioyim.spatialblueprint.SpatialBlueprintRegistry;
import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class RenderBlock extends Block implements EntityBlock {

    public RenderBlock() {
        super(Block.Properties.of()
                .strength(0.3f)
                .dynamicShape()
                .noOcclusion()
                .isViewBlocking((state, level, pos) -> false));
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != SpatialBlueprintRegistry.RENDER_BLOCK_ENTITY.get()) return null;
        if (level.isClientSide()) {
            return (lvl, pos, blockState, t) -> {
                if (t instanceof RenderBlockEntity tile) {
                    tile.tickClient();
                }
            };
        }
        return (lvl, pos, blockState, t) -> {
            if (t instanceof RenderBlockEntity tile) {
                tile.tickServer();
            }
        };
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RenderBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // 客户端：在 super 移除 BlockEntity 之前缓存 targetBlock 供破坏粒子使用。
        // 仅在方块被移除为空气时缓存（玩家破坏），替换为真实方块时不缓存。
        if (level.isClientSide() && !state.is(newState.getBlock()) && newState.isAir()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RenderBlockEntity rbe && rbe.targetBlock != null && !rbe.targetBlock.isAir()) {
                // 破坏粒子缓存（客户端纯逻辑类，addDestroyEffects 走 IClientBlockExtensions）
                com.xioyim.spatialblueprint.client.RenderBlockParticleCache.put(pos.immutable(), rbe.targetBlock);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
