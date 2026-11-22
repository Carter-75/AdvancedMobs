package mod.advancedmobs.ability.pufferfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Deflation Dash (Pufferfish ability 5 of 5, mobility /
 * defense).
 *
 * WHAT IT DOES: the pufferfish blows out all its water
 * at once in a bubble and splash jet and rockets
 * backward, small again and suddenly very fast. The
 * jet itself shoves back whatever was in front of it,
 * and the fish spends the next half second of
 * confusion vanishing around cover at full swim speed.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the pufferfish's current target within
 *   14 blocks (distance squared 196), plus the
 *   pufferfish in water (the jet needs water to blow
 *   out). No inflation is required: the dash forces
 *   the blow-out from whatever state the fish is in.
 * - The blow-out (start()): the escape direction is
 *   locked once, horizontal, pointing directly away
 *   from the target ("dx", "dz", normalized). The
 *   real synced puff state is forced to
 *   Pufferfish.STATE_SMALL, the design's "small
 *   again": that is the scale reset, done through the
 *   vanilla state rather than a modifier because no
 *   other ability can be running at the same time, so
 *   there is never a scale modifier of this mod's to
 *   clear. PUFFER_FISH_BLOW_OUT plays at volume 1.2,
 *   pitch 1.1, and the jet bursts on the fish's front
 *   side (toward the target it is escaping): 14
 *   BUBBLE plus 10 SPLASH at 1.2 blocks toward the
 *   target.
 * - The jet shove (start(), one bounded query,
 *   radius 4.0 around the fish, vertical reach 1.5
 *   both ways): every enemy (the package enemy rule)
 *   in the front hemisphere (the horizontal dot
 *   product of the fish-to-enemy direction against
 *   the fish-to-target direction is above 0, so
 *   enemies behind the fish are untouched) is shoved
 *   directly away from the fish at 1.4 blocks per
 *   tick horizontal and 0.3 up, hurtMarked set. The
 *   shove deals no damage: it is the design's
 *   attack_knockback style push delivered as a pure
 *   velocity write, and the class comment is the
 *   record of that choice. PUFFER_FISH_FLOP plays at
 *   each shoved enemy at volume 0.8, pitch 1.3, the
 *   slap of the jet hitting them.
 * - The rocket (ticks 1 to 10): each tick the fish's
 *   velocity is written along the locked escape
 *   direction at 1.15 blocks per tick, vertical held
 *   at the fish's current vertical velocity so it
 *   neither sinks nor breaches under the drive. The
 *   trail is 2 BUBBLE plus 1 SPLASH per tick at the
 *   fish's front, the jet still streaming. After tick
 *   10 the drive stops and the fish swims free.
 * - The speed (start() to end()): the fish gains
 *   SPEED I for 100 ticks (the design's speed effect
 *   on self; on a fish it rides MOVEMENT_SPEED the
 *   vanilla way) and a transient WATER_MOVEMENT_
 *   EFFICIENCY +1.0 add_value modifier (id
 *   advancedmobs:deflation_dash_efficiency; the
 *   attribute's maximum, so none of the dash speed is
 *   lost to the water). end() removes the modifier by
 *   id and removes the SPEED effect, so the burst of
 *   speed is exactly the activation's 80 ticks even
 *   though the effect was applied for 100.
 * - Duration: 80 ticks (4 seconds), hard finish. If
 *   the fish leaves the water mid-dash the drive
 *   stops (no velocity writes) but the clock runs
 *   out the same way.
 * - end() does not restore the puff state: the fish
 *   ends the dash small, which is vanilla's resting
 *   state, and the vanilla puff goal re-inflates it
 *   later on its own schedule if danger stays close.
 *
 * WHAT IT LOOKS LIKE: a sudden jet of bubbles and
 * splash blasting forward off the fish, enemies in
 * front of it knocked tumbling backward, and the
 * fish itself, suddenly small, already a shrinking
 * streak of bubbles around the nearest cover.
 *
 * PERFORMANCE: start() is one bounded radius-4.0
 * query plus the fixed bursts. The rocket is one
 * velocity write and 3 particles per tick for 10
 * ticks; ticks 11 to 80 do nothing at all. Particle
 * budget per activation: jet 24, trail 10 times 3 =
 * 30, total at most 54.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes the efficiency modifier by
 * id and the SPEED effect. The jet shove is a
 * one-shot velocity write and leaves nothing behind.
 *
 * State tag keys: "dx", "dz" (the locked escape
 * direction, normalized).
 */
