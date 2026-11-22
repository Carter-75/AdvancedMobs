package mod.advancedmobs.ability.vindicator;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Executioner's Leap (Vindicator ability 5 of 5, mobility
 * and attack).
 *
 * WHAT IT DOES: the vindicator springs into the air with axe
 * high, hangs at the peak for one terrible instant while its
 * shadow pools on the ground below, and crashes down in a
 * ring of dust pillars and splintering ground that damages
 * and staggers everything around the landing point. It falls
 * any distance without harm, so it happily leaps off towers
 * and cliffsides onto targets below. The landing is loud,
 * telegraphed, and worth dodging.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vindicator's current target between 5 and 18 blocks
 *   away (distance squared 25 to 324) and the vindicator on
 *   the ground.
 * - The aim: start() records the target's position at that
 *   moment (feet x, y, z in the state tag). The aim does NOT
 *   track: a target that walks out from under the shadow
 *   during the flight dodges the slam. That is the
 *   counterplay, and the shadow below is the warning.
 * - Leap attributes, applied in start() and removed in end()
 *   (the design's Uses list): JUMP_STRENGTH add_value +0.8
 *   (any vanilla jump the vindicator makes during the
 *   activation is a monster jump too; the leap arc itself is
 *   code-set so the landing is exact), SAFE_FALL_DISTANCE
 *   add_value +100 (no fall damage from any height for the
 *   whole activation: the cliffsides clause).
 * - Phase 0, the rise: launch vertical velocity 1.18 blocks
 *   per tick in start(); every flight tick the horizontal
 *   velocity is re-asserted toward the aim at the remaining
 *   horizontal distance times 0.18, clamped to [0.30, 1.30]
 *   blocks per tick, zeroed within 1.0 block so it drops onto
 *   the spot. Vertical velocity is left to gravity.
 * - Phase 1, the hang: when the vindicator is airborne and
 *   its vertical velocity first falls to 0.02 or below (the
 *   apex), it hangs for exactly 10 ticks: velocity set each
 *   tick to a slow drift toward the aim (0.2 blocks per tick
 *   horizontal) and 0.03 up, hurtMarked set, so it hovers at
 *   the peak, axe high. A VINDICATOR_CELEBRATE roar (volume
 *   1.0, pitch 1.1) marks the moment.
 * - Phase 2, the dive: velocity is set once toward the aim
 *   point (direction to feet plus 0.5, normalized, times
 *   1.55) and then left alone; gravity and momentum do the
 *   rest.
 * - The shadow: on every even tick of phases 0 to 2, 6 dark
 *   DUST motes (color 0x1F1F1F, scale 1.8) are drawn on a
 *   radius 1.6 circle at the aim point, 0.15 above the
 *   recorded ground level: the pooling shadow that telegraphs
 *   the landing.
 * - The landing (the first tick on the ground after having
 *   been airborne, in any flight phase): one bounded query,
 *   enemies (package rules) within radius 3.5 of the actual
 *   landing point (2 below to 3 above) take 7.0 points of
 *   typed mob-attack damage credited to the vindicator, are
 *   hurled radially outward at 1.0 horizontal and 0.45 up
 *   (dead center pushes along the vindicator's look
 *   direction), and gain SLOWNESS I for 40 ticks: the
 *   stagger. The slam center is the vindicator's real landing
 *   position, wherever the arc came down.
 * - Aftermath: 20 quiet ticks after the slam, then the
 *   activation ends. Failure exit: no landing within 110
 *   ticks (knocked into water, a web, or the void) ends the
 *   activation with no slam; the attributes still come off.
 *
 * WHAT IT LOOKS LIKE: a huge rising arc, a roaring silhouette
 * hanging motionless at the top of it over a dark pooling
 * circle on the ground, then a meteor drop: 18 DUST_PILLAR
 * columns of the ground block bursting up around the crater
 * with 10 BLOCK_CRUMBLE splinters, a GENERIC_EXPLODE thud at
 * volume 0.5 and pitch 1.1, and everything nearby staggering
 * away slowed.
 *
 * TERRAIN: this ability places and breaks no blocks in
 * either mobGriefing mode; the splintering ground is
 * particle theater, so the terrain gate has nothing to block
 * here.
 *
 * PERFORMANCE: flight ticks are one distance computation and
 * one velocity write; the shadow is one 6-particle send every
 * 2 ticks; the slam is one bounded entity query, once. No
 * per-tick allocations beyond the velocity vectors the
 * steering writes anyway. Particle budget per activation:
 * shadow at most 55 draws times 6 = 330 worst case over a
 * maximally long flight (a normal leap is airborne about 30
 * ticks, about 90), slam 28, total at most 358, stated here
 * so a future pass can trim the shadow interval if it must.
 *
 * State tag keys: "aim_x", "aim_y", "aim_z" (the recorded aim
 * point), "phase" (0 rise, 1 hang, 2 dive, 3 landed),
 * "airborne" (1 once the vindicator has left the ground),
 * "hang_left" (hang ticks remaining), "land_tick"
 * (ticksActive when the slam landed).
 */
public final class ExecutionersLeapAbility implements MobAbility {

    private static final int MAX_FLIGHT_TICKS = 110;
    private static final int HANG_TICKS = 10;
    private static final int AFTERMATH_TICKS = 20;
    private static final double SLAM_RADIUS = 3.5;
    private static final float SLAM_DAMAGE = 7.0f;
    private static final double SLAM_KNOCKBACK = 1.0;
    private static final double SLAM_KNOCKBACK_UP = 0.45;
    private static final double LAUNCH_UP = 1.18;
    private static final double DIVE_SPEED = 1.55;
    private static final DustParticleOptions SHADOW_DUST = new DustParticleOptions(0x1F1F1F, 1.8f);
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "executioners_leap_jump");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "executioners_leap_fall");

    @Override
    public String id() {
        return "executioners_leap";
    }

    @Override
    public String displayName() {
        return "Executioner's Leap";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 25.0 && distanceSqr <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        ctx.state().putDouble("aim_x", target != null ? target.getX() : mob.getX());
        ctx.state().putDouble("aim_y", target != null ? target.getY() : mob.getY());
        ctx.state().putDouble("aim_z", target != null ? target.getZ() : mob.getZ());
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("airborne", 0);
        ctx.state().putInt("hang_left", 0);
        ctx.state().putInt("land_tick", -1);

        VindicatorAbilitySupport.addModifier(mob, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.8, AttributeModifier.Operation.ADD_VALUE));
        VindicatorAbilitySupport.addModifier(mob, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));

        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(current.x, LAUNCH_UP, current.z);
        mob.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_AMBIENT,
                SoundSource.HOSTILE, 1.1f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int phase = ctx.state().getIntOr("phase", 0);
        if (phase == 3) {
            return; // Landed: the aftermath is quiet by design.
        }
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

        if (ctx.ticksActive() % 2 == 0) {
            drawShadow(ctx, level);
        }

        switch (phase) {
            case 0 -> tickRise(ctx);
            case 1 -> tickHang(ctx, level);
            case 2 -> {
                // The dive is ballistic: the velocity was set
                // once at the hang's end and is left alone.
            }
            default -> {
                // Unknown phase from a future version: coast.
            }
        }
    }

    /** Phase 0: steer toward the aim; switch to the hang at the apex. */
    private void tickRise(AbilityContext ctx) {
        Mob mob = ctx.mob();
        Vec3 current = mob.getDeltaMovement();
        if (ctx.state().getIntOr("airborne", 0) == 1 && current.y <= 0.02) {
            ctx.state().putInt("phase", 1);
            ctx.state().putInt("hang_left", HANG_TICKS);
            return;
        }
        double dx = ctx.state().getDoubleOr("aim_x", mob.getX()) - mob.getX();
        double dz = ctx.state().getDoubleOr("aim_z", mob.getZ()) - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 1.0) {
            double speed = Math.max(0.30, Math.min(1.30, horizontal * 0.18));
            mob.setDeltaMovement(dx / horizontal * speed, current.y, dz / horizontal * speed);
        } else {
            mob.setDeltaMovement(0.0, current.y, 0.0);
        }
        mob.hurtMarked = true;
    }

    /** Phase 1: hover at the peak, axe high; at the end, dive at the aim. */
    private void tickHang(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        int hangLeft = ctx.state().getIntOr("hang_left", 0);
        if (hangLeft == HANG_TICKS) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_CELEBRATE,
                SoundSource.HOSTILE, 1.0f, 1.1f);
        }
        double dx = ctx.state().getDoubleOr("aim_x", mob.getX()) - mob.getX();
        double dz = ctx.state().getDoubleOr("aim_z", mob.getZ()) - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vx = horizontal > 1.0e-4 ? dx / horizontal * 0.2 : 0.0;
        double vz = horizontal > 1.0e-4 ? dz / horizontal * 0.2 : 0.0;
        mob.setDeltaMovement(vx, 0.03, vz);
        mob.hurtMarked = true;

        hangLeft--;
        ctx.state().putInt("hang_left", hangLeft);
        if (hangLeft <= 0) {
            ctx.state().putInt("phase", 2);
            double aimY = ctx.state().getDoubleOr("aim_y", mob.getY());
            Vec3 toAim = new Vec3(ctx.state().getDoubleOr("aim_x", mob.getX()) - mob.getX(),
                                  aimY + 0.5 - mob.getY(),
                                  ctx.state().getDoubleOr("aim_z", mob.getZ()) - mob.getZ());
            double length = toAim.length();
            if (length > 1.0e-4) {
                mob.setDeltaMovement(toAim.scale(DIVE_SPEED / length));
                mob.hurtMarked = true;
            }
        }
    }

    /** The pooling shadow at the aim point: the telegraph. */
    private void drawShadow(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        double aimX = ctx.state().getDoubleOr("aim_x", mob.getX());
        double aimY = ctx.state().getDoubleOr("aim_y", mob.getY());
        double aimZ = ctx.state().getDoubleOr("aim_z", mob.getZ());
        for (int i = 0; i < 6; i++) {
            double a = i * (Math.PI * 2.0 / 6.0);
            level.sendParticles(SHADOW_DUST, aimX + Math.cos(a) * 1.6, aimY + 0.15,
                aimZ + Math.sin(a) * 1.6, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** The impact: radial damage, hurl, and stagger, debris of the real ground block. */
    private void runSlam(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        double x = mob.getX(), y = mob.getY(), z = mob.getZ();
        ctx.state().putInt("phase", 3);
        ctx.state().putInt("land_tick", ctx.ticksActive());

        List<LivingEntity> victims = VindicatorAbilitySupport.enemiesInBox(ctx, x, y, z, SLAM_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), SLAM_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
            double ox = victim.getX() - x;
            double oz = victim.getZ() - z;
            double horizontal = Math.sqrt(ox * ox + oz * oz);
            if (horizontal < 1.0e-4) {
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

        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
        if (ground.isAir()) {
            ground = Blocks.DIRT.defaultBlockState();
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
            x, y + 0.2, z, 18, 1.8, 0.4, 1.8, 0.12);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
            x, y + 0.3, z, 10, 2.2, 0.4, 2.2, 0.25);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.5f, 1.1f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.state().getIntOr("phase", 0) == 3) {
            int landTick = ctx.state().getIntOr("land_tick", 0);
            return ctx.ticksActive() >= landTick + AFTERMATH_TICKS;
        }
        return ctx.ticksActive() >= MAX_FLIGHT_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        VindicatorAbilitySupport.removeModifier(mob, Attributes.JUMP_STRENGTH, JUMP_ID);
        VindicatorAbilitySupport.removeModifier(mob, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
    }
}
