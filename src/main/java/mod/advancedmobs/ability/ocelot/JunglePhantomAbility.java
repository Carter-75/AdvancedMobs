package mod.advancedmobs.ability.ocelot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Jungle Phantom (Ocelot ability 1 of 5, mobility /
 * control).
 *
 * WHAT IT DOES: the ocelot melts into a swirl of jungle
 * leaves and vanishes. While phantom it is invisible and
 * fast, weaving through the fight, and every time it
 * strikes it bursts back out of the leaves in a puff,
 * hits, and is gone again before anything can track it:
 * between strikes, enemy mobs lose the ocelot as their
 * target entirely.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the ocelot's current target within 14 blocks
 *   (distance squared 196).
 * - The melt (start()): the ocelot is marked with the
 *   scoreboard tag "advancedmobs_jungle_phantom" (the
 *   design's tag to track the phantom state; removed in
 *   end()), gains INVISIBILITY and SPEED II (amplifier 1)
 *   for 320 ticks (both removed exactly in end()), and
 *   vanishes in a burst of 12 TINTED_LEAVES (jungle green)
 *   plus 6 POOF motes, with OCELOT_AMBIENT at pitch 1.2.
 * - The weave (tick(), every 5 ticks): the ocelot is
 *   navigation-driven onto its live target at speed 1.3
 *   while it is more than 2.0 blocks away.
 * - The strike (tick(), every 5 ticks, in reach): within
 *   2.0 blocks (distance squared 4.0), at most once per 25
 *   ticks, the phantom re-enters: damage is the ocelot's
 *   live ATTACK_DAMAGE attribute value (a base ocelot:
 *   3.0, raised by the mod's damage upgrades), dealt
 *   through a mob attack source credited to the ocelot,
 *   with a re-entry burst of 6 POOF plus 4 TINTED_LEAVES
 *   at the victim and OCELOT_HURT at pitch 1.3. Right
 *   after the strike lands, the fade pulse below runs:
 *   the hit is the moment it was visible, and then it is
 *   gone again.
 * - The fade (on each strike, and every 40 ticks between
 *   strikes): 6 POOF motes swirl at the ocelot, and one
 *   bounded radius-12 query finds every enemy Mob whose
 *   current target is the ocelot and clears that target:
 *   they lose track of it between strikes (the design's
 *   control line). Players are never target-cleared: a
 *   player aims by hand, so there is no AI target to drop;
 *   the invisibility itself is their counter-play.
 * - The leaf drift (tick(), every 4 ticks): 2
 *   TINTED_LEAVES drift down around the invisible ocelot,
 *   the only tell of where the phantom is.
 * - Duration: exactly 300 ticks (15 seconds). The
 *   framework cooldown runs from the end as usual.
 * - end(): the phantom tag is removed and both effects
 *   are removed, exactly the set start() applied.
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: a swirl of green leaves, a puff of
 * dust, and the ocelot is simply not there. Leaves drift
 * where it moves. Damage arrives with a burst of leaves
 * and dust at the victim, the attacker is never seen, and
 * mobs that were chasing it stand down and look for
 * another target, again and again.
 *
 * PERFORMANCE: canTrigger is one distance check. tick()
 * returns immediately except every 4th tick (2 particles),
 * every 5th tick (one distance check, one navigation
 * drive, at most one damage call per 25 ticks), and every
 * 40th tick or on a strike (one bounded radius-12 query).
 * The leaf option is a pre-built static final in the
 * support class. Particle budget per activation: 18 at
 * start, drift 75 times 2 = 150, strikes at most 12 times
 * 10 = 120, fade bursts at most (12 strikes plus 7 pulses)
 * times 6 = 114, total at most 402 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "next_strike" (the next activation tick
 * a phantom strike may land).
 */
public final class JunglePhantomAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = DURATION_TICKS + 20;
    private static final int HUNT_INTERVAL = 5;
    private static final int DRIFT_INTERVAL = 4;
    private static final int FADE_INTERVAL = 40;
    private static final double STRIKE_RANGE_SQR = 4.0; // 2.0 blocks
    private static final int STRIKE_COOLDOWN_TICKS = 25;
    private static final String PHANTOM_TAG = "advancedmobs_jungle_phantom";

    @Override
    public String id() {
        return "jungle_phantom";
    }

    @Override
    public String displayName() {
        return "Jungle Phantom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("next_strike", 0);
        // The design's tag to track the phantom state.
        caster.addTag(PHANTOM_TAG);
        // The melt: unseen and fast for the whole activation.
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 1));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(OcelotAbilitySupport.LEAF, caster.getX(), caster.getY() + 0.6, caster.getZ(),
                12, 0.5, 0.5, 0.5, 0.2);
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                6, 0.4, 0.4, 0.4, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.OCELOT_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.2f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level != null && ticks % DRIFT_INTERVAL == 0) {
            // The leaf drift: the phantom's only tell.
            level.sendParticles(OcelotAbilitySupport.LEAF, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                2, 0.4, 0.3, 0.4, 0.1);
        }
        boolean struck = false;
        if (ticks % HUNT_INTERVAL == 0) {
            struck = hunt(ctx, ticks);
        }
        if (level != null && (struck || ticks % FADE_INTERVAL == 0)) {
            fade(ctx);
        }
    }

    /** The weave and the strike, every 5 ticks. Returns true when a strike landed. */
    private boolean hunt(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        if (caster.distanceToSqr(target) > STRIKE_RANGE_SQR) {
            caster.getNavigation().moveTo(target, 1.3);
            return false;
        }
        if (ticks < ctx.state().getIntOr("next_strike", 0)) {
            return false;
        }
        ctx.state().putInt("next_strike", ticks + STRIKE_COOLDOWN_TICKS);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return false;
        }
        // The re-entry strike: the phantom lands one hit and is gone again.
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        level.sendParticles(ParticleTypes.POOF, target.getX(), target.getY() + 0.5, target.getZ(),
            6, 0.4, 0.4, 0.4, 0.08);
        level.sendParticles(OcelotAbilitySupport.LEAF, target.getX(), target.getY() + 0.6, target.getZ(),
            4, 0.4, 0.4, 0.4, 0.2);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.OCELOT_HURT, SoundSource.HOSTILE, 1.0f, 1.3f);
        return true;
    }

    /** The fade: a dust swirl, and enemy mobs lose the ocelot as their target. */
    private void fade(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            6, 0.4, 0.4, 0.4, 0.05);
        // Enemies lose track between strikes: one bounded query.
        for (LivingEntity enemy : OcelotAbilitySupport.enemiesAroundSelf(ctx, 12.0, 3.0, 3.0)) {
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                enemyMob.setTarget(null);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeTag(PHANTOM_TAG);
        caster.removeEffect(MobEffects.INVISIBILITY);
        caster.removeEffect(MobEffects.SPEED);
    }
}
