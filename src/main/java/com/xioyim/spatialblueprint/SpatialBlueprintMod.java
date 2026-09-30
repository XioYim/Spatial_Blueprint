package com.xioyim.spatialblueprint;

import com.mojang.logging.LogUtils;
import com.xioyim.spatialblueprint.item.SpatialBlueprintComponents;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.util.BlockSubstitutionGenerator;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
        // ForagersInsight 兼容：关它的作物收获经验，否则捕获预检的 BreakEvent 会被当成收菜而爆发经验
        com.xioyim.spatialblueprint.util.ForagersInsightCompat.patchCropHarvestXpOff();
        modContainer.registerConfig(ModConfig.Type.COMMON, GeneratedSubstitutionsConfig.SPEC,
                "spatial_blueprint/block_substitutions.toml");

        SpatialBlueprintComponents.register(modEventBus);
        SpatialBlueprintItems.register(modEventBus);
        SpatialBlueprintRegistry.register(modEventBus);
        modEventBus.addListener(com.xioyim.spatialblueprint.network.BlueprintNetwork::register);

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.xioyim.spatialblueprint.command.SBCommands::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SpatialBlueprintMod::onPlayerLogout);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SpatialBlueprintMod::onPlayerLogin);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SpatialBlueprintMod::onChangeGameMode);
        // SB-TODO(P5): clientSetup（ClientModSetup）

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent event)
                        -> com.xioyim.spatialblueprint.log.BlueprintFileLogger.close());

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(BlueprintConfig::onConfigReload);
    }

    /**
     * 登录时四步推送：传送权限 → 存档身份 → 孤儿次元袋提示 → 配置页权限。
     *
     * <p>每个发包步骤都经 {@link #sendLoginStep} 兜 {@code UnsupportedOperationException}（K21）：
     * GameTest 自建的 mock 玩家在配置相位触发登录事件，NeoForge 相位检查拒绝 play 包；
     * 真玩家此时已是 play 阶段，不受影响。逐步兜底保证前一步失败不会跳过后面的
     * —— 尤其 {@code orphanBagsDetected} 的文件日志必须照写。</p>
     */
    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            sendLoginStep(sp, "teleport permission",
                    () -> com.xioyim.spatialblueprint.command.SBCommands.syncTeleportPerm(sp));
            sendLoginStep(sp, "world identity", () -> syncWorldIdentity(sp));
            notifyLostDimensionalBlueprints(sp);
            sendLoginStep(sp, "config permission", () -> syncConfigPerm(sp));
        }
    }

    /**
     * 单个登录发包步骤的 K21 兜底：半成品连接（GameTest 的 mock 玩家还在配置相位）发 play 包
     * 会抛 {@code UnsupportedOperationException}，记 debug 后继续下一步。
     * 只接这一种异常，其它异常照常抛出，不静默吞掉真 bug。
     */
    private static void sendLoginStep(ServerPlayer sp, String what, Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException e) {
            LOGGER.debug("[SpatialBlueprint] {} sync skipped for {}: {}",
                    what, sp.getGameProfile().getName(), e.getMessage());
        }
    }

    /**
     * 登录时把「能否打开/编辑服务器配置」推给客户端 —— 配置页面是 OP 专属，
     * 客户端自己算不出权限，不推的话非 OP 也能点开那个保存必被拒的假页面。
     */
    private static void syncConfigPerm(ServerPlayer sp) {
        com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(sp,
                new com.xioyim.spatialblueprint.network.s2c.S2C_ConfigPermMessage(sp.hasPermissions(2)));
    }

    /**
     * 把当前存档的身份推给客户端 —— 工程 / 次元蓝图右键时靠它就地判断归属。
     *
     * <p>蓝图目录是所有单人存档共用的，不推这份身份客户端就分不出手里这张蓝图是不是本档的。</p>
     */
    private static void syncWorldIdentity(ServerPlayer sp) {
        com.xioyim.spatialblueprint.util.WorldIdentity id =
                com.xioyim.spatialblueprint.util.WorldIdentity.get(sp.serverLevel());
        com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(sp,
                new com.xioyim.spatialblueprint.network.S2C_WorldIdentityMessage(
                        id.uuid().toString(),
                        sp.server.getWorldData().getLevelName()));
    }

    /**
     * 上线时检查该玩家的次元袋里有没有「蓝图文件已经没了」的孤儿。
     *
     * <p>管理员在服务器目录里手动删除或归档次元蓝图后，玩家那袋材料就再也无法使用了。
     * 不提示的话玩家只会发现材料空间莫名少了一块、却不知道发生了什么。</p>
     *
     * <p>只提示、不自动清理 —— 万一是误删，管理员还能把文件恢复回来；
     * 要不要放弃这袋材料由玩家自己在材料空间界面决定。</p>
     */
    private static void notifyLostDimensionalBlueprints(ServerPlayer sp) {
        com.xioyim.spatialblueprint.util.MaterialSpace space =
                com.xioyim.spatialblueprint.util.MaterialSpace.get(sp.serverLevel());
        int lost = 0;
        for (String bpId : space.bagIds(sp.getUUID())) {
            if (com.xioyim.spatialblueprint.util.BlueprintStorage.findFile(bpId) == null
                    && space.bagTotal(sp.getUUID(), bpId) > 0) {
                lost++;
            }
        }
        if (lost == 0) return;
        sendLoginStep(sp, "orphan bag notice",
                () -> com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(sp,
                        new com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult(
                                false, "spatial_blueprint.message.void_blueprint.lost_notice")));
        com.xioyim.spatialblueprint.log.BlueprintFileLogger.orphanBagsDetected(sp, lost);
    }

    /**
     * 切换游戏模式后重推传送权限 —— 创造模式天然拥有该权限，
     * 不重推的话玩家切到创造后按钮仍是灰的（切回生存则相反，会留下一个点了被服务端拒绝的按钮）。
     */
    private static void onChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // 本事件在新模式真正生效【之前】触发，此时 sp.isCreative() 还是旧值，
        // 不能直接调 TeleportPermissions.canTeleport()，必须用 event.getNewGameMode()。
        boolean allowed = sp.hasPermissions(2)
                || event.getNewGameMode() == net.minecraft.world.level.GameType.CREATIVE
                || com.xioyim.spatialblueprint.util.TeleportPermissions.get(sp.serverLevel())
                        .has(sp.getUUID());
        com.xioyim.spatialblueprint.network.BlueprintNetwork.sendToPlayer(sp,
                new com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage(allowed));
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        com.xioyim.spatialblueprint.util.SBUndoCooldown.clearPlayer(event.getEntity().getUUID());
        com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage.forget(event.getEntity().getUUID());
        // Clear server-side preview entry so nearby players stop seeing a stale box
        if (event.getEntity() instanceof ServerPlayer sp) {
            com.xioyim.spatialblueprint.server.ServerPreviewTracker.clear(sp);
        } else {
            // SB-TODO(P5): 客户端分支——清 ClientMaterialSpaceCache / ClientTeleportPermCache /
            // ClientConfigPermCache 三个缓存（1.20.1 用 DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)）。
            // 不许用运行时 dist 判断代替类加载隔离：专用服务端加载本类时就会连带解析
            // ClientMaterialSpaceCache，它又引用 MaterialSpaceScreen（extends Screen），
            // 触发 RuntimeDistCleaner 的 "invalid dist DEDICATED_SERVER" 直接让服务端启动失败。
        }
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
