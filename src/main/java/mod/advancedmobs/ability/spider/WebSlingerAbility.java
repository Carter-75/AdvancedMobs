package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Web Slinger (Spider ability 5 of 5, mobility).
 *
 * WHAT IT DOES: the spider fires a strand of silk at its
 * distant target, END_ROD motes tracing the line through the
 * air, and reels itself along it in a swinging arc that carries
 * it over walls, pits, and enemy heads. It arrives with
 * JUMP_BOOST legs already coiled, ready to drop straight onto
 * whatever waits at the anchor point.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 8 and 26
 *   blocks away (distance squared 64 to 676). Inside 8 blocks
 *   the spider walks; this is the gap closer for real distance.
 * - The anchor (start()): the target's position at start, 1.0
 *   block up, recorded in the state tag. The strand is fired at
 *   where the target stood; a target that relocates is not
 *   chased mid-flight (the landing spot is fixed, like every
 *   telegraphed leap in this mod).
 * - The flight gear (start()): two transient attribute
 *   modifiers for the flight only, both removed in end():
 *   STEP_HEIGHT +1.0 (Identifier advancedmobs:web_slinger_step)
 *   so a low lip at the landing does not stop the arrival, and
 *   SAFE_FALL_DISTANCE +100.0 (Identifier
 *   advancedmobs:web_slinger_fall) so the swing's drop costs no
 *   fall damage. The spider's fallDistance is also zeroed every
 *   flight tick as a belt to that attribute's braces.
 * - The reel (every tick until arrival): the spider's velocity
 *   is set directly toward the anchor at 1.15 blocks per tick,
 *   with a +0.22 upward arc added while the spider is still
 *   below the anchor's height, so the path swings up and over
 *   instead of grinding along the ground. Vanilla gravity and
 *   drag fight the set each tick; the repeated set is what makes
 *   the reel a guided swing rather than a throw. The navigation
 *   is stopped once at start so the pathfinder does not argue
 *   with the strand (navigation stops are the standing way to
 *   hold a mob's course; NoAi is never used, it would freeze
 *   the ability itself).
 * - The strand (every 2 ticks of flight): 5 END_ROD motes are
 *   laid along the straight line from the spider's center to
 *   the anchor, evenly spaced: the visible silk line it is
 *   reeling along.
 * - Arrival: the spider is within 2.2 blocks of the anchor
 *   (distance squared 4.84), or the flight hard-caps at 45
 *   ticks. On arrival the spider gets JUMP_BOOST (amplifier 1)
 *   for 120 ticks (a timed effect, expires on its own), 6
 *   END_ROD motes burst at the landing, and a SPIDER_AMBIENT
 *   hiss plays at volume 0.7, pitch 1.2. The ability then
 *   lingers 10 ticks past arrival so the framework does not
 *   hand the spider its next move mid-landing, and finishes.
 *   Hard finish at 60 ticks whatever happens.
 *
 * WHAT IT LOOKS LIKE: a line of glowing END_ROD motes snaps
 * from the spider to a point above its prey, and the spider
 * sails along it in a rising arc, legs tucked, drops the last
 * stretch onto its target's head, and lands coiled and
 * spring-loaded.
 *
 * PERFORMANCE: the flight writes one velocity vector per tick
 * (a single allocation, the direction math) and sends 5
 * particles every 2 ticks. No entity scans, no block scans, no
 * registry or config lookups: the anchor and the modifiers are
 * resolved at start() and the modifier objects are static
 * finals. Particle budget per activation: strand at most 23
 * pulses times 5 = 115 END_ROD, arrival burst 6, total at most
 * 121 particles.
 *
 * CLEANUP: end() removes both transient attribute modifiers and
 * zeroes the fall distance one last time. The JUMP_BOOST is a
 * timed effect and expires on its own. Nothing is spawned and
 * no block is touched, so the terrain rule does not apply.
 *
 * State tag keys: "ax", "ay", "az" (the anchor, doubles),
 * "arrived" (1 once the spider reached the anchor), "arrive_tick"
 * (the activation tick of the arrival).
 */
