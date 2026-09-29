package com.xioyim.spatialblueprint.client.outliner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Renders an AABB as 6 translucent textured faces plus 12 thick box edges.
 * Mirrors the visuals of Create's AABBOutline.
 */
public class AABBOutline extends Outline {

    protected AABB bb;

    protected final Vector3f minPosTemp1 = new Vector3f();
    protected final Vector3f maxPosTemp1 = new Vector3f();

    protected final Vector4f colorTemp1 = new Vector4f();
    protected final Vector3f pos0Temp = new Vector3f();
    protected final Vector3f pos1Temp = new Vector3f();
    protected final Vector3f pos2Temp = new Vector3f();
    protected final Vector3f pos3Temp = new Vector3f();
    protected final Vector3f normalTemp = new Vector3f();
    protected final Vector3f originTemp = new Vector3f();

    public AABBOutline(AABB bb) {
        setBounds(bb);
    }

    public AABB getBounds() {
        return bb;
    }

    public void setBounds(AABB bb) {
        this.bb = bb;
    }

    @Override
    public void render(PoseStack ms, MultiBufferSource buffer, Vec3 camera, float pt) {
        params.loadColor(colorTemp);
        Vector4f color = colorTemp;
        int lightmap = params.lightmap;
        boolean disableLineNormals = params.disableLineNormals;
        renderBox(ms, buffer, camera, bb, color, lightmap, disableLineNormals);
    }

    protected void renderBox(PoseStack ms, MultiBufferSource buffer, Vec3 camera, AABB box, Vector4f color,
                             int lightmap, boolean disableLineNormals) {
        Vector3f minPos = minPosTemp1;
        Vector3f maxPos = maxPosTemp1;

        boolean cameraInside = box.contains(camera);
        boolean cull = !cameraInside && !params.disableCull;
        float inflate = cameraInside ? -1 / 128f : 1 / 128f;

        box = box.move(camera.scale(-1));
        minPos.set((float) box.minX - inflate, (float) box.minY - inflate, (float) box.minZ - inflate);
        maxPos.set((float) box.maxX + inflate, (float) box.maxY + inflate, (float) box.maxZ + inflate);

        renderBoxFaces(ms, buffer, cull, params.getHighlightedFace(), minPos, maxPos, color, lightmap);

        float lineWidth = params.getLineWidth();
        if (lineWidth == 0)
            return;

        VertexConsumer consumer = buffer.getBuffer(params.isNoDepth()
                ? SBOutlinerRenderTypes.outlineSolidNoDepth()
                : SBOutlinerRenderTypes.outlineSolid());
        renderBoxEdges(ms, consumer, minPos, maxPos, lineWidth, color, lightmap, disableLineNormals);
    }

