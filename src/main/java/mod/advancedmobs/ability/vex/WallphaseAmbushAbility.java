package mod.advancedmobs.ability.vex;

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
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.phys.Vec3;

/**
 * Wallphase Ambush (Vex ability 2 of 5, mobility, attack).
 *
 * WHAT IT DOES: the vex dives in a straight line at the spot
 * directly behind its target, passing through any wall, floor
 * or ceiling in the way on its natural phasing flight and
 * trailing PORTAL wisps, erupts out of the cover at the
 * target's back with its sword already swinging behind a
 * burst of STRENGTH, lands one committed ambush blow, and
 * darts straight back through cover to where it started
 * before enemies can answer. There is no safe wall to put
 * your back to while this vex hunts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vex's current target within 14 blocks (distance
 *   squared 196).
 * - The setup (start()): the vex's position is recorded as
 *   its home point. The exit point is computed once, from the
 *   target's position at cast: 1.4 blocks behind the target
 *   relative to the target's own facing (its look angle,
 *   horizontal), at the target's feet height plus 1.2 (about
 *   chest height on a standing victim). The vex gains
 *   STRENGTH II (amplifier 1) for 160 ticks, removed in
 *   end(); a VEX_CHARGE plays (volume 1.0, pitch 1.2). The
 *   vex is put in its vanilla charging state for the whole
 *   ability: while a vex charges, its own tick sets
 *   noPhysics, which is the vanilla phasing that lets it pass
 *   through solid blocks. The ability re-asserts the charging
 *   flag every tick, because the vex's own charge goal also
 *   writes it.
 * - Phase 0, the dive (steered in code, as the design's Uses
 *   list calls for): every tick the vex's velocity is set
 *   straight at the exit point at 1.15 blocks per tick (the
 *   step is capped at the remaining distance, so it does not
 *   overshoot). 2 PORTAL particles trail at its center each
 *   tick. The dive ends when the vex is within 0.9 blocks of
 *   the exit point, or after 50 ticks if something kept it
 *   from arriving.
 * - Phase 1, the strike: the vex keeps flying at the target's
 *   live chest position at 1.25 blocks per tick. The first
 *   tick it is within 1.9 blocks of the target, the ambush
 *   blow lands in code: mob-attack damage credited to the
 *   vex equal to its live ATTACK_DAMAGE attribute value
 *   (which includes its iron sword) plus 3.0, with 6 CRIT
 *   particles at the victim. The STRENGTH II is on top of
 *   this for any vanilla sword hits the vex lands during the
 *   ability, which is the design's "burst of STRENGTH behind
 *   the blow". After the blow, or after 12 strike ticks
 *   without reaching the target (a whiff), the ability moves
 *   to the retreat.
 * - Phase 2, the retreat: every tick the velocity is set
 *   straight at the recorded home point at 1.25 blocks per
 *   tick, PORTAL trail running. The retreat ends within 1.0
 *   block of home, or after 25 ticks.
 * - Duration: the phases above; hard finish at 110 ticks.
 * - end(): the charging flag is cleared and the STRENGTH is
 *   removed. If the caster dies first, the framework
 *   termination path runs this same end(), so a dead vex
 *   never keeps its ambush strength or its phasing flag.
 *
 * WHAT IT LOOKS LIKE: a screech, then a line of portal wisps
 * cutting straight through the wall; the vex bursts out of
 * the solid block at the victim's back, crit sparkles on the
 * hit, and the wisp line folds back into the wall it came
 * from.
 *
 * PERFORMANCE: every tick is one distance computation, one
 * velocity write, and 2 particles; no entity queries at all
 * (the victim is the resolved target). Particle budget per
 * activation: dive at most 50 times 2 = 100, strike burst 6,
 * retreat at most 25 times 2 = 50, total at most 156
 * particles.
 *
 * TERRAIN: this ability changes no blocks; it passes
 * through them on the vex's vanilla phasing, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "phase" (0 dive, 1 strike, 2 retreat),
 * "phase_ticks", "exit_x", "exit_y", "exit_z", "home_x",
 * "home_y", "home_z", "struck" (1 once the ambush blow has
 * landed), "done" (1 when the retreat completes).
 */
public final class WallphaseAmbushAbility implements MobAbility {

    private static final int STRENGTH_TICKS = 160;
    private static final int DIVE_MAX_TICKS = 50;
    private static final int STRIKE_MAX_TICKS = 12;
    private static final int RETREAT_MAX_TICKS = 25;
    private static final int HARD_FINISH_TICKS = 110;
    private static final double DIVE_SPEED = 1.15;
    private static final double STRIKE_SPEED = 1.25;
    private static final double AMBUSH_BONUS_DAMAGE = 3.0;

