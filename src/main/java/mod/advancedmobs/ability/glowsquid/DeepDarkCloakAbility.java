package mod.advancedmobs.ability.glowsquid;

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
 * Deep Dark Cloak (Glow Squid ability 4 of 5,
 * defense).
 *
 * WHAT IT DOES: the glow squid kills its lights and
 * pulls the dark in with it. A reverse pulse of
 * plain squid ink bursts outward and true darkness
 * settles over the water: the squid itself vanishes
 * into invisibility while every enemy nearby is
 * wrapped in the darkness effect, their detection
 * range shrunk to almost nothing in the black, and
 * the squid slips away unseen.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 12
 *   blocks (distance squared 144), plus the squid in
 *   water.
 * - The reverse pulse (start()): 30 SQUID_INK
 *   particles spread over a 1.5 block sphere plus 8
 *   BUBBLE, with GLOW_SQUID_SQUIRT at volume 1.5,
 *   pitch 0.7 (the deep, lights-out squirt). One
 *   bounded query, radius 6.0 around the caster,
 *   vertical reach 3.0 both ways: every enemy (the
 *   package enemy rule) gains DARKNESS for 160 ticks
 *   and BLINDNESS for 60 ticks. The caster gains
 *   INVISIBILITY for 220 ticks, removed in end().
 *   The escape direction (horizontal, directly away
 *   from the target, facing fallback like Ink Nova)
 *   and the burst point are recorded in the state
 *   tag.
 * - The slip away (ticks 1 to 20): the caster is
 *   steered along the escape direction at 0.5 blocks
 *   per tick, out of the dark it made.
 * - The settling dark (ticks 1 to 200): 2 SQUID_INK
 *   every 4 ticks at the recorded burst point, the
 *   black hanging where the lights went out.
 * - The detection shrink (every 20 ticks): one
 *   bounded query, radius 8.0 around the caster:
 *   every enemy Mob currently targeting the caster
 *   loses it as a target. This is the design's
 *   follow_range interaction in code, on top of the
 *   vanilla rule that already shrinks other mobs'
 *   detection range against an invisible entity:
 *   between the two, enemies in the dark effectively
 *   cannot hold the squid in mind at all. Players
 *   are never target-cleared (a player's targeting
 *   is their own eyes, already blinded and darkened
 *   by the effects).
 * - Duration: 200 ticks, hard finish.
 *
 * WHAT IT LOOKS LIKE: the brightest thing in the
 * water suddenly going black, a globe of ink and
 * pulsing darkness where it was, and nothing at all
 * where it went.
 *
 * PERFORMANCE: one bounded query in start() and one
 * every 20 ticks; the slip is one steering write per
 * tick for 20 ticks; the settle is 2 sends every 4
 * ticks. Particle budget per activation: 38 at
 * start, at most 100 settle, total at most 138.
 *
 * TERRAIN: this ability changes no blocks; the
 * cloak is particles and effects only, so the
 * mobGriefing gate does not apply.
 *
 * CLEANUP: end() removes INVISIBILITY from the
 * caster. Enemy darkness and blindness are victim
 * debuffs on their own clocks and are left to
 * expire (the Bray of Doom precedent).
 *
 * State tag keys: "ex", "ez" (the locked escape
 * direction), "px", "py", "pz" (the burst point).
 */
public final class DeepDarkCloakAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int SLIP_TICKS = 20;
    private static final double GLOBE_RADIUS = 6.0;
    private static final double FORGET_RADIUS = 8.0;

    @Override
    public String id() {
        return "deep_dark_cloak";
    }

    @Override
    public String displayName() {
        return "Deep Dark Cloak";
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
            awayX = 1.0;
            awayZ = 0.0;
            length = 1.0;
        }
        state.putDouble("ex", awayX / length);
        state.putDouble("ez", awayZ / length);

        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 220, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.GLOW_SQUID_SQUIRT, SoundSource.HOSTILE, 1.5f, 0.7f);
        level.sendParticles(ParticleTypes.SQUID_INK, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            30, 1.5, 1.2, 1.5, 0.05);
        level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            8, 1.0, 0.8, 1.0, 0.05);

        // The dark settles over everything hostile nearby.
        List<LivingEntity> caught = GlowSquidAbilitySupport.enemiesAroundSelf(ctx, GLOBE_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : caught) {
            victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 160, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
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

        if (ticks <= SLIP_TICKS) {
            // Slip away along the escape line while the dark holds.
            GlowSquidAbilitySupport.steerToward(caster,
                caster.getX() + state.getDoubleOr("ex", 1.0) * 4.0,
                caster.getY(),
                caster.getZ() + state.getDoubleOr("ez", 0.0) * 4.0, 0.5);
        }

        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.SQUID_INK,
                state.getDoubleOr("px", caster.getX()),
                state.getDoubleOr("py", caster.getY()) + 0.3,
                state.getDoubleOr("pz", caster.getZ()),
                2, 1.2, 0.9, 1.2, 0.03);
        }

        if (ticks % 20 == 0) {
            // The detection shrink: in the dark, nothing holds the squid.
            List<LivingEntity> nearby = GlowSquidAbilitySupport.enemiesAroundSelf(ctx, FORGET_RADIUS, 4.0, 4.0);
            for (LivingEntity enemy : nearby) {
                if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                    enemyMob.setTarget(null);
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.INVISIBILITY);
    }
}
