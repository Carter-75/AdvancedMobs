package mod.advancedmobs.ability.bat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.phys.Vec3;

/**
 * Dive Bomb (Bat ability 2 of 5, attack).
 *
 * WHAT IT DOES: the bat climbs above its victim, folds its
 * wings, and drops like a stone, gravity cranked up and a
 * streak of smoke behind it, pulling out at the last instant
 * to rake the target with one hard hit for something so
 * small. The fall converts straight back into climb speed
 * and the bat is gone before the enemy finishes turning.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bat's current target within 20 blocks (distance
 *   squared 400).
 * - The climb (start() and phase 0, 15 ticks): start() wakes
 *   a resting bat (setResting(false), BAT_TAKEOFF volume 1.0
 *   pitch 1.0). Every climb tick the velocity is set toward
 *   a point 8 blocks above the target's live position at
 *   0.95 blocks per tick (the step is capped at the remaining
 *   distance). If the target dies during the climb, the climb
 *   point is above the bat itself and the dive then misses
 *   on purpose (see below).
 * - The dive (phase 1, at most 35 ticks): at dive entry the
 *   bat gains two transient attribute modifiers, both removed
 *   in end(): GRAVITY plus 1.5 ADD_MULTIPLIED_BASE (gravity
 *   at 2.5 times normal, the design's cranked gravity) and
 *   SAFE_FALL_DISTANCE plus 40.0 ADD_VALUE (the bat takes no
 *   fall damage out of its own dive, the design's safe fall
 *   line). A BAT_AMBIENT screech (volume 1.2, pitch 1.5)
 *   marks the fold. Each dive tick the horizontal velocity
 *   is aimed at the target's live position at 0.85 blocks
 *   per tick while the vertical velocity is left to the
 *   cranked gravity, only clamped so the fall never exceeds
 *   2.6 blocks per tick; 2 SMOKE particles streak behind the
 *   bat each tick. Impact is the first tick the bat is
 *   within 1.7 blocks of the target: the strike lands
 *   (below). If the bat touches the ground (onGround) within
 *   2.2 horizontal blocks of the target, the strike also
 *   lands; touching ground farther away, losing the target,
 *   or running the full 35 ticks is a miss: no strike,
 *   straight to the pull-out.
 * - The strike (exactly once, on impact): the target alone
 *   takes a flat 5.0 damage through
 *   damageSources().mobAttack(caster), hard for a 6 health
 *   bat and the payoff of the whole dive (the bat has no
 *   ATTACK_DAMAGE instance to scale from, see the support
 *   class comment), with 10 CRIT particles bursting off the
 *   victim's chest and a BAT_AMBIENT (volume 1.2, pitch 0.8)
 *   at the victim.
 * - The pull-out (phase 2, 12 ticks): every tick the velocity
 *   is set toward a point 6 blocks above the target's live
 *   position (above the bat itself if the target is gone) at
 *   1.1 blocks per tick, converting the fall back into climb,
 *   then the ability ends and vanilla bat flight resumes.
 * - Duration: the phases above; hard finish at 90 ticks.
 * - end(): removes the GRAVITY and SAFE_FALL_DISTANCE
 *   transient modifiers exactly, by their fixed ids; both
 *   removals are no-ops if the dive phase never ran.
 *
 * WHAT IT LOOKS LIKE: a takeoff flutter, a small shape
 * climbing fast, a screech, then the shape folding into a
 * near-vertical smoking streak that slams into one victim in
 * a burst of crit stars and vanishes upward again.
 *
 * PERFORMANCE: per tick one distance computation, one
 * velocity write, and at most 2 particles; the strike is a
 * single damage call on the one impact tick; no entity scans
 * anywhere. Particle budget per activation: dive at most 35
 * times 2 = 70 smoke, strike burst 10, total at most 80
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "phase" (0 climb, 1 dive, 2 pull-out),
 * "phase_ticks", "done" (1 when the pull-out completes).
 */
public final class DiveBombAbility implements MobAbility {

