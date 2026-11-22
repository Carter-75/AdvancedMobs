package mod.advancedmobs.ability.goat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
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
 * Stubborn Stance (Goat ability 5 of 5, defense).
 *
 * WHAT IT DOES: the goat locks its legs, lowers its head,
 * and simply refuses to move, dust settling around its
 * planted hooves. For 12 seconds it cannot be knocked back
 * at all and takes reduced damage, and attackers who
 * charge into it bounce off its horns and take a little
 * damage for their trouble.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the goat's current target within 8 blocks
 *   (distance squared 64). The stance answers a close
 *   fight, not a distant one.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Stance modifiers, applied once in start() as transient
 *   attribute modifiers and removed in end():
 *     KNOCKBACK_RESISTANCE add_value +1.0 (id
 *       advancedmobs:stubborn_stance_knockback): the
 *       goat's base is 0, so its total is exactly 1.0, the
 *       design's "cannot be knocked back at all."
 *     MOVEMENT_SPEED add_multiplied_total -1.0 (id
 *       advancedmobs:stubborn_stance_speed): speed
 *       multiplied to zero, the design's "refuses to move,"
 *       done with an attribute, never NoAi (the standing
 *       contract: NoAi would freeze the ability itself).
 *       On top of the attribute, every tick stops the
 *       navigation and zeroes the horizontal velocity
 *       (vertical left alone), so nothing slides the
 *       planted goat around.
 * - RESISTANCE I (amplifier 0) for 260 ticks on the goat,
 *   applied in start() and removed in end(): the design's
 *   reduced damage, 20 percent off everything that gets
 *   through. Removing it in end() means the stance never
 *   leaks damage reduction past its duration (a permanent
 *   upgrade RESISTANCE from the goat's config tree is
 *   ScalingSystem's business and re-applies itself, the
 *   Living Mountain precedent).
 * - The bounce (tick()): the goat's health is polled every
 *   tick against the value recorded the tick before (state
 *   key "last_health", primed in start()). When it drops
 *   by more than 0 and at least 10 ticks have passed since
 *   the last bounce (state key "last_bounce"), the hit is
 *   answered: the goat's last hurt-by mob, when it is an
 *   enemy (GoatAbilitySupport rules) within 5.0 blocks,
 *   takes 3.0 points of thorns damage credited to the goat
 *   (DamageSources.thorns: the horns) AND is knocked back
 *   off them: 1.1 horizontal away from the goat and 0.45
 *   up, hurtMarked set. The 10-tick spacing keeps damage
 *   over time (fire, poison) from machine-gunning the
 *   bounce: at most one answer per half second, and only
 *   for a melee-range attacker; ranged attackers past 5
 *   blocks are never bounced. Healing never triggers a
 *   bounce (the poll only answers drops), and the recorded
 *   health is refreshed every tick either way.
 * - end(): removes both modifiers and the RESISTANCE
 *   effect. If the goat dies mid-stance the framework
 *   termination path runs this same end() on a dead mob:
 *   the removals are harmless no-ops.
 *
 * WHAT IT LOOKS LIKE: a low GOAT_AMBIENT at pitch 0.7 as
 * the legs lock, then a goat gone statue still with pale
 * DUST settling around its hooves (2 particles every 5
 * ticks), attackers visibly rebounding off its lowered
 * horns with their own hurt flash.
 *
 * PERFORMANCE: tick() does one navigation stop, one
 * velocity write, and one health read and compare; the
 * bounce is one distance check against an already-resolved
 * reference (the last hurt-by mob), never an entity query.
 * Particles run every 5th tick only. Particle budget per
 * activation: at most 48 emissions times 2 = 96 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. DUST is a particle, not a placed block.
 *
 * State tag keys: "last_health" (the goat's health at the
 * previous tick's poll, float), "last_bounce" (the tick of
 * the last bounce answer, for the 10-tick spacing).
 */
public final class StubbornStanceAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int EFFECT_TICKS = 260;
    private static final int BOUNCE_SPACING_TICKS = 10;
    private static final float BOUNCE_DAMAGE = 3.0f;
    private static final double BOUNCE_RANGE_SQR = 25.0; // 5.0 blocks
    private static final DustParticleOptions HOOF_DUST = new DustParticleOptions(0xCFC8B8, 1.0f);
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_stance_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_stance_speed");

    @Override
    public String id() {
        return "stubborn_stance";
    }

    @Override
    public String displayName() {
        return "Stubborn Stance";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Planted: unmovable, unmoving, and hardened.
        GoatAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        GoatAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EFFECT_TICKS, 0));
        ctx.state().putFloat("last_health", caster.getHealth());
        ctx.state().putInt("last_bounce", -BOUNCE_SPACING_TICKS);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.GOAT_AMBIENT,
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

        // Health poll: answer fresh hits with the bounce.
        float health = caster.getHealth();
        float lastHealth = ctx.state().getFloatOr("last_health", health);
        float drop = lastHealth - health;
        ctx.state().putFloat("last_health", health);
        int ticks = ctx.ticksActive();
        if (drop > 0.0f && ticks - ctx.state().getIntOr("last_bounce", 0) >= BOUNCE_SPACING_TICKS) {
            LivingEntity attacker = caster.getLastHurtByMob();
            if (attacker != null && GoatAbilitySupport.isEnemy(ctx, attacker)
                && caster.distanceToSqr(attacker) <= BOUNCE_RANGE_SQR) {
                ServerLevel level = ctx.serverLevel();
                if (level != null) {
                    attacker.hurtServer(level, level.damageSources().thorns(caster), BOUNCE_DAMAGE);
                    double kx = attacker.getX() - caster.getX();
                    double kz = attacker.getZ() - caster.getZ();
                    double kLength = Math.sqrt(kx * kx + kz * kz);
                    if (kLength < 0.001) {
                        kx = 1.0;
                        kz = 0.0;
                        kLength = 1.0;
                    }
                    attacker.setDeltaMovement(kx / kLength * 1.1, 0.45, kz / kLength * 1.1);
                    attacker.hurtMarked = true;
                    ctx.state().putInt("last_bounce", ticks);
                }
            }
        }

        if (ticks % 5 == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                // Dust settling around the planted hooves.
                level.sendParticles(HOOF_DUST, caster.getX(), caster.getY() + 0.1, caster.getZ(),
                    2, 0.4, 0.05, 0.4, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        GoatAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        GoatAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);
    }
}
