package mod.advancedmobs.ability.traderllama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
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
 * Hard Bargain Aura (Trader Llama ability 5 of 5, aura).
 *
 * WHAT IT DOES: a smug golden shimmer hangs around the
 * trader llama, the smell of a good deal. Allies fighting
 * inside it feel lucky and hit harder; enemies inside it
 * feel fleeced, every trade gone against them, and their
 * blows lose their strength.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader llama's current target within 14 blocks
 *   (distance squared 196).
 * - Duration: 400 ticks (20 seconds), hard finish. The
 *   aura is centered on the caster and moves with it;
 *   radius 8 blocks, vertical reach 3.
 * - The bargain (tick(), every 10 ticks): one bounded
 *   query for allies and one for enemies inside the
 *   radius. The caster and every ally (the package's ally
 *   rule) gain STRENGTH I (amplifier 0) and LUCK I
 *   (amplifier 0), each refreshed to 40 ticks: the design's
 *   LUCK flavored crits (LUCK is the game's own fortune
 *   effect; paired with STRENGTH it is the aura's whole
 *   offensive gift, there is no separate crit mechanic to
 *   hook server-side) and small STRENGTH. Every enemy (the
 *   package's enemy rule) gains WEAKNESS I (amplifier 0)
 *   refreshed to 40 ticks: fleeced, their attack strength
 *   sapped for as long as they stay in the shimmer. The
 *   effects ride their own 40 tick refresh clocks; end()
 *   strips none of them, so the last pulse lingers at most
 *   40 ticks past the aura (the Caravan Call precedent).
 * - The shimmer (tick(), every 10 ticks, with the pulse):
 *   6 gold ENTITY_EFFECT motes (0.85, 0.65, 0.15) placed on
 *   a ring of radius 2.5 around the caster at chest height,
 *   the ring's phase advancing 0.5 radians per pulse so
 *   the sparkles slowly orbit, plus 3 HAPPY_VILLAGER motes
 *   above the caster and 3 CRIT sparkles at its chest (the
 *   lucky-crit flavor made visible).
 * - start(): LLAMA_AMBIENT at volume 1.2, pitch 1.1 (the
 *   smug bray) with 6 NOTE particles over the caster.
 * - end(): nothing to undo. No modifiers are applied and
 *   the pulse effects expire on their own clocks.
 *
 * WHAT IT LOOKS LIKE: a slow orbit of golden sparkles
 * around a very pleased llama, green happy motes drifting
 * up, the occasional lucky crit sparkle at its chest,
 * while its allies glow with good fortune and its enemies
 * swing like they have been talked down on every deal of
 * their lives.
 *
 * PERFORMANCE: tick() returns immediately except every
 * 10th tick, where it runs two bounded queries (radius 8)
 * and sends 12 particles: no per-tick work, no registry or
 * config lookups in the hot path (the one particle option
 * carrying data is a pre-built static final). Particle
 * budget per activation: 6 at start plus 40 pulses times
 * 12 = 480, total at most 486 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none of its own (the duration runs off
 * the framework's tick counter).
 */
public final class HardBargainAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 10;
    private static final int EFFECT_TICKS = 40;
    private static final double AURA_RADIUS = 8.0;
    private static final double RING_RADIUS = 2.5;
    private static final ColorParticleOption GOLD_SPARKLE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.85f, 0.65f, 0.15f);

    @Override
    public String id() {
        return "hard_bargain_aura";
    }

    @Override
    public String displayName() {
        return "Hard Bargain Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        // The smug bray: business is good.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 1.1f);
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.4, caster.getZ(),
            6, 0.6, 0.3, 0.6, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the bargain pulses every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The good side of the deal: the caster and its allies.
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.LUCK, EFFECT_TICKS, 0));
        List<LivingEntity> allies = TraderLlamaAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, 3.0, 3.0);
        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_TICKS, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.LUCK, EFFECT_TICKS, 0));
        }

        // The fleeced side: every enemy in the shimmer.
        List<LivingEntity> enemies = TraderLlamaAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EFFECT_TICKS, 0));
        }

        // The shimmer: an orbiting gold ring, happy motes, lucky sparkles.
        double phase = (ticks / PULSE_INTERVAL) * 0.5;
        for (int i = 0; i < 6; i++) {
            double angle = phase + i * (Math.PI * 2.0 / 6.0);
            level.sendParticles(GOLD_SPARKLE,
                caster.getX() + Math.cos(angle) * RING_RADIUS,
                caster.getY() + 1.0,
                caster.getZ() + Math.sin(angle) * RING_RADIUS,
                1, 0.0, 0.0, 0.0, 0.0);
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, caster.getX(), caster.getY() + 1.6, caster.getZ(),
            3, 0.5, 0.3, 0.5, 0.02);
        level.sendParticles(ParticleTypes.CRIT, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            3, 0.4, 0.4, 0.4, 0.1);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the pulse effects expire on their
        // own 40 tick clocks, at most 40 ticks past the aura.
    }
}
