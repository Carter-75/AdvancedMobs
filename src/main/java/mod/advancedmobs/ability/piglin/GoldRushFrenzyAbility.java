package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Gold Rush Frenzy (Piglin ability 1 of 5, transformation, aura).
 *
 * WHAT IT DOES: the moment loose gold hits the ground near this
 * piglin, it loses its mind for it. Gold sparkles ignite around
 * its body, and while any gold item lies within reach it fights
 * at SPEED II and STRENGTH I, sprinting straight at the prize
 * and trampling whoever stands in the way. When it reaches the
 * gold it snatches it into its own inventory, and the frenzy
 * fades a few seconds after the last gold in reach is claimed
 * or gone.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   piglin's current target within 16 blocks (distance squared
 *   256), plus at least one gold item entity (the support class's
 *   GOLD ITEM RULE) within 8 blocks horizontally and 3 blocks
 *   vertically of the piglin. The item scan is one bounded
 *   ItemEntity query, the Carrion Call canTrigger precedent.
 * - Duration: at most 600 ticks (30 seconds). The frenzy ends
 *   60 ticks (3 seconds) after the last pulse that saw gold
 *   within reach, claimed or not: the design's "fades a few
 *   seconds after the last gold is claimed".
 * - Pulse every 5 ticks: one bounded ItemEntity query (radius
 *   8, 3 below to 3 above). When it finds gold:
 *     The piglin refreshes SPEED II (amplifier 1) and STRENGTH I
 *       (amplifier 0), 30 ticks each, on itself. The buffs are
 *       only ever refreshed while gold is in reach, so they
 *       bleed off on their own once the gold runs out.
 *     3 gold DUST sparkles (0xFFD633, scale 1.2, the gold dust
 *       color the Zombie Villager's ward uses) puff around the
 *       piglin's body.
 *     The nearest gold item drives the piglin:
 *       CLAIM: at 1.6 blocks or closer, the stack moves into
 *         the piglin's own inventory (Piglin.getInventory(),
 *         the vanilla barter satchel; a remainder that does not
 *         fit stays on the ground as the same item entity), the
 *         item entity is discarded when fully taken, a
 *         PIGLIN_ADMIRING_ITEM squeal plays, and 8 gold DUST
 *         burst at the item's position. This is the design's
 *         "the last gold is claimed" moment, done by the
 *         ability itself rather than left to the piglin brain's
 *         slower vanilla gold admiration.
 *       CHASE: farther than 1.6 blocks, the piglin's navigation
 *         is driven to the item's position at speed 1.35, every
 *         pulse. That is the design's target switching: the
 *         piglin visibly breaks off its fight and runs at the
 *         thrown gold, and a clever enemy can bait it exactly
 *         this way. Navigation steering of a brain mob is
 *         best-effort between pulses (the brain re-plans about
 *         once a second), which is why the order is re-issued
 *         every 5 ticks. The combat target itself is never
 *         rewritten; only the movement is hijacked.
 *       TRAMPLE: while chasing (gold in reach, not yet claimed),
 *         if the framework target is within 1.8 blocks of the
 *         piglin, it takes 3.0 mob-attack damage credited to the
 *         piglin, at most once per 20 ticks: woe to anyone
 *         standing between the piglin and the prize.
 * - end(): SPEED and STRENGTH are removed from the piglin so
 *   the frenzy stops dead instead of bleeding out when the
 *   framework ends the activation early. Accepted edge case,
 *   shared with every fixed-duration effect in this system
 *   (the Scarab Brood precedent): if the piglin somehow had
 *   SPEED or STRENGTH from another source it loses that too;
 *   permanent upgrade effects are safe because ScalingSystem
 *   re-applies its own missing permanent effects (the Living
 *   Stone Shell precedent).
 *
 * WHAT IT LOOKS LIKE: gold DUST sparkles stream off the piglin
 * the whole time gold is near, a PIGLIN_ADMIRING_ITEM squeal and
 * a burst of gold dust mark each claim, and the piglin itself is
 * simply moving at SPEED II with a STRENGTH I arm swing: the
 * frenzy reads as a piglin sprinting through its own fight,
 * eyes only for the gold.
 *
 * PERFORMANCE: canTrigger is one range check plus one bounded
 * ItemEntity query. tick() returns immediately except every 5th
 * tick, which runs one bounded ItemEntity query, at most two
 * effect refreshes, one navigation write, and at most one
 * damage call: no whole-level scans, no allocations beyond the
 * query result list. Particle budget per activation: at most
 * 120 pulses times 3 sparkles = 360, plus 8 per claim (bounded
 * by the gold actually on the ground; stated cap 10 claims =
 * 80), plus 10 at start, total at most 450 particles.
 *
 * State tag keys: "last_seen" (activation tick of the most
 * recent pulse that saw gold), "last_trample" (activation tick
 * of the most recent trample hit).
 */
