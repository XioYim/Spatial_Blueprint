package com.xioyim.spatialblueprint.gametest;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.client.BlueprintFakeWorld;
import com.xioyim.spatialblueprint.client.RenderBlockParticleCache;
import com.xioyim.spatialblueprint.client.SpatialBlueprintClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;

/**
 * SBN-016 P5b 渲染底座 GameTest（t01～t03）。
 * t01 / t02 测纯逻辑（BlueprintFakeWorld / RenderBlockParticleCache）；
 * t03 实测 outliner 在专用测试服不可加载（RuntimeDistCleaner 拒绝客户端类），
 * 把「不可测」钉成边界断言，不为测试改生产类。
 */
@GameTestHolder(SpatialBlueprintMod.MODID)
@PrefixGameTestTemplate(false)
public class SBN016Tests {

    // ── t01：BlueprintFakeWorld（幽灵预览假世界）─────────────────────────────

    @GameTest(template = "empty")
    public static void t01_fakeWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HashMap<Long, BlockState> blocks = new HashMap<>();
        BlockPos stonePos = new BlockPos(101, 64, 101);
        BlockPos waterPos = new BlockPos(102, 64, 101);
        blocks.put(stonePos.asLong(), Blocks.STONE.defaultBlockState());
        blocks.put(waterPos.asLong(), Blocks.WATER.defaultBlockState());

        BlueprintFakeWorld fake = new BlueprintFakeWorld(level, new BlockPos(10, 64, 10), blocks);

        // 方块 / 流体状态：命中取表、未命中空气
        helper.assertTrue(fake.getBlockState(stonePos).is(Blocks.STONE), "hit returns stored state");
        helper.assertTrue(fake.getBlockState(new BlockPos(0, 0, 0)).isAir(), "miss returns air");
        helper.assertTrue(!fake.getFluidState(waterPos).isEmpty(), "fluid state from stored block");
        helper.assertTrue(fake.getFluidState(stonePos).isEmpty(), "stone has empty fluid");

        // 满亮度：block / sky / raw 一律 15（VBO 与放置原点、光照重建解耦）
        helper.assertValueEqual(fake.getBrightness(LightLayer.BLOCK, stonePos), 15, "block brightness 15");
        helper.assertValueEqual(fake.getBrightness(LightLayer.SKY, stonePos), 15, "sky brightness 15");
        helper.assertValueEqual(fake.getRawBrightness(stonePos, 0), 15, "raw brightness 15");

        // getShade 五档 + shade=false
        helper.assertValueEqual(fake.getShade(Direction.DOWN, true), 0.5f, "shade down");
        helper.assertValueEqual(fake.getShade(Direction.NORTH, true), 0.8f, "shade north");
        helper.assertValueEqual(fake.getShade(Direction.SOUTH, true), 0.8f, "shade south");
        helper.assertValueEqual(fake.getShade(Direction.WEST, true), 0.6f, "shade west");
        helper.assertValueEqual(fake.getShade(Direction.EAST, true), 0.6f, "shade east");
        helper.assertValueEqual(fake.getShade(Direction.UP, true), 1.0f, "shade up");
        helper.assertValueEqual(fake.getShade(Direction.DOWN, false), 1.0f, "shade=false is 1.0");

        // 方块实体恒 null；高度取真实世界
        helper.assertTrue(fake.getBlockEntity(stonePos) == null, "no block entity");
        helper.assertValueEqual(fake.getHeight(), level.getHeight(), "height from real world");
        helper.assertValueEqual(fake.getMinBuildHeight(), level.getMinBuildHeight(), "min build height from real world");
        helper.succeed();
    }

    // ── t02：RenderBlockParticleCache（破坏粒子缓存）─────────────────────────

    @GameTest(template = "empty")
    public static void t02_particleCache(GameTestHelper helper) {
        BlockPos pos = new BlockPos(5, 64, 5);
        BlockPos pos2 = new BlockPos(6, 64, 5);
        try {
            // put → remove 取回并移除（再取为 null）
            RenderBlockParticleCache.put(pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
            BlockState got = RenderBlockParticleCache.remove(pos);
            helper.assertTrue(got != null && got.is(Blocks.DIAMOND_BLOCK), "put then remove returns state");
            helper.assertTrue(RenderBlockParticleCache.remove(pos) == null, "second remove is null");

            // clear 后全空
            RenderBlockParticleCache.put(pos, Blocks.STONE.defaultBlockState());
            RenderBlockParticleCache.put(pos2, Blocks.GOLD_BLOCK.defaultBlockState());
            RenderBlockParticleCache.clear();
            helper.assertTrue(RenderBlockParticleCache.remove(pos) == null
                    && RenderBlockParticleCache.remove(pos2) == null, "clear empties cache");

            // clearAllCaches 之后第 13 个缓存也空（V6：漏清此处 → 本用例转红）
            RenderBlockParticleCache.put(pos, Blocks.STONE.defaultBlockState());
            SpatialBlueprintClient.clearAllCaches();
            helper.assertTrue(RenderBlockParticleCache.remove(pos) == null,
                    "clearAllCaches clears the 13th cache");
        } finally {
            RenderBlockParticleCache.clear();
            SpatialBlueprintClient.clearAllCaches();
        }
        helper.succeed();
    }

    // ── t03：outliner 在专用测试服不可加载（客户端边界，记录「不可测」）───────

    @GameTest(template = "empty")
    public static void t03_clientOnlyBoundary(GameTestHelper helper) {
        // SBN-016 实测：Outliner 的方法签名 / 字段引用 net.minecraft.client（MultiBufferSource 等），
        // 专用服务端上 NeoForge RuntimeDistCleaner 直接拒绝加载客户端类：
        //   "Attempted to load class net/minecraft/client/renderer/MultiBufferSource
        //    for invalid dist DEDICATED_SERVER"
        // → outliner 的槽位 / 淡出逻辑在本环境不可测（随 SBN-017 的 runClient 手测覆盖）。
        // 本用例把「加载必被拒」钉成边界断言，不为测试改生产类。
        Throwable rejection = null;
        try {
            Class.forName("com.xioyim.spatialblueprint.client.outliner.Outliner");
        } catch (Throwable t) {
            rejection = t;
        }
        helper.assertTrue(rejection != null, "Outliner must not load on the dedicated server");
        helper.assertTrue(String.valueOf(rejection.getMessage()).contains("invalid dist"),
                "rejected by the dist guard: " + rejection.getMessage());
        helper.succeed();
    }
}
