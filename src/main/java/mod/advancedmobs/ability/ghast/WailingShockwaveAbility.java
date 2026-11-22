package mod.advancedmobs.ability.ghast;

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
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Wailing Shockwave (Ghast ability 1 of 5, attack aura).
 *
 * WHAT IT DOES: the ghast hangs in the air and inhales for a
 * second and a half, its body swelling and tears leaking from
 * its eyes, then throws its face wide and screams. The scream
 * rolls outward as a visible expanding ring that shoves every
 * enemy away hard and bats incoming projectiles back the way
 * they came. After a breath of silence a second, smaller ring
 * follows, timed to catch enemies at the ledges the first ring
 * pushed them toward and knock them off.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 18 blocks
 *   (distance squared 324).
 * - The inhale (ticks 1 to 29): the ghast's navigation is
 *   stopped and its velocity damped to a quarter each tick
 *   (it hangs in place; no NoAi, per the standing contract).
 *   A SCALE modifier of add_multiplied_base +0.2 (modifier id
 *   advancedmobs:wailing_shockwave_scale, removed in end())
 *   swells its body to 1.2 times birth size. DRIPPING_WATER
 *   tears fall from below its eyes every 2 ticks (2 particles
 *   a pulse, at the front of its body, a quarter block below
 *   eye height).
 * - The face: at tick 30 the ghast is put into its vanilla
 *   charging state (Ghast.setCharging(true), the open-mouth
 *   scream face vanilla itself uses when a ghast fires) and
 *   it stays there until end() clears it. The design's
 *   "custom render state for the scream pose" is this vanilla
 *   state: 26.2 exposes no other server-side face control.
 * - The first ring (tick 30): a GHAST_SCREAM (volume 2.0,
 *   pitch 0.9) rolls out. One bounded enemy query in radius
 *   9.0 around the ghast (4 below to 4 above): every enemy
 *   takes 2.0 damage credited to the ghast (mobAttack) and is
 *   shoved directly away from the ghast at 1.6 blocks per tick
 *   horizontal and 0.35 up (velocity set outright, hurtMarked
 *   set so player clients apply it). Projectiles are handled
 *   by one bounded Projectile query in the same box: a
 *   projectile whose owner is an enemy of the ghast, or which
 *   has no owner, is deflected: its velocity is reversed and
 *   scaled by 1.2 and its owner becomes the ghast, so a
 *   deflected shot can hurt its own side. The ghast's own and
 *   its allies' projectiles are untouched.
 * - The first ring visual (ticks 30 to 39): each tick a ring
 *   of 12 DUST_PLUME and 6 CLOUD particles at radius
 *   (ticks - 29), centered on the ghast's mid-height, so the
 *   ring visibly expands from 1 to 10 blocks over 10 ticks.
 * - The silence and second ring (tick 55): after 25 quiet
 *   ticks a second GHAST_SCREAM (volume 1.2, pitch 1.3) and a
 *   second bounded enemy query in radius 7.0: every enemy
 *   takes 1.0 mobAttack damage and is shoved away at 1.1
 *   horizontal and 0.5 up, the higher arc being what drops
 *   enemies off the ledges the first ring pushed them to. Its
 *   visual runs ticks 55 to 61: a ring of 12 DUST_PLUME and
 *   6 CLOUD expanding from 1 to 7 blocks. No projectile
 *   deflection on the second ring.
 * - Duration: hard finish at 70 ticks. Tears keep falling
 *   every 2 ticks for the whole cast.
 *
 * WHAT IT LOOKS LIKE: a ghast swelling as water drips from
 * its eyes, face locked open in the vanilla scream pose, two
 * pale rings of dust plume and cloud rolling outward through
 * the air with a breath of quiet between them, enemies and
 * their own shots flying backward.
 *
 * PERFORMANCE: the inhale is one navigation stop and one
 * velocity write per tick plus a 2-particle tear pulse every
 * 2 ticks. Each ring is one bounded enemy query (and, for the
 * first, one bounded projectile query) on a single tick; the
 * ring visuals are 18 particles a tick for 10 and 7 ticks.
 * Particle budget per activation: tears at most 35 pulses
 * times 2 = 70, first ring 10 times 18 = 180, second ring
 * 7 times 18 = 126, total at most 376 particles.
 *
 * CLEANUP: end() removes the SCALE modifier and clears the
 * charging state, so the ghast always returns to its normal
 * size and face even if it dies mid-scream (the framework
 * termination path runs this same end()).
 *
 * TERRAIN: this ability changes no blocks; the gate does not
 * apply.
 *
 * State tag keys: none beyond the framework's. Every phase is
 * a pure function of ctx.ticksActive().
 */
public final class WailingShockwaveAbility implements MobAbility {