public final class WebSlingerAbility implements MobAbility {

    private static final int MAX_FLIGHT_TICKS = 45;
    private static final int HARD_FINISH_TICKS = 60;
    private static final int LINGER_TICKS = 10;
    private static final double REEL_SPEED = 1.15;
    private static final double ARC_LIFT = 0.22;
    private static final double ARRIVE_DISTANCE_SQR = 4.84;
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "web_slinger_step");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "web_slinger_fall");

    @Override
    public String id() {
        return "web_slinger";
    }

    @Override
    public String displayName() {
        return "Web Slinger";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 64.0 && distanceSqr <= 676.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        double ax = target != null ? target.getX() : mob.getX();
        double ay = target != null ? target.getY() + 1.0 : mob.getY();
        double az = target != null ? target.getZ() : mob.getZ();
        ctx.state().putDouble("ax", ax);
        ctx.state().putDouble("ay", ay);
        ctx.state().putDouble("az", az);

        // The flight gear (removed in end()).
        AttributeInstance stepHeight = mob.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.addOrUpdateTransientModifier(
                new AttributeModifier(STEP_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance safeFall = mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFall != null) {
            safeFall.addOrUpdateTransientModifier(
                new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The pathfinder does not argue with the strand.
        mob.getNavigation().stop();
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ctx.state().getIntOr("arrived", 0) == 1) {
            return; // Cheap early-out: the linger after arrival does nothing.
        }
        double ax = ctx.state().getDoubleOr("ax", mob.getX());
        double ay = ctx.state().getDoubleOr("ay", mob.getY());
        double az = ctx.state().getDoubleOr("az", mob.getZ());

        // The reel: velocity straight at the anchor, arcing up
        // while the spider is below it.
        Vec3 center = mob.position().add(0.0, 0.5, 0.0);
        Vec3 toAnchor = new Vec3(ax - center.x, ay - center.y, az - center.z);
        double distance = toAnchor.length();
        if (distance > 0.001) {
            Vec3 velocity = toAnchor.scale(REEL_SPEED / distance);
            double lift = center.y < ay ? ARC_LIFT : 0.0;
            mob.setDeltaMovement(velocity.x, velocity.y + lift, velocity.z);
            mob.hurtMarked = true;
        }
        mob.fallDistance = 0.0;

        // The strand: END_ROD motes along the line to the anchor.
        if (ticks % 2 == 0) {
            for (int i = 1; i <= 5; i++) {
                double f = i / 6.0;
                level.sendParticles(ParticleTypes.END_ROD,
                    center.x + toAnchor.x * f, center.y + toAnchor.y * f, center.z + toAnchor.z * f,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // Arrival: close enough to the anchor, or out of flight.
        double distanceSqr = mob.distanceToSqr(ax, ay, az);
        if (distanceSqr <= ARRIVE_DISTANCE_SQR || ticks >= MAX_FLIGHT_TICKS) {
            arrive(ctx, level);
        }
    }

    /** The landing: coiled legs (JUMP_BOOST), the burst, and the hiss. */
    private static void arrive(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        ctx.state().putInt("arrived", 1);
        ctx.state().putInt("arrive_tick", ctx.ticksActive());
        mob.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 120, 1));
        mob.fallDistance = 0.0;
        level.sendParticles(ParticleTypes.END_ROD, mob.getX(), mob.getY() + 0.6, mob.getZ(),
            6, 0.4, 0.3, 0.4, 0.08);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 0.7f, 1.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_FINISH_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("arrived", 0) == 1
            && ctx.ticksActive() - ctx.state().getIntOr("arrive_tick", 0) >= LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance stepHeight = mob.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.removeModifier(STEP_ID);
        }
        AttributeInstance safeFall = mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFall != null) {
            safeFall.removeModifier(FALL_ID);
        }
        mob.fallDistance = 0.0;
        // The JUMP_BOOST is a timed effect and expires on its own.
    }
}
