package mod.advancedmobs.ability.breeze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Cyclone Form (Breeze ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the breeze pulls its rods in and stretches into
 * a tall spinning cyclone, cloud and gust streaming off it in a
 * constant spiral. In this form it moves faster, shoots faster,
 * falls gently, periodically lifts on a levitation pulse, and
 * every few seconds sheds a gust ring that shoves melee
 * attackers back before they can connect.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   breeze's current target within 14 blocks (distance squared
 *   196).
 * - Duration: exactly 300 ticks (15 seconds).
 * - Speed: MOVEMENT_SPEED +0.30 ADD_MULTIPLIED_BASE (30 percent
 *   faster) and FLYING_SPEED +0.02 ADD_VALUE for the whole form,
 *   both removed in end(). The breeze's attribute set may not
 *   carry FLYING_SPEED; the shared helper tolerates a missing
 *   instance, in which case that part of the design's speed is
 *   simply not felt (documented, the Frenzy Gore precedent).
 * - Reduced fall damage: FALL_DAMAGE_MULTIPLIER -0.5
 *   ADD_MULTIPLIED_BASE for the whole form (fall damage halved),
 *   removed in end().
 * - Gentle falling: SLOW_FALLING refreshed to 30 ticks every 20
 *   ticks. Lift pulses: LEVITATION amplifier 0 for 12 ticks at
 *   ticks 1, 75, 150, and 225, so the cyclone periodically
 *   rises and drifts. Both effects are removed in end().
 * - Faster shooting: one bonus real BreezeWindCharge at the
 *   target's live chest every 60 ticks (ticks 60, 120, 180,
 *   240), speed 1.6, on top of the breeze's own brain-driven
 *   attacks (the code cannot shorten the brain's attack
 *   cooldown from here, so the extra shots are the collapsed
 *   cooldown in code, the Grand Hex Form precedent). A shot is
 *   skipped when the target is dead or gone that tick.
 * - Gust rings: every 30 ticks (ticks 30, 60, ..., 300), one
 *   bounded query at radius 2.6: each enemy takes no damage but
 *   is shoved 1.05 blocks per tick horizontal away from the
 *   breeze and 0.35 up (hurtMarked set), with 6
 *   GUST_EMITTER_SMALL particles at the breeze's waist and a
 *   BREEZE_WHIRL. This is the design's "pushes melee attackers
 *   back before they can connect": a melee attacker has to
 *   cross the ring's radius to swing, and the ring fires twice
 *   inside a typical approach.
 * - end(): all three attribute modifiers are removed by id and
 *   both effects are removed. If the breeze somehow had
 *   SLOW_FALLING from another source it loses it here, an
 *   accepted edge case shared with every fixed-duration effect
 *   in this system.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: every 2 ticks, 2 GUST and 1
 * CLOUD particle stream off the body on a spiral (angle
 * advancing 0.35 radians per draw, radius 0.7, height cycling
 * 0.3 to 1.5), so the form reads as a spinning column of wind.
 * BREEZE_WHIRL at the start and at every gust ring; BREEZE_SHOOT
 * for each bonus charge.
 *
 * PERFORMANCE: per tick the cost is two modulo checks; the
 * spiral draw runs every 2 ticks (3 particles), the effect
 * refresh every 20, the ring query every 30, the bonus shot
 * every 60. No other queries, no per-tick allocations (the
 * particle types are shared constants; modifiers are built once
 * in start()). Particle budget per activation: spiral 150 draws
 * times 3 = 450, rings 10 times 6 = 60, total at most 510.
 *
 * State tag keys: none beyond the framework's. Every value this
 * ability needs is a constant or read live from the caster.
 */
public final class CycloneFormAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cyclone_form_speed");
    private static final Identifier FLYING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cyclone_form_flying");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cyclone_form_fall");

    @Override
    public String id() {
        return "cyclone_form";
    }

    @Override
    public String displayName() {
        return "Cyclone Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        BreezeAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        BreezeAbilitySupport.addModifier(mob, Attributes.FLYING_SPEED,
            new AttributeModifier(FLYING_ID, 0.02, AttributeModifier.Operation.ADD_VALUE));
        BreezeAbilitySupport.addModifier(mob, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 30, 0));
        ctx.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        // The streaming spiral.
        if (ticks % 2 == 0) {
            double angle = ticks * 0.35;
            double px = mob.getX() + Math.cos(angle) * 0.7;
            double pz = mob.getZ() + Math.sin(angle) * 0.7;
            double py = mob.getY() + 0.3 + (ticks % 24) / 24.0 * 1.2;
            level.sendParticles(ParticleTypes.GUST, px, py, pz, 2, 0.1, 0.1, 0.1, 0.02);
            level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 1, 0.1, 0.1, 0.1, 0.01);
        }

        if (ticks % 20 == 0) {
            mob.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 30, 0));
        }

        // Lift pulses.
        if (ticks == 1 || ticks == 75 || ticks == 150 || ticks == 225) {
            mob.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 12, 0));
        }

        // The gust ring.
        if (ticks % 30 == 0) {
            shedRing(ctx, level);
        }

        // The bonus shot.
        if (ticks % 60 == 0) {
            LivingEntity target = ctx.target();
            if (target != null) {
                Vec3 from = mob.getEyePosition();
                Vec3 aim = new Vec3(target.getX(), target.getEyeY() - 0.4, target.getZ());
                if (BreezeAbilitySupport.fireWindCharge(ctx, from, aim.subtract(from), 1.6) != null) {
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                        SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 0.9f, 1.05f);
                }
            }
        }
    }

    /** One gust ring: the radial shove described in the class comment. */
    private void shedRing(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = BreezeAbilitySupport.enemiesAroundSelf(ctx, 2.6, 2.0, 2.0);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - mob.getX();
            double dz = victim.getZ() - mob.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double scale = horizontal > 1.0e-4 ? 1.05 / horizontal : 0.0;
            Vec3 velocity = victim.getDeltaMovement();
            victim.setDeltaMovement(velocity.x + dx * scale, 0.35, velocity.z + dz * scale);
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL,
            mob.getX(), mob.getY() + 0.8, mob.getZ(), 6, 0.6, 0.3, 0.6, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 0.8f, 1.1f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        BreezeAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        BreezeAbilitySupport.removeModifier(mob, Attributes.FLYING_SPEED, FLYING_ID);
        BreezeAbilitySupport.removeModifier(mob, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
        mob.removeEffect(MobEffects.SLOW_FALLING);
        mob.removeEffect(MobEffects.LEVITATION);
    }
}