    private static final int SCREAM_TICK = 30;
    private static final int SECOND_SCREAM_TICK = 55;
    private static final int HARD_FINISH_TICKS = 70;
    private static final double FIRST_RADIUS = 9.0;
    private static final double SECOND_RADIUS = 7.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "wailing_shockwave_scale");

    @Override
    public String id() {
        return "wailing_shockwave";
    }

    @Override
    public String displayName() {
        return "Wailing Shockwave";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GhastAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.2, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_WARN,
                SoundSource.HOSTILE, 1.2f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();

        if (ticks < SCREAM_TICK) {
            // The inhale: hang in place (see the class comment).
            mob.getNavigation().stop();
            Vec3 velocity = mob.getDeltaMovement();
            mob.setDeltaMovement(velocity.x * 0.25, velocity.y * 0.25, velocity.z * 0.25);
        }

        if (ticks % 2 == 0) {
            // The tears: DRIPPING_WATER below the eyes, at the
            // front of the body (the ghast's look direction).
            Vec3 look = mob.getLookAngle();
            double eyeX = mob.getX() + look.x * mob.getBbWidth() * 0.5;
            double eyeZ = mob.getZ() + look.z * mob.getBbWidth() * 0.5;
            level.sendParticles(ParticleTypes.DRIPPING_WATER,
                eyeX, mob.getY() + mob.getBbHeight() * 0.55, eyeZ,
                2, 0.5, 0.1, 0.5, 0.0);
        }

        if (ticks == SCREAM_TICK) {
            if (mob instanceof Ghast ghast) {
                ghast.setCharging(true);
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_SCREAM,
                SoundSource.HOSTILE, 2.0f, 0.9f);
            shoveRing(ctx, level, FIRST_RADIUS, 2.0f, 1.6, 0.35);
            deflectProjectiles(ctx, level);
        }
        if (ticks > SCREAM_TICK && ticks <= SCREAM_TICK + 9) {
            drawRing(ctx, level, ticks - SCREAM_TICK + 1);
        }

        if (ticks == SECOND_SCREAM_TICK) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_SCREAM,
                SoundSource.HOSTILE, 1.2f, 1.3f);
            shoveRing(ctx, level, SECOND_RADIUS, 1.0f, 1.1, 0.5);
        }
        if (ticks > SECOND_SCREAM_TICK && ticks <= SECOND_SCREAM_TICK + 6) {
            drawRing(ctx, level, ticks - SECOND_SCREAM_TICK + 1);
        }
    }

    /** One scream ring's shove: damage and knockback for every enemy in radius. */
    private static void shoveRing(AbilityContext ctx, ServerLevel level, double radius,
                                  float damage, double push, double lift) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = GhastAbilitySupport.enemiesInBox(ctx,
            mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(), radius, 4.0, 4.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), damage);
            Vec3 away = new Vec3(victim.getX() - mob.getX(), 0.0, victim.getZ() - mob.getZ());
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(1.0, 0.0, 0.0);
            }
            away = away.normalize();
            victim.setDeltaMovement(away.x * push, lift, away.z * push);
            victim.hurtMarked = true;
        }
    }

    /** The first ring's projectile deflection (see the class comment). */
    private static void deflectProjectiles(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        double cy = mob.getY() + mob.getBbHeight() * 0.5;
        AABB box = new AABB(mob.getX() - FIRST_RADIUS, cy - 4.0, mob.getZ() - FIRST_RADIUS,
                            mob.getX() + FIRST_RADIUS, cy + 4.0, mob.getZ() + FIRST_RADIUS);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box);
        for (Projectile projectile : projectiles) {
            if (projectile.getOwner() == mob) {
                continue;
            }
            if (projectile.getOwner() instanceof LivingEntity owner
                && (owner == mob || ctx.data().isAlly(owner.getUUID()))) {
                continue;
            }
            Vec3 velocity = projectile.getDeltaMovement();
            projectile.setDeltaMovement(velocity.x * -1.2, velocity.y * -1.2, velocity.z * -1.2);
            projectile.setOwner(mob);
        }
    }

    /** Draws one expanding ring frame of DUST_PLUME and CLOUD at the given radius. */
    private static void drawRing(AbilityContext ctx, ServerLevel level, int radius) {
        Mob mob = ctx.mob();
        double y = mob.getY() + mob.getBbHeight() * 0.5;
        for (int i = 0; i < 12; i++) {
            double angle = i * (Math.PI / 6.0) + ctx.ticksActive() * 0.03;
            level.sendParticles(ParticleTypes.DUST_PLUME,
                mob.getX() + Math.cos(angle) * radius, y, mob.getZ() + Math.sin(angle) * radius,
                1, 0.0, 0.0, 0.0, 0.0);
        }
        for (int i = 0; i < 6; i++) {
            double angle = i * (Math.PI / 3.0) + ctx.ticksActive() * 0.03;
            level.sendParticles(ParticleTypes.CLOUD,
                mob.getX() + Math.cos(angle) * radius, y, mob.getZ() + Math.sin(angle) * radius,
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GhastAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        if (mob instanceof Ghast ghast) {
            ghast.setCharging(false);
        }
    }
}
