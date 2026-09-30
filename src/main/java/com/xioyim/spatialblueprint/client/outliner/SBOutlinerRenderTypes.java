package com.xioyim.spatialblueprint.client.outliner;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BiFunction;

/**
 * Custom render types for the blueprint outline (ghost box) rendering.
 * Mirror of the visuals used by Create's outliner: an opaque solid layer
 * for the thick box edges and a translucent textured layer for the faces.
 *
 * <p>Extends {@link RenderType} (which itself extends {@link RenderStateShard})
 * so the {@code protected} render-state shards used below are accessible.</p>
 */
public abstract class SBOutlinerRenderTypes extends RenderType {

    private static final RenderType OUTLINE_SOLID = RenderType.create(
            "spatial_blueprint:outline_solid",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_SOLID_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ResourceLocation.withDefaultNamespace("textures/misc/white.png"), false, false))
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(false));

    private static final RenderType OUTLINE_SOLID_NO_DEPTH = RenderType.create(
            "spatial_blueprint:outline_solid_no_depth",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_SOLID_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ResourceLocation.withDefaultNamespace("textures/misc/white.png"), false, false))
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    private static final BiFunction<ResourceLocation, Boolean, RenderType> OUTLINE_TRANSLUCENT = Util.memoize(
            (texture, cull) -> RenderType.create(
                    "spatial_blueprint:outline_translucent" + (cull ? "_cull" : ""),
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
                    false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(cull
                                    ? RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER
                                    : RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(cull ? CULL : NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)));

    public static RenderType outlineSolid() {
        return OUTLINE_SOLID;
    }

    /** Opaque edge layer that ignores the depth buffer (always drawn on top). */
    public static RenderType outlineSolidNoDepth() {
        return OUTLINE_SOLID_NO_DEPTH;
    }

    public static RenderType outlineTranslucent(ResourceLocation texture, boolean cull) {
        return OUTLINE_TRANSLUCENT.apply(texture, cull);
    }

    private SBOutlinerRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                  boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState,
                                  Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }
}
