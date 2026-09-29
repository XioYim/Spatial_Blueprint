package com.xioyim.spatialblueprint;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * NeoForge 1.21.1 线主类（P0 骨架）。
 * 注册、配置、网络等按迁移计划书分阶段从 main 分支（Forge 1.20.1 线）搬过来。
 */
@Mod(SpatialBlueprintMod.MODID)
public class SpatialBlueprintMod {
    public static final String MODID = "spatial_blueprint";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SpatialBlueprintMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[SpatialBlueprint] NeoForge 1.21.1 skeleton loaded");
    }
}
