package mod.advancedmobs.ability.cavespider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Silk Minefield (Cave Spider ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the cave spider strings its hunting ground
 * with near invisible tripwire: real string and cobweb
 * patches laid low across the floor around it, with only the
 * odd DUST mote giving them away. An enemy that crosses a
 * wire sets off a NOTE chime, takes a burst of SLOWNESS, and
 * lights up GLOWING through the dark, revealed to the spider
 * that laid the trap.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the spider's current target within 12 blocks (distance
 *   squared 144). The field is strung around a fight in
 *   progress.
 * - The laying (start(), mobGriefing mode only): the 5 by 5
 *   square centered on the spider (Chebyshev radius 2, 25
 *   positions) at the spider's feet level, at most 16 blocks
 *   placed. Positions where x + z is even get a tripwire
 *   (string) when the shared placement path allows it
 *   (replaceable, no fluid, no block entity, not
 *   unbreakable, and a face-sturdy floor below, so the string
 *   lies across the floor and never floats); the other
 *   positions get a cobweb patch with a 50 percent roll. The
 *   placed tripwire positions are recorded in the state tag
 *   as block position longs. The blocks are PERMANENT
 *   terrain. With mobGriefing off, nothing is laid, no wire
 *   can ever trip, and the ability is only its 400 tick
 *   watch with empty hands: the minefield IS its terrain.
 * - The watch (every 5 ticks for 400 ticks): ONE bounded
 *   entity query (radius 3.0 around the spider, box reach 1.5
 *   below, 2.0 above) collects the enemies nearby; each
 *   enemy whose feet block position matches a recorded,
 *   not yet tripped wire trips it. Tripping a wire: the
 *   string is consumed (the position is set back to air, but
 *   only if the block there is still the tripwire that was
 *   laid), 6 NOTE particles burst at the wire and a
 *   NOTE_BLOCK_PLING chime plays at volume 0.6, pitch 1.5,
 *   the tripper gets SLOWNESS II (amplifier 1) for 60 ticks
 *   and GLOWING (amplifier 0) for 120 ticks, and if the
 *   spider currently has no target it takes the tripper as
 *   its target: the chime only the spider side acts on. Each
 *   wire trips at most once.
 * - The tell (every 20 ticks): 1 silk DUST mote (color
 *   0xD8D4C8, scale 0.8) drifts over the first untripped
 *   wire: the odd glint that gives the field away to a
 *   careful eye.
 * - Duration: 400 ticks (20 seconds) of watching. When the
 *   ability ends the spider stops watching; untripped wires
 *   and cobweb patches stay in the world as permanent
 *   terrain, inert string like any other string a player
 *   might find, until someone breaks them.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the string and cobwebs
 * are real block placement, so they happen only when
 * AbilityContext.mobGriefingAllowed() is true, and they stay
 * placed for good. A tripped wire consuming itself is part
 * of the same placement: it can only happen in mobGriefing
 * mode, because that is the only mode where wires exist.
 * end() never touches the field.
 *
 * WHAT IT LOOKS LIKE: the spider scuttles a slow circuit and
 * the cave floor behind it is suddenly threaded with string
 * at ankle height and patches of low web, nearly invisible
 * but for a drifting mote of dust here and there, until
 * something blunders across a wire: a soft pling, a burst of
 * musical notes, and the intruder slows, lit up like a
 * lantern in the dark.
 *
 * PERFORMANCE: the laying is one fixed pass of at most 25
 * position checks, once, at start. The watch runs one
 * bounded entity query every 5 ticks and compares feet
 * positions against at most 16 recorded wire longs held in
 * the state tag; the tell is 1 particle every 20 ticks. No
 * per-tick work outside the pulses. Particle budget per
 * activation: trips at most 16 times 6 = 96 NOTE, tells 20
 * times 1 = 20 DUST, total at most 116 particles.
 *
 * CLEANUP: nothing to undo. The field is permanent terrain,
 * the trip effects are timed and expire, and end() is
 * intentionally empty.
 *
 * State tag keys: "wires" (long array of placed tripwire
 * block positions), "tripped" (long array of wire positions
 * that have already fired).
 */
