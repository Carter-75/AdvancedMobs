package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zoglin;

/**
 * Undying Bulk (Zoglin ability 2 of 5, defense and transformation).
 *
 * WHAT IT DOES: the zoglin's rot runs so deep that death does
 * not take the first time. While this ability is active the
 * zoglin carries an armed death save: the first killing blow
 * that lands on it simply does not kill it. It erupts in the
 * totem burst and soul wisps, stands back up at half health,
 * and comes back bigger and angrier, its maximum health
 * permanently raised for the rest of the fight. Enemies who
 * spent their best attacks earning the kill have to earn it
 * again. It works exactly once in the zoglin's whole life, and
 * fire is never saved from: fire finishes the job properly.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the zoglin's current target within 12 blocks (distance
 *   squared 144), plus the save unspent: a zoglin already
 *   carrying the permanent bulk modifier (see the save hook)
 *   never rolls this ability again.
 * - Duration: the save stays armed for at most 2400 ticks
 *   (120 seconds) from start(). If the zoglin is not killed in
 *   that window the bulk lapses unspent and the ability ends
 *   (it may arm again on a later activation; the once-only
 *   rule is enforced at the moment of the save, not here). The
 *   ability also ends on the first tick after the save fires.
 * - The save itself does NOT live in this class: a killing
 *   blow is processed inside LivingEntity.hurtServer between
 *   ticks, so the interception sits in the vanilla totem
 *   check, at ZoglinUndyingBulkMixin, which delegates to
 *   UndyingBulkSave.trySave. That hook performs the whole
 *   revival at the moment of the blow: guards (no save from
 *   invulnerability-bypassing damage, no save from fire),
 *   the permanent raises (MAX_HEALTH +20, SCALE 1.3 times,
 *   ATTACK_DAMAGE +3), effects cleared, health set to half
 *   of the new maximum, totem pop broadcast, soul burst,
 *   bellow, and the state tag's "revived" flag set so this
 *   ability ends on its next tick. Read UndyingBulkSave's
 *   class comment for the full save spec.
 * - This class's own work is only the arming: start() sets
 *   the state flags the hook reads ("armed" 1, "revived" 0)
 *   and plays the gathering tell below; tick() watches for
 *   the "revived" flag and drifts the armed tell.
 *
 * WHAT IT LOOKS LIKE: while armed, the zoglin is quietly
 * wrong: a low ZOGLIN_AMBIENT rumble at a dropped pitch and a
 * burst of 8 SOUL wisps at start(), then 2 SOUL wisps drift
 * up off its back every 20 ticks for the whole window, the
 * only warning that killing it once will not be enough. The
 * revival itself is the loud part and is specified in
 * UndyingBulkSave: the TOTEM_OF_UNDYING burst of the vanilla
 * totem pop, 24 SOUL wisps, and a ZOGLIN_ANGRY bellow, on a
 * zoglin suddenly a third again as large.
 *
 * PERFORMANCE: tick() returns immediately except every 20th
 * tick (one 2-particle send) plus one state read per tick.
 * canTrigger does one distance check and one attribute
 * modifier lookup. No entity queries anywhere. Particle
 * budget per activation: 8 at start plus at most 120 armed
 * tells times 2 = 240, total at most 248 particles (the
 * revival's own burst is the save hook's, counted there).
 *
 * CLEANUP: end() removes nothing, deliberately. This ability
 * applies no transient modifiers and no effects of its own.
 * The permanent raises are applied by the save hook at the
 * revival and are permanent by the design's explicit words
 * ("MAX_HEALTH permanently raised for the rest of the fight"),
 * the documented exception to the usual end() reversal rule;
 * if the window lapses unspent there is nothing to reverse
 * either, the armed flag simply dies with the state tag.
 *
 * State tag keys: "armed" (1 while the save is armed; the
 * save hook requires it), "revived" (set to 1 by the save
 * hook at the moment of the revival; ends the ability).
 */
public final class UndyingBulkAbility implements MobAbility {

    static final int ARM_WINDOW_TICKS = 2400;
    private static final int TELL_INTERVAL = 20;

    @Override
    public String id() {
        return UndyingBulkSave.ABILITY_ID;
    }

    @Override
    public String displayName() {
        return "Undying Bulk";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 144.0) {
            return false;
        }
        // Once only: a zoglin that already came back never arms again.
        return ctx.mob() instanceof Zoglin zoglin && !UndyingBulkSave.hasSpentBulk(zoglin);
    }

    @Override
    public void start(AbilityContext ctx) {
        // Arm the save. The hook reads exactly these two flags.
        ctx.state().putInt("armed", 1);
        ctx.state().putInt("revived", 0);

        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                8, 0.7, 0.7, 0.7, 0.06);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOGLIN_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("revived", 0) != 0) {
            return; // The save fired; isFinished ends the ability this tick.
        }
        if (ctx.ticksActive() % TELL_INTERVAL != 0) {
            return; // Cheap early-out: the armed tell drifts every 20 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + mob.getBbHeight() + 0.3, mob.getZ(),
            2, 0.6, 0.2, 0.6, 0.04);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("revived", 0) != 0
            || ctx.ticksActive() >= ARM_WINDOW_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: see the CLEANUP section of the class comment.
    }
}
