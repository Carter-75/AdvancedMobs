package mod.advancedmobs.ability.cod;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Frenzy Current Aura (Cod ability 5 of 5, aura).
 *
 * WHAT IT DOES: the cod churns the water around it
 * into a slow swirl of bubbles and fishing sparkles
 * that marks its hunting ground for thirty seconds.
 * Every allied fish and squid swimming in the swirl
 * gains speed and dolphins_grace and runs rings around
 * slower enemies, and whenever an ally lands a kill
 * inside the ground, the whole swirl tightens: the
 * ring spins faster and throws more spray, up to three
 * steps of frenzy, before settling back down.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cod's current target within 16 blocks
 *   (distance squared 256), plus the cod in water (an
 *   aura of currents needs water to churn).
 * - Duration: 600 ticks (30 seconds), hard finish. The
 *   aura is centered on the caster and follows it; it
 *   is a standing field, not a placed zone, so it
 *   leaves nothing behind when it ends.
 * - The buff pulse (every 10 ticks, one bounded query,
 *   radius 7.0 around the caster, vertical reach 3.0
 *   both ways): the caster and every buff target in
 *   the query (the package BUFF RULE: war allies, plus
 *   any fish or squid that is neither the caster's
 *   target nor its war enemy) gain SPEED I and
 *   DOLPHINS_GRACE, each refreshed to 30 ticks, the
 *   design's two effect lines. Effects are only
 *   refreshed while their owner stays in the swirl;
 *   leaving the ground lets them lapse on their own
 *   clocks.
 * - The kill watch (same pulse): the pulse sums
 *   MobWarData.getKillCount() over the caster and
 *   every Mob caught by the buff query. The first
 *   pulse only records the sum (the baseline). When a
 *   later pulse's sum is higher than the recorded one,
 *   an ally (or the caster) has landed a kill nearby:
 *   the frenzy level rises by 1, capped at 3, and the
 *   frenzy window restarts at 200 ticks from that
 *   pulse. When the window runs out, the level falls
 *   back to 0. The new sum is recorded either way, so
 *   one kill can never retrigger itself.
 * - The swirl (every 4 ticks): an angle stored in the
 *   state tag advances by 0.15 radians per visual tick
 *   plus 0.10 per frenzy level (0.15 at rest, 0.45 at
 *   full frenzy). Two points on a radius 2.5 ring
 *   around the caster, opposite each other at the
 *   stored angle, each send 1 BUBBLE, and 1 FISHING
 *   sparkle is sent at the caster: the churned hunting
 *   ground, drawn in particles only. At frenzy level 2
 *   or higher, each ring point sends 1 extra BUBBLE:
 *   the tighter, sprayier spin of the design's text.
 *   FISH_SWIM plays softly (volume 0.6, pitch 1.0 plus
 *   0.15 per frenzy level) every 100 ticks.
 * - end(): nothing to strip. The caster's own buffs
 *   were refreshed on the same 30-tick clocks as every
 *   ally's and lapse on their own; the swirl is
 *   particles and leaves nothing behind.
 *
 * WHAT IT LOOKS LIKE: a slow double ring of rising
 * bubbles circling the cod, fishing sparkles drifting
 * through it, friendly fish and squid streaking through
 * the churn, and the whole ring visibly winding tighter
 * and faster each time something dies inside it.
 *
 * PERFORMANCE: the buff pulse is one bounded query
 * every 10 ticks touching at most the entities inside
 * radius 7; the visual pass is 5 to 7 single-particle
 * sends every 4 ticks; nothing runs on the other
 * ticks. Particle budget per activation: 150 visual
 * passes times at most 7 = 1050 sends of single
 * particles in the absolute worst case (full frenzy
 * for the whole 30 seconds, which the 200-tick window
 * and 3-kill ladder make unreachable in practice),
 * typical total at most 750.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * State tag keys: "angle" (the swirl's current ring
 * angle, radians), "kill_sum" (the last recorded ally
 * kill count sum; -1 until the baseline pulse),
 * "frenzy" (the current frenzy level, 0 to 3),
 * "frenzy_until" (the activation tick the frenzy
 * window ends at).
 */
public final class FrenzyCurrentAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final int PULSE_INTERVAL = 10;
    private static final int VISUAL_INTERVAL = 4;
    private static final double AURA_RADIUS = 7.0;
    private static final int BUFF_TICKS = 30;
    private static final int FRENZY_WINDOW_TICKS = 200;
    private static final int MAX_FRENZY = 3;

    @Override
    public String id() {
        return "frenzy_current_aura";
    }

    @Override
    public String displayName() {
        return "Frenzy Current Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.putDouble("angle", 0.0);
        state.putInt("kill_sum", -1);
        state.putInt("frenzy", 0);
        state.putInt("frenzy_until", 0);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 0.8f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % PULSE_INTERVAL == 0) {
            runBuffPulse(ctx, ticks);
        }
        if (ticks % VISUAL_INTERVAL == 0) {
            runVisualPass(ctx, ticks);
        }
    }

    /** One buff pulse: refresh the school, and watch for a fresh ally kill. Every 10 ticks. */
    private void runBuffPulse(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        // The caster always swims its own ground.
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, BUFF_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, BUFF_TICKS, 0));

        int killSum = MobWarData.get(caster).getKillCount();
        List<LivingEntity> school = CodAbilitySupport.livingInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, 3.0, 3.0,
            e -> CodAbilitySupport.isBuffTarget(ctx, e));
        for (LivingEntity mate : school) {
            mate.addEffect(new MobEffectInstance(MobEffects.SPEED, BUFF_TICKS, 0));
            mate.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, BUFF_TICKS, 0));
            if (mate instanceof Mob mateMob) {
                killSum += MobWarData.get(mateMob).getKillCount();
            }
        }

        // The kill watch: a rising sum is a fresh kill inside the ground.
        int recorded = state.getIntOr("kill_sum", -1);
        if (recorded >= 0 && killSum > recorded) {
            state.putInt("frenzy", Math.min(MAX_FRENZY, state.getIntOr("frenzy", 0) + 1));
            state.putInt("frenzy_until", ticks + FRENZY_WINDOW_TICKS);
        } else if (state.getIntOr("frenzy", 0) > 0 && ticks >= state.getIntOr("frenzy_until", 0)) {
            state.putInt("frenzy", 0);
        }
        state.putInt("kill_sum", killSum);
    }

    /** One swirl pass: advance the ring and draw it. Every 4 ticks. */
    private void runVisualPass(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int frenzy = state.getIntOr("frenzy", 0);
        double angle = state.getDoubleOr("angle", 0.0) + 0.15 + 0.10 * frenzy;
        state.putDouble("angle", angle);

        for (int i = 0; i < 2; i++) {
            double pointAngle = angle + i * Math.PI;
            double px = caster.getX() + Math.cos(pointAngle) * 2.5;
            double pz = caster.getZ() + Math.sin(pointAngle) * 2.5;
            level.sendParticles(ParticleTypes.BUBBLE, px, caster.getY() + 0.3, pz,
                frenzy >= 2 ? 2 : 1, 0.2, 0.3, 0.2, 0.03);
        }
        level.sendParticles(ParticleTypes.FISHING, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            1, 0.6, 0.4, 0.6, 0.02);
        if (ticks % 100 == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 0.6f, 1.0f + 0.15f * frenzy);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: every buff rides its own 30-tick clock out
        // and the swirl is particles only (see the class comment).
    }
}
