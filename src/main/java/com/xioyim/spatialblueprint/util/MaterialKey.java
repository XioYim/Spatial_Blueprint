package com.xioyim.spatialblueprint.util;

import com.xioyim.spatialblueprint.BlueprintConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 材料复合键：把「注册 ID + 完整 NBT」压缩成一个稳定的字符串键。
 *
 * <p>格式：</p>
 * <ul>
 *   <li>无 NBT（绝大多数方块）：{@code "minecraft:stone"} —— 与旧的纯 ID 键完全一致</li>
 *   <li>有 NBT（附魔装备、带耐久的工具等）：{@code "minecraft:diamond_sword#a3f2c1d4e5b6"}</li>
 * </ul>
 *
 * <p>后缀是规范化 NBT 的 48 位 FNV-1a 摘要。不同附魔、不同耐久的同种物品会得到不同的键，
 * 因此在材料清单与材料空间中各自独立计数；GUI 也会自然地分成多行显示。</p>
 *
 * <p>规范化会剔除 {@link BlueprintConfig#MATERIAL_SPACE_IGNORED_NBT_KEYS} 中列出的顶层字段
 * （默认 {@code RepairCost}）。铁砧修理代价不同的同款附魔装备因此仍视为同一种材料，
 * 否则玩家会遇到「看起来一模一样的剑就是存不进去」。</p>
 */
public final class MaterialKey {

    /** 分隔符。ResourceLocation 不允许出现 '#'，因此不会与注册 ID 冲突。 */
    public static final char SEP = '#';

    private MaterialKey() {}

    // ── 构建 ──────────────────────────────────────────────────────────────

    /** 由注册 ID + 原始 tag 构建复合键；tag 规范化后为空则返回纯 ID。 */
    public static String make(String id, @Nullable CompoundTag rawTag) {
        CompoundTag norm = normalize(rawTag);
        if (norm == null || norm.isEmpty()) return id;
        return id + SEP + digest(canonical(norm));
    }

    /**
     * 由 ItemStack 构建复合键（D8：按组件补丁摘要）。
     *
     * <p>补丁的 NBT 编码与 {@code ItemStack.save} 写出的 {@code components} 子标签是同一个
     * （{@link DataComponentPatch#CODEC} + provider 的序列化上下文），因此实体身上的物品
     * 与玩家手里的物品会算出同一个键。补丁为空 → 纯注册 ID。</p>
     */
    public static String of(ItemStack stack, HolderLookup.Provider provider) {
        if (stack.isEmpty()) return "";
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        return make(id, encodePatch(stack.getComponentsPatch(), provider));
    }

    /** 组件补丁 → 与 {@code ItemStack.save} 的 components 子标签同编码的 CompoundTag；空补丁 → null。 */
    @Nullable
    private static CompoundTag encodePatch(DataComponentPatch patch, HolderLookup.Provider provider) {
        if (patch.isEmpty()) return null;
        Tag encoded = DataComponentPatch.CODEC
                .encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), patch)
                .result().orElse(null);
        return encoded instanceof CompoundTag ct ? ct : null;
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /** 从复合键取回注册 ID。纯 ID 键原样返回。 */
    public static String idOf(String key) {
        int i = key.indexOf(SEP);
        return i < 0 ? key : key.substring(0, i);
    }

    /** 该键是否带 NBT 后缀。 */
    public static boolean hasTag(String key) {
        return key.indexOf(SEP) >= 0;
    }

    // ── 规范化 ────────────────────────────────────────────────────────────

    /**
     * 复制一份 tag 并剔除忽略字段（仅顶层），再递归剔除「有和没有一个样」的空结构。
     * 返回 {@code null} 表示该 tag 等价于「无 NBT」。
     */
    @Nullable
    public static CompoundTag normalize(@Nullable CompoundTag rawTag) {
        if (rawTag == null || rawTag.isEmpty()) return null;
        CompoundTag copy = rawTag.copy();
        for (String ignored : ignoredKeys()) {
            copy.remove(ignored);
        }
        pruneNoOps(copy);
        return copy.isEmpty() ? null : copy;
    }

    /**
     * 递归剔除对物品毫无影响的空结构：空 Compound、空 List，以及方块实体标签里
     * 那个与物品 ID 重复的 {@code id} 字段。
     *
     * <p>同一个空潜影盒在游戏里有好几种等价写法 —— 直接合成出来的没有补丁，
     * 从世界里挖起来的带着 {@code {minecraft:block_entity_data:{Items:[],id:"minecraft:shulker_box"}}}。
     * 不做归一化的话两者算出的复合键不同，玩家会遇到「手上明明是同一个空潜影盒，
     * 就是存不进材料清单」。空容器如此，{@code display:{}}、{@code Enchantments:[]}
     * 这类同样是有和没有完全等价。</p>
     *
     * <p>补丁里「移除某组件」的标记键（形如 {@code !minecraft:xxx}，值为空 compound）
     * <b>不剔除</b> —— 它表示「这个物品没有该默认组件」，是真差异。</p>
     */
    private static void pruneNoOps(CompoundTag tag) {
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (key.startsWith("!")) continue;
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                // BlockEntityTag.id（旧）与 minecraft:block_entity_data.id 恒等于该物品对应的
                // 方块实体类型，留着只会制造假差异
                if (BLOCK_ENTITY_TAG.equals(key) || BLOCK_ENTITY_DATA.equals(key)) compound.remove("id");
                pruneNoOps(compound);
                if (compound.isEmpty()) tag.remove(key);
            } else if (child instanceof ListTag list) {
                // 列表元素本身还可能嵌着容器（潜影盒套潜影盒）
                for (Tag element : list) {
                    if (element instanceof CompoundTag c) pruneNoOps(c);
                }
                if (list.isEmpty()) tag.remove(key);
            }
        }
    }

    /** 容器 / 告示牌等方块物品存放方块实体数据的键（旧格式）。 */
    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";

    /** 1.21 的方块实体数据组件键；与旧 {@code BlockEntityTag} 同样剔除重复的 {@code id}。 */
    private static final String BLOCK_ENTITY_DATA = "minecraft:block_entity_data";

    private static List<String> ignoredKeys() {
        try {
            List<? extends String> cfg = BlueprintConfig.MATERIAL_SPACE_IGNORED_NBT_KEYS.get();
            if (cfg != null) return new ArrayList<>(cfg);
        } catch (Exception ignored) {
            // 配置尚未加载（注册期 / 客户端早期）——退回默认值
        }
        return List.of("RepairCost", "minecraft:repair_cost");
    }

    // ── 摘要 ──────────────────────────────────────────────────────────────

    /**
     * 生成与键顺序无关的规范字符串。
     * CompoundTag 内部是 HashMap，直接 {@code toString()} 的字段顺序不保证稳定，
     * 因此这里对每一层的键名排序后再拼接。
     */
    private static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (String k : keys) {
                Tag child = compound.get(k);
                if (child == null) continue;
                sb.append(k).append(':').append(canonical(child)).append(',');
            }
            return sb.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            StringBuilder sb = new StringBuilder("[");
            for (Tag child : list) {
                sb.append(canonical(child)).append(',');
            }
            return sb.append(']').toString();
        }
        return tag.toString();
    }

    /** FNV-1a 64 位摘要，取低 48 位输出为 12 位十六进制。 */
    private static String digest(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return String.format("%012x", hash & 0xFFFFFFFFFFFFL);
    }
}
