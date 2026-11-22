package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Rot Aura (Zombie ability 4 of 5, aura).
 *
 * WHAT IT DOES: for 12 seconds the zombie boils with a miasma of spores,
 * ash, and sickly green sparkles. Enemies who stay inside the cloud are
 * steadily weakened: the longer an enemy brawls inside the aura, the
 * stronger its WEAKNESS grows, and HUNGER gnaws at it throughout. The
 * zombie feeds on the rot and slowly regenerates while the aura lasts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie's
 *   current target within 8 blocks (distance squared 64). This is a
 *   brawling aura, not a sniping tool.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Pulse every 20 ticks (12 pulses per activation), radius 4.0 blocks
 *   around the zombie (2.0 below to 2.5 above its feet), enemies only
 *   (ZombieAbilitySupport rules):
 *     Each pulse adds 20 ticks to that enemy's exposure record (state
 *     tag "exposure": UUID string to exposed ticks, kept only for
 *     enemies caught by the latest pulse, so the record can never grow
 *     beyond the current crowd).
 *     WEAKNESS amplifier by exposure: 0 below 100 exposed ticks
 *       (5 seconds), 1 from 100, 2 from 200 (10 seconds). Applied for
 *       30 ticks per pulse, so it persists briefly between pulses and
 *       lapses 10 ticks after the enemy escapes or the aura ends.
 *     HUNGER amplifier 0 for 30 ticks per pulse, same refresh.
 *   Vanilla effects do not stack with themselves, so the "stacking" of
 *   the design is this exposure-driven amplifier ladder: leave and the
 *   record is pruned at the next pulse; your next visit starts over.
 * - The zombie gets REGENERATION I (amplifier 0) for 30 ticks per pulse,
 *   refreshed on the same schedule: slow, steady healing while it brawls.
 *
 * WHAT IT LOOKS LIKE: per pulse, a burst around the zombie's body: 6
 * MYCELIUM spore particles, 4 ASH particles, and 4 ENTITY_EFFECT sparkles
 * in sickly green (color 0.35, 0.75, 0.25), spread 1.6 horizontally and
 * 0.8 vertically around chest height. Victims also show the vanilla
 * effect sparkles of WEAKNESS and HUNGER while debuffed.
 *
 * PERFORMANCE: tick() returns immediately except every 20th tick. The
 * pulse is one bounded entity query (8 by 4.5 by 8 box) plus effect
 * applications to the handful of enemies inside. The exposure tag is
 * rebuilt per pulse with one entry per current victim, no accumulation.
 * Particle budget per activation: 12 pulses times 14 particles = 168.
 *
 * CLEANUP: end() has nothing to undo. Every effect this aura applies
 * lasts at most 30 ticks and expires on its own; no attribute modifiers,
 * no spawned entities, no block changes. The exposure record lives in the
 * activation state tag, which the framework discards at end().
 *
 * State tag keys: "exposure" (compound: UUID string to exposed ticks).
 */
public final class RotAuraAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 20;
    private static final ColorParticleOption ROT_SPARKLE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.35f, 0.75f, 0.25f);

    @Override
    public String id() {
        return "rot_aura";
    }

    @Override
    public String displayName() {
        return "Rot Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("exposure", new CompoundTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: pulses run every 20 ticks only.
        }
        CompoundTag previous = ctx.state().getCompoundOrEmpty("exposure");
        CompoundTag exposure = new CompoundTag();
        List<LivingEntity> victims = ZombieAbilitySupport.enemiesAroundSelf(ctx, 4.0, 2.0, 2.5);
        for (LivingEntity victim : victims) {
            String key = victim.getUUID().toString();
            int exposed = previous.getIntOr(key, 0) + PULSE_INTERVAL;
            exposure.putInt(key, exposed);
            int amplifier = exposed >= 200 ? 2 : exposed >= 100 ? 1 : 0;
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, amplifier));
            victim.addEffect(new MobEffectInstance(MobEffects.HUNGER, 30, 0));
        }
        // Replacing the tag prunes every enemy that left the aura.
        ctx.state().put("exposure", exposure);

        // The zombie feeds on the rot.
        ctx.mob().addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            double x = ctx.mob().getX(), y = ctx.mob().getY() + 1.0, z = ctx.mob().getZ();
            level.sendParticles(ParticleTypes.MYCELIUM, x, y, z, 6, 1.6, 0.8, 1.6, 0.02);
            level.sendParticles(ParticleTypes.ASH, x, y, z, 4, 1.6, 0.8, 1.6, 0.02);
            level.sendParticles(ROT_SPARKLE, x, y, z, 4, 1.6, 0.8, 1.6, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: all applied effects expire within 30 ticks and
        // the framework discards the exposure record with the state tag.
    }
}
