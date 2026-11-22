package mod.advancedmobs.ability.axolotl;

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
import net.minecraft.world.entity.animal.axolotl.Axolotl;

/**
 * Playing Dead (Axolotl ability 1 of 5, defense).
 *
 * WHAT IT DOES: shot or cornered, the axolotl flops over and
 * plays dead exactly like its wild instincts demand: belly up
 * and still, a few heart particles drifting up. Enemies drop
 * it as a target and turn away, while underneath the act it
 * regenerates fast and stacks resistance, and it springs back
 * to life once their backs are turned.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the axolotl's current target within 14 blocks (distance
 *   squared 196). Works in water and on land, the way the
 *   vanilla feint does.
 * - The flop (start()): the caster is put into the REAL
 *   vanilla play-dead pose through Axolotl.setPlayingDead(true)
 *   (a public setter in 26.2, verified against the jar; the
 *   pose is the design's custom render state, carried by
 *   vanilla's own play-dead animation state). While the flag
 *   is set, Axolotl.canBeSeenAsEnemy() returns false (jar
 *   verified), so vanilla target selection cannot pick the
 *   axolotl up again on its own. The caster gains REGENERATION
 *   II and RESISTANCE I, both for 200 ticks (removed in
 *   end()). One bounded query at radius 16 clears the caster
 *   as a target from every enemy Mob hunting it (the support
 *   class's Ashen Veil pattern: vanilla target plus war data
 *   tracked target), and the caster's navigation is stopped.
 *   AXOLOTL_SPLASH plays at volume 0.8 when the flop happens
 *   in water.
 * - Holding the act (tick()): the pose flag is re-asserted
 *   every tick, because the vanilla brain runs its own
 *   play-dead timer (TOTAL_PLAYDEAD_TIME) and would end the
 *   pose early on its own schedule; re-asserting one boolean
 *   per tick is the cheapest possible hold. Navigation is
 *   stopped and horizontal velocity is zeroed every tick so
 *   the body stays where it flopped (vertical velocity is
 *   left alone so it settles onto the bottom or the ground
 *   naturally). Every 10 ticks the target clearing repeats
 *   (one bounded query, radius 16) and the pulse records in
 *   the state tag whether anyone was still hunting the
 *   caster; 1 HEART particle drifts up from the body on the
 *   same pulse.
 * - Springing back (isFinished): the act runs at most 160
 *   ticks (8 seconds). It ends early once the area has been
 *   calm for a while: a clearing pulse that found nobody
 *   targeting the caster starts the calm clock, and 30
 *   further ticks with no pulse finding a hunter ends the
 *   act, but never before tick 40, so the feint always buys
 *   at least 2 seconds. A pulse that finds a hunter resets
 *   the calm clock.
 *
 * WHAT IT LOOKS LIKE: the axolotl rolls belly up in the real
 * vanilla play-dead animation and lies still, one faint heart
 * drifting up every half second, while its hunters visibly
 * lose interest and wander off; then it flips back over and
 * swims away at full health regeneration speed.
 *
 * PERFORMANCE: per tick the work is one pose re-assert, one
 * navigation stop, and one velocity write. The bounded query
 * runs once in start() and once per 10 ticks. Particle budget
 * per activation: 1 HEART per 10 ticks over at most 160
 * ticks, at most 16 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() clears the play-dead pose flag and removes
 * the REGENERATION and RESISTANCE effects (both are this
 * ability's own; a regeneration the vanilla feint granted on
 * its own would be removed too, the accepted edge case shared
 * with every fixed-duration self effect in this system). The
 * pose clear is a plain flag write on the caster, safe even
 * when end() runs through the termination path on a dead or
 * removed axolotl.
 *
 * State tag keys: "calm_from" (the tick the current calm
 * streak began, or -1 while someone is still hunting).
 */
public final class PlayingDeadAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 160;
    private static final int MIN_TICKS = 40;
    private static final int CALM_TICKS_NEEDED = 30;
    private static final int PULSE_INTERVAL = 10;
    private static final double CLEAR_RADIUS = 16.0;

    @Override
    public String id() {
        return "playing_dead";
    }

    @Override
    public String displayName() {
        return "Playing Dead";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("calm_from", -1);

        setPose(ctx, true);
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 0));
        caster.getNavigation().stop();
        AxolotlAbilitySupport.clearEnemyTargetsOnCaster(ctx, CLEAR_RADIUS);

        if (caster.isInWater() && ctx.level() instanceof ServerLevel level) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.AXOLOTL_SPLASH, SoundSource.HOSTILE, 0.8f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // Hold the act: re-assert the pose against the vanilla
        // brain's own play-dead timer, and keep the body still.
        setPose(ctx, true);
        caster.getNavigation().stop();
        caster.setDeltaMovement(0.0, caster.getDeltaMovement().y, 0.0);

        if (ctx.ticksActive() % PULSE_INTERVAL == 0) {
            int hunters = AxolotlAbilitySupport.clearEnemyTargetsOnCaster(ctx, CLEAR_RADIUS);
            if (hunters == 0) {
                if (ctx.state().getIntOr("calm_from", -1) < 0) {
                    ctx.state().putInt("calm_from", ctx.ticksActive());
                }
            } else {
                ctx.state().putInt("calm_from", -1);
            }
            level.sendParticles(ParticleTypes.HEART,
                caster.getX(), caster.getY() + caster.getBbHeight() + 0.2, caster.getZ(),
                1, 0.2, 0.1, 0.2, 0.02);
        }
    }

    /** Sets or clears the vanilla play-dead pose flag on the caster. */
    private void setPose(AbilityContext ctx, boolean playingDead) {
        if (ctx.mob() instanceof Axolotl axolotl) {
            axolotl.setPlayingDead(playingDead);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        int calmFrom = ctx.state().getIntOr("calm_from", -1);
        return ticks >= MIN_TICKS && calmFrom >= 0 && ticks - calmFrom >= CALM_TICKS_NEEDED;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        setPose(ctx, false);
        caster.removeEffect(MobEffects.REGENERATION);
        caster.removeEffect(MobEffects.RESISTANCE);
    }
}
