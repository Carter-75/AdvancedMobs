package mod.advancedmobs.ability.bee;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Sting Barrage (Bee ability 1 of 5, attack).
 *
 * WHAT IT DOES: the bee blurs forward in a speed fueled dash,
 * dust motes streaking behind it, and stings its target three
 * times in a blink, each sting landing with a crit spark.
 * Unlike a normal bee it keeps its stinger and its health,
 * but each sting stacks a longer poison on the victim, so the
 * barrage gets deadlier the longer it stays on one target.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bee's current target within 10 blocks (distance
 *   squared 100).
 * - Duration: at most 60 ticks (3 seconds); the ability ends
 *   early the moment the third sting lands.
 * - The dash (start()): the bee gains SPEED II (amplifier 1)
 *   for 70 ticks, a visual and real boost under the steering
 *   below; end() removes it. The target's UUID is recorded;
 *   if the recorded target dies or vanishes, the bee steers
 *   at the framework's live target instead, and if there is
 *   none it simply hovers out the remaining ticks.
 * - Steering (tick(), every tick): the bee's velocity is
 *   written straight at the target's chest (target Y plus
 *   60 percent of its height) at 1.1 blocks per tick, the
 *   Vex and Phantom velocity steering pattern: the bee's
 *   flight answers per-tick velocity writes. Two gold DUST
 *   motes (color 0xEAC33C, scale 1.0) spawn at the bee's
 *   position every tick as the streaking trail.
 * - The stings: when the bee is within 1.5 blocks of the
 *   target and at least 12 ticks have passed since the last
 *   sting (the first sting has no wait), a sting lands:
 *   damage equal to the bee's live ATTACK_DAMAGE attribute
 *   value (2.0 for an unupgraded bee) dealt as a mob attack
 *   credited to the bee, 6 CRIT particles at the victim, a
 *   BEE_STING sound (volume 1.0, pitch 1.2), and POISON
 *   (amplifier 0) on the victim for 40 ticks after the
 *   first sting, 60 after the second, 80 after the third:
 *   the stacking is in the poison's clock, not its strength.
 *   The sting is a code damage call, never the bee's vanilla
 *   doHurtTarget, so the vanilla sting flag is never set:
 *   the bee keeps its stinger, takes no sting death, and
 *   loses no health, exactly as the design states.
 * - end(): the SPEED effect is removed from the bee. The
 *   victim's poison is the victim's own and expires on its
 *   own clock.
 *
 * WHAT IT LOOKS LIKE: a bee suddenly streaking in a straight
 * gold dusted line at its victim, three sharp sting sounds
 * in quick succession, each with a burst of crit sparks, and
 * the victim left ticking green with poison that lasts
 * longer with every sting it took.
 *
 * PERFORMANCE: tick() does one steering write, one distance
 * check, and 2 particles per tick; a sting is one damage
 * call, one effect application, and one sound. No entity
 * queries anywhere. Particle budget per activation: trail
 * 2 times 60 = 120, stings 3 times 6 = 18, total at most
 * 138 particles. The DUST options are a pre-built static
 * final.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "stings" (stings landed so far),
 * "last_sting" (tick of the last sting, -100 before the
 * first).
 */
public final class StingBarrageAbility implements MobAbility {

    private static final int MAX_TICKS = 60;
    private static final int STING_COUNT = 3;
    private static final int STING_INTERVAL = 12;
    private static final double DASH_SPEED = 1.1;
    private static final double STING_RANGE = 1.5;
    private static final DustParticleOptions TRAIL_DUST = new DustParticleOptions(0xEAC33C, 1.0f);

    @Override
    public String id() {
        return "sting_barrage";
    }

    @Override
    public String displayName() {
        return "Sting Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 70, 1));
        ctx.state().putInt("stings", 0);
        ctx.state().putInt("last_sting", -100);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();

        // The streaking trail, every tick of the dash.
        level.sendParticles(TRAIL_DUST, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            2, 0.15, 0.15, 0.15, 0.02);
        if (target == null) {
            return;
        }

        double dist = BeeAbilitySupport.steerToward(ctx,
            target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(), DASH_SPEED);
        int ticks = ctx.ticksActive();
        int stings = ctx.state().getIntOr("stings", 0);
        if (dist <= STING_RANGE && stings < STING_COUNT
            && ticks - ctx.state().getIntOr("last_sting", -100) >= STING_INTERVAL) {
            sting(ctx, level, target, stings);
            ctx.state().putInt("stings", stings + 1);
            ctx.state().putInt("last_sting", ticks);
        }
    }

    /** One sting of the barrage (see the class comment for the numbers). */
    private static void sting(AbilityContext ctx, ServerLevel level, LivingEntity target, int stingIndex) {
        Mob caster = ctx.mob();
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        // The stacking poison: 40, then 60, then 80 ticks.
        target.addEffect(new MobEffectInstance(MobEffects.POISON, 40 + stingIndex * 20, 0));
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() * 0.6,
            target.getZ(), 6, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BEE_STING,
            SoundSource.HOSTILE, 1.0f, 1.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("stings", 0) >= STING_COUNT || ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The dash fuel ends with the barrage.
        ctx.mob().removeEffect(MobEffects.SPEED);
    }
}
