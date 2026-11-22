package mod.advancedmobs.ability.guardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Tidal Bulwark Ring (Guardian ability 3 of 5, terrain).
 *
 * WHAT IT DOES: with a sweep of its tail the guardian raises a
 * circular wall of prismarine and sea lantern blocks out of the
 * ground or the water, trapping itself and its prey inside a
 * glowing arena. Currents swirl inside the ring, dragging
 * swimmers downward, while the guardian, at home in its own
 * current, circles freely.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two
 * modes:
 * - mobGriefing ON: the wall is placed and STAYS. The design text
 *   says the wall sinks back down after the fight; Carter's
 *   standing terrain rule supersedes that line, the same way it
 *   superseded Gravemud Mire's restore and Magma Ring Rising's
 *   crumble. There is no restore: end() touches no blocks, and
 *   the ring remains as a permanent prismarine arena afterwards.
 *   The current zone still lasts only the ability duration.
 * - mobGriefing OFF: no blocks are changed at all. The ability
 *   runs as a pure zone effect for its duration (same current,
 *   same self buff, same particles, no wall), and the world is
 *   left exactly as it was.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 4 blocks
 *   (distance squared 16) of the guardian, so both are inside the
 *   ring when it rises (ring radius 5). A target already outside
 *   the ring never gets walled out by its own trap.
 * - Duration: exactly 300 ticks (15 seconds) from start(). This
 *   is the current zone's lifetime; in mobGriefing mode the wall
 *   outlives it.
 * - The wall (mobGriefing mode only), centered on the guardian's
 *   block position at start(): columns at Euclidean distance in
 *   [4.5, 5.5) from the center (about 32 columns). For each
 *   column, the floor is the highest solid block found scanning
 *   from 2 above the center's feet level down to 6 below it
 *   (solid means not replaceable and holding no fluid); a column
 *   with no floor in that range is skipped, so the wall never
 *   floats. The wall is 3 blocks tall from the floor: each
 *   position is filled only when it can be replaced (air, grass,
 *   and other replaceable blocks) or holds water, has no block
 *   entity, and is not already the block being placed. Material
 *   is PRISMARINE, except the middle block of every 4th column
 *   (column index divisible by 4), which is a SEA_LANTERN: the
 *   glowing band that lights the arena. At most 120 blocks
 *   change. Containers, unbreakable blocks, and other fluids are
 *   never touched, and nothing replaced drops as an item.
 * - The current: every 5 ticks, enemies (package rule) inside
 *   the ring (horizontal distance at most 5.5 from the recorded
 *   center, up to 4 below and 4 above its feet level) that are in
 *   water are dragged down: their vertical velocity drops by
 *   0.10 per pulse, floored at -0.55, and their velocity is
 *   flagged for sync (hurtMarked). On land inside the ring the
 *   current does nothing: it is a water current, not a gravity
 *   field. This runs in both terrain modes.
 * - The guardian itself, for the duration: a
 *   WATER_MOVEMENT_EFFICIENCY add_value modifier of +1.0
 *   (modifier id advancedmobs:tidal_bulwark_efficiency, removed
 *   in end()), so it swims and wades through its own current at
 *   full land speed, immune to the drag by simply never being its
 *   own enemy.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: at start, 12 SPLASH and 8
 * CURRENT_DOWN particles burst around the ring line as the wall
 * breaks the surface, with a deep GUARDIAN_AMBIENT (volume 1.2,
 * pitch 0.6). Every 10 ticks during the zone, 4 CURRENT_DOWN
 * particles stream down at random spots inside the ring and 3
 * SPLASH particles kick up over random wall columns. The sea
 * lantern band glows from the wall itself.
 *
 * PERFORMANCE: block work happens once in start() (about 32
 * columns, each a scan of at most 9 positions plus at most 3
 * placements, mobGriefing mode only) and never again. tick()
 * returns immediately except every 5th tick (current: one bounded
 * entity query over an 11 by 8 by 11 box) and every 10th tick (7
 * particles at pseudo-random positions computed from the context
 * random). Particle budget per activation: 20 at start plus at
 * most 30 zone pulses times 7 = 210, total at most 230 particles.
 *
 * State tag keys: "cx", "cy", "cz" (ring center block pos, the
 * guardian's feet level at start). No block list is stored:
 * placed wall blocks are permanent and never revisited.
 */
public final class TidalBulwarkRingAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int CURRENT_INTERVAL = 5;
    private static final int MAX_PLACEMENTS = 120;
    private static final double RING_MIN = 4.5;
    private static final double RING_MAX = 5.5;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "tidal_bulwark_efficiency");

    @Override
    public String id() {
        return "tidal_bulwark_ring";
    }

    @Override
    public String displayName() {
        return "Tidal Bulwark Ring";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 16.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        BlockPos center = mob.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());

        // Terrain (mobGriefing mode only): the permanent wall.
        // With mobGriefing off, no block is touched and the
        // ability is a zone effect only. See the TERRAIN RULE in
        // the class comment.
        if (ctx.mobGriefingAllowed()) {
            raiseWall(level, center);
        }

        // The guardian is at home in its own current.
        AttributeInstance efficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The wall breaks the surface.
        double cx = center.getX() + 0.5, cy = center.getY(), cz = center.getZ() + 0.5;
        level.sendParticles(ParticleTypes.SPLASH, cx, cy + 2.5, cz, 12, 4.6, 0.5, 4.6, 0.2);
        level.sendParticles(ParticleTypes.CURRENT_DOWN, cx, cy + 1.5, cz, 8, 4.2, 0.8, 4.2, 0.1);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.6f);
    }

    /**
     * Places the permanent ring wall (see the class comment for
     * the shape, the floor scan, and the safety checks). The wall
     * is placed for good: nothing records it for removal.
     */
    private static void raiseWall(ServerLevel level, BlockPos center) {
        int placed = 0;
        int columnIndex = 0;
        for (int dx = -6; dx <= 6 && placed < MAX_PLACEMENTS; dx++) {
            for (int dz = -6; dz <= 6 && placed < MAX_PLACEMENTS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < RING_MIN || dist >= RING_MAX) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;

                // The floor: the highest solid block in the scan
                // range. No floor, no column (the wall never
                // floats).
                int floorY = Integer.MIN_VALUE;
                for (int y = center.getY() + 2; y >= center.getY() - 6; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState below = level.getBlockState(pos);
                    if (!below.canBeReplaced() && below.getFluidState().isEmpty()) {
                        floorY = y;
                        break;
                    }
                }
                if (floorY == Integer.MIN_VALUE) {
                    columnIndex++;
                    continue;
                }

                boolean lanternColumn = columnIndex % 4 == 0;
                for (int h = 1; h <= 3 && placed < MAX_PLACEMENTS; h++) {
                    BlockPos pos = new BlockPos(x, floorY + h, z);
                    BlockState existing = level.getBlockState(pos);
                    boolean wetOrEmpty = existing.canBeReplaced()
                        || existing.getFluidState().getType() == net.minecraft.world.level.material.Fluids.WATER;
                    if (!wetOrEmpty || level.getBlockEntity(pos) != null) {
                        continue;
                    }
                    Block block = lanternColumn && h == 2 ? Blocks.SEA_LANTERN : Blocks.PRISMARINE;
                    if (existing.is(block)) {
                        continue;
                    }
                    level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
                    placed++;
                }
                columnIndex++;
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean current = ticks % CURRENT_INTERVAL == 0;
        boolean spray = ticks % 10 == 0;
        if (!current && !spray) {
            return; // Cheap early-out: work runs on the 5 and 10 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;

        if (current) {
            // The drag: swimmers inside the ring are pulled down.
            // Runs in both terrain modes; the zone exists whether
            // or not the wall was allowed to rise.
            List<LivingEntity> victims = GuardianAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 5.5, 4.0, 4.0);
            for (LivingEntity victim : victims) {
                double dx = victim.getX() - cx;
                double dz = victim.getZ() - cz;
                if (dx * dx + dz * dz > 30.25 || !victim.isInWater()) {
                    continue;
                }
                Vec3 motion = victim.getDeltaMovement();
                victim.setDeltaMovement(motion.x, Math.max(motion.y - 0.10, -0.55), motion.z);
                victim.hurtMarked = true;
            }
        }
        if (spray) {
            // Down-currents streaming inside the ring, spray over
            // the wall line.
            for (int i = 0; i < 4; i++) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                double distance = ctx.random().nextDouble() * 4.5;
                level.sendParticles(ParticleTypes.CURRENT_DOWN,
                    cx + Math.cos(angle) * distance, cy + 1.8, cz + Math.sin(angle) * distance,
                    1, 0.0, 0.0, 0.0, 0.05);
            }
            for (int i = 0; i < 3; i++) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                level.sendParticles(ParticleTypes.SPLASH,
                    cx + Math.cos(angle) * 5.0, cy + 2.8, cz + Math.sin(angle) * 5.0,
                    1, 0.1, 0.1, 0.1, 0.05);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The guardian's own buff ends with the zone. The wall,
        // when placed, is permanent terrain and is deliberately
        // NOT touched here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
    }
}
