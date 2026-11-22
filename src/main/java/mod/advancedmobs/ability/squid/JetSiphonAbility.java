package mod.advancedmobs.ability.squid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Jet Siphon (Squid ability 3 of 5, mobility).
 *
 * WHAT IT DOES: the squid fires its siphon and
 * torpedoes backward in a burst of bubbles, faster
 * than most fish swim forward, weaving side to side
 * as it goes. Riding its own pressure wave it takes
 * reduced damage for the whole maneuver, and before
 * it has to rest it fires the jet twice more: three
 * chained bursts, each one re-aimed away from
 * whatever is chasing it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 16
 *   blocks (distance squared 256), plus the squid in
 *   water.
 * - The ride (start()): RESISTANCE I for 80 ticks
 *   (the design's reduced damage while jetting;
 *   removed in end()) and a transient
 *   AIR_DRAG_MODIFIER -0.9 add_value modifier (id
 *   advancedmobs:jet_siphon_drag; the design's
 *   lowered drag line, leaving 0.1 of the default
 *   1.0, so the jet's velocity writes bleed off as
 *   little as the attribute allows), removed in
 *   end(). The first jet begins immediately.
 * - A jet (10 ticks): the direction is locked at the
 *   jet's start as the unit vector pointing directly
 *   away from the target's live position, vertical
 *   component included and the whole vector clamped
 *   to at most 0.4 vertical so the squid stays in its
 *   water (the caster's own look direction when
 *   there is somehow no target). At the lock:
 *   SQUID_SQUIRT at volume 1.2, pitch 1.1, and the
 *   siphon burst of 8 BUBBLE_POP plus 8 BUBBLE at the
 *   caster. Each jet tick the velocity is written
 *   along the direction at 1.25 blocks per tick,
 *   plus the weave: a perpendicular horizontal
 *   offset of sin(jet tick times 0.6) times 0.25
 *   added to the velocity, so the path snakes
 *   instead of running a straight line (the design's
 *   weaving between obstacles). 2 BUBBLE trail per
 *   tick.
 * - The drift (15 ticks between jets): the caster
 *   steers toward its target at 0.3 blocks per tick,
 *   repositioning and catching its breath while the
 *   next jet aims. No drift follows the third jet.
 * - Duration: 3 jets of 10 ticks plus 2 drifts of 15
 *   ticks = 60 ticks, tracked in the state tag by
 *   phase; isFinished reads the done flag the third
 *   jet's end sets, with a 70-tick hard cap as a
 *   safety net.
 *
 * WHAT IT LOOKS LIKE: three sharp bubble bursts,
 * each one a squid streaking backward on a snaking
 * line of bubbles, pausing just long enough to aim
 * the next.
 *
 * PERFORMANCE: one velocity write and 2 particle
 * sends per jet tick, one steering write per drift
 * tick, the burst only at a jet lock (3 times). No
 * entity queries anywhere. Particle budget per
 * activation: 3 jets times (16 burst plus 20 trail)
 * = 108, total at most 108.
 *
 * TERRAIN: this ability changes no blocks; the jets
 * are velocity and particles, so the mobGriefing
 * gate does not apply.
 *
 * CLEANUP: end() removes the RESISTANCE effect and
 * the drag modifier by id.
 *
 * State tag keys: "phase" (0 jetting, 1 drifting),
 * "phase_tick" (ticks inside the current phase),
 * "jets_left" (jets still to fire after the current
 * one), "dx", "dy", "dz" (the locked jet direction),
 * "done" (the third jet has finished).
 */
public final class JetSiphonAbility implements MobAbility {

    private static final int JET_TICKS = 10;
    private static final int DRIFT_TICKS = 15;
    private static final int TOTAL_JETS = 3;
    private static final double JET_SPEED = 1.25;
    private static final int HARD_CAP_TICKS = 70;
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "jet_siphon_drag");

    @Override
    public String id() {
        return "jet_siphon";
    }

    @Override
    public String displayName() {
        return "Jet Siphon";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 80, 0));
        SquidAbilitySupport.addModifier(caster, Attributes.AIR_DRAG_MODIFIER,
            new AttributeModifier(DRAG_ID, -0.9, AttributeModifier.Operation.ADD_VALUE));

        CompoundTag state = ctx.state();
        state.putInt("jets_left", TOTAL_JETS - 1);
        state.putBoolean("done", false);
        lockJet(ctx);
    }

    /** Locks a fresh jet direction away from the target and fires the siphon burst. */
    private void lockJet(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();

        double dx;
        double dy;
        double dz;
        if (target != null) {
            dx = caster.getX() - target.getX();
            dy = caster.getY() - target.getY();
            dz = caster.getZ() - target.getZ();
        } else {
            Vec3 look = caster.getLookAngle();
            dx = -look.x;
            dy = 0.0;
            dz = -look.z;
        }
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.001) {
            dx = 1.0;
            dy = 0.0;
            dz = 0.0;
            length = 1.0;
        }
        dx /= length;
        dz /= length;
        dy = Math.max(-0.4, Math.min(0.4, dy / length));
        state.putDouble("dx", dx);
        state.putDouble("dy", dy);
        state.putDouble("dz", dz);
        state.putInt("phase", 0);
        state.putInt("phase_tick", 0);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 1.2f, 1.1f);
            level.sendParticles(ParticleTypes.BUBBLE_POP, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                8, 0.4, 0.3, 0.4, 0.05);
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                8, 0.4, 0.3, 0.4, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int phaseTick = state.getIntOr("phase_tick", 0) + 1;
        state.putInt("phase_tick", phaseTick);

        if (state.getIntOr("phase", 0) == 0) {
            // Jetting: velocity along the locked line, with the weave.
            double dx = state.getDoubleOr("dx", 1.0);
            double dy = state.getDoubleOr("dy", 0.0);
            double dz = state.getDoubleOr("dz", 0.0);
            double weave = Math.sin(phaseTick * 0.6) * 0.25;
            // The horizontal perpendicular of the jet direction.
            double perpX = -dz;
            double perpZ = dx;
            caster.setDeltaMovement(
                dx * JET_SPEED + perpX * weave,
                dy * JET_SPEED,
                dz * JET_SPEED + perpZ * weave);
            caster.hurtMarked = true;
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.25, 0.25, 0.25, 0.03);

            if (phaseTick >= JET_TICKS) {
                int jetsLeft = state.getIntOr("jets_left", 0);
                if (jetsLeft <= 0) {
                    state.putBoolean("done", true);
                } else {
                    state.putInt("jets_left", jetsLeft - 1);
                    state.putInt("phase", 1);
                    state.putInt("phase_tick", 0);
                }
            }
            return;
        }

        // Drifting: reposition toward the target while the next jet aims.
        LivingEntity target = ctx.target();
        if (target != null) {
            SquidAbilitySupport.steerToward(caster, target.getX(), target.getY(), target.getZ(), 0.3);
        }
        if (phaseTick >= DRIFT_TICKS) {
            lockJet(ctx);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getBooleanOr("done", false) || ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.RESISTANCE);
        SquidAbilitySupport.removeModifier(caster, Attributes.AIR_DRAG_MODIFIER, DRAG_ID);
    }
}
