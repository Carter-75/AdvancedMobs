package mod.advancedmobs.ability.goat;

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
 * Screaming Aura (Goat ability 2 of 5, aura).
 *
 * WHAT IT DOES: the goat opens its mouth and screams, and
 * keeps screaming for 15 seconds, sound waves rolling off
 * it in pulses. Every enemy near the screaming goat is
 * rattled: queasy, weakened, and unable to keep a clean
 * attack rhythm going through the sheer noise. The goat
 * itself is too stubborn to care and just keeps fighting.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the goat's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The scream pulses (ticks 1, 41, 81, and every 40 ticks
 *   after: 8 pulses over the duration): GOAT_SCREAMING_AMBIENT
 *   at volume 1.5, pitch 1.0 plays at the goat (the ability
 *   makes any goat scream like a screaming goat for its
 *   duration, whether or not it was born one), a ring of 8
 *   NOTE particles rolls out at head height around the
 *   goat, and 4 ANGRY_VILLAGER particles puff over its
 *   head (the visible annoyance of the noise).
 * - The rattle pulse (every 10 ticks, 30 pulses): one
 *   bounded query, radius 6.0 around the goat (3 below to
 *   3 above). Every enemy in it (GoatAbilitySupport rules)
 *   gains NAUSEA for 60 ticks and WEAKNESS I (amplifier 0)
 *   for 30 ticks, both refreshed each pulse, so a victim
 *   who stays in the din stays rattled for the whole aura
 *   and a few seconds past leaving it. The design's
 *   "attacks keep getting interrupted" is carried by these
 *   two effects, per the design's own Uses line: NAUSEA
 *   wrecks aim and WEAKNESS cuts the damage of every swing
 *   that does land; there is no separate target-clearing
 *   mechanic (clearing war targets from here would fight
 *   the war system's own targeting).
 * - Between pulses, 2 NOTE particles drift from the goat's
 *   mouth every 5 ticks: the screaming never actually
 *   stops, the pulses are just its peaks.
 * - The goat takes no self effect and no self debuff: too
 *   stubborn to care. Its own AI is untouched: it keeps
 *   chasing and headbutting its target through the noise.
 * - end(): nothing to clean up. The effects on victims
 *   ride their own clocks out (at most 60 ticks past the
 *   last refresh), matching the other aura packages.
 *
 * WHAT IT LOOKS LIKE: a goat standing in the middle of a
 * fight screaming nonstop, NOTE glyphs rippling off it in
 * rings every 2 seconds, angry faces puffing over its
 * head, while everything around it sways with nausea
 * sparkles and swings weakly.
 *
 * PERFORMANCE: tick() returns immediately except on the
 * 5-tick particle interval, the 10-tick rattle pulse (one
 * bounded radius-6.0 query), and the 40-tick scream pulse
 * (particles and one sound, no query). Particle budget per
 * activation: scream pulses 8 times 12 = 96, mouth notes
 * 60 times 2 = 120, total at most 216 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the duration and pulses run off
 * the framework's tick counter).
 */
public final class ScreamingAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int SCREAM_INTERVAL = 40;
    private static final int RATTLE_INTERVAL = 10;
    private static final int NOTE_INTERVAL = 5;
    private static final double RATTLE_RADIUS = 6.0;

    @Override
    public String id() {
        return "screaming_aura";
    }

    @Override
    public String displayName() {
        return "Screaming Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // The first scream fires from tick() at tick 1 via
        // the pulse schedule; start() has nothing to set up.
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        double headY = caster.getY() + caster.getBbHeight() + 0.2;

        if ((ticks - 1) % SCREAM_INTERVAL == 0) {
            // The scream peak: sound, a NOTE ring, angry puffs.
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.GOAT_SCREAMING_AMBIENT, SoundSource.HOSTILE, 1.5f, 1.0f);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0;
                level.sendParticles(ParticleTypes.NOTE,
                    caster.getX() + Math.cos(angle) * 1.5, headY, caster.getZ() + Math.sin(angle) * 1.5,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), headY, caster.getZ(),
                4, 0.4, 0.2, 0.4, 0.05);
        }

        if (ticks % RATTLE_INTERVAL == 0) {
            // The rattle: refresh the din's debuffs on everyone close.
            List<LivingEntity> enemies = GoatAbilitySupport.enemiesAroundSelf(ctx, RATTLE_RADIUS, 3.0, 3.0);
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 60, 0));
                enemy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 0));
            }
        } else if (ticks % NOTE_INTERVAL == 0) {
            // The screaming between peaks: notes at the mouth.
            level.sendParticles(ParticleTypes.NOTE, caster.getX(), headY, caster.getZ(),
                2, 0.3, 0.15, 0.3, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: victim effects expire on their
        // own clocks (see the class comment).
    }
}
