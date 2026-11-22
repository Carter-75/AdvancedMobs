package mod.advancedmobs.ability.mule;

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

/**
 * Hybrid Vigor (Mule ability 1 of 5, transformation).
 *
 * WHAT IT DOES: the mule surges with the best of both
 * parents: horse strength and spring, donkey temper. Enchant
 * sparkles run down its flanks, it closes on its enemy at a
 * boosted pace, and it strikes with flat code hoof blows for
 * the duration: a pack animal fighting like a warhorse.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mule's current target within 14 blocks (distance
 *   squared 196).
 * - The surge (start(), all removed exactly in end()):
 *   STRENGTH I and SPEED I for 420 ticks (the design's
 *   strength and speed effect lines), plus two transient
 *   attribute modifiers under fixed ids: JUMP_STRENGTH
 *   +0.30 ADD_VALUE (the design's jump power line: a plain
 *   mule's randomized 0.4 to 1.0 base jumps noticeably
 *   higher) and ATTACK_DAMAGE +3.0 ADD_VALUE (the design's
 *   attack line). The attack modifier is documented INERT
 *   on the mule: verified against the jar, the inherited
 *   AbstractHorse attribute chain has no ATTACK_DAMAGE
 *   instance, so the null-tolerant helper simply never
 *   applies it (the Grand Hex Form precedent for inert
 *   design lines). The form's actual damage is the code
 *   strike below, and the STRENGTH effect still raises any
 *   vanilla melee the mule ever lands.
 * - The chase (tick()): every 5 ticks, while the target is
 *   alive and beyond strike range, the mule's navigation is
 *   driven onto the target's live position at speed
 *   multiplier 1.2 (a passive animal's AI never chases a set
 *   target on its own; the Sporeling Swarm precedent).
 * - The strike (tick()): when the target is within 2.4
 *   blocks and at least 15 ticks have passed since the last
 *   strike (the strike tick is kept in the state tag), the
 *   mule lands one hoof blow: 5.0 flat mob-attack damage
 *   credited to the mule, the victim knocked 0.4 blocks per
 *   tick horizontally away from the mule and 0.2 up
 *   (hurtMarked so it syncs), 6 CRIT particles at the
 *   victim's chest, and MULE_HURT at pitch 1.2 as the
 *   strike cry. At most one strike per 15 ticks, so the
 *   form's ceiling is about 20 damage over a full duration
 *   against a target that stays in reach.
 * - Sparkles (tick()): 2 ENCHANT particles at the mule's
 *   flanks every 4 ticks (the design's enchant sparkles
 *   running down its sides), plus a 12 ENCHANT burst and
 *   MULE_ANGRY (volume 1.0) in start() as the surge hits.
 * - Duration: exactly 400 ticks (20 seconds), hard finish.
 * - end(): both modifiers are removed and both effects are
 *   stripped. Idempotent: safe when start() never ran.
 *
 * WHAT IT LOOKS LIKE: the mule brays, enchant motes sheet
 * down its flanks, and it comes on fast and springy,
 * lashing out with its front hooves in flashes of crit
 * sparks: a hauler that has decided it is cavalry.
 *
 * PERFORMANCE: start() is two effect applications, two
 * modifier writes, one sound, and one particle burst.
 * tick() is one distance check per tick, a navigation drive
 * every 5 ticks, a strike at most once per 15 ticks, and a
 * particle send every 4 ticks; there are no entity queries
 * anywhere (the strike reads the framework target only).
 * Particle budget per activation: 12 at start, sparkles at
 * most 100 sends of 2 = 200, strikes at most 26 times 6 =
 * 156, total at most 368 particles.
 *
 * TERRAIN: this ability changes no blocks, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "last_strike" (tick of the last hoof
 * strike, -15 at start so the first strike can land at once).
 */
public final class HybridVigorAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int STRIKE_INTERVAL_TICKS = 15;
    private static final double STRIKE_RANGE_SQR = 2.4 * 2.4;
    private static final float STRIKE_DAMAGE = 5.0f;
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "hybrid_vigor_jump");
    private static final Identifier ATTACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "hybrid_vigor_attack");

    @Override
    public String id() {
        return "hybrid_vigor";
    }

    @Override
    public String displayName() {
        return "Hybrid Vigor";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("last_strike", -STRIKE_INTERVAL_TICKS);
        // The surge: the design's effect and attribute lines.
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS + 20, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS + 20, 0));
        MuleAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.30, AttributeModifier.Operation.ADD_VALUE));
        MuleAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(ATTACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.MULE_ANGRY,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            12, 0.6, 0.6, 0.6, 0.5);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Sparkles running down the flanks.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                2, caster.getBbWidth() * 0.6, 0.5, caster.getBbWidth() * 0.6, 0.3);
        }

        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double distanceSqr = caster.distanceToSqr(target);
        CompoundTag state = ctx.state();
        if (distanceSqr <= STRIKE_RANGE_SQR
            && ticks - state.getIntOr("last_strike", -STRIKE_INTERVAL_TICKS) >= STRIKE_INTERVAL_TICKS) {
            // The hoof blow: flat code damage (see the class
            // comment for why the number is not attribute based).
            state.putInt("last_strike", ticks);
            target.hurtServer(level, level.damageSources().mobAttack(caster), STRIKE_DAMAGE);
            double awayX = target.getX() - caster.getX();
            double awayZ = target.getZ() - caster.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length > 1.0e-4) {
                Vec3 current = target.getDeltaMovement();
                target.setDeltaMovement(awayX / length * 0.4, Math.max(current.y, 0.2), awayZ / length * 0.4);
                target.hurtMarked = true;
            }
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(),
                6, 0.3, 0.4, 0.3, 0.1);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.MULE_HURT,
                SoundSource.HOSTILE, 0.8f, 1.2f);
        } else if (ticks % 5 == 0) {
            // The chase: drive onto the target while it is
            // out of strike range (see the class comment).
            caster.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.2);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        MuleAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        MuleAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, ATTACK_ID);
        caster.removeEffect(MobEffects.STRENGTH);
        caster.removeEffect(MobEffects.SPEED);
    }
}
