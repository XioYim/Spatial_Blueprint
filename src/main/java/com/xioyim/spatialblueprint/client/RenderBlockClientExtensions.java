package com.xioyim.spatialblueprint.client;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

/**
 * RenderBlock 的客户端扩展（1.21 的 addDestroyEffects 落点）。
 *
 * <p>用 {@code onRemove} 预缓存的 targetBlock 纹理生成破坏粒子：levelEvent 2001 到达时
 * BlockEntity 已被移除，拿不到真实目标方块。</p>
 */
public class RenderBlockClientExtensions implements IClientBlockExtensions {

    @Override
    public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine manager) {
        // 使用 onRemove 预缓存的 targetBlock 纹理生成粒子
        BlockState target = RenderBlockParticleCache.remove(pos);
        if (target != null && !target.isAir()) {
            manager.destroy(pos, target);
        }
        // 无论是否有缓存都返回 true，抑制黑紫缺失纹理的默认粒子
        return true;
    }
}
