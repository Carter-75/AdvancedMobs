package mod.advancedmobs.ability.sulfurcube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.GeyserBaseParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Super Bounce Slam (Sulfur Cube ability 4 of 5, attack and
 * mobility).
 *
 * WHAT IT DOES: the cube compresses, flashes with GEYSER_POOF,
 * and launches itself skyward at its target. It comes down in
 * a huge slam that blasts nearby enemies outward and emits
 * the vanilla bounce game event, then rebounds off the ground
 * two more times at shrinking height, each bounce a smaller
 * shockwave, before it settles back into the fight.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cube's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: at most 200 ticks (10 seconds) from start(); the
 *   ability finishes the moment the third slam lands.
 * - The physics (both transient modifiers added in start(),
 *   removed in end(), ids under advancedmobs:super_bounce_*):
 *     BOUNCINESS         add_value +1.0 (to the 1.0 cap, the
 *                                         design's bounciness
 *                                         at 1: every collision
 *                                         with a block or a
 *                                         collidable entity
 *                                         returns the cube's
 *                                         full velocity for the
 *                                         whole activation, so
 *                                         even unscripted
 *                                         bounces stay live)
 *     SAFE_FALL_DISTANCE add_value +30  (the cube's own slam
 *                                         heights, about 11
 *                                         blocks for the first
 *                                         launch, never hurt
 *                                         the cube itself)
 * - The launch (start()): velocity aimed at the target:
 *   horizontal 0.4 along the cube-to-target direction and
 *   vertical 1.35 (about an 11 block apex), hurtMarked, with 8
 *   GEYSER_POOF (water depth 2) and a SULFUR_CUBE_JUMP at
 *   pitch 0.9.
 * - The slams: a landing reads as the cube onGround with the
 *   previous tick's vertical velocity at or below -0.55, at
 *   least 6 ticks after the last slam. Each slam fires the
 *   vanilla bounce game event (mob.gameEvent(GameEvent.
 *   BOUNCE), the design's line: wardens and sculk hear this
 *   cube coming down) and hits every enemy in its radius once:
 *     slam 1: radius 4.0, 8.0 mob attack damage, knockback
 *             1.1 horizontal and 0.45 up
 *     slam 2: radius 3.0, 6.0 damage, 0.85 and 0.35
 *     slam 3: radius 2.2, 4.0 damage, 0.6 and 0.25
 *   Damage is credited to the cube; knockback is a velocity
 *   write away from the impact point, hurtMarked.
 * - The rebounds: after slams 1 and 2 the cube rebounds in
 *   code at vertical velocity 0.95 and then 0.68 (shrinking
 *   heights, about 5.5 and then 2.9 blocks), with horizontal
 *   velocity refreshed to 0.30 toward the target when it is
 *   still alive, so the slam chain walks across the fight.
 *   After slam 3 there is no rebound and the ability ends.
 *   The BOUNCINESS attribute also bounces the cube in the
 *   vanilla physics step; the scripted rebound velocity is
 *   written after, in the same tick, so the three show slams
 *   keep their scripted heights regardless.
 * - Air trail: 1 GEYSER_POOF every 4 ticks while airborne.
 * - No blocks are placed or broken anywhere in this ability,
 *   so the mobGriefing terrain gate does not apply.
 *
 * WHAT IT LOOKS LIKE: a sulfur cube rocketing up in a burst of
 * steam puffs, hanging at the top of its arc, and crashing
 * down with a rubbery SULFUR_CUBE_BOUNCE boom (pitch 1.1, then
 * 0.95, then 0.8 as the bounces shrink), goo and steam
 * spraying at every impact, enemies hurled outward in a ring,
 * twice more at falling height, then done.
 *
 * PERFORMANCE: tick() is a velocity read, a landing check, and
 * a 4-tick particle pulse; the only entity queries are the
 * three one-shot slam pulses on tight boxes. Modifiers are
 * resolved at start() and removed at end(); the particle
 * option is a pre-built static final. Particle budget per
 * activation, hard caps by construction: launch 8, air trail
 * at most 50, slams (10 + 6) + (7 + 4) + (5 + 3) = 35, total
 * at most 93 particles.
 *
 * CLEANUP: end() removes both modifiers by id. Nothing else
 * was created; wherever the third slam leaves the cube is
 * where it resumes the fight.
 *
 * State tag keys: "bounces" (slams completed), "prev_vy"
 * (last tick's vertical velocity), "last_slam" (tick of the
 * last slam).
 */
public final class SuperBounceSlamAbility implements MobAbility {

