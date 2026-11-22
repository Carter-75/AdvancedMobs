package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;

import java.util.List;

/**
 * Bastion Standard Aura (Piglin Brute ability 5 of 5, aura
 * and defense).
 *
 * WHAT IT DOES: the brute plants its presence like a banner.
 * Piglin and brute allies fighting beside it gain RESISTANCE
 * and a pool of ABSORPTION, refreshed for as long as they
 * hold the line inside the aura, while gold DUST motes drift
 * in a slow ring around the brute's position showing exactly
 * how far the protection reaches. A piglin line around a
 * standard brute is genuinely hard to break, and the brute
 * at its center becomes the obvious and worst target.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   at least one aura ally (PiglinBruteAbilitySupport AURA
 *   ALLY RULE: an AbstractPiglin with an either-direction
 *   alliance record, or one fighting the same target as the
 *   brute) within 10 blocks of the brute (one bounded query,
 *   the Carrion Call and Hive Mind Link precedent). A lone
 *   brute's standard protects nobody, so an empty aura never
 *   burns the cooldown.
 * - Duration: exactly 400 ticks (20 seconds).
 * - The blessing pulse, every 20 ticks: every aura ally
 *   within radius 10 of the brute (3.0 below to 3.0 above)
 *   gains RESISTANCE I and ABSORPTION I, each for 40 ticks.
 *   The 40 against the 20 tick pulse is deliberate: an ally
 *   that steps out of the ring keeps its blessing for up to
 *   two seconds of bleed-off, and an ally holding the line
 *   has both effects refreshed forever, its absorption pool
 *   (4 points at amplifier 0) topping back up as the effect
 *   reapplies. The brute itself is NOT blessed: the design
 *   gives the protection to the line, and the brute's own
 *   toughness is the Bulwark Form's job.
 * - The ring: every 4 ticks, 10 gold DUST motes (color
 *   0xFFD633, scale 1.2, the Piglin package's gold) are drawn
 *   on the radius 10 circle at the brute's feet plus 0.3, the
 *   ring's angular offset advancing 0.06 radians per draw so
 *   the motes slowly orbit. The ring is centered on the
 *   brute's LIVE position: the standard moves with its
 *   bearer, and the blessing pulse reads the same center, so
 *   the visible ring and the real protection never disagree.
 *
 * WHAT IT LOOKS LIKE: a PIGLIN_BRUTE_ANGRY plant-the-banner
 * roar (volume 1.0, pitch 0.8) at the start, then the slow
 * gold ring turning around the brute for 20 seconds, with
 * the vanilla RESISTANCE and ABSORPTION presentation on
 * every blessed ally (absorption hearts over the line).
 *
 * TERRAIN: this ability places and breaks no blocks in
 * either mobGriefing mode; the aura is a zone effect around
 * the brute, not block placement, so the terrain gate does
 * not apply.
 *
 * PERFORMANCE: the blessing is one bounded query every 20
 * ticks over at most a bastion crowd; the ring is one
 * 10-particle send every 4 ticks plus an angle write; all
 * other ticks return immediately. Particle budget per
 * activation: 100 draws times 10 = at most 1000 motes, the
 * price of a ring an enemy can read at a glance, stated here
 * so a future pass can trim the draw interval if it must.
 *
 * CLEANUP: end() removes nothing: both blessing effects are
 * timed at 40 ticks and bleed off on their own clocks after
 * the last pulse, and no attribute, entity, or block was
 * created. If the brute dies mid-aura the framework
 * termination path runs this same (empty) end(), and the
 * line's blessings fade within two seconds of the standard
 * falling, which is the design's intent.
 *
 * State tag keys: "ring_angle" (the ring's current angular
 * offset, in radians).
 */
public final class BastionStandardAuraAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final double AURA_RADIUS = 10.0;
    private static final int PULSE_INTERVAL = 20;
    private static final int BLESSING_TICKS = 40;
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(0xFFD633, 1.2f);

    @Override
    public String id() {
        return "bastion_standard_aura";
    }

    @Override
    public String displayName() {
        return "Bastion Standard Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return !PiglinBruteAbilitySupport.auraAlliesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putDouble("ring_angle", 0.0);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_BRUTE_ANGRY,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean pulse = ticks % PULSE_INTERVAL == 0;
        boolean ring = ticks % 4 == 0;
        if (!pulse && !ring) {
            return; // Cheap early-out: work happens on the 4 and 20 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        if (pulse) {
            List<AbstractPiglin> allies = PiglinBruteAbilitySupport.auraAlliesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0);
            for (AbstractPiglin ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, BLESSING_TICKS, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, BLESSING_TICKS, 0));
            }
        }
        if (ring) {
            double angle = ctx.state().getDoubleOr("ring_angle", 0.0);
            for (int i = 0; i < 10; i++) {
                double a = angle + i * (Math.PI * 2.0 / 10.0);
                level.sendParticles(GOLD_DUST, mob.getX() + Math.cos(a) * AURA_RADIUS,
                    mob.getY() + 0.3, mob.getZ() + Math.sin(a) * AURA_RADIUS,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
            ctx.state().putDouble("ring_angle", angle + 0.06);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to remove: the blessings are timed effects
        // that bleed off on their own (see the spec comment).
    }
}
