package mod.advancedmobs.ability.bat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;

import java.util.List;

/**
 * Stalactite Drop (Bat ability 5 of 5, terrain attack).
 *
 * WHAT IT DOES: the bat darts to the ceiling above its
 * enemy and worries at the stone until a pointed dripstone
 * spike gives way, dropping as a real falling block with a
 * whistle of falling dust. The spike deals heavy piercing
 * damage where it lands and pins the spot as a hazard
 * shard, a small up-pointed dripstone tip left standing in
 * the ground, and the bat is already choosing the next
 * stalactite: two drops per activation.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bat's current target within 14 blocks (distance
 *   squared 196), plus a ceiling above the target: scanning
 *   up from the target's block position, 3 to 14 blocks up,
 *   finds a block with a collision shape whose cell below
 *   has none (the anchor). Outdoors or under a low ceiling
 *   the ability simply never fires. The scan is reads only.
 * - The dart (start of each cycle): the anchor is found
 *   again above the target's live position (it may have
 *   moved since the trigger check) and stored. The bat
 *   takes off if resting (setResting(false), BAT_TAKEOFF
 *   volume 1.0 pitch 1.0) and steers at a point 1.6 blocks
 *   below the anchor at 1.0 blocks per tick for at most 30
 *   ticks; reaching within 1.1 blocks, or the timeout,
 *   starts the drop. A cycle whose target has lost its
 *   ceiling (no anchor found) ends the ability early.
 * - The drop: a real FallingBlockEntity of the pointed
 *   dripstone default state (a stalactite tip pointing
 *   down) spawns in the cell below the anchor
 *   (FallingBlockEntity.fall), with dropItem disabled and
 *   disableDrop set so a failed landing never scatters an
 *   item, and its own vanilla entity-crush damage zeroed
 *   through setHurtsEntities(0, 0): the impact below is the
 *   one damage number of record. Its velocity is set to a
 *   gentle (0, -0.15, 0). A BAT_AMBIENT (volume 0.9, pitch
 *   1.5, the worrying-at-the-stone screech) and 4
 *   FALLING_DUST particles of the dripstone state mark the
 *   release.
 * - The fall (tracked every tick): the spike's last known
 *   position is refreshed and 2 FALLING_DUST particles of
 *   the dripstone state trail it per tick, the design's
 *   whistle of falling dust. When the spike resolves (the
 *   entity is gone because it landed), or is seen on the
 *   ground, the impact runs at its last known position. A
 *   down-pointing tip cannot survive standing on the ground,
 *   so vanilla destroys the landed spike without placing it;
 *   the only lasting block is the shard placed by the impact
 *   itself, below. With mobGriefing OFF the spike is
 *   intercepted just above the ground (the Icicle Fall
 *   pattern: falling, and the gap to the first collidable
 *   block below within one tick of travel) and discarded
 *   before its own tick can place anything, and no shard is
 *   placed either; the impact still runs in full. Hard
 *   timeout: after 60 flight ticks a still-flying spike
 *   impacts at its last position and is discarded.
 * - The impact: 10 BLOCK particles of the dripstone state
 *   and a GENERIC_EXPLODE sound (volume 0.6, pitch 1.3, the
 *   stone shattering); every enemy (package rules) within
 *   radius 2.0 (1.5 below to 2.0 above the impact) takes
 *   7.0 damage through
 *   level.damageSources().fallingStalactite(caster), the
 *   falling stalactite damage type credited to the bat, the
 *   design's fall damage type line and the heavy piercing
 *   hit of the design.
 * - The hazard shard (mobGriefing ON only): the ground at
 *   the impact column is found by scanning down at most 6
 *   blocks from the impact point for the first collidable
 *   block; in the cell directly above it, if that cell's
 *   state canBeReplaced(), holds no block entity, and the
 *   shard state canSurvive there, one pointed dripstone
 *   block is placed pointing UP with thickness TIP: the
 *   hazard shard, permanent terrain per the standing rule,
 *   never removed by end(). With mobGriefing off no shard
 *   is placed and the impact's damage and particles are the
 *   whole effect.
 * - The second drop: after the first impact the cycle
 *   repeats once against the target's new position. After
 *   the second impact the ability finishes 5 ticks later;
 *   hard cap 240 ticks.
 * - end(): a still-flying spike is discarded with no
 *   impact: a terminated drop never lands, which also
 *   guarantees a mobGriefing-off world never receives a
 *   block from a dead bat. A shard already placed is
 *   permanent terrain and is deliberately not touched.
 *
 * WHAT IT LOOKS LIKE: the bat peels off, flutters up to the
 * cave roof over its victim, worries at the stone with a
 * thin screech, and a stone spike peels away in a trail of
 * falling dust and smashes down in a burst of rock shards,
 * leaving a small pale spike standing where it fell.
 *
 * PERFORMANCE: the dart is one steering write per tick; the
 * flight is one UUID resolve and one position read per
 * tick, plus at most 4 block reads on the mobGriefing-off
 * landing check; each impact is one bounded entity query
 * plus at most 6 block reads and one placement check for
 * the shard; the ceiling scans are at most 12 block reads,
 * twice per activation. Particle budget per activation:
 * per drop 4 at release, at most 60 times 2 = 120 falling
 * dust, 10 at impact, so at most 134 per drop and 268 for
 * the two drops.
 *
 * TERRAIN: the falling spike and the hazard shard are this
 * package's only block changes and follow the standing rule
 * exactly: permanent placement only when mobGriefing is on,
 * intercepted and discarded with no placement and no shard
 * when it is off, impact effects running either way. See
 * the class comment of BatAbilitySupport.
 *
 * State tag keys: "phase" (0 darting, 1 falling, 2 done),
 * "cycle" (0 first spike, 1 second), "ax", "ay", "az" (the
 * anchor block), "dart_ticks", "spike" (the falling block's
 * UUID), "px"/"py"/"pz" (its last known position),
 * "flight_ticks", "impact_tick" (the tick the last impact
 * ran).
 */
