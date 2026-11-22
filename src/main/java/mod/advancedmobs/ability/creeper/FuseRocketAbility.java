package mod.advancedmobs.ability.creeper;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Fuse Rocket (Creeper ability 2 of 5, mobility).
 *
 * WHAT IT DOES: the creeper lights its own fuse as a rocket motor
 * and launches in a screaming arc across the field, fire and smoke
 * blasting from its feet. It crosses gaps and enemy lines in the
 * air, hangs at the top under slow falling control, and comes down
 * on whatever it aimed at, detonating on landing right in the
 * middle of it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   creeper's current target between 6 and 20 blocks away
 *   (distance squared 36 to 400), and the creeper on the ground:
 *   a rocket needs solid ground to launch from.
 * - The launch (start()): velocity is set ballistically, aimed at
 *   the target's position at launch time: horizontal component
 *   along the caster-to-target line at speed 1.0, vertical
 *   component 0.95. There is no steering after launch; the arc is
 *   the aim. Two aids shape the arc:
 *     GRAVITY: a transient attribute modifier,
 *       add_multiplied_base -0.4 (60 percent of normal gravity),
 *       id advancedmobs:fuse_rocket_gravity, added in start() and
 *       removed in end(). The arc flies higher and farther.
 *     SLOW_FALLING: amplifier 0 for 220 ticks, no particles, no
 *       icon, applied in start() and removed in end(). It does
 *       nothing on the way up and caps the descent, so the
 *       creeper floats down onto its target instead of plummeting
 *       past it, and takes no fall damage from its own flight.
 *   Sounds at launch: FIREWORK_ROCKET_LAUNCH and CREEPER_PRIMED,
 *   the motor and the fuse together.
 * - Flight (tick()): while ascending (the first 20 ticks, every 2
 *   ticks) the motor burns: 1 EXPLOSION_EMITTER, 3 SMOKE, and 2
 *   FLAME particles at the creeper's feet. The airborne flag is
 *   set the first tick the creeper leaves the ground; before
 *   that, the launch tick's ground contact cannot fake a landing.
 * - The landing detonation: the first tick after the airborne
 *   flag is set where the creeper is on the ground again, or is
 *   in water at tick 10 or later (a rocket that lands in water
 *   cooks off at the surface instead of sinking silently), the
 *   creeper detonates where it stands: a power 3.5 explosion
 *   through Level.explode (typed explosion damage, vanilla
 *   falloff, its own flash and boom), and THE CASTER DIES in it,
 *   discarded right after the blast if the blast did not already
 *   kill it, exactly like Cluster Detonation and vanilla
 *   Creeper.explodeCreeper. The landing blast is the creeper's
 *   own detonation delivered by rocket; the death is the design,
 *   and the framework's termination path runs end() once.
 * - Hard finish at tick 200: if the creeper never lands (wedged
 *   on a ledge, still floating), the ability just ends: no
 *   detonation, the creeper survives, and end() strips the flight
 *   aids so it drops and walks normally.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the landing explosion picks
 * its block interaction from the shared
 * AbilityContext.mobGriefingAllowed() check at detonation time:
 * Level.ExplosionInteraction.MOB (blocks break, permanently) when
 * mobGriefing is on, Level.ExplosionInteraction.NONE (no block is
 * ever altered; damage and knockback unchanged) when it is off.
 *
 * WHAT IT LOOKS LIKE: a firework launch and a primed hiss, a
 * comet of EXPLOSION_EMITTER flashes, smoke, and flame trailing
 * up the arc, a slow green hang at the top of the flight, then
 * the descent and the blast where it touches down.
 *
 * PERFORMANCE: the trail runs every 2 ticks for at most 20 ticks
 * (10 pulses times 6 particles = at most 60 particles; the
 * landing explosion's particles are vanilla's). Every other tick
 * is two ground/water reads. No entity scans, no allocations in
 * the flight path.
 *
 * CLEANUP: end() removes the GRAVITY modifier by id and the
 * SLOW_FALLING effect. Both calls are safe on a dead or removed
 * caster (the normal outcome here), so the termination path's
 * end() after the landing detonation is a harmless no-op pass.
 * The detonated flag guarantees the blast fires at most once.
 *
 * State tag keys: "airborne" (1 once the creeper has left the
 * ground), "detonated" (1 once the landing blast has fired).
 */
public final class FuseRocketAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 200;
    private static final int TRAIL_TICKS = 20;
    private static final int SLOW_FALLING_TICKS = 220;
    private static final float LANDING_POWER = 3.5f;
    private static final double LAUNCH_HORIZONTAL_SPEED = 1.0;
    private static final double LAUNCH_VERTICAL_SPEED = 0.95;
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fuse_rocket_gravity");

    @Override
    public String id() {
        return "fuse_rocket";
    }

    @Override
    public String displayName() {
        return "Fuse Rocket";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("airborne", 0);
        ctx.state().putInt("detonated", 0);

        // Aim: the horizontal line from the creeper to its target
        // as it stands right now. No target (lost between the
        // trigger check and start) falls back to the look direction.
        LivingEntity target = ctx.target();
        double dirX;
        double dirZ;
        if (target != null) {
            dirX = target.getX() - mob.getX();
            dirZ = target.getZ() - mob.getZ();
        } else {
            Vec3 look = mob.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            dirX = 0.0;
            dirZ = 1.0;
            length = 1.0;
        }
        mob.setDeltaMovement(dirX / length * LAUNCH_HORIZONTAL_SPEED,
            LAUNCH_VERTICAL_SPEED, dirZ / length * LAUNCH_HORIZONTAL_SPEED);

        // The two flight aids: lighter gravity for the arc, slow
        // falling for the descent. Both are stripped in end().
        AttributeInstance gravity = mob.getAttribute(Attributes.GRAVITY);
        if (gravity != null) {
            gravity.addOrUpdateTransientModifier(new AttributeModifier(
                GRAVITY_ID, -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, SLOW_FALLING_TICKS, 0, false, false, false));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 1.0f, 0.9f);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("detonated", 0) != 0) {
            return; // The blast already fired; the caster is gone.
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();

        // The motor burn: a comet trail while the rocket climbs.
        if (level != null && ticks <= TRAIL_TICKS && ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                1, 0.1, 0.1, 0.1, 0.0);
            level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                3, 0.25, 0.2, 0.25, 0.04);
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                2, 0.2, 0.2, 0.2, 0.03);
        }

        boolean wasAirborne = ctx.state().getIntOr("airborne", 0) != 0;
        if (!mob.onGround()) {
            ctx.state().putInt("airborne", 1);
            // Water has no ground contact: a rocket that comes
            // down in water cooks off at the surface (see spec).
            if (wasAirborne && mob.isInWater() && ticks >= 10 && level != null) {
                detonate(ctx, level);
            }
            return;
        }
        if (wasAirborne && level != null) {
            detonate(ctx, level);
        }
    }

    /** The landing detonation: the blast, then the caster's deliberate death (see class comment). */
    private static void detonate(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        ctx.state().putInt("detonated", 1);
        // The one shared terrain gate, read at detonation time.
        Level.ExplosionInteraction interaction = ctx.mobGriefingAllowed()
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;
        level.explode(caster, caster.getX(), caster.getY(), caster.getZ(),
            LANDING_POWER, false, interaction);
        if (!caster.isRemoved()) {
            caster.discard();
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("detonated", 0) != 0
            || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance gravity = mob.getAttribute(Attributes.GRAVITY);
        if (gravity != null) {
            gravity.removeModifier(GRAVITY_ID);
        }
        mob.removeEffect(MobEffects.SLOW_FALLING);
    }
}
