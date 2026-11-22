package mod.advancedmobs.ability.phantom;

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
 * Nightmare Dive (Phantom ability 1 of 5, attack).
 *
 * WHAT IT DOES: the phantom breaks off, climbs high above
 * its victim with a swooping shriek, folds its wings, and
 * comes down in a screaming tear of wind and portal sparks.
 * The dive ends in one heavy crit style strike on the one
 * target, and the victim is stamped with DARKNESS and
 * WEAKNESS so the rest of the flock can finish it while it
 * stumbles blind.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the phantom's current target within 24 blocks (distance
 *   squared 576).
 * - The climb (start() and phase 0, 20 ticks): start()
 *   plays a PHANTOM_SWOOP (volume 1.2, pitch 0.9). Every
 *   climb tick the velocity is set toward a point 12 blocks
 *   above the target's live position at 1.0 blocks per tick
 *   (the step is capped at the remaining distance), with 2
 *   PORTAL particles trailing at the phantom's body each
 *   tick. If the target dies during the climb, the climb
 *   point is above the phantom itself and the dive phase
 *   then finds no target and misses on purpose (see below).
 * - Phase 1, the dive: every tick the velocity is set
 *   straight at the target's live chest (feet plus 0.8) at
 *   1.9 blocks per tick, with 2 PORTAL particles trailing.
 *   Impact is the first tick the phantom is within 1.6
 *   blocks of the target: the strike lands (below). A
 *   second PHANTOM_SWOOP (volume 1.0, pitch 1.3) plays at
 *   dive entry.
 * - The strike (exactly once, on impact): the target alone
 *   takes mob-attack damage credited to the phantom equal
 *   to the phantom's live ATTACK_DAMAGE value plus 6.0 (a
 *   vanilla phantom's sheet reads 6, so an unupgraded
 *   phantom strikes for 12.0, double its normal bite: the
 *   crit of the design). The victim gains DARKNESS for 100
 *   ticks and WEAKNESS I (amplifier 0) for 100 ticks, and
 *   8 CRIT plus 6 DAMAGE_INDICATOR particles burst off its
 *   chest with a PHANTOM_BITE (volume 1.2, pitch 0.9).
 *   Then the recover phase.
 * - The miss: if the dive runs 40 ticks without impact, or
 *   the phantom falls 3 blocks below the target's feet, or
 *   the target is gone at dive entry, the dive has missed:
 *   no strike, no debuffs, straight to the recover phase.
 *   The price of a miss is the wasted dive itself.
 * - Phase 2, the recover: 10 ticks of damped flight
 *   (velocity times 0.6 per tick) while the phantom pulls
 *   out of the dive, then the ability ends and the vanilla
 *   circling AI takes back over.
 * - Duration: the phases above; hard finish at 90 ticks.
 * - end(): nothing to reverse. This ability applies no
 *   attribute modifiers and no effects to the caster; the
 *   victim's DARKNESS and WEAKNESS are fixed-duration
 *   effects that expire on their own clocks, the standard
 *   treatment for victim debuffs in this system.
 *
 * WHAT IT LOOKS LIKE: a swoop cry, a dark shape climbing
 * fast in a corkscrew of purple portal sparks, a second
 * sharper cry, then the shape folding into a near-vertical
 * streak that slams into one victim in a burst of crit
 * stars and damage indicators, leaving it blinking in
 * darkness while the phantom wheels away.
 *
 * PERFORMANCE: per tick one distance computation, one
 * velocity write, and 2 particles; the strike is a single
 * damage call plus two effect applications on the one
 * impact tick; no entity scans anywhere. Particle budget
 * per activation: climb 20 times 2 = 40, dive at most 40
 * times 2 = 80, strike burst 14, total at most 134
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "phase" (0 climb, 1 dive, 2 recover),
 * "phase_ticks", "done" (1 when the recover completes).
 */
public final class NightmareDiveAbility implements MobAbility {

    private static final int CLIMB_TICKS = 20;
    private static final int DIVE_MAX_TICKS = 40;
    private static final int RECOVER_TICKS = 10;
    private static final int HARD_FINISH_TICKS = 90;
    private static final double CLIMB_SPEED = 1.0;
    private static final double DIVE_SPEED = 1.9;
    private static final double IMPACT_DISTANCE = 1.6;
    private static final double STRIKE_BONUS = 6.0;
    private static final int DEBUFF_TICKS = 100;

    @Override
    public String id() {
        return "nightmare_dive";
    }

    @Override
    public String displayName() {
        return "Nightmare Dive";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("phase_ticks", 0);
        ctx.state().putInt("done", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PHANTOM_SWOOP,
            SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int phase = ctx.state().getIntOr("phase", 0);
        int phaseTicks = ctx.state().getIntOr("phase_ticks", 0) + 1;
        ctx.state().putInt("phase_ticks", phaseTicks);
        LivingEntity target = ctx.target();

        if (phase == 0) {
            double climbX = target != null ? target.getX() : caster.getX();
            double climbY = (target != null ? target.getY() : caster.getY()) + 12.0;
            double climbZ = target != null ? target.getZ() : caster.getZ();
            PhantomAbilitySupport.steerToward(ctx, climbX, climbY, climbZ, CLIMB_SPEED);
            trail(ctx, level);
            if (phaseTicks >= CLIMB_TICKS) {
                if (level != null) {
                    level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PHANTOM_SWOOP,
                        SoundSource.HOSTILE, 1.0f, 1.3f);
                }
                setPhase(ctx, 1);
            }
        } else if (phase == 1) {
            if (target == null) {
                setPhase(ctx, 2);
                return;
            }
            double dist = PhantomAbilitySupport.steerToward(ctx,
                target.getX(), target.getY() + 0.8, target.getZ(), DIVE_SPEED);
            trail(ctx, level);
            if (dist <= IMPACT_DISTANCE) {
                strike(ctx, level, target);
                setPhase(ctx, 2);
            } else if (phaseTicks >= DIVE_MAX_TICKS || caster.getY() < target.getY() - 3.0) {
                setPhase(ctx, 2); // The miss: no strike, straight to the pull-out.
            }
        } else {
            caster.setDeltaMovement(caster.getDeltaMovement().scale(0.6));
            if (phaseTicks >= RECOVER_TICKS) {
                ctx.state().putInt("done", 1);
            }
        }
    }

    /** The portal spark trail shed while climbing and diving (2 per tick). */
    private static void trail(AbilityContext ctx, ServerLevel level) {
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            2, 0.25, 0.2, 0.25, 0.05);
    }

    /** The one heavy strike at the bottom of the dive (see the class comment). Runs exactly once. */
    private static void strike(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        float damage = (float) (caster.getAttributeValue(Attributes.ATTACK_DAMAGE) + STRIKE_BONUS);
        target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        target.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DEBUFF_TICKS, 0));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, 0));
        double cx = target.getX();
        double cy = target.getY() + 0.9;
        double cz = target.getZ();
        level.sendParticles(ParticleTypes.CRIT, cx, cy, cz, 8, 0.3, 0.4, 0.3, 0.15);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, cx, cy, cz, 6, 0.3, 0.4, 0.3, 0.1);
        level.playSound(null, cx, cy, cz, SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    private static void setPhase(AbilityContext ctx, int phase) {
        ctx.state().putInt("phase", phase);
        ctx.state().putInt("phase_ticks", 0);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("done", 0) == 1 || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to reverse: no caster modifiers or effects
        // were applied (see the class comment).
    }
}
