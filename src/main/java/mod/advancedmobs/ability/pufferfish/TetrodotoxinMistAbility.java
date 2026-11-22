package mod.advancedmobs.ability.pufferfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Tetrodotoxin Mist (Pufferfish ability 2 of 5,
 * attack / area denial).
 *
 * WHAT IT DOES: the pufferfish vents a billowing
 * green cloud of its own toxin. Noxious gas rolls
 * outward at the cast point and hangs there for
 * twenty seconds, thick enough to hide in, and
 * anything hostile breathing it is poisoned and
 * nauseated while the pufferfish sits happily in the
 * middle of its own smog.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the pufferfish's current target within
 *   12 blocks (distance squared 144), plus the
 *   pufferfish in water (the toxin vents into water;
 *   the cloud is drawn at the water's level around
 *   the cast point).
 * - The vent (start()): the cloud's center is fixed
 *   at the pufferfish's live position and recorded in
 *   the state tag; it never moves again, even if the
 *   fish swims off (the design's lingering area
 *   denial, the Downcurrent Net planting pattern).
 *   PUFFER_FISH_BLOW_OUT plays at volume 1.2, pitch
 *   0.7, a long low vent, and the opening billow is
 *   14 NOXIOUS_GAS_CLOUD plus 6 NOXIOUS_GAS around
 *   the fish.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The cloud (every 2 ticks): 2 NOXIOUS_GAS_CLOUD
 *   sent at random points inside radius 3.5 of the
 *   center at the center's height (the rolling green
 *   body of the mist; positions come from the mob's
 *   own random source), plus 1 NOXIOUS_GAS at the
 *   center every 4 ticks (the denser wisps at the
 *   vent). Both particle types are the 26.2 sulfur
 *   set's gas particles, plain SimpleParticleTypes,
 *   so the sends carry no per-send option building.
 * - The breathing (every 10 ticks, one bounded
 *   query, radius 4.0 around the center, vertical
 *   reach 2.0 both ways): every enemy (the package
 *   enemy rule) gains POISON I and NAUSEA I, each
 *   refreshed to 60 ticks, so a victim that stays in
 *   the cloud stays poisoned and sick, and one that
 *   escapes carries about 3 seconds of each out with
 *   it. The caster is never its own enemy under the
 *   package rule, which is the code form of the
 *   design's immunity: the fish breathes its own
 *   toxin without effect. Allies are likewise never
 *   enemies and never poisoned.
 *
 * WHAT IT LOOKS LIKE: a venting hiss, then a low
 * green fog bank rolling in place, gas wisps curling
 * up through it, hostile shapes inside it flashing
 * poison bubbles and swaying with nausea while the
 * pufferfish's silhouette sits at the center.
 *
 * PERFORMANCE: all work runs on intervals: 2
 * particle sends every 2 ticks, 1 more every 4
 * ticks, and one bounded radius-4.0 query every 10
 * ticks with at most one effect pair per enemy
 * found. Nothing runs per tick. Particle budget per
 * activation: opening 20, cloud at most 200 times 2
 * = 400, wisps at most 100, total at most 520.
 *
 * TERRAIN: this ability changes no blocks; the mist
 * is a zone effect in code drawn with particles, not
 * placed blocks, so the mobGriefing gate does not
 * apply.
 *
 * CLEANUP: end() does nothing: the ability holds no
 * modifiers, no summons, and no caster state, and
 * victim effects ride their own 60-tick clocks out
 * after the last pulse.
 *
 * State tag keys: "cx", "cy", "cz" (the cloud's
 * fixed center).
 */
public final class TetrodotoxinMistAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 10;
    private static final double CLOUD_RADIUS = 4.0;
    private static final double DRAW_RADIUS = 3.5;

    @Override
    public String id() {
        return "tetrodotoxin_mist";
    }

    @Override
    public String displayName() {
        return "Tetrodotoxin Mist";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.HOSTILE, 1.2f, 0.7f);
            level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD,
                caster.getX(), caster.getY() + 0.3, caster.getZ(),
                14, 1.2, 0.5, 1.2, 0.03);
            level.sendParticles(ParticleTypes.NOXIOUS_GAS,
                caster.getX(), caster.getY() + 0.3, caster.getZ(),
                6, 0.8, 0.4, 0.8, 0.03);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());

        // The rolling body of the mist.
        if (ticks % 2 == 0) {
            RandomSource random = ctx.random();
            for (int i = 0; i < 2; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double reach = random.nextDouble() * DRAW_RADIUS;
                level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD,
                    cx + Math.cos(angle) * reach, cy + 0.2, cz + Math.sin(angle) * reach,
                    1, 0.3, 0.2, 0.3, 0.01);
            }
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.NOXIOUS_GAS, cx, cy + 0.4, cz,
                1, 0.5, 0.3, 0.5, 0.02);
        }

        // The breathing: one bounded query per pulse.
        if (ticks % PULSE_INTERVAL == 0) {
            List<LivingEntity> victims = PufferfishAbilitySupport.enemiesInBox(
                ctx, cx, cy, cz, CLOUD_RADIUS, 2.0, 2.0);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
                victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 60, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: no modifiers, no summons, no
        // caster state (see the CLEANUP note above).
    }
}
