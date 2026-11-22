package mod.advancedmobs.ability.squid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Tentacle Slam (Squid ability 2 of 5, attack).
 *
 * WHAT IT DOES: the squid rises over its target,
 * rears up with all its arms spread, and brings
 * every tentacle down at once in a cracking ring of
 * bubbles. The slam lands heavy damage on everything
 * close around the target and drags the survivors
 * inward toward the squid, which then grapples its
 * original target: a rapid series of small hits,
 * each one tugging the victim closer, for as long as
 * the grapple holds.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 8
 *   blocks (distance squared 64), plus the squid in
 *   water.
 * - The kit (start()): the design's two attribute
 *   lines are applied as transient modifiers for the
 *   whole activation and removed in end():
 *   ATTACK_DAMAGE +4.0 add_value (id
 *   advancedmobs:tentacle_slam_damage) and
 *   ATTACK_KNOCKBACK +2.0 add_value (id
 *   advancedmobs:tentacle_slam_knockback). Both are
 *   documented here as INERT on the squid: it has no
 *   ATTACK_DAMAGE or ATTACK_KNOCKBACK attribute
 *   instance anywhere in its chain (verified against
 *   the jar; the package's null-tolerant helper
 *   simply skips them), and the slam, drag, and
 *   grapple below are all flat code values, the
 *   Grand Hex Form precedent. The target's UUID is
 *   recorded for the grapple.
 * - The rear-up (ticks 1 to 10): the caster is
 *   steered at 0.5 blocks per tick toward a hover
 *   point 2.0 blocks above the target's live
 *   position, arms spread over the victim. 2 BUBBLE
 *   per tick trail the rise.
 * - The slam (tick 12): one bounded query, radius
 *   3.0 around the target's live position (the
 *   caster's own position when the target is gone),
 *   vertical reach 2.5 both ways: every enemy (the
 *   package enemy rule) takes 7.0 flat damage as a
 *   mob attack credited to the caster, then is
 *   dragged toward the squid: velocity set toward
 *   the caster at 0.8 blocks per tick horizontally
 *   and 0.1 up, hurtMarked set (the design's Motion
 *   pull). The crack: 12 SWEEP_ATTACK, 8 CRIT, and
 *   14 BUBBLE at the slam point, SQUID_SQUIRT at
 *   volume 1.5, pitch 0.7.
 * - The grapple (ticks 13 to 72): every 8 ticks, if
 *   the recorded target is alive and within 4.0
 *   blocks, it takes 1.5 flat damage credited to the
 *   caster, a tug toward the caster at 0.25 blocks
 *   per tick, and 4 CRIT at its position: the rapid
 *   small hits of the design. Between tugs the
 *   caster steers toward the target at 0.4 blocks
 *   per tick whenever it is more than 2.5 blocks
 *   away, holding the grapple's range. 2 BUBBLE
 *   every 4 ticks drift off the arms.
 * - Duration: 72 ticks, hard finish.
 *
 * WHAT IT LOOKS LIKE: a squid looming up over its
 * prey, a ring of bubbles and sweep flashes cracking
 * down, the prey yanked inward, then a close,
 * flashing scuffle as the arms keep striking.
 *
 * PERFORMANCE: the rear-up and grapple steering are
 * one velocity write per tick; the slam is one
 * bounded query once; the grapple resolves one
 * stored UUID per 8-tick tug. No per-tick queries.
 * Particle budget per activation: 20 rear-up trail,
 * 34 slam, at most 7 tugs times 4 = 28 crit, 30
 * grapple drift, total at most 112.
 *
 * TERRAIN: this ability changes no blocks; the slam
 * is particles, damage, and velocity, so the
 * mobGriefing gate does not apply.
 *
 * CLEANUP: end() removes both kit modifiers by id.
 * The grapple's damage and tugs are instant writes
 * and leave nothing behind.
 *
 * State tag keys: "tid" (the grappled target's
 * UUID).
 */
public final class TentacleSlamAbility implements MobAbility {

