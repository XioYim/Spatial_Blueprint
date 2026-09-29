package com.xioyim.spatialblueprint.network;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.SpatialBlueprintMod;
import com.xioyim.spatialblueprint.item.BlankBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItem;
import com.xioyim.spatialblueprint.item.SpatialBlueprintItems;
import com.xioyim.spatialblueprint.log.BlueprintLogger;
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

import java.util.Collections;
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
    /** 净化模式：非法方块不录入（对应位置视为空气），危险 NBT 自动净化，继续捕获。 */
    private final boolean sanitizeMode;
    /** 捕获模式：SAFE / SANITIZE / FORCE。 */
    private final com.xioyim.spatialblueprint.util.CaptureMode mode;
    private final String noteShort;
    private final String noteLong;

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, com.xioyim.spatialblueprint.util.CaptureMode.SAFE);
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter, com.xioyim.spatialblueprint.util.CaptureMode mode) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, false, false, mode);
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter, boolean opBypass) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, opBypass, false,
                opBypass ? com.xioyim.spatialblueprint.util.CaptureMode.FORCE : com.xioyim.spatialblueprint.util.CaptureMode.SAFE);
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter, boolean opBypass, boolean sanitizeMode) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, opBypass, sanitizeMode,
                sanitizeMode ? com.xioyim.spatialblueprint.util.CaptureMode.SANITIZE
                        : opBypass ? com.xioyim.spatialblueprint.util.CaptureMode.FORCE
                        : com.xioyim.spatialblueprint.util.CaptureMode.SAFE);
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                               FluidCaptureFilter fluidCaptureFilter, boolean opBypass, boolean sanitizeMode,
                               com.xioyim.spatialblueprint.util.CaptureMode mode) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, opBypass, sanitizeMode, mode, "", "");
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter,
                              com.xioyim.spatialblueprint.util.CaptureMode mode,
                              String noteShort, String noteLong) {
        this(name, clearAfterCapture, durationSeconds, fluidCaptureFilter, false, false, mode,
                noteShort, noteLong);
    }

    public CaptureAreaMessage(String name, boolean clearAfterCapture, int durationSeconds,
                              FluidCaptureFilter fluidCaptureFilter, boolean opBypass, boolean sanitizeMode,
                              com.xioyim.spatialblueprint.util.CaptureMode mode,
                              String noteShort, String noteLong) {
        this.name = name;
        this.clearAfterCapture = clearAfterCapture;
        this.durationSeconds = durationSeconds;
        this.fluidCaptureFilter = fluidCaptureFilter;
        this.opBypass = opBypass;
        this.sanitizeMode = sanitizeMode;
        this.mode = mode;
        this.noteShort = noteShort == null ? "" : noteShort;
        this.noteLong = noteLong == null ? "" : noteLong;
    }

    public static void encode(CaptureAreaMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.name);
        buf.writeBoolean(msg.clearAfterCapture);
        buf.writeInt(msg.durationSeconds);
        buf.writeByte(msg.fluidCaptureFilter.toByte());
        buf.writeBoolean(msg.opBypass);
        buf.writeBoolean(msg.sanitizeMode);
        buf.writeInt(msg.mode.ordinal());
        buf.writeUtf(msg.noteShort, 15);
        buf.writeUtf(msg.noteLong, 200);
    }

    public static CaptureAreaMessage decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(BlueprintValidator.MAX_NAME_LENGTH * 2);
        boolean clear = buf.readBoolean();
        int duration = buf.readInt();
        FluidCaptureFilter filter = FluidCaptureFilter.fromByte(buf.readByte());
        boolean bypass = buf.readBoolean();
        boolean sanitize = buf.readBoolean();
        com.xioyim.spatialblueprint.util.CaptureMode mode = com.xioyim.spatialblueprint.util.CaptureMode.fromOrdinal(buf.readInt());
        String noteShort = buf.readUtf(15);
        String noteLong = buf.readUtf(200);
        return new CaptureAreaMessage(name, clear, duration, filter, bypass, sanitize, mode, noteShort, noteLong);
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

            // clearAfterCapture = 次元蓝图，两者的体积上限与槽位上限都不同
            String targetCategory = msg.clearAfterCapture ? "dimensional" : "create";

            // 槽位校验必须在这里 —— 次元蓝图捕获会清除世界方块，
            // 等清完了才发现存不下，结构就白白没了。
            BlueprintValidator.ValidationResult slot =
                    BlueprintValidator.checkSlot(player, targetCategory);
            if (!slot.valid()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.area(p1, p2) + " " + BlueprintLogger.name(safeName), slot.failKey());
                sendResult(player, false, slot.failKey(), slot.detailKey(), slot.detailArgs());
                return;
            }

            BlueprintValidator.ValidationResult validation =
                    BlueprintValidator.validateCaptureArea(player, p1, p2, msg.fluidCaptureFilter,
                            targetCategory);
            if (!validation.valid()) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.area(p1, p2) + " " + BlueprintLogger.volume(p1, p2)
                        + " " + BlueprintLogger.name(safeName), validation.failKey());
                sendResult(player, false, validation.failKey(), validation.detailKey(), validation.detailArgs());
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

            // 非法方块检测：按捕获模式处理。
            // SAFE：普通/创造玩家服务端拒绝，OP 放行。
            // SANITIZE：不拦截，捕获阶段移除非法方块（视为空气）。
            // FORCE：放行（OP 强制）。
            boolean isOp = player.hasPermissions(2);
            boolean isCreative = player.isCreative();
            // FORCE 仅 OP 可用。msg.mode 完全由客户端决定，非 OP 改包发 FORCE 即可绕过
            // 全部非法方块检测并捕获生物，因此必须在服务端降级。
            // SANITIZE 不限权限——它只是「移除非法方块后继续」，不绕过任何安全检查。
            final com.xioyim.spatialblueprint.util.CaptureMode effMode =
                    (msg.mode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE && !isOp)
                            ? com.xioyim.spatialblueprint.util.CaptureMode.SAFE
                            : msg.mode;

            // ── 次元蓝图：只允许「安全」模式，任何人一样，OP 也不例外 ──────────
            // 次元蓝图会把结构从世界里搬走、材料入袋、放置时原样放回。它跟其它几类的
            // 根本区别是「世界里那份没了」：净化 / OP 放行都意味着蓝图里存的和世界里
            // 消失的不是同一份东西。所以次元蓝图不做任何净化，选区必须整体合规 ——
            // 非法方块、非法物品、能执行命令的告示牌 / 书、带生成数据的刷怪笼，
            // 任一命中就整单拒收，不入库、不清世界，让玩家自己清理干净再来。
            //
            // 客户端「净化并继续」按钮对次元蓝图已灰掉，但 msg.mode 是客户端发的，
            // 改包照样能送 SANITIZE 进来，服务端必须再拒一次。
            final boolean strictDimensional = msg.clearAfterCapture;
            if (strictDimensional && effMode != com.xioyim.spatialblueprint.util.CaptureMode.SAFE) {
                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                        BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName),
                        "dimensional_mode_rejected=" + effMode);
                sendResult(player, false, "spatial_blueprint.error.dimensional_no_sanitize");
                return;
            }

            // 非法方块 / 非法物品预扫。
            // 非次元蓝图：SAFE + 非 OP 才查非法方块（OP 放行，SANITIZE 在捕获阶段跳过）。
            // 次元蓝图：任何人都查，且连方块实体 NBT 一起查（hasDangerousBlockNbt ——
            // 容器里的非法物品、告示牌命令、讲台书命令、刷怪笼生成数据）。
            if (effMode == com.xioyim.spatialblueprint.util.CaptureMode.SAFE
                    && (!isOp || strictDimensional)) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int y = min.getY(); y <= max.getY(); y++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            if (state.isAir()) continue;
                            if (BlueprintSanitizer.isIllegalBlock(state)) {
                                BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                                        BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName),
                                        "illegal_block=" + state.getBlock().getName().getString());
                                sendResult(player, false, "spatial_blueprint.error.illegal_blocks");
                                return;
                            }
                            if (strictDimensional) {
                                BlockEntity be = level.getBlockEntity(pos);
                                if (be != null && BlueprintSanitizer.hasDangerousBlockNbt(be.saveWithFullMetadata())) {
                                    BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                                            BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName),
                                            "dimensional_dangerous_nbt@" + pos + " " + state.getBlock().getName().getString());
                                    sendResult(player, false, "spatial_blueprint.error.dimensional_illegal_items");
                                    return;
                                }
                            }
                        }
                    }
                }
            }
            // 次元蓝图：实体身上的物品也预扫（展示框 / 盔甲架 / 白名单生物）。
            // 判定与扫描报告 C2S_RequestIllegalBlocksMessage 同口径 —— 报告里标危险的，
            // 这里必拒；报告里没标的，这里必放。两边对不上玩家就没法自己清理。
            if (strictDimensional) {
                AABB preBox = new AABB(
                        min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
                String hit = BlueprintSanitizer.findEntityWithIllegalItems(level, preBox);
                if (hit != null) {
                    BlueprintLogger.failed(player, BlueprintLogger.Action.CAPTURE,
                            BlueprintLogger.area(min, max) + " " + BlueprintLogger.name(safeName),
                            "dimensional_entity_illegal_items=" + hit);
                    sendResult(player, false, "spatial_blueprint.error.dimensional_illegal_items");
                    return;
                }
            }

            // 储存模式：验证区域内每个方块的破坏权限
            // 兼容 FTB Chunks / 其他领地保护 mod（通过 BlockEvent.BreakEvent 取消机制）
            //
            // 创造模式是否也受检，跟放置侧同一个开关（denyCreativePlacementInProtectedArea，
            // 默认 true）。这里以前硬编码 !isCreative() 跳过创造 —— 于是开放创造的服上
            // 任何人都能用次元蓝图把别人领地里的建筑整片搬走，放置侧拦得住、捕获侧拦不住。
            // 「创造模式能不能无视领地改动世界」对玩家是同一件事，不该分成两套行为。
            if (msg.clearAfterCapture && (!player.isCreative()
                    || BlueprintConfig.DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA.get())) {
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

            // ── 危险 NBT 处理（按模式）────────────────────────────────────
            // SAFE：普通/创造净化危险 NBT，OP 放行。
            // SANITIZE：净化。
            // FORCE：放行（OP 强制）。
            boolean stripDangerous;
            switch (effMode) {
                case SAFE     -> stripDangerous = !isOp;
                case SANITIZE -> stripDangerous = true;
                default       -> stripDangerous = false;
            }

            // ── 容器内容清空（防复制）────────────────────────────────────
            // 次元蓝图是把结构从世界里搬走的（clearAfterCapture），箱内物品跟着走天经地义；
            // 其余几类都能反复放置，留着箱内物品等于给玩家一台复制机。
            //
            // ⚠ FORCE 除外：那是 OP 的「强制捕获，不受任何限制」，完整保留一切内容 ——
            // FORCE 本来就只有 OP 创造模式能用，能刷物品的人本来也不需要靠蓝图刷。
            boolean stripContents = !msg.clearAfterCapture
                    && effMode != com.xioyim.spatialblueprint.util.CaptureMode.FORCE;

            // ⚠ 上面那条只管「防复制」线。安全线（stripDangerous）此前走的
            // stripDangerousBlockNbt 会无条件倒空一切容器，于是非 OP 玩家的次元蓝图
            // 虽然在防复制线上被放行，箱子照样被安全线倒干净 ——
            // 「次元蓝图箱内物品跟着走」实际只对 OP 成立，搬一次家等于销毁一次财产。
            //
            // 次元蓝图改走 KeepContents 版本：告示牌命令 / 讲台书命令 / 刷怪笼生成数据
            // 照清（真安全项），容器只在混有非法物品时才整体倒空。
            boolean keepContainersWhenStripping = msg.clearAfterCapture;

            BlueprintData data = new BlueprintData();
            data.name = safeName;
            data.size = new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;
                        if (msg.fluidCaptureFilter.shouldSkip(state)) continue;
                        // 净化模式：非法方块不录入（对应位置视为空气）
                        if (effMode == com.xioyim.spatialblueprint.util.CaptureMode.SANITIZE
                                && BlueprintSanitizer.isIllegalBlock(state)) continue;
                        BlockEntity be = level.getBlockEntity(pos);
                        CompoundTag nbt = be != null ? be.saveWithFullMetadata() : null;
                        if (nbt != null) {
                            // 安全净化（按模式）；非次元蓝图的版本连带清空容器，
                            // 次元蓝图版本保留容器（仅在混有非法物品时整体倒空）。
                            // 走不到安全净化时，再单独走防复制那条。
                            if (stripDangerous) {
                                nbt = keepContainersWhenStripping
                                        ? BlueprintSanitizer.stripDangerousBlockNbtKeepContents(nbt)
                                        : BlueprintSanitizer.stripDangerousBlockNbt(nbt);
                            } else if (stripContents) {
                                nbt = BlueprintSanitizer.stripItemContents(nbt);
                            }
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

            // 捕捉实体：画 / 展示框 / 盔甲架 / 白名单生物（按模式）
            //
            // 实体身上的物品，三分支与上面方块容器那套严格对齐：
            //   stripContents  （非次元蓝图）           → 无条件清空，防复制
            //   stripEntityBad （次元蓝图 + 非 OP，或 SANITIZE）→ 只在含非法物品时整体倒
            //   两者都不是     （次元蓝图 + OP + SAFE） → 原样保留
            //
            // 改前 SANITIZE 是无条件清装备/清展示框物品，而次元蓝图的清除阶段又会
            // 无条件删掉这些实体 —— 捕获的是剥光的盔甲架，删掉的是穿着装备的盔甲架，
            // 中间那套装备凭空消失。箱子已经改成「含非法才倒」，同一次捕获里
            // 箱内钻石跟着走、盔甲架上的钻石套却没了，没法跟玩家解释。
            final boolean stripEntityBad = stripDangerous;
            // 生物/拴绳结捕获：SAFE 仅创造游戏模式；SANITIZE 不捕获；FORCE（OP）捕获
            boolean captureMobs = switch (effMode) {
                case SAFE     -> isCreative;
                case SANITIZE -> false;
                default       -> true;
            };

            // 画
            level.getEntitiesOfClass(Painting.class, captureBox, e -> true).forEach(entity ->
                    captureHangingEntity(entity, min, data, true));
            // 展示框：非次元蓝图一律移除物品（防复制）；次元蓝图只在物品非法时倒
            level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true).forEach(entity -> {
                CompoundTag nbt = new CompoundTag();
                if (!entity.save(nbt)) return;
                nbt.remove("UUID");
                if (stripContents) {
                    nbt.remove("Item");
                } else if (stripEntityBad) {
                    BlueprintSanitizer.sanitizeEntityItemsInPlace(nbt);
                }
                addEntityEntry(nbt, entity, min, data);
            });
            // 盔甲架：非次元蓝图一律移除装备（防复制）；次元蓝图只在装备非法时倒
            level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, captureBox, e -> true)
                    .forEach(entity -> {
                        CompoundTag nbt = new CompoundTag();
                        if (!entity.save(nbt)) return;
                        nbt.remove("UUID");
                        if (stripContents) {
                            nbt.remove("ArmorItems");
                            nbt.remove("HandItems");
                        } else if (stripEntityBad) {
                            BlueprintSanitizer.sanitizeEntityItemsInPlace(nbt);
                        }
                        addEntityEntry(nbt, entity, min, data);
                    });
            // 白名单生物：按模式捕获
            if (captureMobs) {
                for (net.minecraft.world.entity.LivingEntity le :
                        level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                                captureBox, e -> e instanceof net.minecraft.world.entity.LivingEntity
                                        && !(e instanceof net.minecraft.world.entity.player.Player))) {
                    String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                    if (BlueprintSanitizer.ALLOWED_MOB_ENTITIES.contains(mobId)) {
                        CompoundTag nbt = new CompoundTag();
                        if (!le.save(nbt)) continue;
                        nbt.remove("UUID");
                        // 驮兽（驴/骡/羊驼）身上能背箱子，装备栏也能塞东西。
                        // 非次元蓝图无条件倒空（防复制，OP 也不例外）；
                        // 次元蓝图保留原样，只在含非法内容时才整体倒掉。
                        // 判定用 stripEntityBad 而不是 !isOp：当前 SANITIZE 不捕生物，
                        // 两者在可达路径上等价，但跟展示框/盔甲架/方块容器写成同一个条件，
                        // 将来改了 captureMobs 的门槛也不会漏掉这条。
                        if (stripContents) {
                            BlueprintSanitizer.stripMobInventoryInPlace(nbt);
                        } else if (stripEntityBad) {
                            BlueprintSanitizer.sanitizeMobInventoryInPlace(nbt);
                        }
                        addEntityEntry(nbt, le, min, data);
                    }
                }
                // 拴绳结（LeashFenceKnotEntity 非 LivingEntity，需单独捕获）
                level.getEntitiesOfClass(net.minecraft.world.entity.decoration.LeashFenceKnotEntity.class,
                                captureBox, e -> true)
                        .forEach(entity -> {
                            CompoundTag nbt = new CompoundTag();
                            if (!entity.save(nbt)) return;
                            nbt.remove("UUID");
                            addEntityEntry(nbt, entity, min, data);
                        });
            }

            // 盒型实体（Create 超级胶水等）：与上面几类不同，位置不在 Pos / TileXYZ 上，
            // 而是 NBT 里的 From/To 一对角点。不捕它的后果是结构照样放得出来，
            // 但 Create 重新组装 contraption 时找不到胶水，粘性失效 → 一推就散架。
            //
            // 按注册 ID 匹配，不引用 Create 的任何类 —— 本 mod 对 Create 零依赖，
            // From/To 是纯 vanilla 的 double ListTag，读写不需要认识那个实体类型。
            // 捕获不受 captureMobs 门禁影响：胶水是结构的一部分，不是生物。
            captureBoxEntities(level, captureBox, min, data);

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
            data.ownerUuid    = player.getUUID().toString();
            com.xioyim.spatialblueprint.util.WorldIdentity.stamp(data, player.serverLevel());
            data.createdAt    = System.currentTimeMillis();
            data.saveMode     = effMode;
            // 与 effMode 绑定：该标记的含义就是「OP 以 FORCE 模式捕获」，
            // 放置时据此跳过材料校验与扣除（挖走不给料，放置也不要料）。
            data.opBypassCapture = effMode == com.xioyim.spatialblueprint.util.CaptureMode.FORCE;
            data.noteShort = msg.noteShort;
            data.noteLong = msg.noteLong;

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
                // 方块已从世界清除，材料存入该玩家材料空间的「次元袋」，与蓝图 ID 绑定。
                // 袋子只能被同 ID 的次元蓝图放置时消耗，玩家无法取出，也不受普通池上限约束。
                // 这样即使蓝图物品丢失，结构（dat 文件）与材料（袋子）都还在。
                //
                // 只有 FORCE 捕获（OP 特权）不入袋 —— 挖走不给料，对应放置时也不要料。
                //
                // 创造模式同样入袋：放置时生存模式会严格校验袋子是否存在，
                // 若创造捕获的蓝图不入袋，切到生存后就永远放不出来（材料无从补齐）。
                // 创造模式放置本身不消耗袋子，蓝图仍可重复使用；袋子堆积由 OP 清理，
                // 代价远小于「蓝图彻底作废」。
                if (effMode != com.xioyim.spatialblueprint.util.CaptureMode.FORCE
                        && data.materialList != null && !data.materialList.blockCounts.isEmpty()) {
                    String bagId = spatialStack.getTag().getString("blueprintId");
                    if (!bagId.isEmpty()) {
                        com.xioyim.spatialblueprint.util.MaterialSpace.get(player.serverLevel())
                                .bagDepositAll(player.getUUID(), bagId, data.materialList, data.name);
                    }
                }
            }
            player.setItemInHand(hand, spatialStack);
            com.xioyim.spatialblueprint.util.ItemSyncUtil.syncHand(player, hand);

            // 主动把蓝图数据推给客户端缓存：否则捕获后立刻右键打开界面时客户端还没有数据，
            // 材料按钮会因 data==null 而显示为禁用（看起来像「没有材料清单」）。
            String newBpId = spatialStack.hasTag() ? spatialStack.getTag().getString("blueprintId") : "";
            if (!newBpId.isEmpty()) {
                BlueprintNetwork.sendBlueprintTo(player, newBpId, data);
            }

            // 冷却只在这里起算：到这一步蓝图已经落盘、物品已经换好，是一次真正成功的创建
            com.xioyim.spatialblueprint.network.s2c.S2C_CaptureCooldownMessage.notifySuccess(player);

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

                // 第一步：移除已被捕获的实体。
                // 必须与上面的捕获范围严格一一对应 —— 捕获了却不删除会导致实体（及其装备）
                // 被复制：蓝图里存了一份，世界里还留着一份，放置时就凭空多出一份物品。
                level.getEntitiesOfClass(Painting.class, captureBox, e -> true)
                        .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
                level.getEntitiesOfClass(ItemFrame.class, captureBox, e -> true)
                        .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
                // 盔甲架：捕获阶段无条件捕获，因此这里也无条件移除
                level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, captureBox, e -> true)
                        .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
                // 白名单生物与拴绳结：仅在捕获阶段真的捕获了它们时才移除
                if (captureMobs) {
                    for (net.minecraft.world.entity.LivingEntity le :
                            level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                                    captureBox, e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
                        String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
                        if (BlueprintSanitizer.ALLOWED_MOB_ENTITIES.contains(mobId)) {
                            le.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                        }
                    }
                    level.getEntitiesOfClass(net.minecraft.world.entity.decoration.LeashFenceKnotEntity.class,
                                    captureBox, e -> true)
                            .forEach(e -> e.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
                }
                // 盒型实体（胶水）：整盒在内 / 跨界的都删，见 removeBoxEntities 的说明
                removeBoxEntities(level, captureBox);

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
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        int relTileX = nbt.getInt("TileX") - min.getX();
        int relTileY = nbt.getInt("TileY") - min.getY();
        int relTileZ = nbt.getInt("TileZ") - min.getZ();
        // Painting  使用 "facing"（小写）+ Direction.get2DDataValue() 编码（0-3）
        // ItemFrame 使用 "Facing"（大写）+ Direction.get3DDataValue() 编码（0-5，含上下）
        byte facing = type.equals("minecraft:painting")
                ? nbt.getByte("facing")
                : nbt.getByte("Facing");
        data.entities.add(new BlueprintData.EntityEntry(relTileX, relTileY, relTileZ, facing, type, nbt));
    }

    /**
     * 通用实体条目构造。悬挂实体（画/展示框）用 TileX/Y/Z；盔甲架/生物用 Pos（世界坐标）。
     */
    /**
     * 捕获盒型实体 —— 位置由 NBT 的 {@code From}/{@code To} 角点描述，而不是 {@code Pos}。
     * 目前唯一的实例是 Create 的超级胶水（见 {@code BlueprintConfig.BOX_ENTITIES}）。
     *
     * <p><b>只收整盒都在选区内的。</b>{@code getEntitiesOfClass} 用的是相交判定，
     * 会把跨出边界的胶水也捞进来；那种半截胶水放置后指向选区外的空气，
     * 既粘不住东西又留下一个看不见的实体。Create 自己的做法是裁剪（collectCropped），
     * 但裁一半的胶水到底该不该粘是个产品问题，这里选择直接跳过 —— 宁可不捕，
     * 不要捕一个语义不明的。</p>
     *
     * <p>写进 entry 的 {@code From}/{@code To} 已经转成<b>相对 {@code min} 的坐标</b>，
     * 放置时再按旋转变换并加上目标原点，见
     * {@code BlueprintPlacementHandler.applyBoxEntityCorners}。</p>
     */
    public static void captureBoxEntities(net.minecraft.server.level.ServerLevel level,
                                           net.minecraft.world.phys.AABB captureBox,
                                           BlockPos min, BlueprintData data) {
        java.util.Set<String> boxIds = BlueprintConfig.getBoxEntities();
        if (boxIds.isEmpty()) return;

        for (net.minecraft.world.entity.Entity entity :
                level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, captureBox, e -> true)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (!boxIds.contains(type)) continue;

            CompoundTag nbt = new CompoundTag();
            if (!entity.save(nbt)) continue;
            nbt.remove("UUID");

            net.minecraft.nbt.ListTag from = nbt.getList("From", 6);
            net.minecraft.nbt.ListTag to   = nbt.getList("To", 6);
            if (from.size() != 3 || to.size() != 3) {
                // 不是预期的角点结构 —— 可能是同 ID 的别的实现，或 Create 改了格式。
                // 静默跳过：捕不到胶水只是结构会散，硬塞一个格式不对的实体反而更糟。
                SpatialBlueprintMod.LOGGER.debug(
                        "[SpatialBlueprint] Box entity {} has no From/To pair, skipped", type);
                continue;
            }

            // ⚠ NBT 里的 From/To 不是世界坐标，是相对实体 Pos 的偏移：
            // Create 存盘写的是 bb.move(-pos)，读盘再 +pos 还原（Pos 取盒底中心）。
            // 上面那两行只用来确认「是预期格式」，几何一律取实体自己的包围盒 ——
            // 改前把 From/To 当世界坐标去跟 captureBox 比，偏移量都在 ±几格内，
            // 永远落不进世界坐标的选区，于是一个胶水都捕不到。
            net.minecraft.world.phys.AABB bb = entity.getBoundingBox();
            double loX = bb.minX, hiX = bb.maxX;
            double loY = bb.minY, hiY = bb.maxY;
            double loZ = bb.minZ, hiZ = bb.maxZ;

            // 整盒必须落在选区内（含边界）——捕获框是 [min, max+1) 的方块空间
            if (loX < captureBox.minX || hiX > captureBox.maxX
                    || loY < captureBox.minY || hiY > captureBox.maxY
                    || loZ < captureBox.minZ || hiZ > captureBox.maxZ) {
                continue;
            }

            // 蓝图内约定：From/To 存成相对选区 min 的绝对角点（放置侧再换回 Create 的相对 Pos 写法）
            nbt.put("From", doubleTriple(loX - min.getX(), loY - min.getY(), loZ - min.getZ()));
            nbt.put("To",   doubleTriple(hiX - min.getX(), hiY - min.getY(), hiZ - min.getZ()));

            // tileX/Y/Z 只用于放置时的区块加载检查，取盒子的一个角即可；
            // facing 对盒型实体无意义，填 0。真正的位置全在 From/To 里。
            data.entities.add(new BlueprintData.EntityEntry(
                    (int) Math.floor(loX) - min.getX(),
                    (int) Math.floor(loY) - min.getY(),
                    (int) Math.floor(loZ) - min.getZ(),
                    (byte) 0, type, nbt));
        }
    }

    /**
     * 删除选区内（含跨界）的所有盒型实体（Create 超级胶水）。
     *
     * <p>次元蓝图会把结构从世界里搬走，胶水也必须一起走 —— 否则蓝图存一份、世界留一份，
     * 放置时复制。这里刻意<b>不做</b>「整盒在选区内」的裁剪：跨界的那半截胶水反正不会被
     * 捕获（见 {@link #captureBoxEntities}），留在原地只会指向已消失的方块，一并清除。</p>
     */
    static void removeBoxEntities(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.world.phys.AABB captureBox) {
        java.util.Set<String> boxIds = BlueprintConfig.getBoxEntities();
        if (boxIds.isEmpty()) return;
        for (net.minecraft.world.entity.Entity entity :
                level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, captureBox, e -> true)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (boxIds.contains(type)) {
                entity.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
        }
    }

    private static net.minecraft.nbt.ListTag doubleTriple(double a, double b, double c) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(net.minecraft.nbt.DoubleTag.valueOf(a));
        list.add(net.minecraft.nbt.DoubleTag.valueOf(b));
        list.add(net.minecraft.nbt.DoubleTag.valueOf(c));
        return list;
    }

    public static void addEntityEntry(CompoundTag nbt, net.minecraft.world.entity.Entity entity,
                                      BlockPos min, BlueprintData data) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        byte facing = type.equals("minecraft:painting")
                ? nbt.getByte("facing")
                : nbt.getByte("Facing");
        int relX, relY, relZ;
        if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity) {
            relX = nbt.getInt("TileX") - min.getX();
            relY = nbt.getInt("TileY") - min.getY();
            relZ = nbt.getInt("TileZ") - min.getZ();
        } else {
            net.minecraft.nbt.ListTag posList = nbt.getList("Pos", 6);
            relX = (int) Math.floor(posList.getDouble(0)) - min.getX();
            relY = (int) Math.floor(posList.getDouble(1)) - min.getY();
            relZ = (int) Math.floor(posList.getDouble(2)) - min.getZ();
        }
        data.entities.add(new BlueprintData.EntityEntry(relX, relY, relZ, facing, type, nbt));
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey) {
        sendResult(player, success, messageKey, "", null);
    }

    private static void sendResult(ServerPlayer player, boolean success, String messageKey, String detailKey, Object[] detailArgs) {
        BlueprintNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new S2C_OperationResult(success, messageKey, detailKey, detailArgs)
        );
    }
}
