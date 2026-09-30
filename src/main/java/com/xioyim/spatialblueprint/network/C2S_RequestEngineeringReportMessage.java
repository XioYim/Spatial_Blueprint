package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S：请求一份工程蓝图（源文件）的内容检测报告。
 *
 * <p>放置界面 ⌘ 进入创建页之前先看报告：服务端 load 源文件 → {@code scanBlueprintData}
 * → 回 {@code S2C_UploadReportMessage}（走 {@code UploadReportScreen} 的加载态 → 结果态）。</p>
 *
 * <p>只在客户端展示，不进上传暂存（{@code PendingUploadRegistry}），也不记上传日志。
 * 扫描必须在服务端做：非法方块表是服务端配置，客户端本地 toml 在多人服上可能不同。</p>
 */
public class C2S_RequestEngineeringReportMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_RequestEngineeringReportMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_request_engineering_report_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestEngineeringReportMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_RequestEngineeringReportMessage::encode,
                    C2S_RequestEngineeringReportMessage::decode);

    private final String sourceId;

    public C2S_RequestEngineeringReportMessage(String sourceId) {
        this.sourceId = sourceId == null ? "" : sourceId;
    }

    @Override
    public CustomPacketPayload.Type<C2S_RequestEngineeringReportMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_RequestEngineeringReportMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sourceId, 64);
    }

    public static C2S_RequestEngineeringReportMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestEngineeringReportMessage(buf.readUtf(64));
    }

    public static void handle(C2S_RequestEngineeringReportMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_RequestEngineeringReportMessage msg, ServerPlayer player) {
        if (player == null || !player.isCreative()) return;

        BlueprintData data = BlueprintStorage.load(msg.sourceId);
        if (data == null) return;

        BlueprintSanitizer.UploadScan scan = BlueprintSanitizer.scanBlueprintData(
                data, BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                BlueprintConfig.MAX_NBT_DEPTH.get());
        BlueprintSanitizer.NbtBudgetResult budget = BlueprintSanitizer.checkNbtBudget(
                data, BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                BlueprintConfig.MAX_TOTAL_NBT_BYTES.get());

        int totalBlocks = data.normalBlocks.size() + data.dependentBlocks.size()
                + data.lateBlocks.size();
        BlueprintNetwork.sendToPlayer(player,
                new com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage(
                        data.name, totalBlocks, player.hasPermissions(2),
                        data.size.getX(), data.size.getY(), data.size.getZ(),
                        0, budget.totalBytes(), budget.maxSingle(),
                        0,
                        scan.illegalBlocks(), scan.dangerousBlocks(), scan.oversizedNbt(),
                        scan.deepNbt(), scan.droppedEntities(), scan.conversions(), scan.freeBlocks(),
                        scan.illegalTotal(), scan.dangerousTotal(), scan.oversizedTotal(),
                        scan.deepNbtTotal(), scan.droppedTotal(), scan.conversionsTotal(), scan.freeTotal(),
                        "engineering", BlueprintConfig.getMaxVolumeFor("engineering"),
                        BlueprintConfig.MAX_BLOCK_ENTITY_NBT_BYTES.get(),
                        BlueprintConfig.MAX_TOTAL_NBT_BYTES.get()));
    }
}
