package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Plague Breath (Zoglin ability 3 of 5, attack).
 *
 * WHAT IT DOES: the zoglin plants itself, opens its rotting
 * jaws, and exhales a rolling cone of greenish miasma, the
 * sulfur-born NOXIOUS_GAS and NOXIOUS_GAS_CLOUD of the design.
 * Enemies caught in the breath are wracked with NAUSEA and
 * POISON and stumble out of formation. When the breath stops,
 * the gas hangs low over the ground where it was exhaled as a
 * lingering hazard zone for a few more seconds before it
 * disperses: anything standing in it keeps breathing poison.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   zoglin's current target within 9 blocks (distance squared
 *   81). A point blank exhale, not a ranged tool.
 * - Duration: exactly 200 ticks (10 seconds) from start():
 *   a 40 tick breath phase, then a 160 tick linger phase.
 * - Facing and cloud center, fixed in start(): the facing is
 *   the direction toward the target's position at start() (or
 *   the look direction if the target is dead center). The
 *   cloud center is recorded 3.5 blocks ahead of the zoglin
 *   along that facing, at the zoglin's feet height. Neither
 *   moves afterwards: the breath is exhaled once, in one
 *   direction, and the zoglin's later wandering does not drag
 *   the hazard around.
 * - Breath phase (ticks 1 to 40): a cone pulse every 4 ticks
 *   (10 pulses). An enemy (ZoglinAbilitySupport rules) is in
 *   the cone when it is within 6.0 blocks of the zoglin
 *   (checked with one bounded query, 2.0 below to 3.0 above
 *   its feet) and the horizontal direction from the zoglin to
 *   it has a dot product of at least 0.70 with the facing
 *   (inside roughly a 45 degree half-angle). Each pulse gives
 *   every enemy in the cone NAUSEA amplifier 0 for 80 ticks
 *   and POISON amplifier 0 for 100 ticks.
 * - Linger phase (ticks 41 to 200): a hazard pulse every 10
 *   ticks (16 pulses) over the recorded cloud center: enemies
 *   within radius 3.2 of the center (1.5 below to 2.5 above
 *   it) gain POISON amplifier 0 for 60 ticks and NAUSEA
 *   amplifier 0 for 60 ticks. The 60 against the 10 tick pulse
 *   means a victim who leaves the cloud stays sick about 2
 *   seconds past the last breath of it.
 *
 * WHAT IT LOOKS LIKE: a low ZOGLIN_ANGRY exhale at a dropped
 * pitch in start(). During the breath phase, every 2 ticks
 * (20 emissions) the cone is drawn in gas: 2 NOXIOUS_GAS at
 * each of 1.5, 3.0 and 4.5 blocks along the facing (lateral
 * jitter up to 0.5 blocks from the random source, at chest
 * height falling to knee height along the cone, because the
 * miasma hangs low) plus 2 NOXIOUS_GAS_CLOUD at the far point.
 * During the linger, every 5 ticks (32 emissions) 3
 * NOXIOUS_GAS_CLOUD and 2 NOXIOUS_GAS roll at the cloud
 * center at knee height. Victims show the vanilla NAUSEA and
 * POISON sparkles while sickened.
 *
 * TERRAIN: the cloud is a zone effect (particles and debuffs
 * around a recorded point), not block placement, so the
 * mobGriefing gate does not apply anywhere in this ability.
 *
 * PERFORMANCE: the cone pulse runs every 4th tick of the
 * breath phase and the hazard pulse every 10th tick of the
 * linger, each one bounded entity query; particle emissions
 * run every 2nd and every 5th tick respectively and are pure
 * arithmetic from the recorded facing. All other ticks return
 * immediately. Particle budget per activation: breath 20
 * emissions times 8 = 160, linger 32 emissions times 5 = 160,
 * total at most 320 particles.
 *
 * CLEANUP: end() has nothing to undo. Every effect this breath
 * applies lasts at most 100 ticks and expires on its own; no
 * attribute modifiers, no spawned entities, no block changes.
 *
 * State tag keys: "dir_x" and "dir_z" (the exhale facing,
 * unit length), "cx", "cy" and "cz" (the recorded cloud
 * center).
 */
