package mod.advancedmobs.ability.bee;

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
import net.minecraft.world.entity.animal.bee.Bee;

import java.util.List;

/**
 * Queen Ascendant (Bee ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the bee grows to twice its size, heart and
 * enchant particles swirling around it, and gains a
 * commanding hum. As a queen it hits harder, flies faster,
 * and every bee ally near it fights with strength and
 * speed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bee's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The ascension (start()): three transient attribute
 *   modifiers under fixed ids, all removed in end(): SCALE
 *   +1.0 ADD_MULTIPLIED_BASE (twice the size, model and
 *   hitbox together), ATTACK_DAMAGE +3.0 ADD_VALUE (an
 *   unupgraded queen hits for 5.0, up from the bee's 2.0),
 *   and FLYING_SPEED +0.5 ADD_MULTIPLIED_BASE (half again
 *   as fast on the attribute a bee actually steers by).
 *   The stripes deepening to gold and black is the design's
 *   flavor for the size change: there is no server side
 *   retexture hook in 26.2, so the look is carried by the
 *   doubled size and the particle swirl, documented here as
 *   the approximation. The opening burst is 8 HEART and 12
 *   ENCHANT particles at the bee, with a BEE_LOOP_AGGRESSIVE
 *   sound (volume 1.5, pitch 0.8) as the commanding hum.
 * - The court (tick(), every 10 ticks): one bounded query
 *   of radius 8.0 around the queen (2.5 below, 2.5 above).
 *   Every candidate that is a Bee AND an ally of the queen
 *   (the shared either-direction ally rule) gains STRENGTH
 *   I (amplifier 0) and SPEED I (amplifier 0), each
 *   refreshed to 30 ticks. Non-bee allies get nothing: the
 *   design's court is bees only. The queen herself is not
 *   in the query results (the ally rule excludes the
 *   caster); her own buffs are the attribute modifiers
 *   above. A bee that leaves the radius simply stops being
 *   refreshed and its last refresh expires on its own
 *   clock.
 * - The swirl (tick(), every 10 ticks, same pass): 2 HEART
 *   and 3 ENCHANT particles around the queen.
 * - end(): the three transient modifiers are removed by
 *   id, exactly reversing start(). The court's effect
 *   refreshes expire on their own clocks.
 *
 * WHAT IT LOOKS LIKE: a bee suddenly twice as big, hearts
 * and enchantment motes curling around it, humming low and
 * loud, while every bee near it darts faster and stings
 * harder.
 *
 * PERFORMANCE: one bounded query and one 5 particle swirl
 * every 10 ticks; nothing runs on the other ticks, and the
 * modifiers are built once at start() and removed once in
 * end(). Particle budget per activation: 20 at start plus
 * 40 pulses times 5 = 200, total at most 220 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the duration rides the framework
 * tick counter; all modifiers live under fixed ids).
 */
public final class QueenAscendantAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int COURT_INTERVAL = 10;
    private static final double COURT_RADIUS = 8.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "queen_ascendant_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "queen_ascendant_damage");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "queen_ascendant_speed");

    @Override
    public String id() {
        return "queen_ascendant";
    }

    @Override
    public String displayName() {
        return "Queen Ascendant";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        BeeAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        BeeAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));
        BeeAbilitySupport.addModifier(caster, Attributes.FLYING_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            8, 0.4, 0.4, 0.4, 0.05);
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            12, 0.5, 0.5, 0.5, 0.1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BEE_LOOP_AGGRESSIVE,
            SoundSource.HOSTILE, 1.5f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % COURT_INTERVAL != 0) {
            return; // Cheap early-out: the court runs every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        // The swirl around the queen.
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            2, 0.5, 0.4, 0.5, 0.03);
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            3, 0.5, 0.4, 0.5, 0.05);
        // The court: bee allies near the queen fight harder
        // and faster (see the class comment).
        List<LivingEntity> allies = BeeAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), COURT_RADIUS, 2.5, 2.5);
        for (LivingEntity ally : allies) {
            if (ally instanceof Bee) {
                ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        BeeAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        BeeAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        BeeAbilitySupport.removeModifier(caster, Attributes.FLYING_SPEED, SPEED_ID);
    }
}
