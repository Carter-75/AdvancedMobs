package mod.advancedmobs.ability.shulker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Petal Storm Spiral (Shulker ability 1 of 5, attack).
 *
 * WHAT IT DOES: the shulker throws its shell wide and fires a
 * dozen real homing bullets in a rising spiral: each bullet
 * spawns a little farther out and a little higher than the
 * last, corkscrewing around the shulker before its vanilla
 * homing curves it in on the target. Every bullet is the
 * vanilla projectile, so each hit still lands the vanilla 4
 * damage plus LEVITATION: a target caught by the spiral is
 * lifted, turned, and dropped in a shower of END_ROD sparks.
 * The spiral is slow to start and impossible to fully dodge
 * in the open, exactly as designed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the shulker's current target within 24 blocks (distance
 *   squared 576).
 * - Duration: exactly 70 ticks from start(). Shots fire at
 *   ticks 5, 10, 15, ... 60: 12 bullets total. The last 10
 *   ticks are quiet runoff so the volley reads as finished
 *   before the framework cooldown starts.
 * - The spiral (per shot i, 0-based): spawn angle i * 0.9
 *   radians, spawn radius 1.0 + i * 0.18 blocks (1.0 out to
 *   2.98), spawn height 0.5 + i * 0.22 above the shulker's
 *   feet (0.5 up to 2.92). The bullet is created at that
 *   offset position with the shared fireBullet() helper (real
 *   ShulkerBullet, caster-owned, attach-face homing axis).
 * - Targeting: each shot aims at the framework target live at
 *   that tick. If the framework target is gone, the shot
 *   retargets to the nearest enemy within 24 blocks of the
 *   shulker (one bounded query, only on such a tick). If no
 *   enemy exists at all, the shot is skipped; the spiral
 *   still runs to tick 70.
 * - Sound: SHULKER_SHOOT at volume 1.5 with each shot, the
 *   vanilla attack sound.
 * - No terrain changes, no attribute modifiers, no effects
 *   applied by the ability itself: the LEVITATION comes from
 *   the vanilla bullets. end() therefore has nothing to undo.
 *
 * WHAT IT LOOKS LIKE: a widening, rising ring of pale bullets
 * blooming around the shulker like petals (the design's
 * image), each trailing the bullet's own vanilla sparkle,
 * while 3 END_ROD motes drift up off the shulker every 2
 * ticks through the firing phase and each shot pops a
 * 4-mote END_ROD burst at its spawn point.
 *
 * PERFORMANCE: tick() returns immediately except on shot
 * ticks (every 5th) and sparkle ticks (every 2nd). The only
 * entity query is the fallback retarget, at most 12 bounded
 * queries per activation and only when the target is dead.
 * No per-tick allocations beyond the bullet on shot ticks.
 * Particle budget per activation: sparkles 30 emissions x 3
 * = 90, shot bursts 12 x 4 = 48, total at most 138 sent
 * particles, plus the bullets' own vanilla trails.
 *
 * State tag keys: none (the spiral is a pure function of the
 * activation tick counter).
 */
public final class PetalStormSpiralAbility implements MobAbility {

    static final int DURATION_TICKS = 70;
    private static final int SHOT_INTERVAL = 5;
    private static final int SHOT_COUNT = 12;
    private static final double ANGLE_STEP = 0.9;
    private static final double BASE_RADIUS = 1.0;
    private static final double RADIUS_STEP = 0.18;
    private static final double BASE_HEIGHT = 0.5;
    private static final double HEIGHT_STEP = 0.22;

    @Override
    public String id() {
        return "petal_storm_spiral";
    }

    @Override
    public String displayName() {
        return "Petal Storm Spiral";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // Nothing to initialize: the spiral is driven entirely
        // by the activation tick counter.
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int tick = ctx.ticksActive();

        // Ambient END_ROD sparkle off the open shell, firing phase only.
        if (tick <= SHOT_INTERVAL * SHOT_COUNT && tick % 2 == 0) {
            level.sendParticles(ParticleTypes.END_ROD,
                caster.getX(), caster.getY() + 1.0, caster.getZ(),
                3, 0.5, 0.4, 0.5, 0.02);
        }

        if (tick % SHOT_INTERVAL != 0 || tick > SHOT_INTERVAL * SHOT_COUNT) {
            return; // Cheap early-out: only shot ticks do real work.
        }
        int shot = tick / SHOT_INTERVAL - 1;

        LivingEntity target = ctx.target();
        if (target == null) {
            target = ShulkerAbilitySupport.nearestEnemy(ctx,
                caster.getX(), caster.getY(), caster.getZ(), 24.0);
        }
        if (target == null) {
            return; // Nobody to shoot: the shot is skipped.
        }

        double angle = shot * ANGLE_STEP;
        double radius = BASE_RADIUS + shot * RADIUS_STEP;
        double spawnX = caster.getX() + Math.cos(angle) * radius;
        double spawnY = caster.getY() + BASE_HEIGHT + shot * HEIGHT_STEP;
        double spawnZ = caster.getZ() + Math.sin(angle) * radius;
        if (ShulkerAbilitySupport.fireBullet(ctx, target, spawnX, spawnY, spawnZ)) {
            level.sendParticles(ParticleTypes.END_ROD, spawnX, spawnY, spawnZ,
                4, 0.2, 0.2, 0.2, 0.03);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.5f, 1.0f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the bullets are free vanilla
        // projectiles and the ability applied no modifiers,
        // effects, or terrain.
    }
}
