package com.xioyim.spatialblueprint;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.List;

public class BlueprintConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<List<String>> EXCLUDED_BLOCKS;
    /**
     * 不允许出现在容器（及任何 NBT 层级）里的<b>物品</b> ID。
     *
     * <p>与 {@link #EXCLUDED_BLOCKS} 互补：那份列表只覆盖有方块形式的物品，
     * 刷怪蛋、命令方块矿车这类<b>纯物品</b>不在方块注册表里，只能靠本列表拦。</p>
     */
    public static final ModConfigSpec.ConfigValue<List<String>> EXCLUDED_ITEMS;
    public static final java.util.List<String> DEFAULT_BLOCK_SUBSTITUTIONS;
    /** 材料空间（非次元分区）总件数上限。0 = 自动取 {@link #ABSOLUTE_MAX_CAPTURE_VOLUME} 的 1.2 倍。 */
    public static final ModConfigSpec.IntValue MATERIAL_SPACE_MAX_COUNT;
    /** 构建材料复合键时忽略的顶层 NBT 字段。 */
    public static final ModConfigSpec.ConfigValue<List<String>> MATERIAL_SPACE_IGNORED_NBT_KEYS;
    public static final ModConfigSpec.IntValue ANIMATION_SPEED;
    public static final ModConfigSpec.IntValue ANIMATION_DURATION_MIN;
    public static final ModConfigSpec.IntValue ANIMATION_DURATION_MAX;
    // ── 构建时间自动分档（6档）────────────────────────────────────────────
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER1_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER1_SECONDS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER2_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER2_SECONDS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER3_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER3_SECONDS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER4_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER4_SECONDS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER5_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER5_SECONDS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER6_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER6_SECONDS;
    public static final ModConfigSpec.IntValue BUILD_TIME_TIER7_SECONDS;
    // ── 捕获体积上限：按【蓝图类型】而非游戏模式 ────────────────────────────
    // 生存 / 创造不再影响上限 —— 一个结构该多大是由它要存成哪种蓝图决定的，
    // 与玩家当时处于什么模式无关。OP 一律豁免（上限 = ABSOLUTE_MAX_CAPTURE_VOLUME）。
    /** 空间蓝图体积上限。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_SPATIAL;
    /** 投影蓝图体积上限。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_PROJECTION;
    /** 次元蓝图体积上限。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_DIMENSIONAL;
    /** 工程蓝图体积上限。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_ENGINEERING;
    /** 投影模板体积上限。0 = 不额外限制（仍受 {@link #ABSOLUTE_MAX_CAPTURE_VOLUME} 硬底线约束）。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_TEMPLATE;
    /** 项目蓝图选区体积上限。0 = 不额外限制（仍受 ABSOLUTE_MAX_CAPTURE_VOLUME 约束）。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_PROJECTSET;
    /** 子工程方块数上限。与独立工程蓝图分开配置。 */
    public static final ModConfigSpec.IntValue MAX_VOLUME_SUBPROJECT;

    // ── 磁盘日志保留天数（0 = 永久保留）────────────────────────────────────
    public static final ModConfigSpec.IntValue LOG_RETENTION_DAYS_PLACEMENT;
    public static final ModConfigSpec.IntValue LOG_RETENTION_DAYS_ADMIN;

    // ── 每玩家槽位上限（0 = 不限）──────────────────────────────────────────
    // 按 ownerUuid + category 统计；无主的旧蓝图不计入任何人，归档为 .bak 的天然不计。
    public static final ModConfigSpec.IntValue SLOT_LIMIT_SPATIAL;
    public static final ModConfigSpec.IntValue SLOT_LIMIT_PROJECTION;
    public static final ModConfigSpec.IntValue SLOT_LIMIT_DIMENSIONAL;
    public static final ModConfigSpec.IntValue SLOT_LIMIT_ENGINEERING;
    public static final ModConfigSpec.IntValue SLOT_LIMIT_TEMPLATE;
    /**
     * Hard upper bound for capture volume regardless of config values.
     * To raise this limit, change the value here and rebuild the mod:
     *   BlueprintConfig.java → ABSOLUTE_MAX_CAPTURE_VOLUME
     *
     * <p>Raised 500,000 → 1,600,000 (2026-07-20, experimental) to test how far the litematic-based
     * storage format (palette + bit-packed BlockStates, far more compact than the old one-NBT-
     * compound-per-block format) can stretch. Note this does NOT reduce the block-count-proportional
     * costs elsewhere (main-thread sanitizer passes during upload/capture, actual in-world placement
     * time, in-memory BlockEntry object count) — those still scale linearly with block count
     * regardless of how small the file is. Watch for main-thread stalls during upload/placement
     * at this scale before raising further.</p>
     */
    public static final int ABSOLUTE_MAX_CAPTURE_VOLUME = 1_600_000;
    public static final ModConfigSpec.IntValue PREVIEW_DISTANCE;
    public static final ModConfigSpec.IntValue RENDER_CANCEL_MARGIN;
    public static final ModConfigSpec.IntValue PREVIEW_BROADCAST_RADIUS;
    public static final ModConfigSpec.IntValue PREVIEW_BROADCAST_INTERVAL;
    public static final ModConfigSpec.IntValue EXTRA_COOLDOWN_SECONDS;
    /** 子工程领取冷却（秒）。每个玩家的每个子工程单独计时。 */
    public static final ModConfigSpec.IntValue SUBPROJECT_CLAIM_COOLDOWN;
    public static final ModConfigSpec.IntValue MAX_UNDO_HISTORY;
    public static final ModConfigSpec.BooleanValue LARGE_BLUEPRINT_BROADCAST_ENABLED;
    public static final ModConfigSpec.IntValue LARGE_BLUEPRINT_BROADCAST_THRESHOLD;
    public static final ModConfigSpec.ConfigValue<List<String>> LATE_PLACE_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<String>> HIDDEN_PREVIEW_BLOCKS;
    /** 是否对创造模式玩家也执行领地保护检测（BUG-01 修复开关）。默认 false（保持原行为）。 */
    public static final ModConfigSpec.BooleanValue DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA;
    // ── 上传安全检测 ────────────────────────────────────────────────────────
    public static final ModConfigSpec.IntValue MAX_UPLOAD_FILE_BYTES;
    public static final ModConfigSpec.IntValue MAX_NBT_DEPTH;

    /** 上传文件中单个方块实体 NBT 的字节预算，超出即拒收整个文件。 */
    public static final ModConfigSpec.IntValue MAX_BLOCK_ENTITY_NBT_BYTES;

    /** 上传文件中所有方块实体 NBT 的字节总预算，超出即拒收整个文件。 */
    public static final ModConfigSpec.IntValue MAX_TOTAL_NBT_BYTES;
    public static final ModConfigSpec.IntValue MAX_BOUNDING_VOLUME;
    public static final ModConfigSpec.IntValue UPLOAD_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue UPLOAD_COOLDOWN_SECONDS_OP;
    public static final ModConfigSpec.IntValue UPLOAD_MAX_PER_MINUTE;
    /** 特殊掉落物处理：放置完成发光时，列表中的物品设为永不自动消失。 */
    public static final ModConfigSpec.ConfigValue<List<String>> SPECIAL_DROP_ITEMS;

    /** 盒型实体：位置由 NBT 的 From/To 角点描述，而不是 Pos / TileXYZ。 */
    public static final ModConfigSpec.ConfigValue<List<String>> BOX_ENTITIES;

    static {
        BUILDER.push("general");

        EXCLUDED_BLOCKS = BUILDER
                .comment("List of block IDs that cannot be captured by blueprints")
                .define("excludedBlocks", List.of(
                        "minecraft:bedrock",
                        "minecraft:spawner",
                        "minecraft:end_portal",
                        "minecraft:end_portal_frame",
                        "minecraft:command_block",
                        "minecraft:repeating_command_block",
                        "minecraft:chain_command_block",
                        "minecraft:structure_block",
                        "minecraft:structure_void",
                        "minecraft:jigsaw",
                        "minecraft:barrier",
                        "minecraft:nether_portal",
                        "minecraft:end_gateway",
                        "minecraft:moving_piston",
                        "minecraft:trial_spawner",
                        "minecraft:vault"
                ));

        EXCLUDED_ITEMS = BUILDER
                .comment("List of ITEM IDs that may not appear inside containers (or anywhere in NBT).",
                        "Complements excludedBlocks: that list only covers items that also exist as blocks.",
                        "Pure items such as spawn eggs and command block minecarts are not in the block",
                        "registry at all and can only be blocked here.",
                        "Checked recursively against every string 'id' key at any NBT depth, so it also",
                        "covers nested shulker boxes and modded containers using their own key names.")
                .define("excludedItems", List.of(
                        "minecraft:command_block_minecart",
                        "minecraft:spawner",
                        "minecraft:bedrock",
                        "minecraft:barrier",
                        "minecraft:structure_block",
                        "minecraft:structure_void",
                        "minecraft:jigsaw",
                        "minecraft:command_block",
                        "minecraft:repeating_command_block",
                        "minecraft:chain_command_block",
                        "minecraft:end_portal_frame",
                        "minecraft:debug_stick",
                        "minecraft:knowledge_book",
                        "minecraft:trial_spawner",
                        "minecraft:vault"
                ));

        BUILDER.comment("Capture volume limits per BLUEPRINT TYPE (non-air block count).",
                        "Game mode no longer affects these — how big a structure may be is decided by",
                        "which kind of blueprint it is stored as, not by what mode the player is in.",
                        "OP players are exempt and always capped at " + ABSOLUTE_MAX_CAPTURE_VOLUME + ".",
                        "To raise the hard cap, edit ABSOLUTE_MAX_CAPTURE_VOLUME in BlueprintConfig.java.")
               .push("volumeLimits");

        MAX_VOLUME_SPATIAL = BUILDER
                .comment("Spatial blueprint volume limit. Default: 500000.")
                .defineInRange("maxVolumeSpatial", 500000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_VOLUME_PROJECTION = BUILDER
                .comment("Projection blueprint volume limit. Default: 100000.")
                .defineInRange("maxVolumeProjection", 100000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_VOLUME_DIMENSIONAL = BUILDER
                .comment("Dimensional blueprint volume limit. Default: 100000.")
                .defineInRange("maxVolumeDimensional", 100000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_VOLUME_ENGINEERING = BUILDER
                .comment("Engineering blueprint volume limit. Default: 100000.")
                .defineInRange("maxVolumeEngineering", 100000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_VOLUME_TEMPLATE = BUILDER
                .comment("Projection template volume limit. 0 = no extra limit.",
                        "Templates are uploaded from untrusted client data, so the",
                        ABSOLUTE_MAX_CAPTURE_VOLUME + " hard floor still applies even at 0.")
                .defineInRange("maxVolumeTemplate", 0, 0, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_VOLUME_PROJECTSET = BUILDER
                .comment("Project blueprint SELECTION VOLUME limit (sizeX * sizeY * sizeZ,",
                        "not the non-air block count). 0 = no extra limit; only the",
                        ABSOLUTE_MAX_CAPTURE_VOLUME + " hard cap applies (OP included).")
                .defineInRange("maxVolumeProjectSet", 0, 0, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_VOLUME_SUBPROJECT = BUILDER
                .comment("Sub-project block-count limit, configured separately from",
                        "maxVolumeEngineering. Default: 300000.")
                .defineInRange("maxVolumeSubProject", 300000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        BUILDER.pop();

        BUILDER.comment("Disk log retention, in days. 0 = keep forever.",
                        "Logs live in config/spatial_blueprint/logs/ and roll over daily.",
                        "placement_<date>.log records successful placements of SAFE/SANITIZE blueprints.",
                        "admin_<date>.log records events an admin may need to trace later:",
                        "dimensional archives, FORCE placements, permanent material loss, rejected uploads.")
               .push("logs");

        LOG_RETENTION_DAYS_PLACEMENT = BUILDER
                .comment("Retention for placement logs. Default: 30 days.")
                .defineInRange("retentionDaysPlacement", 30, 0, 3650);

        LOG_RETENTION_DAYS_ADMIN = BUILDER
                .comment("Retention for admin logs. Default: 90 days (small but high value).")
                .defineInRange("retentionDaysAdmin", 90, 0, 3650);

        BUILDER.pop();

        BUILDER.comment("Per-player slot limits, counted by owner UUID + blueprint category.",
                        "0 = unlimited. Blueprints with no recorded owner (captured before the",
                        "ownership system existed) count towards nobody. Archived .bak files do not count.",
                        "When a category is full, capture/upload of that type is refused.",
                        "OP players are exempt. Usage is also shown on the blueprint library tabs.")
               .push("slotLimits");

        SLOT_LIMIT_SPATIAL = BUILDER
                .comment("Spatial blueprint slots per player. Default: 0 (unlimited).")
                .defineInRange("slotLimitSpatial", 0, 0, 100000);

        SLOT_LIMIT_PROJECTION = BUILDER
                .comment("Projection blueprint slots per player. Default: 30.")
                .defineInRange("slotLimitProjection", 30, 0, 100000);

        SLOT_LIMIT_DIMENSIONAL = BUILDER
                .comment("Dimensional blueprint slots per player. Default: 10.")
                .defineInRange("slotLimitDimensional", 10, 0, 100000);

        SLOT_LIMIT_ENGINEERING = BUILDER
                .comment("Engineering blueprint slots per player. Default: 0 (unlimited).")
                .defineInRange("slotLimitEngineering", 0, 0, 100000);

        SLOT_LIMIT_TEMPLATE = BUILDER
                .comment("Projection template slots per player. Default: 30.")
                .defineInRange("slotLimitTemplate", 30, 0, 100000);

        BUILDER.pop();

        PREVIEW_DISTANCE = BUILDER
                .comment("Max distance (in blocks) for blueprint placement preview and deployment. Default: 128.")
                .defineInRange("previewDistance", 128, 1, 256);

        RENDER_CANCEL_MARGIN = BUILDER
                .comment("Extra distance (in blocks) added to the building's half-diagonal for the auto-cancel radius.\n"
                        + "If the player moves farther than (halfDiagonal + margin) from the building center,\n"
                        + "the preview is hidden and deployment is blocked. Default: 128.")
                .defineInRange("renderCancelMargin", 128, 0, 512);

        PREVIEW_BROADCAST_RADIUS = BUILDER
                .comment("Radius (in blocks) within which other players can see your blueprint preview outline.\n"
                        + "Default: 256.")
                .defineInRange("previewBroadcastRadius", 256, 16, 1024);

        PREVIEW_BROADCAST_INTERVAL = BUILDER
                .comment("Seconds between position broadcasts for unlocked (non-fixed) blueprint previews.\n"
                        + "Locked previews are broadcast immediately on change and do not use this interval.\n"
                        + "Default: 3.")
                .defineInRange("previewBroadcastInterval", 3, 1, 60);

        BUILDER.pop();
        BUILDER.push("materialList");

        // blockSubstitutions 配置项已迁移到独立 toml（GeneratedSubstitutionsConfig）。
        // 此处仅保留默认值列表作为独立配置的默认值。
        DEFAULT_BLOCK_SUBSTITUTIONS = List.of(
                        // ── Liquids & fire ─────────────────────────────────────────────────
                        "minecraft:water=minecraft:water_bucket",
                        "minecraft:lava=minecraft:lava_bucket",
                        "minecraft:bubble_column=",
                        "minecraft:fire=minecraft:flint_and_steel",
                        "minecraft:soul_fire=minecraft:flint_and_steel",
                        "minecraft:powder_snow=minecraft:snowball",
                        // ── Cauldrons (filled variants: cauldron + its content) ────────────
                        "minecraft:water_cauldron=minecraft:cauldron+minecraft:water_bucket",
                        "minecraft:lava_cauldron=minecraft:cauldron+minecraft:lava_bucket",
                        "minecraft:powder_snow_cauldron=minecraft:cauldron+minecraft:snowball",
                        // ── Piston ────────────────────────────────────────────────────────
                        "minecraft:piston_head=minecraft:piston",
                        // ── Redstone & string ─────────────────────────────────────────────
                        "minecraft:redstone_wire=minecraft:redstone",
                        "minecraft:tripwire=minecraft:string",
                        // ── Crops & plant growth stages ───────────────────────────────────
                        "minecraft:carrots=minecraft:carrot",
                        "minecraft:potatoes=minecraft:potato",
                        "minecraft:beetroots=minecraft:beetroot_seeds",
                        "minecraft:wheat=minecraft:wheat_seeds",
                        "minecraft:cocoa=minecraft:cocoa_beans",
                        "minecraft:melon_stem=minecraft:melon_seeds",
                        "minecraft:pumpkin_stem=minecraft:pumpkin_seeds",
                        "minecraft:attached_melon_stem=minecraft:melon_seeds",
                        "minecraft:attached_pumpkin_stem=minecraft:pumpkin_seeds",
                        "minecraft:sweet_berry_bush=minecraft:sweet_berries",
                        "minecraft:torchflower_crop=minecraft:torchflower_seeds",
                        "minecraft:pitcher_crop=minecraft:pitcher_pod",
                        // ── 免费（转化为空气）：海草、高海草、草、高草、雪层等 ───────────
                        "minecraft:seagrass=",
                        "minecraft:tall_seagrass=",
                        // ⚠ 1.20.1 里矮草叫 minecraft:grass，1.20.3 才改名 minecraft:short_grass。
                        // 升级版本时要跟着改，否则这条匹配不到任何方块（配置菜单里显示无图标）。
                        "minecraft:grass=",
                        "minecraft:tall_grass=",
                        "minecraft:snow=",
                        "minecraft:moss_carpet=",
                        "minecraft:pink_petals=",
                        "minecraft:sculk_vein=",
                        // ── 转化为其他物品 ────────────────────────────────────────────────
                        "minecraft:cobweb=minecraft:string",
                        "minecraft:grass_block=minecraft:dirt",
                        "minecraft:farmland=minecraft:dirt",
                        // ── Vine / seagrass plant-block variants ──────────────────────────
                        "minecraft:kelp_plant=minecraft:kelp",
                        "minecraft:weeping_vines_plant=minecraft:weeping_vines",
                        "minecraft:twisting_vines_plant=minecraft:twisting_vines",
                        "minecraft:cave_vines=minecraft:glow_berries",
                        "minecraft:cave_vines_plant=minecraft:glow_berries",
                        "minecraft:big_dripleaf_stem=minecraft:big_dripleaf",
                        "minecraft:bamboo_sapling=minecraft:bamboo",
                        // ── Wall torches ──────────────────────────────────────────────────
                        "minecraft:wall_torch=minecraft:torch",
                        "minecraft:redstone_wall_torch=minecraft:redstone_torch",
                        "minecraft:soul_wall_torch=minecraft:soul_torch",
                        // ── Wall signs (all wood types) ───────────────────────────────────
                        "minecraft:oak_wall_sign=minecraft:oak_sign",
                        "minecraft:spruce_wall_sign=minecraft:spruce_sign",
                        "minecraft:birch_wall_sign=minecraft:birch_sign",
                        "minecraft:jungle_wall_sign=minecraft:jungle_sign",
                        "minecraft:acacia_wall_sign=minecraft:acacia_sign",
                        "minecraft:dark_oak_wall_sign=minecraft:dark_oak_sign",
                        "minecraft:mangrove_wall_sign=minecraft:mangrove_sign",
                        "minecraft:cherry_wall_sign=minecraft:cherry_sign",
                        "minecraft:bamboo_wall_sign=minecraft:bamboo_sign",
                        "minecraft:crimson_wall_sign=minecraft:crimson_sign",
                        "minecraft:warped_wall_sign=minecraft:warped_sign",
                        // ── Wall hanging signs (all wood types) ───────────────────────────
                        "minecraft:oak_wall_hanging_sign=minecraft:oak_hanging_sign",
                        "minecraft:spruce_wall_hanging_sign=minecraft:spruce_hanging_sign",
                        "minecraft:birch_wall_hanging_sign=minecraft:birch_hanging_sign",
                        "minecraft:jungle_wall_hanging_sign=minecraft:jungle_hanging_sign",
                        "minecraft:acacia_wall_hanging_sign=minecraft:acacia_hanging_sign",
                        "minecraft:dark_oak_wall_hanging_sign=minecraft:dark_oak_hanging_sign",
                        "minecraft:mangrove_wall_hanging_sign=minecraft:mangrove_hanging_sign",
                        "minecraft:cherry_wall_hanging_sign=minecraft:cherry_hanging_sign",
                        "minecraft:bamboo_wall_hanging_sign=minecraft:bamboo_hanging_sign",
                        "minecraft:crimson_wall_hanging_sign=minecraft:crimson_hanging_sign",
                        "minecraft:warped_wall_hanging_sign=minecraft:warped_hanging_sign",
                        // ── Wall banners (all colors → default white banner, shown with its pattern NBT) ──
                        "minecraft:white_wall_banner=minecraft:white_banner",
                        "minecraft:orange_wall_banner=minecraft:white_banner",
                        "minecraft:magenta_wall_banner=minecraft:white_banner",
                        "minecraft:light_blue_wall_banner=minecraft:white_banner",
                        "minecraft:yellow_wall_banner=minecraft:white_banner",
                        "minecraft:lime_wall_banner=minecraft:white_banner",
                        "minecraft:pink_wall_banner=minecraft:white_banner",
                        "minecraft:gray_wall_banner=minecraft:white_banner",
                        "minecraft:light_gray_wall_banner=minecraft:white_banner",
                        "minecraft:cyan_wall_banner=minecraft:white_banner",
                        "minecraft:purple_wall_banner=minecraft:white_banner",
                        "minecraft:blue_wall_banner=minecraft:white_banner",
                        "minecraft:brown_wall_banner=minecraft:white_banner",
                        "minecraft:green_wall_banner=minecraft:white_banner",
                        "minecraft:red_wall_banner=minecraft:white_banner",
                        "minecraft:black_wall_banner=minecraft:white_banner",
                        // ── Wall heads & skulls ───────────────────────────────────────────
                        "minecraft:skeleton_wall_skull=minecraft:skeleton_skull",
                        "minecraft:wither_skeleton_wall_skull=minecraft:wither_skeleton_skull",
                        "minecraft:zombie_wall_head=minecraft:zombie_head",
                        "minecraft:player_wall_head=minecraft:player_head",
                        "minecraft:creeper_wall_head=minecraft:creeper_head",
                        "minecraft:dragon_wall_head=minecraft:dragon_head",
                        "minecraft:piglin_wall_head=minecraft:piglin_head",
                        // ── Coral wall fans ───────────────────────────────────────────────
                        "minecraft:tube_coral_wall_fan=minecraft:tube_coral_fan",
                        "minecraft:brain_coral_wall_fan=minecraft:brain_coral_fan",
                        "minecraft:bubble_coral_wall_fan=minecraft:bubble_coral_fan",
                        "minecraft:fire_coral_wall_fan=minecraft:fire_coral_fan",
                        "minecraft:horn_coral_wall_fan=minecraft:horn_coral_fan",
                        "minecraft:dead_tube_coral_wall_fan=minecraft:dead_tube_coral_fan",
                        "minecraft:dead_brain_coral_wall_fan=minecraft:dead_brain_coral_fan",
                        "minecraft:dead_bubble_coral_wall_fan=minecraft:dead_bubble_coral_fan",
                        "minecraft:dead_fire_coral_wall_fan=minecraft:dead_fire_coral_fan",
                        "minecraft:dead_horn_coral_wall_fan=minecraft:dead_horn_coral_fan",
                        // ── Candle cakes (candle + cake placed together) ──────────────────
                        "minecraft:candle_cake=minecraft:candle",
                        "minecraft:white_candle_cake=minecraft:white_candle",
                        "minecraft:orange_candle_cake=minecraft:orange_candle",
                        "minecraft:magenta_candle_cake=minecraft:magenta_candle",
                        "minecraft:light_blue_candle_cake=minecraft:light_blue_candle",
                        "minecraft:yellow_candle_cake=minecraft:yellow_candle",
                        "minecraft:lime_candle_cake=minecraft:lime_candle",
                        "minecraft:pink_candle_cake=minecraft:pink_candle",
                        "minecraft:gray_candle_cake=minecraft:gray_candle",
                        "minecraft:light_gray_candle_cake=minecraft:light_gray_candle",
                        "minecraft:cyan_candle_cake=minecraft:cyan_candle",
                        "minecraft:purple_candle_cake=minecraft:purple_candle",
                        "minecraft:blue_candle_cake=minecraft:blue_candle",
                        "minecraft:brown_candle_cake=minecraft:brown_candle",
                        "minecraft:green_candle_cake=minecraft:green_candle",
                        "minecraft:red_candle_cake=minecraft:red_candle",
                        "minecraft:black_candle_cake=minecraft:black_candle",
                        // ── Potted plants (pot not tracked; map to contained plant) ────────
                        "minecraft:potted_oak_sapling=minecraft:flower_pot+minecraft:oak_sapling",
                        "minecraft:potted_spruce_sapling=minecraft:flower_pot+minecraft:spruce_sapling",
                        "minecraft:potted_birch_sapling=minecraft:flower_pot+minecraft:birch_sapling",
                        "minecraft:potted_jungle_sapling=minecraft:flower_pot+minecraft:jungle_sapling",
                        "minecraft:potted_acacia_sapling=minecraft:flower_pot+minecraft:acacia_sapling",
                        "minecraft:potted_dark_oak_sapling=minecraft:flower_pot+minecraft:dark_oak_sapling",
                        "minecraft:potted_mangrove_propagule=minecraft:flower_pot+minecraft:mangrove_propagule",
                        "minecraft:potted_cherry_sapling=minecraft:flower_pot+minecraft:cherry_sapling",
                        "minecraft:potted_bamboo=minecraft:flower_pot+minecraft:bamboo",
                        "minecraft:potted_fern=minecraft:flower_pot+minecraft:fern",
                        "minecraft:potted_dandelion=minecraft:flower_pot+minecraft:dandelion",
                        "minecraft:potted_poppy=minecraft:flower_pot+minecraft:poppy",
                        "minecraft:potted_blue_orchid=minecraft:flower_pot+minecraft:blue_orchid",
                        "minecraft:potted_allium=minecraft:flower_pot+minecraft:allium",
                        "minecraft:potted_azure_bluet=minecraft:flower_pot+minecraft:azure_bluet",
                        "minecraft:potted_red_tulip=minecraft:flower_pot+minecraft:red_tulip",
                        "minecraft:potted_orange_tulip=minecraft:flower_pot+minecraft:orange_tulip",
                        "minecraft:potted_white_tulip=minecraft:flower_pot+minecraft:white_tulip",
                        "minecraft:potted_pink_tulip=minecraft:flower_pot+minecraft:pink_tulip",
                        "minecraft:potted_oxeye_daisy=minecraft:flower_pot+minecraft:oxeye_daisy",
                        "minecraft:potted_cornflower=minecraft:flower_pot+minecraft:cornflower",
                        "minecraft:potted_lily_of_the_valley=minecraft:flower_pot+minecraft:lily_of_the_valley",
                        "minecraft:potted_wither_rose=minecraft:flower_pot+minecraft:wither_rose",
                        "minecraft:potted_brown_mushroom=minecraft:flower_pot+minecraft:brown_mushroom",
                        "minecraft:potted_red_mushroom=minecraft:flower_pot+minecraft:red_mushroom",
                        "minecraft:potted_crimson_fungus=minecraft:flower_pot+minecraft:crimson_fungus",
                        "minecraft:potted_warped_fungus=minecraft:flower_pot+minecraft:warped_fungus",
                        "minecraft:potted_crimson_roots=minecraft:flower_pot+minecraft:crimson_roots",
                        "minecraft:potted_warped_roots=minecraft:flower_pot+minecraft:warped_roots",
                        "minecraft:potted_dead_bush=minecraft:flower_pot+minecraft:dead_bush",
                        "minecraft:potted_cactus=minecraft:flower_pot+minecraft:cactus",
                        "minecraft:potted_azalea_bush=minecraft:flower_pot+minecraft:azalea",
                        "minecraft:potted_flowering_azalea_bush=minecraft:flower_pot+minecraft:flowering_azalea",
                        "minecraft:potted_torchflower=minecraft:flower_pot+minecraft:torchflower"
                );

        MATERIAL_SPACE_MAX_COUNT = BUILDER
                .comment("Maximum total item count in a player's material space (non-dimensional partition).\n"
                        + "0 = auto: ABSOLUTE_MAX_CAPTURE_VOLUME * 1.2 = 1920000.\n"
                        + "The dimensional partition (materials captured by dimensional blueprints)\n"
                        + "is bound to blueprint IDs and is NOT subject to this cap.")
                .defineInRange("materialSpaceMaxCount", 0, 0, Integer.MAX_VALUE);

        MATERIAL_SPACE_IGNORED_NBT_KEYS = BUILDER
                .comment("Top-level NBT keys ignored when building a material key.\n"
                        + "Items differing ONLY in these fields are treated as the same material.\n"
                        + "Default: RepairCost — otherwise the same enchanted gear with a different\n"
                        + "anvil repair cost counts as a separate material and cannot be deposited.")
                .define("materialSpaceIgnoredNbtKeys",
                        (List<String>) new ArrayList<>(List.of("RepairCost")));

        BUILDER.pop();
        BUILDER.push("animation");

        ANIMATION_SPEED = BUILDER
                .comment("Render block visual animation speed multiplier. Higher = faster block-morph animation.\n"
                        + "1 = default speed (20 ticks = 1 second)\n"
                        + "2 = 2x faster (10 ticks)\n"
                        + "5 = 5x faster (4 ticks)")
                .defineInRange("animationSpeed", 1, 1, 20);

        BUILD_TIME_TIER1_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 1: < this many blocks → tier1 seconds. Default: 10000")
                .defineInRange("buildTimeTier1MaxBlocks", 10000, 1, 2000000);
        BUILD_TIME_TIER1_SECONDS = BUILDER
                .comment("Build-time tier 1 duration in seconds. Default: 8")
                .defineInRange("buildTimeTier1Seconds", 8, 1, 3600);

        BUILD_TIME_TIER2_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 2: < this many blocks → tier2 seconds. Default: 50000")
                .defineInRange("buildTimeTier2MaxBlocks", 50000, 1, 2000000);
        BUILD_TIME_TIER2_SECONDS = BUILDER
                .comment("Build-time tier 2 duration in seconds. Default: 12")
                .defineInRange("buildTimeTier2Seconds", 12, 1, 3600);

        BUILD_TIME_TIER3_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 3: < this many blocks → tier3 seconds. Default: 120000")
                .defineInRange("buildTimeTier3MaxBlocks", 120000, 1, 2000000);
        BUILD_TIME_TIER3_SECONDS = BUILDER
                .comment("Build-time tier 3 duration in seconds. Default: 16")
                .defineInRange("buildTimeTier3Seconds", 16, 1, 3600);

        BUILD_TIME_TIER4_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 4: < this many blocks → tier4 seconds. Default: 250000")
                .defineInRange("buildTimeTier4MaxBlocks", 250000, 1, 2000000);
        BUILD_TIME_TIER4_SECONDS = BUILDER
                .comment("Build-time tier 4 duration in seconds. Default: 25")
                .defineInRange("buildTimeTier4Seconds", 25, 1, 3600);

        BUILD_TIME_TIER5_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 5: < this many blocks → tier5 seconds. Default: 400000")
                .defineInRange("buildTimeTier5MaxBlocks", 400000, 1, 2000000);
        BUILD_TIME_TIER5_SECONDS = BUILDER
                .comment("Build-time tier 5 duration in seconds. Default: 30")
                .defineInRange("buildTimeTier5Seconds", 30, 1, 3600);

        BUILD_TIME_TIER6_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 6: < this many blocks → tier6 seconds. Default: 600000")
                .defineInRange("buildTimeTier6MaxBlocks", 600000, 1, 2000000);
        BUILD_TIME_TIER6_SECONDS = BUILDER
                .comment("Build-time tier 6 duration in seconds. Default: 50")
                .defineInRange("buildTimeTier6Seconds", 50, 1, 3600);

        BUILD_TIME_TIER7_SECONDS = BUILDER
                .comment("Build-time tier 7 (catch-all for >= tier6 blocks). Default: 60")
                .defineInRange("buildTimeTier7Seconds", 60, 1, 3600);

        ANIMATION_DURATION_MIN = BUILDER
                .comment("Minimum animation duration (seconds) for all players (including OP and creative). Default: 3")
                .defineInRange("animationDurationMin", 3, 1, 3600);

        ANIMATION_DURATION_MAX = BUILDER
                .comment("Maximum animation duration (seconds) for all players (including OP and creative). Default: 600")
                .defineInRange("animationDurationMax", 600, 1, 3600);

        EXTRA_COOLDOWN_SECONDS = BUILDER
                .comment("Extra cooldown (seconds) added on top of the animation duration after a blueprint\n"
                        + "finishes deploying. Total cooldown = animationDuration + extraCooldown.\n"
                        + "During this time the item shows as a 'building blueprint' and cannot be re-used.")
                .defineInRange("extraCooldownSeconds", 3, 0, 3600);

        SUBPROJECT_CLAIM_COOLDOWN = BUILDER
                .comment("Cooldown in seconds between claims of the SAME sub-project",
                        "by the SAME player. Counted per player per sub-project.",
                        "Default: 600 (10 minutes). 0 disables the cooldown.")
                .defineInRange("subProjectClaimCooldownSeconds", 600, 0, 86400);

        MAX_UNDO_HISTORY = BUILDER
                .comment("Maximum number of undo records stored per creative-mode player for /sb undo.\n"
                        + "Only spatial blueprint placements by creative players are recorded.\n"
                        + "Range: 2-16. Default: 6.")
                .defineInRange("maxUndoHistory", 6, 2, 16);

        LARGE_BLUEPRINT_BROADCAST_ENABLED = BUILDER
                .comment("Whether to broadcast a server-wide notice when a large blueprint is placed.\n"
                        + "Default: true")
                .define("largeBlueprintBroadcastEnabled", true);

        LARGE_BLUEPRINT_BROADCAST_THRESHOLD = BUILDER
                .comment("Block count threshold for the large-blueprint broadcast.\n"
                        + "Placements with this many or more blocks trigger the broadcast.\n"
                        + "Default: 100000")
                .defineInRange("largeBlueprintBroadcastThreshold", 100000, 1, 2000000);

        DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA = BUILDER
                .comment("If true, creative-mode players are also subject to territory-protection checks\n"
                        + "(same as survival players). Covers BOTH directions of world modification:\n"
                        + "  - placement (EntityPlaceEvent + BreakEvent on the clear pass)\n"
                        + "  - dimensional-blueprint capture, which clears the region (BreakEvent)\n"
                        + "The key name says 'placement' for backward compatibility with existing configs.\n"
                        + "Default: true.")
                .define("denyCreativePlacementInProtectedArea", true);

        BUILDER.pop();
        BUILDER.push("placement");

        LATE_PLACE_BLOCKS = BUILDER
                .comment("Blocks that are force-placed last (after all animations and decorative entities).\n"
                        + "Useful for container blocks (chests, shulker boxes, furnaces, etc.) whose NBT\n"
                        + "data (inventory contents) must be restored after the structure is fully built.\n"
                        + "These blocks skip the animation phase and are placed at the very end of round 4.")
                .define("latePlaceBlocks", List.of(
                        // ── Chests ─────────────────────────────────────────
                        "minecraft:chest",
                        "minecraft:trapped_chest",
                        // ── Barrel ─────────────────────────────────────────
                        "minecraft:barrel",
                        // ── Shulker boxes (17 variants) ────────────────────
                        "minecraft:shulker_box",
                        "minecraft:white_shulker_box",
                        "minecraft:orange_shulker_box",
                        "minecraft:magenta_shulker_box",
                        "minecraft:light_blue_shulker_box",
                        "minecraft:yellow_shulker_box",
                        "minecraft:lime_shulker_box",
                        "minecraft:pink_shulker_box",
                        "minecraft:gray_shulker_box",
                        "minecraft:light_gray_shulker_box",
                        "minecraft:cyan_shulker_box",
                        "minecraft:purple_shulker_box",
                        "minecraft:blue_shulker_box",
                        "minecraft:brown_shulker_box",
                        "minecraft:green_shulker_box",
                        "minecraft:red_shulker_box",
                        "minecraft:black_shulker_box",
                        // ── Furnaces ───────────────────────────────────────
                        "minecraft:furnace",
                        "minecraft:blast_furnace",
                        "minecraft:smoker",
                        // ── Anvils ─────────────────────────────────────────
                        "minecraft:anvil",
                        "minecraft:chipped_anvil",
                        "minecraft:damaged_anvil",
                        // ── Ender chest ────────────────────────────────────
                        "minecraft:ender_chest",
                        // ── Fluids ─────────────────────────────────────────
                        // Must be placed last so the structure is fully built
                        // before water/lava flows and fills the space.
                        "minecraft:water",
                        "minecraft:lava",
                        "minecraft:bubble_column"
                ));

        HIDDEN_PREVIEW_BLOCKS = BUILDER
                .comment("Block IDs hidden from the blueprint preview when the fluid-toggle button is active.\n"
                        + "Defaults include water and lava. Add any block ID here to hide it from the preview\n"
                        + "(e.g. minecraft:kelp, minecraft:seagrass). Does NOT affect the final build.\n"
                        + "Fluid-state blocks (water/lava) are always hidden when the toggle is on,\n"
                        + "regardless of whether they appear in this list.")
                .define("hiddenPreviewBlocks", List.of(
                        "minecraft:water",
                        "minecraft:lava",
                        "minecraft:bubble_column"
                ));

        BUILDER.pop();
        BUILDER.push("upload_security");

        MAX_UPLOAD_FILE_BYTES = BUILDER
                .comment("Maximum size (bytes) of a raw uploaded blueprint file, checked BEFORE decompression.\n"
                        + "This is the first check on any client-supplied byte stream — a highly compressed\n"
                        + "NBT payload can expand a small file into hundreds of MB in memory, so the check\n"
                        + "must happen before deserialization, not after.\n"
                        + "Default: 20971520 (20 MB) — raised from 5 MB alongside ABSOLUTE_MAX_CAPTURE_VOLUME\n"
                        + "(500k -> 1.6M blocks, 2026-07-20) to give headroom for larger structures.")
                .defineInRange("maxUploadFileBytes", 20_971_520, 1024, Integer.MAX_VALUE);

        MAX_NBT_DEPTH = BUILDER
                .comment("Maximum nesting depth of any NBT node in an uploaded blueprint file.\n"
                        + "Scanned iteratively (never recursively) to avoid a StackOverflow from maliciously\n"
                        + "nested NBT crashing the server. This limit is never bypassed, not even by OPs.\n"
                        + "Vanilla tile entities rarely exceed 5 levels; default 8 leaves headroom.")
                .defineInRange("maxNbtDepth", 8, 1, 256);

        MAX_BOUNDING_VOLUME = BUILDER
                .comment("Maximum bounding-box volume (W*H*L) of an uploaded blueprint, separate from the\n"
                        + "non-air block count check. The litematic storage format must lay out a dense\n"
                        + "index array covering the ENTIRE bounding box (air included), so a sparse structure\n"
                        + "with a huge bounding box but few actual blocks would otherwise still allocate an\n"
                        + "enormous array. Deliberately NOT tied to ABSOLUTE_MAX_CAPTURE_VOLUME — a real test\n"
                        + "file with 1.5M blocks (well under the 1.6M block cap) had a bounding volume of\n"
                        + "9.4M cells (~16% fill), so the two need independent headroom. Default: 20000000.")
                .defineInRange("maxBoundingVolume", 20_000_000, 1, Integer.MAX_VALUE);

        UPLOAD_COOLDOWN_SECONDS = BUILDER
                .comment("Minimum seconds between two upload attempts from the same non-OP player\n"
                        + "(C2S_UploadProjectionTemplateMessage). Prevents a single rapid-fire spam burst.\n"
                        + "Default: 5.")
                .defineInRange("uploadCooldownSeconds", 5, 0, 3600);

        UPLOAD_COOLDOWN_SECONDS_OP = BUILDER
                .comment("Minimum seconds between two upload attempts from an OP player. OPs are trusted\n"
                        + "and also skip the per-minute cap below entirely, but still get a minimal cooldown\n"
                        + "so a stuck client/macro can't fire literally every tick. Default: 1.")
                .defineInRange("uploadCooldownSecondsOp", 1, 0, 3600);

        UPLOAD_MAX_PER_MINUTE = BUILDER
                .comment("Maximum number of upload attempts a non-OP player may make within a rolling\n"
                        + "60-second window, on top of the per-attempt cooldown above. Each upload does\n"
                        + "meaningful synchronous work on the main server thread (decompress, sanitize,\n"
                        + "disk write, re-broadcast), so this caps sustained abuse even if the cooldown\n"
                        + "alone were bypassed. OP players are exempt from this cap entirely. Default: 10.")
                .defineInRange("uploadMaxPerMinute", 10, 1, 1000);

        BUILDER.pop();
        BUILDER.push("nbt_budget");

        MAX_BLOCK_ENTITY_NBT_BYTES = BUILDER
                .comment("Byte budget for a SINGLE block entity NBT in an UPLOADED blueprint file.",
                        "The other upload checks measure compressed size, nesting depth and bounding",
                        "box -- none of them measures NBT BYTES. A command block holds up to 32500",
                        "chars in one tag, player-head skin blobs are all distinct so they never dedupe,",
                        "and a 5000-entry enchantment list is only 3 levels deep so maxNbtDepth never",
                        "sees it. None of those are dangerous or block-count-heavy, yet they inflate",
                        "the save by orders of magnitude.",
                        "UPLOAD ONLY -- capture is deliberately unlimited: OP forced blueprints must be",
                        "able to carry every byte of NBT, and dimensional blueprints move the structure",
                        "out of the world so their contents have to come along.",
                        "Over budget = that block entity's NBT is CLEARED and the block is KEPT; the",
                        "file still uploads. Only maxTotalNbtBytes rejects the whole file, because a",
                        "total overrun cannot be fixed by clearing one block. Same for maxNbtDepth.",
                        "Default: 8192 (8 KB). Vanilla block entities are far below this.")
                .defineInRange("maxBlockEntityNbtBytes", 8192, 512, 1_048_576);

        MAX_TOTAL_NBT_BYTES = BUILDER
                .comment("Byte budget for ALL block-entity NBT in one uploaded file, summed.",
                        "Only block-entity NBT counts toward this -- the bounding box and block count",
                        "are bounded by maxBoundingVolume and the maxVolume* settings instead.",
                        "UPLOAD ONLY, same rationale as maxBlockEntityNbtBytes.",
                        "Default: 20971520 (20 MB).")
                .defineInRange("maxTotalNbtBytes", 20_971_520, 1_048_576, Integer.MAX_VALUE);

        BUILDER.pop();
        BUILDER.push("special_drops");

        // Special drop handling: when placement glows the drops, items in this list never despawn
        SPECIAL_DROP_ITEMS = BUILDER
                .comment("Special drop handling: when blueprint placement glows the dropped items,\n"
                        + "items in this list are set to never despawn automatically (UnlimitedLifetime).\n"
                        + "Default: lead, item_frame, glow_item_frame.")
                .define("specialDropItems", List.of(
                        "minecraft:lead",
                        "minecraft:item_frame",
                        "minecraft:glow_item_frame"));

        BOX_ENTITIES = BUILDER
                .comment("Entity IDs whose position is described by a From/To corner pair in NBT",
                        "(a double ListTag each) instead of Pos / TileX-Y-Z.",
                        "Create's super glue is the reason this exists: it is an invisible box spanning",
                        "two blocks, and Create refuses to re-assemble a contraption without it —",
                        "capturing the blocks but not the glue makes the structure fall apart on placement.",
                        "These entities are captured only when the box lies entirely inside the selection,",
                        "and their corners are rotated with the blueprint on placement.",
                        "Listing an ID here is harmless when the mod providing it is absent.")
                .define("boxEntities", List.of(
                        "create:super_glue"));

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private static List<Block> cachedExcludedBlocks              = null;
    private static java.util.Set<String> cachedExcludedItems     = null;
    private static java.util.Set<String> cachedLatePlaceBlocks   = null;
    private static java.util.Set<String> cachedBoxEntities       = null;
    private static java.util.Set<String> cachedHiddenPreviewBlocks = null;
    private static java.util.Set<String> cachedSpecialDropItems  = null;

    /** 特殊掉落物物品 ID 集合（永不消失）。 */
    public static java.util.Set<String> getSpecialDropItems() {
        if (cachedSpecialDropItems == null) {
            cachedSpecialDropItems = new java.util.HashSet<>(SPECIAL_DROP_ITEMS.get());
        }
        return cachedSpecialDropItems;
    }

    public static List<Block> getExcludedBlocks() {
        if (cachedExcludedBlocks == null) {
            cachedExcludedBlocks = new ArrayList<>();
            for (String id : EXCLUDED_BLOCKS.get()) {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (BuiltInRegistries.BLOCK.containsKey(rl)) {
                    cachedExcludedBlocks.add(BuiltInRegistries.BLOCK.get(rl));
                }
            }
        }
        return cachedExcludedBlocks;
    }

    public static boolean isExcluded(Block block) {
        return getExcludedBlocks().contains(block);
    }

    public static boolean isExcluded(net.minecraft.world.level.block.state.BlockState state) {
        return isExcluded(state.getBlock());
    }

    /**
     * 按注册名字符串（如 {@code "minecraft:repeating_command_block"}）检测是否为禁用方块。
     * 找不到对应方块（非法字符串、或该 ID 根本不是一个方块）时返回 false——本方法只负责
     * "是方块且被禁用" 这一种非法情形，不对未知 ID 做额外假设。
     */
    public static boolean isExcludedId(String registryId) {
        ResourceLocation rl = ResourceLocation.tryParse(registryId);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return false;
        return isExcluded(BuiltInRegistries.BLOCK.get(rl));
    }

    /**
     * 该分类的捕获体积上限（非空气方块数）。
     *
     * <p>OP 请直接用 {@link #ABSOLUTE_MAX_CAPTURE_VOLUME}，不要走本方法 —— OP 一律豁免。</p>
     *
     * @param category {@code create} / {@code project} / {@code dimensional} /
     *                 {@code engineering} / {@code template}；未知分类按空间蓝图处理
     * @return 有效上限，恒不超过 {@link #ABSOLUTE_MAX_CAPTURE_VOLUME}
     */
    public static int getMaxVolumeFor(String category) {
        int configured = switch (category == null ? "" : category) {
            case "project"     -> MAX_VOLUME_PROJECTION.get();
            case "dimensional" -> MAX_VOLUME_DIMENSIONAL.get();
            case "engineering" -> MAX_VOLUME_ENGINEERING.get();
            // 模板的 0 表示「不额外限制」，退回硬底线
            case "template"    -> MAX_VOLUME_TEMPLATE.get() <= 0
                                        ? ABSOLUTE_MAX_CAPTURE_VOLUME : MAX_VOLUME_TEMPLATE.get();
            // 项目蓝图按选区体积算；0 表示「不额外限制」，退回硬底线（同模板）
            case "projectset"  -> MAX_VOLUME_PROJECTSET.get() <= 0
                                        ? ABSOLUTE_MAX_CAPTURE_VOLUME : MAX_VOLUME_PROJECTSET.get();
            case "subproject"  -> MAX_VOLUME_SUBPROJECT.get();
            default            -> MAX_VOLUME_SPATIAL.get();
        };
        return Math.min(configured, ABSOLUTE_MAX_CAPTURE_VOLUME);
    }

    /**
     * 该分类的每玩家槽位上限；{@code 0} 表示不限。
     *
     * <p>满槽位时该类型的捕获 / 上传会被拒绝（OP 豁免）；用量同时显示在蓝图列表的 tab 上。</p>
     */
    public static int getSlotLimitFor(String category) {
        return switch (category == null ? "" : category) {
            case "project"     -> SLOT_LIMIT_PROJECTION.get();
            case "dimensional" -> SLOT_LIMIT_DIMENSIONAL.get();
            case "engineering" -> SLOT_LIMIT_ENGINEERING.get();
            case "template"    -> SLOT_LIMIT_TEMPLATE.get();
            // 奖励蓝图是 OP 发出去的东西，不占玩家槽位 —— 发多了不该把玩家的投影额度挤爆
            case "reward"      -> 0;
            // 项目蓝图与子工程都不占玩家槽位
            case "projectset", "subproject" -> 0;
            default            -> SLOT_LIMIT_SPATIAL.get();
        };
    }

    /**
     * 按注册名字符串检测是否为禁用<b>物品</b>（配置 {@code excludedItems}）。
     *
     * <p>只查本列表，不查 {@code excludedBlocks} —— 两者的合并判定在
     * {@code BlueprintSanitizer.isIllegalItemId()} 里做。</p>
     */
    public static boolean isExcludedItemId(String registryId) {
        return getExcludedItems().contains(registryId);
    }

    /** {@code excludedItems} 的集合形式（缓存）。 */
    public static java.util.Set<String> getExcludedItems() {
        if (cachedExcludedItems == null) {
            cachedExcludedItems = new java.util.HashSet<>(EXCLUDED_ITEMS.get());
        }
        return cachedExcludedItems;
    }

    /** Returns the set of block registry IDs that should be force-placed last (round 4). */
    public static java.util.Set<String> getLatePlaceBlocks() {
        if (cachedLatePlaceBlocks == null) {
            cachedLatePlaceBlocks = new java.util.HashSet<>(LATE_PLACE_BLOCKS.get());
        }
        return cachedLatePlaceBlocks;
    }

    /**
     * 位置由 NBT 的 {@code From}/{@code To} 角点描述的实体 ID 集合。
     *
     * <p>这类实体的几何是一个 AABB，捕获与放置都要单独处理角点坐标 ——
     * 见 {@code CaptureAreaMessage} 的盒型实体段与 {@code BlueprintPlacementHandler.placeBoxEntity}。</p>
     *
     * <p>⚠ Create 的 {@code From}/{@code To} 是<b>相对 {@code Pos} 的偏移</b>（存盘
     * {@code bb.move(-pos)}，读盘 {@code +pos}），{@code Pos} 取盒底中心。它们不是世界坐标；
     * 捕获侧几何一律取 {@code entity.getBoundingBox()}，放置侧按同一约定写回三个键。</p>
     */
    public static java.util.Set<String> getBoxEntities() {
        if (cachedBoxEntities == null) {
            cachedBoxEntities = new java.util.HashSet<>(BOX_ENTITIES.get());
        }
        return cachedBoxEntities;
    }

    /** Returns the set of block registry IDs hidden from blueprint preview when hideFluid is on. */
    public static java.util.Set<String> getHiddenPreviewBlocks() {
        if (cachedHiddenPreviewBlocks == null) {
            cachedHiddenPreviewBlocks = new java.util.HashSet<>(HIDDEN_PREVIEW_BLOCKS.get());
        }
        return cachedHiddenPreviewBlocks;
    }

    public static void clearCache() {
        cachedExcludedBlocks        = null;
        cachedExcludedItems         = null;
        cachedLatePlaceBlocks       = null;
        cachedHiddenPreviewBlocks   = null;
        cachedSpecialDropItems      = null;
        cachedBoxEntities           = null;
    }

    /**
     * 根据蓝图总方块数（normalBlocks + dependentBlocks + lateBlocks）自动返回推荐构建时长（秒）。
     * 按六档配置匹配，超过 tier5 上限使用 tier6 时长作为兜底。
     */
    public static int getAutoDuration(int blockCount) {
        if (blockCount < BUILD_TIME_TIER1_MAX_BLOCKS.get()) return BUILD_TIME_TIER1_SECONDS.get();
        if (blockCount < BUILD_TIME_TIER2_MAX_BLOCKS.get()) return BUILD_TIME_TIER2_SECONDS.get();
        if (blockCount < BUILD_TIME_TIER3_MAX_BLOCKS.get()) return BUILD_TIME_TIER3_SECONDS.get();
        if (blockCount < BUILD_TIME_TIER4_MAX_BLOCKS.get()) return BUILD_TIME_TIER4_SECONDS.get();
        if (blockCount < BUILD_TIME_TIER5_MAX_BLOCKS.get()) return BUILD_TIME_TIER5_SECONDS.get();
        if (blockCount < BUILD_TIME_TIER6_MAX_BLOCKS.get()) return BUILD_TIME_TIER6_SECONDS.get();
        return BUILD_TIME_TIER7_SECONDS.get();
    }

    /** 材料空间（非次元分区）的实际上限；配置为 0 时取捕获体积上限的 1.2 倍。 */
    public static int materialSpaceMaxCount() {
        int configured = MATERIAL_SPACE_MAX_COUNT.get();
        return configured > 0 ? configured : (int) (ABSOLUTE_MAX_CAPTURE_VOLUME * 1.2);
    }

    public static void onConfigReload(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            clearCache();
        }
    }
}
