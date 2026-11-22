package mod.advancedmobs.ability.snowgolem;

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

import java.util.List;

/**
 * Abominable Form (Snow Golem ability 3 of 5,
 * transformation).
 *
 * WHAT IT DOES: the little snowman swells into a hulking
 * yeti of packed snow, three times its normal size, and
 * trades its snowball plinks for brutal melee swipes: every
 * half second that its target is in reach it hauls off with
 * a sweeping blow that arcs snow across everything standing
 * near the victim.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The form (start(), all removed in end()): SCALE
 *   add_multiplied_base +2.0 (3.0 times normal size, the
 *   design's tripled SCALE; SCALE is in LivingEntity's base
 *   attribute set, so the modifier lands), MAX_HEALTH
 *   add_value +12.0 (4 to 16 health; the golem heals the
 *   added 12 on entry, the Colossus Form pattern, and its
 *   health is clamped back to the true maximum in end()),
 *   and STRENGTH I for 420 ticks (removed in end()).
 * - The swipes (code-driven, every 10 ticks): when the
 *   golem's current target is alive and within 3.5 blocks
 *   (a reach sized for the tripled body), the golem swipes:
 *   the target takes 9.0 damage as a mob attack credited to
 *   the golem, and every other enemy within 2.5 blocks of
 *   the victim takes 4.5 (the sweep). The 9.0 is a flat
 *   code number stated here on purpose: 6.0 for the swipe
 *   itself plus 3.0 for the form's STRENGTH I, folded in by
 *   hand because of the verified limit below.
 * - Verified limit, documented per the standing rules: the
 *   design's ATTACK_DAMAGE line is INERT on the snow golem.
 *   Verified against the jar, SnowGolem.createAttributes
 *   adds only MAX_HEALTH and MOVEMENT_SPEED, and
 *   LivingEntity's base attribute set has no ATTACK_DAMAGE
 *   instance either, so an ATTACK_DAMAGE modifier would
 *   silently do nothing (the Grand Hex Form precedent);
 *   STRENGTH likewise only boosts real melee attacks, which
 *   the golem never makes. The swipes above are therefore
 *   code damage calls, and the STRENGTH effect is carried
 *   for its icon and any interaction that reads it, with
 *   its damage folded into the flat swipe number. The
 *   golem's vanilla ranged goal also still fires an
 *   occasional ordinary snowball during the form; nothing
 *   in 26.2 lets an ability suspend one goal cleanly, and
 *   the swipes are the form's real pressure.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): this ability places and
 * breaks no blocks, so the mobGriefing gate does not apply:
 * everything is attribute modifiers, one effect, code
 * damage, and particles. Documented render limit: the
 * design's thick snowy limbs and icicle nose are a
 * retexture with no server-side hook in 26.2, so the look
 * is carried by the tripled size and the snow shedding.
 *
 * WHAT IT LOOKS LIKE: a deep SNOW_GOLEM_AMBIENT (volume
 * 1.5, pitch 0.6, the yeti voice) at the start with a burst
 * of 16 SNOWFLAKE and 8 ITEM_SNOWBALL as the snow packs
 * on, 2 SNOWFLAKE shedding off the body every 5 ticks for
 * the whole form, and per swipe a SWEEP_ATTACK arc plus 6
 * ITEM_SNOWBALL at the victim with a PLAYER_ATTACK_SWEEP
 * (volume 1.0, pitch 0.8).
 *
 * PERFORMANCE: tick() returns immediately except on the
 * 5-tick shedding and the 10-tick swipe check; a swipe is
 * one distance check plus at most one bounded query around
 * the victim. The modifiers are built once in start() and
 * removed once in end(). Particle budget per activation:
 * opening 24, shedding at most 80 times 2 = 160, swipes at
 * most 40 times 7 = 280, total at most 464 particles.
 *
 * State tag keys: none. The form is a pure function of the
 * framework tick counter, and its modifiers and effect are
 * removed in end() by fixed ids.
 */
public final class AbominableFormAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int SWIPE_INTERVAL = 10;
    private static final double SWIPE_REACH = 3.5;
    private static final double SWEEP_RADIUS = 2.5;
    private static final float SWIPE_DAMAGE = 9.0f;
    private static final float SWEEP_DAMAGE = 4.5f;
    private static final float HEALTH_BONUS = 12.0f;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "abominable_form_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "abominable_form_health");

    @Override
    public String id() {
        return "abominable_form";
    }

    @Override
    public String displayName() {
        return "Abominable Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SnowGolemAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        SnowGolemAbilitySupport.addModifier(caster, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));
        // Arrive full: the added health comes in healed (the
        // Colossus Form pattern); end() clamps it back off.
        caster.heal(HEALTH_BONUS);
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNOW_GOLEM_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.6f);
            level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                16, 0.8, 1.0, 0.8, 0.08);
            level.sendParticles(ParticleTypes.ITEM_SNOWBALL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                8, 0.7, 0.9, 0.7, 0.15);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // Snow shedding off the packed body.
        if (ticks % 5 == 0) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 1.5, caster.getZ(),
                2, 1.2, 1.2, 1.2, 0.03);
        }

        if (ticks % SWIPE_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            if (target != null && target.isAlive()
                && caster.distanceToSqr(target) <= SWIPE_REACH * SWIPE_REACH) {
                swipe(ctx, level, target);
            }
        }
    }

    /** One swipe: full damage to the victim, half to every other enemy beside it. */
    private static void swipe(AbilityContext ctx, ServerLevel level, LivingEntity victim) {
        Mob caster = ctx.mob();
        victim.hurtServer(level, level.damageSources().mobAttack(caster), SWIPE_DAMAGE);
        List<LivingEntity> bystanders = SnowGolemAbilitySupport.enemiesInBox(
            ctx, victim.getX(), victim.getY(), victim.getZ(), SWEEP_RADIUS, 2.0, 2.0);
        for (LivingEntity bystander : bystanders) {
            if (bystander != victim) {
                bystander.hurtServer(level, level.damageSources().mobAttack(caster), SWEEP_DAMAGE);
            }
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            1, 0.4, 0.3, 0.4, 0.0);
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            6, 0.5, 0.4, 0.5, 0.15);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SnowGolemAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        SnowGolemAbilitySupport.removeModifier(caster, Attributes.MAX_HEALTH, HEALTH_ID);
        if (caster.getHealth() > caster.getMaxHealth()) {
            caster.setHealth(caster.getMaxHealth());
        }
        caster.removeEffect(MobEffects.STRENGTH);
    }
}
