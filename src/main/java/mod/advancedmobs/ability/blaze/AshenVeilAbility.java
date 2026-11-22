package mod.advancedmobs.ability.blaze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Ashen Veil (Blaze ability 4 of 5, defense).
 *
 * WHAT IT DOES: the blaze detonates a choking cloud of ash
 * and smoke and vanishes inside it. For five seconds it is
 * invisible and hardened, drifting silently to a new position
 * while every enemy caught in the cloud loses track of it
 * completely and coughs up ash. When the veil thins, the
 * blaze is already somewhere else, winding up its next
 * volley.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 12 blocks
 *   (distance squared 144).
 * - The vanishing (start()): INVISIBILITY and RESISTANCE I
 *   (amplifier 0), each for 110 ticks with particles and icon
 *   hidden, applied to the blaze and removed in end(). (A
 *   blaze that somehow had either effect from another source
 *   would lose it at end(), an accepted edge case shared with
 *   every fixed-duration effect in this system.) The cloud
 *   center is the blaze's position at cast time, recorded in
 *   the state tag; the cloud is a place and does not follow
 *   the blaze. The detonation throws 26 ASH and 10
 *   LARGE_SMOKE at the blaze's body with a GENERIC_EXPLODE
 *   (volume 0.3, pitch 0.7).
 * - The drift (start() picks it, tick() walks it): the
 *   destination is 5 blocks from the cast position at a
 *   random angle, at the same height. If the blaze's bounding
 *   box would collide there, the opposite direction is tried;
 *   if that collides too, the destination is the cast
 *   position itself (a veil in place). Over ticks 1 to 20 the
 *   blaze is snapped along the straight line from the cast
 *   position to the destination, and its navigation is
 *   stopped every one of those ticks. No NoAi is used; the
 *   snap is the hold, per the standing contract. The veil
 *   does not suppress the blaze's vanilla attacks: a blaze
 *   that was already winding up can still loose a volley from
 *   inside the cloud, which is the design's own closing image.
 * - Losing the trail (tick(), every 5 ticks): one bounded
 *   enemy query in radius 5.5 around the cloud center (3
 *   below to 3 above it). Every enemy Mob whose vanilla
 *   target is the blaze has its target cleared, and its war
 *   system tracked target is cleared too when it points at
 *   the blaze (MobWarData.clearTarget), so the enemy has to
 *   acquire the blaze all over again once it reappears.
 *   Players have no target to clear; the invisibility itself
 *   is what hides the blaze from them.
 * - The coughing (tick(), every 10 ticks, off the same
 *   query): each enemy in the cloud gets 2 ASH particles at
 *   its eye height. Pure theater: the cloud deals no damage.
 * - The murk (tick(), every 4 ticks): 3 ASH drift at the
 *   cloud center (spread 2.0 wide, 1.0 tall) and 1
 *   SMALL_FLAME burns at the blaze's own position: the faint
 *   glow of its rods through the murk, the one tell the
 *   design allows.
 * - Duration: exactly 100 ticks (5 seconds) from start().
 *
 * TERRAIN RULE: this ability changes no blocks at all: the
 * cloud is particles and effects, the drift is a reposition.
 * The mobGriefing gate does not apply anywhere in it.
 *
 * WHAT IT LOOKS LIKE: a soft detonation of gray ash and dark
 * smoke where the blaze stood, a choking cloud hanging in the
 * air for five seconds with enemies inside it coughing ash
 * and swinging at nothing, and a single small flame winking
 * slowly across the cloud as the hidden blaze drifts to its
 * new spot.
 *
 * PERFORMANCE: the drift is one snapTo per tick for 20 ticks.
 * The enemy query runs every 5 ticks on a tight box; the
 * murk pulse every 4 ticks is 4 particle spawns. No
 * whole-level scans, no per-tick allocations beyond the query
 * results. Particle budget per activation: detonation 36,
 * murk 25 pulses times 4 = 100, coughing at most 10 pulses
 * times a generous 4 enemies times 2 = 80, total at most 216
 * particles.
 *
 * CLEANUP: end() removes the INVISIBILITY and RESISTANCE
 * effects from the blaze. The cloud is particles only and
 * needs no cleanup; cleared enemy targets stay cleared (the
 * enemies re-acquire targets through their own AI, which is
 * the point of the ability).
 *
 * State tag keys: "cx", "cy", "cz" (the cloud center,
 * doubles), "sx", "sy", "sz" (the cast position, doubles),
 * "dx", "dz" (the drift destination, doubles; the drift
 * height never changes, so no "dy" is stored).
 */
public final class AshenVeilAbility implements MobAbility {

