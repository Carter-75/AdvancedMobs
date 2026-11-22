package mod.advancedmobs.ability.ravager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.AbstractIllager;

import java.util.List;

/**
 * Roar of the Raid (Ravager ability 3 of 5, aura).
 *
 * WHAT IT DOES: the ravager throws back its head and roars,
 * and the roar is a physical thing: a SONIC_BOOM ring rolls
 * outward across the field in three expanding waves, and
 * every enemy a wave washes over is weakened and slowed to a
 * crawl. Every illager ally who hears it surges with
 * STRENGTH: the moment the raid stops skirmishing and starts
 * winning. Even the ravager needs a breath after a roar that
 * size, and it stands slowed once the activation ends.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the ravager's current target within 16 blocks (distance
 *   squared 256) AND at least 1 illager ally within 16
 *   blocks (RavagerAbilitySupport's illager ally rule:
 *   AbstractIllager with an either-direction alliance record
 *   or fighting the same target). A roar with no raid to
 *   hear it never burns the cooldown (the Carrion Call
 *   precedent). canTrigger runs one bounded query, the same
 *   cost the framework already pays for combat checks.
 * - The surge (start()): every illager ally within 16 blocks
 *   of the ravager gains STRENGTH amplifier 0 for 200 ticks
 *   (10 seconds), with 3 ENCHANTED_HIT sparkles each. The
 *   ravager itself is not strengthened; its weapon is the
 *   roar. RAVAGER_ROAR plays at volume 2.0.
 * - The rings (ticks 10, 20, 30): three waves roll out from
 *   the ravager's live position at radii 4, 8, and 12 blocks.
 *   On each wave tick, one bounded query (radius + 1.5 box)
 *   finds the enemies whose horizontal distance from the
 *   ravager falls inside the wave band (radius plus or minus
 *   1.5 blocks) and who have not been caught by an earlier
 *   wave of this roar (tracked in the state tag's "hit"
 *   compound, so a victim is rolled over at most once even
 *   when two bands overlap them). Each caught enemy gains
 *   WEAKNESS amplifier 0 for 100 ticks and SLOWNESS
 *   amplifier 1 for 100 ticks (5 seconds of crawl). The wave
 *   is drawn with SONIC_BOOM particles placed on the
 *   circumference at the ravager's chest height: 10 motes
 *   for the radius 4 ring, 16 for radius 8, 22 for radius 12.
 * - The breath (end()): the ravager gains SLOWNESS amplifier
 *   0 for 60 ticks, applied in end() so it outlives the
 *   activation (the Colossal Grief and Stubborn Hide Brace
 *   precedent: end-of-ability costs are applied in end()).
 *   A ravager that somehow had SLOWNESS from another source
 *   loses it when this one is refreshed out, an accepted
 *   edge case shared with every fixed-duration effect in
 *   this system. The activation finishes at tick 40, after
 *   the third ring has rolled.
 *
 * WHAT IT LOOKS LIKE: the head throws back with a
 * RAVAGER_ROAR at volume 2.0, ENCHANTED_HIT sparkles flare
 * on every illager in earshot as the strength lands, and
 * three visible SONIC_BOOM rings roll across the field at
 * chest height, each leaving weakened, crawling enemies
 * behind it. The ravager's own SLOWNESS sparkles show while
 * it catches its breath afterwards.
 *
 * PERFORMANCE: start() is one bounded query. tick() returns
 * immediately except on the three wave ticks, each one
 * bounded query plus one circumference draw. Particle budget
 * per activation: 48 SONIC_BOOM plus 3 per surged ally (at
 * most about 24 in a full patrol): at most about 72.
 *
 * CLEANUP: end() applies only the breath SLOWNESS. The
 * allies' STRENGTH and the enemies' debuffs are timed
 * effects and expire on their own clocks; no modifiers are
 * used and no blocks are changed, so the terrain rule does
 * not apply (stated here per the standing convention).
 *
 * State tag keys: "hit" (compound: victim UUID string to 1,
 * everyone an earlier wave of this roar has already caught).
 */
public final class RoarOfTheRaidAbility implements MobAbility {

    static final int DURATION_TICKS = 40;
    private static final int[] WAVE_TICKS = {10, 20, 30};
    private static final double[] WAVE_RADII = {4.0, 8.0, 12.0};
    private static final int[] WAVE_MOTES = {10, 16, 22};
    private static final double BAND_HALF_WIDTH = 1.5;
    private static final int ALLY_RANGE = 16;

    @Override
    public String id() {
        return "roar_of_the_raid";
    }

    @Override
    public String displayName() {
        return "Roar of the Raid";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        // The roar needs a raid: at least one illager ally in
        // earshot, or the activation is wasted breath.
        return !RavagerAbilitySupport.illagerAlliesAroundSelf(ctx, ALLY_RANGE, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("hit", new CompoundTag());
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_ROAR,
            SoundSource.HOSTILE, 2.0f, 1.0f);

        // The surge: every illager ally in earshot surges.
        List<AbstractIllager> allies = RavagerAbilitySupport.illagerAlliesAroundSelf(ctx, ALLY_RANGE, 3.0, 3.0);
        for (AbstractIllager ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 200, 0));
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, ally.getX(), ally.getY() + 1.2, ally.getZ(),
                3, 0.4, 0.5, 0.4, 0.1);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        int wave = -1;
        for (int i = 0; i < WAVE_TICKS.length; i++) {
            if (ticks == WAVE_TICKS[i]) {
                wave = i;
                break;
            }
        }
        if (wave < 0) {
            return; // Cheap early-out: only the three wave ticks work.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        double radius = WAVE_RADII[wave];

        // Draw the ring at chest height.
        int motes = WAVE_MOTES[wave];
        for (int i = 0; i < motes; i++) {
            double angle = (Math.PI * 2.0 * i) / motes;
            level.sendParticles(ParticleTypes.SONIC_BOOM,
                mob.getX() + Math.cos(angle) * radius, mob.getY() + 1.2, mob.getZ() + Math.sin(angle) * radius,
                1, 0.0, 0.0, 0.0, 0.0);
        }

        // Catch everyone standing in the wave band, once per
        // roar.
        CompoundTag hit = ctx.state().getCompoundOrEmpty("hit");
        List<LivingEntity> victims = RavagerAbilitySupport.enemiesAroundSelf(ctx, radius + BAND_HALF_WIDTH, 2.5, 2.5);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - mob.getX();
            double dz = victim.getZ() - mob.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < radius - BAND_HALF_WIDTH || distance > radius + BAND_HALF_WIDTH) {
                continue;
            }
            String key = victim.getUUID().toString();
            if (hit.contains(key)) {
                continue;
            }
            hit.putInt(key, 1);
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
        }
        ctx.state().put("hit", hit);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The breath: applied here so it outlives the
        // activation. Nothing else to undo: no modifiers, no
        // summons, no blocks.
        ctx.mob().addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
    }
}
