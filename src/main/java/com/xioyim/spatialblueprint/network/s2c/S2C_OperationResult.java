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

    public S2C_OperationResult(boolean success, String messageKey) {
        this(success, messageKey, "");
    }

    public S2C_OperationResult(boolean success, String messageKey, String detail) {
        this.success = success;
        this.messageKey = messageKey != null ? messageKey : "";
        this.detail = detail != null ? detail : "";
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
        buf.writeUtf(msg.detail, 512);
    }

    public static S2C_OperationResult decode(FriendlyByteBuf buf) {
        return new S2C_OperationResult(buf.readBoolean(), buf.readUtf(256), buf.readUtf(512));
    }

    public static void handle(S2C_OperationResult msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> handleClient(msg));
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(S2C_OperationResult msg) {
        Minecraft mc = Minecraft.getInstance();

        if (!msg.messageKey.isEmpty() && mc.player != null) {
            Component text = msg.detail.isEmpty()
                    ? Component.translatable(msg.messageKey)
                    : Component.literal(Component.translatable(msg.messageKey).getString() + "\n" + msg.detail);

            if (msg.success) {
                NotifySound.client();
                // 成功消息：关闭特定界面 + 聊天显示
                if (mc.screen instanceof com.xioyim.spatialblueprint.gui.PointSelectionScreen
                        || mc.screen instanceof com.xioyim.spatialblueprint.gui.PlacementScreen) {
                    mc.setScreen(null);
                }
                mc.player.displayClientMessage(text, false);
            } else {
                // 失败消息：优先用 ErrorToast
                if (mc.screen instanceof ErrorToastSupport toastScreen) {
                    toastScreen.setErrorToast(text);
                    NotifySound.clientError();
                } else {
                    // 不支持 toast 的界面：关闭 + 聊天显示
                    if (mc.screen instanceof com.xioyim.spatialblueprint.gui.PointSelectionScreen
                            || mc.screen instanceof com.xioyim.spatialblueprint.gui.PlacementScreen) {
                        mc.setScreen(null);
                    }
                    NotifySound.clientError();
                    mc.player.sendSystemMessage(text);
                }
            }
        }
    }
}
