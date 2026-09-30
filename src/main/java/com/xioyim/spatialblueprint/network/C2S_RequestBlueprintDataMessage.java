package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C2S: Client requests the full {@link BlueprintData} for a given blueprintId.
 *
 * <p>The server loads the data from the file store and replies with
 * {@link S2C_BlueprintDataMessage}（或分片）. Rate-limited to one request per player per
 * {@value #COOLDOWN_MS} ms (#7).</p>
 */
public class C2S_RequestBlueprintDataMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_RequestBlueprintDataMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_request_blueprint_data_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestBlueprintDataMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_RequestBlueprintDataMessage::encode, C2S_RequestBlueprintDataMessage::decode);

    /** Minimum milliseconds between accepted requests from the same player. */
    private static final long COOLDOWN_MS = 500L;

    /** Tracks the last accepted request timestamp per player UUID. */
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private final String blueprintId;

    public C2S_RequestBlueprintDataMessage(String blueprintId) {
        this.blueprintId = blueprintId;
    }

    @Override
    public CustomPacketPayload.Type<C2S_RequestBlueprintDataMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_RequestBlueprintDataMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_RequestBlueprintDataMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestBlueprintDataMessage(buf.readUtf(64));
    }

    public static void handle(C2S_RequestBlueprintDataMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_RequestBlueprintDataMessage msg, ServerPlayer player) {
        if (player == null || msg.blueprintId.isEmpty()) return;

        // #7: rate-limit — reject if the same player sent a request too recently
        long now = System.currentTimeMillis();
        boolean[] pass = {false};
        LAST_REQUEST.compute(player.getUUID(), (id, last) -> {
            if (last == null || now - last >= COOLDOWN_MS) {
                pass[0] = true;
                return now;
            }
            return last;
        });
        // Rate-limited: send fail so client doesn't time out silently
        if (!pass[0]) {
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_BlueprintDataFailMessage(msg.blueprintId,
                            "spatial_blueprint.error.request_rate_limited"));
            return;
        }

        // H2：归属下沉服务端 —— 复用 canManageBlueprint（主人或 OP），无主只有 OP 能下。
        // 不满足直接按不存在回，避免向改包者确认 id 是否存在。
        if (!BlueprintValidator.canManageBlueprint(player, msg.blueprintId)) {
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_BlueprintDataFailMessage(msg.blueprintId,
                            "spatial_blueprint.error.blueprint_not_found"));
            return;
        }

        try {
            BlueprintData data = BlueprintStorage.load(msg.blueprintId);
            if (data == null) {
                BlueprintNetwork.sendToPlayer(player,
                        new S2C_BlueprintDataFailMessage(msg.blueprintId,
                                "spatial_blueprint.error.blueprint_not_found"));
                return;
            }
            BlueprintNetwork.sendBlueprintTo(player, msg.blueprintId, data);
        } catch (Exception e) {
            SpatialBlueprintMod.LOGGER.error(
                    "[BlueprintData] Failed to load blueprint {}: {}", msg.blueprintId, e.getMessage());
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_BlueprintDataFailMessage(msg.blueprintId,
                            "spatial_blueprint.error.blueprint_load_error"));
        }
    }
}
