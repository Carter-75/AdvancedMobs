package mod.advancedmobs.ability.cod;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
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
 * Silver Flash School (Cod ability 4 of 5, defense).
 *
 * WHAT IT DOES: the whole school turns sideways at
 * once and the water whites out in a bright silver
 * flash. Enemies facing the flash are blinded for two
 * seconds and lose track of the fighter, and in the
 * same instant the real cod slips away under
 * invisibility, darting off at speed while its
 * schoolmates scatter in every direction. By the time
 * eyes clear, nobody knows which fish was the threat.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cod's current target within 12 blocks
 *   (distance squared 144), plus the cod in water (the
 *   flash is a swimming school's trick).
 * - The flash (start(), one bounded query, radius 6.0
 *   around the caster, vertical reach 2.5 both ways):
 *   3 FLASH particles at the caster's position, color
 *   ARGB 0xFFD9E2EC, a bright silver white (the
 *   design's flash line; FLASH is a color particle in
 *   26.2 and the option is built once as a static
 *   final). Every enemy (the package enemy rule) in
 *   the query whose own look direction points at the
 *   caster (the dot product of its look vector with
 *   the normalized direction to the caster is at least
 *   0.5, a 60 degree half-angle cone: it was facing the
 *   flash) gains BLINDNESS for 40 ticks (the design's
 *   couple of seconds), and any enemy Mob in the query
 *   that had the caster as its target loses it
 *   (setTarget(null)): blinded or not, the school has
 *   broken everyone's lock, the design's "lose track of
 *   which fish is the fighter". FISH_SWIM plays at
 *   volume 1.2, pitch 1.6, a startled flick.
 * - The slip away (also start()): the caster gains
 *   INVISIBILITY for 100 ticks (removed in end()) and
 *   is darted directly away from its target at 1.2
 *   blocks per tick horizontally with a 0.15 lift,
 *   hurtMarked so the dart syncs; 6 POOF particles mark
 *   the spot it vanished from. Allied fish and squid
 *   caught in the same query (the package buff rule)
 *   scatter: each gets a velocity burst of 0.8 blocks
 *   per tick directly away from the caster plus a
 *   random sideways 0.3, with 2 POOF at its position:
 *   the schoolmates scattering in the design's text.
 * - Duration: 100 ticks (5 seconds), the invisibility
 *   window. tick() does nothing: the cod's own vanilla
 *   movement carries the escape, and end() removes the
 *   invisibility exactly on time. If the caster is
 *   terminated early, end() still runs and strips it.
 *
 * WHAT IT LOOKS LIKE: one white silver blink, every
 * facing enemy clutching at blinded eyes, a puff where
 * the cod was, and fish bursting outward in every
 * direction while the real fighter is simply not there
 * anymore.
 *
 * PERFORMANCE: everything happens in start(): two
 * bounded queries over the same radius (enemies and
 * buff targets) and a fixed handful of particles.
 * tick() is empty. Particle budget per activation: 3
 * flash plus 6 poof at the caster plus at most 2 poof
 * per scattering schoolmate (bounded by the query;
 * realistically a handful), total at most 45 in any
 * plausible fight.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes INVISIBILITY from the caster.
 * Enemy blindness rides its own 40-tick clock out;
 * cleared targets stay cleared (retargeting is the
 * enemy's own AI's problem, the mirage precedent).
 *
 * State tag keys: none of its own (the activation is
 * one-shot in start(); the framework's tick counter
 * drives the duration).
 */
public final class SilverFlashSchoolAbility implements MobAbility {

    private static final int DURATION_TICKS = 100;
    private static final double FLASH_RADIUS = 6.0;
    private static final int BLIND_TICKS = 40;
    private static final double FACING_DOT = 0.5;
    /** The design's bright silver flash, ARGB. Built once; particle options are immutable. */
    private static final ColorParticleOption SILVER_FLASH =
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFFD9E2EC);

    @Override
    public String id() {
        return "silver_flash_school";
    }

    @Override
    public String displayName() {
        return "Silver Flash School";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level == null) {
            return;
        }
        LivingEntity target = ctx.target();

        // The flash itself.
        level.sendParticles(SILVER_FLASH, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            3, 0.4, 0.3, 0.4, 0.0);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.2f, 1.6f);

        // Blinding and lock-breaking: enemies that faced the flash go blind,
        // and every enemy Mob tracking the caster loses it either way.
        List<LivingEntity> enemies = CodAbilitySupport.enemiesAroundSelf(ctx, FLASH_RADIUS, 2.5, 2.5);
        for (LivingEntity enemy : enemies) {
            Vec3 look = enemy.getLookAngle();
            double toX = caster.getX() - enemy.getX();
            double toY = (caster.getY() + 0.3) - (enemy.getY() + enemy.getEyeHeight() * 0.5);
            double toZ = caster.getZ() - enemy.getZ();
            double toLength = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
            if (toLength > 0.001) {
                double dot = (look.x * toX + look.y * toY + look.z * toZ) / toLength;
                if (dot >= FACING_DOT) {
                    enemy.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0));
                }
            }
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                enemyMob.setTarget(null);
            }
        }

        // The slip away: invisibility plus a dart directly away from the target.
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, DURATION_TICKS, 0, false, false, false));
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            6, 0.35, 0.3, 0.35, 0.03);
        double awayX = caster.getX() - (target != null ? target.getX() : caster.getX() - 1.0);
        double awayZ = caster.getZ() - (target != null ? target.getZ() : caster.getZ());
        double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLength < 0.001) {
            awayX = 1.0;
            awayZ = 0.0;
            awayLength = 1.0;
        }
        caster.setDeltaMovement(awayX / awayLength * 1.2, 0.15, awayZ / awayLength * 1.2);
        caster.hurtMarked = true;

        // The scatter: schoolmates burst outward from the caster.
        List<LivingEntity> schoolmates = CodAbilitySupport.livingInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), FLASH_RADIUS, 2.5, 2.5,
            e -> CodAbilitySupport.isBuffTarget(ctx, e));
        for (LivingEntity mate : schoolmates) {
            double outX = mate.getX() - caster.getX();
            double outZ = mate.getZ() - caster.getZ();
            double outLength = Math.sqrt(outX * outX + outZ * outZ);
            if (outLength < 0.001) {
                outX = ctx.random().nextDouble() - 0.5;
                outZ = ctx.random().nextDouble() - 0.5;
                outLength = Math.sqrt(outX * outX + outZ * outZ) + 0.001;
            }
            double side = (ctx.random().nextDouble() - 0.5) * 0.6;
            mate.setDeltaMovement(outX / outLength * 0.8 - outZ / outLength * side, 0.1,
                outZ / outLength * 0.8 + outX / outLength * side);
            mate.hurtMarked = true;
            level.sendParticles(ParticleTypes.POOF, mate.getX(), mate.getY() + 0.2, mate.getZ(),
                2, 0.25, 0.2, 0.25, 0.03);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        // Nothing per tick: the escape is the start() dart and the
        // invisibility window, which end() closes.
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
