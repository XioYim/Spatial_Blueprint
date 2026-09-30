package com.xioyim.spatialblueprint.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

public class RenderBlockRenderer implements BlockEntityRenderer<RenderBlockEntity> {
    public RenderBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RenderBlockEntity blockEntity, float partialTicks, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        int drawSize = blockEntity.drawSize;
        float nowScale = (float) drawSize / (float) blockEntity.getMaxSize();
        float nextScale = (float) blockEntity.nextDrawSize() / (float) blockEntity.getMaxSize();
        float scale = Mth.lerp(partialTicks, nowScale, nextScale);

        if (scale >= 1.0f) scale = 1f;
        if (scale <= 0) scale = 0;

        BlockState renderState = blockEntity.renderBlock;
        if (renderState == null || renderState.isAir()) return;

        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = dispatcher.getBlockModel(renderState);

        poseStack.pushPose();
        VertexConsumer builder = bufferSource.getBuffer(RenderType.cutout());

        poseStack.translate((1 - scale) / 2, (1 - scale) / 2, (1 - scale) / 2);
        poseStack.scale(scale, scale, scale);

        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();
        if (!renderState.getFluidState().isEmpty()) {
            // 流体：用蓝/橙色染色玻璃代替，随动画缩放
            boolean isLava = renderState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
            BlockState sub = isLava
                    ? net.minecraft.world.level.block.Blocks.ORANGE_STAINED_GLASS.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.BLUE_STAINED_GLASS.defaultBlockState();
            VertexConsumer transBuilder = bufferSource.getBuffer(RenderType.translucent());
            try {
                modelRenderer.tesselateBlock(level, dispatcher.getBlockModel(sub), sub, pos, poseStack, transBuilder, false, random, sub.getSeed(pos), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            } catch (Exception ignored) {}
        } else {
            try {
                modelRenderer.tesselateBlock(level, model, renderState, pos, poseStack, builder, false, random, renderState.getSeed(pos), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            } catch (Exception e) {
                dispatcher.renderSingleBlock(renderState, poseStack, bufferSource, packedLight, packedOverlay, ModelData.EMPTY, null);
            }
        }

        poseStack.popPose();
    }
}
