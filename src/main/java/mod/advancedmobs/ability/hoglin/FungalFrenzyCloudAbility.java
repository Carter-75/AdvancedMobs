package mod.advancedmobs.ability.hoglin;

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
 * Fungal Frenzy Cloud (Hoglin ability 3 of 5, aura).
 *
 * WHAT IT DOES: the hoglin shakes its mane violently and sheds
 * a rolling cloud of crimson spores and mycelium that hangs
 * over the battlefield and drifts with it. Enemies breathing
 * the cloud are hit with NAUSEA as the fungus gets into them,
 * while the hoglin and its hoglin and zoglin allies inside the
 * cloud gain SPEED and fight in a frenzy: a moving zone of
 * sickness for the enemy and strength for the herd.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   hoglin's current target within 10 blocks (distance squared
 *   100). The cloud is a brawling zone around the hoglin, not a
 *   ranged tool.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - Pulse every 20 ticks (15 pulses per activation), radius
 *   5.0 blocks around the hoglin (2.0 below to 3.0 above its
 *   feet), centered on the hoglin's live position, which is
 *   what makes the cloud drift with it:
 *     Enemies (HoglinAbilitySupport rules) gain NAUSEA
 *       amplifier 0 for 60 ticks. The 60 against the 20 tick
 *       pulse means a victim who escapes the cloud stays sick
 *       for about 2 seconds after the last breath, and a victim
 *       who stays in is refreshed every pulse.
 *     The caster and its frenzy allies (HoglinAbilitySupport's
 *       swarm ally rule: hoglins and zoglins with an alliance
 *       record to the caster in either direction) gain SPEED
 *       amplifier 0 for 30 ticks, refreshed every pulse and
 *       lapsing 10 ticks after they leave the cloud or it
 *       ends. The caster is buffed too: it is a hoglin standing
 *       in its own cloud. Wild hoglins and zoglins with no
 *       alliance record are not buffed (see the support class
 *       comment for the rule).
 * - The two pulses are independent queries over the same box:
 *   an entity is never both (allies are excluded from the
 *   enemy rule by the alliance record).
 *
 * WHAT IT LOOKS LIKE: the shake at start() throws 16
 * CRIMSON_SPORE and 8 MYCELIUM particles off the hoglin's body
 * with a HOGLIN_AMBIENT snort. During the cloud, every 4 ticks
 * 2 CRIMSON_SPORE and 1 MYCELIUM drift at points rotating
 * around the hoglin at radius 1.8 (angle advances 0.30 radians
 * per emission, so the cloud visibly rolls), at chest height.
 * Victims show the vanilla NAUSEA sparkles while sickened and
 * allies show SPEED sparkles while frenzied.
 *
 * PERFORMANCE: tick() returns immediately except every 4th
 * tick (3 particle sends computed from ticksActive() alone).
 * The pulse runs every 20th tick: two bounded entity queries
 * (10 by 5 by 10 box) plus effect applications to the handful
 * of entities inside. No state is read or written at all.
 * Particle budget per activation: 24 at start plus 75
 * emissions times 3 = 225, total at most 249 particles.
 *
 * CLEANUP: end() has nothing to undo. Every effect this cloud
 * applies lasts at most 60 ticks and expires on its own; no
 * attribute modifiers, no spawned entities, no block changes
 * (the cloud is a zone effect around the hoglin, not terrain,
 * so the mobGriefing gate does not apply anywhere in it).
 *
 * State tag keys: none.
 */
public final class FungalFrenzyCloudAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 20;
    private static final int DRIFT_INTERVAL = 4;
    private static final double CLOUD_RADIUS = 5.0;

    @Override
    public String id() {
        return "fungal_frenzy_cloud";
    }

    @Override
    public String displayName() {
        return "Fungal Frenzy Cloud";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // The mane shake: the cloud is shed in one violent burst.
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            16, 1.0, 0.7, 1.0, 0.08);
        level.sendParticles(ParticleTypes.MYCELIUM, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            8, 1.0, 0.7, 1.0, 0.08);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_AMBIENT,
            SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        if (ticks % DRIFT_INTERVAL == 0) {
            // The rolling cloud: spores drifting around the hoglin.
            double angle = ticks * 0.30;
            double px = mob.getX() + Math.cos(angle) * 1.8;
            double pz = mob.getZ() + Math.sin(angle) * 1.8;
            double py = mob.getY() + 1.0;
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, px, py, pz, 2, 0.5, 0.5, 0.5, 0.03);
            level.sendParticles(ParticleTypes.MYCELIUM, px, py, pz, 1, 0.5, 0.5, 0.5, 0.03);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx);
        }
    }

    /** One cloud pulse: sickness for enemies, frenzy for the herd. */
    private void pulse(AbilityContext ctx) {
        List<LivingEntity> victims = HoglinAbilitySupport.enemiesAroundSelf(ctx, CLOUD_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 60, 0));
        }
        List<LivingEntity> allies = HoglinAbilitySupport.frenzyAlliesAroundSelf(ctx, CLOUD_RADIUS, 2.0, 3.0);
        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: all applied effects expire within 60 ticks.
    }
}
