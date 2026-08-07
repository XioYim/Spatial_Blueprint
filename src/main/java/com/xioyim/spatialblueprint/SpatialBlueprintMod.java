package com.xioyim.spatialblueprint;

import com.xioyim.spatialblueprint.block.entity.RenderBlockEntity;
import com.xioyim.spatialblueprint.client.renderer.RenderBlockRenderer;
import com.xioyim.spatialblueprint.command.SBCommands;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.SBUndoCooldown;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(SpatialBlueprintMod.MODID)
public class SpatialBlueprintMod {
    public static final String MODID = "spatial_blueprint";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public SpatialBlueprintMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // Ensure the mod's config subdirectory exists before Forge writes the config file.
        try {
            java.nio.file.Files.createDirectories(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("spatial_blueprint"));
        } catch (java.io.IOException ignored) {}

        context.registerConfig(ModConfig.Type.COMMON, BlueprintConfig.SPEC, "spatial_blueprint/spatial_blueprint.toml");

        SpatialBlueprintItems.register(modEventBus);
        SpatialBlueprintRegistry.register(modEventBus);
        BlueprintNetwork.register();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(BlueprintConfig::onConfigReload);

        MinecraftForge.EVENT_BUS.addListener(SBCommands::register);
        MinecraftForge.EVENT_BUS.addListener(SpatialBlueprintMod::onPlayerLogout);
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SBUndoCooldown.clearPlayer(event.getEntity().getUUID());
        // Clear server-side preview entry so nearby players stop seeing a stale box
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.xioyim.spatialblueprint.server.ServerPreviewTracker.clear(sp);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        BlueprintStorage.init();
        ProjectStorage.init();
        LOGGER.info("Spatial Blueprint mod loaded!");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        BlockEntityRenderers.register(SpatialBlueprintRegistry.RENDER_BLOCK_ENTITY.get(), RenderBlockRenderer::new);
        com.xioyim.spatialblueprint.client.ClientPreviewSettings.load();
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SpatialBlueprintItems.BLANK_BLUEPRINT);
            event.accept(SpatialBlueprintItems.SPATIAL_BLUEPRINT);
            event.accept(SpatialBlueprintItems.PROJECTION_BLUEPRINT);
            event.accept(SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT);
            event.accept(SpatialBlueprintItems.ENGINEERING_BLUEPRINT);
            event.accept(SpatialBlueprintItems.PROJECT_BLUEPRINT);
        }
    }
}
