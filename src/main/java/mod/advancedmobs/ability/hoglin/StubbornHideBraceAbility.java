package mod.advancedmobs.ability.hoglin;

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
 * Stubborn Hide Brace (Hoglin ability 5 of 5, defense).
 *
 * WHAT IT DOES: the hoglin plants all four legs, drops its
 * head, and braces. For up to 10 seconds it cannot be moved
 * and cannot move: completely immune to knockback, its armor
 * spiked, steam rising off its back. Melee attackers wear
 * themselves out on the hide and take chip damage back for
 * every hit they land. One very heavy hit breaks the brace
 * and staggers the hoglin.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   hoglin's current target within 8 blocks (distance squared
 *   64). The brace answers a close fight, not a distant one.
 * - Duration: at most 200 ticks (10 seconds) from start(),
 *   ending early the tick the brace breaks (below).
 * - Brace modifiers, all applied once in start() as transient
 *   attribute modifiers and all removed in end():
 *     KNOCKBACK_RESISTANCE add_value +1.0 (id
 *       advancedmobs:stubborn_hide_knockback): the hoglin's
 *       base is 0, so this is complete knockback immunity.
 *     ARMOR add_value +10.0 (id
 *       advancedmobs:stubborn_hide_armor): the hide spikes
 *       from the hoglin's base 2 armor to 12.
 *     MOVEMENT_SPEED add_multiplied_total -1.0 (id
 *       advancedmobs:stubborn_hide_speed): the hoglin's speed
 *       is multiplied to zero for the duration. This is the
 *       design's "cannot move", done with an attribute, never
 *       NoAi (the standing contract: NoAi would freeze the
 *       ability itself). On top of the attribute, every tick
 *       stops the navigation and zeroes the horizontal
 *       velocity (vertical left alone), so nothing slides the
 *       planted hoglin around.
 * - Chip damage: the hoglin's health is polled every tick
 *   against the value recorded the tick before (state key
 *   "last_health", primed in start()). When it drops by more
 *   than 0 and less than the breaking threshold, the hit that
 *   did it is answered: the hoglin's last hurt-by mob, when it
 *   is an enemy (HoglinAbilitySupport rules) within 5.0
 *   blocks, takes 2.0 points of thorns damage credited to the
 *   hoglin (DamageSources.thorns, the hide wearing the
 *   attacker down). Ranged attackers past 5 blocks take no
 *   chip: the design wears out melee attackers. Healing never
 *   triggers chip (the poll only answers drops), and the
 *   recorded health is refreshed every tick either way.
 * - The break: when a single tick's health drop is 8.0 or
 *   more (one very heavy hit: a charged crit, a point blank
 *   explosion that got through, a warden-class blow), the
 *   brace breaks. No chip is dealt for the breaking hit. The
 *   state flag "broken" ends the ability that same tick, and
 *   end() applies the stagger: SLOWNESS amplifier 1 and
 *   WEAKNESS amplifier 0 for 40 ticks to the hoglin, applied
 *   in end() so the stagger outlives the brace itself (the
 *   Colossal Grief post-form debuff precedent).
 *
 * WHAT IT LOOKS LIKE: a low HOGLIN_AMBIENT grunt at a dropped
 * pitch as the legs plant, and ANGRY_VILLAGER particles
 * steaming off the hoglin's back: 2 particles every 10 ticks
 * at back height for the whole brace. The chip answers are
 * invisible apart from the attacker's own hurt flash; the
 * stagger shows the vanilla SLOWNESS and WEAKNESS sparkles.
 *
 * PERFORMANCE: tick() does one navigation stop, one velocity
 * write, and one health read and compare; the chip answer is
 * one distance check against an already-resolved reference
 * (the last hurt-by mob), never an entity query. Particles
 * run every 10th tick only. Particle budget per activation:
 * at most 20 emissions times 2 = 40 particles.
 *
 * CLEANUP: end() removes all three modifiers by id and, when
 * the brace broke, applies the stagger. If the hoglin dies
 * mid-brace the framework termination path runs this same
 * end() on a dead mob: the removals are harmless no-ops and
 * the stagger lands on a corpse, where it does nothing.
 *
 * State tag keys: "last_health" (the hoglin's health at the
 * previous tick's poll, float), "broken" (1 once a heavy hit
 * has broken the brace).
 */
public final class StubbornHideBraceAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final float BREAK_DROP = 8.0f;
    private static final float CHIP_DAMAGE = 2.0f;
    private static final double CHIP_RANGE_SQR = 25.0;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_hide_knockback");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_hide_armor");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stubborn_hide_speed");

    @Override
    public String id() {
        return "stubborn_hide_brace";
    }

    @Override
    public String displayName() {
        return "Stubborn Hide Brace";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Planted: unmovable, armored, and going nowhere.
        HoglinAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        HoglinAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        HoglinAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        ctx.state().putFloat("last_health", mob.getHealth());
        ctx.state().putInt("broken", 0);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Planted: hold position through navigation and velocity,
        // never NoAi (see the class comment).
        mob.getNavigation().stop();
        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, current.y, 0.0);

        // Health poll: answer hits with chip, break on a heavy one.
        float health = mob.getHealth();
        float lastHealth = ctx.state().getFloatOr("last_health", health);
        float drop = lastHealth - health;
        ctx.state().putFloat("last_health", health);
        if (drop >= BREAK_DROP) {
            ctx.state().putInt("broken", 1);
            return;
        }
        if (drop > 0.0f) {
            LivingEntity attacker = mob.getLastHurtByMob();
            if (attacker != null && HoglinAbilitySupport.isEnemy(ctx, attacker)
                && mob.distanceToSqr(attacker) <= CHIP_RANGE_SQR) {
                ServerLevel level = ctx.serverLevel();
                if (level != null) {
                    attacker.hurtServer(level, level.damageSources().thorns(mob), CHIP_DAMAGE);
                }
            }
        }

        if (ctx.ticksActive() % 10 == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                // Steam off the planted hoglin's back.
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                    mob.getX(), mob.getY() + mob.getBbHeight() + 0.2, mob.getZ(),
                    2, 0.6, 0.2, 0.6, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("broken", 0) != 0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        HoglinAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        HoglinAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        HoglinAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        if (ctx.state().getIntOr("broken", 0) != 0) {
            // The stagger: the broken brace leaves the hoglin
            // reeling for 2 seconds after the brace is gone.
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0));
        }
    }
}
