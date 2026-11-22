package mod.advancedmobs.ability.skeletonhorse;

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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Bonechill Aura (Skeleton Horse ability 5 of 5, aura /
 * control).
 *
 * WHAT IT DOES: a cold dread rolls off the skeleton horse
 * in waves of pale dust, and darkness closes in on every
 * enemy near it. Foes standing in the aura are slowed and
 * weakened while their vision pulses with the warden-style
 * darkness effect.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 12 blocks (distance
 *   squared 144).
 * - The aura radius (resolved once in start(), stored in
 *   the state tag, never re-read per tick): the design's
 *   follow_range based radius: half the horse's FOLLOW_RANGE
 *   attribute value, clamped to between 6.0 and 12.0
 *   blocks. A vanilla skeleton horse's follow range is 16,
 *   so the default aura radius is 8.0 blocks; a horse whose
 *   upgrades raised its follow range chills a wider circle.
 *   If the attribute instance is somehow absent, the radius
 *   falls back to 8.0.
 * - The opening (start()): SKELETON_HORSE_AMBIENT at volume
 *   1.2, pitch 0.6 (a low, cold neigh), and a first ring of
 *   12 pale gray DUST motes at 0.7 times the aura radius.
 * - The chill pulse (every 20 ticks): one bounded enemy
 *   query of the aura radius around the horse. Every enemy
 *   inside gains SLOWNESS I refreshed to 30 ticks, WEAKNESS
 *   I refreshed to 30 ticks, and DARKNESS refreshed to 40
 *   ticks (the design's warden style darkness: the 40-tick
 *   darkness against a 20-tick pulse keeps the victim's
 *   vision pulsing in waves rather than sitting black).
 *   The same pulse sends the visible wave: 12 pale gray
 *   DUST motes on a ring at 0.7 times the aura radius
 *   around the horse, plus 3 SCULK_CHARGE_POP accents at
 *   the horse's body (the design's sculk accent particles).
 * - The cold drift (every 4 ticks): 1 pale gray DUST mote
 *   drifts off the horse's body between pulses, so the
 *   aura reads as a constant cold haze, not just the waves.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): nothing to strip. The aura applies no modifiers
 *   or effects to the caster, and the debuffs on victims
 *   are ordinary vanilla effects that tick out on their own
 *   clocks within 2 seconds of the last pulse (the Bray of
 *   Doom precedent).
 *
 * WHAT IT LOOKS LIKE: a ring of pale dust rolling outward
 * from the skeleton horse once a second, cold haze drifting
 * off its bones between waves, and every enemy inside the
 * circle moving like it is wading through black water as
 * darkness pulses across its vision.
 *
 * PERFORMANCE: the radius is resolved once in start(); the
 * pulse is one bounded query every 20 ticks touching only
 * the enemies inside it; the drift is 1 particle every 4
 * ticks; there are no other queries or allocations in the
 * hot path. Particle budget per activation: opening 12,
 * pulses 15 times 15 = 225, drift 75 times 1 = 75, total
 * at most 312 particles.
 *
 * TERRAIN: this ability changes no blocks; it is a zone
 * effect, so the mobGriefing gate does not apply.
 *
 * State tag keys: "radius" (the resolved aura radius in
 * blocks, a double).
 */
public final class BonechillAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 20;
    private static final int DRIFT_INTERVAL = 4;
    private static final double FALLBACK_RADIUS = 8.0;

    @Override
    public String id() {
        return "bonechill_aura";
    }

    @Override
    public String displayName() {
        return "Bonechill Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();

        // The follow_range based radius, resolved once (see the class comment).
        double radius = FALLBACK_RADIUS;
        AttributeInstance followRange = caster.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            radius = Math.max(6.0, Math.min(12.0, followRange.getValue() * 0.5));
        }
        ctx.state().putDouble("radius", radius);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SKELETON_HORSE_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.6f);
            sendRing(ctx, radius);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level == null) {
            return;
        }
        double radius = ctx.state().getDoubleOr("radius", FALLBACK_RADIUS);

        if (ticks % DRIFT_INTERVAL == 0) {
            Mob caster = ctx.mob();
            level.sendParticles(SkeletonHorseAbilitySupport.PALE_DUST,
                caster.getX(), caster.getY() + 1.0, caster.getZ(),
                1, 0.5, 0.5, 0.5, 0.03);
        }
        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx, radius);
        }
    }

    /** The chill pulse: debuffs on every enemy in the aura, plus the visible wave. */
    private void pulse(AbilityContext ctx, double radius) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        List<LivingEntity> victims = SkeletonHorseAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), radius, 2.5, 3.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 40, 0));
        }
        sendRing(ctx, radius);
        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            3, 0.4, 0.4, 0.4, 0.05);
    }

    /** One ring of pale dust motes at 0.7 times the aura radius around the horse. */
    private void sendRing(AbilityContext ctx, double radius) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        double ringRadius = radius * 0.7;
        for (int i = 0; i < 12; i++) {
            double angle = Math.PI * 2.0 * i / 12.0;
            level.sendParticles(SkeletonHorseAbilitySupport.PALE_DUST,
                caster.getX() + Math.cos(angle) * ringRadius, caster.getY() + 0.6,
                caster.getZ() + Math.sin(angle) * ringRadius,
                1, 0.1, 0.15, 0.1, 0.01);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: no caster modifiers or effects, and victim
        // debuffs ride their own clocks (see the class comment).
    }
}
