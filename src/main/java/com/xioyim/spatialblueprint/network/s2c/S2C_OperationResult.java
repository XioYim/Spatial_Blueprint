package com.xioyim.spatialblueprint.network.s2c;

import com.xioyim.spatialblueprint.gui.ErrorToastSupport;
import com.xioyim.spatialblueprint.util.NotifySound;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2C_OperationResult {

    public final boolean success;
    public final String messageKey;
    public final String detail;
    public final Object[] detailArgs;

    /** A3：decode 参数个数上限（正常只有几个；发包方是服务端，纯纵深防御）。 */
    private static final int MAX_DETAIL_ARGS = 16;

    /**
     * A3：读列表计数，超限整包拒收（与 {@code S2C_BlueprintChunkMessage} /
     * {@code S2C_BlueprintDataMessage} 的 {@code DecoderException} 同一口径）。
     */
    public static int checkedCount(FriendlyByteBuf buf, int cap, String what) {
        int n = buf.readVarInt();
        if (n < 0 || n > cap) {
            throw new io.netty.handler.codec.DecoderException(
                    "S2C list count out of range: " + what + " = " + n);
        }
        return n;
    }

    public S2C_OperationResult(boolean success, String messageKey) {
        this(success, messageKey, "", null);
    }

    public S2C_OperationResult(boolean success, String messageKey, String detail) {
        this(success, messageKey, detail, null);
    }

    public S2C_OperationResult(boolean success, String messageKey, String detail, Object[] detailArgs) {
        this.success = success;
        this.messageKey = messageKey != null ? messageKey : "";
        this.detail = detail != null ? detail : "";
        this.detailArgs = detailArgs != null ? detailArgs : new Object[0];
    }

    public static S2C_OperationResult success() {
        return new S2C_OperationResult(true, "");
    }

    public static S2C_OperationResult failure(String messageKey) {
        return new S2C_OperationResult(false, messageKey);
    }

    public static S2C_OperationResult failure(String messageKey, String detail) {
        return new S2C_OperationResult(false, messageKey, detail);
    }

    public static void encode(S2C_OperationResult msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.messageKey, 256);
        buf.writeUtf(msg.detail, 256);
        buf.writeVarInt(msg.detailArgs.length);
        for (Object arg : msg.detailArgs) {
            buf.writeUtf(String.valueOf(arg), 64);
        }
    }

    public static S2C_OperationResult decode(FriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        String messageKey = buf.readUtf(256);
        String detail = buf.readUtf(256);
        // A3 纵深防御：异常计数整包拒收，见 checkedCount。
        int argCount = checkedCount(buf, MAX_DETAIL_ARGS, "detailArgs");
        Object[] detailArgs = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            detailArgs[i] = buf.readUtf(64);
        }
        return new S2C_OperationResult(success, messageKey, detail, detailArgs);
    }

    public static void handle(S2C_OperationResult msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    // 必须标 @OnlyIn：本方法体里读 mc.screen（类型是 Screen）、构造 ErrorToastScreen，
    // 不剥离的话专用服务端加载本类、验证方法时就会去加载 Screen 而崩溃。
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void handleClient(S2C_OperationResult msg) {
        Minecraft mc = Minecraft.getInstance();

        if (!msg.messageKey.isEmpty() && mc.player != null) {
            // 上传成功走横幅（不再聊天）；其余成功保持聊天
            boolean uploadBanner = msg.messageKey.startsWith("spatial_blueprint.message.upload_success");

            Component text;
            if (msg.success) {
                text = msg.detail.isEmpty()
                        ? Component.translatable(msg.messageKey).copy()
                                .withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD)
                        : Component.translatable(msg.messageKey).copy()
                                .withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD)
                                .append(Component.literal("\n"))
                                .append(successDetail(msg));
            } else if (msg.detail.isEmpty()) {
                text = Component.translatable(msg.messageKey).copy()
                        .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD);
            } else {
                text = Component.translatable(msg.messageKey).copy()
                        .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD)
                        .append(Component.literal("\n"))
                        .append(buildDetail(msg));
            }

            if (msg.success) {
                NotifySound.client();
                // 成功消息：关闭特定界面
                if (mc.screen instanceof com.xioyim.spatialblueprint.gui.PointSelectionScreen
                        || mc.screen instanceof com.xioyim.spatialblueprint.gui.PlacementScreen) {
                    mc.setScreen(null);
                }
                if (uploadBanner) {
                    com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(text);
                } else {
                    mc.player.displayClientMessage(text, false);
                }
            } else {
                // 失败消息一律走横幅。
                // 不支持 ErrorToastSupport 的界面（以及没有任何界面时，例如玩家在世界中
                // 直接触发的服务端报错）由 ErrorToastScreen 顶上，关闭后回到原界面/游戏。
                // 上传被拒且当前停在「上传中…」报告页时，先退回上传页 —— 那份扫描不会再有
                // 结果，一直停在 loading 只会卡死页面。
                if (mc.screen instanceof com.xioyim.spatialblueprint.gui.UploadReportScreen urs
                        && urs.isLoading()) {
                    mc.setScreen(urs.getParentScreen());
                }
                if (mc.screen instanceof com.xioyim.spatialblueprint.gui.PointSelectionScreen
                        || mc.screen instanceof com.xioyim.spatialblueprint.gui.PlacementScreen) {
                    // 这两个界面在失败后本就该关掉（原有行为），关掉后横幅直接盖在游戏上
                    if (!(mc.screen instanceof ErrorToastSupport)) mc.setScreen(null);
                }
                NotifySound.clientError();
                com.xioyim.spatialblueprint.gui.ErrorToastScreen.show(text);
            }
        }
    }

    /** 给一段文本上色。 */
    private static Component col(Object value, net.minecraft.ChatFormatting fmt) {
        return Component.literal(String.valueOf(value)).withStyle(fmt);
    }

    /** 成功 detail 的精细着色：槽位行「当前xx的槽位状态（已用黄/上限绿）」。 */
    private static Component successDetail(S2C_OperationResult msg) {
        Object[] a = msg.detailArgs;
        if (a.length >= 2) {
            String preKey = null, postKey = null;
            switch (msg.detail) {
                case "spatial_blueprint.message.upload_success.slots.create":
                    preKey = "spatial_blueprint.message.upload_success.slots.create.pre";
                    postKey = "spatial_blueprint.message.upload_success.slots.create.post";
                    break;
                case "spatial_blueprint.message.upload_success.slots.project":
                    preKey = "spatial_blueprint.message.upload_success.slots.project.pre";
                    postKey = "spatial_blueprint.message.upload_success.slots.project.post";
                    break;
                case "spatial_blueprint.message.upload_success.slots.template":
                    preKey = "spatial_blueprint.message.upload_success.slots.template.pre";
                    postKey = "spatial_blueprint.message.upload_success.slots.template.post";
                    break;
                default:
                    break;
            }
            if (preKey != null) {
                return Component.translatable(preKey)
                        .append(col(a[0], net.minecraft.ChatFormatting.YELLOW))
                        .append(Component.literal("/"))
                        .append(col(a[1], net.minecraft.ChatFormatting.GREEN))
                        .append(Component.translatable(postKey));
            }
        }
        return Component.translatable(msg.detail, (Object[]) msg.detailArgs);
    }

    /**
     * detail 行的精细着色：服务端把「当前值 / 上限」等以参数形式传入，translatable 无法给
     * 单个参数上色（塞 Component 会打出 toString），因此对已知模板手工分段：当前值→黄、
     * 上限→绿；维度等次要信息保持默认白。未知模板整段浅青回退。
     */
    private static Component buildDetail(S2C_OperationResult msg) {
        Object[] a = msg.detailArgs;
        switch (msg.detail) {
            case "spatial_blueprint.error.detail.file_size":
                if (a.length >= 2) {
                    return Component.translatable("spatial_blueprint.error.detail.file.pre")
                            .append(col(a[0], net.minecraft.ChatFormatting.YELLOW))
                            .append(Component.translatable("spatial_blueprint.error.detail.file.sep"))
                            .append(col(a[1], net.minecraft.ChatFormatting.GREEN));
                }
                break;
            case "spatial_blueprint.error.detail.bounding_volume":
                if (a.length >= 5) {
                    return Component.translatable("spatial_blueprint.error.detail.bbox.pre")
                            .append(col(a[0], net.minecraft.ChatFormatting.YELLOW))
                            .append(Component.translatable("spatial_blueprint.error.detail.bbox.open"))
                            .append(Component.literal(a[1] + "×" + a[2] + "×" + a[3]))
                            .append(Component.translatable("spatial_blueprint.error.detail.bbox.close"))
                            .append(col(a[4], net.minecraft.ChatFormatting.GREEN))
                            .append(Component.translatable("spatial_blueprint.error.detail.bbox.suffix"));
                }
                break;
            case "spatial_blueprint.error.detail.block_count":
                if (a.length >= 2) {
                    return Component.translatable("spatial_blueprint.error.detail.block.pre")
                            .append(col(a[0], net.minecraft.ChatFormatting.YELLOW))
                            .append(Component.translatable("spatial_blueprint.error.detail.block.mid"))
                            .append(col(a[1], net.minecraft.ChatFormatting.GREEN))
                            .append(Component.translatable("spatial_blueprint.error.detail.block.suffix"));
                }
                break;
            default:
                break;
        }
        // 未知模板回退：整段浅青
        return Component.translatable(msg.detail, (Object[]) msg.detailArgs)
                .copy().withStyle(net.minecraft.ChatFormatting.AQUA);
    }
}
