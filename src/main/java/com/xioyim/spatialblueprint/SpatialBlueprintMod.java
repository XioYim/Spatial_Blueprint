package com.xioyim.spatialblueprint;

import com.xioyim.spatialblueprint.command.SBCommands;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.network.BlueprintNetwork;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.SBUndoCooldown;
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
        // ForagersInsight 兼容：关它的作物收获经验，否则捕获预检的 BreakEvent 会被当成收菜而爆发经验
        com.xioyim.spatialblueprint.util.ForagersInsightCompat.patchCropHarvestXpOff();
        // 自动生成的方块替换候选（独立 toml，仅参考）
        context.registerConfig(ModConfig.Type.COMMON, GeneratedSubstitutionsConfig.SPEC,
                "spatial_blueprint/block_substitutions.toml");

        SpatialBlueprintItems.register(modEventBus);
        SpatialBlueprintRegistry.register(modEventBus);
        BlueprintNetwork.register();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(BlueprintConfig::onConfigReload);

        MinecraftForge.EVENT_BUS.addListener(SBCommands::register);
        MinecraftForge.EVENT_BUS.addListener(SpatialBlueprintMod::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(SpatialBlueprintMod::onPlayerLogin);
        MinecraftForge.EVENT_BUS.addListener(SpatialBlueprintMod::onChangeGameMode);
        MinecraftForge.EVENT_BUS.addListener(SpatialBlueprintMod::onServerStopping);
    }

    /** 关服时释放日志句柄（每条已 flush，这里只是收尾）。 */
    private static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        com.xioyim.spatialblueprint.log.BlueprintFileLogger.close();
    }

    /**
     * 登录时把「能否使用蓝图传送」的结论推给客户端。
     *
     * <p>客户端自己算不出来（权限名单在服务端世界数据里），不推的话传送按钮会一直显示为无权限。</p>
     */
    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            SBCommands.syncTeleportPerm(sp);
            syncWorldIdentity(sp);
            notifyLostDimensionalBlueprints(sp);
            syncConfigPerm(sp);
        }
    }

    /**
     * 登录时把「能否打开/编辑服务器配置」推给客户端 —— 配置页面是 OP 专属，
     * 客户端自己算不出权限，不推的话非 OP 也能点开那个保存必被拒的假页面。
     */
    private static void syncConfigPerm(net.minecraft.server.level.ServerPlayer sp) {
        BlueprintNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new com.xioyim.spatialblueprint.network.s2c.S2C_ConfigPermMessage(sp.hasPermissions(2)));
    }

    /**
     * 把当前存档的身份推给客户端 —— 工程 / 次元蓝图右键时靠它就地判断归属。
     *
     * <p>蓝图目录是所有单人存档共用的，不推这份身份客户端就分不出手里这张蓝图是不是本档的。</p>
     */
    private static void syncWorldIdentity(net.minecraft.server.level.ServerPlayer sp) {
        com.xioyim.spatialblueprint.util.WorldIdentity id =
                com.xioyim.spatialblueprint.util.WorldIdentity.get(sp.serverLevel());
        BlueprintNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
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
    private static void notifyLostDimensionalBlueprints(net.minecraft.server.level.ServerPlayer sp) {
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
        BlueprintNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult(
                        false, "spatial_blueprint.message.void_blueprint.lost_notice"));
        com.xioyim.spatialblueprint.log.BlueprintFileLogger.orphanBagsDetected(sp, lost);
    }

    /**
     * 切换游戏模式后重推传送权限 —— 创造模式天然拥有该权限，
     * 不重推的话玩家切到创造后按钮仍是灰的（切回生存则相反，会留下一个点了被服务端拒绝的按钮）。
     */
    private static void onChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        // 本事件在新模式真正生效【之前】触发，此时 sp.isCreative() 还是旧值，
        // 不能直接调 TeleportPermissions.canTeleport()，必须用 event.getNewGameMode()。
        boolean allowed = sp.hasPermissions(2)
                || event.getNewGameMode() == net.minecraft.world.level.GameType.CREATIVE
                || com.xioyim.spatialblueprint.util.TeleportPermissions.get(sp.serverLevel())
                        .has(sp.getUUID());
        BlueprintNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage(allowed));
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SBUndoCooldown.clearPlayer(event.getEntity().getUUID());
        com.xioyim.spatialblueprint.network.C2S_RequestProjectListMessage.forget(event.getEntity().getUUID());
        // Clear server-side preview entry so nearby players stop seeing a stale box
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.xioyim.spatialblueprint.server.ServerPreviewTracker.clear(sp);
        } else {
            // 单人退出 / 客户端断线：清空材料空间缓存，避免下次进入其他存档时残留旧数据。
            //
            // 必须用 DistExecutor，不能写成 if (FMLEnvironment.dist == Dist.CLIENT) —— 那是
            // 运行时判断，阻止不了【类加载】：专用服务端加载本类时就会连带解析
            // ClientMaterialSpaceCache，它又引用 MaterialSpaceScreen（extends Screen），
            // 触发 RuntimeDistCleaner 的 "invalid dist DEDICATED_SERVER" 直接让服务端启动失败。
            // unsafeRunWhenOn 把方法引用包在 Supplier 里，只有 dist 匹配时才会实例化。
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> com.xioyim.spatialblueprint.client.ClientMaterialSpaceCache::clear);
            // 传送权限同理：不清的话会把上一个存档的权限带进下一个
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> com.xioyim.spatialblueprint.client.ClientTeleportPermCache::clear);
            // 配置页 OP 权限同理
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> com.xioyim.spatialblueprint.client.ClientConfigPermCache::clear);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        BlueprintStorage.init();
        ProjectStorage.init();
        com.xioyim.spatialblueprint.log.BlueprintFileLogger.init();
        // 扫描注册表，生成方块替换候选到独立 toml（幂等覆盖写）
        event.enqueueWork(com.xioyim.spatialblueprint.util.BlockSubstitutionGenerator::generateAndSave);
        LOGGER.info("Spatial Blueprint mod loaded!");
    }

    /**
     * 客户端初始化。实际内容全部在 {@link com.xioyim.spatialblueprint.client.ClientModSetup} 里 ——
     * 本方法不能直接引用任何客户端类型：注册配置界面的 lambda 会把
     * {@code net.minecraft.client.gui.screens.Screen} 带进本类的方法签名，
     * 专用服务端加载本类时就会被 RuntimeDistCleaner 拒绝，导致服务端启动失败。
     */
    private void clientSetup(final FMLClientSetupEvent event) {
        // 结构文件目录（.minecraft/schematics/，与 Litematica / Create 共用）不存在就建出来 ——
        // 空目录也比「没有这个文件夹」强：玩家至少知道该往哪儿放文件。
        com.xioyim.spatialblueprint.util.StructureFileScanner.ensureDir();
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.xioyim.spatialblueprint.client.ClientModSetup.init(event));
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
