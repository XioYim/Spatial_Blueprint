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

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

public class RenderBlock extends Block implements EntityBlock {
    // 客户端缓存：onRemove 时保存 targetBlock，供 addDestroyEffects 使用
    // （levelEvent 2001 到达时 BlockEntity 已被移除，必须提前缓存）
    private static final Map<BlockPos, BlockState> CLIENT_PARTICLE_CACHE = new HashMap<>();

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
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // 客户端：在 super 移除 BlockEntity 之前缓存 targetBlock 供破坏粒子使用。
        // 仅在方块被移除为空气时缓存（玩家破坏），替换为真实方块时不缓存。
        if (level.isClientSide() && !state.is(newState.getBlock()) && newState.isAir()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RenderBlockEntity rbe && rbe.targetBlock != null && !rbe.targetBlock.isAir()) {
                CLIENT_PARTICLE_CACHE.put(pos.immutable(), rbe.targetBlock);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    public boolean addDestroyEffects(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                                     net.minecraft.client.particle.ParticleEngine manager) {
        // 使用 onRemove 预缓存的 targetBlock 纹理生成粒子
        BlockState target = CLIENT_PARTICLE_CACHE.remove(pos);
        if (target != null && !target.isAir()) {
            manager.destroy(pos, target);
        }
        // 无论是否有缓存都返回 true，抑制黑紫缺失纹理的默认粒子
        return true;
    }
}
