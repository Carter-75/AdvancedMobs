package mod.advancedmobs.ability.donkey;

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

/**
 * Stubborn Stand (Donkey ability 3 of 5, control / defense).
 *
 * WHAT IT DOES: the donkey plants all four hooves and
 * simply refuses. The ground under it grips like glue, its
 * body locks in place, and it wears the stand like a badge:
 * anything that strikes it during the stand hurts itself on
 * the counter, thorns damage reflected straight back at the
 * attacker with a shove.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the donkey on the ground with its current target within
 *   8 blocks (distance squared 64).
 * - The plant (start(), all removed exactly in end()):
 *   the donkey is tagged "advancedmobs_stubborn_stand"
 *   (the design's tag to mark the stand state; other
 *   systems and future debugging can read it), and three
 *   transient attribute modifiers are applied under fixed
 *   ids: FRICTION_MODIFIER +9.0 ADD_VALUE (total 10x the
 *   ground friction of the block below: the design's
 *   friction line at max, so the donkey stops dead and
 *   cannot be slid or skidded by anything short of a
 *   teleport), KNOCKBACK_RESISTANCE +1.0 ADD_VALUE (the
 *   attribute clamps at total resistance: no hit moves
 *   it), and MOVEMENT_SPEED -1.0 ADD_MULTIPLIED_TOTAL
 *   (speed multiplied to exactly 0). RESISTANCE I is
 *   applied for 260 ticks and removed in end(). Per tick
 *   the navigation is stopped and horizontal velocity is
 *   zeroed, so the plant holds even between AI steps
 *   (never NoAi: abilities tick from the AI step).
 * - The counter (tick(), health polled every tick): the
 *   donkey's health is compared against the last polled
 *   value; a fresh drop means a hit landed, and at most
 *   once per 10 ticks the attacker (the donkey's last
 *   hurt-by mob, when it is a living enemy within 8
 *   blocks) takes 3.0 damage from the vanilla thorns
 *   damage source credited to the donkey (the design's
 *   reflected damage, delivered as the typed thorns
 *   source, the Goat Stubborn Stance pattern) and is
 *   shoved 0.5 blocks per tick horizontally away from the
 *   donkey and 0.3 up, hurtMarked set, with 6 POOF
 *   particles bursting off the point of contact and a
 *   DONKEY_ANGRY snort at volume 0.8, pitch 1.1. The
 *   polled value is refreshed every tick whether or not a
 *   counter fired.
 * - Duration: exactly 240 ticks (12 seconds), hard finish.
 * - end(): the stand tag is removed, all three modifiers
 *   and the RESISTANCE effect are removed. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the donkey goes statue-still, hooves
 * planted wide, and every blow that lands on it answers
 * itself: a puff of dust, a snort, and the attacker
 * staggering back nursing its own hit.
 *
 * PERFORMANCE: per tick the ability does one navigation
 * stop, one velocity write, and one health read and
 * compare; the counter is one damage call against an
 * already-resolved reference, never an entity query.
 * Particle budget per activation: at most 24 counters
 * times 6 = 144 particles.
 *
 * TERRAIN: this ability changes no blocks; the mobGriefing
 * gate does not apply. The friction change is an attribute
 * on the donkey itself, not a block change.
 *
 * State tag keys: "last_health" (the last polled health,
 * stored as a float), "last_counter" (the tick the last
 * counter fired).
 */
public final class StubbornStandAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int COUNTER_SPACING_TICKS = 10;
    private static final double COUNTER_RANGE_SQR = 64.0;
    private static final float COUNTER_DAMAGE = 3.0f;
    private static final String STAND_TAG = "advancedmobs_stubborn_stand";
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_stand_friction");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_stand_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_stand_speed");

    @Override
    public String id() {
        return "stubborn_stand";
    }

    @Override
    public String displayName() {
        return "Stubborn Stand";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putFloat("last_health", caster.getHealth());
        ctx.state().putInt("last_counter", -COUNTER_SPACING_TICKS);
        // The badge of the stand, and the plant itself.
        caster.addTag(STAND_TAG);
        DonkeyAbilitySupport.addModifier(caster, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, 9.0, AttributeModifier.Operation.ADD_VALUE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Planted: hold position through navigation and
        // velocity, never NoAi (see the class comment).
        caster.getNavigation().stop();
        Vec3 current = caster.getDeltaMovement();
        caster.setDeltaMovement(0.0, current.y, 0.0);

        // Health poll: answer fresh hits with the counter.
        float health = caster.getHealth();
        float lastHealth = ctx.state().getFloatOr("last_health", health);
        float drop = lastHealth - health;
        ctx.state().putFloat("last_health", health);
        int ticks = ctx.ticksActive();
        if (drop <= 0.0f || ticks - ctx.state().getIntOr("last_counter", 0) < COUNTER_SPACING_TICKS) {
            return;
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !DonkeyAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > COUNTER_RANGE_SQR) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ctx.state().putInt("last_counter", ticks);
        // The counter: the hit answers itself, thorns-typed.
        attacker.hurtServer(level, level.damageSources().thorns(caster), COUNTER_DAMAGE);
        double kx = attacker.getX() - caster.getX();
        double kz = attacker.getZ() - caster.getZ();
        double kLength = Math.sqrt(kx * kx + kz * kz);
        if (kLength < 0.001) {
            kx = 1.0;
            kz = 0.0;
            kLength = 1.0;
        }
        attacker.setDeltaMovement(kx / kLength * 0.5, 0.3, kz / kLength * 0.5);
        attacker.hurtMarked = true;
        level.sendParticles(ParticleTypes.POOF, attacker.getX(), attacker.getY() + 0.8, attacker.getZ(),
            6, 0.3, 0.4, 0.3, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_ANGRY,
            SoundSource.HOSTILE, 0.8f, 1.1f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeTag(STAND_TAG);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);
    }
}