    protected void renderBoxFaces(PoseStack ms, MultiBufferSource buffer, boolean cull, Direction highlightedFace,
                                  Vector3f minPos, Vector3f maxPos, Vector4f color, int lightmap) {
        PoseStack.Pose pose = ms.last();
        renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, Direction.DOWN, color, lightmap);
        renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, Direction.UP, color, lightmap);
        renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, Direction.NORTH, color, lightmap);
        renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, Direction.SOUTH, color, lightmap);
        renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, Direction.WEST, color, lightmap);
        renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, Direction.EAST, color, lightmap);
    }

    protected void renderBoxFace(PoseStack.Pose pose, MultiBufferSource buffer, boolean cull, Direction highlightedFace,
                                 Vector3f minPos, Vector3f maxPos, Direction face, Vector4f color, int lightmap) {
        boolean highlighted = face == highlightedFace;

        // NOTE: matching Create, only the base face texture is actually used;
        // the highlight texture slot is kept for API compatibility.
        ResourceLocation faceTexture = params.faceTexture;
        if (faceTexture == null)
            return;

        RenderType renderType = SBOutlinerRenderTypes.outlineTranslucent(faceTexture, cull);
        VertexConsumer consumer = buffer.getBuffer(renderType);

        float alphaMult = highlighted ? 1 : 0.5f;
        colorTemp1.set(color.x(), color.y(), color.z(), color.w() * alphaMult);
        color = colorTemp1;

        renderBoxFace(pose, consumer, minPos, maxPos, face, color, lightmap);
    }

    protected void renderBoxFace(PoseStack.Pose pose, VertexConsumer consumer, Vector3f minPos, Vector3f maxPos,
                                 Direction face, Vector4f color, int lightmap) {
        Vector3f pos0 = pos0Temp;
        Vector3f pos1 = pos1Temp;
        Vector3f pos2 = pos2Temp;
        Vector3f pos3 = pos3Temp;
        Vector3f normal = normalTemp;

        float minX = minPos.x();
        float minY = minPos.y();
        float minZ = minPos.z();
        float maxX = maxPos.x();
        float maxY = maxPos.y();
        float maxZ = maxPos.z();

        float maxU;
        float maxV;

        switch (face) {
            case DOWN -> {
                pos0.set(minX, minY, maxZ);
                pos1.set(minX, minY, minZ);
                pos2.set(maxX, minY, minZ);
                pos3.set(maxX, minY, maxZ);
                maxU = maxX - minX;
                maxV = maxZ - minZ;
                normal.set(0, -1, 0);
            }
            case UP -> {
                pos0.set(minX, maxY, minZ);
                pos1.set(minX, maxY, maxZ);
                pos2.set(maxX, maxY, maxZ);
                pos3.set(maxX, maxY, minZ);
                maxU = maxX - minX;
                maxV = maxZ - minZ;
                normal.set(0, 1, 0);
            }
            case NORTH -> {
                pos0.set(maxX, maxY, minZ);
                pos1.set(maxX, minY, minZ);
                pos2.set(minX, minY, minZ);
                pos3.set(minX, maxY, minZ);
                maxU = maxX - minX;
                maxV = maxY - minY;
                normal.set(0, 0, -1);
            }
            case SOUTH -> {
                pos0.set(minX, maxY, maxZ);
                pos1.set(minX, minY, maxZ);
                pos2.set(maxX, minY, maxZ);
                pos3.set(maxX, maxY, maxZ);
                maxU = maxX - minX;
                maxV = maxY - minY;
                normal.set(0, 0, 1);
            }
            case WEST -> {
                pos0.set(minX, maxY, minZ);
                pos1.set(minX, minY, minZ);
                pos2.set(minX, minY, maxZ);
                pos3.set(minX, maxY, maxZ);
                maxU = maxZ - minZ;
                maxV = maxY - minY;
                normal.set(-1, 0, 0);
            }
            case EAST -> {
                pos0.set(maxX, maxY, maxZ);
                pos1.set(maxX, minY, maxZ);
                pos2.set(maxX, minY, minZ);
                pos3.set(maxX, maxY, minZ);
                maxU = maxZ - minZ;
                maxV = maxY - minY;
                normal.set(1, 0, 0);
            }
            default -> {
                maxU = 1;
                maxV = 1;
            }
        }

        bufferQuad(pose, consumer, pos0, pos1, pos2, pos3, color, 0, 0, maxU, maxV, lightmap, normal);
    }

    protected void renderBoxEdges(PoseStack ms, VertexConsumer consumer, Vector3f minPos, Vector3f maxPos,
                                  float lineWidth, Vector4f color, int lightmap, boolean disableNormals) {
        Vector3f origin = originTemp;

        PoseStack.Pose pose = ms.last();

        float lineLengthX = maxPos.x() - minPos.x();
        float lineLengthY = maxPos.y() - minPos.y();
        float lineLengthZ = maxPos.z() - minPos.z();

        origin.set(minPos);
        bufferCuboidLine(pose, consumer, origin, Direction.EAST, lineLengthX, lineWidth, color, lightmap, disableNormals);
        bufferCuboidLine(pose, consumer, origin, Direction.UP, lineLengthY, lineWidth, color, lightmap, disableNormals);
        bufferCuboidLine(pose, consumer, origin, Direction.SOUTH, lineLengthZ, lineWidth, color, lightmap, disableNormals);

        origin.set(maxPos.x(), minPos.y(), minPos.z());
        bufferCuboidLine(pose, consumer, origin, Direction.UP, lineLengthY, lineWidth, color, lightmap, disableNormals);
        bufferCuboidLine(pose, consumer, origin, Direction.SOUTH, lineLengthZ, lineWidth, color, lightmap, disableNormals);

        origin.set(minPos.x(), maxPos.y(), minPos.z());
        bufferCuboidLine(pose, consumer, origin, Direction.EAST, lineLengthX, lineWidth, color, lightmap, disableNormals);
        bufferCuboidLine(pose, consumer, origin, Direction.SOUTH, lineLengthZ, lineWidth, color, lightmap, disableNormals);

        origin.set(minPos.x(), minPos.y(), maxPos.z());
        bufferCuboidLine(pose, consumer, origin, Direction.EAST, lineLengthX, lineWidth, color, lightmap, disableNormals);
        bufferCuboidLine(pose, consumer, origin, Direction.UP, lineLengthY, lineWidth, color, lightmap, disableNormals);

        origin.set(minPos.x(), maxPos.y(), maxPos.z());
        bufferCuboidLine(pose, consumer, origin, Direction.EAST, lineLengthX, lineWidth, color, lightmap, disableNormals);

        origin.set(maxPos.x(), minPos.y(), maxPos.z());
        bufferCuboidLine(pose, consumer, origin, Direction.UP, lineLengthY, lineWidth, color, lightmap, disableNormals);

        origin.set(maxPos.x(), maxPos.y(), minPos.z());
        bufferCuboidLine(pose, consumer, origin, Direction.SOUTH, lineLengthZ, lineWidth, color, lightmap, disableNormals);
    }
}
