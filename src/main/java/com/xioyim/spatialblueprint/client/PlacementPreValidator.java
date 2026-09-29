package com.xioyim.spatialblueprint.client;

import com.xioyim.spatialblueprint.util.BlueprintData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Client-side pre-validation of a blueprint placement.
 * Mirrors the checks in Capsule's {@code StructureSaver.checkDestination()}:
 * <ul>
 *   <li>CREATIVE  — OP + 创造：跳过全部校验，界面给红字警告</li>
 *   <li>EXTRA_BLOCKS — a non-replaceable block exists in the target volume（仅生存）</li>
 *   <li>ENTITY_BLOCKING — a non-player LivingEntity occupies the target volume（仅生存）</li>
 *   <li>OUT_OF_BOUNDS — a target Y position exceeds world build limits</li>
 *   <li>CHUNK_NOT_LOADED — a target chunk is not loaded on the client</li>
 *   <li>OK — all checks passed</li>
 * </ul>
 *
 * <p>Only runs on button press / screen open — not every frame.</p>
 */
@OnlyIn(Dist.CLIENT)
public class PlacementPreValidator {

    public enum Status {
        CREATIVE,
        OK,
        EXTRA_BLOCKS,
        ENTITY_BLOCKING,
        OUT_OF_BOUNDS,
        CHUNK_NOT_LOADED
    }

    public final Status status;

    private PlacementPreValidator(Status status) {
        this.status = status;
    }

    /**
     * Validates the given placement.
     *
     * @param level      client-side world (mc.level)
     * @param player     the local player
     * @param origin     bottom-corner of the structure in world space; null → skip block checks
     * @param data       blueprint data
     * @param rotation   current rotation
     * @param clearFirst true = clear entire bounding box before placing;
     *                   false = replace-only mode (survival: EXTRA_BLOCKS still checked)
     */
    public static PlacementPreValidator validate(Level level, Player player,
                                                  @Nullable BlockPos origin,
                                                  BlueprintData data, Rotation rotation,
                                                  boolean clearFirst) {
        // OP + 创造模式：强制放置，界面上给红字「无视任何限制」警告。
        if (player.isCreative() && player.hasPermissions(2)) {
            return new PlacementPreValidator(Status.CREATIVE);
        }

        // 非 OP 的创造模式玩家：同样不受「额外方块 / 实体阻挡」约束。
        //
        // 创造模式本来就能随手把挡路的方块拆掉，拿这两条拦他毫无意义 ——
        // 真正能拦住他的是服务端的领地插件预检（precheckPlacement 会 post BreakEvent，
        // 与游戏模式无关）。界面上不给红字「OP强制放置」，按钮走普通样式，
        // 被领地拦下时由服务端回横幅说明。
        //
        // Y 越界与区块未加载仍然要查：那是物理上放不下，不是权限问题。
        boolean creativeBypass = player.isCreative();

        // No target yet (AlignMode.OFF or no look target) — treat as OK
        if (origin == null) return new PlacementPreValidator(Status.OK);

        BlockPos size = data.size;
        int sX = size.getX(), sY = size.getY(), sZ = size.getZ();

        // World bounding box of the rotated structure (X/Z swap for 90° rotations)
        boolean swapXZ = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90);
        int bbX = swapXZ ? sZ : sX;
        int bbZ = swapXZ ? sX : sZ;

        // ── Entity check — one bulk AABB query ──────────────────────────
        // Mirrors Capsule: block if any non-player LivingEntity is in the volume.
        AABB fullBox = new AABB(
                origin.getX(),        origin.getY(),        origin.getZ(),
                origin.getX() + bbX,  origin.getY() + sY,   origin.getZ() + bbZ
        );
        List<LivingEntity> entities = creativeBypass ? java.util.List.of() : level.getEntitiesOfClass(
                LivingEntity.class, fullBox, e -> !(e instanceof Player));
        if (!entities.isEmpty()) return new PlacementPreValidator(Status.ENTITY_BLOCKING);

        // ── Per-block checks ─────────────────────────────────────────────
        for (int x = 0; x < sX; x++) {
            for (int y = 0; y < sY; y++) {
                for (int z = 0; z < sZ; z++) {
                    BlockPos worldPos = origin.offset(
                            transformX(x, z, size, rotation),
                            y,
                            transformZ(x, z, size, rotation)
                    );

                    // Y bounds
                    if (worldPos.getY() >= level.getMaxBuildHeight()
                            || worldPos.getY() < level.getMinBuildHeight()) {
                        return new PlacementPreValidator(Status.OUT_OF_BOUNDS);
                    }

                    // Chunk loaded on client
                    if (!level.hasChunkAt(worldPos)) {
                        return new PlacementPreValidator(Status.CHUNK_NOT_LOADED);
                    }

                    // Non-replaceable block — survival only.
                    // 创造模式（含非 OP）跳过：拆方块对他不是障碍，拦下来只会挡路。
                    if (creativeBypass) continue;
                    BlockState existing = level.getBlockState(worldPos);
                    if (!existing.isAir() && !existing.canBeReplaced()) {
                        return new PlacementPreValidator(Status.EXTRA_BLOCKS);
                    }
                }
            }
        }

        return new PlacementPreValidator(Status.OK);
    }

    /** Overload for callers that always use clearFirst=true (engineering blueprint screen). */
    public static PlacementPreValidator validate(Level level, Player player,
                                                  @Nullable BlockPos origin,
                                                  BlueprintData data, Rotation rotation) {
        return validate(level, player, origin, data, rotation, true);
    }

    // ── Transform helpers delegated to shared BlueprintTransform util (#12) ──

    private static int transformX(int x, int z, BlockPos size, Rotation rotation) {
        return com.xioyim.spatialblueprint.util.BlueprintTransform.x(x, 0, z, size, rotation);
    }

    private static int transformZ(int x, int z, BlockPos size, Rotation rotation) {
        return com.xioyim.spatialblueprint.util.BlueprintTransform.z(x, 0, z, size, rotation);
    }
}