public final class StalactiteDropAbility implements MobAbility {

    private static final int CYCLES = 2;
    private static final int MAX_DART_TICKS = 30;
    private static final int FLIGHT_TIMEOUT_TICKS = 60;
    private static final int HARD_FINISH_TICKS = 240;
    private static final int CEILING_MIN = 3;
    private static final int CEILING_MAX = 14;
    private static final double DART_SPEED = 1.0;
    private static final double DART_RANGE = 1.1;
    private static final float IMPACT_DAMAGE = 7.0f;
    private static final double IMPACT_RADIUS = 2.0;

    @Override
    public String id() {
        return "stalactite_drop";
    }

    @Override
    public String displayName() {
        return "Stalactite Drop";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0
            && findCeiling(ctx.level(), target) != null;
    }

    /**
     * The ceiling anchor above a target: the first collidable
     * block 3 to 14 blocks above it whose cell below is open.
     * Reads only; null when there is no usable ceiling.
     */
    private static BlockPos findCeiling(Level level, LivingEntity target) {
        BlockPos base = target.blockPosition();
        for (int dy = CEILING_MIN; dy <= CEILING_MAX; dy++) {
            BlockPos pos = base.above(dy);
            BlockState state = level.getBlockState(pos);
            if (state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            BlockPos below = pos.below();
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("cycle", 0);
        beginCycle(ctx);
    }

    /** Starts one drop: find the anchor over the target and dart to it. */
    private void beginCycle(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        BlockPos anchor = (level != null && target != null) ? findCeiling(level, target) : null;
        if (anchor == null) {
            // The target has lost its ceiling: nothing to drop.
            state.putInt("phase", 2);
            state.putInt("impact_tick", ctx.ticksActive());
            return;
        }
        state.putInt("phase", 0);
        state.putInt("dart_ticks", 0);
        state.putInt("ax", anchor.getX());
        state.putInt("ay", anchor.getY());
        state.putInt("az", anchor.getZ());
        if (caster instanceof Bat bat && bat.isResting()) {
            bat.setResting(false);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_TAKEOFF,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int phase = ctx.state().getIntOr("phase", 0);
        if (phase == 0) {
            tickDart(ctx, level);
        } else if (phase == 1) {
            trackSpike(ctx, level);
        }
        // Phase 2: both impacts ran (or no ceiling); nothing left to do.
    }

    /** The dart: fly to the worry point under the anchor, then let the spike go. */
    private void tickDart(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        int dartTicks = state.getIntOr("dart_ticks", 0) + 1;
        state.putInt("dart_ticks", dartTicks);
        double ax = state.getIntOr("ax", 0) + 0.5;
        double ay = state.getIntOr("ay", 0);
        double az = state.getIntOr("az", 0) + 0.5;
        double dist = BatAbilitySupport.steerToward(ctx, ax, ay - 1.6, az, DART_SPEED);
        if (dist <= DART_RANGE || dartTicks >= MAX_DART_TICKS) {
            dropSpike(ctx, level);
        }
    }

    /** The drop: swap the anchor's spike for a real falling block under it. */
    private void dropSpike(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        BlockPos spawn = new BlockPos(state.getIntOr("ax", 0),
            state.getIntOr("ay", 0) - 1, state.getIntOr("az", 0));
        BlockState spikeState = Blocks.POINTED_DRIPSTONE.defaultBlockState();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_AMBIENT,
            SoundSource.HOSTILE, 0.9f, 1.5f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, spikeState),
            spawn.getX() + 0.5, spawn.getY() + 0.5, spawn.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);

        FallingBlockEntity spike = FallingBlockEntity.fall(level, spawn, spikeState);
        if (spike == null) {
            runImpact(ctx, level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            return;
        }
        // A failed landing must never scatter an item, and the
        // impact below is the one damage number of record.
        spike.dropItem = false;
        spike.disableDrop();
        spike.setHurtsEntities(0.0f, 0);
        spike.setDeltaMovement(0.0, -0.15, 0.0);
        spike.hurtMarked = true;
        state.store("spike", UUIDUtil.CODEC, spike.getUUID());
        state.putDouble("px", spike.getX());
        state.putDouble("py", spike.getY());
        state.putDouble("pz", spike.getZ());
        state.putInt("flight_ticks", 0);
        state.putInt("phase", 1);
    }

    /**
     * Watches the falling spike: refreshes its last known
     * position, trails its falling dust, runs the impact when
     * it lands, and, with mobGriefing off, intercepts it just
     * above the ground so no block is ever placed (see the
     * TERRAIN note in the class comment).
     */
    private void trackSpike(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        state.putInt("flight_ticks", state.getIntOr("flight_ticks", 0) + 1);
        Entity spike = BatAbilitySupport.resolveStored(level, state, "spike");
        double x = state.getDoubleOr("px", ctx.mob().getX());
        double y = state.getDoubleOr("py", ctx.mob().getY());
        double z = state.getDoubleOr("pz", ctx.mob().getZ());
        if (spike == null) {
            // Landed and resolved itself: impact at the last spot.
            runImpact(ctx, level, x, y, z);
            return;
        }
        x = spike.getX();
        y = spike.getY();
        z = spike.getZ();
        state.putDouble("px", x);
        state.putDouble("py", y);
        state.putDouble("pz", z);
        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST,
                Blocks.POINTED_DRIPSTONE.defaultBlockState()),
            x, y + 0.5, z, 2, 0.2, 0.3, 0.2, 0.03);
        if (spike.onGround() || state.getIntOr("flight_ticks", 0) >= FLIGHT_TIMEOUT_TICKS) {
            runImpact(ctx, level, x, y, z);
            spike.discard();
            return;
        }
        if (!ctx.mobGriefingAllowed() && spike.getDeltaMovement().y < 0.0
            && BatAbilitySupport.aboutToLand(level, spike)) {
            // mobGriefing off: impact now and discard the
            // falling block before its own tick can place it.
            runImpact(ctx, level, x, y, z);
            spike.discard();
        }
    }

