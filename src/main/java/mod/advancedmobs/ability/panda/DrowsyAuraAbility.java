package mod.advancedmobs.ability.panda;

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
 * Drowsy Aura (Panda ability 4 of 5, aura).
 *
 * WHAT IT DOES: a lazy calm radiates from the panda,
 * tinted leaves drifting down around it and the light
 * seeming softer inside its bubble. Enemies in the aura
 * grow sluggish, their attacks coming slower and weaker,
 * while the panda naps its way to small steady
 * regeneration.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the panda's current target within 14 blocks (distance
 *   squared 196).
 * - Duration: exactly 400 ticks (20 seconds), centered on
 *   the moving panda (the aura follows it; there is no
 *   fixed center).
 * - The drowse pulse (every 10 ticks, one bounded query at
 *   radius 6.0, 3.0 below to 3.0 above): every enemy in the
 *   aura gains SLOWNESS at amplifier 0, MINING_FATIGUE at
 *   amplifier 0, and WEAKNESS at amplifier 0, each
 *   refreshed to 30 ticks (the design's three lines:
 *   sluggish movement, slower attacks, weaker attacks).
 *   At the same pulse the panda refreshes its own
 *   REGENERATION at amplifier 0 to 30 ticks (the design's
 *   self line: small steady regeneration, 1 health every
 *   50 ticks while the aura holds).
 * - The drift (every 5 ticks): 4 TINTED_LEAVES shed at
 *   random offsets within radius 3.0 of the panda, 2.5
 *   blocks above it, drifting down (the design's falling
 *   leaves; the softer light is carried by the leaves and
 *   the calm, there is no server-side light change).
 * - end(): nothing to clean up. Every effect the aura
 *   applies rides its own 30 tick clock and expires on its
 *   own just past the last pulse (the Caravan Call
 *   precedent); there are no modifiers, displays, or
 *   summons.
 *
 * WHAT IT LOOKS LIKE: a panda ambling through a slow rain
 * of green tinted leaves, everything fighting it moving
 * like it is underwater, weapons swinging late and soft,
 * while the panda itself placidly glows with the faintest
 * regeneration sparkle.
 *
 * PERFORMANCE: the pulse is one bounded query every 10
 * ticks; the drift is 4 particles every 5 ticks with two
 * random doubles each; nothing runs per tick. Particle
 * budget per activation: 80 emissions times 4 = 320
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. TINTED_LEAVES is a particle, not a placed
 * block, and the zone is an effect zone, not terrain.
 *
 * State tag keys: none (the aura is fully described by the
 * tick counter).
 */
public final class DrowsyAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 10;
    private static final int EFFECT_TICKS = 30;
    private static final double AURA_RADIUS = 6.0;
    private static final double LEAF_RADIUS = 3.0;

    @Override
    public String id() {
        return "drowsy_aura";
    }

    @Override
    public String displayName() {
        return "Drowsy Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // Nothing to set up: the first pulse lands on tick 10.
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        if (ticks % 5 == 0) {
            // The leaf drift over the bubble.
            for (int i = 0; i < 4; i++) {
                double offsetX = (ctx.random().nextDouble() * 2.0 - 1.0) * LEAF_RADIUS;
                double offsetZ = (ctx.random().nextDouble() * 2.0 - 1.0) * LEAF_RADIUS;
                level.sendParticles(PandaAbilitySupport.TINTED_LEAVES,
                    caster.getX() + offsetX, caster.getY() + 2.5, caster.getZ() + offsetZ,
                    1, 0.1, 0.1, 0.1, 0.01);
            }
        }

        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the drowse lands on pulses only.
        }
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
        List<LivingEntity> enemies = PandaAbilitySupport.enemiesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, EFFECT_TICKS, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EFFECT_TICKS, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: every effect rides its own
        // clock, see the class comment.
    }
}
