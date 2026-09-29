package com.xioyim.spatialblueprint;

import com.mojang.logging.LogUtils;
import com.xioyim.spatialblueprint.item.SpatialBlueprintComponents;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.util.BlockSubstitutionGenerator;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;

/**
 * NeoForge 1.21.1 线主类。
 * 注册、网络、命令等按迁移计划书分阶段从 1.20.1 线（{@code origin/forge-1.20.1}）搬过来，
 * 本轮（SBN-004 / P2b）接上数据组件、物品、方块与创造物品栏。
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

        SpatialBlueprintComponents.register(modEventBus);
        SpatialBlueprintItems.register(modEventBus);
        SpatialBlueprintRegistry.register(modEventBus);
        modEventBus.addListener(com.xioyim.spatialblueprint.network.BlueprintNetwork::register);

        // SB-TODO(P4): SBCommands（命令注册）
        // SB-TODO(P4): 登录登出事件（传送权限 / 存档身份 / 孤儿次元袋提示）
        // SB-TODO(P5): clientSetup（ClientModSetup）
        // SB-TODO(P4): ForagersInsightCompat（作物收获经验补丁）

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent event)
                        -> com.xioyim.spatialblueprint.log.BlueprintFileLogger.close());

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(BlueprintConfig::onConfigReload);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        BlueprintStorage.init();
        ProjectStorage.init();
        com.xioyim.spatialblueprint.log.BlueprintFileLogger.init();
        // 扫描注册表，生成方块替换候选到独立 toml（幂等覆盖写）
        event.enqueueWork(BlockSubstitutionGenerator::generateAndSave);
        LOGGER.info("Spatial Blueprint mod loaded!");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SpatialBlueprintItems.BLANK_BLUEPRINT);
            event.accept(SpatialBlueprintItems.SPATIAL_BLUEPRINT);
            event.accept(SpatialBlueprintItems.PROJECTION_BLUEPRINT);
            event.accept(SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT);
            event.accept(SpatialBlueprintItems.ENGINEERING_BLUEPRINT);
            event.accept(SpatialBlueprintItems.REWARD_BLUEPRINT);
            event.accept(SpatialBlueprintItems.PUBLIC_PROJECT_BLUEPRINT);
            event.accept(SpatialBlueprintItems.PRIVATE_PROJECT_BLUEPRINT);
        }
    }
}
