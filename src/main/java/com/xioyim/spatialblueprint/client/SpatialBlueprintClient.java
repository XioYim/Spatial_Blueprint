package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端专属入口（P5 设计决定 1）：由 FML 按 dist 加载，取代 1.20.1 的
 * ClientModSetup + DistExecutor —— NeoForge 1.21 不再剥离客户端类，也没有 DistExecutor，
 * 客户端逻辑必须待在只有客户端才会加载的类里。
 *
 * <p>本类不做任何服务端判断：{@code @Mod(dist = Dist.CLIENT)} 保证专用服务端不构造它，
 * 主类 SpatialBlueprintMod 中没有任何对 client 包的引用。</p>
 */
@Mod(value = SpatialBlueprintMod.MODID, dist = Dist.CLIENT)
public final class SpatialBlueprintClient {

    public SpatialBlueprintClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(SpatialBlueprintClient::onLoggingOut);
    }

    /**
     * 客户端初始化（1.20.1 ClientModSetup.init 的三件事）。
     *
     * @param container ModContainer（配置界面工厂注册用，J48：container.registerExtensionPoint）
     */
    private void clientSetup(final FMLClientSetupEvent event) {
        // SB-TODO(P5): 注册 RenderBlockRenderer（BlockEntityRenderers.register，SBN-016 渲染底座）
        ClientPreviewSettings.load();
        // SB-TODO(P6): 配置界面工厂（container.registerExtensionPoint(IConfigScreenFactory.class, …)，J48；
        // 原 1.20.1 在 ClientModSetup 里注册 ConfigScreenHandler.ConfigScreenFactory，
        // 打开前用 ClientConfigPermCache 拦非 OP）
    }

    /**
     * 收到他人预览广播的客户端入口：先滤掉自己的广播，再交给缓存。
     *
     * <p>自过滤需要本地玩家（net.minecraft.client），放本类是为了让
     * {@link ClientOtherPreviewCache} 保持纯逻辑、能在 GameTest 里测。</p>
     */
    public static void receiveOtherPreview(com.xioyim.spatialblueprint.network.S2C_PreviewBroadcastMessage msg) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // Don't show our own preview via broadcast
        if (mc.player != null && mc.player.getUUID().equals(msg.playerUUID)) return;
        ClientOtherPreviewCache.receive(msg);
    }

    /**
     * 断线 / 退出世界时清空全部客户端缓存（P5 设计决定 2：由入口集中清理）。
     *
     * <p>1.20.1 每个缓存类各带一个 {@code ClientPlayerNetworkEvent.LoggingOut} 监听清自己；
     * 迁移后集中到这里，缓存类保持纯逻辑（可在 GameTest 里直接调 clear 验证）。</p>
     */
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAllCaches();
    }

    /**
     * 断线清理的落点（t07 直接调它，不 post 客户端事件）。
     *
     * <p>清单与 1.20.1 带 LoggingOut 监听的类逐个对上：ClientBlueprintCache / ClientCaptureCooldown /
     * ClientConfigMirror / ClientConfigPermCache / ClientLibraryCache / ClientMaterialSpaceCache /
     * ClientOtherPreviewCache / ClientProjectEntriesCache / ClientProjectListCache /
     * ClientProjectStatusCache / ClientTeleportPermCache / ClientWorldIdentity（共 12 个）。</p>
     */
    public static void clearAllCaches() {
        ClientBlueprintCache.clearAll();
        ClientCaptureCooldown.clear();
        ClientConfigMirror.clear();
        ClientConfigPermCache.clear();
        ClientLibraryCache.invalidate();
        ClientMaterialSpaceCache.clear();
        ClientOtherPreviewCache.clear();
        ClientProjectEntriesCache.clear();
        ClientProjectListCache.clear();
        ClientProjectStatusCache.clear();
        ClientTeleportPermCache.clear();
        ClientWorldIdentity.clear();
    }
}
