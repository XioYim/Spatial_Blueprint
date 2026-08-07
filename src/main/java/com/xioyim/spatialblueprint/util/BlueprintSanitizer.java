package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 蓝图上传净化管线。
 *
 * <ul>
 *   <li>Phase 1：{@link #exceedsMaxDepth(Tag, int)} — NBT 嵌套深度扫描</li>
 *   <li>Phase 2：容器物品清空 / 告示牌危险文本清空 / 实体白名单 / 展示框内容过滤</li>
 *   <li>Phase 3：坐标越界方块/实体过滤、画 NBT 净化、字段级数值/字符串校验（§4）</li>
 * </ul>
 *
 * <p>后续阶段（hasIllegalBlocks 标记、审核队列）将陆续加入本类，详见项目根目录
 * 《上传文件检测细节和方案计划.md》第 5、11.8 节。</p>
 */
public final class BlueprintSanitizer {

    private BlueprintSanitizer() {}

    // ── Phase 1：NBT 深度扫描 ──────────────────────────────────────────────

    /**
     * 逐层广度优先扫描 NBT 树，判断是否存在深度超过 {@code maxDepth} 的节点。
     *
     * <p>必须使用迭代 BFS 而非递归：恶意构造的 NBT 可嵌套数千层，递归扫描会直接
     * StackOverflow 崩溃服务器。一旦发现深度超限立即返回，不继续遍历剩余节点。</p>
     *
     * @param root     待扫描的根节点（通常是反序列化后的 {@link CompoundTag}）
     * @param maxDepth 允许的最大嵌套深度（根节点自身记为深度 1）
     * @return 是否存在超过 {@code maxDepth} 的节点
     */
    public static boolean exceedsMaxDepth(Tag root, int maxDepth) {
        if (maxDepth < 1) return true;

        List<Tag> currentLevel = new ArrayList<>();
        currentLevel.add(root);
        int depth = 1;

        while (!currentLevel.isEmpty()) {
            if (depth > maxDepth) return true;

            List<Tag> nextLevel = new ArrayList<>();
            for (Tag t : currentLevel) {
                if (t instanceof CompoundTag ct) {
                    for (String key : ct.getAllKeys()) {
                        nextLevel.add(ct.get(key));
                    }
                } else if (t instanceof ListTag lt) {
                    for (Tag child : lt) {
                        nextLevel.add(child);
                    }
                }
            }

            if (nextLevel.isEmpty()) break;
            currentLevel = nextLevel;
            depth++;
        }

        return depth > maxDepth;
    }

    // ── Phase 2：方块 NBT 危险内容 ──────────────────────────────────────────

    /** 检测方块 NBT 是否含危险内容：非空容器物品列表、告示牌文字、或刷怪笼生成数据。 */
    public static boolean hasDangerousBlockNbt(CompoundTag nbt) {
        if (nbt == null) return false;
        // 容器：Items 列表非空
        if (nbt.contains("Items", 9) && !nbt.getList("Items", 10).isEmpty()) return true;
        // 告示牌 1.20.x 格式
        for (String side : new String[]{"front_text", "back_text"}) {
            if (nbt.contains(side, 10)) {
                CompoundTag sideTag = nbt.getCompound(side);
                if (sideTag.contains("messages", 9)) {
                    ListTag msgs = sideTag.getList("messages", 8);
                    for (int i = 0; i < msgs.size(); i++) {
                        String s = msgs.getString(i);
                        if (!s.equals("{\"text\":\"\"}") && !s.isBlank() && !s.equals("\"\"")) return true;
                    }
                }
            }
        }
        // 告示牌旧格式（1.19 及以前）
        for (String key : new String[]{"Text1", "Text2", "Text3", "Text4"}) {
            if (nbt.contains(key, 8)) {
                String s = nbt.getString(key);
                if (!s.equals("{\"text\":\"\"}") && !s.isBlank() && !s.equals("\"\"")) return true;
            }
        }
        // 刷怪笼：SpawnData 非空，或 SpawnPotentials 列表非空
        // （SpawnData/Potentials 里的实体不受上传实体白名单约束，是绕过"禁止生物"规则的另一条路）
        if (nbt.contains("SpawnData", 10) && !nbt.getCompound("SpawnData").isEmpty()) return true;
        if (nbt.contains("SpawnPotentials", 9) && !nbt.getList("SpawnPotentials", 10).isEmpty()) return true;
        return false;
    }

    /**
     * 清除方块 NBT 中的危险内容（容器物品、告示牌文字、刷怪笼生成数据），返回副本。
     * 原 NBT 不受影响。刷怪笼只清空 {@code SpawnData}/{@code SpawnPotentials}，
     * Delay/MinSpawnDelay/MaxSpawnDelay/SpawnCount 等纯数值字段原样保留。
     */
    public static CompoundTag stripDangerousBlockNbt(CompoundTag nbt) {
        if (nbt == null) return null;
        CompoundTag stripped = nbt.copy();
        stripped.remove("Items");
        stripped.remove("RecordItem");
        for (String side : new String[]{"front_text", "back_text"}) {
            if (stripped.contains(side, 10)) {
                CompoundTag sideTag = stripped.getCompound(side).copy();
                sideTag.remove("messages");
                stripped.put(side, sideTag);
            }
        }
        for (String key : new String[]{"Text1", "Text2", "Text3", "Text4"}) {
            stripped.remove(key);
        }
        stripped.remove("SpawnData");
        stripped.remove("SpawnPotentials");
        return stripped;
    }

    /**
     * 无条件清空所有方块的容器物品与告示牌文字（不判断是否"危险"，直接清空）。
     *
     * <p>用于上传等不受信任的客户端数据源：不能依赖"先检测再决定是否清空"，
     * 因为上传数据整体不可信，容器/告示牌内容一律强制清空。</p>
     */
    public static void stripContainerAndSignNbtInPlace(BlueprintData data) {
        stripListInPlace(data.normalBlocks);
        stripListInPlace(data.dependentBlocks);
        stripListInPlace(data.lateBlocks);
    }

    private static void stripListInPlace(List<BlueprintData.BlockEntry> list) {
        for (int i = 0; i < list.size(); i++) {
            BlueprintData.BlockEntry entry = list.get(i);
            if (entry.nbt == null) continue;
            CompoundTag stripped = stripDangerousBlockNbt(entry.nbt);
            if (stripped != entry.nbt) {
                list.set(i, new BlueprintData.BlockEntry(entry.pos, entry.state, stripped));
            }
        }
    }

    // ── Phase 2：实体白名单 ──────────────────────────────────────────────

    /** 上传时允许保留的实体类型。其余一律丢弃（含所有生物）。 */
    public static final Set<String> ENTITY_WHITELIST = Set.of(
            "minecraft:painting",
            "minecraft:item_frame",
            "minecraft:glow_item_frame"
    );

    /**
     * 蓝图内相对坐标的合法范围上界（不含），对应文档 §4.4 的 [0, 2048)。
     * 方块 pos 或实体 tileX/Y/Z 超出该范围一律视为非法丢弃。
     */
    public static final int MAX_RELATIVE_COORD = 2048;

    /**
     * 判断实体条目是否允许保留。
     *
     * <p>同时校验 {@code entry.type} 与其 NBT 内真正的 {@code id} 字段——放置时
     * {@code EntityType.create(nbt, level)} 是按 NBT 里的 {@code id} 决定召唤哪种实体，
     * 完全不看 {@code entry.type}。只检查 {@code entry.type} 会被绕过：攻击者可以把
     * {@code entry.type} 填成 "minecraft:painting" 骗过白名单，同时把 NBT 的
     * {@code id} 改成任意其他实体（含生物、命令方块矿车等）。两者必须一致且都在白名单内。</p>
     *
     * <p>同时校验坐标与朝向范围（§4.5）：tileX/Y/Z 必须落在 [0, {@link #MAX_RELATIVE_COORD})；
     * facing 画为 [0,3]，展示框/荧光展示框为 [0,5]（字节按无符号处理）。</p>
     */
    public static boolean isEntityAllowed(BlueprintData.EntityEntry entry) {
        if (entry.type == null || !ENTITY_WHITELIST.contains(entry.type)) return false;
        if (entry.nbt == null) return false;
        String nbtId = entry.nbt.getString("id");
        if (!entry.type.equals(nbtId)) return false;

        if (entry.tileX < 0 || entry.tileX >= MAX_RELATIVE_COORD) return false;
        if (entry.tileY < 0 || entry.tileY >= MAX_RELATIVE_COORD) return false;
        if (entry.tileZ < 0 || entry.tileZ >= MAX_RELATIVE_COORD) return false;

        int facing = entry.facing & 0xFF; // byte → unsigned
        int maxFacing = entry.type.equals("minecraft:painting") ? 3 : 5;
        return facing <= maxFacing;
    }

    /** 移除所有不在白名单内的实体（原地过滤 {@code data.entities}）。 */
    public static void filterEntitiesInPlace(BlueprintData data) {
        data.entities.removeIf(entry -> !isEntityAllowed(entry));
    }

    // ── Phase 2：展示框内容过滤 ──────────────────────────────────────────

    /** 检测展示框实体 NBT 中的物品是否属于 {@link BlueprintConfig#EXCLUDED_BLOCKS}。 */
    public static boolean isItemFrameItemDangerous(CompoundTag entityNbt) {
        if (!entityNbt.contains("Item", 10)) return false;
        CompoundTag item = entityNbt.getCompound("Item");
        String itemId = item.getString("id");
        if (itemId.isBlank()) return false;
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc == null) return false;
        Block block = BuiltInRegistries.BLOCK.get(loc);
        return block != Blocks.AIR && BlueprintConfig.isExcluded(block);
    }

    /**
     * 展示框物品内容净化（用于不受信任的上传数据源）：
     * 危险物品 → 连 id 一并丢弃；安全物品 → 只保留 id + Count，丢弃 tag
     * （防止用看似安全的物品外壳夹带危险 NBT）。
     */
    public static void sanitizeItemFrameContentInPlace(CompoundTag entityNbt) {
        if (!entityNbt.contains("Item", 10)) return;
        if (isItemFrameItemDangerous(entityNbt)) {
            entityNbt.remove("Item");
            return;
        }
        CompoundTag item = entityNbt.getCompound("Item");
        CompoundTag cleaned = new CompoundTag();
        cleaned.putString("id", item.getString("id"));
        if (item.contains("Count")) {
            cleaned.putByte("Count", item.getByte("Count"));
        }
        entityNbt.put("Item", cleaned);
    }

    /**
     * 画 NBT 净化：只保留 {@code id}（决定实体种类，放置时 {@code EntityType.create} 依赖它）
     * 与 {@code variant}（画的种类），丢弃其余全部字段。
     *
     * <p>facing / TileX/Y/Z / Pos 等字段放置时会被 {@code BlueprintPlacementHandler}
     * 无条件覆盖重写，原始值不影响放置结果，可放心丢弃。</p>
     */
    public static void sanitizePaintingNbt(CompoundTag nbt) {
        List<String> toRemove = new ArrayList<>();
        for (String key : nbt.getAllKeys()) {
            if (!key.equals("id") && !key.equals("variant")) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            nbt.remove(key);
        }
    }

    // ── Phase 3：坐标范围 / 字段级校验 ──────────────────────────────────────

    /**
     * 丢弃相对坐标越界的方块（§4.4：X/Y/Z 必须落在 [0, {@link #MAX_RELATIVE_COORD})）。
     * 越界方块直接从列表中移除（不是拒绝整个文件）。
     */
    public static void filterOutOfRangeBlocksInPlace(BlueprintData data) {
        filterBlockRange(data.normalBlocks);
        filterBlockRange(data.dependentBlocks);
        filterBlockRange(data.lateBlocks);
    }

    private static void filterBlockRange(List<BlueprintData.BlockEntry> list) {
        list.removeIf(e -> e.pos.getX() < 0 || e.pos.getX() >= MAX_RELATIVE_COORD
                || e.pos.getY() < 0 || e.pos.getY() >= MAX_RELATIVE_COORD
                || e.pos.getZ() < 0 || e.pos.getZ() >= MAX_RELATIVE_COORD);
    }

    // ── Phase 3.5：硬编码非法方块底线 ────────────────────────────────────────

    /**
     * 硬编码非法方块最小集（§3.5）：无论 {@link BlueprintConfig#EXCLUDED_BLOCKS} 配置怎么改，
     * 这些方块永远被拒绝。这些方块要么在原版中无法通过正常生存/创造途径自然获得，要么具备
     * 直接执行指令/破坏服务器的能力——不依赖服主是否记得把它们填进配置文件。
     *
     * <p>{@code minecraft:command_block_minecart} 是实体不是方块，已经被
     * {@link #ENTITY_WHITELIST} 挡住，不需要在这里重复列出。</p>
     *
     * <p>{@code minecraft:light} 不在此列表中：它只是创造模式可通过搜索栏正常获取的
     * 隐形光源方块，没有 NBT 漏洞、不能执行指令，不构成安全风险，不该被当作非法方块。</p>
     */
    public static final Set<String> HARDCODED_ILLEGAL_BLOCKS = Set.of(
            "minecraft:command_block",
            "minecraft:repeating_command_block",
            "minecraft:chain_command_block",
            "minecraft:jigsaw",
            "minecraft:structure_block",
            "minecraft:structure_void",
            "minecraft:barrier",
            "minecraft:bedrock",
            "minecraft:end_portal_frame",
            "minecraft:end_portal",
            "minecraft:nether_portal",
            "minecraft:end_gateway",
            "minecraft:moving_piston"
    );

    /** 综合判断：配置里的 {@code EXCLUDED_BLOCKS}，或硬编码底线列表，任一命中即视为非法。 */
    public static boolean isIllegalBlock(BlockState state) {
        Block block = state.getBlock();
        if (BlueprintConfig.isExcluded(block)) return true;
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(block);
        return rl != null && HARDCODED_ILLEGAL_BLOCKS.contains(rl.toString());
    }

    /** {@link #isIllegalBlock(BlockState)} 的注册名字符串版本（用于材料清单等只有 ID 没有 BlockState 的场景）。 */
    public static boolean isIllegalBlockId(String registryId) {
        return BlueprintConfig.isExcludedId(registryId) || HARDCODED_ILLEGAL_BLOCKS.contains(registryId);
    }

    /**
     * 上传投影模板专属字段规则（§4.3 数值范围 + 强制清空工程蓝图专属字段）：
     *
     * <ul>
     *   <li>{@code animDuration} 超出 [0,300] 归 0（回退到配置文件自动分档）</li>
     *   <li>{@code customWithdrawXp} / {@code customWithdrawLimit}：非 OP 强制 -1；
     *       OP 也会被截断到配置上限</li>
     *   <li>{@code fixedDimension} / {@code fixedStartPos} / {@code fixedEndPos} / {@code maxDistance}：
     *       这些是工程蓝图专属字段，投影模板用不到，一律清空，防止无意义数据混入 dat 文件</li>
     * </ul>
     */
    public static void enforceTemplateFieldRules(BlueprintData data, boolean isOp) {
        if (data.animDuration < 0 || data.animDuration > 300) {
            data.animDuration = 0;
        }

        if (!isOp) {
            data.customWithdrawXp = -1;
            data.customWithdrawLimit = -1;
        } else {
            if (data.customWithdrawXp < -1) data.customWithdrawXp = -1;
            int maxXp = BlueprintConfig.MAX_WITHDRAW_XP_COST.get();
            if (data.customWithdrawXp > maxXp) data.customWithdrawXp = maxXp;

            if (data.customWithdrawLimit < -1) data.customWithdrawLimit = -1;
            int maxLimit = BlueprintConfig.MAX_WITHDRAW_COUNT.get();
            if (data.customWithdrawLimit > maxLimit) data.customWithdrawLimit = maxLimit;
        }

        data.fixedDimension = "";
        data.fixedStartPos  = null;
        data.fixedEndPos    = null;
        data.maxDistance    = -1;
    }
}
