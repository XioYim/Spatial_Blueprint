package com.xioyim.spatialblueprint.network;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.GeneratedSubstitutionsConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: 客户端请求服务端热加载所有 spatial_blueprint 配置（仅 OP 权限）。
 * 由配置主页面"保存并关闭"按钮触发。
 */
public class C2S_ReloadConfigMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_ReloadConfigMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_reload_config_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ReloadConfigMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_ReloadConfigMessage::encode, C2S_ReloadConfigMessage::decode);

    @Override
    public CustomPacketPayload.Type<C2S_ReloadConfigMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_ReloadConfigMessage msg, FriendlyByteBuf buf) {}

    public static C2S_ReloadConfigMessage decode(FriendlyByteBuf buf) {
        return new C2S_ReloadConfigMessage();
    }

    public static void handle(C2S_ReloadConfigMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_ReloadConfigMessage msg, ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) return;
        doReload();
        player.sendSystemMessage(Component.translatable("spatial_blueprint.command.reload.success"));
    }

    /** 从磁盘重新读取所有 spatial_blueprint 配置，并清除各级缓存。 */
    public static void doReload() {
        try {
            // FML 4：ConfigTracker.INSTANCE 已不在公开 API（1.20.1 口径），改走 ModConfigs.getModConfigs。
            for (ModConfig mc : ModConfigs.getModConfigs(SpatialBlueprintMod.MODID)) {
                if (mc.getLoadedConfig() == null) continue;
                // 第一步：NightConfig 从磁盘重读到独立的 CommentedFileConfig
                CommentedFileConfig fc = CommentedFileConfig.of(mc.getFullPath());
                fc.load();
                // 盘上值不合法就按内嵌默认值校正（只改内存副本，不回写磁盘）
                if (!mc.getSpec().isCorrect(fc)) {
                    mc.getSpec().correct(fc);
                }
                // 写回 FML 持有的配置对象：REPLACE 语义（FML 正规 reload 是整份替换 LoadedConfig，
                // 手动路径原地重建 —— 不清的话盘上删掉的键会残留，而正确性校验只保证「该有的在」）
                var target = mc.getLoadedConfig().config();
                target.clear();
                target.putAll(fc);
                fc.close();
                // 第二步（关键）：让 spec 从重建后的 config 重新接受值表，
                // 再清 ModConfigSpec.ConfigValue 的 cachedValue —— 只读盘不清缓存的话
                // .get() 仍然返回旧值，这正是 materialSpaceMaxCount 改了却不生效的原因。
                if (mc.getSpec() instanceof ModConfigSpec spec) {
                    spec.acceptConfig(mc.getLoadedConfig());
                    spec.afterReload();
                }
            }
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.warn("[SpatialBlueprint] Config disk reload failed: {}", e.getMessage());
        }
        // 第三步：清除本 mod 自己的派生缓存（GUI 通过 .set() 改内存值时也需要此步）
        BlueprintConfig.clearCache();
        GeneratedSubstitutionsConfig.invalidateCache();
    }
}
