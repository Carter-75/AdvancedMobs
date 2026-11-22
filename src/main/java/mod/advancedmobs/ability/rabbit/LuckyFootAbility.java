package mod.advancedmobs.ability.rabbit;

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
 * Lucky Foot (Rabbit ability 4 of 5, aura / defense).
 *
 * WHAT IT DOES: the rabbit's foot kindles with enchant
 * sparkles and fortune itself leans toward it: for
 * fifteen seconds it is preposterously hard to pin down,
 * quick and springy and lucky, while every enemy nearby
 * is cursed with fumbling lucklessness, their blows
 * landing soft.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the rabbit's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The blessing (start(), all removed in end()): LUCK
 *   I, SPEED I, and JUMP_BOOST I on the rabbit, each for
 *   320 ticks (amplifier 0). The design's luck line is
 *   the LUCK effect: the luck attribute itself is
 *   player-only in 26.2 (verified in CAPABILITIES.md),
 *   so the effect is the carrier, as the design's own
 *   Uses line states. The opening burst is 12 ENCHANT
 *   particles swirling at the rabbit's feet.
 * - The sparkle (tick()): 2 ENCHANT particles at the
 *   rabbit's feet every 2 ticks for the whole blessing,
 *   the glowing foot of the design.
 * - The curse (tick(), pulse every 10 ticks): one
 *   bounded query at radius 5.0 around the rabbit;
 *   every enemy (the package enemy rule) inside gains
 *   UNLUCK I and WEAKNESS I, each refreshed to 30
 *   ticks. The design's pair: unluck is the curse
 *   itself, and weakness is its teeth, the fumbled
 *   attacks landing for reduced damage. Refreshed
 *   while the enemy stays in the aura, expiring on its
 *   own clock about a second and a half after it gets
 *   out. The effects are never stripped by end(); they
 *   ride their own clocks (the Caravan Call precedent).
 * - end(): LUCK, SPEED, and JUMP_BOOST are removed from
 *   the rabbit. A rabbit that somehow had one of them
 *   from another source loses it at end(), an accepted
 *   edge case shared with every fixed-duration effect
 *   in this system.
 *
 * WHAT IT LOOKS LIKE: a rabbit with one foot trailing
 * enchant sparkles, bouncing higher and darting faster
 * than a rabbit should, while everything trying to hit
 * it swings like it is having the worst day of its life.
 *
 * PERFORMANCE: 2 particles every 2 ticks and one bounded
 * query every 10 ticks; nothing else per tick. Particle
 * budget per activation: 12 at start, sparkle 2 times
 * 150 = 300, total at most 312 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the activation is pure timers
 * and effects; the framework tick counter drives it).
 */
public final class LuckyFootAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final double CURSE_RADIUS = 5.0;
    private static final int CURSE_REFRESH_TICKS = 30;

    @Override
    public String id() {
        return "lucky_foot";
    }

    @Override
    public String displayName() {
        return "Lucky Foot";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.LUCK, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, EFFECT_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                12, 0.4, 0.3, 0.4, 0.4);
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
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.15, caster.getZ(),
                2, 0.3, 0.15, 0.3, 0.3);
        }
        if (ticks % 10 != 0) {
            return; // Cheap early-out: the curse pulses every 10 ticks only.
        }
        List<LivingEntity> cursed = RabbitAbilitySupport.enemiesAroundSelf(ctx,
            CURSE_RADIUS, 2.0, 2.0);
        for (LivingEntity enemy : cursed) {
            enemy.addEffect(new MobEffectInstance(MobEffects.UNLUCK, CURSE_REFRESH_TICKS, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CURSE_REFRESH_TICKS, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.LUCK);
        caster.removeEffect(MobEffects.SPEED);
        caster.removeEffect(MobEffects.JUMP_BOOST);
    }
}