    static final int MAX_TICKS = 200;
    private static final int SLAM_COUNT = 3;
    private static final double[] SLAM_RADIUS = {4.0, 3.0, 2.2};
    private static final float[] SLAM_DAMAGE = {8.0f, 6.0f, 4.0f};
    private static final double[] SLAM_KNOCKBACK = {1.1, 0.85, 0.6};
    private static final double[] SLAM_UP = {0.45, 0.35, 0.25};
    private static final double[] REBOUND_UP = {0.95, 0.68};
    private static final int[] SLAM_POOF = {10, 7, 5};
    private static final int[] SLAM_GOO = {6, 4, 3};
    private static final float[] SLAM_PITCH = {1.1f, 0.95f, 0.8f};
    private static final Identifier BOUNCINESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "super_bounce_slam_bounciness");
    private static final Identifier SAFE_FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "super_bounce_slam_safe_fall");
    private static final GeyserBaseParticleOptions POOF =
        new GeyserBaseParticleOptions(ParticleTypes.GEYSER_POOF, 2, 0.6f);

    @Override
    public String id() {
        return "super_bounce_slam";
    }

    @Override
    public String displayName() {
        return "Super Bounce Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SulfurCubeAbilitySupport.addModifier(mob, Attributes.BOUNCINESS,
            new AttributeModifier(BOUNCINESS_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        SulfurCubeAbilitySupport.addModifier(mob, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(SAFE_FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));

        LivingEntity target = ctx.target();
        double dx = target != null ? target.getX() - mob.getX() : mob.getLookAngle().x;
        double dz = target != null ? target.getZ() - mob.getZ() : mob.getLookAngle().z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            dx = 0.0;
            dz = 0.0;
        } else {
            dx = dx / length * 0.4;
            dz = dz / length * 0.4;
        }
        mob.setDeltaMovement(dx, 1.35, dz);
        mob.hurtMarked = true;

        ctx.state().putInt("bounces", 0);
        ctx.state().putDouble("prev_vy", 1.35);
        ctx.state().putInt("last_slam", -20);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(POOF, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                8, 0.4, 0.15, 0.4, 0.1);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_JUMP,
                SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        int bounces = ctx.state().getIntOr("bounces", 0);
        double prevVy = ctx.state().getDoubleOr("prev_vy", 0.0);

        if (!mob.onGround() && ticks % 4 == 0) {
            level.sendParticles(POOF, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                1, 0.3, 0.1, 0.3, 0.03);
        }

        if (mob.onGround() && prevVy <= -0.55 && bounces < SLAM_COUNT
            && ticks - ctx.state().getIntOr("last_slam", -20) >= 6) {
            slam(ctx, level, bounces);
            bounces = ctx.state().getIntOr("bounces", 0);
        }

        ctx.state().putDouble("prev_vy", mob.getDeltaMovement().y);
    }

    /** One slam: bounce game event, ring damage and knockback, particles, and the scripted rebound. */
    private void slam(AbilityContext ctx, ServerLevel level, int slamIndex) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        mob.gameEvent(GameEvent.BOUNCE);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_BOUNCE,
            SoundSource.HOSTILE, 1.2f, SLAM_PITCH[slamIndex]);
        level.sendParticles(POOF, mob.getX(), mob.getY() + 0.2, mob.getZ(),
            SLAM_POOF[slamIndex], 0.5, 0.15, 0.5, 0.12);
        level.sendParticles(ParticleTypes.SULFUR_CUBE_GOO, mob.getX(), mob.getY() + 0.2, mob.getZ(),
            SLAM_GOO[slamIndex], 0.6, 0.2, 0.6, 0.15);

        for (LivingEntity enemy : SulfurCubeAbilitySupport.enemiesInBox(ctx,
                mob.getX(), mob.getY(), mob.getZ(), SLAM_RADIUS[slamIndex], 1.5, 2.5)) {
            enemy.hurtServer(level, level.damageSources().mobAttack(mob), SLAM_DAMAGE[slamIndex]);
            double awayX = enemy.getX() - mob.getX();
            double awayZ = enemy.getZ() - mob.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            double pushX = length > 0.001 ? awayX / length * SLAM_KNOCKBACK[slamIndex] : 0.0;
            double pushZ = length > 0.001 ? awayZ / length * SLAM_KNOCKBACK[slamIndex] : 0.0;
            Vec3 current = enemy.getDeltaMovement();
            enemy.setDeltaMovement(pushX, Math.max(current.y, SLAM_UP[slamIndex]), pushZ);
            enemy.hurtMarked = true;
        }

        ctx.state().putInt("bounces", slamIndex + 1);
        ctx.state().putInt("last_slam", ticks);

        if (slamIndex < SLAM_COUNT - 1) {
            // The scripted rebound: shrinking height, walking
            // toward the target while it lives.
            LivingEntity target = ctx.target();
            Vec3 current = mob.getDeltaMovement();
            double dx = current.x;
            double dz = current.z;
            if (target != null) {
                double towardX = target.getX() - mob.getX();
                double towardZ = target.getZ() - mob.getZ();
                double length = Math.sqrt(towardX * towardX + towardZ * towardZ);
                if (length > 0.001) {
                    dx = towardX / length * 0.30;
                    dz = towardZ / length * 0.30;
                }
            }
            mob.setDeltaMovement(dx, REBOUND_UP[slamIndex], dz);
            mob.hurtMarked = true;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_TICKS || ctx.state().getIntOr("bounces", 0) >= SLAM_COUNT;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.BOUNCINESS, BOUNCINESS_ID);
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_ID);
    }
}
