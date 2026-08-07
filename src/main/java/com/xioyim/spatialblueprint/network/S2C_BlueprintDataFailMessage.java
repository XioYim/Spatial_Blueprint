package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: Notifies the client that a blueprint data request failed (file not found, access denied, etc.).
 * The client uses this to transition out of the "downloading" state and show an error with retry.
 */
public class S2C_BlueprintDataFailMessage {

    private final String blueprintId;
    private final String reason;

    public S2C_BlueprintDataFailMessage(String blueprintId, String reason) {
        this.blueprintId = blueprintId;
        this.reason      = reason;
    }

    public static void encode(S2C_BlueprintDataFailMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.reason,      128);
    }

    public static S2C_BlueprintDataFailMessage decode(FriendlyByteBuf buf) {
        String id     = buf.readUtf(64);
        String reason = buf.readUtf(128);
        return new S2C_BlueprintDataFailMessage(id, reason);
    }

    public static void handle(S2C_BlueprintDataFailMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientBlueprintCache.markFailed(msg.blueprintId, msg.reason);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
