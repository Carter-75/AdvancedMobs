package mod.advancedmobs.ability.elderguardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Curse of the Sunken Court (Elder Guardian ability 1 of 5, aura).
 *
 * WHAT IT DOES: the elder guardian's ghost apparition flashes
 * across every enemy in the arena exactly as its vanilla curse
 * does, but this curse lands in full: MINING_FATIGUE III,
 * WEAKNESS and a crushing SLOWNESS settle on the whole enemy
 * force at once, and the elder keeps watch for the rest of the
 * activation, recasting the curse on any enemy that shakes it
 * off early. The fight resumes at the elder's pace.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   elder guardian's current target within 50 blocks (distance
 *   squared 2500), the vanilla curse's own range.
 * - Duration: exactly 600 ticks (30 seconds) of watch from
 *   start(). The curse effects themselves run on their own 600
 *   tick clocks and are never removed by end(): a curse laid on
 *   the last watch tick lingers up to 30 more seconds, the same
 *   way the vanilla curse outlives the moment it was cast.
 * - The curse (start(), and again per recast wave): every enemy
 *   (package rule) within a true 50 block distance of the elder
 *   (box query first, then a round distance check) gains
 *   MINING_FATIGUE amplifier 2 (level III, the vanilla curse's
 *   strength), WEAKNESS amplifier 0 and SLOWNESS amplifier 0,
 *   each for 600 ticks, and one ELDER_GUARDIAN apparition
 *   particle is sent at the victim's position. Note on the
 *   package rule: players other than the elder's combat target
 *   are NOT cursed, the same anti-griefing limit every area
 *   ability in this mod follows (see Crushing Depth Aura); every
 *   enemy mob in range is cursed, and the target player always
 *   is.
 * - The recast watch: every 40 ticks, the same 50 block scan
 *   runs again. An enemy missing ANY of the three effects (it
 *   drank milk, a cleanse fired, the clock ran out) has all
 *   three reapplied fresh at full 600 tick duration, with the
 *   apparition particle. If at least one enemy was recursed in a
 *   wave, the curse sound plays again, quieter. Enemies that
 *   walk into the arena mid-watch are picked up by the same
 *   scan: they are missing all three effects, so they are
 *   cursed on the next wave, which is the designed behavior for
 *   a curse that guards an arena.
 * - The elder itself gains nothing and is never its own enemy;
 *   its allies are excluded by the package rule, so a Turret
 *   Brood raised beside it stays uncursed.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the ELDER_GUARDIAN ghost
 * apparition particle flashes at every cursed enemy (the same
 * full-screen ghost a vanilla elder guardian curse shows its
 * victim), and ELDER_GUARDIAN_CURSE (volume 1.5) tolls as the
 * curse first falls; recast waves toll it again at volume 0.8.
 * The water goes briefly silent in the moment before the first
 * toll in the fiction of the design; mechanically the curse
 * sound and the apparitions are the whole opening. Victims show
 * the vanilla effect sparkles and icons for all three effects.
 *
 * PERFORMANCE: the 50 block scan is one bounded entity query
 * (100 by 40 by 100 box) run once in start() and once per 40
 * tick wave (16 scans per activation at most), each followed by
 * a round-distance pass and effect writes only for enemies
 * actually cursed. tick() returns immediately on every other
 * tick. No registry or config lookups in the tick path; the
 * effect holders are the static MobEffects fields. Particle
 * budget per activation: 1 apparition per cursed enemy per wave;
 * at a generous 20 enemies a wave that is 20 particles, times 16
 * waves, total at most 320 particles.
 *
 * CLEANUP: end() has nothing to undo: no modifiers, no spawns,
 * and the curse effects deliberately bleed off on their own
 * clocks (see the duration note above).
 *
 * State tag keys: none. The watch reads everything it needs
 * live (positions, effect presence) on each wave.
 */
public final class CurseOfTheSunkenCourtAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int RECAST_INTERVAL = 40;
    private static final int EFFECT_TICKS = 600;
    private static final double RANGE = 50.0;

    @Override
    public String id() {
        return "curse_of_the_sunken_court";
    }

    @Override
    public String displayName() {
        return "Curse of the Sunken Court";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= RANGE * RANGE;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The curse falls on the whole court at once.
        int cursed = castCurse(ctx, level, true);
        if (cursed > 0) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.5f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % RECAST_INTERVAL != 0) {
            return; // Cheap early-out: the watch scans on the 40 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The recast wave: anyone in the arena who shook the
        // curse off (or just walked in) is cursed again.
        int recursed = castCurse(ctx, level, false);
        if (recursed > 0) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.8f, 1.0f);
        }
    }

    /**
     * One curse wave: applies the full curse to every enemy in
     * range. On the opening wave (opening = true) every enemy is
     * cursed unconditionally; on recast waves only enemies
     * missing at least one of the three effects are. Returns how
     * many enemies were cursed by this wave.
     */
    private int castCurse(AbilityContext ctx, ServerLevel level, boolean opening) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = ElderGuardianAbilitySupport.enemiesAroundSelf(ctx, RANGE, 20.0, 20.0);
        int cursed = 0;
        for (LivingEntity victim : victims) {
            if (victim.distanceToSqr(mob) > RANGE * RANGE) {
                continue; // The box query is square; the court is round.
            }
            if (!opening && victim.hasEffect(MobEffects.MINING_FATIGUE)
                && victim.hasEffect(MobEffects.WEAKNESS)
                && victim.hasEffect(MobEffects.SLOWNESS)) {
                continue; // Still fully cursed: the watch leaves it alone.
            }
            victim.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, EFFECT_TICKS, 2));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EFFECT_TICKS, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, 0));
            // The ghost apparition flashes across the victim.
            level.sendParticles(ParticleTypes.ELDER_GUARDIAN,
                victim.getX(), victim.getY() + 1.0, victim.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
            cursed++;
        }
        return cursed;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the curse effects bleed off on their
        // own 600 tick clocks, as designed (see the class
        // comment).
    }
}
