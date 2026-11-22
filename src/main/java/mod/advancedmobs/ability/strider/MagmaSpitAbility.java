package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Magma Spit (Strider ability 3 of 5, attack).
 *
 * WHAT IT DOES: the strider hawks up a glob of molten rock,
 * DRIPPING_LAVA running from its mouth, and spits one small
 * fireball in a high arc at its target. The glob bursts where
 * it lands in LAVA spray: enemies in the splash take damage
 * and are set burning, and the burst splashes real fire
 * blocks across the impact point.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): a live target between 5 and 20
 *   blocks away (distance squared 25 to 400). Point blank
 *   targets are for the body and the frenzy, not the spit.
 * - The glob (start()): the target's position at this moment
 *   is recorded (the glob aims at where the target stood, so
 *   a moving target can dodge). One SmallFireball is spawned
 *   at the strider's mouth (0.5 blocks above its top) with
 *   the strider as its owner and its accelerationPower
 *   zeroed, so the glob is a pure ballistic: nothing propels
 *   it after the spit. The lob velocity is computed for a
 *   flight time of T = 20 + (target distance in blocks)
 *   ticks: the horizontal components divide the horizontal
 *   distance by the drag sum of a 0.95 inertia shell over T
 *   ticks, exactly compensating the glob's vanilla air drag,
 *   and the vertical component covers the height difference
 *   plus half the gravity drop over T ticks. The aim model
 *   is the Magma Mortar's, deliberately approximate; the 2.2
 *   block splash below is the real effect.
 * - Flight (tick()): every tick 0.05 of gravity is subtracted
 *   from the glob's vertical velocity (vanilla fireballs have
 *   no gravity of their own; the subtraction is what makes
 *   the glob arc). The glob's position is recorded every
 *   tick. Every 2 ticks, 2 FALLING_LAVA particles drip off
 *   it: the visible arc. Vanilla glob behavior on a direct
 *   hit is part of the design: a SmallFireball that strikes
 *   an entity directly deals its vanilla 5.0 fireball damage
 *   and lights it for 5 seconds, and one that strikes a block
 *   with mobGriefing on sets that one block on fire (vanilla
 *   checks the game rule itself).
 * - The burst (when the glob entity is gone, or at the hard
 *   timeout of 100 ticks of flight, when it is discarded
 *   where it flies): one bounded enemy query in radius 2.2
 *   around the impact point, from 1.5 below to 2 above it.
 *   Every enemy takes 4.0 damage as a mob attack credited to
 *   the strider (hurtServer with damageSources().mobAttack;
 *   a strider has no ATTACK_DAMAGE attribute instance in
 *   26.2, verified against the jar) and is ignited for 5
 *   seconds (igniteForSeconds).
 * - Fizzle guard: a glob that vanishes within its first 3
 *   ticks while still within 2.5 blocks of the launch point
 *   (it clipped the strider or the ceiling above it) runs no
 *   burst: the spit simply fails.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the burst's fire blocks
 * are PERMANENT terrain and are gated by
 * AbilityContext.mobGriefingAllowed():
 * - mobGriefing ON: up to 4 fire blocks are placed around
 *   the impact point (the impact position itself, then
 *   random offsets inside radius 1.5, at the impact height
 *   and one block above and below it), each through the
 *   shared placeFire safety check (dry, replaceable, sturdy
 *   ground beneath, never a block entity). The patch stays
 *   and burns whatever stands in it until it burns out on
 *   vanilla's own schedule. (The design text said the patch
 *   is short lived; fire blocks burn out on their own
 *   schedule, and the standing terrain rule makes the
 *   placement permanent rather than restored, the same call
 *   as Magma Mortar.)
 * - mobGriefing OFF: no fire is placed (and the vanilla glob
 *   places none either, per its own rule check). The burst
 *   still damages and ignites exactly as above.
 *
 * WHAT IT LOOKS LIKE: DRIPPING_LAVA at the mouth and a
 * FIRECHARGE_USE crack (volume 0.9, pitch 1.0) as the glob
 * leaves, a dripping FALLING_LAVA arc across the fight, then
 * the burst: 10 LAVA and 8 FLAME particles with a
 * GENERIC_EXPLODE (volume 0.5, pitch 1.2) and a LAVA_POP
 * (volume 0.8, pitch 0.9), over a patch of real fire.
 *
 * PERFORMANCE: flight tracking is one UUID resolve and one
 * position read per tick; the gravity write is one velocity
 * read and write. The single bounded enemy query runs once,
 * at the burst. Particle budget per activation: mouth drips
 * 8, flight at most 50 ticks of drips times 2 = 100, burst
 * 18, total at most 126 particles.
 *
 * CLEANUP: end() discards the glob if it is somehow still
 * flying. Fire blocks already placed (mobGriefing on) are
 * permanent terrain and are deliberately NOT touched.
 *
 * State tag keys: "glob" (the fireball's UUID), "sx", "sy",
 * "sz" (launch point, doubles), "lx", "ly", "lz" (the glob's
 * last known position, doubles), "burst" (1 once the burst
 * ran).
 */
public final class MagmaSpitAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 200;
    private static final int GLOB_TIMEOUT_TICKS = 100;
    private static final double BURST_RADIUS = 2.2;
    private static final int MAX_FIRE_BLOCKS = 4;
    private static final float BURST_DAMAGE = 4.0f;
    private static final double GLOB_GRAVITY = 0.05;

    @Override
    public String id() {
        return "magma_spit";
    }

    @Override
    public String displayName() {
        return "Magma Spit";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 25.0 && distanceSqr <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("burst", 0);
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            state.putInt("burst", 1);
            return;
        }
        double sx = mob.getX();
        double sy = mob.getY() + mob.getBbHeight() + 0.5;
        double sz = mob.getZ();
        state.putDouble("sx", sx);
        state.putDouble("sy", sy);
        state.putDouble("sz", sz);
        state.putDouble("lx", sx);
        state.putDouble("ly", sy);
        state.putDouble("lz", sz);

        level.sendParticles(ParticleTypes.DRIPPING_LAVA, mob.getX(),
            mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(),
            8, 0.3, 0.2, 0.3, 0.0);

        // The lob: ballistic velocity for a flight of T ticks,
        // horizontal components drag-compensated (see the class
        // comment), vertical covering the height gap plus the
        // gravity drop. The Magma Mortar aim model, reused.
        double dx = target.getX() - sx;
        double dy = target.getY() - sy;
        double dz = target.getZ() - sz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int flightTicks = 20 + (int) horizontal;
        double dragSum = (1.0 - Math.pow(0.95, flightTicks)) / 0.05;
        Vec3 velocity = new Vec3(
            dx / dragSum,
            dy / flightTicks + 0.5 * GLOB_GRAVITY * flightTicks + 0.2,
            dz / dragSum);
        SmallFireball glob = new SmallFireball(level, mob, velocity);
        glob.snapTo(sx, sy, sz, 0.0f, 0.0f);
        // No self-propulsion: the arc comes from the gravity
        // subtraction in tick(), not from the glob's own power.
        glob.accelerationPower = 0.0;
        if (!level.addFreshEntity(glob)) {
            state.putInt("burst", 1);
            return;
        }
        state.store("glob", UUIDUtil.CODEC, glob.getUUID());
        level.playSound(null, sx, sy, sz, SoundEvents.FIRECHARGE_USE,
            SoundSource.HOSTILE, 0.9f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        if (state.getIntOr("burst", 0) != 0) {
            return;
        }
        int ticks = ctx.ticksActive();
        Entity glob = resolveGlob(ctx, level);
        if (glob == null) {
            // The glob hit something (or fizzled against the
            // launch point): the burst runs at its last known
            // position, unless the fizzle guard says it never
            // really left.
            double lx = state.getDoubleOr("lx", ctx.mob().getX());
            double ly = state.getDoubleOr("ly", ctx.mob().getY());
            double lz = state.getDoubleOr("lz", ctx.mob().getZ());
            if (!fizzled(ctx, lx, ly, lz)) {
                runBurst(ctx, level, lx, ly, lz);
            } else {
                state.putInt("burst", 1);
            }
            return;
        }
        state.putDouble("lx", glob.getX());
        state.putDouble("ly", glob.getY());
        state.putDouble("lz", glob.getZ());
        // Gravity: the subtraction that bends the glob's flight
        // into an arc.
        Vec3 velocity = glob.getDeltaMovement();
        glob.setDeltaMovement(velocity.x, velocity.y - GLOB_GRAVITY, velocity.z);
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.FALLING_LAVA, glob.getX(), glob.getY(), glob.getZ(),
                2, 0.1, 0.1, 0.1, 0.0);
        }
        if (ticks >= GLOB_TIMEOUT_TICKS) {
            // A glob that never landed bursts where it flies.
            runBurst(ctx, level, glob.getX(), glob.getY(), glob.getZ());
            glob.discard();
        }
    }

    /** Whether the vanished glob fizzled at the launch point (see the class comment). */
    private static boolean fizzled(AbilityContext ctx, double lx, double ly, double lz) {
        if (ctx.ticksActive() > 3) {
            return false;
        }
        CompoundTag state = ctx.state();
        double dx = lx - state.getDoubleOr("sx", lx);
        double dy = ly - state.getDoubleOr("sy", ly);
        double dz = lz - state.getDoubleOr("sz", lz);
        return dx * dx + dy * dy + dz * dz <= 6.25;
    }

    /**
     * The burst: damage and ignite every enemy at the impact
     * point, and, when the terrain rule allows it, splash
     * permanent fire blocks across the spot.
     */
    private static void runBurst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        CompoundTag state = ctx.state();
        state.putInt("burst", 1);
        Mob mob = ctx.mob();
        List<LivingEntity> victims = StriderAbilitySupport.enemiesInBox(ctx, x, y, z, BURST_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), BURST_DAMAGE);
            victim.igniteForSeconds(5.0f);
        }

        if (ctx.mobGriefingAllowed()) {
            int placed = 0;
            BlockPos center = BlockPos.containing(x, y, z);
            if (StriderAbilitySupport.placeFire(level, center)) {
                placed++;
            }
            for (int attempt = 0; attempt < 12 && placed < MAX_FIRE_BLOCKS; attempt++) {
                double ox = (ctx.random().nextDouble() * 3.0 - 1.5);
                double oz = (ctx.random().nextDouble() * 3.0 - 1.5);
                int oy = ctx.random().nextInt(3) - 1;
                BlockPos candidate = BlockPos.containing(x + ox, y + oy, z + oz);
                if (StriderAbilitySupport.placeFire(level, candidate)) {
                    placed++;
                }
            }
        }

        level.sendParticles(ParticleTypes.LAVA, x, y + 0.3, z, 10, 0.8, 0.4, 0.8, 0.1);
        level.sendParticles(ParticleTypes.FLAME, x, y + 0.3, z, 8, 0.8, 0.4, 0.8, 0.08);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.5f, 1.2f);
        level.playSound(null, x, y, z, SoundEvents.LAVA_POP, SoundSource.HOSTILE, 0.8f, 0.9f);
    }

    /** Resolves the tracked glob from the state tag, or null when it is gone. */
    private Entity resolveGlob(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (!state.contains("glob")) {
            return null;
        }
        UUID id = state.read("glob", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_FINISH_TICKS
            || ctx.state().getIntOr("burst", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity glob = resolveGlob(ctx, level);
        if (glob != null) {
            glob.discard();
        }
    }
}
