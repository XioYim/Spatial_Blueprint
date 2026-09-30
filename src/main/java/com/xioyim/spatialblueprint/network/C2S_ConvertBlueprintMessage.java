package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * C2S: OP client requests converting a blueprint between Spatial ({@code "create"}) and
 * Projection ({@code "project"}) categories.
 *
 * <p>Only the {@code category} field changes; all other blueprint data (name, creator,
 * timestamps, notes, etc.) is preserved. After a successful conversion the server pushes a
 * fresh {@link S2C_LibrarySyncMessage} so the client list stays in sync.</p>
 */
public class C2S_ConvertBlueprintMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_ConvertBlueprintMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_convert_blueprint_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ConvertBlueprintMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_ConvertBlueprintMessage::encode, C2S_ConvertBlueprintMessage::decode);

    private final String blueprintId;

    public C2S_ConvertBlueprintMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    @Override
    public CustomPacketPayload.Type<C2S_ConvertBlueprintMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_ConvertBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_ConvertBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_ConvertBlueprintMessage(buf.readUtf(64));
    }

    public static void handle(C2S_ConvertBlueprintMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_ConvertBlueprintMessage msg, ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) return;

        boolean ok = BlueprintStorage.convertCategory(msg.blueprintId);
        if (ok) {
            // H2：OP 也走过滤口径（OP 本来就是全量，结果一致）。
            List<BlueprintStorage.BlueprintSummary> updated = C2S_RequestLibraryMessage.buildFilteredSummaries(player);
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_LibrarySyncMessage(updated));
        }
    }
}
