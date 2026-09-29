package com.xioyim.spatialblueprint.network;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: 客户端请求服务端热加载所有 spatial_blueprint 配置（仅 OP 权限）。
 * 由配置主页面"保存并关闭"按钮触发。
 */
public class C2S_ReloadConfigMessage {

    public static void encode(C2S_ReloadConfigMessage msg, FriendlyByteBuf buf) {}

    public static C2S_ReloadConfigMessage decode(FriendlyByteBuf buf) {
        return new C2S_ReloadConfigMessage();
    }

    public static void handle(C2S_ReloadConfigMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.hasPermissions(2)) return;
            doReload();
            player.sendSystemMessage(Component.translatable("spatial_blueprint.command.reload.success"));
        });
        ctx.get().setPacketHandled(true);
    }

    /** 从磁盘重新读取所有 spatial_blueprint 配置，并清除各级缓存。 */
    public static void doReload() {
        try {
            ConfigTracker.INSTANCE.configSets().values().forEach(set ->
                    set.stream()
                       .filter(mc -> SpatialBlueprintMod.MODID.equals(mc.getModId()))
                       .forEach(mc -> {
                           // 第一步：NightConfig 从磁盘重读到 childConfig
                           if (mc.getConfigData() instanceof FileConfig fc) {
                               fc.load();
                           }
                           // 第二步（关键）：清除 ForgeConfigSpec.ConfigValue 的 cachedValue。
                           // ConfigValue.get() 首次调用后会把值缓存下来，只读盘不清缓存的话
                           // .get() 仍然返回旧值 —— 这正是 materialSpaceMaxCount 改了却不生效的原因。
                           // Forge 正常的 reload 流程会调此方法，手动读盘必须自己补上。
                           if (mc.getSpec() instanceof ForgeConfigSpec spec) {
                               spec.afterReload();
                           }
                       }));
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Config disk reload failed: {}", e.getMessage());
        }
        // 第三步：清除本 mod 自己的派生缓存（GUI 通过 .set() 改内存值时也需要此步）
        BlueprintConfig.clearCache();
        GeneratedSubstitutionsConfig.invalidateCache();
    }
}
