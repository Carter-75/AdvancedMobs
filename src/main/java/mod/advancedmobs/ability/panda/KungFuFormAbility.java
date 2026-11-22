package mod.advancedmobs.ability.panda;

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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Kung Fu Form (Panda ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the panda rises up, cracks its knuckles in
 * a ring of crit sparkles, and takes a proper martial
 * stance. In this form it attacks in fast sweeping combos,
 * each strike throwing a sweep arc that hits groups, and
 * it sidesteps incoming blows in bursts of speed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the panda's current target within 14 blocks (distance
 *   squared 196).
 * - Duration: exactly 400 ticks (20 seconds).
 * - The form (start(), removed in end()): two transient
 *   modifiers and one effect. SCALE add_multiplied_base
 *   +0.15 (1.15x, id advancedmobs:kung_fu_form_scale: the
 *   panda stands taller; the design's render shift to a
 *   standing posture is carried by this growth plus the
 *   stance particles, because 26.2 has no server-side
 *   stand pose for a panda: sit and on-back are the only
 *   panda pose flags and both are lying down). ATTACK_SPEED
 *   add_multiplied_base +0.5 (id
 *   advancedmobs:kung_fu_form_speed; the design's
 *   ATTACK_SPEED line, applied through the null-tolerant
 *   helper and INERT on the panda: ATTACK_SPEED is absent
 *   from createLivingAttributes in 26.2, so no panda has
 *   the instance, the Grand Hex Form precedent; the fast
 *   combos below are code-driven instead). STRENGTH at
 *   amplifier 0 for 420 ticks (the design's STRENGTH line),
 *   removed in end().
 * - The opening (start()): 12 CRIT particles ring the
 *   panda at chest height and PANDA_AGGRESSIVE_AMBIENT
 *   plays at volume 1.2 (the knuckle crack and the stance
 *   call).
 * - The sweeping combos (polled every tick off the panda's
 *   last-hurt-mob timestamp, the Ursa Major pattern): when
 *   the timestamp changes and the last hurt mob is a living
 *   enemy, the strike sweeps: every OTHER enemy within
 *   radius 2.5 of the victim takes half the panda's live
 *   ATTACK_DAMAGE value as a mob attack credited to the
 *   panda (3.0 for an unupgraded panda, before the form's
 *   STRENGTH is counted by the victim hit itself), with 1
 *   SWEEP_ATTACK particle drawn at the victim and 4 CRIT
 *   sparkles (the design's SWEEP_ATTACK and CRIT lines).
 *   One bounded query per landed strike, never per tick.
 * - The sidestep (polled every tick off the panda's
 *   last-hurt-by timestamp, the Honey Coat pattern): when
 *   the timestamp changes, the attacker is a living enemy,
 *   and at least 40 ticks have passed since the last
 *   sidestep, the panda sidesteps: SPEED at amplifier 1
 *   (Speed II) for 40 ticks on its own clock, and a lateral
 *   dash: horizontal velocity set to 0.7 blocks per tick
 *   along the horizontal perpendicular of the
 *   attacker-to-panda direction (the side is picked by the
 *   sign of the cross product with the panda's look
 *   direction, so it sidesteps across the attacker's line,
 *   never straight into or away from it), vertical velocity
 *   untouched, hurtMarked set, with 6 CLOUD particles at
 *   the panda's feet. The sidestep does no damage and does
 *   not clear the attacker's target: it is footwork, not
 *   an escape.
 * - end(): both transient modifiers are removed and the
 *   STRENGTH is removed. The sidestep's SPEED rides its
 *   own 40 tick clock past the end, deliberately (a panda
 *   that sidesteps on the last tick keeps the burst).
 *
 * WHAT IT LOOKS LIKE: a panda suddenly a head taller,
 * knuckles cracking in a ring of crit stars, bellowing its
 * aggressive call; every paw swipe throws a white sweep
 * arc through the whole group around its victim, and every
 * few seconds it blurs sideways in a puff of cloud dust,
 * already swinging again.
 *
 * PERFORMANCE: per tick the cost is two integer timestamp
 * compares. The sweep is one bounded query only on a tick
 * where a strike actually landed; the sidestep is one
 * velocity write at most once per 40 ticks. Particle
 * budget per activation: opening 12, sweeps at most about
 * 20 strikes times 5 = 100, sidesteps at most 10 times 6
 * = 60, total at most about 172 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. SWEEP_ATTACK and CLOUD are particles, not
 * placed blocks.
 *
 * State tag keys: "hurt_mob_ts" (last seen last-hurt-mob
 * timestamp), "hurt_by_ts" (last seen last-hurt-by
 * timestamp), "last_sidestep" (tick of the last sidestep).
 */
public final class KungFuFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int STRENGTH_TICKS = 420;
    private static final double SWEEP_RADIUS = 2.5;
    private static final int SIDESTEP_COOLDOWN_TICKS = 40;
    private static final double SIDESTEP_SPEED = 0.7;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "kung_fu_form_scale");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "kung_fu_form_speed");

    @Override
    public String id() {
        return "kung_fu_form";
    }

    @Override
    public String displayName() {
        return "Kung Fu Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("hurt_mob_ts", caster.getLastHurtMobTimestamp());
        state.putInt("hurt_by_ts", caster.getLastHurtByMobTimestamp());
        state.putInt("last_sidestep", -SIDESTEP_COOLDOWN_TICKS);

        PandaAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PandaAbilitySupport.addModifier(caster, Attributes.ATTACK_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, STRENGTH_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.sendParticles(ParticleTypes.CRIT,
            caster.getX(), caster.getY() + 1.2, caster.getZ(),
            12, 0.6, 0.3, 0.6, 0.15);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.PANDA_AGGRESSIVE_AMBIENT, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();

        // The sweeping combos: a fresh landed strike sweeps
        // the group around its victim.
        int hurtMobTs = caster.getLastHurtMobTimestamp();
        if (hurtMobTs != state.getIntOr("hurt_mob_ts", 0)) {
            state.putInt("hurt_mob_ts", hurtMobTs);
            LivingEntity victim = caster.getLastHurtMob();
            if (victim != null && victim.isAlive() && PandaAbilitySupport.isEnemy(ctx, victim)) {
                sweep(ctx, level, victim);
            }
        }

        // The sidestep: a fresh landed blow, off cooldown,
        // blurs the panda sideways.
        int hurtByTs = caster.getLastHurtByMobTimestamp();
        if (hurtByTs != state.getIntOr("hurt_by_ts", 0)) {
            state.putInt("hurt_by_ts", hurtByTs);
            LivingEntity attacker = caster.getLastHurtByMob();
            int ticks = ctx.ticksActive();
            if (attacker != null && attacker.isAlive() && PandaAbilitySupport.isEnemy(ctx, attacker)
                && ticks - state.getIntOr("last_sidestep", -SIDESTEP_COOLDOWN_TICKS) >= SIDESTEP_COOLDOWN_TICKS) {
                state.putInt("last_sidestep", ticks);
                sidestep(ctx, level, attacker);
            }
        }
    }

    /** One sweep: every enemy around the victim except the victim takes half the panda's damage. */
    private void sweep(AbilityContext ctx, ServerLevel level, LivingEntity victim) {
        Mob caster = ctx.mob();
        float damage = (float) (caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5);
        List<LivingEntity> group = PandaAbilitySupport.enemiesInBox(
            ctx, victim.getX(), victim.getY(), victim.getZ(), SWEEP_RADIUS, 2.0, 2.0);
        for (LivingEntity bystander : group) {
            if (bystander == victim) {
                continue;
            }
            bystander.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK,
            victim.getX(), victim.getY() + 1.0, victim.getZ(),
            1, 0.1, 0.1, 0.1, 0.0);
        level.sendParticles(ParticleTypes.CRIT,
            victim.getX(), victim.getY() + 1.0, victim.getZ(),
            4, 0.4, 0.3, 0.4, 0.1);
    }

    /** One sidestep: Speed II and a lateral dash across the attacker's line. */
    private void sidestep(AbilityContext ctx, ServerLevel level, LivingEntity attacker) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 1));

        double awayX = caster.getX() - attacker.getX();
        double awayZ = caster.getZ() - attacker.getZ();
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            awayX = look.x;
            awayZ = look.z;
            length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 0.001) {
                awayX = 0.0;
                awayZ = 1.0;
                length = 1.0;
            }
        }
        awayX /= length;
        awayZ /= length;
        // The perpendicular, sided so the dash crosses the
        // attacker's line in the direction the panda faces.
        Vec3 look = caster.getLookAngle();
        double side = (awayZ * look.x - awayX * look.z) >= 0.0 ? 1.0 : -1.0;
        double dashX = -awayZ * side;
        double dashZ = awayX * side;
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dashX * SIDESTEP_SPEED, velocity.y, dashZ * SIDESTEP_SPEED);
        caster.hurtMarked = true;

        level.sendParticles(ParticleTypes.CLOUD,
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            6, 0.4, 0.2, 0.4, 0.08);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PandaAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        PandaAbilitySupport.removeModifier(caster, Attributes.ATTACK_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.STRENGTH);
    }
}
