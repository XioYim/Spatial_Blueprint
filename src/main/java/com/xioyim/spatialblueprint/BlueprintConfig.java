package com.xioyim.spatialblueprint;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BlueprintConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String>       FLUID_COLOR_WATER;
    public static final ForgeConfigSpec.ConfigValue<String>       FLUID_COLOR_LAVA;
    public static final ForgeConfigSpec.ConfigValue<List<String>> EXCLUDED_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<String>> BLOCK_SUBSTITUTIONS;
    public static final ForgeConfigSpec.IntValue WITHDRAW_XP_RATIO;
    public static final ForgeConfigSpec.IntValue MAX_WITHDRAW_XP_COST;
    public static final ForgeConfigSpec.IntValue MAX_WITHDRAW_COUNT;
    public static final ForgeConfigSpec.IntValue ANIMATION_SPEED;
    public static final ForgeConfigSpec.IntValue ANIMATION_DURATION_MIN;
    public static final ForgeConfigSpec.IntValue ANIMATION_DURATION_MAX;
    public static final ForgeConfigSpec.BooleanValue ALLOW_CREATIVE_DURATION_EDIT;
    // ── 构建时间自动分档（6档）────────────────────────────────────────────
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER1_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER1_SECONDS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER2_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER2_SECONDS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER3_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER3_SECONDS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER4_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER4_SECONDS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER5_MAX_BLOCKS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER5_SECONDS;
    public static final ForgeConfigSpec.IntValue BUILD_TIME_TIER6_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_CAPTURE_VOLUME_CREATIVE;
    public static final ForgeConfigSpec.IntValue MAX_CAPTURE_VOLUME_SURVIVAL;
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
    public static final ForgeConfigSpec.IntValue PREVIEW_DISTANCE;
    public static final ForgeConfigSpec.IntValue RENDER_CANCEL_MARGIN;
    public static final ForgeConfigSpec.IntValue PREVIEW_BROADCAST_RADIUS;
    public static final ForgeConfigSpec.IntValue PREVIEW_BROADCAST_INTERVAL;
    public static final ForgeConfigSpec.IntValue EXTRA_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_UNDO_HISTORY;
    public static final ForgeConfigSpec.BooleanValue LARGE_BLUEPRINT_BROADCAST_ENABLED;
    public static final ForgeConfigSpec.IntValue LARGE_BLUEPRINT_BROADCAST_THRESHOLD;
    public static final ForgeConfigSpec.ConfigValue<List<String>> LATE_PLACE_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<String>> HIDDEN_PREVIEW_BLOCKS;
    /** 是否对创造模式玩家也执行领地保护检测（BUG-01 修复开关）。默认 false（保持原行为）。 */
    public static final ForgeConfigSpec.BooleanValue DENY_CREATIVE_PLACEMENT_IN_PROTECTED_AREA;
    // ── 上传安全检测 ────────────────────────────────────────────────────────
    public static final ForgeConfigSpec.IntValue MAX_UPLOAD_FILE_BYTES;
    public static final ForgeConfigSpec.IntValue MAX_NBT_DEPTH;
    public static final ForgeConfigSpec.IntValue MAX_BOUNDING_VOLUME;
    public static final ForgeConfigSpec.IntValue UPLOAD_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue UPLOAD_COOLDOWN_SECONDS_OP;
    public static final ForgeConfigSpec.IntValue UPLOAD_MAX_PER_MINUTE;

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
                        "minecraft:jigsaw",
                        "minecraft:barrier"
                ));

        MAX_CAPTURE_VOLUME_CREATIVE = BUILDER
                .comment("Maximum capture volume (W×H×D) for creative-mode players.\n"
                        + "Default: 300000. Hard cap enforced by the mod: " + ABSOLUTE_MAX_CAPTURE_VOLUME + " blocks.\n"
                        + "To raise the hard cap, edit ABSOLUTE_MAX_CAPTURE_VOLUME in BlueprintConfig.java.")
                .defineInRange("maxCaptureVolumeCreative", 300000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        MAX_CAPTURE_VOLUME_SURVIVAL = BUILDER
                .comment("Maximum capture volume (W×H×D) for survival/adventure-mode players.\n"
                        + "Default: 100000. Hard cap enforced by the mod: " + ABSOLUTE_MAX_CAPTURE_VOLUME + " blocks.\n"
                        + "To raise the hard cap, edit ABSOLUTE_MAX_CAPTURE_VOLUME in BlueprintConfig.java.")
                .defineInRange("maxCaptureVolumeSurvival", 100000, 1, ABSOLUTE_MAX_CAPTURE_VOLUME);

        PREVIEW_DISTANCE = BUILDER
                .comment("Max distance (in blocks) for blueprint placement preview and deployment.")
                .defineInRange("previewDistance", 64, 1, 256);

        RENDER_CANCEL_MARGIN = BUILDER
                .comment("Extra distance (in blocks) added to the building's half-diagonal for the auto-cancel radius.\n"
                        + "If the player moves farther than (halfDiagonal + margin) from the building center,\n"
                        + "the preview is hidden and deployment is blocked. Default: 64.")
                .defineInRange("renderCancelMargin", 64, 0, 512);

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

        BLOCK_SUBSTITUTIONS = BUILDER
                .comment("Block substitution map for the material list.\n"
                        + "When a blueprint captures the left-side block, it is recorded in the material\n"
                        + "list as the right-side block/item instead. This affects both the material count\n"
                        + "and the displayed icon, so survival players know which obtainable item to gather.\n"
                        + "\n"
                        + "Format: one entry per line as \"sourceBlockId=targetBlockOrItemId\".\n"
                        + "Example: minecraft:weeping_vines_plant=minecraft:weeping_vines\n"
                        + "         minecraft:water=minecraft:water_bucket\n"
                        + "\n"
                        + "The defaults cover all vanilla blocks that have no direct item form or whose\n"
                        + "block registry name differs from the obtainable item (wall signs, crop stages,\n"
                        + "plant variants, liquid blocks, potted plants, etc.).")
                .define("blockSubstitutions", List.of(
                        // ── Liquids & fire ─────────────────────────────────────────────────
                        "minecraft:water=minecraft:water_bucket",
                        "minecraft:lava=minecraft:lava_bucket",
                        "minecraft:bubble_column=minecraft:water_bucket",
                        "minecraft:fire=minecraft:flint_and_steel",
                        "minecraft:soul_fire=minecraft:soul_torch",
                        "minecraft:powder_snow=minecraft:powder_snow_bucket",
                        // ── Cauldrons (filled variants) ────────────────────────────────────
                        "minecraft:water_cauldron=minecraft:water_bucket",
                        "minecraft:lava_cauldron=minecraft:lava_bucket",
                        "minecraft:powder_snow_cauldron=minecraft:powder_snow_bucket",
                        // ── Piston ────────────────────────────────────────────────────────
                        "minecraft:piston_head=minecraft:piston",
                        // ── Redstone & string ─────────────────────────────────────────────
                        "minecraft:redstone_wire=minecraft:redstone",
                        "minecraft:tripwire=minecraft:string",
                        // ── Crops & plant growth stages ───────────────────────────────────
                        "minecraft:carrots=minecraft:carrot",
                        "minecraft:potatoes=minecraft:potato",
                        "minecraft:beetroots=minecraft:beetroot_seeds",
                        "minecraft:cocoa=minecraft:cocoa_beans",
                        "minecraft:melon_stem=minecraft:melon_seeds",
                        "minecraft:pumpkin_stem=minecraft:pumpkin_seeds",
                        "minecraft:attached_melon_stem=minecraft:melon_seeds",
                        "minecraft:attached_pumpkin_stem=minecraft:pumpkin_seeds",
                        "minecraft:sweet_berry_bush=minecraft:sweet_berries",
                        "minecraft:torchflower_crop=minecraft:torchflower_seeds",
                        "minecraft:pitcher_crop=minecraft:pitcher_pod",
                        // ── Vine / seagrass plant-block variants ──────────────────────────
                        "minecraft:kelp_plant=minecraft:kelp",
                        "minecraft:weeping_vines_plant=minecraft:weeping_vines",
                        "minecraft:twisting_vines_plant=minecraft:twisting_vines",
                        "minecraft:cave_vines=minecraft:glow_berries",
                        "minecraft:cave_vines_plant=minecraft:glow_berries",
                        "minecraft:tall_seagrass=minecraft:seagrass",
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
                        // ── Wall banners (all 16 colors) ──────────────────────────────────
                        "minecraft:white_wall_banner=minecraft:white_banner",
                        "minecraft:orange_wall_banner=minecraft:orange_banner",
                        "minecraft:magenta_wall_banner=minecraft:magenta_banner",
                        "minecraft:light_blue_wall_banner=minecraft:light_blue_banner",
                        "minecraft:yellow_wall_banner=minecraft:yellow_banner",
                        "minecraft:lime_wall_banner=minecraft:lime_banner",
                        "minecraft:pink_wall_banner=minecraft:pink_banner",
                        "minecraft:gray_wall_banner=minecraft:gray_banner",
                        "minecraft:light_gray_wall_banner=minecraft:light_gray_banner",
                        "minecraft:cyan_wall_banner=minecraft:cyan_banner",
                        "minecraft:purple_wall_banner=minecraft:purple_banner",
                        "minecraft:blue_wall_banner=minecraft:blue_banner",
                        "minecraft:brown_wall_banner=minecraft:brown_banner",
                        "minecraft:green_wall_banner=minecraft:green_banner",
                        "minecraft:red_wall_banner=minecraft:red_banner",
                        "minecraft:black_wall_banner=minecraft:black_banner",
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
                        "minecraft:potted_oak_sapling=minecraft:oak_sapling",
                        "minecraft:potted_spruce_sapling=minecraft:spruce_sapling",
                        "minecraft:potted_birch_sapling=minecraft:birch_sapling",
                        "minecraft:potted_jungle_sapling=minecraft:jungle_sapling",
                        "minecraft:potted_acacia_sapling=minecraft:acacia_sapling",
                        "minecraft:potted_dark_oak_sapling=minecraft:dark_oak_sapling",
                        "minecraft:potted_mangrove_propagule=minecraft:mangrove_propagule",
                        "minecraft:potted_cherry_sapling=minecraft:cherry_sapling",
                        "minecraft:potted_bamboo=minecraft:bamboo",
                        "minecraft:potted_fern=minecraft:fern",
                        "minecraft:potted_dandelion=minecraft:dandelion",
                        "minecraft:potted_poppy=minecraft:poppy",
                        "minecraft:potted_blue_orchid=minecraft:blue_orchid",
                        "minecraft:potted_allium=minecraft:allium",
                        "minecraft:potted_azure_bluet=minecraft:azure_bluet",
                        "minecraft:potted_red_tulip=minecraft:red_tulip",
                        "minecraft:potted_orange_tulip=minecraft:orange_tulip",
                        "minecraft:potted_white_tulip=minecraft:white_tulip",
                        "minecraft:potted_pink_tulip=minecraft:pink_tulip",
                        "minecraft:potted_oxeye_daisy=minecraft:oxeye_daisy",
                        "minecraft:potted_cornflower=minecraft:cornflower",
                        "minecraft:potted_lily_of_the_valley=minecraft:lily_of_the_valley",
                        "minecraft:potted_wither_rose=minecraft:wither_rose",
                        "minecraft:potted_brown_mushroom=minecraft:brown_mushroom",
                        "minecraft:potted_red_mushroom=minecraft:red_mushroom",
                        "minecraft:potted_crimson_fungus=minecraft:crimson_fungus",
                        "minecraft:potted_warped_fungus=minecraft:warped_fungus",
                        "minecraft:potted_crimson_roots=minecraft:crimson_roots",
                        "minecraft:potted_warped_roots=minecraft:warped_roots",
                        "minecraft:potted_dead_bush=minecraft:dead_bush",
                        "minecraft:potted_cactus=minecraft:cactus",
                        "minecraft:potted_azalea_bush=minecraft:azalea",
                        "minecraft:potted_flowering_azalea_bush=minecraft:flowering_azalea",
                        "minecraft:potted_torchflower=minecraft:torchflower"
                ));

        WITHDRAW_XP_RATIO = BUILDER
                .comment("Experience level cost ratio for quick-withdrawing materials from a blueprint.\n"
                        + "Cost = ceil(material_types / ratio), capped at maxWithdrawXpCost, minimum 1.\n"
                        + "Example: 10 material types with ratio 6 → ceil(10/6) = 2 levels.\n"
                        + "Set to 0 to disable XP cost entirely (free withdrawals).\n"
                        + "Per-blueprint override (set by creative players via ✦ button) takes priority.")
                .defineInRange("withdrawXpRatio", 6, 0, 100);

        MAX_WITHDRAW_XP_COST = BUILDER
                .comment("Maximum experience levels deducted per quick-withdraw operation.\n"
                        + "Acts as a hard cap on the formula-calculated cost.\n"
                        + "Per-blueprint custom XP (set via ✦) is NOT capped by this value.")
                .defineInRange("maxWithdrawXpCost", 30, 1, 1000);

        MAX_WITHDRAW_COUNT = BUILDER
                .comment("Maximum number of times a survival player may quick-withdraw from one blueprint item.\n"
                        + "After reaching this limit the ♻ button is disabled. Creative mode is unrestricted.\n"
                        + "Per-blueprint override via the ⊘ button in the blueprint library.")
                .defineInRange("maxWithdrawCount", 5, 1, 1000);

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
                .comment("Build-time tier 3: < this many blocks → tier3 seconds. Default: 80000")
                .defineInRange("buildTimeTier3MaxBlocks", 80000, 1, 2000000);
        BUILD_TIME_TIER3_SECONDS = BUILDER
                .comment("Build-time tier 3 duration in seconds. Default: 16")
                .defineInRange("buildTimeTier3Seconds", 16, 1, 3600);

        BUILD_TIME_TIER4_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 4: < this many blocks → tier4 seconds. Default: 100000")
                .defineInRange("buildTimeTier4MaxBlocks", 100000, 1, 2000000);
        BUILD_TIME_TIER4_SECONDS = BUILDER
                .comment("Build-time tier 4 duration in seconds. Default: 25")
                .defineInRange("buildTimeTier4Seconds", 25, 1, 3600);

        BUILD_TIME_TIER5_MAX_BLOCKS = BUILDER
                .comment("Build-time tier 5: < this many blocks → tier5 seconds. Default: 200000")
                .defineInRange("buildTimeTier5MaxBlocks", 200000, 1, 2000000);
        BUILD_TIME_TIER5_SECONDS = BUILDER
                .comment("Build-time tier 5 duration in seconds. Default: 30")
                .defineInRange("buildTimeTier5Seconds", 30, 1, 3600);

        BUILD_TIME_TIER6_SECONDS = BUILDER
                .comment("Build-time tier 6 (catch-all for >= tier5 blocks). Default: 40")
                .defineInRange("buildTimeTier6Seconds", 40, 1, 3600);

        ANIMATION_DURATION_MIN = BUILDER
                .comment("Minimum animation duration (seconds) for all players (including OP and creative). Default: 3")
                .defineInRange("animationDurationMin", 3, 1, 3600);

        ANIMATION_DURATION_MAX = BUILDER
                .comment("Maximum animation duration (seconds) for all players (including OP and creative). Default: 600")
                .defineInRange("animationDurationMax", 600, 1, 3600);

        ALLOW_CREATIVE_DURATION_EDIT = BUILDER
                .comment("If true, creative mode players can manually edit the build duration (same as OP).\n"
                        + "If false, creative players are read-only and duration is auto-calculated. Default: false")
                .define("allowCreativeDurationEdit", false);

        EXTRA_COOLDOWN_SECONDS = BUILDER
                .comment("Extra cooldown (seconds) added on top of the animation duration after a blueprint\n"
                        + "finishes deploying. Total cooldown = animationDuration + extraCooldown.\n"
                        + "During this time the item shows as a 'building blueprint' and cannot be re-used.")
                .defineInRange("extraCooldownSeconds", 3, 0, 3600);

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
                        + "during blueprint placement (same as survival players).\n"
                        + "Default: false (creative players bypass territory protection — vanilla behavior).")
                .define("denyCreativePlacementInProtectedArea", false);

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
        BUILDER.push("preview");

        FLUID_COLOR_WATER = BUILDER
                .comment("Ghost preview color for water blocks in blueprint rendering (6-digit RGB hex).\n"
                        + "Fluid blocks are rendered as opaque solid-color cubes to avoid animated texture overhead.\n"
                        + "Default: 3F76E4 (Minecraft water blue). Prefix '#' is optional.")
                .define("fluidPreviewColorWater", "3F76E4");

        FLUID_COLOR_LAVA = BUILDER
                .comment("Ghost preview color for lava blocks in blueprint rendering (6-digit RGB hex).\n"
                        + "Default: FF6400 (lava orange).")
                .define("fluidPreviewColorLava", "FF6400");

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
        SPEC = BUILDER.build();
    }

    private static List<Block> cachedExcludedBlocks              = null;
    private static Map<String, String> cachedSubstitutions       = null;
    private static java.util.Set<String> cachedLatePlaceBlocks   = null;
    private static java.util.Set<String> cachedHiddenPreviewBlocks = null;

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
     * Returns the block-substitution map: source block registry ID → target block/item registry ID.
     *
     * <p>Applied at material-list creation time so the recorded ID is the obtainable target.
     * For example {@code minecraft:weeping_vines_plant} → {@code minecraft:weeping_vines}
     * means survival players are told to gather weeping vines, not the unobtainable plant form.</p>
     */
    public static Map<String, String> getBlockSubstitutions() {
        if (cachedSubstitutions == null) {
            cachedSubstitutions = new LinkedHashMap<>();
            for (String entry : BLOCK_SUBSTITUTIONS.get()) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    cachedSubstitutions.put(
                            entry.substring(0, eq).trim(),
                            entry.substring(eq + 1).trim());
                }
            }
        }
        return cachedSubstitutions;
    }

    /** Returns the set of block registry IDs that should be force-placed last (round 4). */
    public static java.util.Set<String> getLatePlaceBlocks() {
        if (cachedLatePlaceBlocks == null) {
            cachedLatePlaceBlocks = new java.util.HashSet<>(LATE_PLACE_BLOCKS.get());
        }
        return cachedLatePlaceBlocks;
    }

    /** Returns the 0x00RRGGBB color for water ghost blocks in preview. */
    public static int getWaterPreviewColor() { return parseHexRgb(FLUID_COLOR_WATER.get()); }
    /** Returns the 0x00RRGGBB color for lava ghost blocks in preview. */
    public static int getLavaPreviewColor()  { return parseHexRgb(FLUID_COLOR_LAVA.get()); }

    private static int parseHexRgb(String hex) {
        try { return Integer.parseUnsignedInt(hex.strip().replaceFirst("^#", ""), 16) & 0xFFFFFF; }
        catch (NumberFormatException e) { return 0xFFFFFF; }
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
        cachedSubstitutions         = null;
        cachedLatePlaceBlocks       = null;
        cachedHiddenPreviewBlocks   = null;
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
        return BUILD_TIME_TIER6_SECONDS.get();
    }

    public static void onConfigReload(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            clearCache();
        }
    }
}
