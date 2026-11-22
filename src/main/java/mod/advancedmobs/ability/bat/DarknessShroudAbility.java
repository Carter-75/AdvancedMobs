package mod.advancedmobs.ability.bat;

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
import net.minecraft.world.entity.ambient.Bat;

import java.util.List;

/**
 * Darkness Shroud (Bat ability 3 of 5, defense aura).
 *
 * WHAT IT DOES: the bat beats its wings and the cave goes
 * darker around it, the darkness effect pulsing over nearby
 * enemies while ash and smoke thicken the air. Inside its
 * shroud the bat is nearly impossible to track: it flits
 * around its victim in the black, and enemies keep losing
 * sight of it entirely, wasting their attention on empty air.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bat's current target within 12 blocks (distance
 *   squared 144).
 * - The shroud (start()): a resting bat takes off
 *   (setResting(false), BAT_TAKEOFF, volume 1.0, pitch 1.0)
 *   and a low BAT_AMBIENT flutter (volume 1.0, pitch 0.7)
 *   opens the shroud.
 * - The flit (every tick): the bat steers at a point 1.5
 *   blocks above its target's live position at 0.8 blocks
 *   per tick, circling close in the dark. With no live
 *   target it damps its velocity by 0.6 per tick and hangs.
 * - The murk (every 2 ticks): 1 ASH and 1 SMOKE particle
 *   drift around the bat's body, the thickened air of the
 *   design.
 * - The pulse (every 20 ticks): one bounded query (radius
 *   8.0 around the bat, 3.0 below to 3.0 above) finds the
 *   enemies inside the shroud. Each gains DARKNESS for 70
 *   ticks, refreshed every pulse, so inside the shroud an
 *   enemy can barely see a block ahead of it. On top of
 *   that, each enemy Mob whose current target is the bat
 *   rolls the bat's random source: on a roll under 0.5 it
 *   loses the bat as its target (setTarget(null)). That
 *   target loss is the design's invisibility detection
 *   range reduction and follow_range interaction carried in
 *   code: shrinking each victim's FOLLOW_RANGE attribute
 *   instead would fight the victim's own upgrade sheet and
 *   would need restoring on entities that may unload
 *   mid-activation, so the shroud breaks tracking directly,
 *   which is the effect the design describes. Players are
 *   never target-cleared (the framework's target is theirs
 *   to keep); they still eat the DARKNESS.
 * - Duration: 240 ticks (12 seconds), hard finish.
 * - end(): nothing to reverse. The bat applied no attribute
 *   modifiers and no effects to itself; the enemies'
 *   DARKNESS is a fixed-duration effect that expires on its
 *   own clock once the pulses stop.
 *
 * WHAT IT LOOKS LIKE: the air around the bat fills with
 * drifting ash and smoke, the light seems to die in pulses
 * as darkness washes over everything nearby, and the small
 * shape inside the murk keeps flickering from spot to spot
 * while its enemies swing at where it was.
 *
 * PERFORMANCE: per tick one steering write; every 2 ticks
 * 2 particle sends; every 20 ticks one bounded entity query
 * plus one effect application and at most one target clear
 * per enemy found. Particle budget per activation: 120
 * murk ticks times 2 = 240 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none beyond the framework's own; the
 * ability is fully driven by ctx.ticksActive().
 */
public final class DarknessShroudAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 20;
    private static final double PULSE_RADIUS = 8.0;
    private static final int DARKNESS_TICKS = 70;
    private static final double FLIT_SPEED = 0.8;
    private static final double TRACK_LOSS_CHANCE = 0.5;

    @Override
    public String id() {
        return "darkness_shroud";
    }

    @Override
    public String displayName() {
        return "Darkness Shroud";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (caster instanceof Bat bat && bat.isResting()) {
            bat.setResting(false);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_TAKEOFF,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_AMBIENT,
            SoundSource.HOSTILE, 1.0f, 0.7f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        if (target != null) {
            BatAbilitySupport.steerToward(ctx,
                target.getX(), target.getY() + 1.5, target.getZ(), FLIT_SPEED);
        } else {
            caster.setDeltaMovement(caster.getDeltaMovement().scale(0.6));
        }
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.ASH, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                1, 0.5, 0.3, 0.5, 0.02);
            level.sendParticles(ParticleTypes.SMOKE, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                1, 0.5, 0.3, 0.5, 0.02);
        }
        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx, level);
        }
    }

    /** One darkness pulse: blind the shroud's enemies and shake half their tracking. */
    private static void pulse(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<LivingEntity> enemies = BatAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), PULSE_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_TICKS, 0));
            if (enemy instanceof Mob mob && mob.getTarget() == caster
                && ctx.random().nextDouble() < TRACK_LOSS_CHANCE) {
                mob.setTarget(null);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to reverse: no caster modifiers or effects
        // were applied (see the class comment).
    }
}
