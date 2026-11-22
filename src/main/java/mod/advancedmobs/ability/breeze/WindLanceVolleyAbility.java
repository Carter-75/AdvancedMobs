package mod.advancedmobs.ability.breeze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Wind Lance Volley (Breeze ability 1 of 5, attack).
 *
 * WHAT IT DOES: the breeze inhales, spins up a tight spiral of
 * wind, and fires a rapid line of six wind charges at its
 * target. Each charge bursts on impact in the vanilla wind pop,
 * scattering whatever stands at the burst point. The sixth and
 * last charge is oversized: when it bursts, a much larger gust
 * detonates with it and flings everything near the impact point
 * hard.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   breeze's current target within 16 blocks (distance squared
 *   256).
 * - Duration: shots land on ticks 5, 10, 15, 20, 25, and 30
 *   (six charges, one every 5 ticks). The ability finishes at
 *   tick 70 at the latest, or 8 ticks after the oversized burst
 *   has gone off, whichever comes first.
 * - The charges: real vanilla BreezeWindCharge projectiles (see
 *   BreezeAbilitySupport), fired from the breeze's eye position
 *   toward the target's chest (eye height minus 0.4) at its
 *   position on the shot tick, so a moving target can dodge the
 *   later shots. Speed 1.6 blocks per tick. Each charge's own
 *   burst is entirely vanilla: a wind explosion at the impact
 *   point dealing minor damage and knocking entities away from
 *   the center; wind bursts never break blocks, so the terrain
 *   rule is not involved. If the target is dead or gone on a
 *   shot tick, that shot is aimed at the last recorded target
 *   position instead; if no position was ever recorded, the
 *   shot is skipped.
 * - The oversized charge (shot 6): tracked by UUID with its
 *   last known position recorded every tick. When it vanishes
 *   (impact, or the 100-tick flight timeout discards it where
 *   it flies), the code burst runs at its last position:
 *   enemies within 3.0 blocks take 4.0 mob-attack damage
 *   credited to the breeze and are flung radially away from the
 *   burst point at 1.5 blocks per tick horizontal and 0.65 up
 *   (hurtMarked set so player clients apply it), on top of the
 *   charge's own vanilla pop. A fizzle guard matches the Magma
 *   Mortar precedent: a charge that vanishes within 3 ticks and
 *   2.5 blocks of the muzzle never really left, and runs no
 *   burst.
 * - end(): nothing to clean up. The charges are vanilla
 *   projectiles that finish on their own; any still in flight
 *   when the ability ends simply keep flying and burst as
 *   ordinary wind charges.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: BREEZE_INHALE at the start as
 * the breeze draws breath. Each shot: 4 GUST particles at the
 * muzzle and BREEZE_SHOOT at a pitch rising with the shot count
 * (0.9 plus 0.08 per shot). The oversized burst: 3
 * GUST_EMITTER_LARGE and 10 SMALL_GUST particles plus
 * BREEZE_WIND_CHARGE_BURST at volume 1.5.
 *
 * PERFORMANCE: tick() does at most one wind charge spawn on a
 * shot tick and one UUID resolve plus position write per tick
 * while the oversized charge flies; the burst is one bounded
 * query (radius 3.0). No entity scans otherwise. Particle
 * budget per activation: 6 shots times 4 muzzle motes = 24,
 * plus the oversized burst's 13, total at most 37 particles
 * sent by this ability (the charges' own vanilla trails and
 * bursts are vanilla's).
 *
 * State tag keys: "shots" (charges fired so far), "tx", "ty",
 * "tz" (last recorded target position), "big" (the oversized
 * charge's UUID, once fired), "bx", "by", "bz" (its last known
 * position), "big_tick" (the tick it was fired), "burst"
 * (1 once the oversized burst has run).
 */
public final class WindLanceVolleyAbility implements MobAbility {

    static final int SHOT_COUNT = 6;
    private static final int SHOT_INTERVAL = 5;
    private static final int FIRST_SHOT_TICK = 5;
    private static final int HARD_FINISH_TICK = 70;
    private static final int BIG_FLIGHT_TIMEOUT = 100;
    private static final double CHARGE_SPEED = 1.6;
    private static final double BURST_RADIUS = 3.0;
    private static final float BURST_DAMAGE = 4.0f;

    @Override
    public String id() {
        return "wind_lance_volley";
    }

    @Override
    public String displayName() {
        return "Wind Lance Volley";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.putInt("shots", 0);
        state.putInt("burst", 0);
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(state, target);
        }
        Mob mob = ctx.mob();
        ctx.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();

        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(state, target);
        }

        // Fire the scheduled shots.
        int shots = state.getIntOr("shots", 0);
        if (shots < SHOT_COUNT && ticks >= FIRST_SHOT_TICK + shots * SHOT_INTERVAL) {
            fireShot(ctx, level, state, shots);
        }

        // Track the oversized charge until it bursts.
        if (state.getIntOr("burst", 0) == 0 && state.contains("big")) {
            Entity big = BreezeAbilitySupport.resolve(level,
                state.read("big", UUIDUtil.CODEC).orElse(null));
            if (big == null) {
                double bx = state.getDoubleOr("bx", mob.getX());
                double by = state.getDoubleOr("by", mob.getY());
                double bz = state.getDoubleOr("bz", mob.getZ());
                if (!fizzled(ctx, state, bx, by, bz)) {
                    runBurst(ctx, level, bx, by, bz);
                }
                state.putInt("burst", 1);
            } else {
                state.putDouble("bx", big.getX());
                state.putDouble("by", big.getY());
                state.putDouble("bz", big.getZ());
                if (ticks - state.getIntOr("big_tick", ticks) >= BIG_FLIGHT_TIMEOUT) {
                    runBurst(ctx, level, big.getX(), big.getY(), big.getZ());
                    big.discard();
                    state.putInt("burst", 1);
                }
            }
        }
    }

    /** Fires shot number shotIndex (0-based); the last shot is the oversized one. */
    private void fireShot(AbilityContext ctx, ServerLevel level, CompoundTag state, int shotIndex) {
        Mob mob = ctx.mob();
        if (!state.contains("tx")) {
            state.putInt("shots", shotIndex + 1);
            return;
        }
        Vec3 from = mob.getEyePosition();
        Vec3 aim = new Vec3(state.getDoubleOr("tx", mob.getX()),
                            state.getDoubleOr("ty", mob.getY()),
                            state.getDoubleOr("tz", mob.getZ()));
        BreezeWindCharge charge = BreezeAbilitySupport.fireWindCharge(
            ctx, from, aim.subtract(from), CHARGE_SPEED);
        state.putInt("shots", shotIndex + 1);
        if (charge != null && shotIndex == SHOT_COUNT - 1) {
            state.store("big", UUIDUtil.CODEC, charge.getUUID());
            state.putDouble("bx", charge.getX());
            state.putDouble("by", charge.getY());
            state.putDouble("bz", charge.getZ());
            state.putInt("big_tick", ctx.ticksActive());
        }
        level.sendParticles(ParticleTypes.GUST, from.x, from.y, from.z,
            4, 0.2, 0.2, 0.2, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 0.9f, 0.9f + 0.08f * shotIndex);
    }

    /** The oversized burst at the given point (see the class comment). */
    private void runBurst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = BreezeAbilitySupport.enemiesInBox(ctx, x, y, z, BURST_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - x;
            double dz = victim.getZ() - z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > BURST_RADIUS) {
                continue;
            }
            victim.hurt(level.damageSources().mobAttack(mob), BURST_DAMAGE);
            double scale = horizontal > 1.0e-4 ? 1.5 / horizontal : 0.0;
            Vec3 velocity = victim.getDeltaMovement();
            victim.setDeltaMovement(velocity.x + dx * scale, 0.65, velocity.z + dz * scale);
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, x, y, z, 3, 0.3, 0.3, 0.3, 0.0);
        level.sendParticles(ParticleTypes.SMALL_GUST, x, y, z, 10, 0.8, 0.5, 0.8, 0.1);
        level.playSound(null, x, y, z, SoundEvents.BREEZE_WIND_CHARGE_BURST,
            SoundSource.HOSTILE, 1.5f, 0.9f);
    }

    /**
     * Whether the oversized charge fizzled against the muzzle:
     * gone within 3 ticks of firing and within 2.5 blocks of the
     * breeze, in which case its burst never runs.
     */
    private boolean fizzled(AbilityContext ctx, CompoundTag state, double bx, double by, double bz) {
        if (ctx.ticksActive() - state.getIntOr("big_tick", 0) > 3) {
            return false;
        }
        Mob mob = ctx.mob();
        double dx = bx - mob.getX();
        double dy = by - mob.getY();
        double dz = bz - mob.getZ();
        return dx * dx + dy * dy + dz * dz <= 6.25;
    }

    private static void recordTarget(CompoundTag state, LivingEntity target) {
        state.putDouble("tx", target.getX());
        state.putDouble("ty", target.getEyeY() - 0.4);
        state.putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICK) {
            return true;
        }
        // All shots fired and the oversized burst spent: a short
        // tail so the burst reads, then done.
        return state.getIntOr("shots", 0) >= SHOT_COUNT
            && state.getIntOr("burst", 0) == 1
            && ticks >= FIRST_SHOT_TICK + (SHOT_COUNT - 1) * SHOT_INTERVAL + 8;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: the charges are vanilla
        // projectiles that finish on their own.
    }
}
