package mod.advancedmobs.ability.frog;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Marsh Gas Croak (Frog ability 2 of 5, aura).
 *
 * WHAT IT DOES: the frog's throat swells and it croaks
 * out a rolling cloud of greenish noxious gas that hangs
 * low over the swampy ground around it. Enemies standing
 * in the cloud are nauseated and poisoned, and the frog,
 * bred for bad air, is completely at home in it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the frog's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: 200 ticks. The frog settles in the middle
 *   of its cloud and is planted by per-tick navigation
 *   stops (never NoAi); the cloud is centered on the
 *   frog, so the zone holds where it sits.
 * - The opening croak (start()): FROG_AMBIENT at volume
 *   1.5 and pitch 0.6 (the deep swollen-throat croak),
 *   plus a burst of 10 NOXIOUS_GAS_CLOUD motes at the
 *   frog. The croak repeats every 40 ticks at pitch 0.6
 *   plus a small random lift.
 * - The cloud (visual): every 2 ticks, 2
 *   NOXIOUS_GAS_CLOUD motes (the design's particle) drift
 *   at random offsets inside radius 3.5 at 0.25 above the
 *   frog's feet, so the gas hangs low; every 4 ticks 1
 *   NOXIOUS_GAS mote boils up at the frog itself.
 * - The gas (pulse, every 10 ticks): one bounded query
 *   (radius 5.0, 2 below and 2 above the frog) refreshes
 *   NAUSEA I and POISON I to 60 ticks on every enemy in
 *   the cloud (the design's two effects). The debuffs
 *   ride their own clocks between pulses and after the
 *   ability ends.
 * - Self immunity (the design's conduit_power style
 *   immunity, in code): the pulse only ever applies to
 *   entities the package enemy rule accepts, and the
 *   caster is never its own enemy, so the frog breathes
 *   its own gas unharmed by construction. No effect is
 *   applied to or stripped from the frog.
 * - end(): nothing to undo; no modifiers, no effects on
 *   the caster, no spawns. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a frog sitting in a low green fog
 * bank, throat pulsing with deep croaks, gas boiling off
 * the ground around it while anything that wades in
 * starts retching and taking poison ticks.
 *
 * PERFORMANCE: the cloud emission is 2 particle sends
 * every 2 ticks plus 1 every 4; the pulse is one bounded
 * query every 10 ticks; the per-tick work is one
 * navigation stop. Particle budget per activation: 10
 * opening burst plus 200 cloud plus 50 gas, at most 260
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the cloud is a
 * zone effect of particles and effects, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: none (the activation is pure timing off
 * the framework tick counter).
 */
public final class MarshGasCroakAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final double CLOUD_RADIUS = 5.0;
    private static final int DEBUFF_TICKS = 60;

    @Override
    public String id() {
        return "marsh_gas_croak";
    }

    @Override
    public String displayName() {
        return "Marsh Gas Croak";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FROG_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.6f);
            level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD,
                caster.getX(), caster.getY() + 0.4, caster.getZ(),
                10, 0.8, 0.3, 0.8, 0.03);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        caster.getNavigation().stop();
        RandomSource random = ctx.random();
        if (ticks % 2 == 0) {
            // The low rolling cloud: motes hug the ground.
            for (int i = 0; i < 2; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = random.nextDouble() * 3.5;
                level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD,
                    caster.getX() + Math.cos(angle) * radius, caster.getY() + 0.25,
                    caster.getZ() + Math.sin(angle) * radius,
                    1, 0.15, 0.05, 0.15, 0.01);
            }
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.NOXIOUS_GAS,
                caster.getX(), caster.getY() + 0.5, caster.getZ(),
                1, 0.3, 0.2, 0.3, 0.02);
        }
        if (ticks % 40 == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FROG_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.6f + random.nextFloat() * 0.2f);
        }
        if (ticks % PULSE_INTERVAL == 0) {
            // The gas pulse: enemies in the cloud retch and poison.
            List<LivingEntity> enemies = FrogAbilitySupport.enemiesAroundSelf(ctx, CLOUD_RADIUS, 2.0, 2.0);
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, DEBUFF_TICKS, 0));
                enemy.addEffect(new MobEffectInstance(MobEffects.POISON, DEBUFF_TICKS, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: no caster state was changed.
    }
}
