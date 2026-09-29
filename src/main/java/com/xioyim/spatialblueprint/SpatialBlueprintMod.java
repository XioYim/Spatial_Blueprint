package com.xioyim.spatialblueprint;

import com.mojang.logging.LogUtils;
import com.xioyim.spatialblueprint.util.BlockSubstitutionGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;

/**
 * NeoForge 1.21.1 线主类。
 * 注册、网络、命令等按迁移计划书分阶段从 1.20.1 线（{@code origin/forge-1.20.1}）搬过来，
 * 本轮（SBN-001 / P1a）只含配置系统与 commonSetup 里的替换候选生成。
 */
@Mod(SpatialBlueprintMod.MODID)
public class SpatialBlueprintMod {
    public static final String MODID = "spatial_blueprint";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SpatialBlueprintMod(IEventBus modEventBus, ModContainer modContainer) {
        // Ensure the mod's config subdirectory exists before NeoForge writes the config file.
        try {
            Files.createDirectories(FMLPaths.CONFIGDIR.get().resolve("spatial_blueprint"));
        } catch (IOException ignored) {}

        modContainer.registerConfig(ModConfig.Type.COMMON, BlueprintConfig.SPEC, "spatial_blueprint/spatial_blueprint.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, GeneratedSubstitutionsConfig.SPEC,
                "spatial_blueprint/block_substitutions.toml");

        // SB-TODO(P2): SpatialBlueprintItems（物品注册）
        // SB-TODO(P2): SpatialBlueprintRegistry（方块 / 方块实体注册）
        // SB-TODO(P3): BlueprintNetwork（网络注册）
        // SB-TODO(P4): SBCommands（命令注册）
        // SB-TODO(P4): 登录登出事件（传送权限 / 存档身份 / 孤儿次元袋提示）
        // SB-TODO(P2): 创造物品栏（addCreative）
        // SB-TODO(P5): clientSetup（ClientModSetup）
        // SB-TODO(P4): ForagersInsightCompat（作物收获经验补丁）
        // SB-TODO(P2): Storage.init（BlueprintStorage / ProjectStorage）
        // SB-TODO(P4): FileLogger（BlueprintFileLogger.init / 关服 close）

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(BlueprintConfig::onConfigReload);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 扫描注册表，生成方块替换候选到独立 toml（幂等覆盖写）
        event.enqueueWork(BlockSubstitutionGenerator::generateAndSave);
        LOGGER.info("Spatial Blueprint mod loaded!");
    }
}
