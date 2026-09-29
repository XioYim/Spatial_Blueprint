package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.client.ConfigAccess;
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.client.outliner.Outliner;
import com.xioyim.spatialblueprint.gui.EngineeringBlueprintScreen;
import com.xioyim.spatialblueprint.gui.PlacementScreen;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.DimensionalBlueprintItem;
import com.xioyim.spatialblueprint.item.EngineeringBlueprintItem;
import com.xioyim.spatialblueprint.item.ProjectionBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.VoidDimensionalBlueprintItem;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class BlueprintSelectionRenderer {
    private static double time = 0;
    /** Reused across render frames to avoid per-frame allocation pressure. */
    private static final RandomSource RENDER_RANDOM = RandomSource.create();

    // ── Ghost box face textures (Create-style outline, self-drawn) ─────────
    private static final ResourceLocation SB_FACE_TEXTURE = new ResourceLocation(
            SpatialBlueprintMod.MODID, "textures/special/sb_checker.png");
    private static final ResourceLocation SB_FACE_TEXTURE_HIGHLIGHT = new ResourceLocation(
            SpatialBlueprintMod.MODID, "textures/special/sb_checker_highlight.png");
    /** Create-style ghost box line width (blocks). */
    private static final float GHOST_BOX_LINE_WIDTH = 1 / 16f;
    /** 空白蓝图选区框颜色（白色）。 */
    private static final int GHOST_BOX_COLOR_BLANK = 0xFFFFFF;
    /** 普通蓝图预览框颜色（默认，Create 风格蓝）。 */
    private static final int GHOST_BOX_COLOR_SPATIAL = 0x6886C5;
    /** 投影蓝图预览框颜色。 */
    private static final int GHOST_BOX_COLOR_PROJECTION = 0x73DA9A;
    /** 次元蓝图预览框颜色。 */
    private static final int GHOST_BOX_COLOR_DIMENSIONAL = 0xFC9274;
    /** 空置次元蓝图选区框颜色（黄色）。 */
    private static final int GHOST_BOX_COLOR_VOID = 0xFFFF00;
    /** 工程蓝图预览框颜色（#7C6FBD）。 */
    private static final int GHOST_BOX_COLOR_ENGINEERING = 0x7C6FBD;
    /** 项目蓝图「预览」范围框（紫，与工程蓝图的蓝紫区分开）。 */
    private static final int GHOST_BOX_COLOR_PROJECT = 0xBA68C8;
    /** 项目基准点（anchor）标记：穿过范围盒全高的一条金色竖线（无框）。与范围界面「中心点」那行同色。 */
    private static final int GHOST_COLOR_ANCHOR = 0xFFD54F;
    /** Create 超级胶水贴图（仅字符串引用，对 Create 零编译依赖）。 */
    private static final ResourceLocation CREATE_GLUE_TEXTURE = new ResourceLocation(
            "create", "textures/special/glue.png");
    /** 整盒在选区内的胶水框颜色（Create 绿）。 */
    private static final int GLUE_COLOR_INSIDE = 0x68C586;
    /** 跨界胶水框颜色（红，警告用）。 */
    private static final int GLUE_COLOR_CROSSING = 0xFF5555;
    /** 起终点角标线宽：仅比主框线宽多 2px 视觉增量（约 1/16 → 3/32）。 */
    private static final float GHOST_POINT_LINE_WIDTH = 3 / 32f;
    /** 起点格子框颜色（亮绿）。 */
    private static final int GHOST_COLOR_START = 0x00FF00;
    /** 终点格子框颜色（红）。 */
    private static final int GHOST_COLOR_END = 0xFF0000;

    /** 流体显示/过滤模式（6档循环）。 */
    public static com.xioyim.spatialblueprint.util.FluidDisplayMode fluidMode
            = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;

    /** 隐藏前 N 种用量最多的方块（0=关，1/2/3）。 */
    public static int hideTopN = 0;
    /** 当前需隐藏的方块 ID 集合（由 PlacementScreen 在模式切换时写入）。 */
    public static final java.util.Set<String> hiddenTopBlocks = new java.util.HashSet<>();

    // ── Preview broadcast ─────────────────────────────────────────────────
    /** Tick counter for periodic unlocked-preview C2S broadcasts. */
    private static int broadcastTickCounter = 0;
    /** Last broadcast origin, used for locked-preview deduplication. */
    @Nullable private static net.minecraft.core.BlockPos lastBroadcastOrigin = null;
    private static int lastBroadcastRotationOrdinal = -1;

    // ── Pre-filtered render list cache ────────────────────────────────────
    /** Cached render entry: pre-rotated state + relative offset, filter applied at build time. */
    private static final class RenderEntry {
        final BlockState state;
        final int tx, ty, tz;
        final BlockPos relPos;   // cached to avoid per-frame allocation
        final boolean isFluid;
        final boolean isLatePlace;
        final boolean isWhiteText; // 容器/告示牌/ANIMATED：白块 + lang 文本
        /**
         * 预算好的方块名，只有 {@link #isWhiteText} 的条目才有，其余为 null。
         *
         * <p>原来每帧对每个标签调一次 {@code state.getBlock().getName()}，那是一次
         * {@code MutableComponent} 分配 —— 名字在重建之间根本不会变，没有理由每帧重算。
         * 只给需要画标签的那个子集存，避免为十万个方块各留一个引用。</p>
         */
        @Nullable final net.minecraft.network.chat.Component label;
        RenderEntry(BlockState s, int tx, int ty, int tz, boolean fluid, boolean latePlace, boolean whiteText) {
            this.state = s; this.tx = tx; this.ty = ty; this.tz = tz;
            this.relPos = new BlockPos(tx, ty, tz);
            this.isFluid = fluid; this.isLatePlace = latePlace; this.isWhiteText = whiteText;
            this.label = whiteText ? s.getBlock().getName() : null;
        }
    }

    /** 预算好的实体标签：位置与名字都只随重建变化，不该每帧重算与重新分配。 */
    private record EntityLabel(int tx, int ty, int tz, net.minecraft.network.chat.Component name) {}
    private static final EntityLabel[] NO_ENTITY_LABELS = new EntityLabel[0];

    private static EntityLabel[] buildEntityLabels(BlueprintData data, BlockPos size, Rotation rot) {
        if (data == null || data.entities.isEmpty()) return NO_ENTITY_LABELS;
        EntityLabel[] out = new EntityLabel[data.entities.size()];
        int i = 0;
        for (BlueprintData.EntityEntry e : data.entities) {
            out[i++] = new EntityLabel(
                    transformX(e.tileX, e.tileY, e.tileZ, size, rot),
                    transformY(e.tileX, e.tileY, e.tileZ, size, rot),
                    transformZ(e.tileX, e.tileY, e.tileZ, size, rot),
                    entityLangName(e.type));
        }
        return out;
    }

    /** 画一组预算好的实体标签（黄色 1px 线框 + 实体名）。调用方已把坐标系平移到 origin。 */
    private static void drawEntityLabels(PoseStack ps, MultiBufferSource.BufferSource buf,
                                         Minecraft mc, EntityLabel[] labels) {
        for (EntityLabel e : labels) {
            ps.pushPose();
            ps.translate(e.tx(), e.ty(), e.tz());
            LevelRenderer.renderLineBox(ps, buf.getBuffer(RenderType.lines()),
                    0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                    1.0f, 1.0f, 0.0f, 0.9f, 1.0f, 1.0f, 0.0f);
            renderFloatingLabel(ps, buf, mc, e.name());
            ps.popPose();
        }
    }
    // Blueprint preview render list (keyed by blueprintId × rotation × hide settings)
    private static String      rlBpId     = null;
    private static Rotation    rlRotation = null;
    private static com.xioyim.spatialblueprint.util.FluidDisplayMode rlFluidMode
            = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
    private static int         rlHideTopN  = 0;
    private static final java.util.Set<String> rlHiddenSnapshot = new java.util.HashSet<>();
    private static RenderEntry[] renderEntries    = new RenderEntry[0];
    private static int           renderEntryCount = 0;
    /** Fluid + lateplace entries only (subset of renderEntries, iterated every frame). */
    private static RenderEntry[] specialEntries    = new RenderEntry[0];
    private static int           specialEntryCount = 0;
    /** 预算好的实体标签，随渲染列表一起重建。 */
    private static EntityLabel[] entityLabels      = NO_ENTITY_LABELS;
    // Engineering preview render list (keyed by data identity × hide settings)
    private static BlueprintData engRlData     = null;
    private static com.xioyim.spatialblueprint.util.FluidDisplayMode engRlFluidMode
            = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
    private static int           engRlHideTopN  = 0;
    private static final java.util.Set<String> engRlHiddenSnapshot = new java.util.HashSet<>();
    private static RenderEntry[] engRenderEntries    = new RenderEntry[0];
    private static int           engRenderEntryCount = 0;
    private static RenderEntry[] engSpecialEntries    = new RenderEntry[0];
    private static int           engSpecialEntryCount = 0;
    private static EntityLabel[] engEntityLabels      = NO_ENTITY_LABELS;

    // ── VBO cache — chunked to spread upload cost across frames ─────────────
    /** Blocks per VBO chunk. ~4 k blocks ≈ 1.3 MB upload ≈ 7 ms on render thread. */
    private static final int UPLOAD_CHUNK_SIZE = 4096;

    /**
     * 后台构建的两条泳道，各只有一条线程。
     *
     * <p>原来每个槽位在需要重建时自己 {@code new Thread}，槽位一多就是几条线程同时
     * tessellate（自己 + 工程 + 3 个他人 = 最多 5 条）。改成固定泳道后总并发恒为 2。</p>
     *
     * <p><b>分两条而不是一条</b>，是为了让自己的预览永远不必排在别人后面 ——
     * 一份十万方块的他人结构要跑好几百毫秒，自己转个方向却得立刻响应。
     * 自己这条泳道里 bp 与 eng 串行没有代价：主手要么拿空间/投影/次元蓝图、
     * 要么拿工程蓝图，渲染分派本就是互斥的 if/else，两者不会同帧重建。</p>
     */
    private static final java.util.concurrent.ExecutorService SELF_BUILDER  = newBuilderExecutor("self");
    private static final java.util.concurrent.ExecutorService OTHER_BUILDER = newBuilderExecutor("other");

    private static java.util.concurrent.ExecutorService newBuilderExecutor(String lane) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blueprint-vbo-" + lane);
            t.setDaemon(true);                    // 不阻止 JVM 退出
            t.setPriority(Thread.MIN_PRIORITY);   // 让路给渲染线程
            return t;
        });
    }

    /** 自己手上蓝图的预览槽位。 */
    private static final PreviewVboSlot bpSlot  = new PreviewVboSlot("bp",  SELF_BUILDER);
    /** 工程蓝图固定坐标预览的槽位。 */
    private static final PreviewVboSlot engSlot = new PreviewVboSlot("eng", SELF_BUILDER);

    /**
     * 其他玩家的完整结构预览槽位池（playerUUID → 槽位）。
     *
     * <p>只有被玩家显式 {@code /sbp download} 拉过数据的条目才会进池子；其余仍然只画线框。
     * 淘汰（下线 / 走远 / 被挤出上限 / 数据失效）在每帧开头统一做，立刻还显存。</p>
     */
    private static final java.util.Map<java.util.UUID, OtherPreviewRender> otherSlots =
            new java.util.HashMap<>();
    /** 同时渲染完整结构的人数上限；超出按距离淘汰最远的，降级回线框。 */
    private static final int OTHER_FULL_PREVIEW_CAP = 3;
    /** 超过这个距离只画线框 —— 那么远结构和标签都看不清，白耗显存与 CPU。 */
    private static final double OTHER_FULL_PREVIEW_MAX_DIST = 128.0;

    // ── Fluid solid-color sprite UV (cached on render thread, passed to async build) ──
    private static float   cachedWhiteU = 0f, cachedWhiteV = 0f;
    private static float   cachedWaterU0, cachedWaterV0, cachedWaterU1, cachedWaterV1;
    private static float   cachedLavaU0, cachedLavaV0, cachedLavaU1, cachedLavaV1;
    private static boolean whiteSpriteCached = false;

    /** Frame counter for throttled debug logging (log every N frames). */
    private static long dbgFrame = 0;
    private static final int DBG_INTERVAL = 10;
    private static long lastBlankPreviewLogMs = 0L;
    // ── 非法块扫描缓存（空白选区每帧全量扫描是 1.45M 体积 150ms 级卡顿根因）─────────
    private static BlockPos lastIllegalScanMin = null;
    private static BlockPos lastIllegalScanMax = null;
    private static long lastIllegalScanMs = 0L;
    private static final java.util.List<BlockPos> cachedIllegalPositions = new java.util.ArrayList<>();
    /** 右键空白蓝图 / 点击[创建]时强制重扫一次非法块（选区未变也能刷新世界变化） */
    public static void invalidateIllegalScanCache() {
        lastIllegalScanMin = null;
        lastIllegalScanMax = null;
        cachedIllegalPositions.clear();
    }

    // ── Debug gate ────────────────────────────────────────────────────────
    /**
     * Debug logs are gated behind this check: only fire when the local player
     * is in creative mode AND named "Yim".  This prevents log spam in production
     * while keeping detailed diagnostics available during development.
     *
     * Rule: LOGGER.warn() for real errors always fires (no gate).
     *       LOGGER.info() for diagnostics/perf always uses isDebugSession().
     */
    /**
     * Returns true when the local player is in creative mode AND has the
     * "/tag @s add Yim" tag applied.  All LOGGER.info diagnostic logs are
     * gated behind this check to stay silent in production.
     */
    public static boolean isDebugSession() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return mc.player.getTags().contains("Yim");
    }

    /** Looks up a static white sprite UV on the render thread so async buildChunks can use it. */
    private static void ensureWhiteSprite() {
        if (whiteSpriteCached) return;
        try {
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite =
                    Minecraft.getInstance()
                            .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                            .apply(new net.minecraft.resources.ResourceLocation("minecraft", "block/white_concrete"));
            cachedWhiteU = (sprite.getU0() + sprite.getU1()) * 0.5f;
            cachedWhiteV = (sprite.getV0() + sprite.getV1()) * 0.5f;
        } catch (Exception ignored) {
            cachedWhiteU = 0f; cachedWhiteV = 0f;
        }
        // 水/岩浆静态贴图（图集内 UV 范围），供静态贴图渲染
        try {
            net.minecraft.client.renderer.texture.TextureAtlasSprite waterSprite =
                    Minecraft.getInstance()
                            .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                            .apply(new net.minecraft.resources.ResourceLocation("minecraft", "block/water_still"));
            cachedWaterU0 = waterSprite.getU0(); cachedWaterV0 = waterSprite.getV0();
            cachedWaterU1 = waterSprite.getU1(); cachedWaterV1 = waterSprite.getV1();
        } catch (Exception ignored) {
            cachedWaterU0 = 0f; cachedWaterV0 = 0f; cachedWaterU1 = 1f; cachedWaterV1 = 1f;
        }
        try {
            net.minecraft.client.renderer.texture.TextureAtlasSprite lavaSprite =
                    Minecraft.getInstance()
                            .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                            .apply(new net.minecraft.resources.ResourceLocation("minecraft", "block/lava_still"));
            cachedLavaU0 = lavaSprite.getU0(); cachedLavaV0 = lavaSprite.getV0();
            cachedLavaU1 = lavaSprite.getU1(); cachedLavaV1 = lavaSprite.getV1();
        } catch (Exception ignored) {
            cachedLavaU0 = 0f; cachedLavaV0 = 0f; cachedLavaU1 = 1f; cachedLavaV1 = 1f;
        }
        whiteSpriteCached = true;
    }

    /**
     * Emits a unit cube into a BLOCK-format BufferBuilder, sampling the given
     * texture-sprite UV range on every face with the given tint/alpha — for
     * water/lava static still-frame textures. Must be called with the PoseStack
     * translated to the block's local origin.
     */
    private static void emitTexturedCube(BufferBuilder bb, org.joml.Matrix4f mx,
                                          float u0, float v0, float u1, float v1,
                                          float tr, float tg, float tb, float ta) {
        final int LM = 15728880; // full-bright (sky=15, block=15)
        // Top +Y
        bb.vertex(mx,0,1,0).color(tr,tg,tb,ta).uv(u0,v1).uv2(LM).normal(0,1,0).endVertex();
        bb.vertex(mx,0,1,1).color(tr,tg,tb,ta).uv(u0,v0).uv2(LM).normal(0,1,0).endVertex();
        bb.vertex(mx,1,1,1).color(tr,tg,tb,ta).uv(u1,v0).uv2(LM).normal(0,1,0).endVertex();
        bb.vertex(mx,1,1,0).color(tr,tg,tb,ta).uv(u1,v1).uv2(LM).normal(0,1,0).endVertex();
        // Bottom -Y
        bb.vertex(mx,0,0,0).color(tr,tg,tb,ta).uv(u0,v0).uv2(LM).normal(0,-1,0).endVertex();
        bb.vertex(mx,1,0,0).color(tr,tg,tb,ta).uv(u1,v0).uv2(LM).normal(0,-1,0).endVertex();
        bb.vertex(mx,1,0,1).color(tr,tg,tb,ta).uv(u1,v1).uv2(LM).normal(0,-1,0).endVertex();
        bb.vertex(mx,0,0,1).color(tr,tg,tb,ta).uv(u0,v1).uv2(LM).normal(0,-1,0).endVertex();
        // North -Z
        bb.vertex(mx,0,0,0).color(tr,tg,tb,ta).uv(u0,v0).uv2(LM).normal(0,0,-1).endVertex();
        bb.vertex(mx,0,1,0).color(tr,tg,tb,ta).uv(u0,v1).uv2(LM).normal(0,0,-1).endVertex();
        bb.vertex(mx,1,1,0).color(tr,tg,tb,ta).uv(u1,v1).uv2(LM).normal(0,0,-1).endVertex();
        bb.vertex(mx,1,0,0).color(tr,tg,tb,ta).uv(u1,v0).uv2(LM).normal(0,0,-1).endVertex();
        // South +Z
        bb.vertex(mx,0,0,1).color(tr,tg,tb,ta).uv(u0,v0).uv2(LM).normal(0,0,1).endVertex();
        bb.vertex(mx,1,0,1).color(tr,tg,tb,ta).uv(u1,v0).uv2(LM).normal(0,0,1).endVertex();
        bb.vertex(mx,1,1,1).color(tr,tg,tb,ta).uv(u1,v1).uv2(LM).normal(0,0,1).endVertex();
        bb.vertex(mx,0,1,1).color(tr,tg,tb,ta).uv(u0,v1).uv2(LM).normal(0,0,1).endVertex();
        // West -X
        bb.vertex(mx,0,0,0).color(tr,tg,tb,ta).uv(u0,v0).uv2(LM).normal(-1,0,0).endVertex();
        bb.vertex(mx,0,0,1).color(tr,tg,tb,ta).uv(u1,v0).uv2(LM).normal(-1,0,0).endVertex();
        bb.vertex(mx,0,1,1).color(tr,tg,tb,ta).uv(u1,v1).uv2(LM).normal(-1,0,0).endVertex();
        bb.vertex(mx,0,1,0).color(tr,tg,tb,ta).uv(u0,v1).uv2(LM).normal(-1,0,0).endVertex();
        // East +X
        bb.vertex(mx,1,0,1).color(tr,tg,tb,ta).uv(u0,v0).uv2(LM).normal(1,0,0).endVertex();
        bb.vertex(mx,1,0,0).color(tr,tg,tb,ta).uv(u1,v0).uv2(LM).normal(1,0,0).endVertex();
        bb.vertex(mx,1,1,0).color(tr,tg,tb,ta).uv(u1,v1).uv2(LM).normal(1,0,0).endVertex();
        bb.vertex(mx,1,1,1).color(tr,tg,tb,ta).uv(u0,v1).uv2(LM).normal(1,0,0).endVertex();
    }

    // ── Alignment mode ────────────────────────────────────────────────────
    public enum AlignMode {
        /** 关：no preview rendering. Default state. */
        OFF,
        /** 中心：horizontal center of bounding box aligns with target. */
        CENTER,
        /** 起点：min corner of rotated bounding box aligns with target. */
        START,
        /** 终点：max corner of rotated bounding box aligns with target. */
        END;

        /** Cycle: OFF → CENTER → START → END → OFF */
        public AlignMode next() {
            AlignMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    /**
     * Reads the {@link AlignMode} stored in the given item's NBT.
     * Falls back to {@link AlignMode#OFF} for blank/unset stacks.
     * This replaces the former static {@code currentAlignMode} field so each
     * blueprint item carries its own independent alignment state.
     */
    public static AlignMode getAlignMode(ItemStack stack) {
        String raw = SpatialBlueprintItem.getAlignModeStr(stack);
        try { return AlignMode.valueOf(raw); } catch (Exception e) { return AlignMode.OFF; }
    }

    /**
     * Writes {@code mode} into the given item's NBT (client-side only; no server packet needed
     * because AlignMode is a rendering hint that only affects the final computed origin sent at
     * deploy time via {@link com.xioyim.spatialblueprint.network.PlaceBlueprintMessage}).
     */
    public static void setAlignMode(ItemStack stack, AlignMode mode) {
        SpatialBlueprintItem.setAlignModeStr(stack, mode.name());
    }

    /**
     * X-axis alignment offset so the selected reference point sits at the target.
     * Only X and Z are affected; Y always starts from ground level.
     */
    public static int getAlignOffsetX(BlockPos size, Rotation rotation, AlignMode mode) {
        int rotMaxX = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? size.getZ() - 1 : size.getX() - 1;
        return switch (mode) {
            case END    -> -rotMaxX;
            case CENTER -> -rotMaxX / 2;
            default     -> 0;   // OFF and START: no horizontal X shift
        };
    }

    /** Z-axis alignment offset (same logic as X). */
    public static int getAlignOffsetZ(BlockPos size, Rotation rotation, AlignMode mode) {
        int rotMaxZ = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? size.getX() - 1 : size.getZ() - 1;
        return switch (mode) {
            case END    -> -rotMaxZ;
            case CENTER -> -rotMaxZ / 2;
            default     -> 0;
        };
    }

    /**
     * 基于结构实际 bbox（相对 min/max）的对齐偏移，保证起点/终点/中心对准准星方块。
     * 稀疏结构（bbox 不从 0 开始）时比 {@link #getAlignOffsetX} 更准确。
     */
    public static int alignOffsetFromMinMax(int min, int max, AlignMode mode) {
        return switch (mode) {
            case END    -> -max;
            case CENTER -> -(min + max) / 2;
            default     -> -min;   // OFF / START
        };
    }

    /** Bottom-layer center of the current preview, updated every render frame. Null when no preview. */
    @Nullable
    private static BlockPos lastBottomCenter = null;

    /** Min/max corners of the current preview bounding box (world coords). Null when no preview. */
    @Nullable private static BlockPos lastPreviewMin = null;
    @Nullable private static BlockPos lastPreviewMax = null;

    // ── 放置加载动画（从底部往上填充的 ghost box）───────────────────────────
    private static boolean  placementAnimActive       = false;
    private static long     placementAnimStartTick    = 0;
    private static long     placementAnimDurationTick = 0;
    private static long     placementAnimFadeStart    = -1;
    @Nullable private static BlockPos placementAnimOrigin = null;
    @Nullable private static BlockPos placementAnimSize   = null;
    private static int      placementAnimColor        = 0x6886C5;
    /** 服务端推送的真实放置进度（-1 = 尚未收到；0~1 直接驱动边框）。 */
    private static float    placementAnimServerProgress = -1f;
    /** 服务端已发"全部落地完成"（进度 1.0），进入完成态 + 淡出。 */
    private static boolean  placementAnimServerDone    = false;
    /** 淡出时长（tick）：2 秒。 */
    private static final long PLACEMENT_FADE_TICKS = 40;

    /** 放置开始：启动从底部往上填充的加载动画。 */
    public static void startPlacementAnimation(BlockPos origin, BlockPos size, int durationSeconds, int color) {
        placementAnimActive = true;
        placementAnimStartTick = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : 0;
        placementAnimDurationTick = Math.max(1, durationSeconds) * 20L;
        placementAnimFadeStart = -1;
        placementAnimServerProgress = -1f;
        placementAnimServerDone = false;
        placementAnimOrigin = origin;
        placementAnimSize = size;
        placementAnimColor = color;
    }

    /** 服务端推送放置进度：0~1 跟随普通方块真实放置；1.0 = 全部落地完成，立即进入完成态。 */
    public static void setPlacementServerProgress(float progress) {
        if (!placementAnimActive) return;
        if (progress >= 1f) {
            placementAnimServerDone = true;
            placementAnimServerProgress = 1f;
        } else if (progress >= 0f) {
            placementAnimServerProgress = progress;
        }
    }

    /** 取消放置动画（放置取消/物品变更时）。 */
    public static void stopPlacementAnimation() {
        placementAnimActive = false;
        Outliner.getInstance().remove("placement_anim");
        Outliner.getInstance().remove("placement_anim_fill");
    }

    /**
     * 开口盒线框：仅渲染底部 4 边 + 4 根竖边（无顶部正方形），用于放置加载动画填充。
     */
    private static final class OpenTopAABBOutline extends com.xioyim.spatialblueprint.client.outliner.AABBOutline {
        OpenTopAABBOutline(AABB bb) {
            super(bb);
        }

        @Override
        protected void renderBoxFaces(PoseStack ms, MultiBufferSource buffer, boolean cull,
                                      net.minecraft.core.Direction highlightedFace, org.joml.Vector3f minPos,
                                      org.joml.Vector3f maxPos, org.joml.Vector4f color, int lightmap) {
            // 仅画底面 + 4 个侧面，隐藏顶面（顶部格面）
            PoseStack.Pose pose = ms.last();
            renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, net.minecraft.core.Direction.DOWN, color, lightmap);
            renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, net.minecraft.core.Direction.NORTH, color, lightmap);
            renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, net.minecraft.core.Direction.SOUTH, color, lightmap);
            renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, net.minecraft.core.Direction.WEST, color, lightmap);
            renderBoxFace(pose, buffer, cull, highlightedFace, minPos, maxPos, net.minecraft.core.Direction.EAST, color, lightmap);
        }

        @Override
        protected void renderBoxEdges(PoseStack ms, VertexConsumer consumer, org.joml.Vector3f minPos,
                                      org.joml.Vector3f maxPos, float lineWidth, org.joml.Vector4f color,
                                      int lightmap, boolean disableNormals) {
            org.joml.Vector3f origin = originTemp;
            PoseStack.Pose pose = ms.last();
            float lx = maxPos.x() - minPos.x();
            float ly = maxPos.y() - minPos.y();
            float lz = maxPos.z() - minPos.z();
            float minX = minPos.x(), minY = minPos.y(), minZ = minPos.z();
            float maxX = maxPos.x(), maxZ = maxPos.z();

            // 底部 4 边
            origin.set(minX, minY, minZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.EAST, lx, lineWidth, color, lightmap, disableNormals);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.SOUTH, lz, lineWidth, color, lightmap, disableNormals);
            origin.set(maxX, minY, minZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.SOUTH, lz, lineWidth, color, lightmap, disableNormals);
            origin.set(minX, minY, maxZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.EAST, lx, lineWidth, color, lightmap, disableNormals);

            // 4 根竖边（无顶部正方形）
            origin.set(minX, minY, minZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.UP, ly, lineWidth, color, lightmap, disableNormals);
            origin.set(maxX, minY, minZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.UP, ly, lineWidth, color, lightmap, disableNormals);
            origin.set(minX, minY, maxZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.UP, ly, lineWidth, color, lightmap, disableNormals);
            origin.set(maxX, minY, maxZ);
            bufferCuboidLine(pose, consumer, origin, net.minecraft.core.Direction.UP, ly, lineWidth, color, lightmap, disableNormals);
        }
    }

    /** 每帧推进放置加载动画（在 renderOutlines 前调用，由 onRenderLevel 触发）。 */
    private static void updatePlacementAnimation() {
        if (!placementAnimActive) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || placementAnimOrigin == null || placementAnimSize == null) {
            stopPlacementAnimation();
            return;
        }
        long now = mc.level.getGameTime();
        // 优先使用服务端推送的真实进度；尚未收到时退化为本地线性（防首帧空白）
        float localProgress = placementAnimDurationTick > 0
                ? (float) (now - placementAnimStartTick) / placementAnimDurationTick
                : 1f;
        float progress = placementAnimServerProgress >= 0f ? placementAnimServerProgress : localProgress;

        int x = placementAnimOrigin.getX();
        int y = placementAnimOrigin.getY();
        int z = placementAnimOrigin.getZ();
        int sx = placementAnimSize.getX();
        int sy = placementAnimSize.getY();
        int sz = placementAnimSize.getZ();

        // 白色完整线框（始终全高显示，无面高亮仅棱线）
        //
        // ⚠ 白框必须用 renderPriority(1)、填充线用 (3)：Outliner 的优先级只有两档
        // （<2 与 >=2 分两个 batch），同一档内按 HashMap 的迭代顺序绘制 —— 顺序不确定。
        // 两者都给 3 的话，细白线会随机盖在更粗的填充线上，看起来像被“切断”。
        // 降一档等于强制让填充线后画、压在白框之上。
        AABB fullBox = new AABB(x, y, z, x + sx, y + sy, z + sz);
        float frameW = GHOST_BOX_LINE_WIDTH * 2f;   // 白色框 1/8
        float fillW  = GHOST_BOX_LINE_WIDTH * 4f;   // 填充线 2/8
        float doneW  = GHOST_BOX_LINE_WIDTH * 6f;   // 完成蓝框 3/8

        if (placementAnimServerDone) {
            // 全部落地完成：填充闭合含顶，2 秒淡出（线宽递减消失）
            if (placementAnimFadeStart < 0) placementAnimFadeStart = now;
            float fade = (float) (now - placementAnimFadeStart) / PLACEMENT_FADE_TICKS;
            if (fade >= 1f) {
                stopPlacementAnimation();
                return;
            }
            AABB fillBox = new AABB(x, y, z, x + sx, y + sy, z + sz);
            Outliner.getInstance().chaseAABB("placement_anim", fullBox)
                    .colored(0xFFFFFF)
                    .noDepth()
                    .renderPriority(1)
                    .lineWidth(frameW * (1f - fade));
            // 完成框：类型色填充线颜色 + 3/8 粗（淡出时线宽递减）
            Outliner.getInstance().showOutline("placement_anim_fill", new com.xioyim.spatialblueprint.client.outliner.AABBOutline(fillBox))
                    .colored(placementAnimColor)
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .noDepth()
                    .renderPriority(3)
                    .lineWidth(doneW * (1f - fade));
        } else if (progress < 1f) {
            // 放置中：白色完整框(1/8) + 类型色填充线(2/8)（半透明棋盘格面 = 高亮，开口盒不含顶）
            int h = Math.max(1, (int) Math.ceil(sy * progress));
            AABB fillBox = new AABB(x, y, z, x + sx, y + h, z + sz);
            Outliner.getInstance().chaseAABB("placement_anim", fullBox)
                    .colored(0xFFFFFF)
                    .noDepth()
                    .renderPriority(1)
                    .lineWidth(frameW);
            Outliner.getInstance().showOutline("placement_anim_fill", new OpenTopAABBOutline(fillBox))
                    .colored(placementAnimColor)
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .noDepth()
                    .renderPriority(3)
                    .lineWidth(fillW);
        } else {
            // 普通方块已全部放置、等待 dependent/实体/late 强制落地：顶格闭合但未淡出
            AABB fillBox = new AABB(x, y, z, x + sx, y + sy, z + sz);
            Outliner.getInstance().chaseAABB("placement_anim", fullBox)
                    .colored(0xFFFFFF)
                    .noDepth()
                    .renderPriority(1)
                    .lineWidth(frameW);
            Outliner.getInstance().showOutline("placement_anim_fill", new com.xioyim.spatialblueprint.client.outliner.AABBOutline(fillBox))
                    .colored(placementAnimColor)
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .noDepth()
                    .renderPriority(3)
                    .lineWidth(doneW);
        }
    }

    // ── Bounding-box cache for renderBlueprintPreview (M6 / M11) ────────────
    /** The blueprintId whose relative bounding box is stored below.
     *  Using the ID string (rather than the BlueprintData object reference) means the
     *  cache survives single-player frames where BlueprintStorage.load() returns a new
     *  object each call — fixing the per-frame cache miss introduced by M6 (M11). */
    @Nullable private static String cachedBBId = null;
    private static Rotation cachedBBRotation = Rotation.NONE;
    /** Relative (to origin) min/max extents of the last bounding-box compute. */
    private static int cachedRelMinX, cachedRelMinY, cachedRelMinZ;
    private static int cachedRelMaxX, cachedRelMaxY, cachedRelMaxZ;
    /** True when the fields above contain valid data (blueprint has ≥ 1 block). */
    private static boolean bbCacheValid = false;

    @Nullable public static BlockPos getLastPreviewMin() { return lastPreviewMin; }
    @Nullable public static BlockPos getLastPreviewMax() { return lastPreviewMax; }

    /** 空置次元蓝图的捕捉范围预览（[查看范围] 按钮触发）。 */
    @Nullable private static BlockPos voidPreviewMin = null;
    @Nullable private static BlockPos voidPreviewMax = null;
    // 非法方块扫描缓存（与空白选区同款：选区变化时才重扫，每帧只重放缓存）
    @Nullable private static BlockPos lastVoidIllegalMin = null;
    @Nullable private static BlockPos lastVoidIllegalMax = null;
    private static final java.util.List<BlockPos> cachedVoidIllegalPositions = new java.util.ArrayList<>();

    public static void setVoidPreview(BlockPos min, BlockPos max) {
        voidPreviewMin = min;
        voidPreviewMax = max;
        // 每次点 [查看范围] 强制重扫一次非法块，反映点按钮时的最新世界状态
        lastVoidIllegalMin = null;
        lastVoidIllegalMax = null;
        cachedVoidIllegalPositions.clear();
    }
    public static void clearVoidPreview() {
        voidPreviewMin = null;
        voidPreviewMax = null;
    }

    /**
     * 工程蓝图固定坐标预览（由 [开启预览] 按钮触发）。
     * 状态在 {@link ClientEngineeringPreview}，脱手后仍显示，直到关闭 / 走远 / 换维度 / 登出。
     */
    public static void setEngineeringPreview(String blueprintId, BlockPos min, BlockPos max, String dimension) {
        ClientEngineeringPreview.set(blueprintId, min, max, dimension);
    }

    public static void clearEngineeringPreview() {
        ClientEngineeringPreview.clear();
    }

    @Nullable
    public static BlockPos getLastBottomCenter() {
        return lastBottomCenter;
    }

    // ── Render event ─────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        time += 1;

        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof BlankBlueprintItem) {
            renderBlankBlueprintSelection(mc, player, mainHand, event);
        } else if (mainHand.getItem() instanceof VoidDimensionalBlueprintItem) {
            renderVoidDimensionalSelection(mc, event);
        } else if (mainHand.getItem() instanceof EngineeringBlueprintItem) {
            // 工程 / 子工程预览已脱离手持：由下方 renderEngineeringPreviewIfActive 统一渲染
        } else if (mainHand.getItem() instanceof DimensionalBlueprintItem) {
            renderBlueprintPreview(mc, player, mainHand, event, false, GHOST_BOX_COLOR_DIMENSIONAL);
        } else if (mainHand.getItem() instanceof SpatialBlueprintItem) {
            renderBlueprintPreview(mc, player, mainHand, event, false, GHOST_BOX_COLOR_SPATIAL);
        } else if (mainHand.getItem() instanceof ProjectionBlueprintItem) {
            renderBlueprintPreview(mc, player, mainHand, event, true, GHOST_BOX_COLOR_PROJECTION);
        } else if (mainHand.getItem() instanceof com.xioyim.spatialblueprint.item.ProjectBlueprintItem) {
            renderProjectBlueprint(mc, mainHand);
        } else {
            lastBottomCenter = null;
            clearVoidPreview();
            // 副手直开：主手不是任何蓝图相关物品时，副手项目蓝图的编辑态（临时两点 / 中心线）也要画
            ItemStack offHand = player.getOffhandItem();
            if (offHand.getItem() instanceof com.xioyim.spatialblueprint.item.ProjectBlueprintItem) {
                renderProjectBlueprint(mc, offHand);
            }
        }

        // ── 工程预览（脱手后仍显示；登记在 ClientEngineeringPreview）──────────
        renderEngineeringPreviewIfActive(mc, player, event);

        // ── 项目预览框（脱手后仍显示；多个项目各自一框）────────────────────────
        renderProjectPreviews(mc, player);

        // ── 子工程预览线（规划模式 ◎；脱手后仍显示，多个子工程各自一线）──────────
        renderSubProjectPreviews(mc, player);

        // ── Render other players' blueprint outline boxes ─────────────────
        if (ClientPreviewSettings.isEnabled() && !ClientOtherPreviewCache.isEmpty()) {
            renderOtherPlayerPreviews(event);
        } else {
            // 预览被关掉、或所有人都撤了预览 —— 淘汰逻辑在上面那个方法里，
            // 这里不兜底的话槽位会连着显存一起留在池子中
            disposeAllOtherSlots("预览已关闭或列表为空");
        }

        // 放置加载动画（从底部往上填充的 ghost box）
        updatePlacementAnimation();

        renderOutlines(event);
    }

    /** Renders all live outliner entries (Create-style ghost boxes). */
    private static void renderOutlines(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        // NOTE: at AFTER_PARTICLES the event pose stack has NO camera translation;
        // AABBOutline shifts its box by -camera internally (same convention as Create).
        ps.pushPose();
        MultiBufferSource.BufferSource bufSrc = mc.renderBuffers().bufferSource();
        Outliner.getInstance().renderOutlines(ps, bufSrc, camera, (float) mc.getPartialTick());
        ps.popPose();
        bufSrc.endBatch();
    }

    /** Outline color for other players' blueprint preview boxes (RGB). */
    private static final float OB_R = 0.25f, OB_G = 0.75f, OB_B = 1.0f;

    /**
     * 他人预览框的颜色 —— 按蓝图分类取，与本人预览框保持一致。
     *
     * <p>分类为空（旧客户端/服务端没带这个字段）时回落到默认色，
     * 而不是一律画成空间蓝 —— 那正是「绿色投影蓝图显示成蓝色边框」的成因。</p>
     */
    private static int otherPreviewColor(String category) {
        return switch (category == null ? "" : category) {
            case "project", "template" -> GHOST_BOX_COLOR_PROJECTION;
            case "dimensional"         -> GHOST_BOX_COLOR_DIMENSIONAL;
            case "engineering"         -> GHOST_BOX_COLOR_ENGINEERING;
            case "create"              -> GHOST_BOX_COLOR_SPATIAL;
            default -> ((int) (OB_R * 255) << 16) | ((int) (OB_G * 255) << 8) | (int) (OB_B * 255);
        };
    }

    /** 预览包围盒中心到摄像机的距离，用于距离剔除与超额淘汰的排序。 */
    private static double previewDistance(ClientOtherPreviewCache.Entry e, Vec3 camera) {
        net.minecraft.world.level.block.Rotation rot =
                net.minecraft.world.level.block.Rotation.values()[e.rotation & 3];
        boolean swap = rot == net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                    || rot == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
        double w = swap ? e.sizeZ : e.sizeX;
        double d = swap ? e.sizeX : e.sizeZ;
        return camera.distanceTo(new Vec3(
                e.origin.getX() + w / 2.0,
                e.origin.getY() + e.sizeY / 2.0,
                e.origin.getZ() + d / 2.0));
    }

    /** 释放整个其他玩家槽位池（预览关闭、缓存清空、世界卸载）。 */
    private static void disposeAllOtherSlots(String reason) {
        if (otherSlots.isEmpty()) return;
        int n = otherSlots.size();
        for (OtherPreviewRender r : otherSlots.values()) r.dispose();
        otherSlots.clear();
        if (isDebugSession())
            SpatialBlueprintMod.LOGGER.info("[VBO][DBG] 释放全部他人预览槽位×{}（{}）", n, reason);
    }

    private static void renderOtherPlayerPreviews(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        // ── 选出要画完整结构的那几个：已下载数据 + 在距离内 + 按距离取前 N ──
        java.util.List<ClientOtherPreviewCache.Entry> full = new java.util.ArrayList<>();
        for (ClientOtherPreviewCache.Entry e : ClientOtherPreviewCache.all()) {
            if (e.origin == null || e.fullData == null) continue;
            if (previewDistance(e, camera) <= OTHER_FULL_PREVIEW_MAX_DIST) full.add(e);
        }
        if (full.size() > OTHER_FULL_PREVIEW_CAP) {
            full.sort(java.util.Comparator.comparingDouble(e -> previewDistance(e, camera)));
            full = full.subList(0, OTHER_FULL_PREVIEW_CAP);
        }
        java.util.Set<java.util.UUID> keep = new java.util.HashSet<>();
        for (ClientOtherPreviewCache.Entry e : full) keep.add(e.playerUUID);

        // 淘汰不再入选的槽位（下线 / 走远 / 被挤出上限 / fullData 被清）
        otherSlots.entrySet().removeIf(en -> {
            if (keep.contains(en.getKey())) return false;
            en.getValue().dispose();
            return true;
        });

        // ── 完整结构 ────────────────────────────────────────────────────────
        for (ClientOtherPreviewCache.Entry e : full) {
            OtherPreviewRender r = otherSlots.computeIfAbsent(
                    e.playerUUID, id -> new OtherPreviewRender(e.playerName));
            net.minecraft.world.level.block.Rotation rot =
                    net.minecraft.world.level.block.Rotation.values()[e.rotation & 3];
            r.rebuildIfNeeded(e.fullData, e.blueprintId, rot);
            ensureWhiteSprite();
            r.slot.startBuildIfDirty(mc.level, e.origin, r.entries, r.entryCount);
            r.slot.uploadOneChunk();
            r.slot.draw(event, e.origin, camera);
            r.drawLabels(event, mc, e.origin, camera);
        }

        // ── 线框：所有人都画，包括只有边框的那些 ────────────────────────────
        for (ClientOtherPreviewCache.Entry e : ClientOtherPreviewCache.all()) {
            if (e.origin == null) continue;

            // Apply rotation to get actual bounding box dimensions
            net.minecraft.world.level.block.Rotation rot =
                    net.minecraft.world.level.block.Rotation.values()[e.rotation & 3];
            int w = (rot == net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                  || rot == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90)
                    ? e.sizeZ : e.sizeX;
            int h = e.sizeY;
            int d = (rot == net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                  || rot == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90)
                    ? e.sizeX : e.sizeZ;

            AABB box = new AABB(e.origin.getX(), e.origin.getY(), e.origin.getZ(),
                    e.origin.getX() + w, e.origin.getY() + h, e.origin.getZ() + d);

            // 颜色跟随蓝图类型，与本人预览框一致 —— 广播里带了 category，
            // 不需要等蓝图数据下载。旧版本客户端/服务端没有这个字段时回落到默认色。
            int color = otherPreviewColor(e.category);
            Outliner.getInstance().chaseAABB("other_" + e.playerUUID, box)
                    .colored(color)
                    .noDepth()
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
        }
    }

    // ── Client tick: auto-cancel distance check ───────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        Outliner.getInstance().tickOutlines();

        // 预览广播必须在下面那堆 early-return 之前跑。
        // 它自己会处理「手上没蓝图 / 预览已关 / 无数据」并向服务端发清除包；
        // 放在 return 之后的话，玩家一把蓝图丢出去就直接 return 了，
        // 清除包永远发不出去 —— 别人视角里那个空预览框就会永久卡在原地。
        tickPreviewBroadcast(mc, player);

        // Void preview distance check — clear silently when player walks too far away
        if (voidPreviewMin != null && voidPreviewMax != null) {
            double bCX = (voidPreviewMin.getX() + voidPreviewMax.getX() + 1) / 2.0;
            double bCY = (voidPreviewMin.getY() + voidPreviewMax.getY() + 1) / 2.0;
            double bCZ = (voidPreviewMin.getZ() + voidPreviewMax.getZ() + 1) / 2.0;
            int dx = voidPreviewMax.getX() - voidPreviewMin.getX();
            int dy = voidPreviewMax.getY() - voidPreviewMin.getY();
            int dz = voidPreviewMax.getZ() - voidPreviewMin.getZ();
            double halfDiag = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) / 2.0;
            double cancelDist = halfDiag + ConfigAccess.get(BlueprintConfig.RENDER_CANCEL_MARGIN);
            if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
                clearVoidPreview();
            }
        }

        // 工程预览的自动取消已随预览脱手改成每帧统一规则（renderEngineeringPreviewIfActive）：
        // 距离超限 / 换维度 / 缓存被清 → 静默清除，不再依赖主手 stack。

        // Find SpatialBlueprint or ProjectionBlueprint in hand (must come before AlignMode check
        // because AlignMode is now stored per-item in NBT, not in a shared static field).
        ItemStack stack = player.getMainHandItem();
        boolean isProjection = false;
        if (stack.getItem() instanceof ProjectionBlueprintItem) {
            isProjection = true;
        } else if (!(stack.getItem() instanceof SpatialBlueprintItem)) {
            stack = player.getOffhandItem();
            if (stack.getItem() instanceof ProjectionBlueprintItem) {
                isProjection = true;
            } else if (!(stack.getItem() instanceof SpatialBlueprintItem)) {
                return;
            }
        }

        // Early-exit when the item's AlignMode is OFF (no preview active).
        AlignMode alignMode = getAlignMode(stack);
        if (alignMode == AlignMode.OFF) return;

        BlueprintData data = isProjection
                ? ProjectionBlueprintItem.getBlueprintDataClient(stack)
                : SpatialBlueprintItem.getBlueprintDataClient(stack);
        if (data == null) return;

        // Resolve current target position (locked or cursor)
        BlockPos targetPos;
        if (SpatialBlueprintItem.isLocked(stack)) {
            targetPos = SpatialBlueprintItem.getLockedPos(stack);
        } else {
            targetPos = getPreviewTarget(mc, ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE));
        }
        if (targetPos == null) return; // no target → no building → nothing to cancel

        // Replicate the same origin/center computation as renderBlueprintPreview
        Rotation rotation = SpatialBlueprintItem.getRotation(stack);
        BlockPos size = data.size;
        int xOff = SpatialBlueprintItem.getXOffset(stack);
        int yOff = SpatialBlueprintItem.getYOffset(stack);
        int zOff = SpatialBlueprintItem.getZOffset(stack);
        int alignOffX = getAlignOffsetX(size, rotation, alignMode);
        int alignOffZ = getAlignOffsetZ(size, rotation, alignMode);
        BlockPos origin = targetPos.above().offset(xOff + alignOffX, yOff, zOff + alignOffZ);

        int rotMaxX = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? size.getZ() - 1 : size.getX() - 1;
        int rotMaxY = size.getY() - 1;
        int rotMaxZ = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? size.getX() - 1 : size.getZ() - 1;

        double bCX = origin.getX() + rotMaxX / 2.0;
        double bCY = origin.getY() + rotMaxY / 2.0;
        double bCZ = origin.getZ() + rotMaxZ / 2.0;

        double halfDiag = Math.sqrt((double) rotMaxX * rotMaxX
                + (double) rotMaxY * rotMaxY
                + (double) rotMaxZ * rotMaxZ) / 2.0;
        double cancelDist = halfDiag + ConfigAccess.get(BlueprintConfig.RENDER_CANCEL_MARGIN);

        if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
            // Close whichever blueprint screen is open
            if (mc.screen instanceof PlacementScreen) {
                mc.setScreen(null);
            }
            // Reset AlignMode in the item NBT (no longer a shared static field).
            setAlignMode(stack, AlignMode.OFF);
            lastBottomCenter = null;
            lastPreviewMin   = null;
            lastPreviewMax   = null;
            // 自动取消时清除客户端缓存（等效于玩家手动取消）
            String bpId = SpatialBlueprintItem.getBlueprintId(stack);
            if (bpId != null) ClientBlueprintCache.remove(bpId);
            // Reset placement state on the client stack so next open starts fresh.
            SpatialBlueprintItem.setXOffset(stack, 0);
            SpatialBlueprintItem.setYOffset(stack, 0);
            SpatialBlueprintItem.setZOffset(stack, 0);
            SpatialBlueprintItem.setRotation(stack, Rotation.NONE);
            SpatialBlueprintItem.setLocked(stack, false);
            sendPreviewClear();
        }

    }

    // ── Preview broadcast helpers ─────────────────────────────────────────

    /**
     * Called each client tick to handle periodic C2S preview position broadcasts.
     * Locked previews: broadcast immediately on change (called externally).
     * Unlocked previews: broadcast every PREVIEW_BROADCAST_INTERVAL seconds.
     */
    private static void tickPreviewBroadcast(Minecraft mc, Player player) {
        // Find blueprint stack in hand
        ItemStack stack = player.getMainHandItem();
        boolean isSpatial = stack.getItem() instanceof SpatialBlueprintItem;
        boolean isProj    = stack.getItem() instanceof ProjectionBlueprintItem;
        if (!isSpatial && !isProj) {
            stack = player.getOffhandItem();
            isSpatial = stack.getItem() instanceof SpatialBlueprintItem;
            isProj    = stack.getItem() instanceof ProjectionBlueprintItem;
        }

        if (!isSpatial && !isProj) {
            // No blueprint in hand — clear if we had one
            if (lastBroadcastOrigin != null) {
                sendPreviewClear();
            }
            return;
        }

        AlignMode alignMode = getAlignMode(stack);
        if (alignMode == AlignMode.OFF) {
            if (lastBroadcastOrigin != null) sendPreviewClear();
            return;
        }

        BlueprintData data = isProj
                ? com.xioyim.spatialblueprint.item.ProjectionBlueprintItem.getBlueprintDataClient(stack)
                : SpatialBlueprintItem.getBlueprintDataClient(stack);
        if (data == null) {
            if (lastBroadcastOrigin != null) sendPreviewClear();
            return;
        }

        boolean locked = SpatialBlueprintItem.isLocked(stack);
        int intervalTicks = ConfigAccess.get(BlueprintConfig.PREVIEW_BROADCAST_INTERVAL) * 20;

        if (locked) {
            // Locked: broadcast immediately when origin or rotation changes
            net.minecraft.world.level.block.Rotation rot = SpatialBlueprintItem.getRotation(stack);
            net.minecraft.core.BlockPos lockedPos = SpatialBlueprintItem.getLockedPos(stack);
            if (lockedPos == null) return;
            int xOff = SpatialBlueprintItem.getXOffset(stack);
            int yOff = SpatialBlueprintItem.getYOffset(stack);
            int zOff = SpatialBlueprintItem.getZOffset(stack);
            int aox = getAlignOffsetX(data.size, rot, alignMode);
            int aoz = getAlignOffsetZ(data.size, rot, alignMode);
            net.minecraft.core.BlockPos origin = lockedPos.above().offset(xOff + aox, yOff, zOff + aoz);
            int rotOrd = rot.ordinal();
            if (!origin.equals(lastBroadcastOrigin) || rotOrd != lastBroadcastRotationOrdinal) {
                lastBroadcastOrigin = origin;
                lastBroadcastRotationOrdinal = rotOrd;
                sendPreviewUpdate(stack, data, origin, rot);
            }
        } else {
            // Unlocked: broadcast every N seconds
            broadcastTickCounter++;
            if (broadcastTickCounter >= intervalTicks) {
                broadcastTickCounter = 0;
                net.minecraft.core.BlockPos target = getPreviewTarget(mc, ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE));
                if (target == null) { sendPreviewClear(); return; }
                net.minecraft.world.level.block.Rotation rot = SpatialBlueprintItem.getRotation(stack);
                int xOff = SpatialBlueprintItem.getXOffset(stack);
                int yOff = SpatialBlueprintItem.getYOffset(stack);
                int zOff = SpatialBlueprintItem.getZOffset(stack);
                int aox = getAlignOffsetX(data.size, rot, alignMode);
                int aoz = getAlignOffsetZ(data.size, rot, alignMode);
                net.minecraft.core.BlockPos origin = target.above().offset(xOff + aox, yOff, zOff + aoz);
                lastBroadcastOrigin = origin;
                lastBroadcastRotationOrdinal = rot.ordinal();
                sendPreviewUpdate(stack, data, origin, rot);
            }
        }
    }

    private static void sendPreviewUpdate(ItemStack stack, BlueprintData data,
                                          net.minecraft.core.BlockPos origin,
                                          net.minecraft.world.level.block.Rotation rot) {
        net.minecraft.core.BlockPos size = data.size;
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        String bpId = SpatialBlueprintItem.getBlueprintId(stack);
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_PreviewUpdateMessage(
                        origin, rot.ordinal(), sx, sy, sz, bpId,
                        data.category == null ? "" : data.category));
    }

    /** Sends a clear (null origin) to the server and resets broadcast state. */
    public static void sendPreviewClear() {
        if (lastBroadcastOrigin == null) return;
        lastBroadcastOrigin = null;
        lastBroadcastRotationOrdinal = -1;
        broadcastTickCounter = 0;
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_PreviewUpdateMessage(
                        null, 0, 0, 0, 0, "", ""));
    }

    /**
     * Executes auto-cancel:
     * 1. Resets align mode to OFF (hides preview immediately)
     * 2. Closes PlacementScreen if open
     * 3. Resets the blueprint item's offset / rotation / lock on the client side
     *
     * <p>ForceCancelMessage is intentionally NOT sent. Auto-cancel carries no cooldown
     * penalty — the blueprint is immediately reusable after the screen closes.</p>
     */
    public static void triggerAutoCancel(Minecraft mc) {
        lastBottomCenter = null;

        if (mc.screen instanceof PlacementScreen) {
            mc.setScreen(null);
        }

        // Reset whichever hand holds a SpatialBlueprint so the next open starts fresh.
        // AlignMode is now per-item NBT, so reset it on the found stack instead of a static field.
        if (mc.player != null) {
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                ItemStack s = mc.player.getItemInHand(hand);
                if (s.getItem() instanceof SpatialBlueprintItem) {
                    setAlignMode(s, AlignMode.OFF);
                    // 自动取消时清除客户端缓存
                    String bpId = SpatialBlueprintItem.getBlueprintId(s);
                    if (bpId != null) ClientBlueprintCache.remove(bpId);
                    SpatialBlueprintItem.setXOffset(s, 0);
                    SpatialBlueprintItem.setYOffset(s, 0);
                    SpatialBlueprintItem.setZOffset(s, 0);
                    SpatialBlueprintItem.setRotation(s, Rotation.NONE);
                    SpatialBlueprintItem.setLocked(s, false);
                    break;
                }
            }
        }
    }

    // ── Preview rendering ─────────────────────────────────────────────────

    /** Create 胶水贴图是否可用；首次查询后缓存（运行时不变化）。 */
    @Nullable private static Boolean createGlueTextureAvailable = null;

    private static boolean hasCreateGlueTexture() {
        if (createGlueTextureAvailable == null) {
            createGlueTextureAvailable = Minecraft.getInstance().getResourceManager()
                    .getResource(CREATE_GLUE_TEXTURE).isPresent();
        }
        return createGlueTextureAvailable;
    }

    /**
     * 画出服务端扫描回来的盒型实体（Create 超级胶水）轮廓。
     * 整盒在内用 Create 绿；跨界用红（穿透可见）。没有 Create 贴图时退回投影蓝的棋盘格风格。
     */
    private static void renderGlueBoxes(String slotPrefix, BlockPos min, BlockPos max) {
        java.util.List<com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.BoxEntity> boxes =
                com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientBoxEntities;
        if (boxes.isEmpty()) return;
        AABB selection = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
        boolean hasCreate = hasCreateGlueTexture();
        for (int i = 0; i < boxes.size(); i++) {
            com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.BoxEntity e = boxes.get(i);
            AABB box = new AABB(e.minX(), e.minY(), e.minZ(), e.maxX(), e.maxY(), e.maxZ());
            if (!box.intersects(selection)) continue;   // 旧扫描的残留条目不画
            if (hasCreate) {
                if (e.inside()) {
                    Outliner.getInstance().chaseAABB(slotPrefix + i, box)
                            .colored(GLUE_COLOR_INSIDE)
                            .withFaceTextures(CREATE_GLUE_TEXTURE, CREATE_GLUE_TEXTURE)
                            .disableLineNormals()
                            .lineWidth(GHOST_BOX_LINE_WIDTH);
                } else {
                    Outliner.getInstance().chaseAABB(slotPrefix + i, box)
                            .colored(GLUE_COLOR_CROSSING)
                            .withFaceTextures(CREATE_GLUE_TEXTURE, CREATE_GLUE_TEXTURE)
                            .disableLineNormals()
                            .noDepth()
                            .lineWidth(GHOST_BOX_LINE_WIDTH);
                }
            } else if (e.inside()) {
                Outliner.getInstance().chaseAABB(slotPrefix + i, box)
                        .colored(GHOST_BOX_COLOR_PROJECTION)
                        .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                        .lineWidth(GHOST_BOX_LINE_WIDTH);
            } else {
                Outliner.getInstance().chaseAABB(slotPrefix + i, box)
                        .colored(GLUE_COLOR_CROSSING)
                        .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                        .noDepth()
                        .lineWidth(GHOST_BOX_LINE_WIDTH);
            }
        }
    }

    /** 无破坏权限区块的红框颜色（比单格非法红略亮，区分「这一格非法」和「这一片不许动」）。 */
    private static final int CLAIM_COLOR = 0xFF3030;
    /** 领地红框线宽：比方块框粗一倍，远看也认得出是整片区域。 */
    private static final float CLAIM_LINE_WIDTH = GHOST_BOX_LINE_WIDTH * 2;

    /**
     * 画出服务端扫描回来的「无破坏权限」区块：每个区块只画它与选区相交的那一段柱体，
     * 红粗线、穿透可见。语义是「次元蓝图储存到这里会被拒」；空间蓝图不清世界，不受影响。
     */
    private static void renderProtectedChunks(String slotPrefix, BlockPos min, BlockPos max) {
        java.util.List<com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.ProtectedChunk> chunks =
                com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientProtectedChunks;
        if (chunks.isEmpty()) return;
        int selMinX = Math.min(min.getX(), max.getX()), selMaxX = Math.max(min.getX(), max.getX()) + 1;
        int selMinY = Math.min(min.getY(), max.getY()), selMaxY = Math.max(min.getY(), max.getY()) + 1;
        int selMinZ = Math.min(min.getZ(), max.getZ()), selMaxZ = Math.max(min.getZ(), max.getZ()) + 1;
        for (com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.ProtectedChunk c : chunks) {
            int x0 = Math.max(c.cx() << 4, selMinX), x1 = Math.min((c.cx() << 4) + 16, selMaxX);
            int z0 = Math.max(c.cz() << 4, selMinZ), z1 = Math.min((c.cz() << 4) + 16, selMaxZ);
            if (x0 >= x1 || z0 >= z1) continue;   // 旧扫描的残留条目（不在当前选区里）不画
            Outliner.getInstance().chaseAABB(slotPrefix + c.cx() + "_" + c.cz(),
                            new AABB(x0, selMinY, z0, x1, selMaxY, z1))
                    .colored(CLAIM_COLOR)
                    .noDepth()
                    .lineWidth(CLAIM_LINE_WIDTH);
        }
    }

    /**
     * 主手项目蓝图：「设定范围」编辑中的临时两点（起终点角标 + 白框，与空白蓝图同款）。
     * 这是编辑态，只在手持时画；开了的预览框由 {@link #renderProjectPreviews} 独立渲染（脱手仍显示）。
     * 两点是范围页打开时灌进物品 NBT 的、关闭后保留（下次接着调），所以<b>不在项目维度时不画</b>
     * （e37：项目维度创建时固定，末地 / 下界里不该看见主世界的白框）；两点本身不动。
     */
    private static void renderProjectBlueprint(Minecraft mc, ItemStack stack) {
        if (com.xioyim.spatialblueprint.item.ProjectBlueprintItem.isOutsideProjectDimension(stack, mc.level)) {
            Outliner.getInstance().remove("project_range_start");
            Outliner.getInstance().remove("project_range_end");
            Outliner.getInstance().remove("project_range_box");
            Outliner.getInstance().remove("project_range_box_glow");
            return;
        }
        BlockPos p1 = com.xioyim.spatialblueprint.item.ProjectBlueprintItem.getRangePoint(stack, 1);
        BlockPos p2 = com.xioyim.spatialblueprint.item.ProjectBlueprintItem.getRangePoint(stack, 2);

        if (p1 != null) {
            int sx = p2 != null && p2.getX() < p1.getX() ? -1 : 1;
            int sy = p2 != null && p2.getY() < p1.getY() ? -1 : 1;
            int sz = p2 != null && p2.getZ() < p1.getZ() ? -1 : 1;
            Vec3 startCorner = new Vec3(p1.getX() + (sx < 0 ? 1 : 0),
                    p1.getY() + (sy < 0 ? 1 : 0),
                    p1.getZ() + (sz < 0 ? 1 : 0));
            Outliner.getInstance().showCorner("project_range_start", startCorner, 1, sx, sy, sz)
                    .renderPriority(2)
                    .colored(GHOST_COLOR_START)
                    .noDepth()
                    .lineWidth(GHOST_POINT_LINE_WIDTH);
        } else {
            Outliner.getInstance().remove("project_range_start");
        }
        if (p2 != null) {
            int sx = p1 != null && p1.getX() < p2.getX() ? -1 : 1;
            int sy = p1 != null && p1.getY() < p2.getY() ? -1 : 1;
            int sz = p1 != null && p1.getZ() < p2.getZ() ? -1 : 1;
            Vec3 endCorner = new Vec3(p2.getX() + (sx < 0 ? 1 : 0),
                    p2.getY() + (sy < 0 ? 1 : 0),
                    p2.getZ() + (sz < 0 ? 1 : 0));
            Outliner.getInstance().showCorner("project_range_end", endCorner, 1, sx, sy, sz)
                    .renderPriority(2)
                    .colored(GHOST_COLOR_END)
                    .noDepth()
                    .lineWidth(GHOST_POINT_LINE_WIDTH);
        } else {
            Outliner.getInstance().remove("project_range_end");
        }
        if (p1 != null && p2 != null) {
            AABB box = new AABB(
                    Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()),
                    Math.max(p1.getX(), p2.getX()) + 1, Math.max(p1.getY(), p2.getY()) + 1, Math.max(p1.getZ(), p2.getZ()) + 1);
            Outliner.getInstance().chaseAABB("project_range_box", box)
                    .colored(GHOST_BOX_COLOR_BLANK)
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
            Outliner.getInstance().chaseAABB("project_range_box_glow", box)
                    .colored(GHOST_BOX_COLOR_BLANK)
                    .noDepth()
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
        } else {
            Outliner.getInstance().remove("project_range_box");
            Outliner.getInstance().remove("project_range_box_glow");
        }
        // 中心线不在编辑态画：它只跟着预览框走（renderProjectPreviews），预览关着就不该单独冒出一条金线。
        // 编辑态要看中心点，范围界面标题下那行「中心点：x y z」有。
    }

    /**
     * 画基准点：一条金色竖线，落在 anchor 那格的 x/z 中心，从盒底 {@code bottomY} 到盒顶
     * {@code bottomY + heightY}，与预览盒同高（不画格框、不超出盒顶）。只由 {@link #renderProjectPreviews}
     * 调用 —— 线跟着预览框一起出现、一起消失，不单独显示。
     *
     * <p>Outliner 没有画线的 API；{@code showCorner} 画的是从一角出发沿三轴的三条线，
     * x / z 方向给 0 时 {@link com.xioyim.spatialblueprint.client.outliner.CornerOutline} 会跳过那两条，
     * 只剩竖线。slot 按项目前缀区分，各项目预览互不覆盖。</p>
     */
    private static void renderAnchorLine(String slotPrefix, BlockPos anchor, int bottomY, int heightY) {
        Outliner.getInstance().showCorner(slotPrefix + "_anchor_line",
                        new Vec3(anchor.getX() + 0.5, bottomY, anchor.getZ() + 0.5), Math.max(1, heightY), 0, 1, 0)
                .renderPriority(2)
                .colored(GHOST_COLOR_ANCHOR)
                .noDepth()
                .lineWidth(GHOST_POINT_LINE_WIDTH);
    }

    private static void renderBlankBlueprintSelection(Minecraft mc, Player player, ItemStack stack, RenderLevelStageEvent event) {
        BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos p2 = BlankBlueprintItem.getPoint2(stack);

        if (p1 == null && p2 == null) {
            Outliner.getInstance().remove("blank_selection");
            Outliner.getInstance().remove("blank_selection_glow");
            Outliner.getInstance().remove("blank_start");
            Outliner.getInstance().remove("blank_end");
            return;
        }

        if (p1 != null) {
            // 起点角标：三条线沿 p1 格子棱边指向 p2；方向为 - 的轴从高角起画
            int sx = p2 != null && p2.getX() < p1.getX() ? -1 : 1;
            int sy = p2 != null && p2.getY() < p1.getY() ? -1 : 1;
            int sz = p2 != null && p2.getZ() < p1.getZ() ? -1 : 1;
            Vec3 startCorner = new Vec3(p1.getX() + (sx < 0 ? 1 : 0),
                    p1.getY() + (sy < 0 ? 1 : 0),
                    p1.getZ() + (sz < 0 ? 1 : 0));
            Outliner.getInstance().showCorner("blank_start", startCorner, 1, sx, sy, sz)
                    .renderPriority(2)
                    .colored(GHOST_COLOR_START)
                    .noDepth()
                    .lineWidth(GHOST_POINT_LINE_WIDTH);
        } else {
            Outliner.getInstance().remove("blank_start");
        }
        if (p2 != null) {
            // 终点角标：三条线沿 p2 格子棱边指向 p1；方向为 - 的轴从高角起画
            int sx = p1 != null && p1.getX() < p2.getX() ? -1 : 1;
            int sy = p1 != null && p1.getY() < p2.getY() ? -1 : 1;
            int sz = p1 != null && p1.getZ() < p2.getZ() ? -1 : 1;
            Vec3 endCorner = new Vec3(p2.getX() + (sx < 0 ? 1 : 0),
                    p2.getY() + (sy < 0 ? 1 : 0),
                    p2.getZ() + (sz < 0 ? 1 : 0));
            Outliner.getInstance().showCorner("blank_end", endCorner, 1, sx, sy, sz)
                    .renderPriority(2)
                    .colored(GHOST_COLOR_END)
                    .noDepth()
                    .lineWidth(GHOST_POINT_LINE_WIDTH);
        } else {
            Outliner.getInstance().remove("blank_end");
        }

        if (p1 != null && p2 != null) {
            BlockPos min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
            BlockPos max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));
            long vol = (long)(max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastBlankPreviewLogMs > 1000 && isDebugSession()) {
                lastBlankPreviewLogMs = nowMs;
                int outlinerSize = Outliner.getInstance().getOutlines().size();
                SpatialBlueprintMod.LOGGER.info("[Preview][DBG] blankSelection vol={} ({}x{}x{}) p1={} p2={} min={} max={} outliner={} dangerous={} special={}",
                        vol, max.getX()-min.getX()+1, max.getZ()-min.getZ()+1, max.getY()-min.getY()+1, p1, p2, min, max, outlinerSize,
                        com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientDangerousBlocks.size(),
                        com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientSpecialEntities.size());
            }

            AABB selectionBox = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            Outliner.getInstance().chaseAABB("blank_selection", selectionBox)
                    .colored(GHOST_BOX_COLOR_BLANK)
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
            // 高亮穿透线框：无面（仅棱线）、关闭深度测试，跟随选区框且不被任何方块遮挡
            Outliner.getInstance().chaseAABB("blank_selection_glow", selectionBox)
                    .colored(GHOST_BOX_COLOR_BLANK)
                    .noDepth()
                    .lineWidth(GHOST_BOX_LINE_WIDTH);

            // 非法方块：客户端检测 — 原每帧全量扫描 1.45M 体积约 150-280ms（日志实测），改为选区变化时单次扫描并缓存，
            // 每帧仅重放缓存结果（零扫描），旧非法框靠 Outliner 1 tick 自动淡出回收
            boolean needIllegalRescan = !min.equals(lastIllegalScanMin) || !max.equals(lastIllegalScanMax);
            if (needIllegalRescan) {
                long illegalT0 = System.nanoTime();
                cachedIllegalPositions.clear();
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                int illegalScanned = 0;
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            illegalScanned++;
                            m.set(x, y, z);
                            if (player.level().isLoaded(m)
                                    && com.xioyim.spatialblueprint.util.BlueprintSanitizer.isIllegalBlock(player.level().getBlockState(m))) {
                                cachedIllegalPositions.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
                long illegalMs = (System.nanoTime() - illegalT0) / 1_000_000;
                if (isDebugSession()) {
                    SpatialBlueprintMod.LOGGER.info("[Preview][DBG] illegalScan RESCAN vol={} scanned={} found={} cost={}ms min={} max={}", vol, illegalScanned, cachedIllegalPositions.size(), illegalMs, min, max);
                }
                lastIllegalScanMin = new BlockPos(min.getX(), min.getY(), min.getZ());
                lastIllegalScanMax = new BlockPos(max.getX(), max.getY(), max.getZ());
                lastIllegalScanMs = System.currentTimeMillis();
            } else if (isDebugSession() && System.currentTimeMillis() - lastIllegalScanMs > 2000) {
                // 2s 心跳，仅打缓存命中，避免每帧刷屏
                lastIllegalScanMs = System.currentTimeMillis();
                SpatialBlueprintMod.LOGGER.info("[Preview][DBG] illegalScan CACHE_HIT vol={} found={} min={} max={}", vol, cachedIllegalPositions.size(), min, max);
            }
            for (BlockPos pos : cachedIllegalPositions) {
                AABB illegalBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                Outliner.getInstance().chaseAABB("blank_illegal_" + pos.asLong(), illegalBox)
                        .colored(0xFF0000)
                        .noDepth()
                        .lineWidth(GHOST_BOX_LINE_WIDTH);
            }

            // 危险方块（带 NBT / 危险实体）：复用服务端扫描结果，橙色粗棱线框（无面、穿透）高亮
            for (com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.IllegalBlock e
                    : com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientDangerousBlocks) {
                if (e.x() < min.getX() || e.x() > max.getX()
                        || e.y() < min.getY() || e.y() > max.getY()
                        || e.z() < min.getZ() || e.z() > max.getZ()) continue;
                // 盔甲架圈 2 格高（脚底方块到头顶，实体位置取脚底）
                AABB dangerBox = "minecraft:armor_stand".equals(e.blockId())
                        ? new AABB(e.x(), e.y(), e.z(), e.x() + 1, e.y() + 2, e.z() + 1)
                        : new AABB(e.x(), e.y(), e.z(), e.x() + 1, e.y() + 1, e.z() + 1);
                Outliner.getInstance().chaseAABB("blank_danger_" + e.x() + "_" + e.y() + "_" + e.z(), dangerBox)
                        .colored(0xFF8C00)
                        .noDepth()
                        .lineWidth(GHOST_BOX_LINE_WIDTH);
            }

            // 特殊实体圈框（蓝，穿透）：仅物品展示框；盔甲架/生物改用发光标记
            for (com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.SpecialEntity se
                    : com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientSpecialEntities) {
                if (!"item_frame".equals(se.kind())) continue;
                if (se.x() < min.getX() || se.x() > max.getX()
                        || se.y() < min.getY() || se.y() > max.getY()
                        || se.z() < min.getZ() || se.z() > max.getZ()) continue;
                AABB specialBox = new AABB(se.x(), se.y(), se.z(), se.x() + 1, se.y() + 1, se.z() + 1);
                Outliner.getInstance().chaseAABB("blank_special_" + se.x() + "_" + se.y() + "_" + se.z(), specialBox)
                        .colored(0x55FFFF)
                        .noDepth()
                        .lineWidth(GHOST_BOX_LINE_WIDTH);
            }
            renderGlueBoxes("blank_glue_", min, max);
            renderProtectedChunks("blank_claim_", min, max);
        }
    }

    private static void renderBlueprintPreview(Minecraft mc, Player player, ItemStack stack, RenderLevelStageEvent event, boolean isProjection, int ghostColor) {
        final long tFnStart = System.nanoTime();
        // ── OFF mode: skip all rendering ─────────────────────────────────
        // AlignMode is now read from the item's NBT (per-item, not a shared static field).
        AlignMode alignMode = getAlignMode(stack);
        if (alignMode == AlignMode.OFF) {
            lastBottomCenter = null;
            return;
        }

        BlueprintData data = isProjection
                ? ProjectionBlueprintItem.getBlueprintDataClient(stack)
                : SpatialBlueprintItem.getBlueprintDataClient(stack);
        if (data == null) { lastBottomCenter = null; return; }

        BlockPos targetPos;
        if (SpatialBlueprintItem.isLocked(stack)) {
            targetPos = SpatialBlueprintItem.getLockedPos(stack);
            if (targetPos == null) { lastBottomCenter = null; return; }
        } else {
            targetPos = getPreviewTarget(mc, ConfigAccess.get(BlueprintConfig.PREVIEW_DISTANCE));
            if (targetPos == null) { lastBottomCenter = null; return; }
        }

        int xOffset = SpatialBlueprintItem.getXOffset(stack);
        int yOffset = SpatialBlueprintItem.getYOffset(stack);
        int zOffset = SpatialBlueprintItem.getZOffset(stack);
        Rotation rotation = SpatialBlueprintItem.getRotation(stack);
        BlockPos size = data.size;

        int alignOffX = bbCacheValid
                ? alignOffsetFromMinMax(cachedRelMinX, cachedRelMaxX, alignMode)
                : getAlignOffsetX(size, rotation, alignMode);
        int alignOffZ = bbCacheValid
                ? alignOffsetFromMinMax(cachedRelMinZ, cachedRelMaxZ, alignMode)
                : getAlignOffsetZ(size, rotation, alignMode);

        BlockPos origin = targetPos.above().offset(xOffset + alignOffX, yOffset, zOffset + alignOffZ);

        // ── Distance check (render gate; actual cancel handled in onClientTick) ──
        int rotMaxX = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? size.getZ() - 1 : size.getX() - 1;
        int rotMaxY = size.getY() - 1;
        int rotMaxZ = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
                ? size.getX() - 1 : size.getZ() - 1;

        double bCX = origin.getX() + rotMaxX / 2.0;
        double bCY = origin.getY() + rotMaxY / 2.0;
        double bCZ = origin.getZ() + rotMaxZ / 2.0;
        double halfDiag = Math.sqrt((double) rotMaxX * rotMaxX + (double) rotMaxY * rotMaxY + (double) rotMaxZ * rotMaxZ) / 2.0;
        double cancelDist = halfDiag + ConfigAccess.get(BlueprintConfig.RENDER_CANCEL_MARGIN);

        if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
            lastBottomCenter = null;
            return; // onClientTick will trigger the actual cancel shortly
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // ── Bounding-box compute — cached per (blueprintId × rotation) (M6/M11) ──
        // Using the ID string as key so the cache stays valid across frames in single-player
        // where BlueprintStorage.load() returns a fresh object each call (M11 fix).
        String blueprintId = (stack.hasTag() && stack.getTag().contains("blueprintId"))
                ? stack.getTag().getString("blueprintId") : "";
        if (!blueprintId.equals(cachedBBId) || cachedBBRotation != rotation) {
            int tMinX = Integer.MAX_VALUE, tMinY = Integer.MAX_VALUE, tMinZ = Integer.MAX_VALUE;
            int tMaxX = Integer.MIN_VALUE, tMaxY = Integer.MIN_VALUE, tMaxZ = Integer.MIN_VALUE;
            for (BlueprintData.BlockEntry entry : data.allBlocks()) {
                BlockPos rp = entry.pos;
                int tx = transformX(rp.getX(), rp.getY(), rp.getZ(), size, rotation);
                int ty = rp.getY(); // transformY is always identity
                int tz = transformZ(rp.getX(), rp.getY(), rp.getZ(), size, rotation);
                if (tx < tMinX) tMinX = tx;  if (ty < tMinY) tMinY = ty;  if (tz < tMinZ) tMinZ = tz;
                if (tx > tMaxX) tMaxX = tx;  if (ty > tMaxY) tMaxY = ty;  if (tz > tMaxZ) tMaxZ = tz;
            }
            cachedBBId       = blueprintId;
            cachedBBRotation = rotation;
            cachedRelMinX = tMinX; cachedRelMinY = tMinY; cachedRelMinZ = tMinZ;
            cachedRelMaxX = tMaxX; cachedRelMaxY = tMaxY; cachedRelMaxZ = tMaxZ;
            bbCacheValid  = (tMinX != Integer.MAX_VALUE);
        }
        int minX = bbCacheValid ? origin.getX() + cachedRelMinX : Integer.MAX_VALUE;
        int minY = bbCacheValid ? origin.getY() + cachedRelMinY : Integer.MAX_VALUE;
        int minZ = bbCacheValid ? origin.getZ() + cachedRelMinZ : Integer.MAX_VALUE;
        int maxX = origin.getX() + cachedRelMaxX;
        int maxY = origin.getY() + cachedRelMaxY;
        int maxZ = origin.getZ() + cachedRelMaxZ;

        if (minX != Integer.MAX_VALUE) {
            lastBottomCenter = new BlockPos((minX + maxX) / 2, minY, (minZ + maxZ) / 2);
            lastPreviewMin   = new BlockPos(minX, minY, minZ);
            lastPreviewMax   = new BlockPos(maxX, maxY, maxZ);

            AABB previewBox = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
            Outliner.getInstance().chaseAABB("bp_preview", previewBox)
                    .colored(ghostColor)
                    .noDepth()
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
        } else {
            lastBottomCenter = null;
            lastPreviewMin   = null;
            lastPreviewMax   = null;
            Outliner.getInstance().remove("bp_preview");
        }

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RENDER_RANDOM.setSeed(42L);

        // ── Rebuild render list when blueprint/rotation/hide-settings/fluid-mode change ──
        com.xioyim.spatialblueprint.util.FluidDisplayMode curFluidMode = fluidMode;
        boolean needRebuild = !blueprintId.equals(rlBpId) || rotation != rlRotation
                || curFluidMode != rlFluidMode || hideTopN != rlHideTopN
                || !hiddenTopBlocks.equals(rlHiddenSnapshot);
        if (needRebuild) {
            java.util.Set<String> hiddenPreview = BlueprintConfig.getHiddenPreviewBlocks();
            java.util.Set<String> latePlaceSet  = BlueprintConfig.getLatePlaceBlocks();
            java.util.List<RenderEntry> list = new java.util.ArrayList<>();
            for (BlueprintData.BlockEntry entry : data.allBlocks()) {
                BlockPos rp = entry.pos;
                BlockState s = entry.state;
                if (rotation != Rotation.NONE) s = s.rotate(rotation);
                if (s.isAir()) continue;
                int tx = transformX(rp.getX(), rp.getY(), rp.getZ(), size, rotation);
                int ty = transformY(rp.getX(), rp.getY(), rp.getZ(), size, rotation);
                int tz = transformZ(rp.getX(), rp.getY(), rp.getZ(), size, rotation);
                boolean isFluid = !s.getFluidState().isEmpty();
                if (isFluid) {
                    if (curFluidMode.hidesFluid(s)) continue;
                } else {
                    String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                    // HIDE_ALL_FILTER 也隐藏 hiddenPreviewBlocks（气泡柱等）
                    if (curFluidMode == com.xioyim.spatialblueprint.util.FluidDisplayMode.HIDE_ALL_FILTER
                            && hiddenPreview.contains(blockId)) continue;
                    if (!hiddenTopBlocks.isEmpty() && hiddenTopBlocks.contains(blockId)) continue;
                }
                boolean latePlace = !isFluid && latePlaceSet.contains(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                // 白块 + 文本：容器(latePlace) / 告示牌 / 非 MODEL 形状（潜影盒等）；流体除外
                boolean whiteText = !isFluid && (latePlace
                        || s.getBlock() instanceof net.minecraft.world.level.block.SignBlock
                        || s.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL);
                list.add(new RenderEntry(s, tx, ty, tz, isFluid, latePlace, whiteText));
            }
            renderEntries    = list.toArray(new RenderEntry[0]);
            renderEntryCount = renderEntries.length;
            java.util.List<RenderEntry> specList = new java.util.ArrayList<>();
            for (RenderEntry e : renderEntries) { if (e.isWhiteText) specList.add(e); }
            specialEntries    = specList.toArray(new RenderEntry[0]);
            specialEntryCount = specialEntries.length;
            entityLabels      = buildEntityLabels(data, size, rotation);
            rlBpId = blueprintId; rlRotation = rotation;
            rlFluidMode = curFluidMode; rlHideTopN = hideTopN;
            rlHiddenSnapshot.clear(); rlHiddenSnapshot.addAll(hiddenTopBlocks);
        }

        // ── Start async VBO build when render list changed ────────────────
        dbgFrame++;
        if (needRebuild) {
            if (isDebugSession())
                SpatialBlueprintMod.LOGGER.info("[VBO][DBG] 渲染列表变更 → 触发异步重建，count={}", renderEntryCount);
            bpSlot.markDirty();
        }
        ensureWhiteSprite();
        bpSlot.startBuildIfDirty(player.level(), origin, renderEntries, renderEntryCount);

        // ── Upload one chunk per frame (spreads GPU upload cost) ─────────
        bpSlot.uploadOneChunk();

        // ── Draw all uploaded chunks (may be partially uploaded) ─────────
        long t0 = System.nanoTime();
        bpSlot.draw(event, origin, camera);
        long t1 = System.nanoTime();

        // ── Per-frame: 1px 线框 + lang 文本（容器/告示牌/潜影盒） ──
        // 文本仅 8 格内渲染，多人独立（按本机玩家位置判定）
        poseStack.pushPose();
        poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        for (int i = 0; i < specialEntryCount; i++) {
            RenderEntry e = specialEntries[i];
            poseStack.pushPose();
            poseStack.translate(e.tx, e.ty, e.tz);
            LevelRenderer.renderLineBox(poseStack, bufferSource.getBuffer(RenderType.lines()),
                    0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                    0.9f, 0.9f, 0.9f, 0.8f, 0.9f, 0.9f, 0.9f);
            double dx = origin.getX() + e.tx + 0.5 - player.getX();
            double dy = origin.getY() + e.ty + 0.5 - player.getY();
            double dz = origin.getZ() + e.tz + 0.5 - player.getZ();
            if (dx*dx + dy*dy + dz*dz <= 64.0) {
                renderFloatingLabel(poseStack, bufferSource, mc, e.label);
            }
            poseStack.popPose();
        }

        // ── Per-frame: 实体预览（黄色 1px 线框 + 实体 lang 文本） ──
        // 实体文本同样 8 格剔除
        {
            double pdx, pdy, pdz;
            java.util.ArrayList<EntityLabel> closeEntities = new java.util.ArrayList<>();
            for (EntityLabel el : entityLabels) {
                pdx = origin.getX() + el.tx() + 0.5 - player.getX();
                pdy = origin.getY() + el.ty() + 0.5 - player.getY();
                pdz = origin.getZ() + el.tz() + 0.5 - player.getZ();
                if (pdx*pdx + pdy*pdy + pdz*pdz <= 64.0) closeEntities.add(el);
            }
            if (!closeEntities.isEmpty()) {
                drawEntityLabels(poseStack, bufferSource, mc, closeEntities.toArray(new EntityLabel[0]));
            } else if (entityLabels.length == 0) {
                // 无实体时不额外调用，保持原逻辑
            } else {
                // 远处实体仅保留线框？当前 drawEntityLabels 会同时画线框+文字，
                // 为省文字而保留线框需拆分；此处 8 格外实体完全不画（与文字一致）
            }
        }
        long t2 = System.nanoTime();
        poseStack.popPose();
        bufferSource.endBatch();
        long t3 = System.nanoTime();

        if (dbgFrame % DBG_INTERVAL == 0 && isDebugSession())
            SpatialBlueprintMod.LOGGER.info(
                "[PERF][DBG] 帧#{} count={} building={} | total={}ms drawVBO={}ms loop={}ms endBatch={}ms | chunks={}/{}",
                dbgFrame, renderEntryCount, bpSlot.isBuilding(),
                (t3-tFnStart)/1_000_000.0,
                (t1-t0)/1_000_000.0, (t2-t1)/1_000_000.0, (t3-t2)/1_000_000.0,
                bpSlot.uploadedCount(), bpSlot.totalChunks());
    }

    /**
     * Pure-CPU step: tessellate blocks into chunked RenderedBuffers.
     * Each chunk holds at most UPLOAD_CHUNK_SIZE solid blocks so GPU uploads
     * can be spread across multiple frames (~7 ms each instead of ~190 ms at once).
     * Safe to call from a background thread (no GL state touched).
     */
    /**
     * Pure-CPU step: tessellate blocks into chunked RenderedBuffers.
     * Solid blocks use the model renderer; fluid blocks emit simple solid-color cubes
     * (no animated texture sampling — eliminates the per-frame GPU animation overhead).
     * Late-place blocks are excluded (rendered as name labels per-frame instead).
     * Safe to call from a background thread (no GL state touched).
     */
    private static BufferBuilder.RenderedBuffer[] buildChunks(net.minecraft.world.level.Level level,
                                                               BlockPos origin,
                                                               RenderEntry[] entries, int count,
                                                               float whiteU, float whiteV) {
        // Collect solid + fluid entries (all blocks now render as real models in VBO)
        java.util.List<RenderEntry> buildList = new java.util.ArrayList<>(count);
        java.util.HashMap<Long, BlockState> blockMap = new java.util.HashMap<>(count);
        int fluidCount = 0;
        for (int i = 0; i < count; i++) {
            RenderEntry e = entries[i];
            buildList.add(e);
            if (!e.isFluid) blockMap.put(e.relPos.asLong(), e.state);
            else fluidCount++;
        }
        if (isDebugSession())
            SpatialBlueprintMod.LOGGER.info("[VBO][DBG] buildChunks: total={} solid={} fluid={} late={} numChunks={}",
                count, buildList.size() - fluidCount, fluidCount, count - buildList.size(),
                (buildList.size() + UPLOAD_CHUNK_SIZE - 1) / UPLOAD_CHUNK_SIZE);
        if (buildList.isEmpty()) return new BufferBuilder.RenderedBuffer[0];

        BlueprintFakeWorld fakeWorld = new BlueprintFakeWorld(level, origin, blockMap);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource rand = RandomSource.create();

        int numChunks = (buildList.size() + UPLOAD_CHUNK_SIZE - 1) / UPLOAD_CHUNK_SIZE;
        BufferBuilder.RenderedBuffer[] result = new BufferBuilder.RenderedBuffer[numChunks];
        int fluidDrawn = 0;

        for (int c = 0; c < numChunks; c++) {
            int start = c * UPLOAD_CHUNK_SIZE;
            int end   = Math.min(start + UPLOAD_CHUNK_SIZE, buildList.size());
            int chunkSize = end - start;

            PoseStack ps = new PoseStack();
            BufferBuilder bb = new BufferBuilder(chunkSize * 128);
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

            for (int i = start; i < end; i++) {
                RenderEntry e = buildList.get(i);
                ps.pushPose();
                ps.translate(e.tx, e.ty, e.tz);
                if (e.isFluid) {
                    // 静态贴图：水/岩浆以各自原版贴图（still 帧）渲染，不做流动动画。
                    // 实体水源 = 原色；非实体（流动）水/岩浆 = 更浅的 tint + 半透明以区分。
                    boolean isLava = e.state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
                    boolean source = e.state.getFluidState().isSource();
                    float u0, v0, u1, v1, tr, tg, tb, ta;
                    if (isLava) {
                        u0 = cachedLavaU0; v0 = cachedLavaV0; u1 = cachedLavaU1; v1 = cachedLavaV1;
                        if (source) { tr = 1f; tg = 1f; tb = 1f; ta = 1f; }              // 岩浆贴图自带橙色（不透明）
                        else        { tr = 1f; tg = 0.95f; tb = 0.75f; ta = 0.65f; }     // 流动岩浆：半透明更浅（贴图 B 通道≈0，乘法变不浅）
                    } else {
                        u0 = cachedWaterU0; v0 = cachedWaterV0; u1 = cachedWaterU1; v1 = cachedWaterV1;
                        if (source) { tr = 63f/255f; tg = 118f/255f; tb = 228f/255f; ta = 1f; } // 水源：生物群系蓝 0x3F76E4
                        else        { tr = 0.75f; tg = 0.85f; tb = 1f; ta = 0.8f; }            // 流动水：更浅（偏亮蓝白）
                    }
                    try {
                        emitTexturedCube(bb, ps.last().pose(), u0, v0, u1, v1, tr, tg, tb, ta);
                        fluidDrawn++;
                    } catch (Exception ignored) {}
                } else if (e.isWhiteText) {
                    // 容器/告示牌/ANIMATED：VBO 不画填充，线框 + lang 文本逐帧渲染
                } else {
                    long seed = e.state.getSeed(e.relPos);
                    rand.setSeed(seed);
                    try {
                        BakedModel model = dispatcher.getBlockModel(e.state);
                        modelRenderer.tesselateBlock(fakeWorld, model, e.state, e.relPos, ps, bb,
                                true, rand, seed, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
                    } catch (Exception ignored) {}
                }
                ps.popPose();
            }
            result[c] = bb.end();
        }
        SpatialBlueprintMod.LOGGER.warn("[VBO][FLUID] buildChunks: 共{}格 绘制流体={}",
                buildList.size(), fluidDrawn);
        return result;
    }

    /**
     * Render-thread step: upload one RenderedBuffer to a new VertexBuffer.
     * Called once per frame per pending chunk so cost is bounded.
     */
    private static VertexBuffer uploadBuffer(BufferBuilder.RenderedBuffer rendered) {
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(rendered);
        VertexBuffer.unbind();
        return vbo;
    }

    /**
     * 一份结构预览的 VBO 状态机：异步构建 → 逐帧分块上传 → 每帧绘制 → 显式回收。
     *
     * <p>空间蓝图预览与工程蓝图预览原来各自维护一套完全相同的静态字段
     * （vbos / count / dirty / pendingChunks / uploadIdx / building / generation），
     * 外加两个逐行雷同的 {@code startAsyncXxxBuild}。抽成实例后两者各持一个槽位，
     * 后续要渲染其他玩家的预览时，只需往池子里再放几个实例，不必复制第三套字段。</p>
     *
     * <p>线程约定：{@code pending} / {@code uploadIdx} / {@code building} / {@code generation}
     * 由后台构建线程与渲染线程共同访问，声明为 volatile；其余字段只在渲染线程上碰。</p>
     */
    private static final class PreviewVboSlot {

        /** 日志里区分是哪个槽位（"bp" / "eng" / "other-玩家名"）。 */
        private final String debugName;
        /** 本槽位提交构建任务的泳道。 */
        private final java.util.concurrent.ExecutorService builder;

        private VertexBuffer[] vbos     = new VertexBuffer[0];
        private int            vboCount = 0;
        private boolean        dirty    = true;

        private volatile BufferBuilder.RenderedBuffer[] pending   = null;
        private volatile int                            uploadIdx = 0;
        private volatile boolean                        building  = false;
        /** 自增即令仍在跑的后台构建结果作废（世界卸载、槽位淘汰）。 */
        private volatile int generation = 0;

        PreviewVboSlot(String debugName, java.util.concurrent.ExecutorService builder) {
            this.debugName = debugName;
            this.builder   = builder;
        }

        void markDirty()       { dirty = true; }
        boolean isBuilding()   { return building; }
        boolean hasPending()   { return pending != null; }
        int uploadedCount()    { return vboCount; }
        /** 本轮总分块数；尚未构建完时回退成已上传数，仅供日志显示。 */
        int totalChunks() {
            BufferBuilder.RenderedBuffer[] p = pending;
            return p != null ? p.length : vboCount;
        }

        /**
         * 标脏且没有在途任务时，往泳道里提交一次 CPU 侧的 tessellation。
         *
         * <p>{@code building} 从提交那一刻就置真、直到任务跑完才清 —— 它同时覆盖
         * 「排队中」与「正在算」两种状态，所以同一个槽位在队列里永远只有一个任务，
         * 队列长度天然被槽位数封住（最多 5）。</p>
         */
        void startBuildIfDirty(net.minecraft.world.level.Level level, BlockPos origin,
                               RenderEntry[] entries, int count) {
            if (!dirty || building) return;
            dirty = false;
            building = true;
            // 先关掉旧 VBO —— 显存不必等新的建好才释放
            closeBuffers();
            pending = null; uploadIdx = 0;
            final RenderEntry[] snap = entries.clone();
            final int cnt = count;
            final BlockPos org = origin;
            final int gen = generation;
            final boolean dbg = isDebugSession();
            final float wu = cachedWhiteU, wv = cachedWhiteV;
            builder.execute(() -> {
                try {
                    // 排队期间槽位可能已被淘汰或世界已切换 —— 那就别白算这一份
                    if (generation != gen) {
                        if (dbg) SpatialBlueprintMod.LOGGER.info(
                                "[VBO][DBG] {} 排队中的构建被跳过（gen {} → {}）", debugName, gen, generation);
                        return;
                    }
                    BufferBuilder.RenderedBuffer[] result = buildChunks(level, org, snap, cnt, wu, wv);
                    if (generation == gen) {
                        pending = result;
                        if (dbg) SpatialBlueprintMod.LOGGER.info(
                                "[VBO][DBG] {} 后台构建完成，chunks={}（等待上传）", debugName, result.length);
                    } else {
                        SpatialBlueprintMod.LOGGER.warn(
                                "[VBO] {} 后台构建结果被丢弃：世界已切换（gen {} → {}）", debugName, gen, generation);
                    }
                }
                catch (Exception ex) {
                    SpatialBlueprintMod.LOGGER.warn("[VBO] {} 后台构建异常，origin={}", debugName, org, ex);
                }
                finally { building = false; }
            });
        }

        /** 每帧上传一个分块，把 GPU 上传开销摊到多帧（一次全传约 190 ms）。 */
        void uploadOneChunk() {
            BufferBuilder.RenderedBuffer[] chunks = pending;
            if (chunks == null || building || uploadIdx >= chunks.length) return;
            if (uploadIdx == 0) {
                // 第一块到手才知道要开多大的数组
                vbos = new VertexBuffer[chunks.length];
                vboCount = 0;
            }
            vbos[uploadIdx] = uploadBuffer(chunks[uploadIdx]);
            vboCount = uploadIdx + 1;
            uploadIdx++;
            if (uploadIdx == chunks.length) {
                pending = null;
                if (isDebugSession()) SpatialBlueprintMod.LOGGER.info(
                        "[VBO][DBG] {} 全部分块上传完成，chunks={}", debugName, chunks.length);
            }
        }

        /** 绘制已上传的分块 —— 可能只传了一部分，先画多少算多少。 */
        void draw(RenderLevelStageEvent event, BlockPos origin, Vec3 camera) {
            for (int i = 0; i < vboCount; i++) drawVBO(vbos[i], event, origin, camera);
        }

        /**
         * 释放 GPU 缓冲、丢弃在途的构建结果，并标脏以便下次重建。
         *
         * @return 释放前已上传的分块数，供日志统计
         */
        int dispose() {
            int freed = vboCount;
            closeBuffers();
            dirty = true;
            generation++;
            pending = null;
            uploadIdx = 0;
            return freed;
        }

        private void closeBuffers() {
            for (VertexBuffer v : vbos) { if (v != null) v.close(); }
            vbos = new VertexBuffer[0];
            vboCount = 0;
        }
    }

    /**
     * 一位其他玩家的完整结构预览：渲染列表 + 一个 {@link PreviewVboSlot}。
     *
     * <p><b>固定按默认设置渲染，不跟随本地显示偏好</b> —— 看的是别人的蓝图，
     * 我这边的流体模式与「隐藏用量最多的 N 种」不该改变对方结构的内容。
     * 副作用是好的：本地改设置只会让自己的 {@code bpSlot} 失效，
     * 池子里的槽位一个都不用重建。</p>
     *
     * <p>重建判据只有 {@code blueprintId × rotation} —— 对方走动改变的是 origin，
     * 而 origin 是绘制时才平移的，不进 VBO，所以对方举着蓝图到处跑是零开销的。</p>
     */
    private static final class OtherPreviewRender {

        final PreviewVboSlot slot;

        private String   rlBpId     = null;
        private net.minecraft.world.level.block.Rotation rlRotation = null;
        private RenderEntry[] entries      = new RenderEntry[0];
        private int           entryCount   = 0;
        private RenderEntry[] special      = new RenderEntry[0];
        private int           specialCount = 0;
        private EntityLabel[] entityLabels = NO_ENTITY_LABELS;

        OtherPreviewRender(String playerName) {
            this.slot = new PreviewVboSlot("other-" + playerName, OTHER_BUILDER);
        }

        /** blueprintId 或 rotation 变了才重建渲染列表，并把槽位标脏。 */
        void rebuildIfNeeded(BlueprintData data, String blueprintId,
                             net.minecraft.world.level.block.Rotation rot) {
            if (blueprintId.equals(rlBpId) && rot == rlRotation) return;

            BlockPos size = data.size;
            java.util.Set<String> latePlaceSet = BlueprintConfig.getLatePlaceBlocks();
            java.util.List<RenderEntry> list = new java.util.ArrayList<>();
            for (BlueprintData.BlockEntry entry : data.allBlocks()) {
                BlockPos rp = entry.pos;
                BlockState s = entry.state;
                if (rot != net.minecraft.world.level.block.Rotation.NONE) s = s.rotate(rot);
                if (s.isAir()) continue;
                int tx = transformX(rp.getX(), rp.getY(), rp.getZ(), size, rot);
                int ty = transformY(rp.getX(), rp.getY(), rp.getZ(), size, rot);
                int tz = transformZ(rp.getX(), rp.getY(), rp.getZ(), size, rot);
                boolean isFluid = !s.getFluidState().isEmpty();
                // 这里刻意不套 fluidMode / hiddenTopBlocks：如实还原对方的蓝图
                boolean latePlace = !isFluid && latePlaceSet.contains(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                boolean whiteText = !isFluid && (latePlace
                        || s.getBlock() instanceof net.minecraft.world.level.block.SignBlock
                        || s.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL);
                list.add(new RenderEntry(s, tx, ty, tz, isFluid, latePlace, whiteText));
            }
            entries    = list.toArray(new RenderEntry[0]);
            entryCount = entries.length;
            java.util.List<RenderEntry> spec = new java.util.ArrayList<>();
            for (RenderEntry e : entries) { if (e.isWhiteText) spec.add(e); }
            special       = spec.toArray(new RenderEntry[0]);
            specialCount  = special.length;
            entityLabels  = buildEntityLabels(data, size, rot);

            rlBpId = blueprintId; rlRotation = rot;
            slot.markDirty();
        }

        /** 每帧的容器白框 + 方块名、实体黄框 + 实体名，与自己的预览同款。 */
        void drawLabels(RenderLevelStageEvent event, Minecraft mc, BlockPos origin, Vec3 camera) {
            if (specialCount == 0 && entityLabels.length == 0) return;
            Player viewer = mc.player;
            if (viewer == null) return;
            PoseStack ps = event.getPoseStack();
            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            ps.pushPose();
            ps.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);

            for (int i = 0; i < specialCount; i++) {
                RenderEntry e = special[i];
                ps.pushPose();
                ps.translate(e.tx, e.ty, e.tz);
                LevelRenderer.renderLineBox(ps, buf.getBuffer(RenderType.lines()),
                        0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                        0.9f, 0.9f, 0.9f, 0.8f, 0.9f, 0.9f, 0.9f);
                double dx = origin.getX() + e.tx + 0.5 - viewer.getX();
                double dy = origin.getY() + e.ty + 0.5 - viewer.getY();
                double dz = origin.getZ() + e.tz + 0.5 - viewer.getZ();
                if (dx*dx + dy*dy + dz*dz <= 64.0) {
                    renderFloatingLabel(ps, buf, mc, e.label);
                }
                ps.popPose();
            }

            // 实体文本同样 8 格剔除，多人独立（按观看者位置）
            {
                java.util.ArrayList<EntityLabel> close = new java.util.ArrayList<>();
                for (EntityLabel el : entityLabels) {
                    double dx = origin.getX() + el.tx() + 0.5 - viewer.getX();
                    double dy = origin.getY() + el.ty() + 0.5 - viewer.getY();
                    double dz = origin.getZ() + el.tz() + 0.5 - viewer.getZ();
                    if (dx*dx + dy*dy + dz*dz <= 64.0) close.add(el);
                }
                if (!close.isEmpty()) drawEntityLabels(ps, buf, mc, close.toArray(new EntityLabel[0]));
            }

            ps.popPose();
            buf.endBatch();
        }

        void dispose() {
            slot.dispose();
            entries = new RenderEntry[0]; entryCount   = 0;
            special = new RenderEntry[0]; specialCount = 0;
            entityLabels = NO_ENTITY_LABELS;
            rlBpId  = null; rlRotation = null;
        }
    }

    /** One GPU draw call per frame: translates to (origin - camera), then draws the VBO. */
    private static void drawVBO(VertexBuffer vbo, RenderLevelStageEvent event,
                                 BlockPos origin, Vec3 camera) {
        if (vbo == null || vbo.getFormat() == null) {
            if (dbgFrame % DBG_INTERVAL == 0 && isDebugSession())
                SpatialBlueprintMod.LOGGER.info("[VBO][DBG] drawVBO 跳过: vbo={}", vbo == null ? "null" : "format=null");
            return;
        }
        if (dbgFrame % DBG_INTERVAL == 0 && isDebugSession())
            SpatialBlueprintMod.LOGGER.info("[VBO][DBG] drawVBO GPU draw，origin={}，format={}",
                    origin, vbo.getFormat());

        Matrix4f mvMat = new Matrix4f(event.getPoseStack().last().pose());
        mvMat.translate(
                (float)(origin.getX() - camera.x),
                (float)(origin.getY() - camera.y),
                (float)(origin.getZ() - camera.z));

        RenderType.translucent().setupRenderState();
        net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            // CRITICAL: ChunkOffset must be (0,0,0) because rendertype_translucent.vsh
            // computes "Position + ChunkOffset" before projection. Our positions are
            // relative to origin; the model-view matrix already handles the world offset.
            // Without this, stale ChunkOffset from last terrain chunk renders blocks
            // hundreds of blocks away (invisible).
            if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0f, 0f, 0f);

            vbo.bind();
            vbo.drawWithShader(mvMat, new Matrix4f(RenderSystem.getProjectionMatrix()), shader);
            VertexBuffer.unbind();
        }
        RenderType.translucent().clearRenderState();
    }

    private static void renderVoidDimensionalSelection(Minecraft mc, RenderLevelStageEvent event) {
        if (voidPreviewMin == null || voidPreviewMax == null) return;

        // 起点角标（亮绿粗线，沿 min 格子棱边指向 max，高亮穿透）
        int vsx = voidPreviewMax.getX() >= voidPreviewMin.getX() ? 1 : -1;
        int vsy = voidPreviewMax.getY() >= voidPreviewMin.getY() ? 1 : -1;
        int vsz = voidPreviewMax.getZ() >= voidPreviewMin.getZ() ? 1 : -1;
        Vec3 voidStartCorner = new Vec3(
                voidPreviewMin.getX() + (vsx < 0 ? 1 : 0),
                voidPreviewMin.getY() + (vsy < 0 ? 1 : 0),
                voidPreviewMin.getZ() + (vsz < 0 ? 1 : 0));
        Outliner.getInstance().showCorner("void_start", voidStartCorner, 1, vsx, vsy, vsz)
                .renderPriority(2)
                .colored(GHOST_COLOR_START)
                .noDepth()
                .lineWidth(GHOST_POINT_LINE_WIDTH);

        // 终点角标（红色粗线，沿 max 格子棱边指向 min，高亮穿透）
        int vex = voidPreviewMin.getX() >= voidPreviewMax.getX() ? 1 : -1;
        int vey = voidPreviewMin.getY() >= voidPreviewMax.getY() ? 1 : -1;
        int vez = voidPreviewMin.getZ() >= voidPreviewMax.getZ() ? 1 : -1;
        Vec3 voidEndCorner = new Vec3(
                voidPreviewMax.getX() + (vex < 0 ? 1 : 0),
                voidPreviewMax.getY() + (vey < 0 ? 1 : 0),
                voidPreviewMax.getZ() + (vez < 0 ? 1 : 0));
        Outliner.getInstance().showCorner("void_end", voidEndCorner, 1, vex, vey, vez)
                .renderPriority(2)
                .colored(GHOST_COLOR_END)
                .noDepth()
                .lineWidth(GHOST_POINT_LINE_WIDTH);

        // 整体选区轮廓（Create 风格 ghost box，黄色）
        AABB selBox = new AABB(
                voidPreviewMin.getX(), voidPreviewMin.getY(), voidPreviewMin.getZ(),
                voidPreviewMax.getX() + 1, voidPreviewMax.getY() + 1, voidPreviewMax.getZ() + 1);
        Outliner.getInstance().chaseAABB("void_selection", selBox)
                .colored(GHOST_BOX_COLOR_VOID)
                .noDepth()
                .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                .lineWidth(GHOST_BOX_LINE_WIDTH);

        // 非法方块高亮：与空白选区同款（客户端扫描 isIllegalBlock → 红色穿透框）。
        // 选区不变时不重扫，仅重放缓存；[查看范围] 每次触发已在 setVoidPreview 清过缓存。
        if (!voidPreviewMin.equals(lastVoidIllegalMin) || !voidPreviewMax.equals(lastVoidIllegalMax)) {
            cachedVoidIllegalPositions.clear();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = voidPreviewMin.getX(); x <= voidPreviewMax.getX(); x++) {
                for (int y = voidPreviewMin.getY(); y <= voidPreviewMax.getY(); y++) {
                    for (int z = voidPreviewMin.getZ(); z <= voidPreviewMax.getZ(); z++) {
                        m.set(x, y, z);
                        if (mc.level != null && mc.level.isLoaded(m)
                                && com.xioyim.spatialblueprint.util.BlueprintSanitizer
                                        .isIllegalBlock(mc.level.getBlockState(m))) {
                            cachedVoidIllegalPositions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            lastVoidIllegalMin = new BlockPos(voidPreviewMin.getX(), voidPreviewMin.getY(), voidPreviewMin.getZ());
            lastVoidIllegalMax = new BlockPos(voidPreviewMax.getX(), voidPreviewMax.getY(), voidPreviewMax.getZ());
        }
        for (BlockPos pos : cachedVoidIllegalPositions) {
            AABB illegalBox = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            Outliner.getInstance().chaseAABB("void_illegal_" + pos.asLong(), illegalBox)
                    .colored(0xFF0000)
                    .noDepth()
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
        }

        // 危险方块（带 NBT / 危险实体）：服务端扫描结果，橙色穿透框。
        // 数据来自 [查看范围] 时发出的 C2S_RequestIllegalBlocksMessage，与空白选区同款。
        for (com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.IllegalBlock e
                : com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientDangerousBlocks) {
            if (e.x() < voidPreviewMin.getX() || e.x() > voidPreviewMax.getX()
                    || e.y() < voidPreviewMin.getY() || e.y() > voidPreviewMax.getY()
                    || e.z() < voidPreviewMin.getZ() || e.z() > voidPreviewMax.getZ()) continue;
            // 盔甲架圈 2 格高（脚底方块到头顶，实体位置取脚底）
            AABB dangerBox = "minecraft:armor_stand".equals(e.blockId())
                    ? new AABB(e.x(), e.y(), e.z(), e.x() + 1, e.y() + 2, e.z() + 1)
                    : new AABB(e.x(), e.y(), e.z(), e.x() + 1, e.y() + 1, e.z() + 1);
            Outliner.getInstance().chaseAABB("void_danger_" + e.x() + "_" + e.y() + "_" + e.z(), dangerBox)
                    .colored(0xFF8C00)
                    .noDepth()
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
        }

        // 特殊实体圈框（蓝，穿透）：仅物品展示框；盔甲架/生物改用发光标记
        for (com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.SpecialEntity se
                : com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage.clientSpecialEntities) {
            if (!"item_frame".equals(se.kind())) continue;
            if (se.x() < voidPreviewMin.getX() || se.x() > voidPreviewMax.getX()
                    || se.y() < voidPreviewMin.getY() || se.y() > voidPreviewMax.getY()
                    || se.z() < voidPreviewMin.getZ() || se.z() > voidPreviewMax.getZ()) continue;
            AABB specialBox = new AABB(se.x(), se.y(), se.z(), se.x() + 1, se.y() + 1, se.z() + 1);
            Outliner.getInstance().chaseAABB("void_special_" + se.x() + "_" + se.y() + "_" + se.z(), specialBox)
                    .colored(0x55FFFF)
                    .noDepth()
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
        }
        renderGlueBoxes("void_glue_", voidPreviewMin, voidPreviewMax);
        renderProtectedChunks("void_claim_", voidPreviewMin, voidPreviewMax);
    }

    /**
     * 每帧检查工程预览是否还该显示：数据在、维度对、距离在范围内；否则静默清除。
     * 距离口径与次元预览的自动取消完全一致（盒中心距离 &gt; 半对角线 + RENDER_CANCEL_MARGIN），
     * 走远时若工程界面开着顺手关掉。
     */
    private static void renderEngineeringPreviewIfActive(Minecraft mc, Player player,
                                                         RenderLevelStageEvent event) {
        if (!ClientEngineeringPreview.isActive()) return;
        String bpId = ClientEngineeringPreview.blueprintId();
        BlockPos min = ClientEngineeringPreview.min();
        BlockPos max = ClientEngineeringPreview.max();
        BlueprintData data = ClientBlueprintCache.get(bpId);
        if (data == null) {                     // 缓存被清（换服 / 重开蓝图库）
            ClientEngineeringPreview.clear();
            return;
        }
        if (mc.level == null || ClientEngineeringPreview.dimension() == null
                || !mc.level.dimension().location().toString().equals(ClientEngineeringPreview.dimension())) {
            ClientEngineeringPreview.clear();
            return;
        }
        double bCX = (min.getX() + max.getX() + 1) / 2.0;
        double bCY = (min.getY() + max.getY() + 1) / 2.0;
        double bCZ = (min.getZ() + max.getZ() + 1) / 2.0;
        int dx = max.getX() - min.getX();
        int dy = max.getY() - min.getY();
        int dz = max.getZ() - min.getZ();
        double halfDiag = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) / 2.0;
        double cancelDist = halfDiag + ConfigAccess.get(BlueprintConfig.RENDER_CANCEL_MARGIN);
        if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
            ClientEngineeringPreview.clear();
            if (mc.screen instanceof EngineeringBlueprintScreen) {
                mc.setScreen(null);
            }
            return;
        }
        renderEngineeringPreview(mc, player, data, min, max, event);
    }

    /**
     * 每帧渲染所有开着的项目预览框：范围有、维度对、距离在范围内；否则静默关掉该项目预览。
     * 只画框不画结构（项目蓝图没有方块数据）。Outliner 条目不刷新会自动淡出，
     * disable 之后不需要手动 remove。
     */
    private static void renderProjectPreviews(Minecraft mc, Player player) {
        if (mc.level == null) return;
        java.util.Map<String, ClientProjectPreview.Range> all = ClientProjectPreview.all();
        if (all.isEmpty()) return;
        String dim = mc.level.dimension().location().toString();
        for (java.util.Map.Entry<String, ClientProjectPreview.Range> en : all.entrySet()) {
            String pid = en.getKey();
            ClientProjectPreview.Range range = en.getValue();
            if (range == null || !range.hasBounds()) continue;   // 还没范围，等状态到了再画
            if (range.dimension() == null || !dim.equals(range.dimension())) {
                ClientProjectPreview.disable(pid);
                continue;
            }
            BlockPos o = range.origin();
            BlockPos s = range.size();
            double bCX = o.getX() + s.getX() / 2.0;
            double bCY = o.getY() + s.getY() / 2.0;
            double bCZ = o.getZ() + s.getZ() / 2.0;
            double halfDiag = Math.sqrt((double) s.getX() * s.getX()
                    + (double) s.getY() * s.getY() + (double) s.getZ() * s.getZ()) / 2.0;
            double cancelDist = halfDiag + ConfigAccess.get(BlueprintConfig.RENDER_CANCEL_MARGIN);
            if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
                ClientProjectPreview.disable(pid);
                continue;
            }
            AABB box = new AABB(o.getX(), o.getY(), o.getZ(),
                    o.getX() + s.getX(), o.getY() + s.getY(), o.getZ() + s.getZ());
            Outliner.getInstance().chaseAABB("project_preview_" + pid, box)
                    .colored(GHOST_BOX_COLOR_PROJECT)
                    .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
            Outliner.getInstance().chaseAABB("project_preview_" + pid + "_glow", box)
                    .colored(GHOST_BOX_COLOR_PROJECT)
                    .noDepth()
                    .lineWidth(GHOST_BOX_LINE_WIDTH);
            // 预览的基准点是世界 anchor：已激活 = 实例那一格，未激活 = 作者 anchor；竖线与预览盒同高
            if (range.anchor() != null) {
                renderAnchorLine("project_preview_" + pid, range.anchor(), o.getY(), s.getY());
            }
        }
    }

    /**
     * 每帧渲染所有开着的子工程预览线（规划模式 ◎）：一条 = 该子工程包围盒的 12 条边、
     * 条目颜色、<b>深度测试</b>（被方块遮住就看不见）、不画面贴图、不画文字、不标序号。
     * 维度不符 / 走远 → 静默关掉该条。Outliner 条目不刷新会自动淡出，disable 后不用手动 remove。
     */
    private static void renderSubProjectPreviews(Minecraft mc, Player player) {
        if (mc.level == null) return;
        java.util.Map<String, java.util.Map<Integer, ClientSubProjectPreview.Box>> all =
                ClientSubProjectPreview.all();
        if (all.isEmpty()) return;
        String dim = mc.level.dimension().location().toString();
        for (java.util.Map.Entry<String, java.util.Map<Integer, ClientSubProjectPreview.Box>> project
                : all.entrySet()) {
            String pid = project.getKey();
            for (java.util.Map.Entry<Integer, ClientSubProjectPreview.Box> en
                    : project.getValue().entrySet()) {
                int index = en.getKey();
                ClientSubProjectPreview.Box box = en.getValue();
                if (box == null) continue;
                if (box.dimension() == null || !dim.equals(box.dimension())) {
                    ClientSubProjectPreview.disable(pid, index);
                    SpatialBlueprintMod.LOGGER.info("[SubPreview] auto-off pid={} index={} reason=dimension", pid, index);
                    continue;
                }
                BlockPos o = box.min();
                BlockPos e = box.max();
                // 与 renderProjectPreviews 同一套距离公式（helper 里是同一份）：盒中心 + 半对角线 + 余量
                if (!ClientSubProjectPreview.withinRenderDistance(box,
                        player.getX(), player.getY(), player.getZ())) {
                    ClientSubProjectPreview.disable(pid, index);
                    SpatialBlueprintMod.LOGGER.info("[SubPreview] auto-off pid={} index={} reason=distance", pid, index);
                    continue;
                }
                // 0.04 外扩：子工程盒通常紧贴结构，线正好落在方块面上会被深度测试 z-fighting 吃掉；
                // 线宽 1/16（半径 1/32），外扩稍大于半径才保证整条线抬出方块表面（依然吃深度测试）
                final double eps = 0.04;
                AABB aabb = new AABB(o.getX() - eps, o.getY() - eps, o.getZ() - eps,
                        e.getX() + 1 + eps, e.getY() + 1 + eps, e.getZ() + 1 + eps);
                Outliner.getInstance().chaseAABB("subproject_preview_" + pid + "_" + index, aabb)
                        .colored(box.color())
                        .lineWidth(GHOST_BOX_LINE_WIDTH);
            }
        }
    }

    /**
     * 工程蓝图固定坐标预览渲染。
     *
     * <p>用调用方给的 {@code min}/{@code max} 作为固定原点，渲染完整的方块幽灵预览
     * （#7C6FBD 轮廓 + 半透明方块），不依赖准星目标，旋转固定为 NONE。</p>
     */
    private static void renderEngineeringPreview(Minecraft mc, Player player, BlueprintData data,
                                                  BlockPos min, BlockPos max,
                                                  RenderLevelStageEvent event) {
        BlockPos origin = min;
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // ── #7C6FBD 轮廓（Create 风格 ghost box）───────────────────────────
        AABB previewBox = new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1,
                max.getZ() + 1);
        Outliner.getInstance().chaseAABB("eng_preview", previewBox)
                .colored(GHOST_BOX_COLOR_ENGINEERING)
                .noDepth()
                .withFaceTextures(SB_FACE_TEXTURE, SB_FACE_TEXTURE_HIGHLIGHT)
                .lineWidth(GHOST_BOX_LINE_WIDTH);

        // ── 半透明方块幽灵 ───────────────────────────────────────────────
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RENDER_RANDOM.setSeed(42L);

        // ── Rebuild engineering render list when data/hide-settings/fluid-mode change ──
        com.xioyim.spatialblueprint.util.FluidDisplayMode engCurFluidMode = fluidMode;
        boolean engNeedRebuild = data != engRlData || engCurFluidMode != engRlFluidMode
                || hideTopN != engRlHideTopN || !hiddenTopBlocks.equals(engRlHiddenSnapshot);
        if (engNeedRebuild) {
            java.util.Set<String> hiddenPreview = BlueprintConfig.getHiddenPreviewBlocks();
            java.util.Set<String> latePlaceSet  = BlueprintConfig.getLatePlaceBlocks();
            java.util.List<RenderEntry> list = new java.util.ArrayList<>();
            for (BlueprintData.BlockEntry entry : data.allBlocks()) {
                BlockState s = entry.state;
                if (s.isAir()) continue;
                boolean isFluid = !s.getFluidState().isEmpty();
                if (isFluid) {
                    if (engCurFluidMode.hidesFluid(s)) continue;
                } else {
                    String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                    if (engCurFluidMode == com.xioyim.spatialblueprint.util.FluidDisplayMode.HIDE_ALL_FILTER
                            && hiddenPreview.contains(blockId)) continue;
                    if (!hiddenTopBlocks.isEmpty() && hiddenTopBlocks.contains(blockId)) continue;
                }
                boolean latePlace = !isFluid && latePlaceSet.contains(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                boolean whiteText = !isFluid && (latePlace
                        || s.getBlock() instanceof net.minecraft.world.level.block.SignBlock
                        || s.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL);
                list.add(new RenderEntry(s, entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(),
                        isFluid, latePlace, whiteText));
            }
            engRenderEntries    = list.toArray(new RenderEntry[0]);
            engRenderEntryCount = engRenderEntries.length;
            // specialEntries: white-text blocks (容器/告示牌/潜影盒)
            java.util.List<RenderEntry> engSpec = new java.util.ArrayList<>();
            for (RenderEntry e : engRenderEntries) { if (e.isWhiteText) engSpec.add(e); }
            engSpecialEntries    = engSpec.toArray(new RenderEntry[0]);
            engSpecialEntryCount = engSpec.size();
            // 工程预览不旋转，NONE 走恒等分支
            engEntityLabels = buildEntityLabels(data, data.size, Rotation.NONE);
            engRlData = data; engRlFluidMode = engCurFluidMode; engRlHideTopN = hideTopN;
            engRlHiddenSnapshot.clear(); engRlHiddenSnapshot.addAll(hiddenTopBlocks);
        }

        // ── Start async VBO build when render list changed ────────────────
        if (engNeedRebuild) engSlot.markDirty();
        ensureWhiteSprite();
        engSlot.startBuildIfDirty(player.level(), origin, engRenderEntries, engRenderEntryCount);

        // Upload one chunk per frame
        engSlot.uploadOneChunk();

        // Draw all uploaded chunks
        engSlot.draw(event, origin, camera);

        // ── Per-frame: 1px 线框 + lang 文本（容器/告示牌/潜影盒） ──
        poseStack.pushPose();
        poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        for (int i = 0; i < engSpecialEntryCount; i++) {
            RenderEntry e = engSpecialEntries[i];
            poseStack.pushPose();
            poseStack.translate(e.tx, e.ty, e.tz);
            LevelRenderer.renderLineBox(poseStack, bufferSource.getBuffer(RenderType.lines()),
                    0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                    0.9f, 0.9f, 0.9f, 0.8f, 0.9f, 0.9f, 0.9f);
            double dx = origin.getX() + e.tx + 0.5 - player.getX();
            double dy = origin.getY() + e.ty + 0.5 - player.getY();
            double dz = origin.getZ() + e.tz + 0.5 - player.getZ();
            if (dx*dx + dy*dy + dz*dz <= 64.0) {
                renderFloatingLabel(poseStack, bufferSource, mc, e.label);
            }
            poseStack.popPose();
        }

        // ── Per-frame: 实体预览（黄色 1px 线框 + 实体 lang 文本） ──
        {
            double pdx, pdy, pdz;
            java.util.ArrayList<EntityLabel> closeEngEntities = new java.util.ArrayList<>();
            for (EntityLabel el : engEntityLabels) {
                pdx = origin.getX() + el.tx() + 0.5 - player.getX();
                pdy = origin.getY() + el.ty() + 0.5 - player.getY();
                pdz = origin.getZ() + el.tz() + 0.5 - player.getZ();
                if (pdx*pdx + pdy*pdy + pdz*pdz <= 64.0) closeEngEntities.add(el);
            }
            if (!closeEngEntities.isEmpty()) {
                drawEntityLabels(poseStack, bufferSource, mc, closeEngEntities.toArray(new EntityLabel[0]));
            }
        }
        poseStack.popPose();
        bufferSource.endBatch();
    }

    // ── Level unload: GPU resource cleanup ───────────────────────────────
    // VertexBuffer holds OpenGL buffer objects; must be freed explicitly.
    // Java GC will not release GPU memory, so without this, every world
    // change leaks ~35 MB of VRAM until the next VBO rebuild or game exit.

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;

        boolean hadPending = bpSlot.hasPending() || engSlot.hasPending();
        boolean wasBuildingBp = bpSlot.isBuilding(), wasBuildingEng = engSlot.isBuilding();

        // 关 GPU 缓冲、丢弃在途的 CPU 缓冲（约 35 MB）并自增 generation，
        // 让仍在跑的后台线程结果被丢弃而不是传进新世界
        int bpCount  = bpSlot.dispose();
        int engCount = engSlot.dispose();
        disposeAllOtherSlots("世界卸载");

        // Drop render entry caches (hold RenderEntry → BlockState → Level refs)
        renderEntries    = new RenderEntry[0]; renderEntryCount    = 0;
        specialEntries   = new RenderEntry[0]; specialEntryCount   = 0;
        engRenderEntries = new RenderEntry[0]; engRenderEntryCount = 0;
        engSpecialEntries= new RenderEntry[0]; engSpecialEntryCount= 0;
        entityLabels     = NO_ENTITY_LABELS;   engEntityLabels     = NO_ENTITY_LABELS;
        rlBpId = null; rlRotation = null; engRlData = null;
        rlFluidMode = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
        engRlFluidMode = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
        // Reset white sprite cache so resource pack reloads get fresh UV
        whiteSpriteCached = false;

        // Reset preview state
        lastPreviewMin = null; lastPreviewMax = null; lastBottomCenter = null;

        // 放置中途下线/退存档：静态动画状态还在，但服务端的完成包发给了已断开的旧连接、
        // 重进后永远收不到 —— 不清的话增长线框冻在退出那一帧、再也清不掉（171 实测）。
        // 中途重进则剩下的动画不再显示、服务端静默收尾（与完成通知跳过同思路）。
        stopPlacementAnimation();

        SpatialBlueprintMod.LOGGER.info(
            "[VBO] 世界卸载：释放 bp×{} eng×{} GPU缓冲区，pending={} building=bp:{}/eng:{}",
            bpCount, engCount, hadPending, wasBuildingBp, wasBuildingEng);
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    @Nullable
    public static BlockPos getPreviewTarget(Minecraft mc, int maxDist) {
        Player player = mc.player;
        if (player == null) return null;
        float partialTick = mc.getFrameTime();
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        Vec3 end = eye.add(look.x * maxDist, look.y * maxDist, look.z * maxDist);
        ClipContext clipCtx = new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        BlockHitResult result = player.level().clip(clipCtx);
        return result.getType() == HitResult.Type.BLOCK ? result.getBlockPos() : null;
    }

    private static void renderFloatingLabel(PoseStack poseStack, MultiBufferSource bufferSource, Minecraft mc,
                                            net.minecraft.network.chat.Component name) {
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        float scale = 0.025f;
        poseStack.scale(-scale, -scale, scale);
        float tx = -mc.font.width(name) / 2.0f;
        float ty = -(float) mc.font.lineHeight / 2.0f;
        mc.font.drawInBatch(name, tx, ty, 0xFFFFFF, false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0x000000, 15728880);
        poseStack.popPose();
    }

    /** 实体注册 id → 实体 lang 名（如 minecraft:cow → 牛）。 */
    private static net.minecraft.network.chat.Component entityLangName(String typeId) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(typeId);
        if (rl != null) {
            net.minecraft.world.entity.EntityType<?> et =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(rl);
            if (et != null) {
                return net.minecraft.network.chat.Component.translatable(et.getDescriptionId());
            }
        }
        return net.minecraft.network.chat.Component.literal(typeId);
    }

    // ── Transform helpers delegated to shared BlueprintTransform util (#12) ──
    private static int transformX(int x, int y, int z, BlockPos size, Rotation rotation) {
        return com.xioyim.spatialblueprint.util.BlueprintTransform.x(x, y, z, size, rotation);
    }
    private static int transformY(int x, int y, int z, BlockPos size, Rotation rotation) {
        return com.xioyim.spatialblueprint.util.BlueprintTransform.y(x, y, z, size, rotation);
    }
    private static int transformZ(int x, int y, int z, BlockPos size, Rotation rotation) {
        return com.xioyim.spatialblueprint.util.BlueprintTransform.z(x, y, z, size, rotation);
    }
}
