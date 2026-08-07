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
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.gui.EngineeringBlueprintScreen;
import com.xioyim.spatialblueprint.gui.PlacementScreen;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
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
        RenderEntry(BlockState s, int tx, int ty, int tz, boolean fluid, boolean latePlace) {
            this.state = s; this.tx = tx; this.ty = ty; this.tz = tz;
            this.relPos = new BlockPos(tx, ty, tz);
            this.isFluid = fluid; this.isLatePlace = latePlace;
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

    // ── VBO cache — chunked to spread upload cost across frames ─────────────
    /** Blocks per VBO chunk. ~4 k blocks ≈ 1.3 MB upload ≈ 7 ms on render thread. */
    private static final int UPLOAD_CHUNK_SIZE = 4096;
    private static VertexBuffer[] bpVbos      = new VertexBuffer[0];
    private static int            bpVboCount  = 0;
    private static boolean        bpVboDirty  = true;
    private static VertexBuffer[] engVbos     = new VertexBuffer[0];
    private static int            engVboCount = 0;
    private static boolean        engVboDirty = true;

    // ── Async VBO build state ─────────────────────────────────────────────
    private static volatile BufferBuilder.RenderedBuffer[] bpPendingChunks  = null;
    private static volatile int                            bpUploadIdx       = 0;
    private static volatile boolean                        bpBuilding        = false;
    private static volatile BufferBuilder.RenderedBuffer[] engPendingChunks = null;
    private static volatile int                            engUploadIdx      = 0;
    private static volatile boolean                        engBuilding       = false;
    // Generation counters: incremented on world unload so stale async results are discarded.
    private static volatile int bpGeneration  = 0;
    private static volatile int engGeneration = 0;

    // ── Fluid solid-color sprite UV (cached on render thread, passed to async build) ──
    private static float   cachedWhiteU = 0f, cachedWhiteV = 0f;
    private static boolean whiteSpriteCached = false;
    // ── Fluid color cache keys for VBO rebuild detection ─────────────────
    private static int rlFluidWaterColor  = -1;
    private static int rlFluidLavaColor   = -1;
    private static int engFluidWaterColor = -1;
    private static int engFluidLavaColor  = -1;

    /** Frame counter for throttled debug logging (log every N frames). */
    private static long dbgFrame = 0;
    private static final int DBG_INTERVAL = 10;

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
        return mc.player.isCreative() && mc.player.getTags().contains("DeBug");
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
        whiteSpriteCached = true;
    }

    /**
     * Emits a solid-color unit cube into a BLOCK-format BufferBuilder.
     * Uses a single UV point (from a static white sprite) so the final color equals the vertex color.
     * Must be called with the PoseStack already translated to the block's local origin.
     */
    private static void emitColoredCube(BufferBuilder bb, org.joml.Matrix4f mx,
                                         float r, float g, float b, float a, float u, float v) {
        final int LM = 15728880; // full-bright (sky=15, block=15)
        // Top +Y
        bb.vertex(mx,0,1,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,1,0).endVertex();
        bb.vertex(mx,1,1,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,1,0).endVertex();
        bb.vertex(mx,1,1,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,1,0).endVertex();
        bb.vertex(mx,0,1,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,1,0).endVertex();
        // Bottom -Y
        bb.vertex(mx,0,0,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,-1,0).endVertex();
        bb.vertex(mx,1,0,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,-1,0).endVertex();
        bb.vertex(mx,1,0,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,-1,0).endVertex();
        bb.vertex(mx,0,0,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,-1,0).endVertex();
        // North -Z
        bb.vertex(mx,0,0,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,-1).endVertex();
        bb.vertex(mx,0,1,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,-1).endVertex();
        bb.vertex(mx,1,1,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,-1).endVertex();
        bb.vertex(mx,1,0,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,-1).endVertex();
        // South +Z
        bb.vertex(mx,1,0,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,1).endVertex();
        bb.vertex(mx,1,1,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,1).endVertex();
        bb.vertex(mx,0,1,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,1).endVertex();
        bb.vertex(mx,0,0,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(0,0,1).endVertex();
        // West -X
        bb.vertex(mx,0,0,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(-1,0,0).endVertex();
        bb.vertex(mx,0,1,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(-1,0,0).endVertex();
        bb.vertex(mx,0,1,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(-1,0,0).endVertex();
        bb.vertex(mx,0,0,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(-1,0,0).endVertex();
        // East +X
        bb.vertex(mx,1,0,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(1,0,0).endVertex();
        bb.vertex(mx,1,1,0).color(r,g,b,a).uv(u,v).uv2(LM).normal(1,0,0).endVertex();
        bb.vertex(mx,1,1,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(1,0,0).endVertex();
        bb.vertex(mx,1,0,1).color(r,g,b,a).uv(u,v).uv2(LM).normal(1,0,0).endVertex();
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

    /** Bottom-layer center of the current preview, updated every render frame. Null when no preview. */
    @Nullable
    private static BlockPos lastBottomCenter = null;

    /** Min/max corners of the current preview bounding box (world coords). Null when no preview. */
    @Nullable private static BlockPos lastPreviewMin = null;
    @Nullable private static BlockPos lastPreviewMax = null;

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

    public static void setVoidPreview(BlockPos min, BlockPos max) {
        voidPreviewMin = min;
        voidPreviewMax = max;
    }
    public static void clearVoidPreview() {
        voidPreviewMin = null;
        voidPreviewMax = null;
    }

    /** 工程蓝图固定坐标预览（由 [开启预览] 按钮触发，关闭时清除）。 */
    @Nullable private static BlockPos engineeringPreviewMin = null;
    @Nullable private static BlockPos engineeringPreviewMax = null;

    public static void setEngineeringPreview(BlockPos min, BlockPos max) {
        engineeringPreviewMin = min;
        engineeringPreviewMax = max;
    }

    public static void clearEngineeringPreview() {
        engineeringPreviewMin = null;
        engineeringPreviewMax = null;
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
            renderEngineeringPreview(mc, player, mainHand, event);
        } else if (mainHand.getItem() instanceof SpatialBlueprintItem) {
            renderBlueprintPreview(mc, player, mainHand, event, false);
        } else if (mainHand.getItem() instanceof ProjectionBlueprintItem) {
            renderBlueprintPreview(mc, player, mainHand, event, true);
        } else {
            lastBottomCenter = null;
            clearVoidPreview();
        }

        // ── Render other players' blueprint outline boxes ─────────────────
        if (ClientPreviewSettings.isEnabled() && !ClientOtherPreviewCache.isEmpty()) {
            renderOtherPlayerPreviews(event);
        }
    }

    /** Outline color for other players' blueprint preview boxes (ARGB). */
    private static final float OB_R = 0.25f, OB_G = 0.75f, OB_B = 1.0f, OB_A = 0.9f;

    private static void renderOtherPlayerPreviews(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack ps = event.getPoseStack();

        for (ClientOtherPreviewCache.Entry e : ClientOtherPreviewCache.all()) {
            if (e.origin == null) continue;

            if (e.fullData != null) {
                // Full block preview — reuse existing pipeline (renders at e.origin with e.rotation)
                // This is handled by renderOtherFull; placeholder for future extension.
                // For now fall through to outline box.
            }

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

            double x0 = e.origin.getX() - camera.x;
            double y0 = e.origin.getY() - camera.y;
            double z0 = e.origin.getZ() - camera.z;
            double x1 = x0 + w;
            double y1 = y0 + h;
            double z1 = z0 + d;

            ps.pushPose();
            MultiBufferSource.BufferSource bufSrc =
                    mc.renderBuffers().bufferSource();
            VertexConsumer lines = bufSrc.getBuffer(RenderType.lines());
            LevelRenderer.renderLineBox(ps, lines,
                    x0, y0, z0, x1, y1, z1,
                    OB_R, OB_G, OB_B, OB_A, OB_R, OB_G, OB_B);
            bufSrc.endBatch(RenderType.lines());
            ps.popPose();
        }
    }

    // ── Client tick: auto-cancel distance check ───────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // Void preview distance check — clear silently when player walks too far away
        if (voidPreviewMin != null && voidPreviewMax != null) {
            double bCX = (voidPreviewMin.getX() + voidPreviewMax.getX() + 1) / 2.0;
            double bCY = (voidPreviewMin.getY() + voidPreviewMax.getY() + 1) / 2.0;
            double bCZ = (voidPreviewMin.getZ() + voidPreviewMax.getZ() + 1) / 2.0;
            int dx = voidPreviewMax.getX() - voidPreviewMin.getX();
            int dy = voidPreviewMax.getY() - voidPreviewMin.getY();
            int dz = voidPreviewMax.getZ() - voidPreviewMin.getZ();
            double halfDiag = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) / 2.0;
            double cancelDist = halfDiag + BlueprintConfig.RENDER_CANCEL_MARGIN.get();
            if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
                clearVoidPreview();
            }
        }

        // ── 工程蓝图自动取消：距离超出或维度不匹配时静默关闭预览 ─────────────────
        if (engineeringPreviewMin != null) {
            ItemStack engStack = player.getMainHandItem();
            if (!(engStack.getItem() instanceof EngineeringBlueprintItem)) {
                engStack = player.getOffhandItem();
            }
            if (engStack.getItem() instanceof EngineeringBlueprintItem
                    && getAlignMode(engStack) != AlignMode.OFF
                    && EngineeringBlueprintItem.hasBoundProject(engStack)) {
                EngineeringBlueprintItem.ProjectBoundsResult result =
                        EngineeringBlueprintItem.checkProjectBounds(engStack, player, mc.level);
                if (result != EngineeringBlueprintItem.ProjectBoundsResult.OK) {
                    setAlignMode(engStack, AlignMode.OFF);
                    clearEngineeringPreview();
                    if (mc.screen instanceof EngineeringBlueprintScreen) {
                        mc.setScreen(null);
                    }
                }
            }
        }

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
                ? ProjectionBlueprintItem.getBlueprintData(stack)
                : SpatialBlueprintItem.getBlueprintData(stack);
        if (data == null) return;

        // Resolve current target position (locked or cursor)
        BlockPos targetPos;
        if (SpatialBlueprintItem.isLocked(stack)) {
            targetPos = SpatialBlueprintItem.getLockedPos(stack);
        } else {
            targetPos = getPreviewTarget(mc, BlueprintConfig.PREVIEW_DISTANCE.get());
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
        double cancelDist = halfDiag + BlueprintConfig.RENDER_CANCEL_MARGIN.get();

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

        // Periodic preview position broadcast to server
        tickPreviewBroadcast(mc, player);
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
                ? com.xioyim.spatialblueprint.item.ProjectionBlueprintItem.getBlueprintData(stack)
                : SpatialBlueprintItem.getBlueprintData(stack);
        if (data == null) {
            if (lastBroadcastOrigin != null) sendPreviewClear();
            return;
        }

        boolean locked = SpatialBlueprintItem.isLocked(stack);
        int intervalTicks = BlueprintConfig.PREVIEW_BROADCAST_INTERVAL.get() * 20;

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
                net.minecraft.core.BlockPos target = getPreviewTarget(mc, BlueprintConfig.PREVIEW_DISTANCE.get());
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
                        origin, rot.ordinal(), sx, sy, sz, bpId));
    }

    /** Sends a clear (null origin) to the server and resets broadcast state. */
    public static void sendPreviewClear() {
        if (lastBroadcastOrigin == null) return;
        lastBroadcastOrigin = null;
        lastBroadcastRotationOrdinal = -1;
        broadcastTickCounter = 0;
        BlueprintNetwork.CHANNEL.sendToServer(
                new com.xioyim.spatialblueprint.network.C2S_PreviewUpdateMessage(
                        null, 0, 0, 0, 0, ""));
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

    private static void renderBlankBlueprintSelection(Minecraft mc, Player player, ItemStack stack, RenderLevelStageEvent event) {
        BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
        BlockPos p2 = BlankBlueprintItem.getPoint2(stack);

        if (p1 == null && p2 == null) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer builder = bufferSource.getBuffer(RenderType.lines());

        if (p1 != null) {
            AABB point1Box = new AABB(p1.getX(), p1.getY(), p1.getZ(), p1.getX() + 1, p1.getY() + 1, p1.getZ() + 1);
            LevelRenderer.renderLineBox(poseStack, builder, point1Box, 0.0f, 1.0f, 0.0f, 0.8f);
        }
        if (p2 != null) {
            AABB point2Box = new AABB(p2.getX(), p2.getY(), p2.getZ(), p2.getX() + 1, p2.getY() + 1, p2.getZ() + 1);
            LevelRenderer.renderLineBox(poseStack, builder, point2Box, 1.0f, 0.0f, 0.0f, 0.8f);
        }

        if (p1 != null && p2 != null) {
            BlockPos min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
            BlockPos max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));

            AABB selectionBox = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            LevelRenderer.renderLineBox(poseStack, builder, selectionBox, 0.0f, 0.8f, 1.0f, 0.8f);

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (player.level().isLoaded(pos) && BlueprintConfig.isExcluded(player.level().getBlockState(pos).getBlock())) {
                            AABB illegalBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                            LevelRenderer.renderLineBox(poseStack, builder, illegalBox, 0.67f, 0.0f, 0.0f, 1.0f);
                        }
                    }
                }
            }
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    private static void renderBlueprintPreview(Minecraft mc, Player player, ItemStack stack, RenderLevelStageEvent event, boolean isProjection) {
        final long tFnStart = System.nanoTime();
        // ── OFF mode: skip all rendering ─────────────────────────────────
        // AlignMode is now read from the item's NBT (per-item, not a shared static field).
        AlignMode alignMode = getAlignMode(stack);
        if (alignMode == AlignMode.OFF) {
            lastBottomCenter = null;
            return;
        }

        BlueprintData data = isProjection
                ? ProjectionBlueprintItem.getBlueprintData(stack)
                : SpatialBlueprintItem.getBlueprintData(stack);
        if (data == null) { lastBottomCenter = null; return; }

        BlockPos targetPos;
        if (SpatialBlueprintItem.isLocked(stack)) {
            targetPos = SpatialBlueprintItem.getLockedPos(stack);
            if (targetPos == null) { lastBottomCenter = null; return; }
        } else {
            targetPos = getPreviewTarget(mc, BlueprintConfig.PREVIEW_DISTANCE.get());
            if (targetPos == null) { lastBottomCenter = null; return; }
        }

        int xOffset = SpatialBlueprintItem.getXOffset(stack);
        int yOffset = SpatialBlueprintItem.getYOffset(stack);
        int zOffset = SpatialBlueprintItem.getZOffset(stack);
        Rotation rotation = SpatialBlueprintItem.getRotation(stack);
        BlockPos size = data.size;

        int alignOffX = getAlignOffsetX(size, rotation, alignMode);
        int alignOffZ = getAlignOffsetZ(size, rotation, alignMode);

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
        double cancelDist = halfDiag + BlueprintConfig.RENDER_CANCEL_MARGIN.get();

        if (player.position().distanceToSqr(bCX, bCY, bCZ) > cancelDist * cancelDist) {
            lastBottomCenter = null;
            return; // onClientTick will trigger the actual cancel shortly
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuilder = bufferSource.getBuffer(RenderType.lines());

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
            LevelRenderer.renderLineBox(poseStack, lineBuilder, previewBox, 0.0f, 0.8f, 1.0f, 0.8f);
        } else {
            lastBottomCenter = null;
            lastPreviewMin   = null;
            lastPreviewMax   = null;
        }

        poseStack.popPose();
        bufferSource.endBatch();

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RENDER_RANDOM.setSeed(42L);

        // ── Rebuild render list when blueprint/rotation/hide-settings/fluid-colors change ──
        int curWaterColor = BlueprintConfig.getWaterPreviewColor();
        int curLavaColor  = BlueprintConfig.getLavaPreviewColor();
        com.xioyim.spatialblueprint.util.FluidDisplayMode curFluidMode = fluidMode;
        boolean needRebuild = !blueprintId.equals(rlBpId) || rotation != rlRotation
                || curFluidMode != rlFluidMode || hideTopN != rlHideTopN
                || !hiddenTopBlocks.equals(rlHiddenSnapshot)
                || curWaterColor != rlFluidWaterColor || curLavaColor != rlFluidLavaColor;
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
                list.add(new RenderEntry(s, tx, ty, tz, isFluid, latePlace));
            }
            renderEntries    = list.toArray(new RenderEntry[0]);
            renderEntryCount = renderEntries.length;
            java.util.List<RenderEntry> specList = new java.util.ArrayList<>();
            for (RenderEntry e : renderEntries) { if (e.isLatePlace) specList.add(e); }
            specialEntries    = specList.toArray(new RenderEntry[0]);
            specialEntryCount = specialEntries.length;
            rlBpId = blueprintId; rlRotation = rotation;
            rlFluidMode = curFluidMode; rlHideTopN = hideTopN;
            rlHiddenSnapshot.clear(); rlHiddenSnapshot.addAll(hiddenTopBlocks);
            rlFluidWaterColor = curWaterColor; rlFluidLavaColor = curLavaColor;
        }

        // ── Start async VBO build when render list changed ────────────────
        dbgFrame++;
        if (needRebuild) {
            if (isDebugSession())
                SpatialBlueprintMod.LOGGER.info("[VBO][DBG] 渲染列表变更 → 触发异步重建，count={}", renderEntryCount);
            bpVboDirty = true;
        }
        if (bpVboDirty && !bpBuilding) {
            ensureWhiteSprite();
            startAsyncBpBuild(player.level(), origin, renderEntries, renderEntryCount);
            bpVboDirty = false;
        }

        // ── Upload one chunk per frame (spreads GPU upload cost) ─────────
        BufferBuilder.RenderedBuffer[] pendingChunks = bpPendingChunks;
        if (pendingChunks != null && !bpBuilding && bpUploadIdx < pendingChunks.length) {
            if (bpUploadIdx == 0) {
                // First chunk: size the VBO array
                bpVbos = new VertexBuffer[pendingChunks.length];
                bpVboCount = 0;
            }
            bpVbos[bpUploadIdx] = uploadBuffer(pendingChunks[bpUploadIdx]);
            bpVboCount = bpUploadIdx + 1;
            bpUploadIdx++;
            if (bpUploadIdx == pendingChunks.length) {
                bpPendingChunks = null; // all done
                if (isDebugSession())
                    SpatialBlueprintMod.LOGGER.info("[VBO][DBG] bp 全部分块上传完成，chunks={}", pendingChunks.length);
            }
        }

        // ── Draw all uploaded chunks (may be partially uploaded) ─────────
        long t0 = System.nanoTime();
        for (int ci = 0; ci < bpVboCount; ci++) drawVBO(bpVbos[ci], event, origin, camera);
        long t1 = System.nanoTime();

        // ── Per-frame: late-place blocks only (label overlay; fluids are now in VBO) ──
        poseStack.pushPose();
        poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        for (int i = 0; i < specialEntryCount; i++) {
            RenderEntry e = specialEntries[i];
            poseStack.pushPose();
            poseStack.translate(e.tx, e.ty, e.tz);
            renderBlockNameLabel(poseStack, bufferSource, mc, e.state);
            poseStack.popPose();
        }
        long t2 = System.nanoTime();
        poseStack.popPose();
        bufferSource.endBatch();
        long t3 = System.nanoTime();

        if (dbgFrame % DBG_INTERVAL == 0 && isDebugSession())
            SpatialBlueprintMod.LOGGER.info(
                "[PERF][DBG] 帧#{} count={} building={} | total={}ms drawVBO={}ms loop={}ms endBatch={}ms | chunks={}/{}",
                dbgFrame, renderEntryCount, bpBuilding,
                (t3-tFnStart)/1_000_000.0,
                (t1-t0)/1_000_000.0, (t2-t1)/1_000_000.0, (t3-t2)/1_000_000.0,
                bpVboCount, bpPendingChunks != null ? bpPendingChunks.length : bpVboCount);
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
        // Collect solid + fluid entries (latePlace handled per-frame as labels)
        java.util.List<RenderEntry> buildList = new java.util.ArrayList<>(count);
        java.util.HashMap<Long, BlockState> blockMap = new java.util.HashMap<>(count);
        int fluidCount = 0;
        for (int i = 0; i < count; i++) {
            RenderEntry e = entries[i];
            if (e.isLatePlace) continue;
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

        int waterRgb = BlueprintConfig.getWaterPreviewColor();
        int lavaRgb  = BlueprintConfig.getLavaPreviewColor();

        int numChunks = (buildList.size() + UPLOAD_CHUNK_SIZE - 1) / UPLOAD_CHUNK_SIZE;
        BufferBuilder.RenderedBuffer[] result = new BufferBuilder.RenderedBuffer[numChunks];

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
                    boolean isLava = e.state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
                    int rgb = isLava ? lavaRgb : waterRgb;
                    float r = ((rgb >> 16) & 0xFF) / 255.0f;
                    float g = ((rgb >>  8) & 0xFF) / 255.0f;
                    float b = (rgb & 0xFF) / 255.0f;
                    emitColoredCube(bb, ps.last().pose(), r, g, b, 0.75f, whiteU, whiteV);
                } else {
                    long seed = e.state.getSeed(e.relPos);
                    rand.setSeed(seed);
                    try {
                        BakedModel model = dispatcher.getBlockModel(e.state);
                        modelRenderer.tesselateBlock(fakeWorld, model, e.state, e.relPos, ps, bb,
                                false, rand, seed, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
                    } catch (Exception ignored) {}
                }
                ps.popPose();
            }
            result[c] = bb.end();
        }
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

    /** Starts an async background build for the blueprint preview VBO. */
    private static void startAsyncBpBuild(net.minecraft.world.level.Level level, BlockPos origin,
                                           RenderEntry[] entries, int count) {
        if (bpBuilding) return;
        bpBuilding = true;
        // Close old VBOs immediately so GPU memory is freed
        for (VertexBuffer v : bpVbos) { if (v != null) v.close(); }
        bpVbos = new VertexBuffer[0]; bpVboCount = 0;
        bpPendingChunks = null; bpUploadIdx = 0;
        final RenderEntry[] snap = entries.clone();
        final int cnt = count;
        final BlockPos org = origin;
        final int gen = bpGeneration;
        final boolean dbg = isDebugSession();
        final float wu = cachedWhiteU, wv = cachedWhiteV;
        Thread t = new Thread(() -> {
            try {
                BufferBuilder.RenderedBuffer[] result = buildChunks(level, org, snap, cnt, wu, wv);
                if (bpGeneration == gen) {
                    bpPendingChunks = result;
                    if (dbg) SpatialBlueprintMod.LOGGER.info("[VBO][DBG] bp 后台构建完成，chunks={}（等待上传）", result.length);
                } else {
                    SpatialBlueprintMod.LOGGER.warn("[VBO] bp 后台构建结果被丢弃：世界已切换（gen {} → {}）", gen, bpGeneration);
                }
            }
            catch (Exception ex) { SpatialBlueprintMod.LOGGER.warn("[VBO] bp 后台构建异常，origin={}", org, ex); }
            finally { bpBuilding = false; }
        }, "blueprint-vbo-bp");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Starts an async background build for the engineering preview VBO. */
    private static void startAsyncEngBuild(net.minecraft.world.level.Level level, BlockPos origin,
                                            RenderEntry[] entries, int count) {
        if (engBuilding) return;
        engBuilding = true;
        for (VertexBuffer v : engVbos) { if (v != null) v.close(); }
        engVbos = new VertexBuffer[0]; engVboCount = 0;
        engPendingChunks = null; engUploadIdx = 0;
        final RenderEntry[] snap = entries.clone();
        final int cnt = count;
        final BlockPos org = origin;
        final int gen = engGeneration;
        final boolean dbg = isDebugSession();
        final float wu = cachedWhiteU, wv = cachedWhiteV;
        Thread t = new Thread(() -> {
            try {
                BufferBuilder.RenderedBuffer[] result = buildChunks(level, org, snap, cnt, wu, wv);
                if (engGeneration == gen) {
                    engPendingChunks = result;
                    if (dbg) SpatialBlueprintMod.LOGGER.info("[VBO][DBG] eng 后台构建完成，chunks={}（等待上传）", result.length);
                } else {
                    SpatialBlueprintMod.LOGGER.warn("[VBO] eng 后台构建结果被丢弃：世界已切换（gen {} → {}）", gen, engGeneration);
                }
            }
            catch (Exception ex) { SpatialBlueprintMod.LOGGER.warn("[VBO] eng 后台构建异常，origin={}", org, ex); }
            finally { engBuilding = false; }
        }, "blueprint-vbo-eng");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static String vboStatus(VertexBuffer v) {
        return v.getFormat() != null ? "有效(format=" + v.getFormat() + ")" : "已上传但format=null";
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

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer builder = bufferSource.getBuffer(RenderType.lines());

        // 起点框（绿色）
        AABB minBox = new AABB(voidPreviewMin.getX(), voidPreviewMin.getY(), voidPreviewMin.getZ(),
                voidPreviewMin.getX() + 1, voidPreviewMin.getY() + 1, voidPreviewMin.getZ() + 1);
        LevelRenderer.renderLineBox(poseStack, builder, minBox, 0.0f, 1.0f, 0.0f, 0.8f);

        // 终点框（红色）
        AABB maxBox = new AABB(voidPreviewMax.getX(), voidPreviewMax.getY(), voidPreviewMax.getZ(),
                voidPreviewMax.getX() + 1, voidPreviewMax.getY() + 1, voidPreviewMax.getZ() + 1);
        LevelRenderer.renderLineBox(poseStack, builder, maxBox, 1.0f, 0.0f, 0.0f, 0.8f);

        // 整体选区轮廓（蓝色）
        AABB selBox = new AABB(
                voidPreviewMin.getX(), voidPreviewMin.getY(), voidPreviewMin.getZ(),
                voidPreviewMax.getX() + 1, voidPreviewMax.getY() + 1, voidPreviewMax.getZ() + 1);
        LevelRenderer.renderLineBox(poseStack, builder, selBox, 0.0f, 0.8f, 1.0f, 0.8f);

        poseStack.popPose();
        bufferSource.endBatch();
    }

    /**
     * 工程蓝图固定坐标预览渲染。
     *
     * <p>使用 {@link #engineeringPreviewMin}/{@link #engineeringPreviewMax} 作为固定原点，
     * 渲染完整的方块幽灵预览（橙色轮廓 + 半透明方块），不依赖准星目标，旋转固定为 NONE。</p>
     */
    private static void renderEngineeringPreview(Minecraft mc, Player player, ItemStack stack,
                                                  RenderLevelStageEvent event) {
        AlignMode alignMode = getAlignMode(stack);
        if (alignMode == AlignMode.OFF) return;
        if (engineeringPreviewMin == null || engineeringPreviewMax == null) return;

        BlueprintData data = EngineeringBlueprintItem.getBlueprintData(stack);
        if (data == null) return;

        BlockPos origin = engineeringPreviewMin;
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        // ── 橙色轮廓 ─────────────────────────────────────────────────────
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuilder = bufferSource.getBuffer(RenderType.lines());

        AABB previewBox = new AABB(
                engineeringPreviewMin.getX(), engineeringPreviewMin.getY(), engineeringPreviewMin.getZ(),
                engineeringPreviewMax.getX() + 1, engineeringPreviewMax.getY() + 1,
                engineeringPreviewMax.getZ() + 1);
        LevelRenderer.renderLineBox(poseStack, lineBuilder, previewBox, 1.0f, 0.55f, 0.0f, 0.9f);
        poseStack.popPose();
        bufferSource.endBatch();

        // ── 半透明方块幽灵 ───────────────────────────────────────────────
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RENDER_RANDOM.setSeed(42L);

        // ── Rebuild engineering render list when data/hide-settings/fluid-colors change ──
        int engCurWater = BlueprintConfig.getWaterPreviewColor();
        int engCurLava  = BlueprintConfig.getLavaPreviewColor();
        com.xioyim.spatialblueprint.util.FluidDisplayMode engCurFluidMode = fluidMode;
        boolean engNeedRebuild = data != engRlData || engCurFluidMode != engRlFluidMode
                || hideTopN != engRlHideTopN || !hiddenTopBlocks.equals(engRlHiddenSnapshot)
                || engCurWater != engFluidWaterColor || engCurLava != engFluidLavaColor;
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
                list.add(new RenderEntry(s, entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(), isFluid, latePlace));
            }
            engRenderEntries    = list.toArray(new RenderEntry[0]);
            engRenderEntryCount = engRenderEntries.length;
            // specialEntries: late-place blocks only (fluids are now in VBO)
            java.util.List<RenderEntry> engSpec = new java.util.ArrayList<>();
            for (RenderEntry e : engRenderEntries) { if (e.isLatePlace) engSpec.add(e); }
            engSpecialEntries    = engSpec.toArray(new RenderEntry[0]);
            engSpecialEntryCount = engSpec.size();
            engRlData = data; engRlFluidMode = engCurFluidMode; engRlHideTopN = hideTopN;
            engRlHiddenSnapshot.clear(); engRlHiddenSnapshot.addAll(hiddenTopBlocks);
            engFluidWaterColor = engCurWater; engFluidLavaColor = engCurLava;
        }

        // ── Start async VBO build when render list changed ────────────────
        if (engNeedRebuild) engVboDirty = true;
        if (engVboDirty && !engBuilding) {
            ensureWhiteSprite();
            startAsyncEngBuild(player.level(), origin, engRenderEntries, engRenderEntryCount);
            engVboDirty = false;
        }
        // Upload one chunk per frame
        BufferBuilder.RenderedBuffer[] engPending = engPendingChunks;
        if (engPending != null && !engBuilding && engUploadIdx < engPending.length) {
            if (engUploadIdx == 0) { engVbos = new VertexBuffer[engPending.length]; engVboCount = 0; }
            engVbos[engUploadIdx] = uploadBuffer(engPending[engUploadIdx]);
            engVboCount = engUploadIdx + 1;
            engUploadIdx++;
            if (engUploadIdx == engPending.length) engPendingChunks = null;
        }

        // Draw all uploaded chunks
        for (int ci = 0; ci < engVboCount; ci++) drawVBO(engVbos[ci], event, origin, camera);

        // ── Per-frame: late-place blocks only (fluids are now in VBO) ────
        poseStack.pushPose();
        poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        for (int i = 0; i < engSpecialEntryCount; i++) {
            RenderEntry e = engSpecialEntries[i];
            poseStack.pushPose();
            poseStack.translate(e.tx, e.ty, e.tz);
            renderBlockNameLabel(poseStack, bufferSource, mc, e.state);
            poseStack.popPose();
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

        int bpCount = bpVboCount, engCount = engVboCount;
        boolean hadPending = bpPendingChunks != null || engPendingChunks != null;

        // Close all GPU buffers
        for (VertexBuffer v : bpVbos)  { if (v != null) v.close(); }
        for (VertexBuffer v : engVbos) { if (v != null) v.close(); }
        bpVbos      = new VertexBuffer[0]; bpVboCount  = 0; bpVboDirty  = true;
        engVbos     = new VertexBuffer[0]; engVboCount = 0; engVboDirty = true;

        // Drop pending CPU-side buffers (~35 MB) so they can be GC'd.
        // Increment generation counters so any still-running async thread's
        // result is silently discarded rather than uploaded to the new world.
        bpGeneration++;  engGeneration++;
        bpPendingChunks  = null; bpUploadIdx  = 0;
        engPendingChunks = null; engUploadIdx = 0;

        // Drop render entry caches (hold RenderEntry → BlockState → Level refs)
        renderEntries    = new RenderEntry[0]; renderEntryCount    = 0;
        specialEntries   = new RenderEntry[0]; specialEntryCount   = 0;
        engRenderEntries = new RenderEntry[0]; engRenderEntryCount = 0;
        engSpecialEntries= new RenderEntry[0]; engSpecialEntryCount= 0;
        rlBpId = null; rlRotation = null; engRlData = null;
        rlFluidMode = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
        engRlFluidMode = com.xioyim.spatialblueprint.util.FluidDisplayMode.SHOW_ALL;
        rlFluidWaterColor = -1; rlFluidLavaColor = -1;
        engFluidWaterColor = -1; engFluidLavaColor = -1;
        // Reset white sprite cache so resource pack reloads get fresh UV
        whiteSpriteCached = false;

        // Reset preview state
        lastPreviewMin = null; lastPreviewMax = null; lastBottomCenter = null;

        SpatialBlueprintMod.LOGGER.info(
            "[VBO] 世界卸载：释放 bp×{} eng×{} GPU缓冲区，pending={} building=bp:{}/eng:{}",
            bpCount, engCount, hadPending, bpBuilding, engBuilding);
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

    private static void renderBlockNameLabel(PoseStack poseStack, MultiBufferSource bufferSource, Minecraft mc, BlockState state) {
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        float scale = 0.025f;
        poseStack.scale(-scale, -scale, scale);
        net.minecraft.network.chat.Component name = state.getBlock().getName();
        float tx = -mc.font.width(name) / 2.0f;
        float ty = -(float) mc.font.lineHeight / 2.0f;
        mc.font.drawInBatch(name, tx, ty, 0xFFFFFF, false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0x000000, 15728880);
        poseStack.popPose();
    }

    private static double getMovingEffectPos(AABB boundingBox, int t, double incTime, float offset) {
        return boundingBox.minY + (Math.cos(t * incTime * offset) * 0.5 + 0.5) * (boundingBox.maxY - boundingBox.minY);
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
