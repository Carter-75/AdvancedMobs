package mod.advancedmobs.ability.zombievillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Frenzied Merchant (Zombie Villager ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the zombie villager snaps into a merchant frenzy: body
 * hunched and twitching under a boil of purple sparkles and witch
 * motes, arms flailing at double pace. It rushes its target at SPEED,
 * swings in a rapid blur under HASTE, and keeps a merchant's
 * stubbornness that halves the knockback it takes.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie
 *   villager's current target within 12 blocks (distance squared 144).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - Effects, applied once in start() with exactly the ability's
 *   duration, so they lapse on the same tick the ability ends:
 *     SPEED II (amplifier 1): 40 percent faster movement.
 *     HASTE II (amplifier 1): 40 percent faster attack speed (and
 *       mining speed), the flailing blur.
 * - Attribute changes, transient modifiers added in start() and
 *   removed in end(), ids under advancedmobs:frenzied_merchant_*:
 *     KNOCKBACK_RESISTANCE  add_value  +0.5  (halves knockback taken:
 *       a zombie villager's base is 0, so 0.5 of every knockback is
 *       resisted)
 *     SCALE                 add_value  -0.15 (total scale 0.85: the
 *       hunched, twitching crouch; hitbox shrinks with the model)
 * - No state tag entries are needed: the whole activation is the two
 *   effects, the two modifiers, and the tick counter, so a resumed
 *   activation after a chunk reload behaves identically.
 *
 * WHAT IT LOOKS LIKE: the model shrinks to 85 percent and hunches via
 * the SCALE attribute; HASTE makes the arm swings visibly faster.
 * Every 4 ticks a boil of sparkles at the body: 2 purple ENTITY_EFFECT
 * particles (color 0.62, 0.25, 0.85) and 1 WITCH particle, spread
 * around chest height.
 *
 * PERFORMANCE: tick() returns immediately except every 4th tick, which
 * sends 3 particles. No entity scans, no allocations beyond the two
 * pre-built particle option objects. Particle budget per activation:
 * 50 pulses times 3 particles = 150.
 *
 * CLEANUP: end() removes both attribute modifiers by id. The SPEED and
 * HASTE effects are NOT removed: their duration equals the ability
 * duration and they expire on their own at the same tick (and removing
 * effects in end() could strip an unrelated same-type effect granted
 * by another system, such as the upgrade tree; modifiers carry unique
 * ids, effects do not). If the zombie villager dies mid-frenzy the
 * modifiers die with the entity; nothing else was created.
 */
public final class FrenziedMerchantAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final Identifier KNOCKBACK_ID = Identifier.fromNamespaceAndPath("advancedmobs", "frenzied_merchant_knockback");
    private static final Identifier SCALE_ID = Identifier.fromNamespaceAndPath("advancedmobs", "frenzied_merchant_scale");
    private static final ColorParticleOption FRENZY_SPARKLE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.62f, 0.25f, 0.85f);

    @Override
    public String id() {
        return "frenzied_merchant";
    }

    @Override
    public String displayName() {
        return "Frenzied Merchant";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 1));
        mob.addEffect(new MobEffectInstance(MobEffects.HASTE, DURATION_TICKS, 1));
        AttributeInstance knockback = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.addOrUpdateTransientModifier(
                new AttributeModifier(KNOCKBACK_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(
                new AttributeModifier(SCALE_ID, -0.15, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 4 != 0) {
            return; // Cheap early-out: sparkles boil every 4 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        double x = mob.getX(), y = mob.getY() + 1.1, z = mob.getZ();
        level.sendParticles(FRENZY_SPARKLE, x, y, z, 2, 0.5, 0.5, 0.5, 0.03);
        level.sendParticles(ParticleTypes.WITCH, x, y, z, 1, 0.5, 0.5, 0.5, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance knockback = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.removeModifier(KNOCKBACK_ID);
        }
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
    }
}
