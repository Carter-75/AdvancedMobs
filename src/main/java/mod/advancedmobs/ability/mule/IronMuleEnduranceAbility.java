package mod.advancedmobs.ability.mule;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Iron Mule Endurance (Mule ability 4 of 5, defense).
 *
 * WHAT IT DOES: the mule just does not stop. Its health
 * pool grows by a flat slab of hit points that arrives
 * healed, regeneration and resistance settle in, a cushion
 * of absorption hearts wraps it, and it shrugs off damage
 * that would drop a horse while heart particles drift off
 * its hide now and then: the fight has to go through the
 * whole mule, twice.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mule's current target within 12 blocks (distance
 *   squared 144).
 * - The pool (start(), removed exactly in end()): one
 *   transient modifier under a fixed id, MAX_HEALTH +20.0
 *   ADD_VALUE (the design's max_health line). The added 20
 *   health ARRIVES FULL: the mule's health is raised by 20
 *   on entry (clamped to the new maximum), the Colossus
 *   Form pattern. In end() the modifier is removed and the
 *   health is clamped back to the restored maximum, so
 *   the bonus never leaves phantom health behind.
 * - The shrug (start(), all removed exactly in end()):
 *   ABSORPTION II for 420 ticks (8 absorption hearts, the
 *   design's absorption line), REGENERATION I for 420
 *   ticks (the design's regeneration line), and RESISTANCE
 *   I for 420 ticks (the design's resistance line), all
 *   stripped in end() so none of the form's protection
 *   outlives it.
 * - The look (tick()): 2 HEART particles drift off the
 *   mule's hide every 20 ticks (the design's heart
 *   particle line), and MULE_AMBIENT at volume 1.0 plays
 *   in start() as it settles in for the long haul.
 * - Duration: exactly 400 ticks (20 seconds), hard finish.
 * - end(): the MAX_HEALTH modifier is removed, the health
 *   is clamped to the restored pool, and the three effects
 *   are stripped. Idempotent: safe when start() never ran.
 *
 * WHAT IT LOOKS LIKE: nothing dramatic, and that is the
 * point: the mule takes the hit, the hearts drift, the
 * wounds knit, and it is still standing there with the
 * same patient expression, twenty hit points deeper than
 * it has any right to be.
 *
 * PERFORMANCE: start() is one modifier write, one health
 * write, three effect applications, one sound. tick() is
 * a single particle send every 20 ticks and nothing else;
 * there are no entity queries anywhere in the ability.
 * Particle budget per activation: at most 20 sends of 2 =
 * 40 particles.
 *
 * TERRAIN: this ability changes no blocks, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: none (the activation is pure timed state
 * on the caster itself; the framework's tick counter is
 * the only clock it needs).
 */
public final class IronMuleEnduranceAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final double HEALTH_BONUS = 20.0;
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "iron_mule_endurance_health");

    @Override
    public String id() {
        return "iron_mule_endurance";
    }

    @Override
    public String displayName() {
        return "Iron Mule Endurance";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The pool grows, and the growth arrives healed.
        MuleAbilitySupport.addModifier(caster, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));
        caster.setHealth(Math.min(caster.getMaxHealth(), caster.getHealth() + (float) HEALTH_BONUS));
        // The shrug: absorption cushion, knitting wounds,
        // blunted hits.
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, DURATION_TICKS + 20, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS + 20, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.MULE_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 20 != 0) {
            return; // Cheap early-out: hearts drift on a slow interval only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            2, caster.getBbWidth() * 0.5, 0.4, caster.getBbWidth() * 0.5, 0.02);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        MuleAbilitySupport.removeModifier(caster, Attributes.MAX_HEALTH, HEALTH_ID);
        // The pool shrinks back: clamp any health the bonus
        // was carrying (the Colossus Form pattern).
        if (caster.getHealth() > caster.getMaxHealth()) {
            caster.setHealth(caster.getMaxHealth());
        }
        caster.removeEffect(MobEffects.ABSORPTION);
        caster.removeEffect(MobEffects.REGENERATION);
        caster.removeEffect(MobEffects.RESISTANCE);
    }
}
