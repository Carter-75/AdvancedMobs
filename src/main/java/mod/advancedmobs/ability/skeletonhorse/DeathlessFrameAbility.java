package mod.advancedmobs.ability.skeletonhorse;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Deathless Frame (Skeleton Horse ability 3 of 5, defense).
 *
 * WHAT IT DOES: there is nothing left to kill. When the
 * skeleton horse is beaten down to low health it rattles,
 * sheds a burst of poof and soul particles as its frame
 * comes apart, and pulls itself back together with a surge
 * of healing and absorption under a shell of resistance.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 16 blocks (distance
 *   squared 256), plus the horse at or below 40 percent of
 *   its max health. The ability can therefore only fire
 *   when the horse is genuinely losing the fight.
 * - The surge (start(), all of it one-shot):
 *   - Healing: heal(6.0), a direct health restore. The
 *     design's Uses line names the instant_health effect,
 *     but the skeleton horse is UNDEAD and vanilla inverts
 *     heal and harm for the undead: INSTANT_HEALTH would
 *     deal 6 damage to it instead of healing. heal() adds
 *     raw health with no inversion (capped at max health
 *     by vanilla), which is the surge the design intends.
 *   - Absorption: ABSORPTION II (amplifier 1, 8 absorption
 *     points) for 200 ticks.
 *   - Resistance: RESISTANCE I for 200 ticks.
 *   Absorption and resistance are not heal effects, so the
 *   undead inversion does not touch them.
 * - Once per fight phase: the surge fires once per
 *   activation, and the framework's cooldown (10 to 60
 *   seconds, rolled when this ability ENDS, never when it
 *   starts) plus the 40 percent health trigger together
 *   carry the design's once-per-phase limit: the horse must
 *   survive the cooldown and be beaten back under 40
 *   percent before the frame can pull together again.
 * - The hold-together drift (tick(), every 10 ticks): 2
 *   POOF particles drift off the frame while the surge's
 *   shell lasts, the visible sign the bones are knitting.
 * - Duration: exactly 200 ticks (10 seconds), matching the
 *   surge effects' 200 ticks, hard finish.
 * - end(): ABSORPTION and RESISTANCE are removed, exactly
 *   reversing start() (they expire on the same tick anyway;
 *   the removal keeps the reversal exact on early
 *   termination). The healed health is the surge's gift and
 *   stays, like every other one-shot heal in this mod.
 *
 * WHAT IT LOOKS LIKE: at the brink, the skeleton horse
 * rattles apart in a burst of white poof and blue soul
 * wisps (SKELETON_HORSE_HURT at pitch 0.7, the bone rattle,
 * under SKELETON_HORSE_AMBIENT at pitch 0.9), then pulls
 * itself back together, golden absorption hearts over
 * bone, shrugging off hits while faint poof keeps drifting
 * off the mended frame.
 *
 * PERFORMANCE: start() is one heal call, two effect
 * applications, and one particle burst; tick() returns
 * immediately except every 10th tick (2 particles); there
 * are no entity queries anywhere in the ability. Particle
 * budget per activation: 24 at start, drift 20 times 2 =
 * 40, total at most 64 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none of its own (the framework's fw_ticks
 * drives the duration).
 */
public final class DeathlessFrameAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int DRIFT_INTERVAL = 10;
    private static final float HEAL_AMOUNT = 6.0f;
    private static final double LOW_HEALTH_FRACTION = 0.40;

    @Override
    public String id() {
        return "deathless_frame";
    }

    @Override
    public String displayName() {
        return "Deathless Frame";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        Mob caster = ctx.mob();
        return caster.getHealth() <= caster.getMaxHealth() * LOW_HEALTH_FRACTION;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();

        // The surge: raw heal (see the class comment for why this is
        // not the INSTANT_HEALTH effect), then the shell.
        caster.heal(HEAL_AMOUNT);
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, DURATION_TICKS, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SKELETON_HORSE_HURT, SoundSource.HOSTILE, 1.2f, 0.7f);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SKELETON_HORSE_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.9f);
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                14, 0.6, 0.6, 0.6, 0.1);
            level.sendParticles(ParticleTypes.SOUL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                10, 0.6, 0.6, 0.6, 0.08);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % DRIFT_INTERVAL != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            2, 0.5, 0.5, 0.5, 0.04);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.ABSORPTION);
        caster.removeEffect(MobEffects.RESISTANCE);
    }
}
