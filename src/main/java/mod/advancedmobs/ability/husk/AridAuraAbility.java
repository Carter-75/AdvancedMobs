package mod.advancedmobs.ability.husk;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Arid Aura (Husk ability 4 of 5, aura).
 *
 * WHAT IT DOES: for 12 seconds heat shimmers off the husk in waves of
 * ash and pale motes, and the air around it turns desert dry. Enemies
 * standing in the aura steadily gain HUNGER and SLOWNESS as the
 * dryness saps them: they burn food and move like they are wading
 * through hot sand for as long as they stay close.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   husk's current target within 8 blocks (distance squared 64). This
 *   is a brawling aura, not a sniping tool.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Pulse every 20 ticks (12 pulses per activation), radius 4.0
 *   blocks around the husk (2.0 below to 2.5 above its feet),
 *   enemies only (HuskAbilitySupport rules): each pulse applies
 *   HUNGER amplifier 0 and SLOWNESS amplifier 0 for 30 ticks, so the
 *   pair persists briefly between pulses and lapses 10 ticks after
 *   the enemy escapes or the aura ends. There is no stacking ladder:
 *   the design's pressure is steady, not escalating, and the drain is
 *   the vanilla HUNGER food drain plus a constant SLOWNESS drag.
 * - The design's line that any water based defense does nothing to
 *   stop the dryness is honored by construction: the pulse applies
 *   its effects directly and never checks whether a victim is in
 *   water, raining, or carrying water, so there is no water based
 *   exemption to find or to bug.
 *
 * WHAT IT LOOKS LIKE: per pulse, a shimmer burst around the husk's
 * body: 6 ASH particles and 4 pale ENTITY_EFFECT motes (color 0.85,
 * 0.78, 0.60, hot sand), spread 1.6 horizontally and 0.8 vertically
 * around chest height. Victims also show the vanilla effect sparkles
 * of HUNGER and SLOWNESS while debuffed.
 *
 * PERFORMANCE: tick() returns immediately except every 20th tick.
 * The pulse is one bounded entity query (8 by 4.5 by 8 box) plus
 * effect applications to the handful of enemies inside. No state is
 * read or written at all. Particle budget per activation: 12 pulses
 * times 10 particles = at most 120 particles.
 *
 * CLEANUP: end() has nothing to undo. Every effect this aura applies
 * lasts at most 30 ticks and expires on its own; no attribute
 * modifiers, no spawned entities, no block changes.
 *
 * State tag keys: none.
 */
public final class AridAuraAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 20;
    private static final ColorParticleOption PALE_MOTE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.85f, 0.78f, 0.60f);

    @Override
    public String id() {
        return "arid_aura";
    }

    @Override
    public String displayName() {
        return "Arid Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // Nothing to set up: the aura is a pure pulse schedule.
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: pulses run every 20 ticks only.
        }
        List<LivingEntity> victims = HuskAbilitySupport.enemiesAroundSelf(ctx, 4.0, 2.0, 2.5);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.HUNGER, 30, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
        }

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            double x = ctx.mob().getX(), y = ctx.mob().getY() + 1.0, z = ctx.mob().getZ();
            level.sendParticles(ParticleTypes.ASH, x, y, z, 6, 1.6, 0.8, 1.6, 0.02);
            level.sendParticles(PALE_MOTE, x, y, z, 4, 1.6, 0.8, 1.6, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: all applied effects expire within 30 ticks.
    }
}
