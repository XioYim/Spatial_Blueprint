package com.xioyim.spatialblueprint.client.outliner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector4f;

/**
 * Renders only the three edges meeting at a single corner of a block (a 3D
 * corner bracket), instead of a full box outline.
 *
 * <p>Each axis has an independent direction ({@code +1} or {@code -1}), so the
 * bracket can point toward the opposite selection corner as the selection grows.</p>
 */
public class CornerOutline extends Outline {

    private final Vector3d corner = new Vector3d();
    private float length = 1;
    private int sx = 1, sy = 1, sz = 1;

    public CornerOutline(Vec3 corner, float length, int sx, int sy, int sz) {
        set(corner, length, sx, sy, sz);
    }

    public void set(Vec3 corner, float length, int sx, int sy, int sz) {
        this.corner.set(corner.x, corner.y, corner.z);
        this.length = length;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    @Override
    public void render(PoseStack ms, MultiBufferSource buffer, Vec3 camera, float pt) {
        params.loadColor(colorTemp);
        Vector4f color = colorTemp;
        int lightmap = params.lightmap;
        float lineWidth = params.getLineWidth();
        if (lineWidth == 0)
            return;

        VertexConsumer consumer = buffer.getBuffer(params.isNoDepth()
                ? SBOutlinerRenderTypes.outlineSolidNoDepth()
                : SBOutlinerRenderTypes.outlineSolid());

        // 方向给 0 的轴不画（零长度会画出一个 lineWidth 大小的点）：只给 y 就是一条独立竖线
        Vector3d c = corner;
        if (sx != 0)
            bufferCuboidLine(ms, consumer, camera, c, new Vector3d(c.x + sx * length, c.y, c.z),
                    lineWidth, color, lightmap, params.disableLineNormals);
        if (sy != 0)
            bufferCuboidLine(ms, consumer, camera, c, new Vector3d(c.x, c.y + sy * length, c.z),
                    lineWidth, color, lightmap, params.disableLineNormals);
        if (sz != 0)
            bufferCuboidLine(ms, consumer, camera, c, new Vector3d(c.x, c.y, c.z + sz * length),
                    lineWidth, color, lightmap, params.disableLineNormals);
    }
}