    @Override
    public String id() {
        return "wallphase_ambush";
    }

    @Override
    public String displayName() {
        return "Wallphase Ambush";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("phase_ticks", 0);
        ctx.state().putInt("struck", 0);
        ctx.state().putInt("done", 0);
        ctx.state().putDouble("home_x", caster.getX());
        ctx.state().putDouble("home_y", caster.getY());
        ctx.state().putDouble("home_z", caster.getZ());

        LivingEntity target = ctx.target();
        double exitX = caster.getX();
        double exitY = caster.getY();
        double exitZ = caster.getZ();
        if (target != null) {
            // Directly behind the target, against its own facing.
            Vec3 look = target.getLookAngle();
            double len = Math.sqrt(look.x * look.x + look.z * look.z);
            double backX = len > 0.001 ? -look.x / len : 0.0;
            double backZ = len > 0.001 ? -look.z / len : 1.0;
            exitX = target.getX() + backX * 1.4;
            exitY = target.getY() + 1.2;
            exitZ = target.getZ() + backZ * 1.4;
        }
        ctx.state().putDouble("exit_x", exitX);
        ctx.state().putDouble("exit_y", exitY);
        ctx.state().putDouble("exit_z", exitZ);

        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, STRENGTH_TICKS, 1, false, false, false));
        if (caster instanceof Vex vex) {
            vex.setIsCharging(true);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VEX_CHARGE,
                SoundSource.HOSTILE, 1.0f, 1.2f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (caster instanceof Vex vex) {
            vex.setIsCharging(true); // Re-asserted: the vex's own charge goal writes this flag too.
        }
        int phase = ctx.state().getIntOr("phase", 0);
        int phaseTicks = ctx.state().getIntOr("phase_ticks", 0) + 1;
        ctx.state().putInt("phase_ticks", phaseTicks);

        if (phase == 0) {
            double dist = steerToward(ctx, ctx.state().getDoubleOr("exit_x", caster.getX()),
                ctx.state().getDoubleOr("exit_y", caster.getY()),
                ctx.state().getDoubleOr("exit_z", caster.getZ()), DIVE_SPEED);
            trail(ctx, level);
            if (dist <= 0.9 || phaseTicks >= DIVE_MAX_TICKS) {
                setPhase(ctx, 1);
            }
        } else if (phase == 1) {
            LivingEntity target = ctx.target();
            if (target == null) {
                setPhase(ctx, 2);
                return;
            }
            double dist = steerToward(ctx, target.getX(), target.getY() + 0.8, target.getZ(), STRIKE_SPEED);
            if (dist <= 1.9 && ctx.state().getIntOr("struck", 0) == 0) {
                strike(ctx, level, target);
                setPhase(ctx, 2);
            } else if (phaseTicks >= STRIKE_MAX_TICKS) {
                setPhase(ctx, 2); // A whiff still retreats.
            }
        } else {
            double dist = steerToward(ctx, ctx.state().getDoubleOr("home_x", caster.getX()),
                ctx.state().getDoubleOr("home_y", caster.getY()),
                ctx.state().getDoubleOr("home_z", caster.getZ()), STRIKE_SPEED);
            trail(ctx, level);
            if (dist <= 1.0 || phaseTicks >= RETREAT_MAX_TICKS) {
                ctx.state().putInt("done", 1);
            }
        }
    }

    /** Sets the vex's velocity straight at a point and returns the distance to it before the move. */
    private static double steerToward(AbilityContext ctx, double x, double y, double z, double speed) {
        Mob caster = ctx.mob();
        double dx = x - caster.getX();
        double dy = y - caster.getY();
        double dz = z - caster.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) {
            caster.setDeltaMovement(0.0, 0.0, 0.0);
            return 0.0;
        }
        double step = Math.min(speed, dist);
        caster.setDeltaMovement(dx / dist * step, dy / dist * step, dz / dist * step);
        return dist;
    }

    /** The ambush blow (see the class comment). Runs exactly once per activation. */
    private static void strike(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        ctx.state().putInt("struck", 1);
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        float damage = (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE) + (float) AMBUSH_BONUS_DAMAGE;
        target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.8, target.getZ(),
            6, 0.3, 0.4, 0.3, 0.1);
    }

    /** The portal wisp trail behind the phasing vex. */
    private static void trail(AbilityContext ctx, ServerLevel level) {
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            2, 0.15, 0.2, 0.15, 0.0);
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
        Mob caster = ctx.mob();
        if (caster instanceof Vex vex) {
            vex.setIsCharging(false);
        }
        caster.removeEffect(MobEffects.STRENGTH);
    }
}
