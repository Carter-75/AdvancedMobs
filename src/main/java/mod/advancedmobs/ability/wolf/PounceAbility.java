package mod.advancedmobs.ability.wolf;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * Pounce (Wolf ability 4 of 5, mobility).
 *
 * WHAT IT DOES: the wolf crouches, dust kicking up at its
 * paws, then launches in one long flat leap clear across
 * the fight. It takes no fall damage from the leap, and if
 * it comes down on its target the landing pins the victim
 * briefly while the wolf mauls it with one bite.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wolf on the ground and its current target between
 *   6 and 20 blocks away (distance squared 36 to 400): too
 *   close and a pounce is pointless, too far and even the
 *   leap cannot carry it.
 * - The leap attributes (start()): two transient modifiers
 *   under fixed ids, applied for the whole activation and
 *   removed in end():
 *     GRAVITY           add_multiplied_base  -0.5 (gravity
 *                       halved, the design's GRAVITY line:
 *                       this is what flattens and lengthens
 *                       the arc)
 *     SAFE_FALL_DISTANCE add_value           +30.0 (the
 *                       design's no fall damage from the
 *                       leap; 30 extra safe blocks covers
 *                       any arc this leap can make)
 *   The design's JUMP_STRENGTH line is carried by the launch
 *   velocity itself (a code leap, below), since JUMP_STRENGTH
 *   only feeds the vanilla jump action the wolf is not using.
 * - The crouch (ticks 1 to 10): navigation is stopped each
 *   tick (never NoAi) and 2 CLOUD particles kick up at the
 *   wolf's paws every tick. The target's UUID is recorded
 *   at start.
 * - The launch (tick 10, once): velocity is written toward
 *   the target's live position (its recorded position if it
 *   is gone): horizontal speed is the horizontal distance
 *   divided by 14 ticks, clamped to [0.6, 1.7] blocks per
 *   tick, vertical 0.42. With gravity halved the arc stays
 *   airborne roughly 20 ticks and carries the full gap.
 * - The landing (the first tick after launch where the wolf
 *   is on the ground again): if the recorded victim is
 *   alive and within 3.0 blocks (distance squared 9.0), the
 *   mauling lands: damage equal to the wolf's live
 *   ATTACK_DAMAGE attribute value as a mob attack credited
 *   to the wolf, SLOWNESS III (amplifier 2) for 60 ticks on
 *   the victim (the pin), and a burst of 10 CLOUD particles
 *   at the landing spot. A landing that misses the victim
 *   still puffs the CLOUD burst, with no damage or pin.
 * - Duration: the ability finishes on the landing tick, or
 *   at the hard cap of tick 70 if the wolf somehow never
 *   lands (stuck midair, riding, or swimming out of the
 *   arc). end() removes the two modifiers either way.
 *
 * WHAT IT LOOKS LIKE: a crouching wolf with dust at its
 * paws, one long flat sailing leap with halved gravity, and
 * a dust burst where it comes down on its prey.
 *
 * PERFORMANCE: per tick one navigation stop during the
 * crouch, one onGround read in flight, and at most one UUID
 * resolve on the launch and landing ticks; no entity
 * queries anywhere. Particle budget per activation: 20
 * crouch dust plus 10 landing cloud = at most 30 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "victim" (the target's UUID, stored at
 * start), "launched" (1 once the launch velocity is
 * written).
 */
public final class PounceAbility implements MobAbility {

    private static final int CROUCH_TICKS = 10;
    private static final int HARD_CAP_TICKS = 70;
    private static final double LANDING_RANGE_SQR = 9.0; // 3.0 blocks
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pounce_gravity");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pounce_safe_fall");

    @Override
    public String id() {
        return "pounce";
    }

    @Override
    public String displayName() {
        return "Pounce";
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
        Mob caster = ctx.mob();
        ctx.state().putInt("launched", 0);
        LivingEntity target = ctx.target();
        if (target != null) {
            ctx.state().store("victim", UUIDUtil.CODEC, target.getUUID());
            ctx.state().putDouble("tx", target.getX());
            ctx.state().putDouble("tz", target.getZ());
        }
        WolfAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(GRAVITY_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        WolfAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (ctx.state().getIntOr("launched", 0) == 0) {
            // The crouch: rooted via navigation stops, dust at the paws.
            caster.getNavigation().stop();
            if (level != null) {
                level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    2, 0.35, 0.05, 0.35, 0.05);
            }
            if (ticks >= CROUCH_TICKS) {
                launch(ctx);
            }
            return;
        }
        // In flight: the first grounded tick after the launch is the landing.
        if (caster.onGround() && ticks > CROUCH_TICKS + 2) {
            land(ctx);
            ctx.state().putInt("landed", 1);
        }
    }

    /** Writes the one flat launch velocity toward the victim's position. */
    private void launch(AbilityContext ctx) {
        Mob caster = ctx.mob();
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());
        LivingEntity target = ctx.target();
        if (target != null) {
            tx = target.getX();
            tz = target.getZ();
        }
        double dx = tx - caster.getX();
        double dz = tz - caster.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.001) {
            double speed = Math.max(0.6, Math.min(1.7, horizontal / 14.0));
            caster.setDeltaMovement(dx / horizontal * speed, 0.42, dz / horizontal * speed);
            caster.hurtMarked = true;
        }
        ctx.state().putInt("launched", 1);
    }

    /** Resolves the landing: the pin and bite if the victim is under the wolf. */
    private void land(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.2, caster.getZ(),
            10, 0.5, 0.15, 0.5, 0.08);
        if (!ctx.state().contains("victim")) {
            return;
        }
        UUID victimId = ctx.state().read("victim", UUIDUtil.CODEC).orElse(null);
        if (victimId == null) {
            return;
        }
        Entity found = level.getEntity(victimId);
        if (found instanceof LivingEntity victim && victim.isAlive()
            && caster.distanceToSqr(victim) <= LANDING_RANGE_SQR) {
            double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 2));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("landed", 0) != 0
            || ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        WolfAbilitySupport.removeModifier(caster, Attributes.GRAVITY, GRAVITY_ID);
        WolfAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
    }
}
