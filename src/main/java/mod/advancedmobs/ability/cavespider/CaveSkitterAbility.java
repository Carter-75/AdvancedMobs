package mod.advancedmobs.ability.cavespider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Cave Skitter (Cave Spider ability 4 of 5, mobility).
 *
 * WHAT IT DOES: in short terrifying bursts the cave spider
 * becomes a blur. SPEED and JUMP_BOOST fire together, its
 * scale drops, and it pours through one block gaps and over
 * rubble that stops bigger mobs, each burst kicking up a
 * small DUST_PILLAR and ending with the spider already on
 * the far side of the obstacle, legs still moving.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 3 and 16
 *   blocks away (distance squared 9 to 256). Closer than 3
 *   it is already biting; the skitter is for closing and
 *   repositioning across broken cave ground.
 * - The gear (start()): two timed effects for the whole
 *   activation, expiring on their own just past the end:
 *   SPEED II (amplifier 1) and JUMP_BOOST I (amplifier 0),
 *   both 165 ticks. Two transient attribute modifiers, both
 *   removed in end(): SCALE -0.30 add_value (Identifier
 *   advancedmobs:skitter_scale; the spider renders and
 *   collides at 0.70 of its normal size, small enough to
 *   pour through gaps that stop a full sized spider) and
 *   STEP_HEIGHT +1.0 add_value (Identifier
 *   advancedmobs:skitter_step; it steps clean over one
 *   block rubble mid-burst instead of pathing around it).
 * - The bursts: the 160 tick activation runs in a 40 tick
 *   cycle, burst for the first 20 ticks of each cycle, a 20
 *   tick scuttle pause between bursts, 4 bursts in all. On
 *   the first tick of a burst the navigation is stopped (so
 *   the pathfinder does not argue with the dash; navigation
 *   stops are the standing way to hold a mob's course, NoAi
 *   is never used, it would freeze the ability itself) and 4
 *   DUST_PILLAR motes kick up at the spider's feet. On every
 *   burst tick the spider's horizontal velocity is set
 *   toward its live target at 0.85 blocks per tick; when the
 *   spider is on the ground and the target is more than 0.5
 *   blocks above it, a 0.45 upward hop is added so the burst
 *   carries it up ledges the step height cannot reach. If
 *   the target is lost mid-activation the dash simply stops
 *   and the remaining bursts idle: the effects and modifiers
 *   still run out their clock.
 * - The dust (every 4 ticks during a burst): 2 DUST_PILLAR
 *   motes at the spider's feet, the burst visibly churning
 *   the cave floor.
 * - Duration: 160 ticks (8 seconds). Nothing is spawned and
 *   no block is touched, so the terrain rule does not apply.
 *
 * WHAT IT LOOKS LIKE: the spider drops smaller, blurs, and
 * detonates forward in a spray of dust, pouring over and
 * through the rubble in a straight terrifying line, pauses
 * with legs ticking, then detonates again, four times,
 * arriving on the far side of whatever was in the way.
 *
 * PERFORMANCE: burst ticks write one velocity vector (a
 * single allocation, the direction math) and nothing else;
 * dust runs every 4 ticks during bursts only. No entity
 * scans, no block scans, no registry or config lookups: the
 * target is the context's live target and the modifiers are
 * static finals resolved at class load. Particle budget per
 * activation: 4 bursts times (4 at the burst start plus 5
 * dust pulses times 2) = 56 DUST_PILLAR, total at most 56
 * particles.
 *
 * CLEANUP: end() removes the SCALE and STEP_HEIGHT
 * modifiers by id. The SPEED and JUMP_BOOST effects are
 * timed and expire on their own. Runs identically on a
 * natural finish and on framework termination.
 *
 * State tag keys: none. Bursts are a pure function of the
 * tick counter and the live target.
 */
public final class CaveSkitterAbility implements MobAbility {

    static final int DURATION_TICKS = 160;
    private static final int CYCLE_TICKS = 40;
    private static final int BURST_TICKS = 20;
    private static final int DUST_INTERVAL = 4;
    private static final double DASH_SPEED = 0.85;
    private static final BlockParticleOption DIRT_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.DIRT.defaultBlockState());
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "skitter_scale");
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "skitter_step");

    @Override
    public String id() {
        return "cave_skitter";
    }

    @Override
    public String displayName() {
        return "Cave Skitter";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 9.0 && distanceSqr <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();

        // The gear: timed effects for the whole activation.
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS + 5, 1));
        mob.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, DURATION_TICKS + 5, 0));

        // The shrink and the step: transient, removed in end().
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(
                new AttributeModifier(SCALE_ID, -0.30, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance stepHeight = mob.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.addOrUpdateTransientModifier(
                new AttributeModifier(STEP_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        int phase = (ticks - 1) % CYCLE_TICKS;
        if (phase >= BURST_TICKS) {
            return; // Cheap early-out: the pause between bursts does nothing.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        // Burst start: the pathfinder does not argue with the
        // dash, and the first dust kicks up.
        if (phase == 0) {
            mob.getNavigation().stop();
            level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                4, 0.3, 0.1, 0.3, 0.05);
        }

        // The dash: horizontal velocity straight at the live
        // target, with a hop when the target is above.
        LivingEntity target = ctx.target();
        if (target != null) {
            Vec3 toTarget = new Vec3(target.getX() - mob.getX(), 0.0, target.getZ() - mob.getZ());
            if (toTarget.lengthSqr() > 0.0001) {
                Vec3 dash = toTarget.normalize().scale(DASH_SPEED);
                double vy = mob.getDeltaMovement().y;
                if (mob.onGround() && target.getY() > mob.getY() + 0.5) {
                    vy = 0.45;
                }
                mob.setDeltaMovement(dash.x, vy, dash.z);
                mob.hurtMarked = true;
            }
        }

        // The churn: dust at the feet through the burst.
        if (ticks % DUST_INTERVAL == 0) {
            level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.15, mob.getZ(),
                2, 0.3, 0.1, 0.3, 0.04);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        AttributeInstance stepHeight = mob.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.removeModifier(STEP_ID);
        }
        // The SPEED and JUMP_BOOST effects are timed and expire
        // on their own.
    }
}
