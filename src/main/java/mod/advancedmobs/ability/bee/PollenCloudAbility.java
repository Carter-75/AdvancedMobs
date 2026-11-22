package mod.advancedmobs.ability.bee;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Pollen Cloud (Bee ability 3 of 5, aura).
 *
 * WHAT IT DOES: the bee shakes out a drifting cloud of
 * falling nectar and happy sparkles that hangs in the air
 * where it fights. Allies inside the cloud slowly
 * regenerate, while enemies wading through it are weighed
 * down with slowness as pollen cakes their joints.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bee's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The cloud (start()): the cloud center is fixed at the
 *   bee's position at cast time and recorded in the state
 *   tag; the cloud does NOT follow the bee. Its radius is
 *   4.0 blocks horizontally, reaching 2.5 blocks below and
 *   2.5 blocks above the center.
 * - The pulse (tick(), every 10 ticks): one bounded entity
 *   query over the cloud box. The caster and every ally
 *   inside (the shared ally rule) gain REGENERATION I
 *   (amplifier 0) refreshed to 30 ticks; every enemy inside
 *   (the shared enemy rule) gains SLOWNESS I (amplifier 0)
 *   refreshed to 30 ticks. A creature that leaves the cloud
 *   simply stops being refreshed and its last refresh
 *   expires on its own clock; end() removes nothing.
 * - The look (tick(), every 5 ticks): 3 FALLING_NECTAR and
 *   2 HAPPY_VILLAGER particles drift at random offsets
 *   inside the cloud (horizontal spread 3.5, vertical
 *   spread 1.5 around the center), the visible body of the
 *   cloud.
 * - end(): nothing to clean up. Every effect this ability
 *   applies is a short refresh that expires on its own.
 *
 * WHAT IT LOOKS LIKE: a soft golden haze hanging in the air
 * where the bee cast it, nectar dripping down through it
 * and green happy sparkles rising, friends inside it
 * knitting their wounds closed while enemies drag through
 * it half a step slower.
 *
 * PERFORMANCE: the effect pulse is one bounded query every
 * 10 ticks; the visual pulse is 5 particles every 5 ticks
 * with no query. Nothing runs on the other ticks. Particle
 * budget per activation: 60 visual pulses times 5 = 300
 * particles.
 *
 * TERRAIN: this ability changes no blocks; it is a zone
 * effect, so the mobGriefing gate does not apply.
 *
 * State tag keys: "cx", "cy", "cz" (the fixed cloud center).
 */
public final class PollenCloudAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 10;
    private static final int VISUAL_INTERVAL = 5;
    private static final double RADIUS = 4.0;
    private static final double Y_REACH = 2.5;

    @Override
    public String id() {
        return "pollen_cloud";
    }

    @Override
    public String displayName() {
        return "Pollen Cloud";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The cloud hangs where the bee fights at cast time.
        ctx.state().putDouble("cx", caster.getX());
        ctx.state().putDouble("cy", caster.getY());
        ctx.state().putDouble("cz", caster.getZ());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        double cx = ctx.state().getDoubleOr("cx", ctx.mob().getX());
        double cy = ctx.state().getDoubleOr("cy", ctx.mob().getY());
        double cz = ctx.state().getDoubleOr("cz", ctx.mob().getZ());

        if (ticks % VISUAL_INTERVAL == 0) {
            // The visible body of the cloud: nectar falling,
            // happy sparkles drifting.
            level.sendParticles(ParticleTypes.FALLING_NECTAR, cx, cy + 0.5, cz,
                3, 3.5, 1.5, 3.5, 0.02);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, cx, cy, cz,
                2, 3.5, 1.5, 3.5, 0.02);
        }
        if (ticks % PULSE_INTERVAL != 0) {
            return;
        }
        // The pulse: friends mend, enemies drag.
        Mob caster = ctx.mob();
        if (caster.getX() >= cx - RADIUS && caster.getX() <= cx + RADIUS
            && caster.getZ() >= cz - RADIUS && caster.getZ() <= cz + RADIUS
            && caster.getY() >= cy - Y_REACH && caster.getY() <= cy + Y_REACH) {
            caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        }
        List<LivingEntity> allies = BeeAbilitySupport.alliesInBox(ctx, cx, cy, cz, RADIUS, Y_REACH, Y_REACH);
        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        }
        List<LivingEntity> enemies = BeeAbilitySupport.enemiesInBox(ctx, cx, cy, cz, RADIUS, Y_REACH, Y_REACH);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: every effect is a short refresh
        // that expires on its own clock (see the class comment).
    }
}
