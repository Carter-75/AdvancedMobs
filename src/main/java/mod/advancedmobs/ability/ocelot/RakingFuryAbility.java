package mod.advancedmobs.ability.ocelot;

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
 * Raking Fury (Ocelot ability 2 of 5, attack).
 *
 * WHAT IT DOES: the ocelot becomes a buzzsaw. It presses
 * in close and rakes with both front paws in alternating
 * left and right sweep arcs, a nonstop flurry of shallow
 * cuts. Unarmored targets are shredded by the sheer hit
 * count; armored ones take less per cut but are staggered
 * by every rake, slowed and shoved off line so they can
 * never settle into a counter.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the ocelot's current target within 10 blocks
 *   (distance squared 100).
 * - The crank (start()): a transient ATTACK_SPEED
 *   modifier of +0.6 add_multiplied_base (id
 *   advancedmobs:raking_fury_attack_speed) is applied for
 *   the whole activation and removed exactly in end().
 *   DOCUMENTED LIMIT: the ocelot has no ATTACK_SPEED
 *   attribute instance in 26.2 (LivingEntity's base
 *   attribute set does not include one), so the modifier
 *   is inert on this mob, the same documented situation as
 *   the Witch's Grand Hex Form and the Sheep's Shear
 *   Terror. The design's cranked-up attack speed is
 *   therefore delivered by the code-driven rake clock
 *   below, which is what actually sets the flurry rate;
 *   the modifier stays because the design names the
 *   attribute and the helper tolerates the missing
 *   instance. OCELOT_HURT at pitch 1.4 is the snarl that
 *   opens the flurry.
 * - The hunt (tick(), every 5 ticks): the ocelot is
 *   navigation-driven onto its live target at speed 1.3
 *   while it is more than 2.4 blocks away, so the flurry
 *   stays glued to the fight.
 * - The rake (tick(), in reach): within 2.4 blocks
 *   (distance squared 5.76), at most once per 6 ticks, one
 *   paw rakes: damage is the ocelot's live ATTACK_DAMAGE
 *   attribute value times 0.6 (a base ocelot: 1.8 per cut;
 *   the flurry wins on count, not weight), dealt through a
 *   mob attack source credited to the ocelot (the design's
 *   /damage typed as mob attack). Each rake also staggers:
 *   the victim gains SLOWNESS I refreshed to 30 ticks and
 *   takes a small shove of 0.25 horizontal directly away
 *   from the ocelot with no lift, so it is knocked off its
 *   line without being knocked out of rake range.
 * - The alternating arcs: rakes alternate sides by rake
 *   count (even rakes left, odd rakes right). Each rake
 *   draws 4 SWEEP_ATTACK motes in an arc offset 0.7 blocks
 *   to the raking side of the ocelot's facing at chest
 *   height; every 4th rake adds 3 CRIT motes at the
 *   victim, the deep cut in the pattern.
 * - Duration: exactly 200 ticks (10 seconds): at most 33
 *   rakes if the target stays in reach the whole time.
 *   The framework cooldown runs from the end as usual.
 * - end(): the ATTACK_SPEED modifier is removed (a no-op
 *   while the instance is missing). The stagger SLOWNESS
 *   rides its own 30 tick clock on the victim and is not
 *   stripped; it is a wound, not a stance. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a snarling ocelot plastered to its
 * target, white sweep arcs strobing left, right, left,
 * right at six-tick intervals, crit stars popping on every
 * fourth pass, and the victim lurching half a step sideways
 * with each cut, never quite able to turn and face it.
 *
 * PERFORMANCE: canTrigger is one distance check. tick()
 * returns immediately except every 5th tick (one distance
 * check, one navigation drive) and on rake ticks (one
 * distance check, one damage call, one effect apply, one
 * velocity write). No entity queries anywhere. Particle
 * budget per activation: rakes at most 33 times 4 = 132
 * sweep motes, deep cuts at most 8 times 3 = 24 crit,
 * total at most 156 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "next_rake" (the next activation tick a
 * rake may land), "rake_count" (rakes landed this
 * activation; its parity picks the sweep side).
 */
public final class RakingFuryAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int HUNT_INTERVAL = 5;
    private static final double RAKE_RANGE_SQR = 5.76; // 2.4 blocks
    private static final int RAKE_COOLDOWN_TICKS = 6;
    private static final double RAKE_DAMAGE_FRACTION = 0.6;
    private static final int STAGGER_TICKS = 30;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "raking_fury_attack_speed");

    @Override
    public String id() {
        return "raking_fury";
    }

    @Override
    public String displayName() {
        return "Raking Fury";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("next_rake", 0);
        ctx.state().putInt("rake_count", 0);
        // The design's cranked attack speed; see the class
        // comment for why the flurry rate is code-driven.
        OcelotAbilitySupport.addModifier(caster, Attributes.ATTACK_SPEED,
            new AttributeModifier(SPEED_ID, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.OCELOT_HURT, SoundSource.HOSTILE, 1.0f, 1.4f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double distanceSqr = caster.distanceToSqr(target);
        if (distanceSqr > RAKE_RANGE_SQR) {
            if (ticks % HUNT_INTERVAL == 0) {
                caster.getNavigation().moveTo(target, 1.3);
            }
            return;
        }
        if (ticks < ctx.state().getIntOr("next_rake", 0)) {
            return;
        }
        ctx.state().putInt("next_rake", ticks + RAKE_COOLDOWN_TICKS);
        rake(ctx, target);
    }

    /** One paw rake: the cut, the stagger, and the alternating sweep arc. */
    private void rake(AbilityContext ctx, LivingEntity target) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int rakeCount = ctx.state().getIntOr("rake_count", 0);
        ctx.state().putInt("rake_count", rakeCount + 1);
        // The cut: three fifths of the ocelot's live attack
        // damage, typed as a mob attack from the ocelot.
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * RAKE_DAMAGE_FRACTION;
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        // The stagger: slowed and shoved off line, every rake.
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, STAGGER_TICKS, 0));
        double shoveX = target.getX() - caster.getX();
        double shoveZ = target.getZ() - caster.getZ();
        double shoveLength = Math.sqrt(shoveX * shoveX + shoveZ * shoveZ);
        if (shoveLength > 1.0e-4) {
            Vec3 motion = target.getDeltaMovement();
            target.setDeltaMovement(motion.x + shoveX / shoveLength * 0.25, motion.y,
                motion.z + shoveZ / shoveLength * 0.25);
            target.hurtMarked = true;
        }
        // The alternating arc: even rakes left, odd rakes right.
        Vec3 look = caster.getLookAngle();
        double side = (rakeCount % 2 == 0) ? 1.0 : -1.0;
        double arcX = caster.getX() + look.x * 0.6 - look.z * 0.7 * side;
        double arcZ = caster.getZ() + look.z * 0.6 + look.x * 0.7 * side;
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, arcX, caster.getY() + 0.5, arcZ,
            4, 0.25, 0.25, 0.25, 0.05);
        if ((rakeCount + 1) % 4 == 0) {
            // The deep cut in the pattern.
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.6, target.getZ(),
                3, 0.3, 0.4, 0.3, 0.1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        OcelotAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_SPEED, SPEED_ID);
    }
}
