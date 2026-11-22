package mod.advancedmobs.ability.mooshroom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Spore Burst (Mooshroom ability 1 of 5, attack).
 *
 * WHAT IT DOES: the mooshroom shakes its cap and erupts in a
 * cloud of mycelium motes and drifting spores that hangs
 * around it for the duration. Enemies caught in the cloud are
 * poisoned and slowed as the spores take hold. The mooshroom
 * itself stays immune inside its own haze: the cloud pulse
 * only ever targets enemies (MooshroomAbilitySupport rules),
 * never the caster, so the fungus that made the cloud cannot
 * be hurt by it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mooshroom's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 120 ticks (6 seconds) from start().
 * - The eruption (start()): a 20 particle MYCELIUM burst plus
 *   10 SPORE_BLOSSOM_AIR motes at the mooshroom's body, and
 *   the classic cow ambient sound at volume 1.2, pitch 0.7
 *   (a low shaking bellow).
 * - The cloud pulse (every 10 ticks, 12 pulses): one bounded
 *   query at radius 4.0 around the moving mooshroom (2 below
 *   to 2.5 above). Every enemy found gains POISON I
 *   (amplifier 0) for 60 ticks and SLOWNESS I (amplifier 0)
 *   for 60 ticks, refreshed by each pulse, so an enemy that
 *   stays in the haze stays poisoned and slowed for the whole
 *   burst and about 3 seconds after leaving it. Poison I
 *   deals roughly 2 to 3 damage over a 60 tick run and cannot
 *   kill (the vanilla poison floor); the burst has no direct
 *   damage of its own, the poison and the slow are the whole
 *   threat: a soft mob pinning its attacker in place while
 *   the war around it does the killing.
 * - The haze (every 4 ticks): 3 MYCELIUM motes and 2
 *   SPORE_BLOSSOM_AIR motes drift around the mooshroom's
 *   body, marking the cloud's reach.
 * - end() removes nothing: the poison and slowness ride
 *   their own 60 tick clocks and fade on their own (the
 *   Caravan Call precedent).
 *
 * WHAT IT LOOKS LIKE: a mooshroom wreathed in a purple-grey
 * spore haze, mycelium motes shedding off its cap and pale
 * spore blossom dust drifting down through it, enemies
 * inside it limping and ticking green with poison.
 *
 * PERFORMANCE: tick() returns immediately except every 4th
 * tick (5 haze particles) and every 10th tick (one bounded
 * query). No per-tick allocations beyond the pulse's result
 * list. Particle budget per activation: 30 at start plus 30
 * haze pulses times 5 = 150, total at most 180 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. Everything is effects and particles.
 *
 * State tag keys: none (the activation is pure timers off
 * ctx.ticksActive()).
 */
public final class SporeBurstAbility implements MobAbility {

    private static final int DURATION_TICKS = 120;
    private static final int PULSE_INTERVAL = 10;
    private static final int HAZE_INTERVAL = 4;
    private static final double CLOUD_RADIUS = 4.0;

    @Override
    public String id() {
        return "spore_burst";
    }

    @Override
    public String displayName() {
        return "Spore Burst";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level == null) {
            return;
        }
        // The eruption: the cap shakes its spores loose.
        level.sendParticles(ParticleTypes.MYCELIUM,
            caster.getX(), caster.getY() + 1.0, caster.getZ(),
            20, 0.8, 0.6, 0.8, 0.06);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
            caster.getX(), caster.getY() + 1.4, caster.getZ(),
            10, 0.7, 0.5, 0.7, 0.03);
        if (MooshroomAbilitySupport.cowSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                MooshroomAbilitySupport.cowSound(), SoundSource.HOSTILE, 1.2f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks % HAZE_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.MYCELIUM,
                caster.getX(), caster.getY() + 1.0, caster.getZ(),
                3, 0.9, 0.5, 0.9, 0.03);
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                caster.getX(), caster.getY() + 1.3, caster.getZ(),
                2, 0.8, 0.4, 0.8, 0.02);
        }
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the cloud pulses every 10 ticks only.
        }
        // The cloud takes hold: poison and slowness for every
        // enemy standing in the haze.
        List<LivingEntity> victims = MooshroomAbilitySupport.enemiesAroundSelf(ctx, CLOUD_RADIUS, 2.0, 2.5);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the poison and slowness ride their
        // own clocks and fade on their own (see the class
        // comment).
    }
}
