package mod.advancedmobs.ability.axolotl;

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
 * Gills of the Deep (Axolotl ability 2 of 5, aura / support).
 *
 * WHAT IT DOES: the axolotl fans its feathery gills wide and
 * breathes for the whole team, bubble particles streaming
 * from its gills in a slow, calm current. Allies in the
 * current fight underwater at full strength with their air
 * frozen, and the axolotl itself never wants for breath
 * while the aura runs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the axolotl's current target within 16 blocks (distance
 *   squared 256), plus the axolotl in water (there is no
 *   current to breathe in on land).
 * - The aura (start() and tick()): the activation lasts 300
 *   ticks (15 seconds). The caster applies CONDUIT_POWER I
 *   and BREATH_OF_THE_NAUTILUS I to itself for 60 ticks at
 *   start() and tops its own air supply up to its maximum.
 * - The breath pulse (every 20 ticks): one bounded query at
 *   radius 8.0 around the caster (the support class's ally
 *   rule, either-direction war records) finds the team; every
 *   ally in the box gains CONDUIT_POWER I and
 *   BREATH_OF_THE_NAUTILUS I refreshed to 60 ticks, so an
 *   ally that stays in the current never sees either effect
 *   lapse, and conduit power's vanilla air freeze is what
 *   holds their air still (the design's frozen air line).
 *   The caster refreshes both effects on itself to 60 ticks
 *   on the same pulse and tops its own air supply up to its
 *   maximum again, so the axolotl's own breath truly never
 *   runs down while the aura runs.
 * - The look (every 4 ticks): 3 BUBBLE particles stream from
 *   the caster's gill height (60 percent of its body height),
 *   drifting upward at a slow 0.02 spread speed.
 * - If the axolotl leaves the water the aura ends early:
 *   there is no current out of the water.
 *
 * WHAT IT LOOKS LIKE: a calm, slow bubble stream rising off
 * the axolotl's fanned gills for 15 seconds while its whole
 * team around it stops losing air and hits at full underwater
 * strength.
 *
 * PERFORMANCE: the bubble stream is 3 particle sends every 4
 * ticks; the ally work is one bounded query every 20 ticks
 * with one effect refresh per ally found. No per-tick entity
 * queries. Particle budget per activation: 3 per 4 ticks over
 * at most 300 ticks, at most 225 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() removes CONDUIT_POWER and
 * BREATH_OF_THE_NAUTILUS from the caster only. The allies'
 * copies are ordinary 60 tick effects that expire on their
 * own within 3 seconds of the last pulse, so nothing lingers
 * on anyone else.
 *
 * State tag keys: none (the activation is a pure timer driven
 * by the framework tick counter).
 */
public final class GillsOfTheDeepAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 20;
    private static final int EFFECT_TICKS = 60;
    private static final double AURA_RADIUS = 8.0;

    @Override
    public String id() {
        return "gills_of_the_deep";
    }

    @Override
    public String displayName() {
        return "Gills of the Deep";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        refreshSelf(ctx, caster);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        // The slow bubble stream off the fanned gills.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE,
                caster.getX(), caster.getY() + caster.getBbHeight() * 0.6, caster.getZ(),
                3, 0.25, 0.15, 0.25, 0.02);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            refreshSelf(ctx, caster);
            List<LivingEntity> allies = AxolotlAbilitySupport.alliesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, AURA_RADIUS, AURA_RADIUS);
            for (LivingEntity ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, EFFECT_TICKS, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.BREATH_OF_THE_NAUTILUS, EFFECT_TICKS, 0));
            }
        }
    }

    /** Refreshes the caster's own two effects and tops its air up. See the class comment. */
    private void refreshSelf(AbilityContext ctx, Mob caster) {
        caster.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.BREATH_OF_THE_NAUTILUS, EFFECT_TICKS, 0));
        caster.setAirSupply(caster.getMaxAirSupply());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || !ctx.mob().isInWater();
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.CONDUIT_POWER);
        caster.removeEffect(MobEffects.BREATH_OF_THE_NAUTILUS);
    }
}
