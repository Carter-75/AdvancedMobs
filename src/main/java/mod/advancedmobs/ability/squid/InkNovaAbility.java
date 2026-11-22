package mod.advancedmobs.ability.squid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ink Nova (Squid ability 1 of 5, defense / control).
 *
 * WHAT IT DOES: the squid blows its whole ink sac at
 * once. A dark globe of squid_ink floods the water
 * around it, everything hostile caught inside is
 * blinded and wrapped in darkness, and while they
 * swing at nothing the squid itself jets straight
 * out the far side of the cloud at burst speed and
 * is gone.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 12
 *   blocks (distance squared 144), plus the squid in
 *   water (ink only clouds water; a beached squid
 *   never novas).
 * - The detonation (start()): the escape direction
 *   is locked as the horizontal unit vector pointing
 *   directly away from the target (the caster's own
 *   horizontal facing when there is somehow no
 *   target, or when the target is exactly overhead),
 *   stored in the state tag. One bounded query,
 *   radius 5.0 around the caster, vertical reach 3.0
 *   both ways: every enemy (the package enemy rule)
 *   gains BLINDNESS for 100 ticks and DARKNESS for
 *   100 ticks (the design's two effect lines: vision
 *   gone and the pulsing dark on top). The burst
 *   itself is 40 SQUID_INK particles spread over a
 *   1.5 block sphere plus 10 BUBBLE, with
 *   SQUID_SQUIRT at volume 1.5, pitch 0.9. The
 *   caster gains SPEED I for 40 ticks (the design's
 *   speed effect line), removed in end().
 * - The jet (ticks 1 to 8): the caster's velocity is
 *   written along the locked escape direction at 1.1
 *   blocks per tick with a 0.05 upward component,
 *   hurtMarked set so it syncs: about 9 blocks of
 *   clean water between the squid and the fight in
 *   under half a second. 3 SQUID_INK trail per tick
 *   behind it, the near edge of the globe it left.
 * - The settling globe (ticks 9 to 30): 2 SQUID_INK
 *   every 2 ticks at the detonation point (recorded
 *   in the state tag), the cloud hanging and thinning
 *   where it burst while the squid is already gone.
 *   No further effects are applied; the blindness
 *   and darkness ride their own 100-tick clocks.
 * - Duration: 30 ticks, hard finish.
 *
 * WHAT IT LOOKS LIKE: a black sphere blooming in the
 * water, enemies flailing blind inside it, and a
 * dark streak jetting out the far side.
 *
 * PERFORMANCE: one bounded query in start(); the jet
 * is one velocity write and 3 particle sends per
 * tick for 8 ticks; the settle is 2 sends every 2
 * ticks. No per-tick queries. Particle budget per
 * activation: 50 at start, 24 jet trail, 22 settle,
 * total at most 96.
 *
 * TERRAIN: this ability changes no blocks; the ink
 * is particles and effects only, so the mobGriefing
 * gate does not apply.
 *
 * CLEANUP: end() removes the SPEED effect from the
 * caster. Enemy blindness and darkness are victim
 * debuffs on their own clocks and are left to expire
 * (the Bray of Doom precedent).
 *
 * State tag keys: "ex", "ez" (the locked escape
 * direction), "px", "py", "pz" (the detonation
 * point).
 */
public final class InkNovaAbility implements MobAbility {

    private static final int DURATION_TICKS = 30;
    private static final int JET_TICKS = 8;
    private static final double JET_SPEED = 1.1;
    private static final double GLOBE_RADIUS = 5.0;

    @Override
    public String id() {
        return "ink_nova";
    }

    @Override
    public String displayName() {
        return "Ink Nova";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("px", caster.getX());
        state.putDouble("py", caster.getY());
        state.putDouble("pz", caster.getZ());

        // The escape line: straight away from the target, horizontally.
        LivingEntity target = ctx.target();
        double awayX;
        double awayZ;
        if (target != null) {
            awayX = caster.getX() - target.getX();
            awayZ = caster.getZ() - target.getZ();
        } else {
            Vec3 look = caster.getLookAngle();
            awayX = -look.x;
            awayZ = -look.z;
        }
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            awayX = -look.x;
            awayZ = -look.z;
            length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 0.001) {
                awayX = 1.0;
                awayZ = 0.0;
                length = 1.0;
            }
        }
        state.putDouble("ex", awayX / length);
        state.putDouble("ez", awayZ / length);

        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 1.5f, 0.9f);
        level.sendParticles(ParticleTypes.SQUID_INK, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            40, 1.5, 1.2, 1.5, 0.05);
        level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            10, 1.0, 0.8, 1.0, 0.05);

        // The globe: blind and darken everything hostile caught inside.
        List<LivingEntity> caught = SquidAbilitySupport.enemiesAroundSelf(ctx, GLOBE_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : caught) {
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0));
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        if (ticks <= JET_TICKS) {
            // The jet out the far side of the cloud.
            caster.setDeltaMovement(
                state.getDoubleOr("ex", 1.0) * JET_SPEED, 0.05,
                state.getDoubleOr("ez", 0.0) * JET_SPEED);
            caster.hurtMarked = true;
            level.sendParticles(ParticleTypes.SQUID_INK, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                3, 0.3, 0.3, 0.3, 0.03);
            return;
        }

        // The settling globe at the detonation point.
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.SQUID_INK,
                state.getDoubleOr("px", caster.getX()),
                state.getDoubleOr("py", caster.getY()) + 0.3,
                state.getDoubleOr("pz", caster.getZ()),
                2, 1.2, 0.9, 1.2, 0.03);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.SPEED);
    }
}