    private static final int CLIMB_TICKS = 15;
    private static final int DIVE_MAX_TICKS = 35;
    private static final int PULL_OUT_TICKS = 12;
    private static final int HARD_FINISH_TICKS = 90;
    private static final double CLIMB_SPEED = 0.95;
    private static final double DIVE_STEER_SPEED = 0.85;
    private static final double MAX_FALL_SPEED = 2.6;
    private static final double PULL_OUT_SPEED = 1.1;
    private static final double IMPACT_DISTANCE = 1.7;
    private static final double GROUND_STRIKE_RANGE = 2.2;
    private static final float STRIKE_DAMAGE = 5.0f;
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "dive_bomb_gravity");
    private static final Identifier SAFE_FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "dive_bomb_safe_fall");

    @Override
    public String id() {
        return "dive_bomb";
    }

    @Override
    public String displayName() {
        return "Dive Bomb";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
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
        if (caster instanceof Bat bat && bat.isResting()) {
            bat.setResting(false);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_TAKEOFF,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
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
            double climbY = (target != null ? target.getY() : caster.getY()) + 8.0;
            double climbZ = target != null ? target.getZ() : caster.getZ();
            BatAbilitySupport.steerToward(ctx, climbX, climbY, climbZ, CLIMB_SPEED);
            if (phaseTicks >= CLIMB_TICKS) {
                enterDive(ctx, level);
            }
        } else if (phase == 1) {
            if (target == null) {
                setPhase(ctx, 2);
                return;
            }
            diveTick(ctx, level, target, phaseTicks);
        } else {
            double outX = target != null ? target.getX() : caster.getX();
            double outY = (target != null ? target.getY() : caster.getY()) + 6.0;
            double outZ = target != null ? target.getZ() : caster.getZ();
            BatAbilitySupport.steerToward(ctx, outX, outY, outZ, PULL_OUT_SPEED);
            if (phaseTicks >= PULL_OUT_TICKS) {
                ctx.state().putInt("done", 1);
            }
        }
    }

    /** Dive entry: crank gravity, protect the fall, screech. Runs exactly once. */
    private static void enterDive(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        BatAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(GRAVITY_ID, 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        BatAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(SAFE_FALL_ID, 40.0, AttributeModifier.Operation.ADD_VALUE));
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_AMBIENT,
                SoundSource.HOSTILE, 1.2f, 1.5f);
        }
        setPhase(ctx, 1);
    }

    /** One dive tick: aim horizontally, let cranked gravity drive the fall, test impact. */
    private void diveTick(AbilityContext ctx, ServerLevel level, LivingEntity target, int phaseTicks) {
        Mob caster = ctx.mob();
        double dx = target.getX() - caster.getX();
        double dz = target.getZ() - caster.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity = caster.getDeltaMovement();
        double vx = horizontal < 0.001 ? 0.0 : dx / horizontal * Math.min(DIVE_STEER_SPEED, horizontal);
        double vz = horizontal < 0.001 ? 0.0 : dz / horizontal * Math.min(DIVE_STEER_SPEED, horizontal);
        double vy = Math.max(velocity.y, -MAX_FALL_SPEED);
        caster.setDeltaMovement(vx, vy, vz);
        if (level != null) {
            level.sendParticles(ParticleTypes.SMOKE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.15, 0.15, 0.15, 0.02);
        }
        double dist = caster.distanceTo(target);
        if (dist <= IMPACT_DISTANCE) {
            strike(ctx, level, target);
            setPhase(ctx, 2);
        } else if (caster.onGround()) {
            // Down at ground level: the rake lands only if the
            // bat came down on top of its victim.
            if (horizontal <= GROUND_STRIKE_RANGE) {
                strike(ctx, level, target);
            }
            setPhase(ctx, 2);
        } else if (phaseTicks >= DIVE_MAX_TICKS) {
            setPhase(ctx, 2); // The miss: no strike, straight to the pull-out.
        }
    }

    /** The one hard rake at the bottom of the dive (see the class comment). Runs exactly once. */
    private static void strike(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        target.hurtServer(level, level.damageSources().mobAttack(caster), STRIKE_DAMAGE);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.7, target.getZ(),
            10, 0.3, 0.4, 0.3, 0.15);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BAT_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 0.8f);
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
        BatAbilitySupport.removeModifier(caster, Attributes.GRAVITY, GRAVITY_ID);
        BatAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_ID);
    }
}
