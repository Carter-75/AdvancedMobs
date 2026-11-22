package mod.advancedmobs.ability.giant;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Living Mountain (Giant ability 4 of 5, defense).
 *
 * WHAT IT DOES: the giant plants its feet, crosses its arms,
 * and goes still as a mountain: FALLING_DUST trickles off its
 * stony hide the whole time. While rooted it cannot be
 * knocked back at all, its armor jumps by a full set of
 * netherite and then some, it resists and slowly regenerates,
 * and any enemy that lands a hit on it takes thorns damage
 * from the jagged stone hide.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the giant's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - Rooted: every tick, navigation is stopped and horizontal
 *   velocity is zeroed (NoAi is never used; the giant's own
 *   goals keep running against the stop, which is what holds
 *   it in place). The giant can still turn and swing.
 * - Attribute changes, transient modifiers added in start()
 *   and removed in end(), ids under
 *   advancedmobs:living_mountain_*:
 *     KNOCKBACK_RESISTANCE add_value +1.0 (total immunity)
 *     ARMOR                add_value +20.0
 * - Effects, applied in start() for 320 ticks and removed in
 *   end(): RESISTANCE amplifier 0 and REGENERATION amplifier
 *   0. If the giant carries a permanent regeneration upgrade
 *   from its own config tree, ScalingSystem's
 *   refreshMissingEffects re-applies it after end() removes
 *   this ability's instance, so removing it here is safe (the
 *   Living Stone Shell precedent).
 * - Thorns: the giant's health is polled every tick against
 *   the value stored in the state tag. When it drops, the
 *   giant's last hurt-by mob takes 3.0 thorns damage
 *   (DamageSources.thorns, credited to the giant) when that
 *   attacker is an enemy by the package rule and within 8
 *   blocks, at most once per 10 ticks. A burst of 4 stone
 *   BLOCK_CRUMBLE particles flies at the giant's chest on
 *   each thorn hit. The stored health refreshes every tick,
 *   thorn hit or not. (Accepted edge, shared with Stubborn
 *   Hide Brace: a health drop from a non-attack cause in the
 *   same tick window can credit the thorn to the most recent
 *   attacker.)
 * - end() also stops nothing else: no entities were created.
 *
 * WHAT IT LOOKS LIKE: a motionless giant with dust forever
 * sifting off it: every 4 ticks, 2 FALLING_DUST particles of
 * stone drift down its body, spread across its width. The
 * RESISTANCE and REGENERATION show as vanilla effect
 * sparkles. Attackers see stone chips burst off the hide
 * when the thorns bite.
 *
 * PERFORMANCE: per tick the work is one navigation stop, one
 * health read and compare, and, on drop ticks only, one
 * already-resolved attacker reference: no entity queries
 * anywhere. The dust pulse runs every 4 ticks. Particle
 * budget per activation: at most 75 trickle pulses times 2
 * = 150, plus at most 30 thorn bursts times 4 = 120, total
 * at most 270 particles.
 *
 * State tag keys: "last_health" (float, the health seen on
 * the previous tick), "last_thorn" (int, the tick of the
 * last thorn hit, for the 10-tick spacing).
 */
public final class LivingMountainAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final int THORN_INTERVAL = 10;
    private static final float THORN_DAMAGE = 3.0f;
    private static final Identifier KB_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "living_mountain_kb");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "living_mountain_armor");
    private static final BlockParticleOption STONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.STONE.defaultBlockState());
    private static final BlockParticleOption STONE_FALLING_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultBlockState());

    @Override
    public String id() {
        return "living_mountain";
    }

    @Override
    public String displayName() {
        return "Living Mountain";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GiantAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KB_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        GiantAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EFFECT_TICKS, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
        mob.getNavigation().stop();
        ctx.state().putFloat("last_health", mob.getHealth());
        ctx.state().putInt("last_thorn", -THORN_INTERVAL);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Rooted: hold position against the giant's own goals.
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        if (velocity.x != 0.0 || velocity.z != 0.0) {
            mob.setDeltaMovement(0.0, velocity.y, 0.0);
        }

        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level != null && ticks % 4 == 0) {
            // Stone dust sifting off the hide.
            double spread = mob.getBbWidth() * 0.5;
            level.sendParticles(STONE_FALLING_DUST, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                2, spread, mob.getBbHeight() * 0.3, spread, 0.02);
        }

        // Thorns: a health drop answers the most recent attacker.
        float health = mob.getHealth();
        float lastHealth = ctx.state().getFloatOr("last_health", health);
        ctx.state().putFloat("last_health", health);
        if (level == null || health >= lastHealth) {
            return;
        }
        if (ticks - ctx.state().getIntOr("last_thorn", -THORN_INTERVAL) < THORN_INTERVAL) {
            return;
        }
        LivingEntity attacker = mob.getLastHurtByMob();
        if (attacker == null || !GiantAbilitySupport.isEnemy(ctx, attacker)
            || mob.distanceToSqr(attacker) > 64.0) {
            return;
        }
        ctx.state().putInt("last_thorn", ticks);
        attacker.hurtServer(level, level.damageSources().thorns(mob), THORN_DAMAGE);
        level.sendParticles(STONE_CRUMBLE, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(),
            4, mob.getBbWidth() * 0.4, 0.6, mob.getBbWidth() * 0.4, 0.08);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GiantAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KB_ID);
        GiantAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        mob.removeEffect(MobEffects.RESISTANCE);
        mob.removeEffect(MobEffects.REGENERATION);
    }
}
