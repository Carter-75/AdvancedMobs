package mod.advancedmobs.ability.sniffer;

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
 * Pollen Haze (Sniffer ability 4 of 5, aura).
 *
 * WHAT IT DOES: the sniffer shakes itself out and the
 * air fills with a drifting haze of ancient pollen:
 * spore blossom motes and pink cherry petals hanging over
 * the whole fight. Enemies breathing it are nauseated and
 * slowed to a trudge, while the sniffer's allies in the
 * haze regenerate, soothed by the ancient pollen. The
 * haze travels with the sniffer, which keeps fighting
 * normally underneath it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sniffer's current target within 14 blocks
 *   (distance squared 196).
 * - Duration: 400 ticks (20 seconds).
 * - The haze (tick()): every 2 ticks, 2 SPORE_BLOSSOM_AIR
 *   motes drift at a random point within 5 blocks of the
 *   sniffer at 0.5 to 2.0 blocks of height (the design's
 *   spore blossom air line); every 4 ticks, 1
 *   CHERRY_LEAVES petal drifts the same way (the design's
 *   cherry leaves line).
 * - The breathing (pulse, every 10 ticks): two bounded
 *   queries (radius 7, 3 below and 3 above the sniffer).
 *   Every enemy in the enemy query gains NAUSEA I and
 *   SLOWNESS I refreshed to 30 ticks (the design's nausea
 *   and slowness lines). Every ally in the ally query
 *   gains REGENERATION I refreshed to 30 ticks (the
 *   design's regeneration line; ally targeting follows
 *   the package ally rule, the war system's alliance
 *   record, which is the team based targeting the design
 *   names). The caster itself is not an ally under the
 *   package rule and is deliberately not regenerated
 *   here: the haze is a gift to its friends, and Rooted
 *   Colossus is the sniffer's self-healing ability.
 * - Sounds: SNIFFER_SCENTING at start (volume 1.2, pitch
 *   1.0), the long exhale that starts the haze.
 * - end(): nothing was applied to the caster and nothing
 *   is tracked, so end() strips nothing; victim and ally
 *   effects ride their own 30-tick clocks out (the Bray
 *   of Doom precedent). Idempotent.
 *
 * WHAT IT LOOKS LIKE: a slow green-gold fog rolling off
 * a shaking sniffer, pink petals turning in it, enemies
 * staggering green-faced through the murk while the
 * sniffer's pack mates close their eyes and breathe
 * deep, wounds knitting.
 *
 * PERFORMANCE: the haze is 2 particle sends every 2 ticks
 * and 1 every 4; the breathing is two bounded queries
 * every 10 ticks. No per-tick queries. Particle budget
 * per activation: spore 200 sends times 2 = 400, petals
 * 100 sends times 1 = 100, total at most 500.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply. The haze is a zone
 * effect carried by particles and effect pulses, not by
 * placed blocks.
 *
 * State tag keys: none (the pulses key off the
 * framework's tick counter).
 */
public final class PollenHazeAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final double HAZE_RADIUS = 7.0;
    private static final int PULSE_INTERVAL = 10;
    private static final int EFFECT_TICKS = 30;

    @Override
    public String id() {
        return "pollen_haze";
    }

    @Override
    public String displayName() {
        return "Pollen Haze";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNIFFER_SCENTING, SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        // The haze: spores every 2 ticks, petals every 4.
        if (ticks % 2 == 0) {
            drift(ctx, level, true);
        }
        if (ticks % 4 == 0) {
            drift(ctx, level, false);
        }

        // The breathing.
        if (ticks % PULSE_INTERVAL == 0) {
            List<LivingEntity> enemies = SnifferAbilitySupport.enemiesAroundSelf(ctx, HAZE_RADIUS, 3.0, 3.0);
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, EFFECT_TICKS, 0));
                enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, 0));
            }
            List<LivingEntity> allies = SnifferAbilitySupport.alliesAroundSelf(ctx, HAZE_RADIUS, 3.0, 3.0);
            for (LivingEntity ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
            }
        }
    }

    /** Sends one drift of haze particles at a random point around the sniffer. */
    private void drift(AbilityContext ctx, ServerLevel level, boolean spores) {
        Mob caster = ctx.mob();
        double angle = ctx.random().nextDouble() * Math.PI * 2.0;
        double dist = ctx.random().nextDouble() * 5.0;
        double x = caster.getX() + Math.cos(angle) * dist;
        double z = caster.getZ() + Math.sin(angle) * dist;
        double y = caster.getY() + 0.5 + ctx.random().nextDouble() * 1.5;
        if (spores) {
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 2, 0.3, 0.3, 0.3, 0.02);
        } else {
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, x, y, z, 1, 0.3, 0.2, 0.3, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing applied to the caster, nothing tracked:
        // victim and ally effects ride their own clocks out.
    }
}
