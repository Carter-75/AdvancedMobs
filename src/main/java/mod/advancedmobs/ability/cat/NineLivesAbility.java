package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Nine Lives (Cat ability 1 of 5, defense).
 *
 * WHAT IT DOES: the cat refuses to die. While this ability
 * is active the cat is armed against death itself: when a
 * hit would kill it, totem of undying particles burst
 * around it, it is restored with a surge of health, and it
 * vanishes into invisibility with a speed burst to escape.
 * Each rescue spends one of the cat's nine lives.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cat's current target within 16 blocks
 *   (distance squared 256), plus at least one unspent life
 *   (NineLivesSave.hasLifeLeft). A cat that has spent all 8
 *   of its saves is never offered this ability again.
 * - The intercept (the whole point of the ability): the
 *   save does NOT happen here. A killing blow lands between
 *   ticks, inside LivingEntity.hurtServer, so the rescue is
 *   hooked at the head of checkTotemDeathProtection by
 *   CatNineLivesMixin, delegating to NineLivesSave.trySave,
 *   the established totem pattern (Zoglin's Undying Bulk,
 *   the Evoker's Totem Tyrant). start() only arms the save
 *   (state "armed" = 1, "saves" = 0); trySave does the
 *   rest at the moment of the blow: spend one life from the
 *   PERMANENT per-cat counter (MobWarData skillData key
 *   "am_cat_lives_spent", 8 saves maximum across the cat's
 *   whole life, never refunded; see NineLivesSave for the
 *   permanence decision), clear all effects, restore half
 *   health (at least 2.0) plus an INSTANT_HEALTH surge,
 *   grant INVISIBILITY and SPEED II for 200 ticks, clear
 *   the cat as the target of every Mob within 16 blocks,
 *   and fire the vanilla totem pop (entity event 35) with
 *   20 TOTEM_OF_UNDYING particles and a hiss. Damage that
 *   bypasses invulnerability (the void, /kill) is never
 *   saved from, mirroring the real totem.
 * - Multiple saves: the save stays armed for the whole
 *   activation, so a cat cornered after a rescue can be
 *   saved again by the same activation, one life per blow,
 *   until its lives run out.
 * - The tell (tick(), every 20 ticks): 2 TOTEM_OF_UNDYING
 *   motes drift around the cat, the visible sign that death
 *   is being cheated while the ability lasts.
 * - Duration: exactly 1200 ticks (60 seconds), or until
 *   the cat's last life is spent (isFinished also checks
 *   the permanent counter), whichever comes first. The
 *   framework cooldown runs from the end as usual.
 * - end(): nothing to strip. The ability itself applied no
 *   modifiers and no effects; the save's escape effects
 *   (INVISIBILITY, SPEED II) belong to the rescue, ride
 *   their own 200 tick clocks, and are deliberately left
 *   running. The "armed" flag dies with the state tag,
 *   which disarms the save. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a cat fighting under a faint drift
 * of gold-green totem sparks. The killing blow lands and,
 * instead of a death, the totem burst pops, the cat is
 * suddenly at half health and gone: invisible, sprinting,
 * and its attackers are standing around with nobody to
 * swing at.
 *
 * PERFORMANCE: canTrigger is one distance check and one
 * NBT int read. tick() emits 2 particles every 20 ticks
 * and runs no queries; the save's one bounded radius-16
 * query runs only on an actual killing blow. Particle
 * budget per activation: 60 pulses times 2 = 120 drift
 * motes, plus 20 per save fired (at most 8 saves in a
 * cat's life, so at most 160 more, ever).
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "armed" (1 while the death save may
 * fire), "saves" (how many saves this activation has
 * fired; the permanent record is NineLivesSave's counter).
 */
public final class NineLivesAbility implements MobAbility {

    private static final int DURATION_TICKS = 1200;
    private static final int DRIFT_INTERVAL = 20;

    @Override
    public String id() {
        return NineLivesSave.ABILITY_ID;
    }

    @Override
    public String displayName() {
        return "Nine Lives";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0
            && NineLivesSave.hasLifeLeft(ctx.mob());
    }

    @Override
    public void start(AbilityContext ctx) {
        // Arming is all start() does: the rescue itself is
        // hooked into the killing blow by CatNineLivesMixin.
        ctx.state().putInt("armed", 1);
        ctx.state().putInt("saves", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            8, 0.4, 0.4, 0.4, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % DRIFT_INTERVAL != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        // The tell: totem sparks drift around a cat death cannot have yet.
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            2, 0.4, 0.4, 0.4, 0.05);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || !NineLivesSave.hasLifeLeft(ctx.mob());
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing applied, nothing to strip: the save's
        // escape effects ride their own clocks (see the
        // class comment), and the armed flag dies with the
        // state tag the framework discards.
    }
}
