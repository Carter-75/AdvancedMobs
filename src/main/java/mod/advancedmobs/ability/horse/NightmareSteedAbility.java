package mod.advancedmobs.ability.horse;

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
 * Nightmare Steed (Horse ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the horse's coat darkens, soul fire flames
 * lick along its mane, and it becomes a nightmare steed
 * for the duration: fireproof, faster, trailing blue flame,
 * and its hooves set fire to everything it strikes.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 14 blocks (distance
 *   squared 196).
 * - The form (start(), all removed exactly in end()):
 *   MOVEMENT_SPEED +0.30 ADD_MULTIPLIED_BASE (the design's
 *   movement_speed line: a plain horse's 0.1125 to 0.3375
 *   becomes roughly 0.15 to 0.44) and FIRE_RESISTANCE for
 *   420 ticks (the design's fireproof line; removed in
 *   end() rather than left to expire, so the form's
 *   properties end with the form). HORSE_ANGRY plays at
 *   volume 1.3, pitch 0.8, and 12 SOUL_FIRE_FLAME burst
 *   along the mane as the flames catch.
 * - The flame trail (tick(), every 2 ticks): 2
 *   SOUL_FIRE_FLAME, one at the mane (body height 1.4) and
 *   one at the hooves (0.3), so the steed reads as wreathed
 *   in blue fire standing still and trails flame as it
 *   runs.
 * - The chase (every 5 ticks): the steed is
 *   navigation-driven onto its live target at speed 1.2
 *   (a plain horse's AI never closes to strike on its own;
 *   the form's hunt is code-driven, the Sporeling Swarm
 *   precedent).
 * - The igniting strike: when the target is within 2.3
 *   blocks and the strike clock allows (at most once per
 *   20 ticks, the next strike tick kept in the state tag),
 *   the steed strikes for 5.0 damage as a mob attack
 *   credited to the horse (a flat code value: a horse has
 *   no ATTACK_DAMAGE attribute instance in 26.2 and no
 *   melee attack of its own at any size, verified against
 *   the jar; the form's strikes are code-driven, the
 *   Abominable Form precedent) and the victim is ignited
 *   for 4 seconds (igniteForSeconds(4): the design's
 *   ignition on hit, fire ticks delivered by vanilla
 *   burning). 8 SOUL_FIRE_FLAME and 1 SWEEP_ATTACK burst
 *   at the victim, and HORSE_ANGRY plays at the victim
 *   (volume 1.0, pitch 1.1).
 * - Duration: exactly 400 ticks (20 seconds), hard finish.
 * - end(): the speed modifier is removed and the
 *   FIRE_RESISTANCE is removed. Ignitions already set on
 *   victims are ordinary vanilla burning and burn out on
 *   their own clocks.
 *
 * WHAT IT LOOKS LIKE: a horse wreathed in blue soul fire,
 * mane aflame, running its target down at a dead gallop
 * and leaving it burning with every strike. Documented
 * limit: the design's coat darkening and glowing eyes are
 * a custom render tint, and 26.2 has no server-side hook
 * to retexture or tint a living mob per activation (the
 * Alpha Form and Queen Ascendant precedent), so the look
 * is carried by the soul fire trail and the burning
 * strikes.
 *
 * PERFORMANCE: the trail is 2 particles every 2 ticks; the
 * chase is one navigation drive every 5 ticks; the strike
 * is one distance check per tick and one damage call at
 * most once per 20 ticks; there are no entity queries
 * anywhere in the ability. Particle budget per activation:
 * trail 200 times 2 = 400, strikes at most 20 times 9 =
 * 180, start 12, total at most 592 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The ignitions are entity burning, not placed
 * fire blocks.
 *
 * State tag keys: "next_strike" (first tick another hoof
 * strike may land).
 */
public final class NightmareSteedAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int TRAIL_INTERVAL = 2;
    private static final int CHASE_INTERVAL = 5;
    private static final double STRIKE_RANGE_SQR = 5.29; // 2.3 blocks
    private static final double STRIKE_DAMAGE = 5.0;
    private static final int STRIKE_COOLDOWN_TICKS = 20;
    private static final float IGNITE_SECONDS = 4.0f;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "nightmare_steed_speed");

    @Override
    public String id() {
        return "nightmare_steed";
    }

    @Override
    public String displayName() {
        return "Nightmare Steed";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("next_strike", 0);

        // The form: speed and fireproofing.
        HorseAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        caster.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.HORSE_ANGRY, SoundSource.HOSTILE, 1.3f, 0.8f);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                12, 0.55, 0.5, 0.55, 0.08);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level == null) {
            return;
        }

        // The flame trail: mane and hooves.
        if (ticks % TRAIL_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, caster.getX(), caster.getY() + 1.4, caster.getZ(),
                1, 0.35, 0.2, 0.35, 0.03);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                1, 0.4, 0.1, 0.4, 0.03);
        }

        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        // The hunt: close on the target.
        if (ticks % CHASE_INTERVAL == 0 && caster.distanceToSqr(target) > STRIKE_RANGE_SQR) {
            caster.getNavigation().moveTo(target, 1.2);
        }
        // The igniting strike.
        if (caster.distanceToSqr(target) <= STRIKE_RANGE_SQR
            && ticks >= ctx.state().getIntOr("next_strike", 0)) {
            ctx.state().putInt("next_strike", ticks + STRIKE_COOLDOWN_TICKS);
            target.hurtServer(level, level.damageSources().mobAttack(caster), (float) STRIKE_DAMAGE);
            target.igniteForSeconds(IGNITE_SECONDS);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 0.8, target.getZ(),
                8, 0.4, 0.5, 0.4, 0.1);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 0.8, target.getZ(),
                1, 0.3, 0.3, 0.3, 0.0);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.HORSE_ANGRY, SoundSource.HOSTILE, 1.0f, 1.1f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        HorseAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.FIRE_RESISTANCE);
    }
}
