package mod.advancedmobs.ability.frog;

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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Leapfrog Slam (Frog ability 3 of 5, mobility / attack).
 *
 * WHAT IT DOES: the frog coils and launches in an
 * enormous arc, jump strength and hang time boosted,
 * crossing the whole arena in one bound. It lands belly
 * first with a burst of poof and block crumble, damaging
 * and knocking back everything at the splashdown.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the frog's current target within 14 blocks
 *   (distance squared 196), plus the frog on the ground
 *   (a leap starts from a coil, not mid-air).
 * - The leap kit (start(), removed exactly in end()):
 *   four transient modifiers, the design's attribute
 *   lines: JUMP_STRENGTH +0.6 add_value (id
 *   advancedmobs:leapfrog_slam_jump; the leap reads the
 *   live value, so the boost is what sets the arc),
 *   GRAVITY -0.35 add_multiplied_base (65 percent gravity:
 *   the hang time), SAFE_FALL_DISTANCE +100 add_value
 *   (the frog never hurts itself on its own landing), and
 *   ATTACK_KNOCKBACK +2.0 add_value (the design's
 *   knockback line; the splashdown shove below is
 *   code-applied so it lands even on targets the frog
 *   never swings at, and this modifier is what the
 *   frog's own follow-up bites would ride).
 * - The coil (ticks 1 to 8): the frog is planted by
 *   navigation stops (never NoAi), with 1 POOF mote at
 *   its feet every 4 ticks.
 * - The leap (tick 8): one code impulse toward the
 *   target's live position: horizontal speed is the
 *   distance times 0.075, clamped to [0.5, 1.05] blocks
 *   per tick, and vertical speed is the frog's live
 *   JUMP_STRENGTH value times 1.0 (1.02 with the coil
 *   boost on a base frog: the enormous arc, stretched
 *   further by the reduced gravity). FROG_LONG_JUMP
 *   plays at volume 1.2, pitch 0.9. If the target is gone
 *   at tick 8 the leap aims along the frog's facing at a
 *   nominal 6 blocks and simply lands where it lands.
 * - The splashdown (the first tick at or after 3 ticks
 *   past the leap where the frog is back on the ground):
 *   one bounded query at radius 3.5 around the landing
 *   point: every enemy takes damage equal to the frog's
 *   live ATTACK_DAMAGE times 0.7 (7.0 on a base frog)
 *   through a mob attack source credited to the frog,
 *   and is shoved directly away from the frog at 1.2
 *   horizontal and 0.5 up, with hurtMarked set. The
 *   burst is 16 POOF plus 12 BLOCK_CRUMBLE of the real
 *   ground state (the design's two landing particles),
 *   with GENERIC_EXPLODE at volume 0.8, pitch 0.9 as the
 *   belly-flop thump.
 * - Duration: the activation ends 10 ticks after the
 *   splashdown, with a hard cap of 100 ticks (a leap that
 *   never lands, down a cliff or into deep water, still
 *   ends).
 * - end(): all four modifiers are removed. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a coiled frog, a long floating arc
 * across the arena, and a belly-first splashdown that
 * throws dust, crumbled ground, and bodies in every
 * direction.
 *
 * PERFORMANCE: the coil is one navigation stop and at
 * most 1 particle every 4 ticks; the leap is one velocity
 * write; the airborne phase is one onGround check per
 * tick; the splashdown is one bounded query once.
 * Particle budget per activation: 2 coil motes plus 16
 * poof plus 12 crumble, at most 30 particles.
 *
 * TERRAIN: this ability changes no blocks; BLOCK_CRUMBLE
 * is a particle carrying the ground state, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "phase" (0 coiling, 1 airborne, 2
 * landed), "leap_tick" and "land_tick" (activation ticks
 * of the leap and the splashdown).
 */
public final class LeapfrogSlamAbility implements MobAbility {

    private static final int COIL_TICKS = 8;
    private static final int HARD_CAP_TICKS = 100;
    private static final int LAND_LINGER_TICKS = 10;
    private static final double SLAM_RADIUS = 3.5;
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "leapfrog_slam_jump");
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "leapfrog_slam_gravity");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "leapfrog_slam_fall");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "leapfrog_slam_knockback");

    @Override
    public String id() {
        return "leapfrog_slam";
    }

    @Override
    public String displayName() {
        return "Leapfrog Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        FrogAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.6, AttributeModifier.Operation.ADD_VALUE));
        FrogAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(GRAVITY_ID, -0.35, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        FrogAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        FrogAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        caster.getNavigation().stop();
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        int phase = ctx.state().getIntOr("phase", 0);
        ServerLevel level = ctx.serverLevel();
        if (phase == 0) {
            caster.getNavigation().stop();
            if (level != null && ticks % 4 == 0) {
                level.sendParticles(ParticleTypes.POOF,
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    1, 0.2, 0.05, 0.2, 0.02);
            }
            if (ticks >= COIL_TICKS) {
                leap(ctx, ticks);
            }
            return;
        }
        if (phase == 1 && ticks - ctx.state().getIntOr("leap_tick", 0) >= 3 && caster.onGround()) {
            splashdown(ctx, ticks);
        }
    }

    /** The leap: one impulse on an enormous arc toward the target's live position. */
    private void leap(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        double dirX;
        double dirZ;
        double distance;
        if (target != null) {
            dirX = target.getX() - caster.getX();
            dirZ = target.getZ() - caster.getZ();
            distance = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (distance < 1.0e-4) {
                Vec3 look = caster.getLookAngle();
                dirX = look.x;
                dirZ = look.z;
                distance = 1.0;
            }
        } else {
            Vec3 look = caster.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
            distance = 6.0;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            dirX = 1.0;
            dirZ = 0.0;
            length = 1.0;
        }
        double horizontal = Math.max(0.5, Math.min(1.05, distance * 0.075));
        double vertical = caster.getAttributeValue(Attributes.JUMP_STRENGTH) * 1.0;
        caster.setDeltaMovement(dirX / length * horizontal, vertical, dirZ / length * horizontal);
        caster.hurtMarked = true;
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FROG_LONG_JUMP, SoundSource.HOSTILE, 1.2f, 0.9f);
        }
        ctx.state().putInt("leap_tick", ticks);
        ctx.state().putInt("phase", 1);
    }

    /** The splashdown: area damage and shove at the landing point, then the linger. */
    private void splashdown(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.7;
            List<LivingEntity> enemies = FrogAbilitySupport.enemiesAroundSelf(ctx, SLAM_RADIUS, 2.0, 2.0);
            for (LivingEntity enemy : enemies) {
                enemy.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
                double dx = enemy.getX() - caster.getX();
                double dz = enemy.getZ() - caster.getZ();
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length < 1.0e-4) {
                    dx = 1.0;
                    dz = 0.0;
                    length = 1.0;
                }
                Vec3 motion = enemy.getDeltaMovement();
                enemy.setDeltaMovement(motion.x + dx / length * 1.2, 0.5, motion.z + dz / length * 1.2);
                enemy.hurtMarked = true;
            }
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                16, 0.9, 0.25, 0.9, 0.08);
            level.sendParticles(FrogAbilitySupport.groundCrumble(level, caster.getX(), caster.getY(), caster.getZ()),
                caster.getX(), caster.getY() + 0.2, caster.getZ(),
                12, 0.9, 0.2, 0.9, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.8f, 0.9f);
        }
        ctx.state().putInt("land_tick", ticks);
        ctx.state().putInt("phase", 2);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("phase", 0) == 2
            && ticks - ctx.state().getIntOr("land_tick", 0) >= LAND_LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        FrogAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        FrogAbilitySupport.removeModifier(caster, Attributes.GRAVITY, GRAVITY_ID);
        FrogAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        FrogAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