public final class PlagueBreathAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int BREATH_TICKS = 40;
    private static final int CONE_PULSE_INTERVAL = 4;
    private static final int BREATH_PARTICLE_INTERVAL = 2;
    private static final double CONE_RANGE = 6.0;
    private static final double CONE_DOT_MIN = 0.70;
    private static final int HAZARD_PULSE_INTERVAL = 10;
    private static final int HAZARD_PARTICLE_INTERVAL = 5;
    private static final double HAZARD_RADIUS = 3.2;

    @Override
    public String id() {
        return "plague_breath";
    }

    @Override
    public String displayName() {
        return "Plague Breath";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 81.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The facing: toward the target right now, or along
        // the look direction when the target is dead center.
        double dx = 0.0;
        double dz = 0.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - mob.getX();
            dz = target.getZ() - mob.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            Vec3 look = mob.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0e-4) {
                dx = 1.0;
                length = 1.0;
            }
        }
        double dirX = dx / length;
        double dirZ = dz / length;
        state.putDouble("dir_x", dirX);
        state.putDouble("dir_z", dirZ);
        state.putDouble("cx", mob.getX() + dirX * 3.5);
        state.putDouble("cy", mob.getY());
        state.putDouble("cz", mob.getZ() + dirZ * 3.5);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The exhale: a long, low, wet bellow.
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks <= BREATH_TICKS) {
            if (ticks % BREATH_PARTICLE_INTERVAL == 0) {
                breathParticles(ctx);
            }
            if (ticks % CONE_PULSE_INTERVAL == 0) {
                conePulse(ctx);
            }
        } else {
            if (ticks % HAZARD_PARTICLE_INTERVAL == 0) {
                hazardParticles(ctx);
            }
            if (ticks % HAZARD_PULSE_INTERVAL == 0) {
                hazardPulse(ctx);
            }
        }
    }

    /** The visible cone: gas rolling out along the recorded facing, hanging lower as it travels. */
    private void breathParticles(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        RandomSource random = ctx.random();
        double[] distances = {1.5, 3.0, 4.5};
        for (int i = 0; i < distances.length; i++) {
            double jitterX = (random.nextDouble() - 0.5) * 1.0;
            double jitterZ = (random.nextDouble() - 0.5) * 1.0;
            double px = mob.getX() + dirX * distances[i] + jitterX;
            double pz = mob.getZ() + dirZ * distances[i] + jitterZ;
            double py = mob.getY() + 1.1 - i * 0.3;
            level.sendParticles(ParticleTypes.NOXIOUS_GAS,
                px, py, pz, 2, 0.4, 0.3, 0.4, 0.02);
            if (i == distances.length - 1) {
                level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD,
                    px, py, pz, 2, 0.5, 0.3, 0.5, 0.02);
            }
        }
    }

    /** One breath pulse: enemies inside the cone are wracked with nausea and poison. */
    private void conePulse(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        List<LivingEntity> candidates = ZoglinAbilitySupport.enemiesAroundSelf(ctx, CONE_RANGE, 2.0, 3.0);
        for (LivingEntity victim : candidates) {
            double vx = victim.getX() - mob.getX();
            double vz = victim.getZ() - mob.getZ();
            double dist = Math.sqrt(vx * vx + vz * vz);
            if (dist < 1.0e-4) {
                continue; // On top of the zoglin: no facing to test, the gore handles those.
            }
            double dot = (vx * dirX + vz * dirZ) / dist;
            if (dot < CONE_DOT_MIN) {
                continue;
            }
            victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
        }
    }

    /** The lingering cloud: gas rolling low over the recorded center. */
    private void hazardParticles(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);
        level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD,
            cx, cy + 0.5, cz, 3, 1.4, 0.3, 1.4, 0.02);
        level.sendParticles(ParticleTypes.NOXIOUS_GAS,
            cx, cy + 0.4, cz, 2, 1.4, 0.3, 1.4, 0.02);
    }

    /** One hazard pulse: enemies standing in the lingering cloud keep breathing poison. */
    private void hazardPulse(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);
        List<LivingEntity> victims = ZoglinAbilitySupport.enemiesInBox(ctx, cx, cy, cz, HAZARD_RADIUS, 1.5, 2.5);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 60, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: all applied effects expire within 100 ticks.
    }
}
