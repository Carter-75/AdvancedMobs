package mod.advancedmobs.ability.camel;

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
import net.minecraft.world.entity.animal.camel.Camel;

import java.util.List;

/**
 * Oasis Blessing (Camel ability 4 of 5, support).
 *
 * WHAT IT DOES: the camel kneels in the middle of the fight
 * and a spring seems to bubble up around it, water raining
 * gently in a ring on the sand. Every ally standing in the
 * ring, and the camel itself, mends and is fed by the
 * blessing, and any ally who is burning is doused clean.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the camel's current target within 16 blocks (distance
 *   squared 256), and at least one ally of the camel within
 *   9 blocks (one bounded query; the blessing is a support
 *   ability and never fires for a camel fighting alone, the
 *   Rescue Current / Pasture Sanctuary precedent).
 * - The kneel (start()): the ring center is the camel's
 *   position at start, recorded in the state tag; the ring
 *   does not follow the camel afterwards. If the caster is
 *   a Camel that is not already sitting, it calls the
 *   vanilla sitDown() and records that, so end() only stands
 *   up a camel this ability actually sat down. CAMEL_SIT
 *   plays at volume 1.0.
 * - The spring (tick(), every tick): the camel's navigation
 *   is stopped (it stays kneeling at the spring, never
 *   NoAi).
 * - The blessing pulse (tick(), every 20 ticks): one
 *   bounded query around the ring center, radius 5.0
 *   (vertical reach 2 below, 3 above). Every ally in the
 *   ring, plus the camel itself, gains REGENERATION I and
 *   SATURATION I, each refreshed to 40 ticks, and any of
 *   them with fire ticks remaining is doused with
 *   clearFire(). The effects are deliberately short and
 *   refreshed by the pulse, so they fade within 2 seconds
 *   of the ability ending or of stepping out of the ring;
 *   end() strips nothing.
 * - The rain (tick(), every 5 ticks): a point on the ring
 *   at radius 4.2, its angle advancing 0.35 radians per
 *   emission so the rain walks around the circle, gets 2
 *   SPLASH and 2 FALLING_WATER particles falling gently;
 *   the center gets 2 SPLASH particles as the spring
 *   bubbles up.
 * - Duration: exactly 240 ticks (12 seconds), hard finish.
 * - end(): the camel stands back up (standUp(), with
 *   CAMEL_STAND at volume 1.0) if this ability sat it down.
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: the camel folds down onto the sand
 * mid-fight, and water starts pattering in a slow circle
 * around it, splashing up from a bubbling center: allies
 * standing in the ring drip, steam off their burns, and
 * visibly mend.
 *
 * PERFORMANCE: per tick the ability is one navigation stop;
 * the blessing pulse is one bounded radius-5.0 query every
 * 20 ticks over a small ally list; the rain is 6 particles
 * every 5 ticks. Particle budget per activation: 48
 * emissions times 6 = 288 particles.
 *
 * TERRAIN: this ability changes no blocks; the spring is
 * particles and effects only, no water is ever placed, so
 * the mobGriefing gate does not apply.
 *
 * State tag keys: "cx"/"cy"/"cz" (the ring center, the
 * camel's position at start), "sat" (whether this
 * activation sat the camel down).
 */
public final class OasisBlessingAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final double RING_RADIUS = 5.0;
    private static final double RAIN_RADIUS = 4.2;
    private static final int PULSE_INTERVAL = 20;
    private static final int EFFECT_TICKS = 40;

    @Override
    public String id() {
        return "oasis_blessing";
    }

    @Override
    public String displayName() {
        return "Oasis Blessing";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        return !CamelAbilitySupport.alliesAroundSelf(ctx, 9.0, 2.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putDouble("cx", caster.getX());
        ctx.state().putDouble("cy", caster.getY());
        ctx.state().putDouble("cz", caster.getZ());

        // The kneel: the vanilla sitting pose, recorded so
        // end() only stands up a camel this ability sat down.
        boolean sat = false;
        if (caster instanceof Camel camel && !camel.isCamelSitting()) {
            camel.sitDown();
            sat = true;
        }
        ctx.state().putBoolean("sat", sat);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CAMEL_SIT,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Kneeling at the spring: hold position through
        // navigation stops, never NoAi.
        caster.getNavigation().stop();

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        double cx = ctx.state().getDoubleOr("cx", caster.getX());
        double cy = ctx.state().getDoubleOr("cy", caster.getY());
        double cz = ctx.state().getDoubleOr("cz", caster.getZ());

        if (ticks % PULSE_INTERVAL == 0) {
            bless(ctx, level, cx, cy, cz);
        }
        if (ticks % 5 == 0) {
            // The rain walks around the ring; the spring bubbles at the center.
            double angle = ticks * 0.35;
            double rx = cx + Math.cos(angle) * RAIN_RADIUS;
            double rz = cz + Math.sin(angle) * RAIN_RADIUS;
            level.sendParticles(ParticleTypes.SPLASH, rx, cy + 1.2, rz, 2, 0.2, 0.1, 0.2, 0.03);
            level.sendParticles(ParticleTypes.FALLING_WATER, rx, cy + 1.6, rz, 2, 0.2, 0.1, 0.2, 0.02);
            level.sendParticles(ParticleTypes.SPLASH, cx, cy + 0.4, cz, 2, 0.4, 0.2, 0.4, 0.03);
        }
    }

    /** One blessing pulse: mend, feed, and douse the caster and every ally in the ring. */
    private void bless(AbilityContext ctx, ServerLevel level, double cx, double cy, double cz) {
        Mob caster = ctx.mob();
        blessOne(caster);
        List<LivingEntity> allies = CamelAbilitySupport.alliesInBox(ctx, cx, cy, cz, RING_RADIUS, 2.0, 3.0);
        for (LivingEntity ally : allies) {
            blessOne(ally);
        }
    }

    /** Applies the blessing to one beneficiary: regeneration, saturation, and a dousing. */
    private void blessOne(LivingEntity beneficiary) {
        beneficiary.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
        beneficiary.addEffect(new MobEffectInstance(MobEffects.SATURATION, EFFECT_TICKS, 0));
        if (beneficiary.getRemainingFireTicks() > 0) {
            beneficiary.clearFire();
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        if (ctx.state().getBooleanOr("sat", false) && caster instanceof Camel camel) {
            camel.standUp();
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CAMEL_STAND,
                    SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        }
    }
}