    /** The landing burst: shattering stone, stalactite damage, and the hazard shard. Runs once per drop. */
    private void runImpact(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        CompoundTag state = ctx.state();
        BlockState spikeState = Blocks.POINTED_DRIPSTONE.defaultBlockState();
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.6f, 1.3f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, spikeState),
            x, y + 0.3, z, 10, 0.7, 0.4, 0.7, 0.15);

        Mob caster = ctx.mob();
        List<LivingEntity> victims = BatAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().fallingStalactite(caster), IMPACT_DAMAGE);
        }

        if (ctx.mobGriefingAllowed()) {
            placeShard(level, x, y, z);
        }

        int cycle = state.getIntOr("cycle", 0);
        if (cycle + 1 < CYCLES) {
            // Already choosing the next stalactite, per the design.
            state.putInt("cycle", cycle + 1);
            beginCycle(ctx);
        } else {
            state.putInt("phase", 2);
            state.putInt("impact_tick", ctx.ticksActive());
        }
    }

    /**
     * Pins the hazard shard: one up-pointed dripstone tip in
     * the first open cell above the ground at the impact
     * column. Permanent terrain; mobGriefing-on callers only.
     */
    private static void placeShard(ServerLevel level, double x, double y, double z) {
        BlockState shard = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.UP)
            .setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, SpeleothemThickness.TIP);
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int startY = (int) Math.floor(y) + 1;
        for (int yy = startY; yy >= startY - 6; yy--) {
            BlockPos ground = new BlockPos(bx, yy, bz);
            if (level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()) {
                continue;
            }
            BlockPos cell = ground.above();
            BlockState cellState = level.getBlockState(cell);
            if (cellState.canBeReplaced() && level.getBlockEntity(cell) == null
                && shard.canSurvive(level, cell)) {
                level.setBlock(cell, shard, Block.UPDATE_ALL);
            }
            return;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("phase", 0) == 2) {
            return ctx.ticksActive() >= state.getIntOr("impact_tick", 0) + 5;
        }
        return ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // A still-flying spike is discarded with no impact: a
        // terminated drop never lands. A shard already placed
        // is permanent terrain and is deliberately not touched.
        if (ctx.state().getIntOr("phase", 0) == 1) {
            Entity spike = BatAbilitySupport.resolveStored(level, ctx.state(), "spike");
            if (spike != null) {
                spike.discard();
            }
        }
    }
}
