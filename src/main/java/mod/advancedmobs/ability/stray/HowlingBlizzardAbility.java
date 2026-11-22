package mod.advancedmobs.ability.stray;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Howling Blizzard (Stray ability 4 of 5, aura).
 *
 * WHAT IT DOES: a personal blizzard orbits the stray, SNOWFLAKE
 * and ASH whipping in a horizontal ring that pulses outward in
 * waves. Each pulse washes over nearby enemies with SLOWNESS and
 * a flash of BLINDNESS, so anything trying to brawl inside the
 * storm swings at shapes in the whiteout.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 10 blocks
 *   (distance squared 100).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Pulse every 20 ticks (12 pulses), centered on the stray
 *   wherever it has moved to: enemies (StrayAbilitySupport
 *   rules) within horizontal radius 4.5 of the stray, between 2.5
 *   below and 2.5 above its feet, get SLOWNESS I (amplifier 0)
 *   for 30 ticks and BLINDNESS (amplifier 0) for 25 ticks, both
 *   refreshed by the next pulse while they stay in the storm.
 *   The blindness is the design's whiteout flash: 25 ticks is
 *   long enough to lose the stray's outline between pulses,
 *   short enough that stepping out of the storm clears it almost
 *   at once. This is a zone effect, not terrain: no blocks are
 *   placed or changed, so the mobGriefing gate does not apply.
 * - Between pulses, every 10 ticks, 2 SNOWFLAKE motes swirl at
 *   radius 1.6 around the stray at chest height: the orbiting
 *   storm reads as moving even between waves.
 *
 * WHAT IT LOOKS LIKE: each pulse throws a visible ring: 10
 * SNOWFLAKE particles placed evenly on a circle around the stray
 * at chest height, the circle's radius stepping outward in waves
 * (2.0, then 3.3, then 4.6 blocks, then back to 2.0), plus 4 ASH
 * motes scattered through the disc. Victims show the vanilla
 * SLOWNESS and BLINDNESS effect behavior while caught in it.
 *
 * PERFORMANCE: tick() returns immediately except every 10th tick
 * (the swirl, 2 particles) and every 20th tick (the pulse: one
 * bounded entity query over a 9 by 5 by 9 box, plus 14 particle
 * sends placed on the ring). No per-tick scans, no allocations in
 * the tick path beyond the pulse's victim list. Particle budget
 * per activation: pulses 12 times (10 SNOWFLAKE + 4 ASH) = 168,
 * swirls at most 24 times 2 = 48, total at most 216 particles.
 *
 * CLEANUP: none needed. The ability applies no attribute
 * modifiers, spawns no entities, and changes no blocks; the
 * effects it applies are short and expire on their own after the
 * last pulse.
 *
 * State tag keys: none of its own (the framework's fw_ticks
 * counter drives the pulse schedule).
 */
public final class HowlingBlizzardAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 20;
    private static final double PULSE_RADIUS = 4.5;
    private static final int RING_POINTS = 10;

    @Override
    public String id() {
        return "howling_blizzard";
    }

    @Override
    public String displayName() {
        return "Howling Blizzard";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // Nothing to set up: the first pulse fires at tick 20 and
        // the swirl keeps the storm visible until then.
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 10 != 0) {
            return; // Cheap early-out: work happens every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        // The orbiting swirl between waves.
        double swirlAngle = ticks * 0.31;
        for (int i = 0; i < 2; i++) {
            double angle = swirlAngle + i * Math.PI;
            level.sendParticles(ParticleTypes.SNOWFLAKE,
                mob.getX() + Math.cos(angle) * 1.6, mob.getY() + 1.2, mob.getZ() + Math.sin(angle) * 1.6,
                1, 0.0, 0.0, 0.0, 0.01);
        }

        if (ticks % PULSE_INTERVAL != 0) {
            return;
        }

        // The pulse: the whiteout washes over everyone close.
        List<LivingEntity> victims = StrayAbilitySupport.enemiesAroundSelf(ctx, PULSE_RADIUS, 2.5, 2.5);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 25, 0));
        }

        // The ring wave: radius steps outward 2.0, 3.3, 4.6 and
        // wraps, one SNOWFLAKE per point on the circle.
        int pulseIndex = (ticks / PULSE_INTERVAL - 1) % 3;
        double ringRadius = 2.0 + pulseIndex * 1.3;
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = i * (Math.PI * 2.0 / RING_POINTS) + ticks * 0.05;
            level.sendParticles(ParticleTypes.SNOWFLAKE,
                mob.getX() + Math.cos(angle) * ringRadius, mob.getY() + 1.1,
                mob.getZ() + Math.sin(angle) * ringRadius,
                1, 0.0, 0.0, 0.0, 0.01);
        }
        level.sendParticles(ParticleTypes.ASH, mob.getX(), mob.getY() + 1.1, mob.getZ(),
            4, PULSE_RADIUS * 0.7, 0.6, PULSE_RADIUS * 0.7, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: no modifiers, no spawns, no blocks (see
        // the CLEANUP note in the class comment).
    }
}
