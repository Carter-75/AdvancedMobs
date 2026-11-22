package mod.advancedmobs.ability.turtle;

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
import net.minecraft.world.phys.Vec3;

/**
 * Tide Ram (Turtle ability 2 of 5, attack / mobility).
 *
 * WHAT IT DOES: in water the slow turtle becomes a torpedo.
 * Dolphin's grace and full swim efficiency surge through it
 * as it lines up, then it slams shell first into its target:
 * splash bursting everywhere and a heavy shove that sends
 * the enemy tumbling away through the water while the
 * turtle swims on through the hole it made.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the turtle's current target within 16 blocks (distance
 *   squared 256), plus the turtle in water (a tide ram needs
 *   water to run in).
 * - The run-up (start()): the target's chest position is
 *   recorded, and for the activation the turtle gains
 *   DOLPHINS_GRACE I (the design's line; removed in end()),
 *   a WATER_MOVEMENT_EFFICIENCY +1.0 ADD_VALUE modifier
 *   (the design's attribute line at its maximum: the turtle
 *   loses none of its speed to the water while it rams;
 *   removed in end()), and an ATTACK_KNOCKBACK +2.0
 *   ADD_VALUE modifier (the design's knockback line;
 *   applied through the null-tolerant helper because the
 *   turtle's attribute set is not guaranteed to carry the
 *   instance, in which case it is simply inert and the code
 *   shove below does all the work either way).
 * - The charge (tick()): the turtle is steered straight at
 *   the target's live chest at 1.15 blocks per tick (code
 *   velocity, the package steering helper), and its last
 *   known position is refreshed every tick so a target that
 *   dies mid-charge is still charged at its last spot. 3
 *   SPLASH particles trail at the turtle's shell each tick.
 *   If the turtle leaves the water mid-charge the run is
 *   over (the strike simply never lands).
 * - The strike: the first tick the turtle comes within 1.9
 *   blocks of the target, the ram lands exactly once: 8.0
 *   flat damage as a mob attack credited to the turtle
 *   (the turtle has no ATTACK_DAMAGE instance; see the
 *   provider comment), then the shove: the victim's velocity
 *   is set to 1.5 blocks per tick directly away from the
 *   turtle horizontally plus 0.45 up, hurtMarked so the
 *   knockback syncs, sending it tumbling through the water.
 *   14 SPLASH and 8 DAMAGE_INDICATOR particles burst at the
 *   victim and TURTLE_SWIM plays at volume 1.2, pitch 0.9.
 * - After the strike the turtle swims on for 10 more ticks
 *   (steered past the victim toward the recorded point) and
 *   the ability ends. Without a strike, reaching within 1.0
 *   blocks of the recorded last position also ends it (the
 *   charge ran through empty water), and the hard cap is 80
 *   ticks either way.
 *
 * WHAT IT LOOKS LIKE: a shell knifing through the water in
 * a fan of spray, one hard splash burst at the victim, and
 * the victim cartwheeling away through the current while
 * the turtle cruises on past it, unbothered.
 *
 * PERFORMANCE: per tick the charge is one steering write,
 * one distance computation, one position record, and 3
 * particles; the strike is one damage call and one velocity
 * write. No entity queries anywhere. Particle budget per
 * activation: at most 80 ticks times 3 = 240 trail plus 22
 * at the strike, total at most 262 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() removes both modifiers and the
 * DOLPHINS_GRACE effect (the grace is this ability's own;
 * a turtle that somehow had it from another source would
 * lose it at end(), the accepted edge case shared with
 * every fixed-duration self effect in this system).
 *
 * State tag keys: "tx", "ty", "tz" (the target's last known
 * chest position), "struck" (1 once the ram has landed),
 * "strike_tick" (the tick the ram landed on).
 */
public final class TideRamAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 80;
    private static final int SWIM_THROUGH_TICKS = 10;
    private static final double CHARGE_SPEED = 1.15;
    private static final double STRIKE_RANGE = 1.9;
    private static final float STRIKE_DAMAGE = 8.0f;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "tide_ram_efficiency");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "tide_ram_knockback");

    @Override
    public String id() {
        return "tide_ram";
    }

    @Override
    public String displayName() {
        return "Tide Ram";
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
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        } else {
            ctx.state().putDouble("tx", caster.getX());
            ctx.state().putDouble("ty", caster.getY());
            ctx.state().putDouble("tz", caster.getZ());
        }
        ctx.state().putInt("struck", 0);
        ctx.state().putInt("strike_tick", -1);

        // The design's three self boosts for the run.
        caster.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, HARD_CAP_TICKS + 20, 0));
        TurtleAbilitySupport.addModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY,
            new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        TurtleAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (!caster.isInWater()) {
            return; // Out of water the run is dead; isFinished ends it.
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        double dist = TurtleAbilitySupport.steerToward(caster, tx, ty, tz, CHARGE_SPEED);

        // The spray fans off the shell as it knifes through the water.
        Vec3 motion = caster.getDeltaMovement();
        level.sendParticles(ParticleTypes.SPLASH,
            caster.getX() - motion.x * 0.8, caster.getY() + 0.3, caster.getZ() - motion.z * 0.8,
            3, 0.2, 0.2, 0.2, 0.03);

        if (ctx.state().getIntOr("struck", 0) == 0 && target != null && dist <= STRIKE_RANGE) {
            strike(ctx, level, target);
        }
    }

    /** Records the target's chest position as the charge point. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY() + target.getBbHeight() * 0.5);
        ctx.state().putDouble("tz", target.getZ());
    }

    /** The ram lands: damage, the heavy shove, and the splash burst. See the class comment. */
    private void strike(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        Mob caster = ctx.mob();
        ctx.state().putInt("struck", 1);
        ctx.state().putInt("strike_tick", ctx.ticksActive());

        target.hurtServer(level, level.damageSources().mobAttack(caster), STRIKE_DAMAGE);

        // The shove: straight away from the turtle, hard, with
        // lift, so the victim tumbles through the water.
        double dx = target.getX() - caster.getX();
        double dz = target.getZ() - caster.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            dx = 1.0;
            dz = 0.0;
            len = 1.0;
        }
        target.setDeltaMovement(dx / len * 1.5, 0.45, dz / len * 1.5);
        target.hurtMarked = true;

        level.sendParticles(ParticleTypes.SPLASH, target.getX(), target.getY() + 0.5, target.getZ(),
            14, 0.4, 0.4, 0.4, 0.1);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 0.6, target.getZ(),
            8, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.TURTLE_SWIM, SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        if (!ctx.mob().isInWater()) {
            return true;
        }
        int strikeTick = ctx.state().getIntOr("strike_tick", -1);
        if (strikeTick >= 0) {
            return ticks >= strikeTick + SWIM_THROUGH_TICKS;
        }
        // No strike yet: running through the recorded position
        // ends the charge.
        Mob caster = ctx.mob();
        double dx = ctx.state().getDoubleOr("tx", caster.getX()) - caster.getX();
        double dy = ctx.state().getDoubleOr("ty", caster.getY()) - caster.getY();
        double dz = ctx.state().getDoubleOr("tz", caster.getZ()) - caster.getZ();
        return ticks > 5 && dx * dx + dy * dy + dz * dz <= 1.0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        TurtleAbilitySupport.removeModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY, EFFICIENCY_ID);
        TurtleAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        caster.removeEffect(MobEffects.DOLPHINS_GRACE);
    }
}
