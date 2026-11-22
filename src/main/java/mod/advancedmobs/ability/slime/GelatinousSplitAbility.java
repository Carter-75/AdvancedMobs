package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.Slime;

/**
 * Gelatinous Split (Slime ability 3 of 5, defense).
 *
 * WHAT IT DOES: when a heavy blow lands, the slime does not
 * die, it divides: it bursts into ITEM_SLIME spray as two
 * healthy slimes one size down shear off its body and bounce
 * straight back into the fight on its team. It also carries
 * OOZING for the aftermath, so even a killing blow in that
 * window leaves two more slimes standing in the puddle where
 * it fell.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework only polls abilities
 *   in combat, so a "heavy blow" reads as its aftermath: the
 *   caster is a slime of size 2 or larger (a size 1 slime has
 *   nothing to shear off) whose current health is at or below
 *   80 percent of its maximum, meaning a real hit has landed.
 * - The split (start(), one-shot): the caster pays for the
 *   sheared-off mass with 20 percent of its MAXIMUM health,
 *   applied as a direct health reduction (no damage event, so
 *   it cannot loop back into the war system's kill credit),
 *   never taking it below 1 health. Then two allied slimes of
 *   size (caster size - 1), minimum 1, spawn at 1.0 block to
 *   either side of the caster (SlimeAbilitySupport's summon
 *   path: MOB_SUMMONED, size forced after finalizeSpawn,
 *   allied in both directions, SummonerTracker registered,
 *   ordered onto the caster's target), each at full health
 *   for its size.
 * - The OOZING window: the caster carries the OOZING effect
 *   at amplifier 0 for the ability's whole 200 ticks (10
 *   seconds). OOZING is vanilla's death trigger: if the
 *   caster dies while it lasts, 2 slimes spawn in the puddle
 *   where it fell, on top of the pair the split already made.
 *   end() removes the effect when the window closes. (A caster
 *   that somehow had OOZING from another source would lose it
 *   at end(), the accepted edge case shared with every
 *   fixed-duration effect in this system.) The split slimes
 *   are NOT removed at end(): they are real mobs sheared off
 *   the caster's body, permanent members of its team, exactly
 *   like the broods the other summon abilities leave behind.
 * - Duration: exactly 200 ticks, which is the OOZING window;
 *   the split itself is instant in start().
 *
 * WHAT IT LOOKS LIKE: a burst of 16 ITEM_SLIME particles off
 * the caster's body and a wet SLIME_SQUISH (volume 1.0, pitch
 * 0.8) at the split, the two new slimes bouncing away on
 * either side, then 1 ITEM_SLIME mote weeping off the split
 * seams every 5 ticks for the rest of the window. The OOZING
 * effect's own sparkles ride on the vanilla effect rendering.
 *
 * PERFORMANCE: start() does the whole split (two spawns, one
 * burst); tick() is a single 5-tick particle pulse; canTrigger
 * is two health reads and a size read. No entity queries at
 * all. Particle budget per activation, hard caps by
 * construction: 16 at the split plus 40 pulses times 1 = 40,
 * total at most 56 particles.
 *
 * CLEANUP: end() removes the OOZING effect. The split slimes
 * stay (see MECHANICS). If the caster dies mid-window the
 * framework termination path runs end() against the dead
 * caster, which is harmless, and OOZING itself fires from the
 * death as designed.
 *
 * State tag keys: none. The whole activation is the OOZING
 * effect plus the tick counter; the split slimes are
 * independent mobs tracked by the war system's own records.
 */
public final class GelatinousSplitAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final double SPLIT_HEALTH_FRACTION = 0.8;
    private static final float MASS_COST_FRACTION = 0.2f;

    @Override
    public String id() {
        return "gelatinous_split";
    }

    @Override
    public String displayName() {
        return "Gelatinous Split";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        if (!(ctx.mob() instanceof Slime slime) || slime.getSize() < 2) {
            return false;
        }
        return ctx.mob().getHealth() <= ctx.mob().getMaxHealth() * SPLIT_HEALTH_FRACTION;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null || !(mob instanceof Slime caster)) {
            return;
        }
        // The mass cost: 20 percent of maximum health, never
        // lethal (the caster always keeps at least 1 health).
        float cost = mob.getMaxHealth() * MASS_COST_FRACTION;
        float payable = Math.min(cost, mob.getHealth() - 1.0f);
        if (payable > 0.0f) {
            mob.setHealth(mob.getHealth() - payable);
        }
        // The split: two healthy slimes one size down, one to
        // either side, straight onto the caster's target.
        int childSize = Math.max(1, caster.getSize() - 1);
        SlimeAbilitySupport.spawnAlliedSlime(ctx, level, mob.getX() + 1.0, mob.getZ(), childSize, null);
        SlimeAbilitySupport.spawnAlliedSlime(ctx, level, mob.getX() - 1.0, mob.getZ(), childSize, null);
        // The aftermath: OOZING, so a killing blow in the
        // window still leaves slimes standing in the puddle.
        mob.addEffect(new MobEffectInstance(MobEffects.OOZING, DURATION_TICKS, 0));
        level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            16, mob.getBbWidth() * 0.5, 0.5, mob.getBbWidth() * 0.5, 0.12);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SLIME_SQUISH,
            SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        // The split seams weep gel: 1 ITEM_SLIME mote every 5 ticks.
        if (ctx.ticksActive() % 5 != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            1, mob.getBbWidth() * 0.4, 0.4, mob.getBbWidth() * 0.4, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The OOZING window closes. The split slimes stay: they
        // are real mobs now, not props of this activation.
        ctx.mob().removeEffect(MobEffects.OOZING);
    }
}
