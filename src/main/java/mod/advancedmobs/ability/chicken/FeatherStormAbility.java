package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Feather Storm (Chicken ability 3 of 5, control / aura).
 *
 * WHAT IT DOES: the chicken beats its wings into a
 * frenzy and fills the air around itself with a blizzard
 * of feathers and dust. Enemies caught in the storm are
 * blinded and slowed, flailing at feathers, while the
 * chicken itself vanishes into the whiteout and slips
 * between their legs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the chicken's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: 300 ticks (15 seconds), the storm centered
 *   on the moving chicken.
 * - The vanishing (start()): the chicken gains
 *   INVISIBILITY for 320 ticks with particles and icon
 *   hidden (the design's brief invisibility, run for the
 *   storm's length): inside its own feather blizzard the
 *   hen is a rustle and a shadow. The classic variant
 *   ambient cluck plays at the start (volume 1.2, pitch
 *   1.0), muffled by the storm. end() removes the
 *   INVISIBILITY; nothing else about the caster changes.
 * - The blizzard (tick(), every 2 ticks): 2 feather ITEM
 *   particles (the design's feather item particle) and 1
 *   CLOUD puff shed around the chicken's body with a 0.6
 *   block spread, so the storm reads as a churning cloud
 *   of down that follows the hen.
 * - The choking pulse (tick(), every 10 ticks): one
 *   bounded query at radius 5.0 around the chicken (2.5
 *   blocks of vertical reach either way); every enemy in
 *   it gains BLINDNESS for 40 ticks and SLOWNESS I
 *   (amplifier 0) for 40 ticks, refreshed each pulse, so
 *   a victim who stays in the storm stays blind and
 *   wading while one who escapes clears 2 seconds after
 *   the last pulse that caught it. The debuffs are
 *   fixed-duration effects that expire on their own
 *   clocks, the standard treatment for victim debuffs in
 *   this system; end() does not strip them.
 *
 * WHAT IT LOOKS LIKE: the hen erupts into a beating,
 * clucking knot of wings and disappears; in her place a
 * five block knot of swirling feathers and dust rolls
 * across the fight, and anything it washes over comes
 * out blinking, blind, and swinging at feathers.
 *
 * PERFORMANCE: the blizzard is 3 particles every 2 ticks
 * and nothing else; the pulse is one bounded query every
 * 10 ticks; no per-tick allocations beyond the particle
 * calls, and the feather particle option is a pre-built
 * static final. Particle budget per activation: 150
 * emissions times 3 = at most 450 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none of its own (the framework's tick
 * counter drives every interval).
 */
public final class FeatherStormAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int VANISH_TICKS = 320;
    private static final double STORM_RADIUS = 5.0;
    private static final int PULSE_INTERVAL = 10;
    private static final int DEBUFF_TICKS = 40;
    private static final ItemParticleOption FEATHER =
        new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER);

    @Override
    public String id() {
        return "feather_storm";
    }

    @Override
    public String displayName() {
        return "Feather Storm";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The vanishing (see the class comment): hidden
        // particles and icon, removed again in end().
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, VANISH_TICKS, 0, false, false, false));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Holder<SoundEvent> cluck = ChickenAbilitySupport.chickenSound(false);
            if (cluck != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), cluck,
                    SoundSource.HOSTILE, 1.2f, 1.0f);
            }
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
        if (ticks % 2 == 0) {
            level.sendParticles(FEATHER, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                2, 0.6, 0.4, 0.6, 0.08);
            level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                1, 0.6, 0.3, 0.6, 0.04);
        }
        if (ticks % PULSE_INTERVAL == 0) {
            List<LivingEntity> victims = ChickenAbilitySupport.enemiesAroundSelf(ctx,
                STORM_RADIUS, 2.5, 2.5);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DEBUFF_TICKS, 0));
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DEBUFF_TICKS, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The hen reappears as the storm settles (see the
        // class comment). Victim debuffs expire on their
        // own clocks and are not stripped.
        ctx.mob().removeEffect(MobEffects.INVISIBILITY);
    }
}
