package mod.advancedmobs.ability.axolotl;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Regeneration Bloom (Axolotl ability 5 of 5, transformation).
 *
 * WHAT IT DOES: wounded past a threshold, the axolotl does
 * what its species is famous for: it simply regrows. Heart
 * and happy_villager particles bloom around it as it rapidly
 * regenerates on a boosted health pool, even regrowing a
 * little larger and frillier than before, and while the
 * bloom runs it is effectively a brand new fighter.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the axolotl's current target within 16 blocks (distance
 *   squared 256), plus the wound threshold from the design:
 *   the axolotl is at or below 50 percent of its maximum
 *   health. A healthy axolotl never blooms. The design's
 *   "only once in a while" tracking is the framework's own
 *   cooldown (10 to 60 seconds, rolled when this ability
 *   ENDS, never when it starts) layered on top of the wound
 *   threshold, so a bloom cannot chain straight into the
 *   next one.
 * - The bloom (start()): the caster gains REGENERATION II
 *   and HEALTH_BOOST I, both for 260 ticks, and a SCALE
 *   add_value modifier of +0.15 (modifier id
 *   advancedmobs:regeneration_bloom_scale; the design's small
 *   regrowth, about 15 percent larger). HEALTH_BOOST I raises
 *   the maximum health by 4 points (the axolotl's 14 becomes
 *   18) and the regeneration fills the enlarged pool at the
 *   Regeneration II rate (1 health every 25 ticks), roughly
 *   9 to 10 health over the run on top of whatever the wound
 *   left. An opening burst of 6 HEART and 6 HAPPY_VILLAGER
 *   particles marks the bloom starting.
 * - While it runs (tick()): every 10 ticks, 2 HEART and 2
 *   HAPPY_VILLAGER particles bloom at the caster. The
 *   activation lasts 240 ticks (12 seconds); nothing else is
 *   gated, so the axolotl fights on normally while it
 *   regrows, in water or out.
 *
 * WHAT IT LOOKS LIKE: a battered axolotl suddenly wreathed
 * in hearts and green sparkles, visibly a touch bigger, its
 * health bar climbing back up mid-fight.
 *
 * PERFORMANCE: per tick the work is nothing but the
 * framework tick counter; the particles are 4 sends every 10
 * ticks. No entity queries anywhere. Particle budget per
 * activation: 12 at the opening burst plus 4 per 10 ticks
 * over 240 ticks (96), total at most 108 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() removes the REGENERATION and HEALTH_BOOST
 * effects and the SCALE modifier. Removing HEALTH_BOOST
 * drops the maximum health back by 4; if the axolotl's
 * current health sits above the restored maximum, vanilla
 * clamps it down, which is the intended price of the bloom
 * ending.
 *
 * State tag keys: none (the activation is a pure timer driven
 * by the framework tick counter).
 */
public final class RegenerationBloomAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int EFFECT_TICKS = 260;
    private static final int PARTICLE_INTERVAL = 10;
    private static final double SCALE_BONUS = 0.15;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "regeneration_bloom_scale");

    @Override
    public String id() {
        return "regeneration_bloom";
    }

    @Override
    public String displayName() {
        return "Regeneration Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        return target != null && caster.distanceToSqr(target) <= 256.0
            && caster.getHealth() <= caster.getMaxHealth() * 0.5f;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, EFFECT_TICKS, 0));
        AxolotlAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, SCALE_BONUS, AttributeModifier.Operation.ADD_VALUE));

        if (ctx.level() instanceof ServerLevel level) {
            bloomParticles(level, caster, 6);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ctx.ticksActive() % PARTICLE_INTERVAL == 0) {
            bloomParticles(level, ctx.mob(), 2);
        }
    }

    /** The heart and sparkle bloom around the regrowing axolotl. See the class comment. */
    private void bloomParticles(ServerLevel level, Mob caster, int count) {
        level.sendParticles(ParticleTypes.HEART,
            caster.getX(), caster.getY() + caster.getBbHeight() * 0.7, caster.getZ(),
            count, 0.3, 0.3, 0.3, 0.02);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            caster.getX(), caster.getY() + caster.getBbHeight() * 0.5, caster.getZ(),
            count, 0.3, 0.3, 0.3, 0.02);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.REGENERATION);
        caster.removeEffect(MobEffects.HEALTH_BOOST);
        AxolotlAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
    }
}
