package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Infestation Bite (Endermite ability 1 of 5, attack).
 *
 * WHAT IT DOES: for the duration, the mite's bite carries the
 * void taint. Every bite it lands tags the victim with
 * INFESTED, and from then on the victim's own injuries work
 * against it: vanilla INFESTED gives every hit the victim
 * takes a 10 percent chance to burst 1 to 3 silverfish out of
 * its body, silverfish that turn on the victim (the vanilla
 * behavior of the effect, verified in CAPABILITIES.md). The
 * mite, meanwhile, gets a burst of speed after each tainted
 * bite so it can scuttle clear while the swarm problem grows.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mite's current target within 3.5 blocks (distance
 *   squared 12.25). This is a bite augmentation; it only makes
 *   sense at fang range.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Bite detection: the bite clock (see
 *   EndermiteAbilitySupport) is primed in start() and polled
 *   every 4 ticks. A poll that reports a bitten enemy applies
 *   INFESTED, amplifier 0, for 300 ticks (15 seconds) to that
 *   victim, refreshed by every later tainted bite, so a victim
 *   the mite keeps biting stays infested well past the end of
 *   this activation: the taint outliving the biter is the
 *   design. The mite itself gains SPEED I (amplifier 0) for
 *   40 ticks per tainted bite, refreshed the same way: the
 *   scuttle-clear.
 * - Bite spacing: a bite only counts when at least 10 ticks
 *   passed since the last counted one. A mite's real attack
 *   interval is about a second, so this changes nothing in
 *   honest play; it only stops two polls inside one attack
 *   cooldown from double-tagging.
 * - The victim keeps whatever else vanilla INFESTED does and
 *   nothing more: no direct damage is added by this ability.
 *   The bite itself is the mite's normal 2.0 melee damage.
 *
 * WHAT IT LOOKS LIKE: each tainted bite bursts 10 purple
 * ENTITY_EFFECT particles (color 0.62, 0.25, 0.85, the ender
 * violet used across this mod) over the victim's body, the
 * void taint visibly boiling off the wound. The silverfish
 * that later burst out are plain vanilla silverfish spawned
 * by the INFESTED effect itself.
 *
 * PERFORMANCE: tick() returns immediately except every 4th
 * tick, where it reads two timestamps and at most one entity
 * reference off the caster: no entity scans, no allocations.
 * Particle budget per activation: at most 24 counted bites
 * (240 ticks at the 10 tick spacing) times 10 particles = at
 * most 240 particles.
 *
 * CLEANUP: nothing to undo. INFESTED and SPEED are timed
 * effects that expire on their own; the design wants the
 * taint to outlive the activation. end() is intentionally
 * empty.
 *
 * State tag keys: "hit_ts" (the bite clock timestamp, see
 * EndermiteAbilitySupport), "last_bite" (tick of the last
 * counted bite, for the spacing rule).
 */
public final class InfestationBiteAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int POLL_INTERVAL = 4;
    private static final int BITE_SPACING = 10;
    private static final int INFESTED_TICKS = 300;
    private static final int SCUTTLE_TICKS = 40;
    private static final ColorParticleOption TAINT =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.62f, 0.25f, 0.85f);

    @Override
    public String id() {
        return "infestation_bite";
    }

    @Override
    public String displayName() {
        return "Infestation Bite";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 12.25;
    }

    @Override
    public void start(AbilityContext ctx) {
        EndermiteAbilitySupport.primeBiteClock(ctx);
        ctx.state().putInt("last_bite", -BITE_SPACING);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % POLL_INTERVAL != 0) {
            return; // Cheap early-out: the bite clock is polled every 4 ticks only.
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        if (ticks - state.getIntOr("last_bite", 0) < BITE_SPACING) {
            return; // Still inside the spacing window of the last counted bite.
        }
        LivingEntity victim = EndermiteAbilitySupport.consumeLandedBite(ctx);
        if (victim == null) {
            return;
        }
        state.putInt("last_bite", ticks);

        // The taint lands: INFESTED on the victim, speed on the mite.
        victim.addEffect(new MobEffectInstance(MobEffects.INFESTED, INFESTED_TICKS, 0));
        Mob mob = ctx.mob();
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, SCUTTLE_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(TAINT, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
                10, 0.3, 0.4, 0.3, 0.1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: the INFESTED tag and the scuttle
        // speed are timed effects, and the design has the taint
        // outlive the activation (see the class comment).
    }
}
