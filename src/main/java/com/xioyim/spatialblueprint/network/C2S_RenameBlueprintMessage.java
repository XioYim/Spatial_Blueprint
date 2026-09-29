package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: creative-mode client requests renaming a server-side blueprint.
 *
 * <p>After a successful rename the server pushes a fresh {@link S2C_LibrarySyncMessage}
 * so the client list stays in sync.</p>
 */
public class C2S_RenameBlueprintMessage {

    private final String blueprintId;
    private final String newName;
    private final String noteShort;
    private final String noteLong;

    public C2S_RenameBlueprintMessage(String blueprintId, String newName,
                                      String noteShort, String noteLong) {
        this.blueprintId = blueprintId;
        this.newName     = newName;
        this.noteShort   = noteShort == null ? "" : noteShort;
        this.noteLong    = noteLong == null ? "" : noteLong;
    }

    public static void encode(C2S_RenameBlueprintMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
        buf.writeUtf(msg.newName,    128);
        buf.writeUtf(msg.noteShort,   15);
        buf.writeUtf(msg.noteLong,   200);
    }

    public static C2S_RenameBlueprintMessage decode(FriendlyByteBuf buf) {
        return new C2S_RenameBlueprintMessage(
                buf.readUtf(64), buf.readUtf(128), buf.readUtf(15), buf.readUtf(200));
    }

    public static void handle(C2S_RenameBlueprintMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !player.isCreative()) return;

            String name = BlueprintValidator.sanitizeName(msg.newName);
            if (name.isEmpty()) return;

            // ── 归属校验：主人或 OP 才能改名 ──────────────────────────────
            // 上面的 isCreative() 只说明「这是蓝图库管理场景」，不代表有权动这一份。
            // 开放创造的服上，少了这道校验等于任何人都能改任何人的蓝图名字和备注。
            if (!BlueprintValidator.canManageBlueprint(player, msg.blueprintId)) {
                BlueprintNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new S2C_OperationResult(false, "spatial_blueprint.error.blueprint_not_owner"));
                return;
            }

            BlueprintStorage.rename(msg.blueprintId, name);
            BlueprintStorage.updateNotes(msg.blueprintId, msg.noteShort.trim(), msg.noteLong.trim());

            // Push the updated list back so the client refreshes immediately.
            // H2：按下发口径过滤后再推，避免改名一次就把全量列表泄一次。
            List<BlueprintStorage.BlueprintSummary> updated = C2S_RequestLibraryMessage.buildFilteredSummaries(player);
            BlueprintNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new S2C_LibrarySyncMessage(updated));
        });
        ctx.get().setPacketHandled(true);
    }
}
