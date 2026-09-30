package com.xioyim.spatialblueprint.client.outliner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 开发用 outliner 调试驱动（SBN-016 三.5）。
 *
 * <p>outliner 在 SBN-017（BlueprintSelectionRenderer）之前没有驱动者，没有它渲染底座的手测
 * 看不到任何画面。本类只在环境变量 {@code SB_DEBUG_OUTLINER} 非空时由
 * {@link com.xioyim.spatialblueprint.client.SpatialBlueprintClient} 注册到游戏总线，
 * 默认构建零影响；SBN-017 接入后是否保留由主会话定。</p>
 *
 * <p>以玩家所在方块 P 为基准画四组测试框（H4 手测对象）：</p>
 * <ul>
 *   <li>A：P+(3,0,0) 单格 AABB，默认深度测试（应被墙挡住）</li>
 *   <li>B：P+(-3,0,0) 单格 AABB，noDepth（隔墙可见）</li>
 *   <li>C：chaseAABB 追随准星指向的方块；没指向方块时不刷新（自然淡出）</li>
 *   <li>D：P+(0,0,3) 角标，长度 0.5、三个方向各一条</li>
 * </ul>
 */
public final class OutlinerDebug {

    private static final String SLOT_A = "dbg_a";
    private static final String SLOT_B = "dbg_b";
    private static final String SLOT_C = "dbg_c";
    private static final String SLOT_D = "dbg_d";

    private OutlinerDebug() {}

    /** 每 tick：刷新四个测试对象，并推进 outliner 自己的计时 / 淡出。 */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        BlockPos p = mc.player.blockPosition();

        // A 常规深度框（默认参数，应被墙挡住）
        Outliner.getInstance().showAABB(SLOT_A, boxAt(p.offset(3, 0, 0)));
        // B 无深度框（隔墙可见）
        Outliner.getInstance().showAABB(SLOT_B, boxAt(p.offset(-3, 0, 0))).noDepth();
        // C 追随准星指向的方块；没指向方块时不刷新（让它自然淡出，检验淡出）
        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            Outliner.getInstance().chaseAABB(SLOT_C, boxAt(blockHit.getBlockPos()));
        }
        // D 角标：单角三条边
        Outliner.getInstance().showCorner(SLOT_D,
                new Vec3(p.getX(), p.getY(), p.getZ() + 3), 0.5f, 1, 1, 1);

        Outliner.getInstance().tickOutlines();
    }

    /** 渲染：与 1.20.1 BlueprintSelectionRenderer.renderOutlines 同写法（AFTER_PARTICLES 相位）。 */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        // NOTE: at AFTER_PARTICLES the event pose stack has NO camera translation;
        // AABBOutline shifts its box by -camera internally (same convention as Create).
        ps.pushPose();
        MultiBufferSource.BufferSource bufSrc = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Outliner.getInstance().renderOutlines(ps, bufSrc, camera, partialTick);
        ps.popPose();
        bufSrc.endBatch();
    }

    private static AABB boxAt(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }
}