public final class GoldRushFrenzyAbility implements MobAbility {

    static final int MAX_TICKS = 600;
    private static final int PULSE_INTERVAL = 5;
    private static final int FADE_TICKS = 60;
    private static final int TRAMPLE_COOLDOWN = 20;
    private static final double GOLD_REACH = 8.0;
    private static final double CLAIM_RANGE_SQR = 1.6 * 1.6;
    private static final double TRAMPLE_RANGE_SQR = 1.8 * 1.8;
    // 26.2 DustParticleOptions takes an RGB int color: 1.0, 0.84, 0.2 as 0xFFD633.
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(0xFFD633, 1.2f);

    @Override
    public String id() {
        return "gold_rush_frenzy";
    }

    @Override
    public String displayName() {
        return "Gold Rush Frenzy";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        return !PiglinAbilitySupport.goldItemsAround(ctx, GOLD_REACH, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("last_seen", 0);
        ctx.state().putInt("last_trample", -TRAMPLE_COOLDOWN);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_ADMIRING_ITEM,
                SoundSource.HOSTILE, 1.0f, 0.9f);
            level.sendParticles(GOLD_DUST, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                10, 0.6, 0.7, 0.6, 0.08);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the frenzy pulses every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        List<ItemEntity> gold = PiglinAbilitySupport.goldItemsAround(ctx, GOLD_REACH, 3.0, 3.0);
        if (gold.isEmpty()) {
            return; // No gold in reach: the fade clock in isFinished() runs.
        }
        ctx.state().putInt("last_seen", ctx.ticksActive());

        // The frenzy itself: faster and stronger than any calm piglin.
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 1));
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));
        level.sendParticles(GOLD_DUST, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            3, 0.5, 0.6, 0.5, 0.05);

        ItemEntity nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (ItemEntity item : gold) {
            double distSqr = mob.distanceToSqr(item);
            if (distSqr < nearestSqr) {
                nearestSqr = distSqr;
                nearest = item;
            }
        }
        if (nearest == null) {
            return;
        }

        if (nearestSqr <= CLAIM_RANGE_SQR) {
            claim(ctx, level, nearest);
            return;
        }

        // The chase: movement hijacked to the prize, re-issued
        // every pulse because the brain re-plans between them.
        mob.getNavigation().moveTo(nearest.getX(), nearest.getY(), nearest.getZ(), 1.35);

        // The trample: whoever stands in the way gets bowled over.
        LivingEntity target = ctx.target();
        if (target != null && PiglinAbilitySupport.isEnemy(ctx, target)
            && mob.distanceToSqr(target) <= TRAMPLE_RANGE_SQR
            && ctx.ticksActive() - ctx.state().getIntOr("last_trample", -TRAMPLE_COOLDOWN) >= TRAMPLE_COOLDOWN) {
            target.hurt(level.damageSources().mobAttack(mob), 3.0f);
            ctx.state().putInt("last_trample", ctx.ticksActive());
        }
    }

    /** Snatches the gold stack into the piglin's own inventory (see the CLAIM spec above). */
    private void claim(AbilityContext ctx, ServerLevel level, ItemEntity item) {
        Mob mob = ctx.mob();
        if (mob instanceof Piglin piglin) {
            ItemStack remainder = piglin.getInventory().addItem(item.getItem());
            if (remainder.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remainder);
            }
        } else {
            // The provider only serves piglins, but never leave
            // gold floating if the caster is somehow not one.
            item.discard();
        }
        level.playSound(null, item.getX(), item.getY(), item.getZ(), SoundEvents.PIGLIN_ADMIRING_ITEM,
            SoundSource.HOSTILE, 1.0f, 1.1f);
        level.sendParticles(GOLD_DUST, item.getX(), item.getY() + 0.3, item.getZ(),
            8, 0.3, 0.3, 0.3, 0.08);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        return ticks >= MAX_TICKS
            || ticks - ctx.state().getIntOr("last_seen", 0) >= FADE_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The frenzy stops dead (see the class comment for the
        // accepted other-source edge case).
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.STRENGTH);
    }
}
