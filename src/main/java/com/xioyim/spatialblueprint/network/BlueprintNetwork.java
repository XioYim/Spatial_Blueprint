package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_ConfigSyncMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

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

    public static void register() {
        CHANNEL.registerMessage(id++, SetPointMessage.class,
                SetPointMessage::encode, SetPointMessage::decode, SetPointMessage::handle);
        CHANNEL.registerMessage(id++, ClearPointsMessage.class,
                ClearPointsMessage::encode, ClearPointsMessage::decode, ClearPointsMessage::handle);
        CHANNEL.registerMessage(id++, CaptureAreaMessage.class,
                CaptureAreaMessage::encode, CaptureAreaMessage::decode, CaptureAreaMessage::handle);
        CHANNEL.registerMessage(id++, PlaceBlueprintMessage.class,
                PlaceBlueprintMessage::encode, PlaceBlueprintMessage::decode, PlaceBlueprintMessage::handle);
        CHANNEL.registerMessage(id++, UpdateOffsetMessage.class,
                UpdateOffsetMessage::encode, UpdateOffsetMessage::decode, UpdateOffsetMessage::handle);
        CHANNEL.registerMessage(id++, UpdateSettingsMessage.class,
                UpdateSettingsMessage::encode, UpdateSettingsMessage::decode, UpdateSettingsMessage::handle);
        CHANNEL.registerMessage(id++, SetBlueprintNameMessage.class,
                SetBlueprintNameMessage::encode, SetBlueprintNameMessage::decode, SetBlueprintNameMessage::handle);
        CHANNEL.registerMessage(id++, S2C_OperationResult.class,
                S2C_OperationResult::encode, S2C_OperationResult::decode, S2C_OperationResult::handle);
        CHANNEL.registerMessage(id++, ForceCancelMessage.class,
                ForceCancelMessage::encode, ForceCancelMessage::decode, ForceCancelMessage::handle);
        CHANNEL.registerMessage(id++, CaptureProjectionMessage.class,
                CaptureProjectionMessage::encode, CaptureProjectionMessage::decode, CaptureProjectionMessage::handle);
        CHANNEL.registerMessage(id++, C2S_ApplyBlueprintMessage.class,
                C2S_ApplyBlueprintMessage::encode, C2S_ApplyBlueprintMessage::decode, C2S_ApplyBlueprintMessage::handle);
        CHANNEL.registerMessage(id++, C2S_EditMaterialMessage.class,
                C2S_EditMaterialMessage::encode, C2S_EditMaterialMessage::decode, C2S_EditMaterialMessage::handle);
        CHANNEL.registerMessage(id++, C2S_VoidRecaptureMessage.class,
                C2S_VoidRecaptureMessage::encode, C2S_VoidRecaptureMessage::decode, C2S_VoidRecaptureMessage::handle);
        CHANNEL.registerMessage(id++, C2S_VoidResetMessage.class,
                C2S_VoidResetMessage::encode, C2S_VoidResetMessage::decode, C2S_VoidResetMessage::handle);
        CHANNEL.registerMessage(id++, C2S_MaterialDepositMessage.class,
                C2S_MaterialDepositMessage::encode, C2S_MaterialDepositMessage::decode, C2S_MaterialDepositMessage::handle);
        CHANNEL.registerMessage(id++, C2S_SetWithdrawPropertyMessage.class,
                C2S_SetWithdrawPropertyMessage::encode, C2S_SetWithdrawPropertyMessage::decode, C2S_SetWithdrawPropertyMessage::handle);
        CHANNEL.registerMessage(id++, S2C_ConfigSyncMessage.class,
                S2C_ConfigSyncMessage::encode, S2C_ConfigSyncMessage::decode, S2C_ConfigSyncMessage::handle);
        CHANNEL.registerMessage(id++, C2S_CreateEngineeringBlueprintMessage.class,
                C2S_CreateEngineeringBlueprintMessage::encode, C2S_CreateEngineeringBlueprintMessage::decode, C2S_CreateEngineeringBlueprintMessage::handle);
        CHANNEL.registerMessage(id++, C2S_RequestBlueprintDataMessage.class,
                C2S_RequestBlueprintDataMessage::encode, C2S_RequestBlueprintDataMessage::decode, C2S_RequestBlueprintDataMessage::handle);
        CHANNEL.registerMessage(id++, S2C_BlueprintDataMessage.class,
                S2C_BlueprintDataMessage::encode, S2C_BlueprintDataMessage::decode, S2C_BlueprintDataMessage::handle);
        CHANNEL.registerMessage(id++, S2C_BlueprintChunkMessage.class,
                S2C_BlueprintChunkMessage::encode, S2C_BlueprintChunkMessage::decode, S2C_BlueprintChunkMessage::handle);
        CHANNEL.registerMessage(id++, S2C_BlueprintDataFailMessage.class,
                S2C_BlueprintDataFailMessage::encode, S2C_BlueprintDataFailMessage::decode, S2C_BlueprintDataFailMessage::handle);
        // #2: multiplayer library support
        CHANNEL.registerMessage(id++, C2S_RequestLibraryMessage.class,
                C2S_RequestLibraryMessage::encode, C2S_RequestLibraryMessage::decode, C2S_RequestLibraryMessage::handle);
        CHANNEL.registerMessage(id++, S2C_LibrarySyncMessage.class,
                S2C_LibrarySyncMessage::encode, S2C_LibrarySyncMessage::decode, S2C_LibrarySyncMessage::handle);
        CHANNEL.registerMessage(id++, C2S_RenameBlueprintMessage.class,
                C2S_RenameBlueprintMessage::encode, C2S_RenameBlueprintMessage::decode, C2S_RenameBlueprintMessage::handle);
        CHANNEL.registerMessage(id++, C2S_DeleteBlueprintMessage.class,
                C2S_DeleteBlueprintMessage::encode, C2S_DeleteBlueprintMessage::decode, C2S_DeleteBlueprintMessage::handle);
        CHANNEL.registerMessage(id++, C2S_SafeCoordMessage.class,
                C2S_SafeCoordMessage::encode, C2S_SafeCoordMessage::decode, C2S_SafeCoordMessage::handle);
        CHANNEL.registerMessage(id++, C2S_EngineeringTeleportMessage.class,
                C2S_EngineeringTeleportMessage::encode, C2S_EngineeringTeleportMessage::decode, C2S_EngineeringTeleportMessage::handle);
        CHANNEL.registerMessage(id++, C2S_VoidTeleportMessage.class,
                C2S_VoidTeleportMessage::encode, C2S_VoidTeleportMessage::decode, C2S_VoidTeleportMessage::handle);
        CHANNEL.registerMessage(id++, C2S_UploadProjectionTemplateMessage.class,
                C2S_UploadProjectionTemplateMessage::encode, C2S_UploadProjectionTemplateMessage::decode, C2S_UploadProjectionTemplateMessage::handle);
        CHANNEL.registerMessage(id++, C2S_PreviewUpdateMessage.class,
                C2S_PreviewUpdateMessage::encode, C2S_PreviewUpdateMessage::decode, C2S_PreviewUpdateMessage::handle);
        CHANNEL.registerMessage(id++, S2C_PreviewBroadcastMessage.class,
                S2C_PreviewBroadcastMessage::encode, S2C_PreviewBroadcastMessage::decode, S2C_PreviewBroadcastMessage::handle);
        CHANNEL.registerMessage(id++, S2C_OpCaptureConfirmMessage.class,
                S2C_OpCaptureConfirmMessage::encode, S2C_OpCaptureConfirmMessage::decode, S2C_OpCaptureConfirmMessage::handle);
        CHANNEL.registerMessage(id++, C2S_SaveMaterialListMessage.class,
                C2S_SaveMaterialListMessage::encode, C2S_SaveMaterialListMessage::decode, C2S_SaveMaterialListMessage::handle);
    }
}