public final class DeflationDashAbility implements MobAbility {

    private static final int DURATION_TICKS = 80;
    private static final int ROCKET_TICKS = 10;
    private static final double ROCKET_SPEED = 1.15;
    private static final double JET_RADIUS = 4.0;
    private static final double JET_SHOVE = 1.4;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "deflation_dash_efficiency");

    @Override
    public String id() {
        return "deflation_dash";
    }

    @Override
    public String displayName() {
        return "Deflation Dash";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();

        // The escape line: directly away from the target.
        double dx = 0.0;
        double dz = -1.0;
        double towardX = 1.0;
        double towardZ = 0.0;
        if (target != null) {
            towardX = target.getX() - caster.getX();
            towardZ = target.getZ() - caster.getZ();
            double towardLength = Math.sqrt(towardX * towardX + towardZ * towardZ);
            if (towardLength > 0.001) {
                towardX /= towardLength;
                towardZ /= towardLength;
                dx = -towardX;
                dz = -towardZ;
            }
        }
        if (Math.sqrt(dx * dx + dz * dz) < 0.001) {
            Vec3 look = caster.getLookAngle();
            dx = -look.x;
            dz = -look.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                dx = 0.0;
                dz = -1.0;
                length = 1.0;
            }
            dx /= length;
            dz /= length;
            towardX = -dx;
            towardZ = -dz;
        }
        state.putDouble("dx", dx);
        state.putDouble("dz", dz);

        // Small again, and fast (see the class comment).
        if (caster instanceof Pufferfish pufferfish) {
            pufferfish.setPuffState(Pufferfish.STATE_SMALL);
        }
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 0));
        PufferfishAbilitySupport.addModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY,
            new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.HOSTILE, 1.2f, 1.1f);
        double jetX = caster.getX() + towardX * 1.2;
        double jetZ = caster.getZ() + towardZ * 1.2;
        level.sendParticles(ParticleTypes.BUBBLE, jetX, caster.getY() + 0.3, jetZ,
            14, 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(ParticleTypes.SPLASH, jetX, caster.getY() + 0.3, jetZ,
            10, 0.5, 0.5, 0.5, 0.1);

        // The jet shove: front hemisphere only.
        List<LivingEntity> victims = PufferfishAbilitySupport.enemiesAroundSelf(ctx, JET_RADIUS, 1.5, 1.5);
        for (LivingEntity victim : victims) {
            double awayX = victim.getX() - caster.getX();
            double awayZ = victim.getZ() - caster.getZ();
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 1.0e-4) {
                continue;
            }
            awayX /= awayLength;
            awayZ /= awayLength;
            if (awayX * towardX + awayZ * towardZ <= 0.0) {
                continue;
            }
            victim.setDeltaMovement(awayX * JET_SHOVE, 0.3, awayZ * JET_SHOVE);
            victim.hurtMarked = true;
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.PUFFER_FISH_FLOP, SoundSource.HOSTILE, 0.8f, 1.3f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks > ROCKET_TICKS || !caster.isInWater()) {
            return;
        }
        CompoundTag state = ctx.state();
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", -1.0);
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * ROCKET_SPEED, velocity.y, dz * ROCKET_SPEED);
        caster.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The jet streams off the front (opposite the
            // escape direction) while the rocket burns.
            level.sendParticles(ParticleTypes.BUBBLE,
                caster.getX() - dx * 0.8, caster.getY() + 0.2, caster.getZ() - dz * 0.8,
                2, 0.25, 0.25, 0.25, 0.05);
            level.sendParticles(ParticleTypes.SPLASH,
                caster.getX() - dx * 0.8, caster.getY() + 0.2, caster.getZ() - dz * 0.8,
                1, 0.25, 0.25, 0.25, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PufferfishAbilitySupport.removeModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY, EFFICIENCY_ID);
        caster.removeEffect(MobEffects.SPEED);
    }
}
