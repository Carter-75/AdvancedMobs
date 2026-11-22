package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Bastion Leap Slam (Piglin Brute ability 1 of 5, mobility
 * and attack).
 *
 * WHAT IT DOES: the brute coils, roars, and launches itself
 * through the air in a huge arc, golden axe raised, aimed at
 * where its target stood when the leap began. It comes down
 * with a slam that cracks blackstone debris in every direction
 * and hurls nearby enemies outward, and the landing zone stays
 * marked by a slowly turning ring of dust for two seconds
 * after. The leap covers ground no charge can match: against
 * this brute, distance is a lie.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the brute's current target between 6 and 20 blocks away
 *   (distance squared 36 to 400) and the brute on the ground.
 *   Point blank fights never leap; the slam is a gap closer.
 * - The aim: start() records the target's position at that
 *   moment (feet x, y, z in the state tag). The aim does NOT
 *   track: a target that moves during the roughly one second
 *   of flight dodges the slam. That is the counterplay.
 * - The launch (start()): vertical velocity 1.02 blocks per
 *   tick; horizontal velocity is then re-asserted every flight
 *   tick toward the aim point at a speed of the remaining
 *   horizontal distance times 0.18, clamped to [0.30, 1.30]
 *   blocks per tick, and zeroed once the brute is within 1.0
 *   block horizontally so it drops onto the spot. Vertical
 *   velocity is left to gravity after the launch tick (the
 *   Ember Wake Descent pattern: one launch write, steering
 *   writes after). The brute's own AI is never touched and
 *   NoAi is never used.
 * - Leap attributes, applied in start() and removed in end()
 *   (the design's Uses list): JUMP_STRENGTH add_value +0.6
 *   (the launch dial: any vanilla jump the brute makes during
 *   the activation is a monster jump too; the slam arc itself
 *   is code-set so the landing is exact), SAFE_FALL_DISTANCE
 *   add_value +100 (the brute takes no fall damage from the
 *   leap no matter the height), ATTACK_KNOCKBACK add_value
 *   +1.0 (its melee hits hurl harder for the whole activation,
 *   on top of the slam's own code knockback).
 * - The slam (the first tick the brute is on the ground after
 *   having been airborne): one bounded query, enemies
 *   (PiglinBruteAbilitySupport rules) within radius 4.0 of the
 *   landing point (2.0 below to 3.0 above) take 6.0 points of
 *   typed mob-attack damage credited to the brute and are
 *   hurled radially outward: 1.4 horizontal, 0.5 up, applied
 *   as a delta movement set with hurtMarked set so it syncs.
 *   A victim standing dead center is pushed along the brute's
 *   look direction instead. The brute's actual landing
 *   position is recorded as the slam center, wherever the arc
 *   really came down.
 * - The dust ring (after the slam): every 5 ticks for 40
 *   ticks, 10 stone-dust motes (DUST, color 0x555049, scale
 *   1.4) are drawn on the radius 4.0 circle around the landing
 *   point at ground level plus 0.25, the ring's angular offset
 *   advancing 0.35 radians per draw so it visibly turns.
 * - Failure exit: if the brute never lands within 100 ticks
 *   (knocked into water, a web, or the void), the activation
 *   ends with no slam. The attributes still come off in end().
 *
 * WHAT IT LOOKS LIKE: a PIGLIN_BRUTE_ANGRY roar (volume 1.2,
 * pitch 0.9) at the coil. At the slam: 20 BLOCK_CRUMBLE and
 * 12 DUST_PILLAR particles of blackstone burst across the
 * landing zone, a GENERIC_EXPLODE at volume 0.5 and pitch 1.2
 * and a BASALT_BREAK at volume 1.0 land with the impact, and
 * the turning dust ring marks the crater for two seconds.
 *
 * TERRAIN: this ability places and breaks no blocks in either
 * mobGriefing mode; the cracked blackstone is particle
 * theater, so the terrain gate has nothing to block here.
 *
 * PERFORMANCE: flight ticks are one distance computation and
 * one velocity write; the slam is one bounded entity query,
 * once; the ring is one 10-particle send every 5 ticks. No
 * per-tick allocations beyond the velocity vector the steering
 * writes anyway. Particle budget per activation: slam 32,
 * ring at most 8 draws times 10 = 80, total at most 112.
 *
 * State tag keys: "aim_x", "aim_y", "aim_z" (the recorded aim
 * point), "phase" (0 flying, 1 landed), "airborne" (1 once the
 * brute has left the ground), "land_tick" (ticksActive when
 * the slam landed), "land_x", "land_y", "land_z" (the slam
 * center), "ring_angle" (the ring's current angular offset).
 */
public final class BastionLeapSlamAbility implements MobAbility {

    private static final int MAX_FLIGHT_TICKS = 100;
    private static final int RING_TICKS = 40;
    private static final double SLAM_RADIUS = 4.0;
    private static final float SLAM_DAMAGE = 6.0f;
    private static final double SLAM_KNOCKBACK = 1.4;
    private static final double SLAM_KNOCKBACK_UP = 0.5;
    private static final double LAUNCH_UP = 1.02;
    private static final BlockParticleOption BLACKSTONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.BLACKSTONE.defaultBlockState());
    private static final BlockParticleOption BLACKSTONE_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.BLACKSTONE.defaultBlockState());
    private static final DustParticleOptions RING_DUST = new DustParticleOptions(0x555049, 1.4f);
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bastion_leap_jump");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bastion_leap_fall");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bastion_leap_knockback");

    @Override
    public String id() {
        return "bastion_leap_slam";
    }

    @Override
    public String displayName() {
        return "Bastion Leap Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        double aimX = target != null ? target.getX() : mob.getX();
        double aimY = target != null ? target.getY() : mob.getY();
        double aimZ = target != null ? target.getZ() : mob.getZ();
        ctx.state().putDouble("aim_x", aimX);
        ctx.state().putDouble("aim_y", aimY);
        ctx.state().putDouble("aim_z", aimZ);
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("airborne", 0);
        ctx.state().putInt("land_tick", -1);
        ctx.state().putDouble("ring_angle", 0.0);

        // The leap attributes (see the spec comment): monster
        // jumps, no fall damage, harder-hurling axe hits.
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.6, AttributeModifier.Operation.ADD_VALUE));
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        // The launch: straight up at the set power; the flight
        // ticks steer the horizontal from here.
        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(current.x, LAUNCH_UP, current.z);
        mob.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_BRUTE_ANGRY,
                SoundSource.HOSTILE, 1.2f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("phase", 0) == 0) {
            tickFlight(ctx);
        } else {
            tickRing(ctx);
        }
    }

    /** Flight: steer toward the aim, and slam on touchdown. */
    private void tickFlight(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (!mob.onGround()) {
            ctx.state().putInt("airborne", 1);
        }
        if (mob.onGround() && ctx.state().getIntOr("airborne", 0) == 1) {
            runSlam(ctx, level);
            return;
        }
        // Steering: horizontal velocity toward the aim point,
        // proportional to what is left of the flight.
        double dx = ctx.state().getDoubleOr("aim_x", mob.getX()) - mob.getX();
        double dz = ctx.state().getDoubleOr("aim_z", mob.getZ()) - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Vec3 current = mob.getDeltaMovement();
        if (horizontal > 1.0) {
            double speed = Math.max(0.30, Math.min(1.30, horizontal * 0.18));
            mob.setDeltaMovement(dx / horizontal * speed, current.y, dz / horizontal * speed);
        } else {
            mob.setDeltaMovement(0.0, current.y, 0.0);
        }
        mob.hurtMarked = true;
    }

    /** The impact: radial damage and hurl, debris, and the phase change to the dust ring. */
    private void runSlam(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        double x = mob.getX(), y = mob.getY(), z = mob.getZ();
        ctx.state().putInt("phase", 1);
        ctx.state().putInt("land_tick", ctx.ticksActive());
        ctx.state().putDouble("land_x", x);
        ctx.state().putDouble("land_y", y);
        ctx.state().putDouble("land_z", z);

        List<LivingEntity> victims = PiglinBruteAbilitySupport.enemiesInBox(ctx, x, y, z, SLAM_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), SLAM_DAMAGE);
            double ox = victim.getX() - x;
            double oz = victim.getZ() - z;
            double horizontal = Math.sqrt(ox * ox + oz * oz);
            if (horizontal < 1.0e-4) {
                // Dead center: push along the brute's facing instead.
                ox = mob.getLookAngle().x;
                oz = mob.getLookAngle().z;
                horizontal = Math.sqrt(ox * ox + oz * oz);
                if (horizontal < 1.0e-4) {
                    ox = 1.0;
                    horizontal = 1.0;
                }
            }
            victim.setDeltaMovement(ox / horizontal * SLAM_KNOCKBACK, SLAM_KNOCKBACK_UP,
                oz / horizontal * SLAM_KNOCKBACK);
            victim.hurtMarked = true;
        }

        level.sendParticles(BLACKSTONE_CRUMBLE, x, y + 0.3, z, 20, 2.4, 0.4, 2.4, 0.25);
        level.sendParticles(BLACKSTONE_PILLAR, x, y + 0.2, z, 12, 1.6, 0.4, 1.6, 0.10);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.5f, 1.2f);
        level.playSound(null, x, y, z, SoundEvents.BASALT_BREAK, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    /** Aftermath: the turning ring of dust over the landing zone. */
    private void tickRing(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 5 != 0) {
            return; // Cheap early-out: the ring redraws every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double cx = ctx.state().getDoubleOr("land_x", ctx.mob().getX());
        double cy = ctx.state().getDoubleOr("land_y", ctx.mob().getY());
        double cz = ctx.state().getDoubleOr("land_z", ctx.mob().getZ());
        double angle = ctx.state().getDoubleOr("ring_angle", 0.0);
        for (int i = 0; i < 10; i++) {
            double a = angle + i * (Math.PI * 2.0 / 10.0);
            level.sendParticles(RING_DUST, cx + Math.cos(a) * SLAM_RADIUS, cy + 0.25,
                cz + Math.sin(a) * SLAM_RADIUS, 1, 0.0, 0.0, 0.0, 0.0);
        }
        ctx.state().putDouble("ring_angle", angle + 0.35);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.state().getIntOr("phase", 0) == 0) {
            return ctx.ticksActive() >= MAX_FLIGHT_TICKS;
        }
        int landTick = ctx.state().getIntOr("land_tick", 0);
        return ctx.ticksActive() >= landTick + RING_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.JUMP_STRENGTH, JUMP_ID);
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