    private static final int DURATION_TICKS = 72;
    private static final int SLAM_TICK = 12;
    private static final int TUG_INTERVAL = 8;
    private static final float SLAM_DAMAGE = 7.0f;
    private static final float TUG_DAMAGE = 1.5f;
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "tentacle_slam_damage");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "tentacle_slam_knockback");

    @Override
    public String id() {
        return "tentacle_slam";
    }

    @Override
    public String displayName() {
        return "Tentacle Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The design's attribute lines; inert on the
        // squid (see the class comment), applied and
        // removed exactly all the same.
        SquidAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        SquidAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));

        LivingEntity target = ctx.target();
        if (target != null) {
            ctx.state().store("tid", UUIDUtil.CODEC, target.getUUID());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();

        if (ticks < SLAM_TICK) {
            // The rear-up: rise and hover over the target.
            if (target != null) {
                SquidAbilitySupport.steerToward(caster,
                    target.getX(), target.getY() + 2.0, target.getZ(), 0.5);
            }
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.3, 0.3, 0.3, 0.03);
            return;
        }

        if (ticks == SLAM_TICK) {
            slam(ctx, level, target);
            return;
        }

        // The grapple: hold range, tug and strike on the interval.
        LivingEntity grappled = grappleTarget(ctx, level);
        if (grappled == null) {
            return;
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.3, 0.3, 0.3, 0.03);
        }
        if (caster.distanceToSqr(grappled) > 6.25) {
            SquidAbilitySupport.steerToward(caster,
                grappled.getX(), grappled.getY(), grappled.getZ(), 0.4);
        }
        if ((ticks - SLAM_TICK) % TUG_INTERVAL == 0
            && caster.distanceToSqr(grappled) <= 16.0) {
            grappled.hurtServer(level, level.damageSources().mobAttack(caster), TUG_DAMAGE);
            pullToward(grappled, caster, 0.25);
            level.sendParticles(ParticleTypes.CRIT,
                grappled.getX(), grappled.getY() + 0.4, grappled.getZ(),
                4, 0.3, 0.3, 0.3, 0.05);
        }
    }

    /** The slam: one bounded query at the target's position, heavy damage and an inward drag. */
    private void slam(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        Mob caster = ctx.mob();
        double slamX = target != null ? target.getX() : caster.getX();
        double slamY = target != null ? target.getY() : caster.getY();
        double slamZ = target != null ? target.getZ() : caster.getZ();

        level.playSound(null, slamX, slamY, slamZ,
            SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 1.5f, 0.7f);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, slamX, slamY + 0.4, slamZ,
            12, 1.2, 0.6, 1.2, 0.05);
        level.sendParticles(ParticleTypes.CRIT, slamX, slamY + 0.4, slamZ,
            8, 1.0, 0.6, 1.0, 0.05);
        level.sendParticles(ParticleTypes.BUBBLE, slamX, slamY + 0.3, slamZ,
            14, 1.4, 0.7, 1.4, 0.08);

        List<LivingEntity> hit = SquidAbilitySupport.enemiesInBox(ctx, slamX, slamY, slamZ, 3.0, 2.5, 2.5);
        for (LivingEntity victim : hit) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), SLAM_DAMAGE);
            pullToward(victim, caster, 0.8);
        }
    }

    /** Sets the victim's velocity toward the caster at the given horizontal speed, slightly up. */
    private void pullToward(LivingEntity victim, LivingEntity caster, double speed) {
        double dx = caster.getX() - victim.getX();
        double dz = caster.getZ() - victim.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            return;
        }
        victim.setDeltaMovement(dx / length * speed, 0.1, dz / length * speed);
        victim.hurtMarked = true;
    }

    /** The recorded grapple target, resolved live, or null when gone. */
    private LivingEntity grappleTarget(AbilityContext ctx, ServerLevel level) {
        Entity found = SquidAbilitySupport.resolveStored(level, ctx.state(), "tid");
        return found instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SquidAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        SquidAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