    static final int DURATION_TICKS = 100;
    private static final int DRIFT_TICKS = 20;
    private static final int EFFECT_TICKS = 110;
    private static final double CLOUD_RADIUS = 5.5;

    @Override
    public String id() {
        return "ashen_veil";
    }

    @Override
    public String displayName() {
        return "Ashen Veil";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        double sx = mob.getX();
        double sy = mob.getY();
        double sz = mob.getZ();
        state.putDouble("cx", sx);
        state.putDouble("cy", sy);
        state.putDouble("cz", sz);
        state.putDouble("sx", sx);
        state.putDouble("sy", sy);
        state.putDouble("sz", sz);

        // The drift destination (see the class comment):
        // 5 blocks at a random angle, the opposite direction
        // if that spot collides, the cast position if both do.
        double angle = ctx.random().nextDouble() * Math.PI * 2.0;
        double destX = sx + Math.cos(angle) * 5.0;
        double destZ = sz + Math.sin(angle) * 5.0;
        if (level != null && !isFreeSpot(level, mob, destX, destZ)) {
            double backX = sx - Math.cos(angle) * 5.0;
            double backZ = sz - Math.sin(angle) * 5.0;
            if (isFreeSpot(level, mob, backX, backZ)) {
                destX = backX;
                destZ = backZ;
            } else {
                destX = sx;
                destZ = sz;
            }
        }
        state.putDouble("dx", destX);
        state.putDouble("dz", destZ);

        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_TICKS, 0, false, false, false));
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EFFECT_TICKS, 0, false, false, false));

        if (level != null) {
            level.sendParticles(ParticleTypes.ASH, sx, sy + 1.0, sz, 26, 1.2, 0.8, 1.2, 0.05);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, sx, sy + 1.0, sz, 10, 1.0, 0.7, 1.0, 0.04);
            level.playSound(null, sx, sy, sz, SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 0.3f, 0.7f);
        }
    }

    /** Whether the blaze's bounding box fits at the given spot at its current height. */
    private static boolean isFreeSpot(ServerLevel level, Mob mob, double x, double z) {
        AABB box = mob.getBoundingBox().move(x - mob.getX(), 0.0, z - mob.getZ());
        return level.noCollision(mob, box);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();

        if (ticks <= DRIFT_TICKS) {
            // The drift: a straight glide from the cast position
            // to the destination over 20 ticks.
            mob.getNavigation().stop();
            double frac = ticks / (double) DRIFT_TICKS;
            double sx = state.getDoubleOr("sx", mob.getX());
            double sy = state.getDoubleOr("sy", mob.getY());
            double sz = state.getDoubleOr("sz", mob.getZ());
            double x = sx + (state.getDoubleOr("dx", sx) - sx) * frac;
            double z = sz + (state.getDoubleOr("dz", sz) - sz) * frac;
            mob.snapTo(x, sy, z, mob.getYRot(), mob.getXRot());
        }

        if (ticks % 4 == 0) {
            // The murk, and the one tell: a rod's glow at the
            // hidden blaze.
            level.sendParticles(ParticleTypes.ASH,
                state.getDoubleOr("cx", mob.getX()), state.getDoubleOr("cy", mob.getY()) + 1.0,
                state.getDoubleOr("cz", mob.getZ()),
                3, 2.0, 1.0, 2.0, 0.02);
            level.sendParticles(ParticleTypes.SMALL_FLAME, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                1, 0.25, 0.4, 0.25, 0.0);
        }

        if (ticks % 5 == 0) {
            cloudPulse(ctx, level, ticks % 10 == 0);
        }
    }

    /**
     * One cloud pulse: enemies in the cloud lose the blaze's
     * trail, and, on every second pulse, cough up ash (see the
     * class comment).
     */
    private static void cloudPulse(AbilityContext ctx, ServerLevel level, boolean cough) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        List<LivingEntity> enemies = BlazeAbilitySupport.enemiesInBox(ctx,
            state.getDoubleOr("cx", mob.getX()), state.getDoubleOr("cy", mob.getY()),
            state.getDoubleOr("cz", mob.getZ()), CLOUD_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == mob) {
                enemyMob.setTarget(null);
                if (mob.getUUID().equals(MobWarData.get(enemyMob).getCurrentTarget())) {
                    MobWarData.get(enemyMob).clearTarget();
                }
            }
            if (cough) {
                level.sendParticles(ParticleTypes.ASH, enemy.getX(), enemy.getEyeY(), enemy.getZ(),
                    2, 0.25, 0.15, 0.25, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.INVISIBILITY);
        mob.removeEffect(MobEffects.RESISTANCE);
    }
}
