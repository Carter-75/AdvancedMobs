package mod.advancedmobs.ability.stray;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Wind Rider (Stray ability 5 of 5, mobility).
 *
 * WHAT IT DOES: the stray catches a rising gust, SMALL_GUST and
 * GUST spirals coiling under it as it launches into the air and
 * rides the wind across the field toward its target. It lands
 * feather soft under SLOW_FALLING, having crossed ground, gaps,
 * and enemy lines that no walker could pass.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 6 and 30 blocks
 *   away (distance squared 36 to 900), and the stray is on the
 *   ground. Short hops are for walking; this is the crossing
 *   tool.
 * - Launch (start): navigation is stopped (never NoAi: abilities
 *   tick from the AI step, so NoAi would freeze the flight; see
 *   the MobAbility contract). The stray's velocity is set toward
 *   the target's position at launch: horizontal direction to the
 *   target times 1.15, vertical 0.55. Two attribute modifiers go
 *   on for the whole flight: AIR_DRAG_MODIFIER add_value -0.75
 *   (modifier id advancedmobs:wind_rider_drag, taking the
 *   attribute from 1 to 0.25, so the air barely bleeds the
 *   launch speed: the long glide) and SAFE_FALL_DISTANCE
 *   add_value +30 (modifier id advancedmobs:wind_rider_soft_landing,
 *   so even a landing after the slow falling has been stripped
 *   stays harmless). Both are removed in end().
 * - Rise phase (ticks 1 to 12): LEVITATION I (amplifier 0,
 *   applied at start for 14 ticks) carries the stray up while
 *   the launch velocity carries it forward. The stray counts as
 *   airborne once it leaves the ground in this phase.
 * - Glide phase (from tick 13): LEVITATION is removed and
 *   SLOW_FALLING (amplifier 0) is applied for 140 ticks, so the
 *   descent is a feather fall. Every 5 ticks of the glide, if the
 *   target is still alive, the stray's horizontal velocity is
 *   nudged 0.10 toward the target's current position, capped at
 *   1.3 horizontal speed: the wind corrects its course onto the
 *   enemy line instead of drifting past it.
 * - Landing: the first glide tick that finds the stray on the
 *   ground again ends the ability (isFinished reads the landed
 *   flag tick() sets). A hard finish at tick 140 covers a stray
 *   that lands on a roof edge and never settles, or one still
 *   drifting at the cap.
 * - Fall damage: none by design. SLOW_FALLING negates it during
 *   the glide, and the SAFE_FALL_DISTANCE modifier covers the
 *   end()-strips-the-effect edge at the hard finish.
 *
 * WHAT IT LOOKS LIKE: a burst of 3 SMALL_GUST spirals at the
 * launch, then every 3 ticks of flight 2 SMALL_GUST and 1 GUST
 * coiling under the stray as it sails, and 6 POOF motes at the
 * touchdown.
 *
 * PERFORMANCE: tick() does one phase check and, in the glide,
 * one onGround read per tick; the course nudge runs every 5
 * ticks (one target resolve and one velocity write), the gust
 * particles every 3 ticks. No entity scans, no block work, no
 * allocations beyond the velocity vector on nudge ticks.
 * Particle budget per activation: 3 at launch, at most 46 flight
 * bursts times 3 = 138, 6 at the touchdown, total at most 147
 * particles.
 *
 * CLEANUP: end() removes both attribute modifiers and removes
 * LEVITATION and SLOW_FALLING (which also clears any levitation
 * or slow falling the stray already had from another source, the
 * same accepted trade the other implemented mobs document for
 * their effect removals). If the caster dies mid-flight, the
 * framework termination path runs this same end().
 *
 * State tag keys: "phase" (0 rise, 1 glide), "airborne" (1 once
 * the stray has left the ground), "landed" (1 once it touches
 * down in the glide).
 */
public final class WindRiderAbility implements MobAbility {

