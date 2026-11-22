package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Wither Herald (Wither Skeleton ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the wither skeleton becomes a herald of decay.
 * Its frame enlarges, black DUST rolls off it, and DARKNESS
 * pulses out from it in waves with SHRIEK particles streaking
 * through the gloom, intermittently blinding every enemy near
 * it. It gains STRENGTH, and its own outline burns GLOWING
 * white against the dark it makes.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   wither skeleton's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The herald's frame and gifts, applied in start() and
 *   removed in end():
 *     SCALE add_value +0.5 transient modifier (modifier id
 *       advancedmobs:wither_herald_scale): 1.5 times normal
 *       size, about 3.6 blocks tall against the normal 2.4.
 *       Removed in end().
 *     STRENGTH II (amplifier 1) for the full 300 ticks: +6
 *       melee damage on its stone sword swings while it heralds.
 *     GLOWING (amplifier 0) for the full 300 ticks: the white
 *       outline against its own darkness.
 *   Removing the effects in end() is the standing fixed-duration
 *   effect contract of this system: if the skeleton somehow had
 *   either effect from another source, it loses it at end(), an
 *   accepted edge case shared with every effect ability in the
 *   mod.
 * - The darkness pulses: at ticks 60, 120, 180, and 240 (every
 *   3 seconds after the first), every enemy
 *   (WitherSkeletonAbilitySupport rules) within horizontal
 *   radius 6.0 of the herald, between 2 below and 3 above its
 *   feet, gets DARKNESS (amplifier 0) for 50 ticks: the pulsing
 *   black fog closes over their screen for 2.5 seconds, then
 *   thins until the next pulse lands. Four pulses across the
 *   15 seconds is the design's "intermittently blinded": the
 *   enemy spends about two thirds of the herald's reign in the
 *   dark and gets a breath of sight between pulses.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: on start, 10 black DUST motes
 * (RGB 0x141414, scale 1.5) boil off the enlarging frame and 4
 * SHRIEK particles streak out, with a low WITHER_SKELETON_AMBIENT
 * proclamation (volume 1.3, pitch 0.45). Every 5 ticks, 3 more
 * black DUST motes roll off its shoulders. Each darkness pulse
 * fires 6 SHRIEK particles from its chest with a
 * WITHER_SKELETON_HURT snarl pitched down (volume 1.0, pitch
 * 0.4).
 *
 * PERFORMANCE: tick() returns immediately except every 5th tick
 * (3 particles) and on the 4 pulse ticks (one bounded entity
 * query over a 12 by 5 by 12 box and one effect application per
 * enemy in it). No other scans, no allocations in the tick path
 * beyond the pulse's result list: the DUST options are a
 * pre-built static final. Particle budget per activation: 14 at
 * start plus at most 60 dust pulses times 3 = 180, plus 4
 * darkness pulses times 6 = 24, total at most 218 particles.
 *
 * CLEANUP: end() removes the SCALE modifier by id and the
 * STRENGTH and GLOWING effects. If the caster dies mid-herald
 * the modifier and effects die with the entity; nothing else
 * was created, so there is no leftover state in the world. The
 * DARKNESS on victims is a normal timed effect and simply
 * expires within 50 ticks of the last pulse.
 *
 * State tag keys: none. The whole activation is the modifier,
 * the two effects, the pulse schedule, and the tick counter,
 * so a resumed activation after a chunk reload behaves
 * identically.
 */
public final class WitherHeraldAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 60;
    private static final int DARKNESS_TICKS = 50;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "wither_herald_scale");
    // 26.2 DustParticleOptions takes an RGB int color: near-black 0x141414.
    private static final DustParticleOptions BLACK_DUST = new DustParticleOptions(0x141414, 1.5f);
    // 26.2 SHRIEK is not a simple particle type: it takes a
    // ShriekParticleOption carrying a delay in ticks (0 here, so
    // the shrieks streak out immediately).
    private static final ShriekParticleOption SHRIEK = new ShriekParticleOption(0);

    @Override
    public String id() {
        return "wither_herald";
    }

    @Override
    public String displayName() {
        return "Wither Herald";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();

        // The enlarged frame.
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(
                new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        }
        // The herald's gifts: strength and the white outline.
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS, 1));
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(BLACK_DUST, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                10, 0.6, 0.8, 0.6, 0.03);
            level.sendParticles(SHRIEK, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                4, 0.5, 0.6, 0.5, 0.1);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 1.3f, 0.45f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean dustTick = ticks % 5 == 0;
        boolean pulseTick = ticks % PULSE_INTERVAL == 0 && ticks < DURATION_TICKS;
        if (!dustTick && !pulseTick) {
            return; // Cheap early-out: nothing runs off the 5 tick and pulse marks.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        if (dustTick) {
            // Black dust rolling off the enlarged frame.
            level.sendParticles(BLACK_DUST, mob.getX(), mob.getY() + 1.8, mob.getZ(),
                3, 0.7, 0.7, 0.7, 0.02);
        }

        if (pulseTick) {
            // The darkness pulse: the gloom closes over every
            // enemy near the herald.
            List<LivingEntity> victims = WitherSkeletonAbilitySupport.enemiesAroundSelf(ctx, 6.0, 2.0, 3.0);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_TICKS, 0));
            }
            level.sendParticles(SHRIEK, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                6, 0.6, 0.7, 0.6, 0.12);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_SKELETON_HURT, SoundSource.HOSTILE, 1.0f, 0.4f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        mob.removeEffect(MobEffects.STRENGTH);
        mob.removeEffect(MobEffects.GLOWING);
    }
}
