package com.xioyim.spatialblueprint.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.xioyim.spatialblueprint.client.ClientBlueprintCache;
import com.xioyim.spatialblueprint.client.ClientOtherPreviewCache;
import com.xioyim.spatialblueprint.client.ClientPreviewSettings;
import com.xioyim.spatialblueprint.gui.OtherPlayerDownloadScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class SBClientCommands {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sbp")
                // /sbp preview on|off
                .then(Commands.literal("preview")
                        .then(Commands.literal("on").executes(ctx -> {
                            ClientPreviewSettings.setEnabled(true);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§a[SBP] 已开启其他玩家蓝图预览显示"),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ClientPreviewSettings.setEnabled(false);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§c[SBP] 已关闭其他玩家蓝图预览显示"),
                                    false);
                            return 1;
                        })))
                // /sbp block <player>
                .then(Commands.literal("block")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    ClientPreviewSettings.block(name);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§e[SBP] 已屏蔽玩家预览：" + name),
                                            false);
                                    return 1;
                                })))
                // /sbp unblock <player>
                .then(Commands.literal("unblock")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    ClientPreviewSettings.unblock(name);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§a[SBP] 已解除屏蔽：" + name),
                                            false);
                                    return 1;
                                })))
                // /sbp blocklist
                .then(Commands.literal("blocklist").executes(ctx -> {
                    var list = ClientPreviewSettings.getBlockedPlayers();
                    if (list.isEmpty()) {
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("§7[SBP] 屏蔽列表为空"),
                                false);
                    } else {
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("§e[SBP] 屏蔽列表：" + String.join(", ", list)),
                                false);
                    }
                    return 1;
                }))
                // /sbp download
                .then(Commands.literal("download").executes(ctx -> {
                    List<ClientOtherPreviewCache.Entry> entries =
                            new ArrayList<>(ClientOtherPreviewCache.all());
                    if (entries.isEmpty()) {
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("§7[SBP] 当前范围内没有其他玩家的蓝图预览"),
                                false);
                        return 0;
                    }
                    if (entries.size() == 1) {
                        // Only one player — download directly
                        OtherPlayerDownloadScreen.downloadEntry(entries.get(0));
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("§a[SBP] 正在下载 " + entries.get(0).playerName + " 的蓝图数据..."),
                                false);
                    } else {
                        // Multiple players — open selection screen
                        Minecraft.getInstance().setScreen(new OtherPlayerDownloadScreen(entries));
                    }
                    return 1;
                }))
                // /sbp clearcache
                .then(Commands.literal("clearcache").executes(ctx -> {
                    ClientBlueprintCache.clearAll();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[SBP] 已清除全部蓝图客户端缓存"),
                            false);
                    return 1;
                }))
        );
    }
}
