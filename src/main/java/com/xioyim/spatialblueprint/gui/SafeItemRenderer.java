package com.xioyim.spatialblueprint.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 统一的物品图标渲染入口，供材料清单、材料空间等所有需要画物品图标的界面使用。
 *
 * <p>直接调用 {@link GuiGraphics#renderItem} 在遇到自定义渲染器（BEWLR）的模组物品时
 * 可能抛异常，并留下被污染的 GL 状态与未弹出的 PoseStack 层，导致后续所有 UI 渲染错乱
 * 甚至整个界面崩溃。本类做三件事：</p>
 *
 * <ol>
 *   <li><b>异常隔离</b>：捕获渲染异常，把出问题的物品记入 {@link #KNOWN_CRASH}，
 *       后续帧直接画屏障方块占位，不再重试（否则每帧都抛异常会严重掉帧）</li>
 *   <li><b>PoseStack 修正</b>：渲染前后比对 modelView 栈深度，把没弹干净的层补弹回去</li>
 *   <li><b>GL 状态还原</b>：还原 blend / shader color；depth test 按调用前的状态还原
 *       而非一律关闭 —— 材料清单依赖 z 顺序让 overlay 遮盖图标，无脑关掉会破坏遮挡</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class SafeItemRenderer {

    /** 已知会崩溃的物品注册名，跨界面共享。 */
    private static final Set<String> KNOWN_CRASH = Collections.synchronizedSet(new HashSet<>());

    private SafeItemRenderer() {}

    /** 安全渲染一个物品图标（16×16）。空 ItemStack 直接跳过。 */
    public static void render(GuiGraphics g, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;

        String itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean depthWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

        // 已知崩溃物品：直接画屏障占位，不再尝试真实渲染
        if (KNOWN_CRASH.contains(itemKey)) {
            try {
                g.renderItem(new ItemStack(Blocks.BARRIER), x, y);
            } catch (Throwable ignored) {}
            restoreGl(depthWasEnabled);
            return;
        }

        PoseStack mv = RenderSystem.getModelViewStack();
        int depthBefore = mvDepth(mv);
        Throwable caught = null;

        try {
            g.renderItem(stack, x, y);
        } catch (Throwable ex) {
            caught = ex;
            KNOWN_CRASH.add(itemKey);
            // BEWLR 崩溃时可能已向 bufferSource 写入残余顶点，立即 endBatch 提交清空，
            // 防止下一次正常 endBatch() 把这批错误数据混入后续物品的渲染结果
            try {
                Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            } catch (Throwable ignored) {}
            SpatialBlueprintMod.LOGGER.warn(
                    "[SafeItemRenderer] renderItem threw for {}: {}", itemKey, ex.toString());
        } finally {
            boolean corrected = false;
            if (depthBefore >= 0) {
                while (mvDepth(mv) > depthBefore) { mv.popPose(); corrected = true; }
            } else if (caught != null) {
                mv.popPose();
                corrected = true;
            }
            if (corrected) RenderSystem.applyModelViewMatrix();
            restoreGl(depthWasEnabled);
        }
    }

    /**
     * 物品循环结束后调用一次，做一次兜底的 GL 状态还原。
     * 逐个物品的还原已在 {@link #render} 内完成，这里主要防止批量渲染过程中
     * 有物品在异常路径上漏掉还原。
     */
    public static void finishBatch(boolean depthEnabled) {
        restoreGl(depthEnabled);
    }

    private static void restoreGl(boolean depthEnabled) {
        // 按调用前的状态还原 depth test，不要一律 disable ——
        // 材料清单靠 z 顺序让 overlay（z=300）盖住物品图标（z≈232）
        if (depthEnabled) RenderSystem.enableDepthTest();
        else              RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    // ── PoseStack 栈深度探测 ──────────────────────────────────────────────
    // 按 Deque 类型查找内部字段，不依赖字段名，兼容 Blaze3D 各版本

    private static final Field MV_POSE_FIELD;
    static {
        Field found = null;
        try {
            for (Field f : PoseStack.class.getDeclaredFields()) {
                if (Deque.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    found = f;
                    break;
                }
            }
        } catch (Exception ignored) {}
        MV_POSE_FIELD = found;
    }

    private static int mvDepth(PoseStack ps) {
        if (MV_POSE_FIELD == null) return -1;
        try {
            return ((Deque<?>) MV_POSE_FIELD.get(ps)).size();
        } catch (Exception e) {
            return -1;
        }
    }
}