public final class SilkMinefieldAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int WATCH_INTERVAL = 5;
    private static final int TELL_INTERVAL = 20;
    private static final int FIELD_RADIUS = 2;
    private static final int MAX_PLACED = 16;
    private static final DustParticleOptions SILK_DUST = new DustParticleOptions(0xD8D4C8, 0.8f);

    @Override
    public String id() {
        return "silk_minefield";
    }

    @Override
    public String displayName() {
        return "Silk Minefield";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Terrain (mobGriefing mode only): the permanent
        // field. With mobGriefing off, no block is touched and
        // no wire is recorded. See the TERRAIN RULE in the
        // class comment.
        if (!ctx.mobGriefingAllowed()) {
            return;
        }
        layField(ctx, level);
    }

    /**
     * Lays the string and cobweb field (see the class comment
     * for the pattern, the checkerboard, and the cap) and
     * records the placed tripwire positions in the state tag.
     */
    private static void layField(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        BlockPos center = mob.blockPosition();
        long[] wires = new long[MAX_PLACED];
        int wireCount = 0;
        int placed = 0;
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS && placed < MAX_PLACED; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS && placed < MAX_PLACED; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // The spider does not string its own square.
                }
                BlockPos pos = center.offset(dx, 0, dz);
                if (((dx + dz) & 1) == 0) {
                    if (CaveSpiderAbilitySupport.placeTripwire(level, pos)) {
                        wires[wireCount++] = pos.asLong();
                        placed++;
                    }
                } else if (ctx.random().nextBoolean()) {
                    if (CaveSpiderAbilitySupport.placeCobweb(level, pos)) {
                        placed++;
                    }
                }
            }
        }
        long[] recorded = new long[wireCount];
        System.arraycopy(wires, 0, recorded, 0, wireCount);
        ctx.state().putLongArray("wires", recorded);
        ctx.state().putLongArray("tripped", new long[0]);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        long[] wires = state.getLongArray("wires").orElse(new long[0]);

        // The tell: an odd DUST mote over the first live wire.
        if (ticks % TELL_INTERVAL == 0 && wires.length > 0) {
            long[] tripped = state.getLongArray("tripped").orElse(new long[0]);
            for (long wire : wires) {
                if (!contains(tripped, wire)) {
                    BlockPos pos = BlockPos.of(wire);
                    level.sendParticles(SILK_DUST, pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5,
                        1, 0.1, 0.05, 0.1, 0.01);
                    break;
                }
            }
        }

        if (ticks % WATCH_INTERVAL != 0 || wires.length == 0) {
            return; // Cheap early-out: the watch pulses every 5 ticks, and only with wires down.
        }

        // The watch: one bounded query, feet positions checked
        // against the recorded wires.
        List<LivingEntity> nearby = CaveSpiderAbilitySupport.enemiesAroundSelf(ctx, 3.0, 1.5, 2.0);
        if (nearby.isEmpty()) {
            return;
        }
        long[] tripped = state.getLongArray("tripped").orElse(new long[0]);
        boolean changed = false;
        for (LivingEntity enemy : nearby) {
            long feet = enemy.blockPosition().asLong();
            if (!contains(wires, feet) || contains(tripped, feet)) {
                continue;
            }
            tripped = append(tripped, feet);
            changed = true;
            tripWire(ctx, level, BlockPos.of(feet), enemy);
        }
        if (changed) {
            state.putLongArray("tripped", tripped);
        }
    }

    /** One wire firing: the chime, the reveal, and the alert (see the class comment). */
    private static void tripWire(AbilityContext ctx, ServerLevel level, BlockPos pos, LivingEntity tripper) {
        // The string is consumed, but only if it is still the
        // tripwire that was laid.
        if (level.getBlockState(pos).is(Blocks.TRIPWIRE)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.sendParticles(ParticleTypes.NOTE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            6, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            SoundEvents.NOTE_BLOCK_PLING, SoundSource.HOSTILE, 0.6f, 1.5f);
        tripper.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
        tripper.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0));
        // The chime only the spider side hears: an idle spider
        // takes the revealed tripper as its target.
        Mob mob = ctx.mob();
        if (mob.getTarget() == null) {
            mob.setTarget(tripper);
        }
    }

    private static boolean contains(long[] values, long needle) {
        for (long value : values) {
            if (value == needle) {
                return true;
            }
        }
        return false;
    }

    private static long[] append(long[] values, long extra) {
        long[] grown = new long[values.length + 1];
        System.arraycopy(values, 0, grown, 0, values.length);
        grown[values.length] = extra;
        return grown;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: the field is permanent terrain,
        // the trip effects are timed and expire on their own.
        // See CLEANUP in the class comment.
    }
}
