package com.xioyim.spatialblueprint.util;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import javax.annotation.Nullable;

/**
 * D2 数据升级（SBN-011，1.21.1 独有）：把 1.20.1 格式的数据载入即升级为 1.21 格式。
 *
 * <p>纯函数：不读配置、不读文件，服务端 / 客户端都可调。四个升级函数直接调
 * {@code DataFixer.update}（DataFixTypes 没有 BLOCK_STATE / BLOCK_ENTITY /
 * ENTITY / ITEM_STACK 这四种的包装，J44；ITEM_STACK 的写法与 SBN-004 t04 同）。
 * 物品那半边（手上的旧蓝图物品）由 {@code LegacyItemMigration} 负责，这里只做数据那半边。</p>
 *
 * <p>失败语义：修复器抛异常、结果不是 CompoundTag、无 id（方块实体 / 实体靠 id 选修复器）
 * 一律返回原 tag 的 copy，不抛到调用方外；调用方用 {@code tryFix*} 变体累计未能升级条目数，
 * 在 deserialize / load 结束时记一条 WARN（不逐条刷日志）。一个坏条目不能让整份蓝图读不出来。</p>
 */
public final class LegacyDataFixer {

    private LegacyDataFixer() {}

    /** 1.20.1 的 MinecraftDataVersion（BlueprintData.serialize 当年固定写的值）。 */
    public static final int LEGACY_DEFAULT = 3465;

    /** 当前版本的 DataVersion。 */
    public static int current() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /**
     * 读到的版本号 → 生效版本。缺键（记 0）视为 1.20.1：本 mod 只有 1.20.1 一条旧线。
     */
    public static int effectiveFrom(int readVersion) {
        return readVersion <= 0 ? LEGACY_DEFAULT : readVersion;
    }

    /**
     * 是否需要升级。from ≥ current 一律不动：不降级，比当前还新的数据原样保留。
     */
    public static boolean needsFix(int from) {
        return from < current();
    }

    /** 单条升级结果：tag 为可用结果（失败时是原 tag 的 copy），ok 为 false 表示未能升级。 */
    public record FixResult(CompoundTag tag, boolean ok) {}

    /** 材料键迁移结果：key 为新键（失败时是原键），components 为新键对应的规范化新 components（无则 null）。 */
    public record MigratedKey(String key, @Nullable CompoundTag components, boolean ok) {}

    public static CompoundTag fixBlockState(CompoundTag tag, int from) {
        return tryFixBlockState(tag, from).tag();
    }

    public static CompoundTag fixBlockEntity(CompoundTag tag, int from) {
        return tryFixBlockEntity(tag, from).tag();
    }

    public static CompoundTag fixEntity(CompoundTag tag, int from) {
        return tryFixEntity(tag, from).tag();
    }

    public static CompoundTag fixItemStack(CompoundTag tag, int from) {
        return tryFixItemStack(tag, from).tag();
    }

    public static FixResult tryFixBlockState(CompoundTag tag, int from) {
        if (!needsFix(from)) return new FixResult(tag, true);
        return runUpdate(References.BLOCK_STATE, tag, from);
    }

    public static FixResult tryFixBlockEntity(CompoundTag tag, int from) {
        if (!needsFix(from)) return new FixResult(tag, true);
        if (!tag.contains("id", Tag.TAG_STRING)) return new FixResult(tag, false);
        return runUpdate(References.BLOCK_ENTITY, tag, from);
    }

    public static FixResult tryFixEntity(CompoundTag tag, int from) {
        if (!needsFix(from)) return new FixResult(tag, true);
        if (!tag.contains("id", Tag.TAG_STRING)) return new FixResult(tag, false);
        return runUpdate(References.ENTITY, tag, from);
    }

    public static FixResult tryFixItemStack(CompoundTag tag, int from) {
        if (!needsFix(from)) return new FixResult(tag, true);
        return runUpdate(References.ITEM_STACK, tag, from);
    }

    private static FixResult runUpdate(DSL.TypeReference type, CompoundTag tag, int from) {
        CompoundTag in = tag.copy();
        try {
            Tag out = DataFixers.getDataFixer()
                    .update(type, new Dynamic<>(NbtOps.INSTANCE, in), from, current())
                    .getValue();
            if (out instanceof CompoundTag upgraded) return new FixResult(upgraded, true);
            return new FixResult(in, false);
        } catch (Throwable t) {
            return new FixResult(in, false);
        }
    }

