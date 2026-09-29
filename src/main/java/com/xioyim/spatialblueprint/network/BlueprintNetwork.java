package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_BlueprintMaterialsMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigPermMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigValuesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenConfigMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpenVoidScreenMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.network.s2c.S2C_PlacementProgressMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectEntriesMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_ProjectStatusMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_UploadReportMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class BlueprintNetwork {

    private static String getProtocolVersion() {
        return ModList.get()
                .getModContainerById(SpatialBlueprintMod.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("1.0.0");
    }

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SpatialBlueprintMod.MODID, "main"),
            BlueprintNetwork::getProtocolVersion,
            v -> v.equals(getProtocolVersion()),
            v -> v.equals(getProtocolVersion())
    );

    private static int id = 0;

    /**
     * Server-side helper: always chunk the blueprint payload into
     * {@link S2C_BlueprintChunkMessage} frames. One code path, no size branch.
     * Refuses (log + no send) when chunk count would exceed
     * {@link S2C_BlueprintChunkMessage#MAX_TOTAL_CHUNKS}.
     */
    public static void sendBlueprintTo(ServerPlayer player, String blueprintId,
                                       com.xioyim.spatialblueprint.util.BlueprintData data) {
        if (player == null || blueprintId == null || blueprintId.isEmpty() || data == null) return;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(data.serialize(), baos);
            byte[] full = baos.toByteArray();
            int chunkSize = S2C_BlueprintChunkMessage.CHUNK_SIZE;
            int totalChunks = (full.length + chunkSize - 1) / chunkSize;
            if (totalChunks > S2C_BlueprintChunkMessage.MAX_TOTAL_CHUNKS) {
                SpatialBlueprintMod.LOGGER.warn(
                        "[BlueprintNetwork] Refusing to send blueprint {}: {} chunks > limit {} ({} bytes)",
                        blueprintId, totalChunks, S2C_BlueprintChunkMessage.MAX_TOTAL_CHUNKS, full.length);
                return;
            }
            for (int i = 0; i < totalChunks; i++) {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, full.length);
                byte[] chunk = java.util.Arrays.copyOfRange(full, start, end);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new S2C_BlueprintChunkMessage(blueprintId, i, totalChunks, chunk));
            }
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintNetwork] Failed to send blueprint {}: {}", blueprintId, e.getMessage());
        }
    }

    // ── 注册辅助：每条消息都必须绑定方向 ──────────────────────────────────────
    //
    // 以前全部走 5 参的 registerMessage 重载，方向是 Optional.empty()。
    // Forge 的 NetworkHooks.validatePacketDirection 写着：
    //
    //     if (packetDirection != expectedDirection.orElse(packetDirection)) { 断开连接 }
    //
    // —— Optional.empty() 时 orElse 直接吐回实际方向，这个判断恒为 false，等于**永不校验**。
    // 于是改包客户端可以把任意 S2C 消息反着发给服务端，服务端照样进 handler：
    // 那些把活委托给 @OnlyIn(Dist.CLIENT) 方法的（S2C_BlueprintChunkMessage、
    // S2C_OpenConfigMessage 等），在专用服务端上那个方法已被 RuntimeDistCleaner 剥离，
    // 调用即 NoSuchMethodError —— 任意玩家都能远程往服务端注异常。
    //
    // 绑定方向后同一段代码会先断开那条连接再抛 IllegalStateException，
    // 恶意客户端被踢，服务端本身不受影响。
    //
    // ⚠ 注册顺序 = 线上的消息序号，两端必须逐条一致。**只能在末尾追加，不许插队或删除**；
    //   动了顺序就必须 bump mod_version 并同时更新双端。

    /** 客户端 → 服务端。 */
    private static <MSG> void c2s(Class<MSG> type,
                                  BiConsumer<MSG, FriendlyByteBuf> encoder,
                                  Function<FriendlyByteBuf, MSG> decoder,
                                  BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(id++, type, encoder, decoder, handler,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /** 服务端 → 客户端。 */
    private static <MSG> void s2c(Class<MSG> type,
                                  BiConsumer<MSG, FriendlyByteBuf> encoder,
                                  Function<FriendlyByteBuf, MSG> decoder,
                                  BiConsumer<MSG, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.registerMessage(id++, type, encoder, decoder, handler,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void register() {
        // 顺序与绑定方向前逐条一致，序号 0 起。
        c2s(SetPointMessage.class,
                SetPointMessage::encode, SetPointMessage::decode, SetPointMessage::handle);
        c2s(ClearPointsMessage.class,
                ClearPointsMessage::encode, ClearPointsMessage::decode, ClearPointsMessage::handle);
        c2s(CaptureAreaMessage.class,
                CaptureAreaMessage::encode, CaptureAreaMessage::decode, CaptureAreaMessage::handle);
        c2s(PlaceBlueprintMessage.class,
                PlaceBlueprintMessage::encode, PlaceBlueprintMessage::decode, PlaceBlueprintMessage::handle);
        c2s(UpdateOffsetMessage.class,
                UpdateOffsetMessage::encode, UpdateOffsetMessage::decode, UpdateOffsetMessage::handle);
        c2s(UpdateSettingsMessage.class,
                UpdateSettingsMessage::encode, UpdateSettingsMessage::decode, UpdateSettingsMessage::handle);
        c2s(SetBlueprintNameMessage.class,
                SetBlueprintNameMessage::encode, SetBlueprintNameMessage::decode, SetBlueprintNameMessage::handle);
        s2c(S2C_OperationResult.class,
                S2C_OperationResult::encode, S2C_OperationResult::decode, S2C_OperationResult::handle);
        c2s(CaptureProjectionMessage.class,
                CaptureProjectionMessage::encode, CaptureProjectionMessage::decode, CaptureProjectionMessage::handle);
        c2s(C2S_ApplyBlueprintMessage.class,
                C2S_ApplyBlueprintMessage::encode, C2S_ApplyBlueprintMessage::decode, C2S_ApplyBlueprintMessage::handle);
        s2c(S2C_UploadReportMessage.class,
                S2C_UploadReportMessage::encode, S2C_UploadReportMessage::decode, S2C_UploadReportMessage::handle);
        c2s(C2S_ConfirmUploadMessage.class,
                C2S_ConfirmUploadMessage::encode, C2S_ConfirmUploadMessage::decode, C2S_ConfirmUploadMessage::handle);
        c2s(C2S_DiscardOrphanBagMessage.class,
                C2S_DiscardOrphanBagMessage::encode, C2S_DiscardOrphanBagMessage::decode,
                C2S_DiscardOrphanBagMessage::handle);
        s2c(S2C_TeleportPermMessage.class,
                S2C_TeleportPermMessage::encode, S2C_TeleportPermMessage::decode,
                S2C_TeleportPermMessage::handle);
        c2s(C2S_DestroyForceBlueprintMessage.class,
                C2S_DestroyForceBlueprintMessage::encode, C2S_DestroyForceBlueprintMessage::decode,
                C2S_DestroyForceBlueprintMessage::handle);
        c2s(C2S_VoidRecaptureMessage.class,
                C2S_VoidRecaptureMessage::encode, C2S_VoidRecaptureMessage::decode, C2S_VoidRecaptureMessage::handle);
        c2s(C2S_VoidResetMessage.class,
                C2S_VoidResetMessage::encode, C2S_VoidResetMessage::decode, C2S_VoidResetMessage::handle);
        c2s(C2S_MaterialDepositMessage.class,
                C2S_MaterialDepositMessage::encode, C2S_MaterialDepositMessage::decode, C2S_MaterialDepositMessage::handle);
        c2s(C2S_SetWithdrawPropertyMessage.class,
                C2S_SetWithdrawPropertyMessage::encode, C2S_SetWithdrawPropertyMessage::decode, C2S_SetWithdrawPropertyMessage::handle);
        s2c(S2C_ConfigSyncMessage.class,
                S2C_ConfigSyncMessage::encode, S2C_ConfigSyncMessage::decode, S2C_ConfigSyncMessage::handle);
        s2c(S2C_ConfigPermMessage.class,
                S2C_ConfigPermMessage::encode, S2C_ConfigPermMessage::decode, S2C_ConfigPermMessage::handle);
        c2s(C2S_CreateEngineeringBlueprintMessage.class,
                C2S_CreateEngineeringBlueprintMessage::encode, C2S_CreateEngineeringBlueprintMessage::decode, C2S_CreateEngineeringBlueprintMessage::handle);
        c2s(C2S_RequestBlueprintDataMessage.class,
                C2S_RequestBlueprintDataMessage::encode, C2S_RequestBlueprintDataMessage::decode, C2S_RequestBlueprintDataMessage::handle);
        s2c(S2C_BlueprintDataMessage.class,
                S2C_BlueprintDataMessage::encode, S2C_BlueprintDataMessage::decode, S2C_BlueprintDataMessage::handle);
        s2c(S2C_BlueprintChunkMessage.class,
                S2C_BlueprintChunkMessage::encode, S2C_BlueprintChunkMessage::decode, S2C_BlueprintChunkMessage::handle);
        s2c(S2C_BlueprintDataFailMessage.class,
                S2C_BlueprintDataFailMessage::encode, S2C_BlueprintDataFailMessage::decode, S2C_BlueprintDataFailMessage::handle);
        // #2: multiplayer library support
        c2s(C2S_RequestLibraryMessage.class,
                C2S_RequestLibraryMessage::encode, C2S_RequestLibraryMessage::decode, C2S_RequestLibraryMessage::handle);
        s2c(S2C_LibrarySyncMessage.class,
                S2C_LibrarySyncMessage::encode, S2C_LibrarySyncMessage::decode, S2C_LibrarySyncMessage::handle);
        c2s(C2S_RenameBlueprintMessage.class,
                C2S_RenameBlueprintMessage::encode, C2S_RenameBlueprintMessage::decode, C2S_RenameBlueprintMessage::handle);
        c2s(C2S_DeleteBlueprintMessage.class,
                C2S_DeleteBlueprintMessage::encode, C2S_DeleteBlueprintMessage::decode, C2S_DeleteBlueprintMessage::handle);
        c2s(C2S_EngineeringTeleportMessage.class,
                C2S_EngineeringTeleportMessage::encode, C2S_EngineeringTeleportMessage::decode, C2S_EngineeringTeleportMessage::handle);
        c2s(C2S_VoidTeleportMessage.class,
                C2S_VoidTeleportMessage::encode, C2S_VoidTeleportMessage::decode, C2S_VoidTeleportMessage::handle);
        c2s(C2S_UploadProjectionTemplateMessage.class,
                C2S_UploadProjectionTemplateMessage::encode, C2S_UploadProjectionTemplateMessage::decode, C2S_UploadProjectionTemplateMessage::handle);
        c2s(C2S_PreviewUpdateMessage.class,
                C2S_PreviewUpdateMessage::encode, C2S_PreviewUpdateMessage::decode, C2S_PreviewUpdateMessage::handle);
        s2c(S2C_PreviewBroadcastMessage.class,
                S2C_PreviewBroadcastMessage::encode, S2C_PreviewBroadcastMessage::decode, S2C_PreviewBroadcastMessage::handle);
        s2c(S2C_OpCaptureConfirmMessage.class,
                S2C_OpCaptureConfirmMessage::encode, S2C_OpCaptureConfirmMessage::decode, S2C_OpCaptureConfirmMessage::handle);
        c2s(C2S_RequestIllegalBlocksMessage.class,
                C2S_RequestIllegalBlocksMessage::encode, C2S_RequestIllegalBlocksMessage::decode, C2S_RequestIllegalBlocksMessage::handle);
        s2c(S2C_IllegalBlocksListMessage.class,
                S2C_IllegalBlocksListMessage::encode, S2C_IllegalBlocksListMessage::decode, S2C_IllegalBlocksListMessage::handle);
        c2s(C2S_SaveMaterialListMessage.class,
                C2S_SaveMaterialListMessage::encode, C2S_SaveMaterialListMessage::decode, C2S_SaveMaterialListMessage::handle);
        s2c(S2C_PlacementProgressMessage.class,
                S2C_PlacementProgressMessage::encode, S2C_PlacementProgressMessage::decode, S2C_PlacementProgressMessage::handle);
        s2c(S2C_OpenConfigMessage.class,
                S2C_OpenConfigMessage::encode, S2C_OpenConfigMessage::decode, S2C_OpenConfigMessage::handle);
        c2s(C2S_ReloadConfigMessage.class,
                C2S_ReloadConfigMessage::encode, C2S_ReloadConfigMessage::decode, C2S_ReloadConfigMessage::handle);
        c2s(C2S_ConvertBlueprintMessage.class,
                C2S_ConvertBlueprintMessage::encode, C2S_ConvertBlueprintMessage::decode, C2S_ConvertBlueprintMessage::handle);
        s2c(S2C_MaterialSpaceSyncMessage.class,
                S2C_MaterialSpaceSyncMessage::encode, S2C_MaterialSpaceSyncMessage::decode, S2C_MaterialSpaceSyncMessage::handle);
        c2s(C2S_RequestMaterialSpaceMessage.class,
                C2S_RequestMaterialSpaceMessage::encode, C2S_RequestMaterialSpaceMessage::decode, C2S_RequestMaterialSpaceMessage::handle);
        c2s(C2S_RequestVoidBagsMessage.class,
                C2S_RequestVoidBagsMessage::encode, C2S_RequestVoidBagsMessage::decode, C2S_RequestVoidBagsMessage::handle);
        c2s(C2S_DiscardInvalidBlueprintMessage.class,
                C2S_DiscardInvalidBlueprintMessage::encode, C2S_DiscardInvalidBlueprintMessage::decode,
                C2S_DiscardInvalidBlueprintMessage::handle);
        s2c(S2C_VoidBagSyncMessage.class,
                S2C_VoidBagSyncMessage::encode, S2C_VoidBagSyncMessage::decode, S2C_VoidBagSyncMessage::handle);
        s2c(S2C_OpenVoidScreenMessage.class,
                S2C_OpenVoidScreenMessage::encode, S2C_OpenVoidScreenMessage::decode,
                S2C_OpenVoidScreenMessage::handle);
        s2c(S2C_WorldIdentityMessage.class,
                S2C_WorldIdentityMessage::encode, S2C_WorldIdentityMessage::decode,
                S2C_WorldIdentityMessage::handle);
        c2s(C2S_RequestConfigMessage.class,
                C2S_RequestConfigMessage::encode, C2S_RequestConfigMessage::decode,
                C2S_RequestConfigMessage::handle);
        c2s(C2S_ApplyConfigMessage.class,
                C2S_ApplyConfigMessage::encode, C2S_ApplyConfigMessage::decode,
                C2S_ApplyConfigMessage::handle);
        s2c(S2C_ConfigValuesMessage.class,
                S2C_ConfigValuesMessage::encode, S2C_ConfigValuesMessage::decode,
                S2C_ConfigValuesMessage::handle);
        s2c(S2C_CaptureCooldownMessage.class,
                S2C_CaptureCooldownMessage::encode, S2C_CaptureCooldownMessage::decode,
                S2C_CaptureCooldownMessage::handle);
        c2s(C2S_DiscardForeignBlueprintMessage.class,
                C2S_DiscardForeignBlueprintMessage::encode, C2S_DiscardForeignBlueprintMessage::decode,
                C2S_DiscardForeignBlueprintMessage::handle);
        c2s(C2S_RequestProjectEntriesMessage.class,
                C2S_RequestProjectEntriesMessage::encode, C2S_RequestProjectEntriesMessage::decode,
                C2S_RequestProjectEntriesMessage::handle);
        s2c(S2C_ProjectEntriesMessage.class,
                S2C_ProjectEntriesMessage::encode, S2C_ProjectEntriesMessage::decode,
                S2C_ProjectEntriesMessage::handle);
        c2s(C2S_CreateSubProjectMessage.class,
                C2S_CreateSubProjectMessage::encode, C2S_CreateSubProjectMessage::decode,
                C2S_CreateSubProjectMessage::handle);
        c2s(C2S_RequestProjectStatusMessage.class,
                C2S_RequestProjectStatusMessage::encode, C2S_RequestProjectStatusMessage::decode,
                C2S_RequestProjectStatusMessage::handle);
        s2c(S2C_ProjectStatusMessage.class,
                S2C_ProjectStatusMessage::encode, S2C_ProjectStatusMessage::decode,
                S2C_ProjectStatusMessage::handle);
        c2s(C2S_ClaimSubProjectMessage.class,
                C2S_ClaimSubProjectMessage::encode, C2S_ClaimSubProjectMessage::decode,
                C2S_ClaimSubProjectMessage::handle);
        c2s(C2S_RequestBlueprintMaterialsMessage.class,
                C2S_RequestBlueprintMaterialsMessage::encode, C2S_RequestBlueprintMaterialsMessage::decode,
                C2S_RequestBlueprintMaterialsMessage::handle);
        s2c(S2C_BlueprintMaterialsMessage.class,
                S2C_BlueprintMaterialsMessage::encode, S2C_BlueprintMaterialsMessage::decode,
                S2C_BlueprintMaterialsMessage::handle);
        // 2.7.0：项目蓝图重做，创建入口改为蓝图库 ✚（旧的带选区版本已删，序号不同）
        c2s(C2S_CreateProjectMessage.class,
                C2S_CreateProjectMessage::encode, C2S_CreateProjectMessage::decode,
                C2S_CreateProjectMessage::handle);
        // 2.7.0：导入项目界面（◈）—— 按 tab 拉清单 / 导入或规划拿物品
        c2s(C2S_RequestProjectListMessage.class,
                C2S_RequestProjectListMessage::encode, C2S_RequestProjectListMessage::decode,
                C2S_RequestProjectListMessage::handle);
        s2c(S2C_ProjectListMessage.class,
                S2C_ProjectListMessage::encode, S2C_ProjectListMessage::decode,
                S2C_ProjectListMessage::handle);
        c2s(C2S_TakeProjectMessage.class,
                C2S_TakeProjectMessage::encode, C2S_TakeProjectMessage::decode,
                C2S_TakeProjectMessage::handle);
        // 2.7.2：项目界面「规划项目」→ 设定范围（临时两点 / 保存）
        c2s(C2S_ProjectRangePointMessage.class,
                C2S_ProjectRangePointMessage::encode, C2S_ProjectRangePointMessage::decode,
                C2S_ProjectRangePointMessage::handle);
        c2s(C2S_SaveProjectRangeMessage.class,
                C2S_SaveProjectRangeMessage::encode, C2S_SaveProjectRangeMessage::decode,
                C2S_SaveProjectRangeMessage::handle);
        c2s(C2S_DeleteSubProjectMessage.class,
                C2S_DeleteSubProjectMessage::encode, C2S_DeleteSubProjectMessage::decode,
                C2S_DeleteSubProjectMessage::handle);
        // 2.7.8：规划模式界面 [提交修改] —— 整份新顺序重排子工程（仅 OP、无任务集占用）
        c2s(C2S_SubmitProjectOrderMessage.class,
                C2S_SubmitProjectOrderMessage::encode, C2S_SubmitProjectOrderMessage::decode,
                C2S_SubmitProjectOrderMessage::handle);
        // 2.7.10（SB-008）：规划模式 [提交修改] 整单 —— 删除 + 新顺序 + 材料清单
        c2s(C2S_SubmitProjectChangesMessage.class,
                C2S_SubmitProjectChangesMessage::encode, C2S_SubmitProjectChangesMessage::decode,
                C2S_SubmitProjectChangesMessage::handle);
        // 2.7.23：导入项目界面 [删除项目]（仅 OP、无任务集占用；成功后回推同 tab 清单）
        c2s(C2S_DeleteProjectMessage.class,
                C2S_DeleteProjectMessage::encode, C2S_DeleteProjectMessage::decode,
                C2S_DeleteProjectMessage::handle);
        c2s(C2S_RequestEngineeringReportMessage.class,
                C2S_RequestEngineeringReportMessage::encode, C2S_RequestEngineeringReportMessage::decode, C2S_RequestEngineeringReportMessage::handle);
        // 2.7.33：工程蓝图安全传送坐标回归（只有坐标，无放行开关；蓝图库工程 Tab [TP]）
        c2s(C2S_SafeCoordMessage.class,
                C2S_SafeCoordMessage::encode, C2S_SafeCoordMessage::decode, C2S_SafeCoordMessage::handle);
        // 2.7.34（SB-011）：奖励蓝图材料 ✎ 提取 / ✖ 清空（服务端生成 / 写盘后回推）
        c2s(C2S_RewardMaterialsMessage.class,
                C2S_RewardMaterialsMessage::encode, C2S_RewardMaterialsMessage::decode, C2S_RewardMaterialsMessage::handle);
        // 2.7.36（SB-013）：大文件上传分片 —— Begin 声明 + Chunk 数据块（组完走旧单包全流程）
        c2s(C2S_UploadBeginMessage.class,
                C2S_UploadBeginMessage::encode, C2S_UploadBeginMessage::decode, C2S_UploadBeginMessage::handle);
        c2s(C2S_UploadChunkMessage.class,
                C2S_UploadChunkMessage::encode, C2S_UploadChunkMessage::decode, C2S_UploadChunkMessage::handle);
    }
}
