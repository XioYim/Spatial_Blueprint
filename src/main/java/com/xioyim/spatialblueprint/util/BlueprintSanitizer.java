package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import com.xioyim.spatialblueprint.network.s2c.S2C_IllegalBlocksListMessage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ── Phase 1.6：NBT 体积预算 ──────────────────────────────────────────

    /**
     * 迭代估算一棵 NBT 树的序列化字节数，累计值达到 {@code cap} 立即停止遍历。
     *
     * <p>存在的理由：现有防线量的是「内容危不危险」（{@link #stripDangerousBlockNbt}、
     * {@link #ENTITY_WHITELIST}）和「方块数多不多」（ABSOLUTE_MAX_CAPTURE_VOLUME），
     * 没有一把尺子量字节数。命令方块单块 Command 上限 32500 字符、玩家头颅的 base64
     * 皮肤各不相同无法去重、上千条附魔的列表深度只有 3 层 —— 这些既不危险也不占方块数，
     * 却能把蓝图撑大几个数量级。</p>
     *
     * <p>必须迭代而非递归，理由同 {@link #exceedsMaxDepth}：恶意 NBT 可嵌套数千层。
     * 达到 {@code cap} 就短路，避免为了称一个一千万元素的列表把它整个走一遍。</p>
     *
     * <p>返回的是估算值，不是精确的序列化长度：字符串按 modified UTF-8 计（即
     * {@code DataOutput.writeUTF} 的编码，逐字符累加不分配数组），标签头按固定 2 字节计。
     * 用于数量级判断足够，不要拿它算精确大小。</p>
     *
     * @param root 待称量的根节点，{@code null} 记 0
     * @param cap  累计上限；达到即返回，此时返回值是下界而非真实大小
     * @return 估算字节数
     */
    /**
     * 上传文件的 NBT 体积预算检查结果。{@code ok} 为真表示通过。
     *
     * <p>单体超限<b>不再</b>导致拒绝：那些条目记入扫描的超大 NBT 分类，净化时清 NBT
     * 保留方块。只有总量超限才令 {@code ok} 为 false。</p>
     *
     * @param pos        触发总量超限的方块坐标（相对蓝图原点），通过时为 {@code null}
     * @param totalBytes 未超单体预算的条目累计估算字节数
     * @param maxSingle  扫描中见到的最大单体估算字节数（用于显示）
     */
    /**
     * 诊断重测倍率：超预算的条目按 {@code perEntry * 这个值} 再称一次<b>只为显示</b>，
     * 免得 40 KB 的头颅在报告里显示成 8 KB、玩家照着把阈值调到 9 KB。
     *
     * <p>清理决策永远用普通的 {@code perEntry} 那次测量，与这个值无关。
     * 报告页判断「是否要加 ≥ 前缀」也必须用它，所以放在这里共用 ——
     * 两边各写一个 64 的话，改一边就会出现该加 ≥ 却不加的情况。</p>
     */
    public static final long DIAGNOSTIC_CAP_MULTIPLIER = 64L;

    public record NbtBudgetResult(boolean ok,
                                  @javax.annotation.Nullable BlockPos pos,
                                  long totalBytes, long maxSingle) {}

    /**
     * 检查上传的蓝图里方块实体 NBT 的体积。
     *
     * <p><b>只用于上传路径。</b>捕获路径不做这个限制 —— OP 强制蓝图要能原样带走全部
     * NBT，次元蓝图是把结构从世界搬走、内容跟着走天经地义。这两类恰恰是无差别预算
     * 首先会误伤的，所以限制只放在上传这一侧：那里进来的是外部文件，
     * 也不可能是 FORCE 或次元（两者都不收）。</p>
     *
     * <p>单体超过 {@code perEntry} 预算的条目<b>不再拒绝</b>，而是跳过不计入总量 ——
     * 它们净化时会整条清 NBT（保留方块），真正落盘的文件不含这些字节，按清理后的体积
     * 判断才有意义。总量仍以 {@code total} 拒绝。</p>
     *
     * @param data     反序列化后的蓝图
     * @param perEntry 单个方块实体的字节预算
     * @param total    全部方块实体的字节总预算
     */
    public static NbtBudgetResult checkNbtBudget(BlueprintData data, long perEntry, long total) {
        long used = 0L;
        long maxSingle = 0L;
        long diagnosticCap = perEntry * DIAGNOSTIC_CAP_MULTIPLIER;
        for (List<BlueprintData.BlockEntry> list : List.of(
                data.normalBlocks, data.dependentBlocks, data.lateBlocks)) {
            for (BlueprintData.BlockEntry entry : list) {
                if (entry.nbt == null) continue;
                long bytes = effectiveNbtBytes(entry.nbt, perEntry);
                long displayBytes = bytes;
                if (bytes >= perEntry) {
                    // 超限条目用诊断 cap 重测，但不影响清理决策
                    displayBytes = effectiveNbtBytes(entry.nbt, diagnosticCap);
                }
                if (displayBytes > maxSingle) maxSingle = displayBytes;
                if (bytes >= perEntry) {
                    continue;   // 单体超限：净化时清 NBT，不计入总量（仍用 perEntry 判定）
                }
                used += bytes;
                if (used > total) {
                    return new NbtBudgetResult(false, entry.pos, used, maxSingle);
                }
            }
        }
        return new NbtBudgetResult(true, null, used, maxSingle);
    }

    /**
     * 方块实体 NBT 在<b>净化之后</b>的估算字节数 —— 体积预算判定的统一口径。
     *
     * <p>不能直接称 {@code nbt}：容器物品在净化路径上必然被 {@link #stripDangerousBlockNbt}
     * 清空（它把非空物品列表判为危险内容）。拿注定要删的字节去定罪，会让一个改过名、
     * 装满附魔书的箱子被判超限，连自定义名一起清掉 —— 而它清空容器后只剩约 100 字节，
     * 根本不该被标记。</p>
     *
     * <p>{@link #stripDangerousBlockNbt} 不修改入参（无危险内容时直接返回原引用），
     * 所以扫描阶段也能安全调用。<b>扫描报告、总量判定、实际清理三处必须都走这个方法</b>，
     * 否则报告里列出的方块和实际被清理的方块会对不上。</p>
     */
    private static long effectiveNbtBytes(CompoundTag nbt, long cap) {
        if (nbt == null) return 0L;
        return estimateNbtBytes(stripDangerousBlockNbt(nbt), cap);
    }

    private static long modifiedUtf8Bytes(String s) {
        if (s == null || s.isEmpty()) return 0L;
        long bytes = 0L;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0) bytes += 2;
            else if (c <= 0x007F) bytes += 1;
            else if (c <= 0x07FF) bytes += 2;
            else bytes += 3;
        }
        return bytes;
    }

    public static long estimateNbtBytes(Tag root, long cap) {
        if (root == null) return 0L;

        long total = 0L;
        List<Tag> currentLevel = new ArrayList<>();
        currentLevel.add(root);

        while (!currentLevel.isEmpty() && total < cap) {
            List<Tag> nextLevel = new ArrayList<>();
            for (Tag t : currentLevel) {
                if (t == null) continue;
                total += 2L;   // 标签头（类型 + 长度）
                if (t instanceof CompoundTag ct) {
                    for (String key : ct.getAllKeys()) {
                        total += 2L + modifiedUtf8Bytes(key);
                        nextLevel.add(ct.get(key));
                    }
                } else if (t instanceof ListTag lt) {
                    for (Tag child : lt) nextLevel.add(child);
                } else if (t instanceof StringTag st) {
                    total += modifiedUtf8Bytes(st.getAsString());
                } else if (t instanceof ByteArrayTag bat) {
                    total += bat.getAsByteArray().length;
                } else if (t instanceof IntArrayTag iat) {
                    total += 4L * iat.getAsIntArray().length;
                } else if (t instanceof LongArrayTag lat) {
                    total += 8L * lat.getAsLongArray().length;
                } else {
                    total += 8L;   // 标量统一按最大的 long/double 计
                }
                if (total >= cap) return total;
            }
            currentLevel = nextLevel;
        }
        return total;
    }

    // ── Phase 1.5：非法物品判定 + 递归扫描 ──────────────────────────────────

    /**
     * 硬编码非法<b>物品</b>底线：无论配置怎么改都拒绝。
     *
     * <p>它们是<b>纯物品</b> —— 不在方块注册表里，{@code BuiltInRegistries.BLOCK.get()}
     * 会返回 AIR，光靠方块判定一律放行，只能在这里拦。</p>
     */
    public static final Set<String> HARDCODED_ILLEGAL_ITEMS = Set.of(
            "minecraft:command_block_minecart",
            "minecraft:debug_stick",
            "minecraft:knowledge_book",
            "minecraft:spawner"
    );

    /** NBT 树递归扫描的最大深度。超过这个层数的结构不是正常存档能产生的，直接判危险。 */
    private static final int MAX_SCAN_DEPTH = 32;

    /**
     * 综合判断一个<b>物品</b>注册名是否非法。
     *
     * <p>三条来源取并集：</p>
     * <ol>
     *   <li>{@link #HARDCODED_ILLEGAL_ITEMS} —— 纯物品底线</li>
     *   <li>配置 {@code excludedItems}</li>
     *   <li>该 ID 若同时是方块，复用现有的方块判定（{@link #isIllegalBlockId}）——
     *       禁止捕获基岩，自然也不该允许把基岩塞在箱子里带走</li>
     * </ol>
     *
     * <p>刷怪蛋按后缀统一拦截：原版有 60 多种，逐个列进配置既啰嗦又容易漏，
     * 而且其他 mod 的刷怪蛋同样以 {@code _spawn_egg} 结尾。</p>
     */
    public static boolean isIllegalItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        if (HARDCODED_ILLEGAL_ITEMS.contains(itemId)) return true;
        if (itemId.endsWith("_spawn_egg")) return true;
        if (BlueprintConfig.isExcludedItemId(itemId)) return true;
        // 同名方块被禁 → 物品形式同样禁
        return isIllegalBlockId(itemId);
    }

    /**
     * 递归扫描 NBT 树，判断是否有任意层级的物品/实体是非法的。
     *
     * <p>只看<b>键名为 {@code id} 的字符串值</b>，不是扫所有字符串 —— 后者会把告示牌上
     * 写着的 ID、玩家给物品起的名字统统误判。而 {@code id} 是 MC 序列化 ItemStack 与
     * Entity 的固定约定（{@code {id:"modid:xxx", Count:1b, tag:{...}}}），
     * <b>任何 mod 只要走标准 {@code ItemStack.save()} 就有这个键</b>，
     * 不管外层容器叫 {@code Items}、{@code inventory} 还是别的什么。</p>
     *
     * <p>因此本方法天然覆盖：套娃潜影盒、其他 mod 的容器、陶罐的 {@code item}
     * 单数键（1.20.2+）、蜂箱的 {@code Bees[].EntityData}、刷怪笼的 {@code SpawnData}。</p>
     *
     * <p>用迭代 BFS 而非递归：恶意 NBT 可嵌套数千层，递归会 StackOverflow。
     * 超过 {@link #MAX_SCAN_DEPTH} 层直接判为危险 —— 正常存档不会有那么深的结构。</p>
     */
    public static boolean containsIllegalItemDeep(Tag root) {
        if (root == null) return false;

        List<Tag> currentLevel = new ArrayList<>();
        currentLevel.add(root);
        int depth = 1;

        while (!currentLevel.isEmpty()) {
            if (depth > MAX_SCAN_DEPTH) return true;   // 深到不正常，按危险处理

            List<Tag> nextLevel = new ArrayList<>();
            for (Tag t : currentLevel) {
                if (t instanceof CompoundTag ct) {
                    if (ct.contains("id", Tag.TAG_STRING) && isIllegalItemId(ct.getString("id"))) {
                        return true;
                    }
                    for (String key : ct.getAllKeys()) {
                        nextLevel.add(ct.get(key));
                    }
                } else if (t instanceof ListTag lt) {
                    nextLevel.addAll(lt);
                }
            }
            currentLevel = nextLevel;
            depth++;
        }
        return false;
    }

    /**
     * 递归清空 NBT 里的所有物品容器 —— 任意键名、任意层级。
     *
     * <p>策略是<b>整体清空</b>而非挑出非法项：容器里混有非法物品时整袋倒掉，
     * 不去逐个甄别（精确删除会留下"部分保留"的中间状态，反而更难判断安全性）。</p>
     *
     * <p>识别规则同 {@link #containsIllegalItemDeep}：含字符串 {@code id} 键的
     * CompoundTag 视为一个物品/实体条目。命中后删除承载它的那个键，
     * 因此 {@code Items} / {@code inventory} / {@code item} / {@code Bees} 一视同仁。</p>
     */
    public static void stripAllItemContainersInPlace(CompoundTag tag) {
        stripContainers(tag, 1);
    }

    /**
     * 通用容器清理要跳过的键 —— 这些由专门逻辑按更细的规则处理。
     *
     * <p>{@code Book}（讲台）本身就是一个 {id,Count,tag} 结构，会被通用规则当成物品整本删掉；
     * 但原有策略是<b>只在书里含命令点击事件时才移除</b>，普通书要保留。</p>
     *
     * <p>{@code Bees}（蜂箱 / 蜂巢）无条件保留 —— 蜜蜂是结构本身的一部分，不是可复制的物品，
     * 玩家捕获一片养蜂场就是想连蜜蜂一起带走。1.21 存盘键是小写 {@code bees}，旧大写 {@code Bees} 照样跳过。</p>
     */
    private static final Set<String> STRIP_SKIP_KEYS = Set.of("Book", "Bees", "bees");

    private static void stripContainers(CompoundTag tag, int depth) {
        if (tag == null || depth > MAX_SCAN_DEPTH) return;
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (STRIP_SKIP_KEYS.contains(key)) continue;
            Tag child = tag.get(key);
            if (child instanceof ListTag lt) {
                if (listHoldsItemEntries(lt)) {
                    tag.remove(key);          // 整个列表倒掉（Items / inventory …）
                } else {
                    for (Tag t : lt) {
                        if (t instanceof CompoundTag ct) stripContainers(ct, depth + 1);
                    }
                }
            } else if (child instanceof CompoundTag ct) {
                if (isItemEntry(ct)) {
                    tag.remove(key);          // 单个物品槽（陶罐的 item、RecordItem …）
                } else {
                    stripContainers(ct, depth + 1);
                }
            }
        }
    }

    /**
     * 生物身上的物品净化：<b>发现非法内容才整体倒空</b>，否则原样保留。
     *
     * <p>针对驮兽（驴 / 骡 / 羊驼）—— 它们能背箱子（{@code Items}），还能穿装备
     * （{@code ArmorItems} / {@code HandItems}）、装鞍（{@code SaddleItem}）、
     * 铺地毯（{@code DecorItem}）。这些内容会随蓝图一起存下来，而空间蓝图可以反复放置，
     * 于是每放一次就复制出一份箱内物品。</p>
     *
     * <p>与容器方块一致采用「整体清空」策略：只要任意一格里有非法物品，
     * 这只生物身上的东西全部倒掉，不做逐格甄别。</p>
     *
     * <p>实现委托给 {@link #sanitizeEntityItemsInPlace} —— 生物、盔甲架、展示框
     * 用的是同一套判定，这里只是保留一个语义明确的名字给生物调用点。</p>
     *
     * @return 是否真的清理过（供调用方记日志用）
     */
    public static boolean sanitizeMobInventoryInPlace(CompoundTag mobNbt) {
        return sanitizeEntityItemsInPlace(mobNbt);
    }

    /**
     * 实体身上的物品净化（通用）：<b>发现非法物品才整体倒空</b>，否则原样保留。
     *
     * <p>与 {@link #stripDangerousBlockNbtKeepContents} 是同一套口径的实体版 ——
     * 次元蓝图把结构从世界里搬走，展示框里的画、盔甲架上的装备、驴背上的箱子
     * 都该跟着走；只有真出现非法物品时才整体倒掉，不做逐格甄别。</p>
     *
     * <p>覆盖的键：展示框的 {@code Item}、容器的 {@code Items}、盔甲架与生物的
     * {@code ArmorItems}/{@code HandItems}、驮兽的 {@code SaddleItem}/{@code DecorItem}/
     * {@code Inventory}/{@code body_armor_item}。前面的
     * {@link #stripAllItemContainersInPlace} 已按「含 id + Count」通吃任意键名，
     * 这份列表只是兜底 —— 个别版本/mod 的槽位不带 {@code Count}，通用识别看不见。</p>
     *
     * @return 是否真的清理过（供调用方记日志用）
     */
    public static boolean sanitizeEntityItemsInPlace(CompoundTag entityNbt) {
        if (entityNbt == null || !containsIllegalItemDeep(entityNbt)) return false;
        stripAllItemContainersInPlace(entityNbt);
        for (String key : new String[]{"Item", "Items", "ArmorItems", "HandItems",
                                       "SaddleItem", "DecorItem", "Inventory", "body_armor_item"}) {
            entityNbt.remove(key);
        }
        return true;
    }

    /** 该 CompoundTag 看起来是不是一个序列化后的物品条目。 */
    private static boolean isItemEntry(CompoundTag ct) {
        return ct.contains("id", Tag.TAG_STRING)
                && (ct.contains("Count") || ct.contains("count"));
    }

    /** 该列表里是不是装着物品条目。1.21 容器组件的槽位包一层 {slot, item} 也算，命中整条删除。 */
    private static boolean listHoldsItemEntries(ListTag lt) {
        for (Tag t : lt) {
            if (t instanceof CompoundTag ct) {
                if (isItemEntry(ct)) return true;
                if (ct.contains("item", Tag.TAG_COMPOUND)
                        && isItemEntry(ct.getCompound("item"))) return true;
            }
        }
        return false;
    }

    /**
     * 无条件清空方块 NBT 里的物品内容，返回副本；原 NBT 不受影响。
     *
     * <p>与 {@link #stripDangerousBlockNbt} 的分工：那个管<b>安全</b>（非法物品、告示牌命令、
     * 刷怪笼数据），按捕获模式决定要不要做；这个管<b>防复制</b>，按蓝图类型决定 ——
     * 次元蓝图是把结构从世界里搬走，箱内物品跟着走天经地义；其余几类都能反复放置，
     * 留着箱内物品等于给玩家一台复制机，跟捕获者是不是 OP 没有关系。</p>
     *
     * <p>{@code Bees} 不在清理范围内（见 {@link #STRIP_SKIP_KEYS}）。</p>
     */
    public static CompoundTag stripItemContents(CompoundTag nbt) {
        if (nbt == null) return null;
        CompoundTag stripped = nbt.copy();
        stripAllItemContainersInPlace(stripped);
        // 兜底：个别容器的槽位可能不带 Count，通用识别看不见
        stripped.remove("Items");
        stripped.remove("RecordItem");
        return stripped;
    }

    /**
     * 无条件倒空生物身上的物品（驮兽箱子、装备栏、鞍、地毯）。
     *
     * <p>与 {@link #sanitizeMobInventoryInPlace} 的分工同上：那个是「发现非法才倒」，
     * 这个是「非次元蓝图一律倒」。一头背着满箱物品的驴，跟着可反复放置的空间蓝图
     * 就是一台复制机。</p>
     */
    public static void stripMobInventoryInPlace(CompoundTag mobNbt) {
        if (mobNbt == null) return;
        stripAllItemContainersInPlace(mobNbt);
        for (String key : new String[]{"Items", "ArmorItems", "HandItems", "SaddleItem", "DecorItem",
                                       "Inventory", "body_armor_item"}) {
            mobNbt.remove(key);
        }
    }

    // ── Phase 2：方块 NBT 危险内容 ──────────────────────────────────────────

    /** 检测方块 NBT 是否含危险内容：非空容器物品列表、告示牌命令点击事件、或刷怪笼生成数据。
     * 告示牌仅当文本 JSON 包含可执行命令的 {@code clickEvent}（run_command / suggest_command）才算危险；
     * 普通文字内容不视为危险。 */
    public static boolean hasDangerousBlockNbt(CompoundTag nbt) {
        if (nbt == null) return false;
        // 容器内容：任意层级、任意键名出现非法物品即算危险。
        //
        // 旧实现是「Items 列表非空即危险」—— 只认原版的 Items 键，其他 mod 的容器
        // （inventory / contents / 自定义键）完全看不见，内容原样进蓝图；
        // 而且普通箱子里放几块石头也会被判危险、整箱清空。
        // 现在改为按内容判定，顺带覆盖套娃潜影盒与陶罐的 item 单数键（1.20.2+）。
        if (containsIllegalItemDeep(nbt)) return true;
        // 告示牌 1.20.x 格式：仅检测命令点击事件，不过滤普通文本
        for (String side : new String[]{"front_text", "back_text"}) {
            if (nbt.contains(side, 10)) {
                CompoundTag sideTag = nbt.getCompound(side);
                if (sideTag.contains("messages", 9)) {
                    ListTag msgs = sideTag.getList("messages", 8);
                    for (int i = 0; i < msgs.size(); i++) {
                        if (hasSignCommandClickEvent(msgs.getString(i))) return true;
                    }
                }
            }
        }
        // 告示牌旧格式（1.19 及以前）
        for (String key : new String[]{"Text1", "Text2", "Text3", "Text4"}) {
            if (nbt.contains(key, 8) && hasSignCommandClickEvent(nbt.getString(key))) return true;
        }
        // 讲台/书：新旧两种物品存储格式的页面含命令点击事件即危险（普通成书保留）
        if (nbt.contains("Book", 10) && bookContainsCommand(nbt.getCompound("Book"))) return true;
        // 刷怪笼：SpawnData 非空，或 SpawnPotentials 列表非空
        // （SpawnData/Potentials 里的实体不受上传实体白名单约束，是绕过"禁止生物"规则的另一条路）
        if (nbt.contains("SpawnData", 10) && !nbt.getCompound("SpawnData").isEmpty()) return true;
        if (nbt.contains("SpawnPotentials", 9) && !nbt.getList("SpawnPotentials", 10).isEmpty()) return true;
        // 试炼刷怪笼（D9 净化兜底）：生成配置任一存在即危险（正常捕获本来就禁捕，见 excludedBlocks）
        if (nbt.contains("spawn_data", 10) && !nbt.getCompound("spawn_data").isEmpty()) return true;
        if (nbt.contains("normal_config", 10) && !nbt.getCompound("normal_config").isEmpty()) return true;
        if (nbt.contains("ominous_config", 10) && !nbt.getCompound("ominous_config").isEmpty()) return true;
        return false;
    }

    /**
     * 清除方块 NBT 中的危险内容（容器物品、告示牌命令点击事件、刷怪笼生成数据），返回副本。
     * 告示牌文字本身被保留，仅移除其中的 {@code run_command}/{@code suggest_command} 点击事件。
     * 原 NBT 不受影响。刷怪笼只清空 {@code SpawnData}/{@code SpawnPotentials}，
     * Delay/MinSpawnDelay/MaxSpawnDelay/SpawnCount 等纯数值字段原样保留。
     *
     * <p>容器是<b>无条件</b>倒空的。次元蓝图（把结构搬走、不可复制）需要保留箱内物品，
     * 走 {@link #stripDangerousBlockNbtKeepContents}。</p>
     */
    public static CompoundTag stripDangerousBlockNbt(CompoundTag nbt) {
        if (nbt == null) return null;
        CompoundTag stripped = nbt.copy();
        // 递归清空所有物品容器（任意键名、任意层级），覆盖其他 mod 的容器与套娃潜影盒
        stripAllItemContainersInPlace(stripped);
        stripped.remove("Items");
        stripped.remove("RecordItem");
        applySafetyStripInPlace(stripped);
        return stripped;
    }

    /**
     * 次元蓝图专用的危险内容清理：安全项照做，<b>容器内容原样保留</b>，返回副本。
     *
     * <p>与 {@link #stripDangerousBlockNbt} 的唯一区别是容器策略：那个无条件倒空一切容器，
     * 这个只在<b>容器里确实混有非法物品时</b>才整体倒掉（策略与驮兽的
     * {@link #sanitizeMobInventoryInPlace} 一致，不做逐格甄别）。</p>
     *
     * <p>存在的理由：次元蓝图是把结构从世界里<b>搬走</b>（{@code clearAfterCapture}），
     * 方块已从原地清除，箱内物品理应跟着走 —— 倒空等于凭空销毁玩家财产。
     * 而其余几类蓝图能反复放置，留着容器内容就是一台复制机，必须走上面那个无条件版本。</p>
     *
     * <p>此前这条路径对非 OP 玩家走的是 {@link #stripDangerousBlockNbt}：
     * {@code stripContents} 那条线虽然为次元蓝图放行了容器，安全线却照样把箱子倒干净，
     * 于是「次元蓝图保留箱内物品」实际只对 OP 成立。</p>
     *
     * <p>告示牌命令、含命令的讲台书、刷怪笼 {@code SpawnData}/{@code SpawnPotentials}
     * 仍然照清 —— 这些是能执行命令 / 绕过生物白名单的真安全项，跟财产无关。</p>
     */
    public static CompoundTag stripDangerousBlockNbtKeepContents(CompoundTag nbt) {
        if (nbt == null) return null;
        CompoundTag stripped = nbt.copy();
        // 含非法物品（含超深嵌套 → containsIllegalItemDeep 按 fail-closed 判危险）才整体倒空
        if (containsIllegalItemDeep(stripped)) {
            stripAllItemContainersInPlace(stripped);
            stripped.remove("Items");
            stripped.remove("RecordItem");
        }
        applySafetyStripInPlace(stripped);
        return stripped;
    }

    /**
     * 两个 {@code stripDangerousBlockNbt*} 共用的安全项清理（就地修改）：
     * 告示牌命令点击事件、含命令的讲台书、刷怪笼生成数据。
     *
     * <p>不含容器处理 —— 容器策略由两个调用方各自决定。</p>
     */
    private static void applySafetyStripInPlace(CompoundTag stripped) {
        for (String side : new String[]{"front_text", "back_text"}) {
            if (stripped.contains(side, 10)) {
                CompoundTag sideTag = stripped.getCompound(side).copy();
                if (sideTag.contains("messages", 9)) {
                    ListTag msgs = sideTag.getList("messages", 8);
                    ListTag cleaned = new ListTag();
                    for (int i = 0; i < msgs.size(); i++) {
                        cleaned.add(StringTag.valueOf(sanitizeSignMessageJson(msgs.getString(i))));
                    }
                    sideTag.put("messages", cleaned);
                }
                stripped.put(side, sideTag);
            }
        }
        for (String key : new String[]{"Text1", "Text2", "Text3", "Text4"}) {
            if (stripped.contains(key, 8)) {
                stripped.putString(key, sanitizeSignMessageJson(stripped.getString(key)));
            }
        }
        // 讲台/书：新旧格式任一页含命令点击事件时，直接移除整本书（保留讲台）
        if (stripped.contains("Book", 10)) {
            CompoundTag book = stripped.getCompound("Book");
            if (bookContainsCommand(book)) {
                stripped.remove("Book");
            }
        }
        stripped.remove("SpawnData");
        stripped.remove("SpawnPotentials");
        // 试炼刷怪笼（D9 净化兜底）：生成配置整段移除
        stripped.remove("spawn_data");
        stripped.remove("normal_config");
        stripped.remove("ominous_config");
    }

    // ── 告示牌命令点击事件过滤（保留文本）──────────────────────────────────

    private static final Gson SIGN_GSON = new Gson();

    /**
     * 文本组件 JSON 的解析/再序列化长度闸门。
     *
     * <p>超过此长度就不再交给 Gson（实测：单串 60,068 字符 / 约 1 万层嵌套即可让
     * {@code toJson} 递归栈溢出，StackOverflowError 是 Error、普通 catch 接不住，
     * 会一路冒泡被网络层吞掉表现为上传卡死）。正常告示牌单行约 384 字符、书页约 1024，
     * 远小于此阈值；</p>
     *
     * <p>超长且含 {@code clickEvent} 字面量的串无法安全解析时按 fail-closed 处理：
     * 检测侧判为有命令、净化侧清空该条文本。不能依赖体积检测兜底——
     * {@code maxBlockEntityNbtBytes} 可配 512~1048576，调大后超长攻击串既不会被判超大
     * 也不会被清 NBT，会原样入库；改为 fail-closed 后则先落入 dangerous 再被净化清空，
     * 仅损失超长行本身，不影响方块与其它行。</p>
     *
     * <p>影响面已被两处的前置 {@code clickEvent} 字面量检查限制：不含该字面量的
     * 正常长文本根本走不到长度闸门。</p>
     */
    private static final int MAX_TEXT_JSON_LEN = 8192;

    private static final String EMPTY_SIGN_TEXT_JSON = "{\"text\":\"\"}";

    /** 判断单条告示牌消息 JSON 是否包含可执行命令的 {@code clickEvent}。 */
    public static boolean hasSignCommandClickEvent(String json) {
        if (json == null || json.isBlank()) return false;
        if (!json.contains("clickEvent")) return false;
        // 超长串无法安全解析，按 fail-closed 判为有命令，宁可误判也不放行（见 MAX_TEXT_JSON_LEN）。
        if (json.length() > MAX_TEXT_JSON_LEN) return true;
        try {
            return findSignRunCommand(JsonParser.parseString(json));
        } catch (Throwable t) {
            // 用 Throwable 不是 Exception：深嵌套 JSON 会让 Gson 递归建树抛
            // StackOverflowError（Error，Exception 接不住）→ 之前直接冒泡被 Forge
            // 网络层静默吞掉，表现为「上传中」卡死且服务端零日志（44 号实测）。
            // 此处捕获并留痕，并与长度闸门同方向按 fail-closed 判为有命令：
            // 走到这里的串长度 <= MAX_TEXT_JSON_LEN（超长的已被闸门拦下），
            // 所以它多半是畸形 JSON 而非超深嵌套。裸 Gson 解析失败不等于
            // Minecraft 的 Component.Serializer 也解析失败 —— 两者注册的
            // TypeAdapter 宽容度不同，判「无命令」放行就是拿这个差异赌运气。
            logJsonAbort("hasSignCommandClickEvent", json.length(), t);
            return true;
        }
    }

    /** 判断讲台 Book（物品结构）的页面是否含命令点击事件。新旧两种物品存储格式都认。 */
    private static boolean bookContainsCommand(CompoundTag book) {
        for (String page : bookPageJsons(book)) {
            if (hasSignCommandClickEvent(page)) return true;
        }
        return false;
    }

    /**
     * 从 Book 物品 NBT 取出全部页面 JSON 字符串，新旧格式都收：
     * 旧 {@code tag.pages}（字符串列表）/ {@code tag.filtered_pages}（compound，值全收），
     * 新 {@code components."minecraft:written_book_content".pages[]} 里的裸字符串、{@code raw}、{@code filtered}。
     * 书与笔（writable_book_content）是纯文本，没有点击事件，不用查。
     */
    private static List<String> bookPageJsons(CompoundTag book) {
        List<String> pages = new ArrayList<>();
        if (book.contains("tag", 10)) {
            CompoundTag tag = book.getCompound("tag");
            if (tag.contains("pages", 9)) {
                ListTag list = tag.getList("pages", 8);
                for (int i = 0; i < list.size(); i++) {
                    pages.add(list.getString(i));
                }
            }
            if (tag.contains("filtered_pages", 10)) {
                CompoundTag fp = tag.getCompound("filtered_pages");
                for (String k : fp.getAllKeys()) {
                    pages.add(fp.getString(k));
                }
            }
        }
        if (book.contains("components", 10)) {
            CompoundTag components = book.getCompound("components");
            if (components.contains("minecraft:written_book_content", 10)) {
                CompoundTag wbc = components.getCompound("minecraft:written_book_content");
                if (wbc.contains("pages", 9)) {
                    ListTag list = wbc.getList("pages", 10);
                    for (int i = 0; i < list.size(); i++) {
                        Tag page = list.get(i);
                        if (page instanceof CompoundTag pg) {
                            if (pg.contains("raw", 8)) pages.add(pg.getString("raw"));
                            if (pg.contains("filtered", 8)) pages.add(pg.getString("filtered"));
                        } else if (page instanceof StringTag st) {
                            pages.add(st.getAsString());
                        }
                    }
                }
            }
        }
        return pages;
    }

    private static boolean findSignRunCommand(JsonElement el) {
        if (el == null) return false;
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("clickEvent") && obj.get("clickEvent").isJsonObject()) {
                JsonObject ce = obj.get("clickEvent").getAsJsonObject();
                if (ce.has("action")) {
                    String action = ce.get("action").getAsString();
                    if (action.equals("run_command") || action.equals("suggest_command")) return true;
                }
            }
            if (obj.has("extra") && obj.get("extra").isJsonArray()) {
                for (JsonElement child : obj.getAsJsonArray("extra")) {
                    if (findSignRunCommand(child)) return true;
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                if (findSignRunCommand(child)) return true;
            }
        }
        return false;
    }

    /**
     * 净化单条告示牌消息 JSON：移除 {@code run_command}/{@code suggest_command} 的
     * {@code clickEvent}（含嵌套 {@code extra}），保留其余文本与格式。
     */
    public static String sanitizeSignMessageJson(String json) {
        if (json == null || json.isBlank()) return json;
        if (!json.contains("clickEvent")) return json;
        // 超长串无法安全净化，按 fail-closed 清空该条文本（见 MAX_TEXT_JSON_LEN）。
        if (json.length() > MAX_TEXT_JSON_LEN) return EMPTY_SIGN_TEXT_JSON;
        try {
            JsonElement root = JsonParser.parseString(json);
            sanitizeSignJson(root);
            return SIGN_GSON.toJson(root);
        } catch (Throwable t) {
            // 同 hasSignCommandClickEvent：净化阶段的解析/序列化同样可能抛。
            // 原来这里原样返回，理由是「随后会被 oversized 整条清 NBT」——
            // 那条论证依赖 maxBlockEntityNbtBytes 的默认值，阈值可配，已作废。
            // 改为与检测侧一致清空该条：检测判危险、净化就必须真的清掉，
            // 否则报告说危险而文件里原样留着，两边对不上。
            logJsonAbort("sanitizeSignMessageJson", json.length(), t);
            return EMPTY_SIGN_TEXT_JSON;
        }
    }

    /**
     * [诊断] 文本组件 JSON 解析被异常中止时的留痕：只记异常类型/消息与栈顶几帧，
     * 不整段打栈 —— 深度解析的 StackOverflowError 栈有上万帧，全打会刷爆日志。
     */
    private static void logJsonAbort(String where, int jsonLen, Throwable t) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage()));
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) {
                sb.append(" | ").append(st[i].toString());
            }
            com.xioyim.spatialblueprint.SpatialBlueprintMod.LOGGER.warn(
                    "[BlueprintSanitizer] {} aborted JSON parse (len={}): {}", where, jsonLen, sb);
        } catch (Throwable ignored) {
            // 日志本身出问题不能影响主流程
        }
    }

    private static void sanitizeSignJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) return;
        JsonObject obj = el.getAsJsonObject();
        if (obj.has("clickEvent") && obj.get("clickEvent").isJsonObject()) {
            JsonObject ce = obj.get("clickEvent").getAsJsonObject();
            if (ce.has("action")) {
                String action = ce.get("action").getAsString();
                if (action.equals("run_command") || action.equals("suggest_command")) {
                    obj.remove("clickEvent");
                }
            }
        }
        if (obj.has("extra") && obj.get("extra").isJsonArray()) {
            for (JsonElement child : obj.getAsJsonArray("extra")) {
                sanitizeSignJson(child);
            }
        }
    }

    /**
     * 无条件清除所有方块的容器物品并净化告示牌（不判断是否"危险"，直接处理）。
     * 容器物品清空；告示牌保留文本、仅移除命令点击事件。
     *
     * <p>用于上传等不受信任的客户端数据源：不能依赖"先检测再决定是否清空"，
     * 因为上传数据整体不可信，容器内容一律强制清空，告示牌命令一律强制移除。</p>
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

    // ── 上传检测报告：扫描 BlueprintData 本身 ────────────────────────────

    /**
     * 上传文件的扫描结果。坐标是<b>蓝图内相对坐标</b>，不是世界坐标 ——
     * 这片结构还不在世界里，玩家跑不过去看。
     *
     * @param illegalBlocks   非法方块（命令方块等），净化时整块删除
     * @param dangerousBlocks 方块本身合法但 NBT 危险（容器有非法物品、告示牌命令、蜂箱等），
     *                        净化时只清 NBT，保留方块
     * @param droppedEntities 白名单之外的实体，净化时整条丢弃
     * @param oversizedNbt    单体 NBT 超预算的方块，净化时清 NBT、保留方块
     * @param conversions     命中的批量转换规则
     * @param freeBlocks      免费方块注册 ID
     */
    public record UploadScan(List<ScanEntry> illegalBlocks,
                             List<ScanEntry> dangerousBlocks,
                             List<ScanEntity> droppedEntities,
                             List<ScanEntry> oversizedNbt,
                             List<ScanEntry> deepNbt,
                             List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions,
                             List<String> freeBlocks,
                             int illegalTotal,
                             int dangerousTotal,
                             int oversizedTotal,
                             int deepNbtTotal,
                             int droppedTotal,
                             int conversionsTotal,
                             int freeTotal) {

        public boolean isClean() {
            return illegalBlocks.isEmpty() && dangerousBlocks.isEmpty()
                    && droppedEntities.isEmpty() && oversizedNbt.isEmpty() && deepNbt.isEmpty();
        }

        public int total() {
            return illegalBlocks.size() + dangerousBlocks.size() + droppedEntities.size()
                    + oversizedNbt.size() + deepNbt.size();
        }
    }

    /** 扫描结果的一条（方块）：相对坐标 + 注册 ID。 */
    public record ScanEntry(int x, int y, int z, String id) {}

    /**
     * 扫描结果的一条（特殊实体）：相对坐标 + kind + 实体/物品 ID + 是否带装备。
     * kind 取 {@code armor_stand} / {@code mob} / {@code item_frame} 之一，
     * 与捕获侧 {@code C2S_RequestIllegalBlocksMessage} 的特殊实体分类一致。
     */
    public record ScanEntity(int x, int y, int z, String kind, String entityId, String itemId,
                             boolean hasEquipment) {}

    /** 单个分类最多记录多少条 —— 报告界面显示不下更多，网络包也不该被撑爆。 */
    public static final int MAX_SCAN_ENTRIES = 200;

    /**
     * 扫描一份待上传的 {@link BlueprintData}，产出检测报告所需的全部数据。
     *
     * <p>与 {@code C2S_RequestIllegalBlocksMessage} 的区别：那个遍历<b>世界坐标</b>去
     * {@code level.getBlockState()}，用于捕获前的选区检查；本方法遍历的是
     * <b>蓝图数据本身</b>，用于上传前的文件检查 —— 结构还不在世界里。</p>
     *
     * @param data           反序列化后的蓝图
     * @param perEntryBudget 单个方块实体的 NBT 字节预算，用于识别超大 NBT 方块
     */
    public static UploadScan scanBlueprintData(BlueprintData data, long perEntryBudget, int maxDepth) {
        List<ScanEntry> illegal   = new ArrayList<>();
        List<ScanEntry> dangerous = new ArrayList<>();
        List<ScanEntry> oversized = new ArrayList<>();
        List<ScanEntry> deepNbt   = new ArrayList<>();
        List<ScanEntity> dropped  = new ArrayList<>();
        int[] illegalTotal   = new int[1];
        int[] dangerousTotal = new int[1];
        int[] oversizedTotal = new int[1];
        int[] deepNbtTotal   = new int[1];

        scanBlockList(data.normalBlocks,    illegal, dangerous, oversized, deepNbt,
                illegalTotal, dangerousTotal, oversizedTotal, deepNbtTotal, perEntryBudget, maxDepth);
        scanBlockList(data.dependentBlocks, illegal, dangerous, oversized, deepNbt,
                illegalTotal, dangerousTotal, oversizedTotal, deepNbtTotal, perEntryBudget, maxDepth);
        scanBlockList(data.lateBlocks,      illegal, dangerous, oversized, deepNbt,
                illegalTotal, dangerousTotal, oversizedTotal, deepNbtTotal, perEntryBudget, maxDepth);

        int droppedTotal = 0;
        for (BlueprintData.EntityEntry e : data.entities) {
            if (!isEntityAllowed(e)) {
                droppedTotal++;
                if (dropped.size() < MAX_SCAN_ENTRIES) {
                    dropped.add(scanEntityFor(e));
                }
            }
        }

        List<S2C_IllegalBlocksListMessage.ConversionEntry> conversions = new ArrayList<>();
        List<String> freeBlocks = new ArrayList<>();
        BulkMaterialConverter.collectConversions(countBlocks(data), conversions, freeBlocks);
        int conversionsTotal = conversions.size();
        int freeTotal = freeBlocks.size();

        return new UploadScan(illegal, dangerous, dropped, oversized, deepNbt, conversions, freeBlocks,
                illegalTotal[0], dangerousTotal[0], oversizedTotal[0], deepNbtTotal[0],
                droppedTotal, conversionsTotal, freeTotal);
    }

    /** 被丢弃的实体 → 特殊实体条目，按三种 kind 复刻捕获侧的分类。 */
    private static ScanEntity scanEntityFor(BlueprintData.EntityEntry e) {
        String type = e.type == null ? "" : e.type;
        if ("minecraft:armor_stand".equals(type)) {
            return new ScanEntity(e.tileX, e.tileY, e.tileZ,
                    "armor_stand", "minecraft:armor_stand", "", armorStandHasEquipment(e.nbt));
        }
        if ("minecraft:item_frame".equals(type) || "minecraft:glow_item_frame".equals(type)) {
            return new ScanEntity(e.tileX, e.tileY, e.tileZ,
                    "item_frame", type, framedItemId(e.nbt), false);
        }
        return new ScanEntity(e.tileX, e.tileY, e.tileZ,
                "mob", type, "", false);
    }

    /** 盔甲架 NBT 里 ArmorItems / HandItems 是否非空（带装备）。 */
    private static boolean armorStandHasEquipment(CompoundTag nbt) {
        if (nbt == null) return false;
        return nonEmptyItemList(nbt, "ArmorItems") || nonEmptyItemList(nbt, "HandItems");
    }

    private static boolean nonEmptyItemList(CompoundTag nbt, String key) {
        if (!nbt.contains(key, 9)) return false;
        return !nbt.getList(key, 10).isEmpty();
    }

    /** 展示框 NBT 里框内物品的注册 ID，无物品时为空串。 */
    private static String framedItemId(CompoundTag nbt) {
        if (nbt == null || !nbt.contains("Item", 10)) return "";
        return nbt.getCompound("Item").getString("id");
    }

    /**
     * 统计一份蓝图里每种方块的出现次数（三个方块列表全算），
     * 供批量转换 / 免费方块判定使用。
     */
    public static Map<String, Integer> countBlocks(BlueprintData data) {
        Map<String, Integer> counts = new HashMap<>();
        for (List<BlueprintData.BlockEntry> list : List.of(
                data.normalBlocks, data.dependentBlocks, data.lateBlocks)) {
            for (BlueprintData.BlockEntry entry : list) {
                ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock());
                if (rl != null) counts.merge(rl.toString(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static void scanBlockList(List<BlueprintData.BlockEntry> list,
                                       List<ScanEntry> illegal, List<ScanEntry> dangerous,
                                       List<ScanEntry> oversized, List<ScanEntry> deepNbt,
                                       int[] illegalTotal, int[] dangerousTotal,
                                       int[] oversizedTotal, int[] deepNbtTotal,
                                       long perEntryBudget, int maxDepth) {
        for (BlueprintData.BlockEntry entry : list) {
            if (isIllegalBlock(entry.state)) {
                illegalTotal[0]++;
                if (illegal.size() < MAX_SCAN_ENTRIES) {
                    ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock());
                    illegal.add(new ScanEntry(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(),
                            rl == null ? "?" : rl.toString()));
                }
                continue;   // 已经算非法方块，不再重复计入危险 NBT
            }
            if (entry.nbt != null && hasDangerousBlockNbt(entry.nbt)) {
                dangerousTotal[0]++;
                if (dangerous.size() < MAX_SCAN_ENTRIES) {
                    ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock());
                    dangerous.add(new ScanEntry(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(),
                            rl == null ? "?" : rl.toString()));
                }
                continue;
            }
            if (entry.nbt != null && effectiveNbtBytes(entry.nbt, perEntryBudget) >= perEntryBudget) {
                oversizedTotal[0]++;
                if (oversized.size() < MAX_SCAN_ENTRIES) {
                    ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock());
                    oversized.add(new ScanEntry(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(),
                            rl == null ? "?" : rl.toString()));
                }
                continue;
            }
            if (entry.nbt != null && exceedsMaxDepth(entry.nbt, maxDepth)) {
                deepNbtTotal[0]++;
                if (deepNbt.size() < MAX_SCAN_ENTRIES) {
                    ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(entry.state.getBlock());
                    deepNbt.add(new ScanEntry(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(),
                            rl == null ? "?" : rl.toString()));
                }
            }
        }
    }

    /**
     * 按「净化」语义处理一份待上传数据：非法方块<b>整块删除</b>，
     * 危险 NBT 就地清空（保留方块本身），单体超预算的 NBT 整条清空（保留方块本身），
     * 白名单外的实体丢弃。
     *
     * <p>与 {@link #stripContainerAndSignNbtInPlace} 的区别：那个只清 NBT、不删方块；
     * 本方法会真的把非法方块从列表里移除，对应报告界面上的「净化并上传」。</p>
     *
     * @param perEntryBudget 单个方块实体的 NBT 字节预算；超出者清 NBT 保留方块
     */
    public static void sanitizeForUploadInPlace(BlueprintData data, long perEntryBudget, int maxDepth) {
        removeIllegalBlocks(data.normalBlocks);
        removeIllegalBlocks(data.dependentBlocks);
        removeIllegalBlocks(data.lateBlocks);
        // 顺序不能反：容器内容在这里必被清空，先称重等于拿注定要删的字节定罪。
        // 见 effectiveNbtBytes 的注释。
        stripContainerAndSignNbtInPlace(data);
        stripOversizedNbtInPlace(data, perEntryBudget);
        stripDeepNbtInPlace(data, maxDepth);
        filterEntitiesInPlace(data);
        // 实体内容净化：画只保留 id+variant；展示框危险物品丢弃 id，安全物品丢弃 tag
        for (BlueprintData.EntityEntry entry : data.entities) {
            if (entry.nbt == null) continue;
            if ("minecraft:painting".equals(entry.type)) {
                sanitizePaintingNbt(entry.nbt);
            } else {
                sanitizeItemFrameContentInPlace(entry.nbt);
            }
        }
    }

    /**
     * 单体 NBT 超预算的方块：清 NBT、保留方块。判断口径与
     * {@link #checkNbtBudget} 及扫描报告一致（基于原始 NBT 估算字节数）。
     */
    public static void stripOversizedNbtInPlace(BlueprintData data, long perEntryBudget) {
        stripOversizedList(data.normalBlocks, perEntryBudget);
        stripOversizedList(data.dependentBlocks, perEntryBudget);
        stripOversizedList(data.lateBlocks, perEntryBudget);
    }

    private static void stripOversizedList(List<BlueprintData.BlockEntry> list, long perEntryBudget) {
        for (int i = 0; i < list.size(); i++) {
            BlueprintData.BlockEntry entry = list.get(i);
            if (entry.nbt == null) continue;
            if (effectiveNbtBytes(entry.nbt, perEntryBudget) >= perEntryBudget) {
                list.set(i, new BlueprintData.BlockEntry(entry.pos, entry.state, null));
            }
        }
    }

    /**
     * 深层 NBT 方块：单体 NBT 嵌套深度超阈（迭代 BFS 判断），净化时清 NBT、保留方块。
     * 口径与扫描报告一致：对每个方块实体 NBT 单独测深度，深度 &gt; maxDepth 视为深层。
     */
    public static void stripDeepNbtInPlace(BlueprintData data, int maxDepth) {
        stripDeepList(data.normalBlocks, maxDepth);
        stripDeepList(data.dependentBlocks, maxDepth);
        stripDeepList(data.lateBlocks, maxDepth);
    }

    private static void stripDeepList(List<BlueprintData.BlockEntry> list, int maxDepth) {
        for (int i = 0; i < list.size(); i++) {
            BlueprintData.BlockEntry entry = list.get(i);
            if (entry.nbt == null) continue;
            if (exceedsMaxDepth(entry.nbt, maxDepth)) {
                list.set(i, new BlueprintData.BlockEntry(entry.pos, entry.state, null));
            }
        }
    }

    private static void removeIllegalBlocks(List<BlueprintData.BlockEntry> list) {
        list.removeIf(e -> isIllegalBlock(e.state));
    }

    // ── Phase 2：实体白名单 ──────────────────────────────────────────────

    /** 上传时允许保留的实体类型。其余一律丢弃（含所有生物）。 */
    public static final Set<String> ENTITY_WHITELIST = Set.of(
            "minecraft:painting",
            "minecraft:item_frame",
            "minecraft:glow_item_frame"
    );

    /** 可捕获生物白名单：仅这些生物可被蓝图捕获（创造模式 & OP 强制时）。 */
    public static final Set<String> ALLOWED_MOB_ENTITIES = Set.of(
            "minecraft:cow", "minecraft:mooshroom", "minecraft:sheep", "minecraft:pig", "minecraft:chicken",
            "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:llama", "minecraft:trader_llama",
            "minecraft:villager", "minecraft:wandering_trader", "minecraft:cat", "minecraft:wolf", "minecraft:rabbit",
            "minecraft:fox", "minecraft:bee", "minecraft:goat", "minecraft:axolotl", "minecraft:frog",
            "minecraft:turtle", "minecraft:panda", "minecraft:camel", "minecraft:sniffer", "minecraft:parrot",
            "minecraft:ocelot", "minecraft:bat", "minecraft:pufferfish", "minecraft:cod", "minecraft:salmon",
            "minecraft:tropical_fish", "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin",
            "minecraft:polar_bear", "minecraft:strider", "minecraft:hoglin", "minecraft:zoglin"
    );

    // ── 实体物品非法判定（捕获预扫 / 扫描报告共用，口径必须一致）────────────────

    /**
     * 该实体身上（任意层级、任意键名）是否带着非法物品。
     *
     * <p>把实体整份序列化后交给 {@link #containsIllegalItemDeep} —— 展示框的 {@code Item}、
     * 盔甲架的 {@code ArmorItems}/{@code HandItems}、驮兽的 {@code Items}/{@code SaddleItem}/
     * {@code DecorItem}，以及手里拿着的潜影盒<b>里面</b>装的东西，全部一次覆盖。
     * 以前扫描报告对盔甲架只看槽位物品的 id、还只查方块表，刷怪蛋和套娃潜影盒都漏。</p>
     *
     * <p>{@code UUID} 之类的非物品键不影响判定（只看键名为 {@code id} 的字符串）。</p>
     */
    public static boolean entityHasIllegalItems(net.minecraft.world.entity.Entity entity) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.save(nbt)) return false;
        return containsIllegalItemDeep(nbt);
    }

    /**
     * 在选区内找第一个带非法物品的实体，找到返回 {@code "<类型>@<坐标>"} 供日志用，没有返回 null。
     *
     * <p>覆盖面与捕获路径实际会收进蓝图的实体<b>完全一致</b>：展示框、盔甲架、
     * {@link #ALLOWED_MOB_ENTITIES} 里的生物。画没有物品，拴绳结没有物品，胶水没有物品，
     * 不在扫描范围内。</p>
     *
     * <p>次元蓝图捕获用它做整单拒收的预扫；扫描报告 {@code C2S_RequestIllegalBlocksMessage}
     * 逐实体分类时用的是同一个 {@link #entityHasIllegalItems} —— 报告里标危险的这里必拒，
     * 报告没标的这里必放，两边对不上玩家就没法自己清理。</p>
     */
    public static String findEntityWithIllegalItems(net.minecraft.server.level.ServerLevel level,
                                                    net.minecraft.world.phys.AABB box) {
        for (net.minecraft.world.entity.decoration.ItemFrame f :
                level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, box, e -> true)) {
            if (entityHasIllegalItems(f)) return "item_frame@" + f.blockPosition();
        }
        for (net.minecraft.world.entity.decoration.ArmorStand s :
                level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, box, e -> true)) {
            if (entityHasIllegalItems(s)) return "armor_stand@" + s.blockPosition();
        }
        for (net.minecraft.world.entity.LivingEntity le :
                level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                        e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).toString();
            if (!ALLOWED_MOB_ENTITIES.contains(id)) continue;
            if (entityHasIllegalItems(le)) return id + "@" + le.blockPosition();
        }
        return null;
    }

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

    /**
     * 检测展示框实体 NBT 中的物品是否非法。
     *
     * <p>口径就是 {@link #isIllegalItemId} —— 硬编码纯物品底线 + {@code _spawn_egg} 后缀
     * + 配置 {@code excludedItems} + 同名方块被禁。</p>
     *
     * <p>⚠ 这里以前只查方块表（{@code BuiltInRegistries.BLOCK.get(id)} 再问
     * {@code EXCLUDED_BLOCKS}），于是<b>纯物品类的非法项全部漏过</b>：刷怪蛋、
     * 命令方块矿车、调试棒、知识之书在方块表里查出来都是 AIR，一律判「安全」。
     * 方块容器那边走的是 {@link #containsIllegalItemDeep}（全口径），
     * 同一个刷怪蛋放箱子里会被拦、挂展示框上却能过 —— 两条防线口径必须一致。</p>
     */
    public static boolean isItemFrameItemDangerous(CompoundTag entityNbt) {
        if (!entityNbt.contains("Item", 10)) return false;
        CompoundTag item = entityNbt.getCompound("Item");
        String itemId = item.getString("id");
        if (itemId.isBlank()) return false;
        if (isIllegalItemId(itemId)) return true;
        ResourceLocation loc = ResourceLocation.tryParse(itemId);
        if (loc == null) return false;
        Block block = BuiltInRegistries.BLOCK.get(loc);
        return block != Blocks.AIR && BlueprintConfig.isExcluded(block);
    }

    /**
     * 展示框物品内容净化（用于不受信任的上传数据源）：
     * 危险物品 → 连 id 一并丢弃；安全物品 → 重建为 {id, count:int}（count 取 count → Count → 1），
     * components 与 tag 都丢（防止用看似安全的物品外壳夹带危险 NBT）。
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
        int count = item.contains("count") ? item.getInt("count")
                : (item.contains("Count") ? item.getByte("Count") : 1);
        cleaned.putInt("count", Math.max(1, count));
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
     *
     * @return 实际丢弃的方块数（供上传报告展示）
     */
    public static int filterOutOfRangeBlocksInPlace(BlueprintData data) {
        int dropped = 0;
        dropped += filterBlockRange(data.normalBlocks);
        dropped += filterBlockRange(data.dependentBlocks);
        dropped += filterBlockRange(data.lateBlocks);
        return dropped;
    }

    private static int filterBlockRange(List<BlueprintData.BlockEntry> list) {
        int before = list.size();
        list.removeIf(e -> e.pos.getX() < 0 || e.pos.getX() >= MAX_RELATIVE_COORD
                || e.pos.getY() < 0 || e.pos.getY() >= MAX_RELATIVE_COORD
                || e.pos.getZ() < 0 || e.pos.getZ() >= MAX_RELATIVE_COORD);
        return before - list.size();
    }

    // ── 非法方块判定：只读配置 excludedBlocks ───────────────────────────────
    // 方块禁用完全交给配置（BlueprintConfig.EXCLUDED_BLOCKS），不再保留代码硬编码兜底。
    // 需要封禁方块时在 /sb config → 通用 → 编辑禁捕方块 里维护即可。

    /** 综合判断：配置 {@code EXCLUDED_BLOCKS} 命中即视为非法（服务端权威）。 */
    public static boolean isIllegalBlock(BlockState state) {
        return BlueprintConfig.isExcluded(state.getBlock());
    }

    /** {@link #isIllegalBlock(BlockState)} 的注册名字符串版本（用于材料清单等只有 ID 没有 BlockState 的场景）。 */
    public static boolean isIllegalBlockId(String registryId) {
        return BlueprintConfig.isExcludedId(registryId);
    }

    /**
     * 剔除材料清单里的非法方块条目（奖励蓝图 ✎ 提取并载入材料时用）。
     *
     * <p>判定与 {@code C2S_SaveMaterialListMessage} 的保存校验同源：复合键先经
     * {@link MaterialKey#idOf} 还原注册 ID，再走 {@link #isIllegalBlockId}；命中的条目
     * 从 {@code blockCounts} 与 {@code keyTags} 一并删除。过滤作用于最终清单
     * （即替换表 / 批量转换之后），不是方块表。</p>
     *
     * @return 删掉的条目数（聊天栏 / 日志用）
     */
    public static int removeIllegalMaterialsInPlace(MaterialList ml) {
        if (ml == null) return 0;
        int removed = 0;
        java.util.Iterator<String> it = ml.blockCounts.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (isIllegalBlockId(MaterialKey.idOf(key))) {
                ml.keyTags.remove(key);
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * 上传投影模板专属字段规则（§4.3 数值范围 + 强制清空工程蓝图专属字段）：
     *
     * <ul>
     *   <li>{@code animDuration} 超出 [0,300] 归 0（回退到配置文件自动分档）</li>
     *   <li>{@code fixedDimension} / {@code fixedStartPos} / {@code fixedEndPos}：
     *       这些是工程蓝图专属字段，投影模板用不到，一律清空，防止无意义数据混入 dat 文件</li>
     * </ul>
     */
    public static void enforceTemplateFieldRules(BlueprintData data, boolean isOp) {
        if (data.animDuration < 0 || data.animDuration > 300) {
            data.animDuration = 0;
        }

        data.fixedDimension = "";
        data.fixedStartPos  = null;
        data.fixedEndPos    = null;
    }
}
