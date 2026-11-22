package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Gorged Mite (Endermite ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the mite feeds, and feeding changes it. Every
 * bite it lands while this ability runs makes it swell one
 * visible step, violet ENTITY_EFFECT steam hissing off its
 * growing shell, until the ankle biter stands at twice its
 * birth size. Its bite grows with its body, and at full gorge
 * it gains full KNOCKBACK_RESISTANCE: a tiny titan that
 * refuses to be swatted away.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mite's current target within 3.5 blocks (distance
 *   squared 12.25). Gorging starts at fang range, where the
 *   bites are.
 * - Duration: exactly 600 ticks (30 seconds) from start().
 *   When the ability ends, the gorge ends with it: all three
 *   modifiers below are removed in end() and the mite shrinks
 *   back to its birth size.
 * - Growth: the bite clock (see EndermiteAbilitySupport) is
 *   primed in start() and polled every 4 ticks. A counted bite
 *   (at least 10 ticks since the last counted one, the same
 *   spacing rule Infestation Bite uses, so one attack cooldown
 *   can never count twice) adds 1 gorge stack, up to a maximum
 *   of 10 stacks. Each stack updates two transient attribute
 *   modifiers in place (addOrUpdateTransientModifier):
 *   - SCALE (modifier id "gorged_mite_scale"): +0.1 per stack
 *     as ADD_VALUE. The mite's base scale is 1.0, so stack 10
 *     is exactly 2.0, twice its birth size (about 0.6 blocks
 *     wide and tall), as the design specifies.
 *   - ATTACK_DAMAGE (modifier id "gorged_mite_damage"): +0.3
 *     per stack as ADD_VALUE. The mite's base bite is 2.0, so
 *     a full gorge bites for 5.0 before any upgrade tree
 *     bonuses: a swollen mite hits like a real threat, not a
 *     nuisance.
 * - Full gorge: the moment the 10th stack lands, a third
 *   transient modifier is added: KNOCKBACK_RESISTANCE
 *   (modifier id "gorged_mite_knockback") +1.0 as ADD_VALUE,
 *   full knockback immunity. Below 10 stacks the mite takes
 *   knockback normally; the refusal to be swatted away is the
 *   prize for finishing the gorge.
 * - Every growth step bursts 8 violet ENTITY_EFFECT particles
 *   (color 0.62, 0.25, 0.85) off the mite's shell and plays
 *   ENDERMITE_AMBIENT at volume 0.6 with a pitch that climbs
 *   with the stack count (1.0 + 0.05 per stack): the hiss of
 *   a shell stretching.
 * - end(): all three modifiers are removed by id, whether or
 *   not full gorge was reached (removing an absent modifier
 *   is a no-op). Nothing else lingers: no effects are used.
 *
 * WHAT IT LOOKS LIKE: a mite that gets visibly bigger bite by
 * bite, ten discrete swells, each with a hiss and a boil of
 * violet steam, until a knee-high mite with a bite like a
 * zombie's is standing there not budging an inch when hit.
 *
 * PERFORMANCE: tick() returns immediately except every 4th
 * tick, where it reads two timestamps and at most one entity
 * reference off the caster: no entity scans, no allocations
 * beyond the modifier updates on a growth step. Particle
 * budget per activation: at most 10 growth steps times 8
 * particles = at most 80 particles.
 *
 * State tag keys: "hit_ts" (the bite clock timestamp, see
 * EndermiteAbilitySupport), "last_bite" (tick of the last
 * counted bite, for the spacing rule), "stacks" (the current
 * gorge stack count, 0 to 10).
 */
public final class GorgedMiteAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int POLL_INTERVAL = 4;
    private static final int BITE_SPACING = 10;
    private static final int MAX_STACKS = 10;
    private static final double SCALE_PER_STACK = 0.1;
    private static final double DAMAGE_PER_STACK = 0.3;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "gorged_mite_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "gorged_mite_damage");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "gorged_mite_knockback");
    private static final ColorParticleOption GORGE_STEAM =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.62f, 0.25f, 0.85f);

    @Override
    public String id() {
        return "gorged_mite";
    }

    @Override
    public String displayName() {
        return "Gorged Mite";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 12.25;
    }

    @Override
    public void start(AbilityContext ctx) {
        EndermiteAbilitySupport.primeBiteClock(ctx);
        CompoundTag state = ctx.state();
        state.putInt("last_bite", -BITE_SPACING);
        state.putInt("stacks", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % POLL_INTERVAL != 0) {
            return; // Cheap early-out: the bite clock is polled every 4 ticks only.
        }
        CompoundTag state = ctx.state();
        int stacks = state.getIntOr("stacks", 0);
        if (stacks >= MAX_STACKS) {
            return; // Fully gorged: nothing left to grow.
        }
        int ticks = ctx.ticksActive();
        if (ticks - state.getIntOr("last_bite", 0) < BITE_SPACING) {
            return; // Still inside the spacing window of the last counted bite.
        }
        LivingEntity victim = EndermiteAbilitySupport.consumeLandedBite(ctx);
        if (victim == null) {
            return;
        }
        state.putInt("last_bite", ticks);
        stacks++;
        state.putInt("stacks", stacks);
        grow(ctx, stacks);
    }

    /** Applies one growth step: bigger body, harder bite, and the full-gorge prize at 10. */
    private void grow(AbilityContext ctx, int stacks) {
        Mob mob = ctx.mob();
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(new AttributeModifier(
                SCALE_ID, SCALE_PER_STACK * stacks, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.addOrUpdateTransientModifier(new AttributeModifier(
                DAMAGE_ID, DAMAGE_PER_STACK * stacks, AttributeModifier.Operation.ADD_VALUE));
        }
        if (stacks >= MAX_STACKS) {
            AttributeInstance knockback = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (knockback != null) {
                knockback.addOrUpdateTransientModifier(new AttributeModifier(
                    KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
            }
        }

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(GORGE_STEAM, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                8, 0.3, 0.25, 0.3, 0.1);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.ENDERMITE_AMBIENT, SoundSource.HOSTILE, 0.6f, 1.0f + 0.05f * stacks);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The gorge ends with the ability: every modifier comes
        // off by id and the mite shrinks back to birth size.
        Mob mob = ctx.mob();
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.removeModifier(DAMAGE_ID);
        }
        AttributeInstance knockback = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.removeModifier(KNOCKBACK_ID);
        }
    }
}