    static final int HARD_FINISH_TICKS = 140;
    private static final int RISE_TICKS = 12;
    private static final double LAUNCH_HORIZONTAL = 1.15;
    private static final double LAUNCH_VERTICAL = 0.55;
    private static final double NUDGE = 0.10;
    private static final double MAX_HORIZONTAL_SPEED = 1.3;
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "wind_rider_drag");
    private static final Identifier SOFT_LANDING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "wind_rider_soft_landing");

    @Override
    public String id() {
        return "wind_rider";
    }

    @Override
    public String displayName() {
        return "Wind Rider";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 900.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        state.putInt("airborne", 0);
        state.putInt("landed", 0);

        // Hold position through navigation, never NoAi (see the
        // class comment).
        mob.getNavigation().stop();

        // The launch: velocity toward where the target stands now.
        if (target != null) {
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) {
                mob.setDeltaMovement(dx / length * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL,
                    dz / length * LAUNCH_HORIZONTAL);
            } else {
                mob.setDeltaMovement(0.0, LAUNCH_VERTICAL, 0.0);
            }
        }

        // The wind kit: quarter air drag and a soft landing,
        // both for the whole flight.
        AttributeInstance drag = mob.getAttribute(Attributes.AIR_DRAG_MODIFIER);
        if (drag != null) {
            drag.addOrUpdateTransientModifier(
                new AttributeModifier(DRAG_ID, -0.75, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance safeFall = mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFall != null) {
            safeFall.addOrUpdateTransientModifier(
                new AttributeModifier(SOFT_LANDING_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The rising gust.
        mob.addEffect(new MobEffectInstance(MobEffects.LEVITATION, RISE_TICKS + 2, 0));
        if (level != null) {
            level.sendParticles(ParticleTypes.SMALL_GUST, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                3, 0.5, 0.2, 0.5, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        if (!mob.onGround()) {
            state.putInt("airborne", 1);
        }

        if (state.getIntOr("phase", 0) == 0 && ticks > RISE_TICKS) {
            // Rise to glide: the gust lets go, the feather fall
            // takes over.
            state.putInt("phase", 1);
            mob.removeEffect(MobEffects.LEVITATION);
            mob.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, HARD_FINISH_TICKS, 0));
        }

        if (state.getIntOr("phase", 0) == 1) {
            if (state.getIntOr("airborne", 0) == 1 && mob.onGround()) {
                // Touchdown: feather soft, and the flight is done.
                if (state.getIntOr("landed", 0) == 0) {
                    state.putInt("landed", 1);
                    level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                        6, 0.5, 0.2, 0.5, 0.03);
                }
                return;
            }
            // Course correction onto the target's current line.
            if (ticks % 5 == 0) {
                LivingEntity target = ctx.target();
                if (target != null && target.isAlive()) {
                    Vec3 velocity = mob.getDeltaMovement();
                    double dx = target.getX() - mob.getX();
                    double dz = target.getZ() - mob.getZ();
                    double length = Math.sqrt(dx * dx + dz * dz);
                    if (length > 0.001) {
                        double vx = velocity.x + dx / length * NUDGE;
                        double vz = velocity.z + dz / length * NUDGE;
                        double speed = Math.sqrt(vx * vx + vz * vz);
                        if (speed > MAX_HORIZONTAL_SPEED) {
                            vx *= MAX_HORIZONTAL_SPEED / speed;
                            vz *= MAX_HORIZONTAL_SPEED / speed;
                        }
                        mob.setDeltaMovement(vx, velocity.y, vz);
                    }
                }
            }
        }

        // The wind under it: gust spirals every 3 ticks of flight.
        if (ticks % 3 == 0) {
            level.sendParticles(ParticleTypes.SMALL_GUST, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                2, 0.5, 0.2, 0.5, 0.05);
            level.sendParticles(ParticleTypes.GUST, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                1, 0.5, 0.2, 0.5, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_FINISH_TICKS
            || ctx.state().getIntOr("landed", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance drag = mob.getAttribute(Attributes.AIR_DRAG_MODIFIER);
        if (drag != null) {
            drag.removeModifier(DRAG_ID);
        }
        AttributeInstance safeFall = mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFall != null) {
            safeFall.removeModifier(SOFT_LANDING_ID);
        }
        mob.removeEffect(MobEffects.LEVITATION);
        mob.removeEffect(MobEffects.SLOW_FALLING);
    }
}
