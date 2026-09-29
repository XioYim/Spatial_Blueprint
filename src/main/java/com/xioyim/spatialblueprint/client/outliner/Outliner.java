package com.xioyim.spatialblueprint.client.outliner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xioyim.spatialblueprint.client.outliner.Outline.OutlineParams;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Thread-safe manager that keeps a set of world-space outlines alive, ticks
 * them each game tick and renders them every frame. Mirrors Create's Outliner.
 */
public class Outliner {

    private static final Outliner instance = new Outliner();

    public static Outliner getInstance() {
        return instance;
    }

    private final Map<Object, OutlineEntry> outlines = Collections.synchronizedMap(new HashMap<>());
    private final Map<Object, OutlineEntry> outlinesView = Collections.unmodifiableMap(outlines);

    public Outliner() {}

    public OutlineParams showOutline(Object slot, Outline outline) {
        outlines.put(slot, new OutlineEntry(outline));
        return outline.getParams();
    }

    public OutlineParams showAABB(Object slot, AABB bb) {
        createAABBOutlineIfMissing(slot, bb);
        ChasingAABBOutline outline = getAndRefreshAABB(slot);
        outline.prevBB = outline.targetBB = outline.bb = bb;
        return outline.getParams();
    }

    public OutlineParams chaseAABB(Object slot, AABB bb) {
        createAABBOutlineIfMissing(slot, bb);
        ChasingAABBOutline outline = getAndRefreshAABB(slot);
        outline.targetBB = bb;
        return outline.getParams();
    }

    public OutlineParams showCorner(Object slot, Vec3 corner, float length, int sx, int sy, int sz) {
        if (!outlines.containsKey(slot) || !(outlines.get(slot).outline instanceof CornerOutline)) {
            CornerOutline outline = new CornerOutline(corner, length, sx, sy, sz);
            addOutline(slot, outline);
        }
        OutlineEntry entry = outlines.get(slot);
        entry.ticksTillRemoval = 1;
        ((CornerOutline) entry.outline).set(corner, length, sx, sy, sz);
        return entry.outline.getParams();
    }

    public void keep(Object slot) {
        if (outlines.containsKey(slot))
            outlines.get(slot).ticksTillRemoval = 1;
    }

    public void remove(Object slot) {
        outlines.remove(slot);
    }

    public Map<Object, OutlineEntry> getOutlines() {
        return outlinesView;
    }

    private void addOutline(Object slot, Outline outline) {
        outlines.put(slot, new OutlineEntry(outline));
    }

    private void createAABBOutlineIfMissing(Object slot, AABB bb) {
        if (!outlines.containsKey(slot) || !(outlines.get(slot).outline instanceof AABBOutline)) {
            ChasingAABBOutline outline = new ChasingAABBOutline(bb);
            addOutline(slot, outline);
        }
    }

    private ChasingAABBOutline getAndRefreshAABB(Object slot) {
        OutlineEntry entry = outlines.get(slot);
        entry.ticksTillRemoval = 1;
        return (ChasingAABBOutline) entry.getOutline();
    }

    public void tickOutlines() {
        Iterator<OutlineEntry> iterator = outlines.values().iterator();
        while (iterator.hasNext()) {
            OutlineEntry entry = iterator.next();
            entry.tick();
            if (!entry.isAlive())
                iterator.remove();
        }
    }

    public void renderOutlines(PoseStack ms, MultiBufferSource.BufferSource buffer, Vec3 camera, float pt) {
        // Batch 1: normal depth-tested boxes.
        outlines.forEach((key, entry) -> {
            Outline o = entry.getOutline();
            if (!o.getParams().isNoDepth())
                renderEntry(entry, ms, buffer, camera, pt);
        });
        buffer.endBatch();
        // Batch 2: no-depth boxes/glow layers (never occluded by geometry).
        outlines.forEach((key, entry) -> {
            Outline o = entry.getOutline();
            if (o.getParams().isNoDepth() && o.getParams().getRenderPriority() < 2)
                renderEntry(entry, ms, buffer, camera, pt);
        });
        buffer.endBatch();
        // Batch 3: corner brackets — always drawn last (on top of everything).
        outlines.forEach((key, entry) -> {
            Outline o = entry.getOutline();
            if (o.getParams().isNoDepth() && o.getParams().getRenderPriority() >= 2)
                renderEntry(entry, ms, buffer, camera, pt);
        });
        buffer.endBatch();
    }

    private void renderEntry(OutlineEntry entry, PoseStack ms, MultiBufferSource buffer, Vec3 camera, float pt) {
        Outline outline = entry.getOutline();
        OutlineParams params = outline.getParams();
        params.alpha = 1;
        if (entry.isFading()) {
            int prevTicks = entry.ticksTillRemoval + 1;
            float fadeTicks = OutlineEntry.FADE_TICKS;
            float lastAlpha = prevTicks >= 0 ? 1 : 1 + (prevTicks / fadeTicks);
            float currentAlpha = 1 + (entry.ticksTillRemoval / fadeTicks);
            float alpha = Mth.lerp(pt, lastAlpha, currentAlpha);

            params.alpha = alpha * alpha * alpha;
            if (params.alpha < 1 / 8f)
                return;
        }
        outline.render(ms, buffer, camera, pt);
    }

    public static class OutlineEntry {
        public static final int FADE_TICKS = 8;

        private final Outline outline;
        private int ticksTillRemoval = 1;

        public OutlineEntry(Outline outline) {
            this.outline = outline;
        }

        public Outline getOutline() {
            return outline;
        }

        public int getTicksTillRemoval() {
            return ticksTillRemoval;
        }

        public boolean isAlive() {
            return ticksTillRemoval >= -FADE_TICKS;
        }

        public boolean isFading() {
            return ticksTillRemoval < 0;
        }

        public void tick() {
            ticksTillRemoval--;
            outline.tick();
        }
    }
}
