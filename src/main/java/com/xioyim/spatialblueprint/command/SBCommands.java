package com.xioyim.spatialblueprint.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.ProjectBlueprintItem;
import com.xioyim.spatialblueprint.network.C2S_ReloadConfigMessage;
import com.xioyim.spatialblueprint.network.S2C_MaterialSpaceSyncMessage;
import com.xioyim.spatialblueprint.server.SubProjectClaimHandler;
import net.minecraft.commands.arguments.GameProfileArgument;

import java.util.Collection;
import java.util.UUID;
import com.xioyim.spatialblueprint.util.BlueprintPlacementHandler;
import com.xioyim.spatialblueprint.util.ItemSyncUtil;
import com.xioyim.spatialblueprint.util.ProjectClaimRegistry;
import com.xioyim.spatialblueprint.util.ProjectData;
import com.xioyim.spatialblueprint.util.ProjectStorage;
import com.xioyim.spatialblueprint.util.SBUndoCooldown;
import com.xioyim.spatialblueprint.util.SBUndoData;
import com.xioyim.spatialblueprint.util.UndoRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class SBCommands {

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("sb")
                        .then(Commands.literal("undo")
                                .executes(SBCommands::executeUndo))
                        .then(Commands.literal("config")
                                .requires(src -> src.hasPermission(2))
                                .executes(SBCommands::executeOpenConfig))
                        .then(Commands.literal("reload")
                                .requires(src -> src.hasPermission(2))
                                .executes(SBCommands::executeReload))
                        .then(Commands.literal("space")
                                .executes(SBCommands::executeSpaceSelf)
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .requires(src -> src.hasPermission(2))
                                        .executes(SBCommands::executeSpaceOther)))
                        .then(Commands.literal("tpperm")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                .executes(ctx -> executeTpPerm(ctx, true))))
                                .then(Commands.literal("revoke")
                                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                .executes(ctx -> executeTpPerm(ctx, false))))
                                .then(Commands.literal("list")
                                        .executes(SBCommands::executeTpPermList)))
                        .then(Commands.literal("project")
                                .then(Commands.literal("activate")
                                        .executes(SBCommands::executeProjectActivate))
                                .then(Commands.literal("claim")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .executes(SBCommands::executeProjectClaim)))
                                .then(Commands.literal("release")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(SBCommands::executeProjectRelease))
                                .then(Commands.literal("list")
                                        .requires(src -> src.hasPermission(2))
                                        .executes(SBCommands::executeProjectList)))
        );
        SpatialBlueprintMod.LOGGER.info(
                "[SpatialBlueprint] Registered command: /sb undo, /sb config, /sb reload, /sb space, /sb tpperm, /sb project");
    }

    private static int executeProjectActivate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.player_only"));
            return 0;
        }

        InteractionHand hand = InteractionHand.MAIN_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ProjectBlueprintItem item)) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.need_item"));
            return 0;
        }

        if (ProjectBlueprintItem.getTaskSetId(stack) != null) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.already_activated"));
            return 0;
        }

        String projectIdString = ProjectBlueprintItem.getProjectId(stack);
        if (projectIdString == null) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.unbound"));
            return 0;
        }

        if (item.isPublicProject() && (!player.hasPermissions(2) || !player.isCreative())) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.public_requires_op"));
            return 0;
        }

        UUID projectId;
        ProjectData project;
        try {
            projectId = UUID.fromString(projectIdString);
            project = ProjectStorage.loadProject(projectId);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.project_missing"));
            return 0;
        }
        if (project == null) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.project_missing"));
            return 0;
        }

        BlockPos size = project.size;
        if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.size_missing"));
            return 0;
        }

        // project.dat 不记维度：任务集的维度在激活时按玩家所在维度写进 Claim
        ServerLevel level = player.serverLevel();
        String currentDimension = level.dimension().location().toString();

        if (project.entries.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.no_entries"));
            return 0;
        }

        for (ProjectData.EntryData entry : project.entries) {
            String filename = entry.filename;
            if (filename == null || filename.isEmpty()
                    || ProjectStorage.loadEntry(projectId, filename) == null) {
                source.sendFailure(Component.translatable(
                        "spatial_blueprint.command.project.activate.entry_missing",
                        filename == null || filename.isEmpty() ? "?" : filename));
                return 0;
            }
        }

        BlockPos origin = player.blockPosition();
        int maxX = origin.getX() + size.getX() - 1;
        int maxY = origin.getY() + size.getY() - 1;
        int maxZ = origin.getZ() + size.getZ() - 1;
        ProjectClaimRegistry registry = ProjectClaimRegistry.get(level);
        ProjectClaimRegistry.Claim conflict = registry.findOverlap(
                currentDimension,
                origin.getX(), origin.getY(), origin.getZ(),
                maxX, maxY, maxZ);
        if (conflict != null) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.activate.overlap",
                    claimOwner(conflict), conflict.minX, conflict.minY, conflict.minZ));
            return 0;
        }

        UUID taskSetId = UUID.randomUUID();
        ProjectBlueprintItem.setTaskSetId(stack, taskSetId);
        ItemSyncUtil.syncHand(player, hand);

        ProjectClaimRegistry.Claim claim = new ProjectClaimRegistry.Claim();
        claim.taskSetId = taskSetId.toString();
        claim.projectId = projectId.toString();
        claim.dimension = currentDimension;
        claim.publicProject = item.isPublicProject();
        claim.ownerUuid = player.getUUID().toString();
        claim.ownerName = player.getGameProfile().getName();
        claim.createdAt = System.currentTimeMillis();
        claim.minX = origin.getX();
        claim.minY = origin.getY();
        claim.minZ = origin.getZ();
        claim.maxX = maxX;
        claim.maxY = maxY;
        claim.maxZ = maxZ;
        // 世界基准点：玩家站位对应 project.origin，anchor 相对 origin 的差原样搬过来。
        // 之后扩建只动 min/max 与 project.origin/size，anchor 永不改 → 已建子工程位置不变。
        BlockPos projectAnchor = project.anchor == null ? project.origin : project.anchor;
        BlockPos projectOrigin = project.origin == null ? BlockPos.ZERO : project.origin;
        claim.anchorX = origin.getX() + (projectAnchor.getX() - projectOrigin.getX());
        claim.anchorY = origin.getY() + (projectAnchor.getY() - projectOrigin.getY());
        claim.anchorZ = origin.getZ() + (projectAnchor.getZ() - projectOrigin.getZ());
        claim.hasAnchor = true;
        registry.add(claim);

        String taskSetPrefix = taskSetPrefix(taskSetId.toString());
        source.sendSuccess(() -> Component.translatable(
                "spatial_blueprint.command.project.activate.success",
                taskSetPrefix, origin.getX(), origin.getY(), origin.getZ()), false);
        return 1;
    }

    private static int executeProjectClaim(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.player_only"));
            return 0;
        }

        int requestedIndex = IntegerArgumentType.getInteger(ctx, "index");
        SubProjectClaimHandler.Result result = SubProjectClaimHandler.claim(player, requestedIndex);
        if (result.alreadyDone()) {
            source.sendSuccess(() -> Component.translatable(
                    "spatial_blueprint.command.project.claim.already_done"), false);
        }
        if (result.success()) {
            source.sendSuccess(() -> Component.translatable(
                    result.messageKey(), result.args()), false);
            return 1;
        }
        source.sendFailure(Component.translatable(result.messageKey(), result.args()));
        return 0;
    }

    private static int executeProjectRelease(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.player_only"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        ProjectClaimRegistry registry = ProjectClaimRegistry.get(level);
        ProjectClaimRegistry.Claim claim = registry.findAt(
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
        if (claim == null) {
            source.sendFailure(Component.translatable(
                    "spatial_blueprint.command.project.release.none"));
            return 0;
        }

        registry.removeByTaskSet(claim.taskSetId);
        source.sendSuccess(() -> Component.translatable(
                "spatial_blueprint.command.project.release.success",
                claimOwner(claim), claim.minX, claim.minY, claim.minZ), false);
        return 1;
    }

    private static int executeProjectList(CommandContext<CommandSourceStack> ctx) {
        java.util.List<ProjectClaimRegistry.Claim> claims =
                ProjectClaimRegistry.get(ctx.getSource().getLevel()).all();
        if (claims.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "spatial_blueprint.command.project.list.empty"), false);
            return 0;
        }

        int index = 1;
        for (ProjectClaimRegistry.Claim claim : claims) {
            int currentIndex = index++;
            Component type = Component.translatable(claim.publicProject
                    ? "spatial_blueprint.command.project.type.public"
                    : "spatial_blueprint.command.project.type.private");
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "spatial_blueprint.command.project.list.entry",
                    currentIndex, claimOwner(claim), type, claim.dimension,
                    claim.minX, claim.minY, claim.minZ,
                    claim.maxX, claim.maxY, claim.maxZ,
                    taskSetPrefix(claim.taskSetId)), false);
        }
        return claims.size();
    }

    private static String claimOwner(ProjectClaimRegistry.Claim claim) {
        if (!claim.ownerName.isEmpty()) return claim.ownerName;
        return claim.ownerUuid.isEmpty() ? "?" : claim.ownerUuid;
    }

    private static String taskSetPrefix(String taskSetId) {
        return taskSetId.substring(0, Math.min(8, taskSetId.length()));
    }

    /** 查看自己的材料空间（普通池）。 */
    private static int executeSpaceSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatable("spatial_blueprint.undo.player_only"));
            return 0;
        }
        S2C_MaterialSpaceSyncMessage.sendFullAndOpen(player, player.getUUID(), "");
        return 1;
    }

    /**
     * 查看指定玩家的材料空间（OP 专属）。材料空间是世界级存储，因此离线玩家同样可查 ——
     * 这里用 {@link GameProfileArgument} 而非 {@code EntityArgument.player()}（后者只认在线玩家）。
     */
    private static int executeSpaceOther(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatable("spatial_blueprint.undo.player_only"));
            return 0;
        }
        Collection<GameProfile> profiles;
        try {
            profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        } catch (Exception e) {
            ctx.getSource().sendFailure(
                    Component.translatable("spatial_blueprint.command.space.unknown_player"));
            return 0;
        }
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(
                    Component.translatable("spatial_blueprint.command.space.unknown_player"));
            return 0;
        }
        GameProfile target = profiles.iterator().next();
        S2C_MaterialSpaceSyncMessage.sendFullAndOpen(viewer, target.getId(), target.getName());
        return 1;
    }

    /**
     * 授予 / 移除玩家的蓝图传送权限（OP 专属）。
     *
     * <p>用 {@link GameProfileArgument} 而非 {@code EntityArgument.player()}，
     * 这样离线玩家也能预先授权。目标在线时立即推送新权限，界面上的按钮随之解锁。</p>
     */
    private static int executeTpPerm(CommandContext<CommandSourceStack> ctx, boolean grant) {
        ServerLevel level = ctx.getSource().getLevel();
        Collection<GameProfile> profiles;
        try {
            profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        } catch (Exception e) {
            ctx.getSource().sendFailure(
                    Component.translatable("spatial_blueprint.command.space.unknown_player"));
            return 0;
        }
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(
                    Component.translatable("spatial_blueprint.command.space.unknown_player"));
            return 0;
        }

        com.xioyim.spatialblueprint.util.TeleportPermissions perms =
                com.xioyim.spatialblueprint.util.TeleportPermissions.get(level);
        int changed = 0;
        for (GameProfile target : profiles) {
            boolean ok = grant ? perms.grant(target.getId()) : perms.revoke(target.getId());
            String key = ok
                    ? (grant ? "spatial_blueprint.command.tpperm.granted"
                             : "spatial_blueprint.command.tpperm.revoked")
                    : (grant ? "spatial_blueprint.command.tpperm.already_has"
                             : "spatial_blueprint.command.tpperm.not_has");
            final String name = target.getName();
            ctx.getSource().sendSuccess(() -> Component.translatable(key, name), true);
            if (ok) changed++;

            // 目标在线的话立刻同步，免得他还要重登才能用
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(target.getId());
            if (online != null) syncTeleportPerm(online);
        }
        return changed;
    }

    /** 列出被单独授予传送权限的玩家（OP 专属）。 */
    private static int executeTpPermList(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        java.util.Set<java.util.UUID> ids =
                com.xioyim.spatialblueprint.util.TeleportPermissions.get(level).all();
        if (ids.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("spatial_blueprint.command.tpperm.list.empty"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Component.translatable("spatial_blueprint.command.tpperm.list.header", ids.size()), false);
        for (java.util.UUID id : ids) {
            // 名字优先从玩家档案缓存查；查不到（从未上线过的 UUID）就直接显示 UUID
            String name = level.getServer().getProfileCache() != null
                    ? level.getServer().getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString())
                    : id.toString();

            // 名字后面挂一个红色 [移除]，点一下就执行 /sb tpperm revoke <名字>。
            //
            // 用玩家名而不是 UUID 拼命令：revoke 走的是 GameProfileArgument，
            // 它认名字（含离线玩家），传 UUID 反而解析不了。
            // 名字查不到（列表里显示的就是一串 UUID）时不给按钮 —— 那条命令注定失败。
            boolean nameResolved = !name.equals(id.toString());
            net.minecraft.network.chat.MutableComponent line =
                    Component.literal(" - " + name + " ");
            if (nameResolved) {
                line.append(Component.translatable("spatial_blueprint.command.tpperm.list.revoke_btn")
                        .withStyle(style -> style
                                .withColor(net.minecraft.ChatFormatting.RED)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                        "/sb tpperm revoke " + name))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                        Component.translatable(
                                                "spatial_blueprint.command.tpperm.list.revoke_tip", name)))));
            }
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return ids.size();
    }

    /** 把该玩家的传送权限结论推给他的客户端。 */
    public static void syncTeleportPerm(ServerPlayer player) {
        com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new com.xioyim.spatialblueprint.network.S2C_TeleportPermMessage(
                        com.xioyim.spatialblueprint.util.TeleportPermissions.canTeleport(player)));
    }

    /** 让执行命令的玩家打开客户端配置主页面（OP 专属；命令本身已带 requires，这里双保险）。 */
    private static int executeOpenConfig(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatable("spatial_blueprint.undo.player_only"));
            return 0;
        }
        if (!player.hasPermissions(2)) {
            ctx.getSource().sendFailure(Component.translatable(
                    "spatial_blueprint.config.error.no_permission"));
            return 0;
        }
        com.xioyim.spatialblueprint.network.BlueprintNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new com.xioyim.spatialblueprint.network.s2c.S2C_OpenConfigMessage());
        return 1;
    }

    /** 热加载所有 spatial_blueprint 配置（OP 专属）。 */
    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        C2S_ReloadConfigMessage.doReload();
        ctx.getSource().sendSuccess(
                () -> Component.translatable("spatial_blueprint.command.reload.success"), true);
        return 1;
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
