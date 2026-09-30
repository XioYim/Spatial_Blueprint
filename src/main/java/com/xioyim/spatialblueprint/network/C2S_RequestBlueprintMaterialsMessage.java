package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.network.s2c.S2C_BlueprintMaterialsMessage;
import com.xioyim.spatialblueprint.util.BlueprintStorage;
import com.xioyim.spatialblueprint.util.SafeNbtIo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * C2S：轻量请求某蓝图的<b>材料清单</b>。
 *
 * <p>只回 {@code materialList.entries}（材料 key + 数量），不整份拉蓝图数据 ——
 * 蓝图列表的 ☄「查看材料清单」用它拉起只读页。</p>
 */
public class C2S_RequestBlueprintMaterialsMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2S_RequestBlueprintMaterialsMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "c2s_request_blueprint_materials_message"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestBlueprintMaterialsMessage> STREAM_CODEC =
            StreamCodec.ofMember(C2S_RequestBlueprintMaterialsMessage::encode,
                    C2S_RequestBlueprintMaterialsMessage::decode);

    /** 单次回传条数上限：材料页显示不下更多，也防止条目超多时撑爆包。 */
    private static final int MAX_ENTRIES = 300;

    private final String blueprintId;

    public C2S_RequestBlueprintMaterialsMessage(String blueprintId) {
        this.blueprintId = blueprintId == null ? "" : blueprintId;
    }

    private C2S_RequestBlueprintMaterialsMessage() { this(""); }

    @Override
    public CustomPacketPayload.Type<C2S_RequestBlueprintMaterialsMessage> type() {
        return TYPE;
    }

    public static void encode(C2S_RequestBlueprintMaterialsMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.blueprintId, 64);
    }

    public static C2S_RequestBlueprintMaterialsMessage decode(FriendlyByteBuf buf) {
        return new C2S_RequestBlueprintMaterialsMessage(buf.readUtf(64));
    }

    public static void handle(C2S_RequestBlueprintMaterialsMessage msg, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) {
            handleServer(msg, player);
        }
    }

    public static void handleServer(C2S_RequestBlueprintMaterialsMessage msg, ServerPlayer player) {
        if (player == null || msg.blueprintId.isEmpty()) return;

        // A1：与 H2 同口径 —— 复用 canManageBlueprint（主人或 OP），否则回空清单。
        if (!com.xioyim.spatialblueprint.server.BlueprintValidator.canManageBlueprint(player, msg.blueprintId)) {
            BlueprintNetwork.sendToPlayer(player,
                    new S2C_BlueprintMaterialsMessage(msg.blueprintId, "", new ArrayList<>(), new ArrayList<>()));
            return;
        }

        List<String> keys = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        String category = "";
        try {
            Path p = BlueprintStorage.findFile(msg.blueprintId);
            if (p != null) {
                try (FileInputStream fis = new FileInputStream(p.toFile())) {
                    CompoundTag root = SafeNbtIo.readCompressed(fis);
                    if (root.contains("SpatialBlueprintData", 10)) {
                        CompoundTag mod = root.getCompound("SpatialBlueprintData");
                        category = mod.getString("category");
                        if (mod.contains("materialList", 10)) {
                            CompoundTag ml = mod.getCompound("materialList");
                            if (ml.contains("entries", 9)) {
                                ListTag entries = ml.getList("entries", 10);
                                int n = Math.min(MAX_ENTRIES, entries.size());
                                for (int i = 0; i < n; i++) {
                                    CompoundTag e = entries.getCompound(i);
                                    keys.add(e.getString("block"));
                                    counts.add(e.getInt("count"));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不到就当没有材料清单，回空列表即可
        }

        BlueprintNetwork.sendToPlayer(player,
                new S2C_BlueprintMaterialsMessage(msg.blueprintId, category, keys, counts));
    }
}
