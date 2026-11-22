package mod.advancedmobs.ability.rabbit;

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

/**
 * Killer Bunny Form (Rabbit ability 1 of 5, transformation).
 *
 * WHAT IT DOES: the rabbit's eyes flash, it swells a step
 * larger, and the prey animal is gone: for fifteen seconds
 * it is the legendary killer bunny, bounding straight at
 * its enemy in long lunging hops and tearing in with rapid
 * bites, wreathed in angry motes.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the rabbit's current target within 14 blocks
 *   (distance squared 196).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The form (start(), all reversed exactly in end()):
 *   - SCALE +0.5 add_multiplied_base (1.5x size; id
 *     advancedmobs:killer_bunny_scale), the design's
 *     "grows a step larger".
 *   - JUMP_STRENGTH +0.30 add_value (id
 *     advancedmobs:killer_bunny_jump): the hops turn into
 *     lunges.
 *   - STRENGTH I (amplifier 0) for 320 ticks, the design's
 *     strength line. The rabbit HAS an ATTACK_DAMAGE
 *     instance (base 3.0, verified against the jar), and
 *     the effect's own attack modifier rides that
 *     attribute, so the bites below read the live value
 *     and count it automatically, exactly as vanilla
 *     melee does.
 *   - GLOWING for 320 ticks, the design's outline line.
 *     The outline renders in vanilla team white, not the
 *     design's red: a teamless mob has no server-side
 *     glow color in 26.2 (see the support class comment).
 *   - The vanilla EVIL variant is deliberately NOT
 *     flipped: its setter is private and the variant is
 *     permanent save data, not an activation state (see
 *     the support class comment).
 * - The opening (start()): 8 ANGRY_VILLAGER motes burst
 *   at the rabbit's head and RABBIT_AMBIENT plays low and
 *   loud (volume 1.5, pitch 0.7), the snarl.
 * - The hunt (tick()): while the target lives, the
 *   rabbit's navigation is driven at it at speed 1.5
 *   whenever it is more than 1.2 blocks away (a passive
 *   animal's AI never chases a set target on its own, so
 *   the drive is the chase: the Sporeling Swarm
 *   precedent). The rabbit's own hop movement does the
 *   lunging under the raised JUMP_STRENGTH.
 * - The bites (tick()): with the target within 1.2
 *   blocks and at least 10 ticks since the last bite
 *   (the last bite tick is recorded in the state tag),
 *   the rabbit bites once: damage equal to its LIVE
 *   ATTACK_DAMAGE attribute value (3.0 unupgraded, 6.0
 *   while STRENGTH I rides it, more with war upgrades)
 *   as a mob attack credited to the rabbit, with 4 CRIT
 *   particles at the victim and the RABBIT_ATTACK sound
 *   (volume 1.0, pitch 1.1). At most 2 bites a second:
 *   the rapid tearing of the design, roughly 12 damage
 *   a second unupgraded while it stays in range.
 * - Anger motes: 2 ANGRY_VILLAGER at the rabbit's head
 *   every 10 ticks for the whole form.
 * - end(): both attribute modifiers are removed and
 *   STRENGTH and GLOWING are stripped. A rabbit that
 *   somehow had either effect from another source loses
 *   it at end(), an accepted edge case shared with every
 *   fixed-duration effect in this system.
 *
 * WHAT IT LOOKS LIKE: a rabbit suddenly half again too
 * big, outlined in light, snorting angry villager motes,
 * covering the ground in huge bounds and snapping at its
 * enemy twice a second in little bursts of crit stars.
 *
 * PERFORMANCE: per tick at most one navigation drive and
 * one distance check; a bite is one damage call at most
 * once per 10 ticks; motes every 10 ticks. No entity
 * queries anywhere. Particle budget per activation: 8 at
 * start, anger 2 times 30 = 60, bites at most 30 times
 * 4 = 120, total at most 188 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "last_bite" (the activation tick of
 * the last landed bite).
 */
public final class KillerBunnyFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final double TRIGGER_RANGE_SQR = 196.0;
    private static final double BITE_RANGE = 1.2;
    private static final int BITE_INTERVAL_TICKS = 10;
    private static final double DRIVE_SPEED = 1.5;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "killer_bunny_scale");
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "killer_bunny_jump");

    @Override
    public String id() {
        return "killer_bunny_form";
    }

    @Override
    public String displayName() {
        return "Killer Bunny Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= TRIGGER_RANGE_SQR;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("last_bite", -BITE_INTERVAL_TICKS);

        RabbitAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        RabbitAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.30, AttributeModifier.Operation.ADD_VALUE));
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, EFFECT_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.6, caster.getZ(),
                8, 0.3, 0.3, 0.3, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.RABBIT_AMBIENT,
                SoundSource.HOSTILE, 1.5f, 0.7f);
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
        if (ticks % 10 == 0) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.6, caster.getZ(),
                2, 0.25, 0.25, 0.25, 0.03);
        }
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double distSqr = caster.distanceToSqr(target);
        if (distSqr > BITE_RANGE * BITE_RANGE) {
            caster.getNavigation().moveTo(target, DRIVE_SPEED);
            return;
        }
        CompoundTag state = ctx.state();
        if (ticks - state.getIntOr("last_bite", -BITE_INTERVAL_TICKS) < BITE_INTERVAL_TICKS) {
            return;
        }
        state.putInt("last_bite", ticks);
        float damage = (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.4, target.getZ(),
            4, 0.25, 0.25, 0.25, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.RABBIT_ATTACK,
            SoundSource.HOSTILE, 1.0f, 1.1f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        RabbitAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        RabbitAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        caster.removeEffect(MobEffects.STRENGTH);
        caster.removeEffect(MobEffects.GLOWING);
    }
}
