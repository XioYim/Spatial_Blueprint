package com.xioyim.spatialblueprint.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.util.BlueprintPlacementHandler;
import com.xioyim.spatialblueprint.util.SBUndoCooldown;
import com.xioyim.spatialblueprint.util.SBUndoData;
import com.xioyim.spatialblueprint.util.UndoRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class SBCommands {

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("sb")
                        .then(Commands.literal("undo")
                                .executes(SBCommands::executeUndo))
        );
        SpatialBlueprintMod.LOGGER.info("[SpatialBlueprint] Registered command: /sb undo");
    }

    private static int executeUndo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("spatial_blueprint.undo.player_only"));
            return 0;
        }

        // 仅创造模式
        if (!player.isCreative()) {
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.undo.creative_only"), false);
            return 0;
        }

        // 冷却检查
        if (SBUndoCooldown.isOnCooldown(player.getUUID())) {
            int remaining = SBUndoCooldown.getRemainingSeconds(player.getUUID());
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.undo.cooldown", remaining), true);
            return 0;
        }

        // 取出历史记录
        SBUndoData undoData = SBUndoData.get(player.serverLevel());
        UndoRecord record = undoData.pop(player.getUUID());
        if (record == null) {
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.undo.no_history"), false);
            return 0;
        }

        // 找到目标维度
        ResourceKey<net.minecraft.world.level.Level> dimKey =
                ResourceKey.create(Registries.DIMENSION, record.dimension());
        ServerLevel targetLevel = player.getServer().getLevel(dimKey);
        if (targetLevel == null) {
            player.displayClientMessage(
                    Component.translatable("spatial_blueprint.undo.dim_unloaded"), false);
            return 0;
        }

        // 执行还原
        BlueprintPlacementHandler.restoreUndo(targetLevel, record, player);

        // 撤回后 5 秒冷却
        SBUndoCooldown.setPostUndoCooldown(player.getUUID());

        targetLevel.playSound(null, player.blockPosition(),
                SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0f, 0.8f);
        player.displayClientMessage(
                Component.translatable("spatial_blueprint.undo.success"), false);

        if (player.isCreative() && player.getTags().contains("DeBug"))
            SpatialBlueprintMod.LOGGER.info("[SBUndo] Player {} undid a placement in {}",
                    player.getScoreboardName(), record.dimension());
        return 1;
    }
}
