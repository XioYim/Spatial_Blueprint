package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.SpatialBlueprintRegistry;
import com.xioyim.spatialblueprint.client.renderer.RenderBlockRenderer;
import com.xioyim.spatialblueprint.gui.SpatialBlueprintConfigScreen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端专属的初始化逻辑，从主 mod 类里拆出来。
 *
 * <p><b>为什么必须单独成类：</b>注册配置界面用的
 * {@code (mc, parent) -> new SpatialBlueprintConfigScreen(parent)} 这个 lambda，
 * 会被编译成所在类的一个 synthetic 方法，其签名里直接带
 * {@code net.minecraft.client.gui.screens.Screen}。JVM 加载该类、构建方法表时就要解析
 * 这个类型 —— 于是专用服务端刚加载主 mod 类就会撞上 Forge 的
 * {@code RuntimeDistCleaner}：{@code Attempted to load class .../Screen for invalid dist
 * DEDICATED_SERVER}，mod 构造失败、服务端直接起不来。</p>
 *
 * <p>把这些引用挪到本类后，主类里只剩一次通过 {@code DistExecutor} 的间接调用，
 * 签名中不含任何客户端类型，服务端就不会去加载它。</p>
 *
 * <p>同理，运行时的 {@code if (FMLEnvironment.dist == Dist.CLIENT)} 判断
 * <b>阻止不了类加载</b>，只能用 {@code DistExecutor} 或独立类。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientModSetup {

    private ClientModSetup() {}

    public static void init(FMLClientSetupEvent event) {
        BlockEntityRenderers.register(
                SpatialBlueprintRegistry.RENDER_BLOCK_ENTITY.get(), RenderBlockRenderer::new);
        ClientPreviewSettings.load();
        event.enqueueWork(() ->
                ModLoadingContext.get().registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> {
                            // 配置页面读写的是服务端配置，非 OP 打开只会看到读本地 toml、
                            // 保存又被拒的假页面 —— 这里直接不让它出现。
                            if (!com.xioyim.spatialblueprint.client.ClientConfigPermCache.canOpen(mc)) {
                                if (mc.player != null) {
                                    mc.player.displayClientMessage(Component.translatable(
                                            com.xioyim.spatialblueprint.client.ClientConfigPermCache.deniedKey()),
                                            false);
                                }
                                return parent;
                            }
                            return new SpatialBlueprintConfigScreen(parent);
                        }))
        );
    }
}
