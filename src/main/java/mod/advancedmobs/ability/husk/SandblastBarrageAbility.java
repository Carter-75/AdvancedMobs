package mod.advancedmobs.ability.husk;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Sandblast Barrage (Husk ability 1 of 5, attack).
 *
 * WHAT IT DOES: the husk exhales a horizontal storm of grit in a wide
 * cone in front of it: pillars of sand dust and tan grit blast forward
 * while loose sand sheets off the top of the blast. Enemies caught in
 * the cone take typed damage and are blinded by grit in their eyes,
 * stumbling (BLINDNESS blocks sprinting and critical hits) while the
 * husk keeps walking in on them under its normal AI.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the husk's
 *   current target within 10 blocks (distance squared 100). The blast
 *   reaches 7.5 blocks, so a target a little beyond the cone can still
 *   walk into the opening pulses.
 * - Direction: at start() the cone axis is snapshotted as the
 *   horizontal unit vector from the husk toward its target (the husk's
 *   look vector if the target is somehow already gone). The cone does
 *   NOT track the target afterwards: it is one exhale in the direction
 *   the husk was facing, and the husk's own movement is untouched, so
 *   it closes in along whatever path its AI picks.
 * - Duration: exactly 60 ticks (3 seconds) from start().
 * - Damage pulse every 10 ticks (ticks 10, 20, 30, 40, 50, 60; 6
 *   pulses): enemies (HuskAbilitySupport rules) inside the cone take
 *   3.0 points of typed mob-attack damage (DamageSources.mobAttack,
 *   so it reads as the husk's own attack, not an environmental hit)
 *   and BLINDNESS amplifier 0 for 40 ticks, refreshed by the next
 *   pulse, so a victim who stays in the blast stays blind until 40
 *   ticks after the last pulse that caught it. Standing in the full
 *   barrage is 18 damage total; stepping out of the cone ends it.
 * - Cone test (per candidate from one bounded box query around the
 *   point 3.5 blocks ahead of the husk, radius 3.4): the candidate's
 *   horizontal offset from the husk must be at most 7.5 blocks, the
 *   dot product of its normalized offset with the cone axis must be
 *   at least 0.70 (a cone about 45 degrees wide either side of the
 *   axis), and its feet must be between 1 below and 3 above the
 *   husk's feet. Candidates behind the husk or far off-axis are
 *   skipped by arithmetic, never by extra queries.
 *
 * WHAT IT LOOKS LIKE: every 2 ticks the blast emits at three points
 * along the axis (1.5, 3.5, and 5.5 blocks ahead of the husk, at
 * chest height 1.2 above its feet): 1 DUST_PILLAR of sand and 2 tan
 * DUST motes (color 0.76, 0.66, 0.44, scale 1.2) per point. Every 4
 * ticks, 2 FALLING_DUST of sand sheet off the top of the blast at the
 * middle point, 2.2 above the husk's feet. Victims show the vanilla
 * BLINDNESS effect sparkles while blinded.
 *
 * PERFORMANCE: tick() returns immediately on odd ticks. Even ticks do
 * particle sends only, from pre-built static particle options. The
 * damage pulse runs one bounded entity query (6.8 by 4 by 6.8 box)
 * every 10 ticks plus a dot product per candidate. No state is read
 * or written per tick except the two stored direction doubles.
 * Particle budget per activation: 30 emissions times 9 (3 pillars
 * plus 6 dust) = 270, plus 15 times 2 falling dust = 30, total at
 * most 300 particles.
 *
 * CLEANUP: end() has nothing to undo. No attribute modifiers, no
 * spawned entities, no block changes; BLINDNESS lasts at most 40
 * ticks past the last pulse and expires on its own.
 *
 * State tag keys: "fx", "fz" (cone axis unit vector, horizontal).
 */
public final class SandblastBarrageAbility implements MobAbility {

    private static final int DURATION_TICKS = 60;
    private static final int PULSE_INTERVAL = 10;
    private static final float BLAST_DAMAGE = 3.0f;
    private static final int BLIND_TICKS = 40;
    private static final double CONE_RANGE = 7.5;
    private static final double CONE_MIN_DOT = 0.70;
    private static final BlockParticleOption SAND_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.SAND.defaultBlockState());
    private static final BlockParticleOption SAND_FALLING =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());
    // 26.2 DustParticleOptions takes an RGB int color: 0.76, 0.66, 0.44 as 0xC2A870.
    private static final DustParticleOptions TAN_DUST = new DustParticleOptions(0xC2A870, 1.2f);
    private static final double[] EMIT_DISTANCES = {1.5, 3.5, 5.5};

    @Override
    public String id() {
        return "sandblast_barrage";
    }

    @Override
    public String displayName() {
        return "Sandblast Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        double fx, fz;
        if (target != null) {
            fx = target.getX() - mob.getX();
            fz = target.getZ() - mob.getZ();
        } else {
            fx = mob.getLookAngle().x;
            fz = mob.getLookAngle().z;
        }
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1.0e-4) {
            fx = 1.0;
            fz = 0.0;
            len = 1.0;
        }
        CompoundTag state = ctx.state();
        state.putDouble("fx", fx / len);
        state.putDouble("fz", fz / len);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 2 != 0) {
            return; // Cheap early-out: the blast emits every 2 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        double fx = state.getDoubleOr("fx", 1.0);
        double fz = state.getDoubleOr("fz", 0.0);
        double baseX = mob.getX(), baseY = mob.getY(), baseZ = mob.getZ();

        // Blast visuals: grit pillars and dust at three depths of the cone.
        for (double distance : EMIT_DISTANCES) {
            double x = baseX + fx * distance;
            double z = baseZ + fz * distance;
            level.sendParticles(SAND_PILLAR, x, baseY + 1.2, z, 1, 0.3, 0.4, 0.3, 0.02);
            level.sendParticles(TAN_DUST, x, baseY + 1.2, z, 2, 0.4, 0.4, 0.4, 0.03);
        }
        if (ticks % 4 == 0) {
            // Loose sand sheeting off the top of the blast.
            level.sendParticles(SAND_FALLING, baseX + fx * 3.5, baseY + 2.2, baseZ + fz * 3.5,
                2, 0.8, 0.2, 0.8, 0.02);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx, level, fx, fz);
        }
    }

    /** One damage pulse: enemies inside the cone take damage and are blinded. */
    private void pulse(AbilityContext ctx, ServerLevel level, double fx, double fz) {
        Mob mob = ctx.mob();
        double centerX = mob.getX() + fx * 3.5;
        double centerZ = mob.getZ() + fz * 3.5;
        List<LivingEntity> candidates = HuskAbilitySupport.enemiesInBox(
            ctx, centerX, mob.getY(), centerZ, 3.4, 1.0, 3.0);
        for (LivingEntity victim : candidates) {
            double ox = victim.getX() - mob.getX();
            double oz = victim.getZ() - mob.getZ();
            double horizontal = Math.sqrt(ox * ox + oz * oz);
            if (horizontal > CONE_RANGE) {
                continue;
            }
            if (horizontal > 1.0e-4 && (ox * fx + oz * fz) / horizontal < CONE_MIN_DOT) {
                continue; // Off-axis: outside the cone.
            }
            victim.hurtServer(level, level.damageSources().mobAttack(mob), BLAST_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: no modifiers, no spawns, no block changes,
        // and BLINDNESS expires on its own within 40 ticks.
    }
}
