package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：玩家登录时同步捕捉体积上限与槽位上限。
 *
 * <p>这些是<b>服务端</b>配置，客户端本地的 toml 在多人模式下不一定相同，因此界面
 * （区域大小校验、蓝图列表 tab 的槽位显示）必须用同步过来的值，不能直接读本地配置。</p>
 *
 * <p>上限按<b>蓝图类型</b>划分，与游戏模式无关 —— 结构能有多大取决于它要存成哪种蓝图。</p>
 */
public class S2C_ConfigSyncMessage implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2C_ConfigSyncMessage> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    SpatialBlueprintMod.MODID, "s2c_config_sync_message"));

    public static final StreamCodec<FriendlyByteBuf, S2C_ConfigSyncMessage> STREAM_CODEC =
            StreamCodec.ofMember(S2C_ConfigSyncMessage::encode, S2C_ConfigSyncMessage::decode);

    /**
     * 客户端缓存 — 登录时由服务端写入；默认值与配置文件默认值一致，
     * 确保在握手包到达之前或单人模式下行为正确。
     */
    public static volatile int clientMaxVolSpatial     = 500_000;
    public static volatile int clientMaxVolProjection  = 100_000;
    public static volatile int clientMaxVolDimensional = 100_000;
    public static volatile int clientMaxVolEngineering = 100_000;
    public static volatile int clientMaxVolTemplate    = BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME;
    public static volatile int clientMaxVolProjectSet = BlueprintConfig.ABSOLUTE_MAX_CAPTURE_VOLUME;
    public static volatile int clientMaxVolSubProject = 300_000;

    public static volatile int clientSlotSpatial     = 0;
    public static volatile int clientSlotProjection  = 30;
    public static volatile int clientSlotDimensional = 10;
    public static volatile int clientSlotEngineering = 0;
    public static volatile int clientSlotTemplate    = 30;

    private final int[] volumes;   // 顺序：spatial, projection, dimensional, engineering, template, projectset, subproject
    private final int[] slots;     // 同上

    public S2C_ConfigSyncMessage(int[] volumes, int[] slots) {
        this.volumes = volumes;
        this.slots   = slots;
    }

    /** 从服务端配置构建一份同步包。 */
    public static S2C_ConfigSyncMessage fromConfig() {
        return new S2C_ConfigSyncMessage(
                new int[]{
                        BlueprintConfig.getMaxVolumeFor("create"),
                        BlueprintConfig.getMaxVolumeFor("project"),
                        BlueprintConfig.getMaxVolumeFor("dimensional"),
                        BlueprintConfig.getMaxVolumeFor("engineering"),
                        BlueprintConfig.getMaxVolumeFor("template"),
                        BlueprintConfig.getMaxVolumeFor("projectset"),
                        BlueprintConfig.getMaxVolumeFor("subproject")
                },
                new int[]{
                        BlueprintConfig.getSlotLimitFor("create"),
                        BlueprintConfig.getSlotLimitFor("project"),
                        BlueprintConfig.getSlotLimitFor("dimensional"),
                        BlueprintConfig.getSlotLimitFor("engineering"),
                        BlueprintConfig.getSlotLimitFor("template")
                });
    }

    /** 客户端侧：按分类取体积上限。 */
    public static int maxVolumeFor(String category) {
        return switch (category == null ? "" : category) {
            case "project"     -> clientMaxVolProjection;
            case "dimensional" -> clientMaxVolDimensional;
            case "engineering" -> clientMaxVolEngineering;
            case "template"    -> clientMaxVolTemplate;
            case "projectset"  -> clientMaxVolProjectSet;
            case "subproject"  -> clientMaxVolSubProject;
            default            -> clientMaxVolSpatial;
        };
    }

    /** 客户端侧：按分类取槽位上限；0 = 不限。 */
    public static int slotLimitFor(String category) {
        return switch (category == null ? "" : category) {
            case "project"     -> clientSlotProjection;
            case "dimensional" -> clientSlotDimensional;
            case "engineering" -> clientSlotEngineering;
            case "template"    -> clientSlotTemplate;
            // 项目蓝图与子工程都不占玩家槽位，无需同步字段
            case "projectset", "subproject" -> 0;
            default            -> clientSlotSpatial;
        };
    }

    public static void encode(S2C_ConfigSyncMessage msg, FriendlyByteBuf buf) {
        for (int v : msg.volumes) buf.writeInt(v);
        for (int s : msg.slots)   buf.writeInt(s);
    }

    public static S2C_ConfigSyncMessage decode(FriendlyByteBuf buf) {
        int[] vols  = new int[7];
        int[] slots = new int[5];
        for (int i = 0; i < 7; i++) vols[i]  = buf.readInt();
        for (int i = 0; i < 5; i++) slots[i] = buf.readInt();
        return new S2C_ConfigSyncMessage(vols, slots);
    }

    @Override
    public CustomPacketPayload.Type<S2C_ConfigSyncMessage> type() {
        return TYPE;
    }

    public static void handle(S2C_ConfigSyncMessage msg, IPayloadContext ctx) {
        handleClient(msg);
    }

    private static void handleClient(S2C_ConfigSyncMessage msg) {
        clientMaxVolSpatial     = msg.volumes[0];
        clientMaxVolProjection  = msg.volumes[1];
        clientMaxVolDimensional = msg.volumes[2];
        clientMaxVolEngineering = msg.volumes[3];
        clientMaxVolTemplate    = msg.volumes[4];
        clientMaxVolProjectSet  = msg.volumes[5];
        clientMaxVolSubProject  = msg.volumes[6];

        clientSlotSpatial     = msg.slots[0];
        clientSlotProjection  = msg.slots[1];
        clientSlotDimensional = msg.slots[2];
        clientSlotEngineering = msg.slots[3];
        clientSlotTemplate    = msg.slots[4];
    }
}
