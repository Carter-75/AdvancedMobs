package mod.advancedmobs.ability.tadpole;

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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Current Rider (Tadpole ability 4 of 5, mobility).
 *
 * WHAT IT DOES: the tadpole reads the water and becomes
 * one with it. Drag falls away, the current carries it
 * at full speed, and it darts around its enemy in a
 * tightening spiral of underwater motes, striking as it
 * crosses and vanishing to the far side of the circle
 * before anything can track it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the tadpole's current target within 16 blocks
 *   (distance squared 256), AND the tadpole in water:
 *   there is no current to ride on dry land.
 * - Duration: 240 ticks (12 seconds).
 * - The rider kit (start(), removed exactly in end()):
 *   two transient attribute modifiers and one effect.
 *   WATER_MOVEMENT_EFFICIENCY +1.0 ADD_VALUE (the
 *   design's "full" line: the attribute runs 0 to 1 and
 *   1 is full land speed underwater, so +1.0 pins it to
 *   the cap whatever the base was). AIR_DRAG_MODIFIER
 *   -1.0 ADD_VALUE (the design's air drag line: the
 *   attribute defaults to 1 and 0 is frictionless, so
 *   the tadpole's velocity writes carry with almost no
 *   decay between steering ticks). DOLPHINS_GRACE I for
 *   260 ticks (the design's effect line, the swim speed
 *   that rides on top of the efficiency).
 * - The spiral (tick(), every tick): the orbit angle is
 *   kept in the state tag as a double, seeded in start()
 *   at the bearing from the target to the tadpole, and
 *   advances 0.22 radians per tick. The orbit radius
 *   breathes: 2.0 plus the sine of (ticks times 0.05)
 *   times 0.8, so it swings between 1.2 and 2.8 blocks.
 *   The anchor is the target's live position plus the
 *   orbit offset, at the target's height plus 0.3. The
 *   tadpole is steered straight at the anchor at 1.15
 *   blocks per tick (the package steering helper, one
 *   velocity write per tick, the Pod Call ram window
 *   pattern). When the target is dead or lost, the
 *   spiral stops and only the particle drift runs until
 *   the duration ends.
 * - The strikes (tick()): when the tadpole comes within
 *   1.5 blocks of the target and the strike clock (kept
 *   in the state tag, ready at tick 0) has come due, the
 *   strike lands: 2.0 flat damage as a mob attack
 *   credited to the tadpole (it has no ATTACK_DAMAGE
 *   instance; see the provider comment), 4 UNDERWATER
 *   plus 2 CURRENT_DOWN particles burst at the target,
 *   FISH_SWIM plays at volume 0.7, pitch 1.6, the orbit
 *   angle jumps by pi (the vanish: the spiral resumes
 *   from the far side of the circle), and the next
 *   strike is set 25 ticks out. At most 10 strikes fit
 *   in one activation.
 * - The drift: 1 UNDERWATER plus 1 CURRENT_DOWN mote at
 *   the tadpole every 2 ticks (the design's two particle
 *   lines, the spiral's visible wake).
 * - end(): both transient modifiers are removed and
 *   DOLPHINS_GRACE is stripped, so the tadpole goes back
 *   to ordinary fish drag the moment the ride ends.
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: a tadpole suddenly far too fast
 * for the water, corkscrewing around its enemy in a
 * helix of sinking current motes, blinking across the
 * circle each time it connects.
 *
 * PERFORMANCE: per tick the work is one angle advance,
 * one steering write, and one distance check; particles
 * run every 2 ticks; a strike is one damage call and
 * one sound, at most once per 25 ticks. No entity
 * queries and no registry or config lookups in the tick
 * path (both modifiers are built once as static finals).
 * Particle budget per activation: at most 120 drift
 * passes times 2 = 240, at most 10 strikes times 6 =
 * 60, total at most 300.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "angle" (the orbit angle in radians,
 * a double), "strike_at" (the activation tick at which
 * the next strike may land).
 */
public final class CurrentRiderAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int GRACE_TICKS = 260;
    private static final double ANGLE_STEP = 0.22;
    private static final double ORBIT_BASE = 2.0;
    private static final double ORBIT_SWING = 0.8;
    private static final double STEER_SPEED = 1.15;
    private static final double STRIKE_RANGE = 1.5;
    private static final int STRIKE_COOLDOWN_TICKS = 25;
    private static final float STRIKE_DAMAGE = 2.0f;
    private static final Identifier WATER_EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "current_rider_water_efficiency");
    private static final Identifier AIR_DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "current_rider_air_drag");
    private static final AttributeModifier WATER_EFFICIENCY_MODIFIER =
        new AttributeModifier(WATER_EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE);
    private static final AttributeModifier AIR_DRAG_CHANGE =
        new AttributeModifier(AIR_DRAG_ID, -1.0, AttributeModifier.Operation.ADD_VALUE);

    @Override
    public String id() {
        return "current_rider";
    }

    @Override
    public String displayName() {
        return "Current Rider";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0 && ctx.mob().isInWater();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        TadpoleAbilitySupport.addModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY, WATER_EFFICIENCY_MODIFIER);
        TadpoleAbilitySupport.addModifier(caster, Attributes.AIR_DRAG_MODIFIER, AIR_DRAG_CHANGE);
        caster.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, GRACE_TICKS, 0));
        LivingEntity target = ctx.target();
        double seed = target != null
            ? Math.atan2(caster.getZ() - target.getZ(), caster.getX() - target.getX())
            : 0.0;
        ctx.state().putDouble("angle", seed);
        ctx.state().putInt("strike_at", 0);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 1.3f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.UNDERWATER, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                1, 0.25, 0.2, 0.25, 0.02);
            level.sendParticles(ParticleTypes.CURRENT_DOWN, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                1, 0.25, 0.2, 0.25, 0.02);
        }
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double angle = ctx.state().getDoubleOr("angle", 0.0) + ANGLE_STEP;
        ctx.state().putDouble("angle", angle);
        double radius = ORBIT_BASE + Math.sin(ticks * 0.05) * ORBIT_SWING;
        TadpoleAbilitySupport.steerToward(caster,
            target.getX() + Math.cos(angle) * radius, target.getY() + 0.3,
            target.getZ() + Math.sin(angle) * radius, STEER_SPEED);
        if (caster.distanceToSqr(target) <= STRIKE_RANGE * STRIKE_RANGE
            && ticks >= ctx.state().getIntOr("strike_at", 0)) {
            strike(ctx, level, target, angle);
        }
    }

    /** One spiral strike: damage, the burst, and the vanish to the far side. */
    private void strike(AbilityContext ctx, ServerLevel level, LivingEntity target, double angle) {
        Mob caster = ctx.mob();
        target.hurtServer(level, level.damageSources().mobAttack(caster), STRIKE_DAMAGE);
        ctx.state().putInt("strike_at", ctx.ticksActive() + STRIKE_COOLDOWN_TICKS);
        // The vanish: the spiral resumes from the far side.
        ctx.state().putDouble("angle", angle + Math.PI);
        level.sendParticles(ParticleTypes.UNDERWATER, target.getX(), target.getY() + 0.4, target.getZ(),
            4, 0.3, 0.3, 0.3, 0.05);
        level.sendParticles(ParticleTypes.CURRENT_DOWN, target.getX(), target.getY() + 0.4, target.getZ(),
            2, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 0.7f, 1.6f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        TadpoleAbilitySupport.removeModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY, WATER_EFFICIENCY_ID);
        TadpoleAbilitySupport.removeModifier(caster, Attributes.AIR_DRAG_MODIFIER, AIR_DRAG_ID);
        caster.removeEffect(MobEffects.DOLPHINS_GRACE);
    }
}