    /**
     * 材料键迁移（D8 遗留）：1.20.1 按旧物品 tag 算摘要的键（{@code id#摘要}，keyTags 存旧 tag）
     * → 按新 components 算的 D8 键。
     *
     * <p>纯 ID 键、或带 '#' 但 legacyTag 为 null 的键：只做物品改名（如 grass → short_grass，
     * 组 {id, Count:1b} 走 fixItemStack 取新 id），摘要后缀原样保留；
     * 否则组 1.20.1 物品（{id, Count:1b, tag:legacyTag}，C8 / K8 口径）走 fixItemStack，
     * 取结果的 id 与 components（缺则 null），按 {@link MaterialKey#make} 算新键。
     * 附带返回规范化后的新 components，给调用方写 keyTags / tagRegistry。</p>
     */
    public static MigratedKey migrateMaterialKey(String key, @Nullable CompoundTag legacyTag, int from) {
        if (!needsFix(from) || key.isBlank()) {
            return new MigratedKey(key, null, true);
        }
        String id = MaterialKey.idOf(key);
        CompoundTag probe = new CompoundTag();
        probe.putString("id", id);
        probe.putByte("Count", (byte) 1);
        if (!MaterialKey.hasTag(key) || legacyTag == null) {
            // 只改名：修复器失败 / 无 id 时原样（纯 ID 键本就不依赖 tag，不计未升级）
            String newId = fixItemStack(probe, from).getString("id");
            if (newId.isEmpty() || newId.equals(id)) return new MigratedKey(key, null, true);
            return new MigratedKey(newId + key.substring(id.length()), null, true);
        }
        probe.put("tag", legacyTag.copy());
        CompoundTag fixed = fixItemStack(probe, from);
        // 成功 = 出了 components，或旧 tag 被吃掉（转成空补丁→纯 ID 键）；
        // 失败 = 旧 tag 原样残留且没有 components（修复器没转掉），调用方保留原键并计数。
        // 注意：修复器可能同时保留 tag 与 components（部分转换），以 components 为准。
        boolean hasComponents = fixed.contains("components", Tag.TAG_COMPOUND);
        boolean tagGone = !fixed.contains("tag", Tag.TAG_COMPOUND);
        if (!hasComponents && !tagGone) {
            return new MigratedKey(key, null, false);
        }
        CompoundTag components = hasComponents ? fixed.getCompound("components").copy() : null;
        if (components != null) collapsePlainText(components);
        String newId = fixed.getString("id");
        if (newId.isEmpty()) newId = id;
        return new MigratedKey(MaterialKey.make(newId, components), MaterialKey.normalize(components), true);
    }

    /**
     * 把 components 里纯文本 JSON（{@code {"text":"X"}} 且无兄弟键）压成裸字符串。
     *
     * <p>修复器写的是展开形，1.21 存盘写的是裸形（当前 Component codec 对无格式纯文本的写法）；
     * 语义相同但 NBT 逐字不同，不压则升级出的旧物与新造的同物算出不同材料键
     * （D8 一致性：实体身上的物品与手里的物品、旧蓝图的键与新产出的键都必须同物同键）。
     * 只动补丁形状的键（custom_name / lore），旧格式 display 写法碰不到；解析失败原样保留。</p>
     */
    private static void collapsePlainText(CompoundTag components) {
        if (components.contains("minecraft:custom_name", Tag.TAG_STRING)) {
            String collapsed = collapseTextJson(components.getString("minecraft:custom_name"));
            if (collapsed != null) components.putString("minecraft:custom_name", collapsed);
        }
        if (components.contains("minecraft:lore", Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag lore = components.getList("minecraft:lore", Tag.TAG_STRING);
            for (int i = 0; i < lore.size(); i++) {
                String collapsed = collapseTextJson(lore.getString(i));
                if (collapsed != null) {
                    lore.set(i, net.minecraft.nbt.StringTag.valueOf(collapsed));
                }
            }
        }
    }

    /** 纯文本 JSON → 裸字符串；不是「仅含 text 字符串的单键对象」返回 null。 */
    @Nullable
    private static String collapseTextJson(String json) {
        com.google.gson.JsonElement el;
        try {
            el = com.google.gson.JsonParser.parseString(json);
        } catch (com.google.gson.JsonSyntaxException e) {
            return null;
        }
        if (!el.isJsonObject()) return null;
        com.google.gson.JsonObject obj = el.getAsJsonObject();
        if (obj.size() != 1 || !obj.has("text")) return null;
        com.google.gson.JsonElement text = obj.get("text");
        if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) return null;
        return new com.google.gson.JsonPrimitive(text.getAsString()).toString();
    }
}
