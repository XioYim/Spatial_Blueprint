package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
import com.xioyim.spatialblueprint.network.s2c.S2C_OpCaptureConfirmMessage;
import com.xioyim.spatialblueprint.network.s2c.S2C_OperationResult;
import com.xioyim.spatialblueprint.server.BlueprintValidator;
import com.xioyim.spatialblueprint.util.BlueprintData;
import com.xioyim.spatialblueprint.util.BlueprintSanitizer;
import com.xioyim.spatialblueprint.util.FluidCaptureFilter;
import com.xioyim.spatialblueprint.util.MaterialList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class CaptureAreaMessage {
    private final String name;
    /** 储存模式：捕捉成功后立刻清除选区内所有方块和装饰实体。 */
    private final boolean clearAfterCapture;
    /** 玩家在 GUI 输入的动画时长（秒），写入蓝图 NBT 供放置阶段使用。 */
    private final int durationSeconds;
    /** 流体捕捉过滤：决定哪些流体方块不录入蓝图。 */
    private final FluidCaptureFilter fluidCaptureFilter;
    /** OP 玩家跳过危险内容检测，无条件创建蓝图。 */
    private final boolean opBypass;

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, false);
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter, boolean opBypass) {
        this.name = name;
        this.clearAfterCapture = clearAfterCapture;
        this.durationSeconds = durationSeconds;
        this.fluidCaptureFilter = fluidCaptureFilter;
        this.opBypass = opBypass;
    }

    public static void encode(CaptureAreaMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name);
        buf.writeBoolean(msg.clearAfterCapture);
        buf.writeInt(msg.durationSeconds);
        buf.writeByte(msg.fluidCaptureFilter.toByte());
        buf.writeBoolean(msg.opBypass);
    }

    public static CaptureAreaMessage decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(BlueprintValidator.MAX_NAME_LENGTH * 2);
        boolean clear = buf.readBoolean();
        int duration = buf.readInt();
        FluidCaptureFilter filter = FluidCaptureFilter.fromByte(buf.readByte());
        boolean bypass = buf.readBoolean();
        return new CaptureAreaMessage(name, clear, duration, filter, bypass);
    }

    public static void handle(CaptureAreaMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!BlueprintValidator.checkAndUpdateCooldown(player)) return;

            ItemStack stack = null;
            InteractionHand hand = null;
            ItemStack mainHand = player.getMainHandItem();
            if (mainHand.getItem() instanceof BlankBlueprintItem) {
                stack = mainHand;
                hand = InteractionHand.MAIN_HAND;
            } else {
                ItemStack offHand = player.getOffhandItem();
                if (offHand.getItem() instanceof BlankBlueprintItem) {
                    stack = offHand;
                    hand = InteractionHand.OFF_HAND;
                }
            }

            if (stack == null) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.name(msg.name), "no_blank_blueprint");
                sendResult(player, false, "spatial_blueprint.error.no_blank_blueprint");
                return;
            }

            String safeName = BlueprintValidator.sanitizeName(msg.name);

            BlockPos p1 = BlankBlueprintItem.getPoint1(stack);
            BlockPos p2 = BlankBlueprintItem.getPoint2(stack);
            if (p1 == null || p2 == null) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.name(safeName), "missing_points");
                sendResult(player, false, "spatial_blueprint.error.missing_points");
                return;
            }

            BlueprintValidator.ValidationResult validation =
                    BlueprintValidator.validateCaptureArea(player, p1, p2, msg.fluidCaptureFilter);
            if (!validation.valid()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.area(p1, p2) + " " + BlueprintLogger.volume(p1, p2)
                        + " " + BlueprintLogger.name(safeName), validation.failKey());
                sendResult(player, false, validation.failKey(), validation.detail());
                return;
            }

            BlockPos min = new BlockPos(
                    Math.min(p1.getX(), p2.getX()),
                    Math.min(p1.getY(), p2.getY()),
                    Math.min(p1.getZ(), p2.getZ())
            );
            BlockPos max = new BlockPos(
                    Math.max(p1.getX(), p2.getX()),
                    Math.max(p1.getY(), p2.getY()),
                    Math.max(p1.getZ(), p2.getZ())
            );

            BlueprintLogger.intent(player, BlueprintLogger.Action.CAPTURE,
                    BlueprintLogger.area(min, max) + " " + BlueprintLogger.volume(min, max)
                    + " " + BlueprintLogger.name(safeName));

            ServerLevel level = player.serverLevel();

            List<String> illegalBlocks = new ArrayList<>();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockState state = level.getBlockState(new BlockPos(x, y, z));
                        if (BlueprintSanitizer.isIllegalBlock(state)) {
                            illegalBlocks.add(state.getBlock().getName().getString());
                        }
                    }
                }
            }
            if (!illegalBlocks.isEmpty()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName),
                        "illegal_blocks=" + illegalBlocks);
                sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                return;
            }

            // 储存模式 + 生存/冒险玩家：验证区域内每个方块的破坏权限
            // 兼容 FTB Chunks / 其他领地保护 mod（通过 BlockEvent.BreakEvent 取消机制）
            if (msg.clearAfterCapture && !player.isCreative()) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            BlockPos checkPos = new BlockPos(x, y, z);
                            BlockState bs = level.getBlockState(checkPos);
                            if (bs.isAir()) continue;
                            BlockEvent.BreakEvent evt =
                                    new BlockEvent.BreakEvent(level, checkPos, bs, player);
                            MinecraftForge.EVENT_BUS.post(evt);
                            if (evt.isCanceled()) {
                                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                                        BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName),
                                        "no_break_permission@" + checkPos);
                                sendResult(player, false, "spatial_blueprint.error.no_break_permission");
                                return;
                            }
                        }
                    }
                }
            }

            AABB captureBox = new AABB(
                    min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0
            );

            // ── 危险内容检测：容器物品、告示牌文字、展示框危险物品 ─────────────────────
            boolean hasDangerous = false;
            // 扫描方块 NBT
            outer_scan:
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
                        if (be != null) {
                            CompoundTag nbt = be.saveWithFullMetadata();
                            if (BlueprintSanitizer.hasDangerousBlockNbt(nbt)) { hasDangerous = true; break outer_scan; }
                        }
                    }
                }
            }
            // 扫描展示框实体（含荧光展示框）
            if (!hasDangerous) {
                for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)) {
                    CompoundTag nbt = new CompoundTag();
                    frame.save(nbt);
                    if (BlueprintSanitizer.isItemFrameItemDangerous(nbt)) { hasDangerous = true; break; }
                }
            }

            if (hasDangerous) {
                boolean isOp = player.hasPermissions(2);
                if (isOp && !msg.opBypass) {
                    // 弹出 OP 确认对话框，暂停本次捕捉
                    BlueprintNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new S2C_OpCaptureConfirmMessage(msg.name, msg.clearAfterCapture,
                                    msg.durationSeconds, msg.fluidCaptureFilter));
                    return;
                }
                if (isOp && msg.opBypass) {
                    SpatialBlueprintMod.LOGGER.info(
                            "[SpatialBlueprint] [op_bypass] {} captured blueprint '{}' with dangerous content bypass",
                            player.getScoreboardName(), safeName);
                }
                // 非 OP（或 OP 未 bypass）：危险内容将在下方捕捉阶段被自动过滤
            }

            BlueprintData data = new BlueprintData();
            data.name = safeName;
            data.size = new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
            // OP bypass 时保留完整 NBT；非 OP（或无危险）时自动过滤危险内容
            boolean stripDangerous = hasDangerous && !(player.hasPermissions(2) && msg.opBypass);
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;
                        if (msg.fluidCaptureFilter.shouldSkip(state)) continue;
                        BlockEntity be = level.getBlockEntity(pos);
                        CompoundTag nbt = be != null ? be.saveWithFullMetadata() : null;
                        if (stripDangerous && nbt != null) {
                            nbt = BlueprintSanitizer.stripDangerousBlockNbt(nbt);
                        }
                        BlueprintData.BlockEntry entry = new BlueprintData.BlockEntry(pos.subtract(min), state, nbt);
                        Set<String> latePlaceIds = BlueprintConfig.getLatePlaceBlocks();
                        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (latePlaceIds.contains(blockId)) {
                            data.lateBlocks.add(entry);
                        } else if (BlueprintData.isDependent(state)) {
                            data.dependentBlocks.add(entry);
                        } else {
                            data.normalBlocks.add(entry);
                        }
                    }
                }
            }
            // Pre-sort at capture time so placement needs no further classification
            Collections.shuffle(data.normalBlocks);
            data.dependentBlocks.sort((a, b) -> {
                boolean aDown = BlueprintData.isDownwardGrowing(a.state);
                boolean bDown = BlueprintData.isDownwardGrowing(b.state);
                if (aDown != bDown) return Boolean.compare(aDown, bDown);
                if (aDown) return Integer.compare(b.pos.getY(), a.pos.getY());
                return Integer.compare(a.pos.getY(), b.pos.getY());
            });

            // 捕捉装饰实体：画、展示框、荧光展示框
            level.getEntitiesOfClass(Painting.class, captureBox, e -> true).forEach(entity -> {
                captureHangingEntity(entity, min, data, false);
            });
            boolean bypassEntities = player.hasPermissions(2) && msg.opBypass;
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true).forEach(entity -> {
                captureHangingEntity(entity, min, data, bypassEntities);
            });

            // 设定构建时长：OP 可自定义，普通玩家按方块数自动分档
            int capturedTotal = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            if (player.hasPermissions(2) && msg.durationSeconds > 0) {
                data.animDuration = Math.max(BlueprintConfig.ANIMATION_DURATION_MIN.get(), msg.durationSeconds);
            } else {
                data.animDuration = BlueprintConfig.getAutoDuration(capturedTotal);
            }

            // 计算并嵌入材料清单 + 分类（统一格式，dat 包含完整信息）
            data.materialList = MaterialList.fromBlueprintData(data);
            data.category     = msg.clearAfterCapture ? "dimensional" : "create";
            data.creatorName  = player.getScoreboardName();
            data.sourceName   = player.getScoreboardName();
            data.createdAt    = System.currentTimeMillis();
            data.opBypassCapture = player.hasPermissions(2) && msg.opBypass;

            // Replace blank blueprint in hand with the appropriate blueprint item
            net.minecraft.world.item.Item resultItem = msg.clearAfterCapture
                    ? SpatialBlueprintItems.DIMENSIONAL_BLUEPRINT.get()
                    : SpatialBlueprintItems.SPATIAL_BLUEPRINT.get();
            ItemStack spatialStack = new ItemStack(resultItem);
            SpatialBlueprintItem.setBlueprintData(spatialStack, data);
            SpatialBlueprintItem.setBlueprintName(spatialStack, safeName);
            SpatialBlueprintItem.setAnimDuration(spatialStack, data.animDuration);
            // 次元蓝图记录捕捉坐标，冷却后用于生成空置次元蓝图
            if (msg.clearAfterCapture) {
                spatialStack.getOrCreateTag().put("captureMin", net.minecraft.nbt.NbtUtils.writeBlockPos(min));
                spatialStack.getOrCreateTag().put("captureMax", net.minecraft.nbt.NbtUtils.writeBlockPos(max));
                spatialStack.getOrCreateTag().putString("creatorName", player.getScoreboardName());
                // 方块已从世界清除，材料视为已由玩家支付，自动标记为全部存入
                if (data.materialList != null && !data.materialList.blockCounts.isEmpty()) {
                    CompoundTag depTag = new CompoundTag();
                    for (Map.Entry<String, Integer> matEntry : data.materialList.blockCounts.entrySet()) {
                        depTag.putInt(matEntry.getKey(), matEntry.getValue());
                    }
                    spatialStack.getOrCreateTag().put("depositedMaterials", depTag);
                }
            }
            player.setItemInHand(hand, spatialStack);
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);

            int capturedBlockCount = data.normalBlocks.size() + data.dependentBlocks.size() + data.lateBlocks.size();
            BlueprintLogger.success(player, BlueprintLogger.Action.CAPTURE,
                    BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName)
                    + " blocks=" + capturedBlockCount + " entities=" + data.entities.size()
                    + (msg.clearAfterCapture ? " [store+clear]" : ""));

            // 储存模式：清除选区内所有方块与装饰实体
            // 清除顺序与放置顺序相反：装饰实体 → 依赖方块 → 普通方块
            // 若先清普通方块，火把/按钮/铁轨会因支撑消失而级联掉落
            if (msg.clearAfterCapture) {
                // UPDATE_CLIENTS(2):        同步客户端视图
                // UPDATE_KNOWN_SHAPE(16):   跳过 updateNeighbourShapes，压力板/红石/中继器等"形状依赖"方块不会因底部消失而弹出
                // UPDATE_SUPPRESS_DROPS(32):抑制物品掉落
                // UPDATE_MOVE_BY_PISTON(64):传 isMoving=true 给 onRemove；DiodeBlock/RedStoneWireBlock 检测到 isMoving=true 立即 return，不发出任何邻居更新
                int clearFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_MOVE_BY_PISTON;
                BlockState air = Blocks.AIR.defaultBlockState();

                // 第一步：移除装饰实体（画、展示框）
                level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                        .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
                level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                        .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));

                // 第二步：优先清除所有依赖方块（火把、红石、中继器、比较器、压力板等）
                // 此时它们的支撑方块仍在原位，onRemove 不会因支撑消失而触发掉落或级联更新。
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int x = min.getX(); x <= max.getX(); x++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            if (!state.isAir() && BlueprintData.isDependent(state)) {
                                level.removeBlockEntity(pos);
                                level.setBlock(pos, air, clearFlags);
                            }
                        }
                    }
                }

                // 第三步：清除剩余普通方块（依赖方块已全部移除，不会再发生级联掉落）
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int x = min.getX(); x <= max.getX(); x++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (!level.getBlockState(pos).isAir()) {
                                level.removeBlockEntity(pos);
                                level.setBlock(pos, air, clearFlags);
                            }
                        }
                    }
                }
            }

            sendResult(player, true, "");
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 捕捉悬挂实体（画/展示框）。
     * @param bypass 若为 true（OP bypass 模式），展示框内的危险物品原样保留；否则自动移除。
     */
    public static void captureHangingEntity(net.minecraft.world.entity.Entity entity,
                                              BlockPos min, BlueprintData data, boolean bypass) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.save(nbt)) {
            // 实体不可序列化（理论上不会发生，防御性检查）
            return;
        }
        nbt.remove("UUID");
        // 展示框：非 bypass 模式下过滤被 EXCLUDED_BLOCKS 列出的危险物品
        if (entity instanceof ItemFrame && !bypass) {
            if (BlueprintSanitizer.isItemFrameItemDangerous(nbt)) {
                nbt.remove("Item");
            }
        }
        int relTileX = nbt.getInt("TileX") - min.getX();
        int relTileY = nbt.getInt("TileY") - min.getY();
        int relTileZ = nbt.getInt("TileZ") - min.getZ();
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        // Painting  使用 "facing"（小写）+ Direction.get2DDataValue() 编码（0-3）
        // ItemFrame 使用 "Facing"（大写）+ Direction.get3DDataValue() 编码（0-5，含上下）
        byte facing = type.equals("minecraft:painting")
                ? nbt.getByte("facing")
                : nbt.getByte("Facing");
        data.entities.add(new BlueprintData.EntityEntry(relTileX, relTileY, relTileZ, facing, type, nbt));
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        sendResult(player, success, messageKey, "");
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey, String detail) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey, detail)
        );
    }
}
