package mod.advancedmobs.ability.mooshroom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Stew of Second Chances (Mooshroom ability 3 of 5,
 * support).
 *
 * WHAT IT DOES: the mooshroom offers a steaming bowl:
 * campfire smoke curls off its back and a ring of warm
 * instant effect sparkles washes over the herd. The
 * mooshroom and every allied mob in the ring are healed in
 * warm pulses and fed with saturation for as long as the
 * stew is served.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mooshroom's current target within 14 blocks
 *   (distance squared 196) AND at least one ally within 10
 *   blocks (one bounded query here, the Pasture Sanctuary
 *   precedent: a mooshroom with nobody to feed never burns
 *   the cooldown on an empty ring; the stew heals the
 *   mooshroom too, but it is a herd ability and only fires
 *   for a herd).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The serving pulse (every 40 ticks, 5 pulses, the first
 *   at tick 40): one bounded query at radius 6.0 around the
 *   moving mooshroom (2.5 below to 2.5 above). The
 *   mooshroom and every ally found gain INSTANT_HEALTH I
 *   (an instant 4 health on application, so a beneficiary
 *   standing in the ring for the whole stew is healed up to
 *   20 health) and SATURATION I refreshed to 100 ticks (the
 *   fed feeling; for mobs without a hunger bar the
 *   saturation is the design's stated effect line and reads
 *   as the warm sparkles, for players in the ally set it
 *   genuinely feeds). Each beneficiary also gets 4 warm
 *   INSTANT_EFFECT sparkles (amber 0xFFB84D) at its body;
 *   sparkles are drawn for the mooshroom plus at most the
 *   first 8 allies the pulse finds, so a huge herd cannot
 *   turn the stew into a particle fountain (the heal itself
 *   has no cap).
 * - The steam: 2 CAMPFIRE_COSY_SMOKE motes every 4 ticks
 *   curl up off the mooshroom's back, and 1 HEART mote every
 *   20 ticks.
 * - Sound: the classic cow ambient sound at start at volume
 *   1.2, pitch 0.9 (a contented low).
 * - end() removes nothing: instant health is instant, and
 *   the last saturation refresh rides its own 100 tick clock
 *   and fades on its own (the Caravan Call precedent).
 *
 * WHAT IT LOOKS LIKE: a mooshroom with campfire smoke
 * curling off its back like a walking stew pot, warm amber
 * sparkles washing over it and its herd in slow pulses,
 * wounds knitting shut with each serving.
 *
 * PERFORMANCE: one bounded query every 40 ticks does the
 * whole serving; steam runs every 4 ticks, hearts every 20;
 * nothing else runs per tick. Particle budget per
 * activation: steam 50 emissions times 2 = 100, hearts 10,
 * sparkles at most 5 pulses times 9 beneficiaries times 4 =
 * 180, total at most 290 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. Everything is effects and particles.
 *
 * State tag keys: none (the activation is pure timers off
 * ctx.ticksActive()).
 */
public final class StewOfSecondChancesAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 40;
    private static final int STEAM_INTERVAL = 4;
    private static final double RING_RADIUS = 6.0;
    private static final int SPARKLE_CAP = 8;
    // The design's warm instant effect sparkle: amber, pre-built once.
    private static final SpellParticleOption WARM_SPARKLE =
        SpellParticleOption.create(ParticleTypes.INSTANT_EFFECT, 0xFFB84D, 1.0f);

    @Override
    public String id() {
        return "stew_of_second_chances";
    }

    @Override
    public String displayName() {
        return "Stew of Second Chances";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 196.0) {
            return false;
        }
        Mob caster = ctx.mob();
        return !MooshroomAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), 10.0, 2.5, 2.5).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level != null && MooshroomAbilitySupport.cowSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                MooshroomAbilitySupport.cowSound(), SoundSource.HOSTILE, 1.2f, 0.9f);
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
        if (ticks % STEAM_INTERVAL == 0) {
            // The stew steams off the mooshroom's back.
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                caster.getX(), caster.getY() + 1.5, caster.getZ(),
                2, 0.4, 0.2, 0.4, 0.02);
        }
        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.HEART,
                caster.getX(), caster.getY() + 1.2, caster.getZ(),
                1, 0.5, 0.4, 0.5, 0.02);
        }
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: servings pulse every 40 ticks only.
        }
        serve(ctx, level);
    }

    /** One serving: heal and feed the mooshroom and every ally in the ring. */
    private void serve(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<LivingEntity> allies = MooshroomAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), RING_RADIUS, 2.5, 2.5);
        feed(level, caster);
        int sparkled = 0;
        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.SATURATION, 100, 0));
            if (sparkled < SPARKLE_CAP) {
                sparkled++;
                level.sendParticles(WARM_SPARKLE,
                    ally.getX(), ally.getY() + 1.0, ally.getZ(),
                    4, 0.5, 0.5, 0.5, 0.03);
            }
        }
    }

    /** Heals and feeds the caster itself, with its warm sparkles. */
    private void feed(ServerLevel level, Mob caster) {
        caster.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SATURATION, 100, 0));
        level.sendParticles(WARM_SPARKLE,
            caster.getX(), caster.getY() + 1.0, caster.getZ(),
            4, 0.5, 0.5, 0.5, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: instant health is instant and the
        // saturation rides its own clock (see the class
        // comment).
    }
}
