package mod.advancedmobs.ability.turtle;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Ancient Wisdom (Turtle ability 4 of 5, aura / support).
 *
 * WHAT IT DOES: this turtle has seen a century of fights
 * and it shows. A slow, calm aura of drifting hearts
 * radiates from its shell: the turtle and every ally near
 * it steadily mend and hold a cushion of absorption, and
 * any of them standing in water breathes easy under
 * conduit power, so nobody drowns mid battle.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the turtle's current target within 16 blocks (distance
 *   squared 256). No ally is required: the turtle itself
 *   always benefits, so the aura is never wasted on a
 *   lone turtle (a deliberate difference from Oasis
 *   Blessing, whose camel does not benefit alone).
 * - The opening (start()): TURTLE_AMBIENT_LAND at volume
 *   1.0, pitch 0.9, and a slow burst of 8 HEART particles
 *   over the shell.
 * - The aura pulse (tick(), every 20 ticks): one bounded
 *   query around the turtle, radius 6.0 (vertical reach 2
 *   below, 3 above). The beneficiaries are the turtle
 *   itself plus every ally in the ring (the package ally
 *   rule). Each beneficiary gains REGENERATION I and
 *   ABSORPTION I (4 absorption points), each refreshed to
 *   40 ticks, and each beneficiary that is in water also
 *   gains CONDUIT_POWER I refreshed to 40 ticks (the
 *   design's in-water line: underwater vision, fast
 *   mining, no drowning). Up to the first 4 allies in the
 *   ring also get 1 HEART particle each as the pulse
 *   lands. The effects are deliberately short and
 *   refreshed by the pulse, so they fade within 2 seconds
 *   of the ability ending or of stepping out of the ring;
 *   end() strips nothing.
 * - The drift (tick(), every 5 ticks): 2 HEART particles
 *   drift up off the shell at a small random offset, the
 *   aura's visible heartbeat.
 * - The turtle fights on normally during the aura: no
 *   navigation stops, no pose, nothing planted. The aura
 *   is centered on the turtle wherever it goes.
 * - Duration: exactly 400 ticks (20 seconds), hard finish.
 * - end(): nothing to undo (the pulse effects ride their
 *   own short clocks out, the Oasis Blessing precedent).
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: hearts rise off the old shell in a
 * slow, steady rhythm for the whole fight, allies near it
 * glow faintly as their wounds close, and underwater the
 * whole group moves in the clear, calm bubble of conduit
 * sight.
 *
 * PERFORMANCE: the pulse is one bounded radius-6.0 query
 * every 20 ticks over a small ally list; the drift is 2
 * particles every 5 ticks; nothing else runs per tick.
 * Particle budget per activation: 8 at start, drift 80
 * emissions times 2 = 160, pulse hearts at most 20 pulses
 * times 4 = 80, total at most 248 particles.
 *
 * TERRAIN: this ability changes no blocks; the mobGriefing
 * gate does not apply.
 *
 * State tag keys: none (the aura is centered on the caster
 * and timed by the framework's tick counter).
 */
public final class AncientWisdomAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final double AURA_RADIUS = 6.0;
    private static final int PULSE_INTERVAL = 20;
    private static final int EFFECT_TICKS = 40;
    private static final int PULSE_HEART_ALLIES = 4;

    @Override
    public String id() {
        return "ancient_wisdom";
    }

    @Override
    public String displayName() {
        return "Ancient Wisdom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.TURTLE_AMBIENT_LAND, SoundSource.HOSTILE, 1.0f, 0.9f);
            level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                8, 0.5, 0.3, 0.5, 0.03);
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
        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx, level);
        }
        if (ticks % 5 == 0) {
            // The heartbeat: two hearts drift up off the shell.
            level.sendParticles(ParticleTypes.HEART,
                caster.getX() + (ctx.random().nextDouble() - 0.5) * 1.2,
                caster.getY() + 0.7,
                caster.getZ() + (ctx.random().nextDouble() - 0.5) * 1.2,
                2, 0.15, 0.15, 0.15, 0.02);
        }
    }

    /** One aura pulse: mend, cushion, and water-breathe the caster and every ally in the ring. */
    private void pulse(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        blessOne(caster);
        List<LivingEntity> allies = TurtleAbilitySupport.alliesAroundSelf(ctx, AURA_RADIUS, 2.0, 3.0);
        int hearts = 0;
        for (LivingEntity ally : allies) {
            blessOne(ally);
            if (hearts < PULSE_HEART_ALLIES) {
                hearts++;
                level.sendParticles(ParticleTypes.HEART, ally.getX(), ally.getY() + 0.8, ally.getZ(),
                    1, 0.2, 0.2, 0.2, 0.02);
            }
        }
    }

    /** Applies the aura to one beneficiary: regeneration, absorption, and conduit power in water. */
    private void blessOne(LivingEntity beneficiary) {
        beneficiary.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
        beneficiary.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EFFECT_TICKS, 0));
        if (beneficiary.isInWater()) {
            beneficiary.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, EFFECT_TICKS, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: the pulse effects are short and
        // ride their own clocks out (see the class comment).
    }
}
