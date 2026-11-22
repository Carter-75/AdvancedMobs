package mod.advancedmobs.ability.tropicalfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Kaleidoscope Flash (Tropical Fish ability 1 of 5,
 * attack / control).
 *
 * WHAT IT DOES: the little fish fans its fins wide
 * and strobes through the whole spectrum, red to
 * blue to gold washing over everything nearby in
 * shifting colored dust. Enemies caught in the
 * light show are nauseated and briefly blinded,
 * their aim swimming, and lose their lock on the
 * fish, while the fish itself darts from side to
 * side between them, untouched, re-strobing every
 * second to keep the nausea rolling.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the fish's current target within 14
 *   blocks (distance squared 196), plus the fish in
 *   water (a beached fish cannot fan a light show).
 * - Duration: 160 ticks (8 seconds).
 * - The opening strobe (start()): 2 FLASH particles
 *   at the caster in the first two cycle colors (the
 *   design's flash line; FLASH is an ARGB color
 *   particle in 26.2 and the four cycle options are
 *   built once as static finals), 12
 *   DUST_COLOR_TRANSITION motes washing outward
 *   (the design's dust_color_transition line: start
 *   color, end color, scale 1.4), and FISH_SWIM at
 *   volume 1.2, pitch 1.6. One bounded query (radius
 *   6.0 around the caster, 2.5 vertical both ways):
 *   every enemy (the package enemy rule) gains
 *   NAUSEA for 200 ticks and BLINDNESS for 60 ticks
 *   (the design's nausea and blindness lines: 10
 *   seconds of swimming aim, 3 seconds of true
 *   blind), and every enemy Mob in the query that
 *   had the caster as its target loses it
 *   (setTarget(null)): the flash breaks every lock
 *   at once, the Silver Flash School precedent.
 * - The darting (every tick): the caster is steered
 *   at 0.75 blocks per tick toward a dart anchor.
 *   The anchor is recalculated every 10 ticks from
 *   the live target's position: 2.5 blocks to one
 *   side of the target (perpendicular to the
 *   caster-to-target line, at the target's own
 *   height), the side alternating each recalculation,
 *   so the fish zigzags across the enemy's front and
 *   is never where a swing was aimed. With no living
 *   target the last anchor stands and the fish holds
 *   its course.
 * - The re-strobe (every 20 ticks): 1 FLASH particle
 *   in the next cycle color plus 6
 *   DUST_COLOR_TRANSITION motes of the next cycle
 *   pair at the caster, and one bounded query
 *   (radius 6.0, as above) refreshing NAUSEA to 100
 *   ticks on every enemy inside: a fish that stays
 *   in the light stays nauseated, but the blindness
 *   is the opening strobe's alone and is never
 *   refreshed.
 * - end(): nothing to strip. The caster carries no
 *   modifiers or self effects, and the victims'
 *   nausea and blindness ride their own clocks out
 *   (the Bray of Doom and Silver Flash precedents).
 *
 * WHAT IT LOOKS LIKE: a tiny fish erupting in a
 * red, gold, blue, green strobe, colored dust
 * rolling off it in waves, enemies reeling with
 * swimming vision while the fish flickers from side
 * to side in front of them, there and gone and
 * there again.
 *
 * PERFORMANCE: start() is one bounded query and a
 * fixed burst. Per tick the darting is one steering
 * write and nothing else; the re-strobe's bounded
 * query runs every 20 ticks only. Particle budget
 * per activation: start 14, at most 7 re-strobes
 * times 7 = 49, total at most 63. All particle
 * options are pre-built static finals; there are no
 * per-tick allocations in the hot path (the anchor
 * is plain doubles in the state tag).
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * State tag keys: "ax", "ay", "az" (the current
 * dart anchor), "side" (the anchor's side, +1 or
 * -1, flipped at each recalculation), "strobe" (the
 * color cycle index).
 */
public final class KaleidoscopeFlashAbility implements MobAbility {

    private static final int DURATION_TICKS = 160;
    private static final double FLASH_RADIUS = 6.0;
    private static final int NAUSEA_TICKS = 200;
    private static final int NAUSEA_REFRESH_TICKS = 100;
    private static final int BLIND_TICKS = 60;
    private static final int STROBE_INTERVAL = 20;
    private static final int ANCHOR_INTERVAL = 10;
    private static final double DART_SPEED = 0.75;
    private static final double DART_OFFSET = 2.5;

    /** The strobe cycle's flash colors, ARGB. Built once; particle options are immutable. */
    private static final ColorParticleOption[] FLASH_CYCLE = {
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFF3B30),
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD60A),
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFF0A84FF),
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFF32D74B)
    };

    /** The strobe cycle's color washes (from RGB, to RGB, scale), matching FLASH_CYCLE. */
    private static final DustColorTransitionOptions[] WASH_CYCLE = {
        new DustColorTransitionOptions(0xFF3B30, 0x0A84FF, 1.4f),
        new DustColorTransitionOptions(0xFFD60A, 0x32D74B, 1.4f),
        new DustColorTransitionOptions(0x0A84FF, 0xFFD60A, 1.4f),
        new DustColorTransitionOptions(0x32D74B, 0xFF3B30, 1.4f)
    };

    @Override
    public String id() {
        return "kaleidoscope_flash";
    }

    @Override
    public String displayName() {
        return "Kaleidoscope Flash";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("side", 1);
        state.putInt("strobe", 0);
        state.putDouble("ax", caster.getX());
        state.putDouble("ay", caster.getY());
        state.putDouble("az", caster.getZ());
        if (level == null) {
            return;
        }

        // The opening strobe: two flashes and the first color wash.
        level.sendParticles(FLASH_CYCLE[0], caster.getX(), caster.getY() + 0.3, caster.getZ(),
            1, 0.3, 0.25, 0.3, 0.0);
        level.sendParticles(FLASH_CYCLE[1], caster.getX(), caster.getY() + 0.3, caster.getZ(),
            1, 0.3, 0.25, 0.3, 0.0);
        level.sendParticles(WASH_CYCLE[0], caster.getX(), caster.getY() + 0.3, caster.getZ(),
            12, 0.8, 0.5, 0.8, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.2f, 1.6f);

        // Blinding, nausea, and the broken locks.
        List<LivingEntity> enemies = TropicalFishAbilitySupport.enemiesAroundSelf(ctx,
            FLASH_RADIUS, 2.5, 2.5);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, NAUSEA_TICKS, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS, 0));
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                enemyMob.setTarget(null);
            }
        }
        state.putInt("strobe", 1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        // The dart anchor flips sides on its interval.
        if (ticks % ANCHOR_INTERVAL == 1) {
            LivingEntity target = ctx.target();
            if (target != null) {
                int side = -state.getIntOr("side", 1);
                state.putInt("side", side);
                double toX = target.getX() - caster.getX();
                double toZ = target.getZ() - caster.getZ();
                double toLength = Math.sqrt(toX * toX + toZ * toZ);
                if (toLength < 0.001) {
                    toX = 1.0;
                    toZ = 0.0;
                    toLength = 1.0;
                }
                // Perpendicular to the caster-to-target line, on the new side.
                double perpX = -toZ / toLength;
                double perpZ = toX / toLength;
                state.putDouble("ax", target.getX() + perpX * DART_OFFSET * side);
                state.putDouble("ay", target.getY());
                state.putDouble("az", target.getZ() + perpZ * DART_OFFSET * side);
            }
        }
        TropicalFishAbilitySupport.steerToward(caster,
            state.getDoubleOr("ax", caster.getX()),
            state.getDoubleOr("ay", caster.getY()),
            state.getDoubleOr("az", caster.getZ()), DART_SPEED);

        // The re-strobe: next colors, nausea refreshed, blindness never.
        if (ticks % STROBE_INTERVAL == 0) {
            int strobe = state.getIntOr("strobe", 1);
            level.sendParticles(FLASH_CYCLE[strobe % FLASH_CYCLE.length],
                caster.getX(), caster.getY() + 0.3, caster.getZ(), 1, 0.3, 0.25, 0.3, 0.0);
            level.sendParticles(WASH_CYCLE[strobe % WASH_CYCLE.length],
                caster.getX(), caster.getY() + 0.3, caster.getZ(), 6, 0.7, 0.4, 0.7, 0.05);
            state.putInt("strobe", strobe + 1);
            List<LivingEntity> enemies = TropicalFishAbilitySupport.enemiesAroundSelf(ctx,
                FLASH_RADIUS, 2.5, 2.5);
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, NAUSEA_REFRESH_TICKS, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: no caster state was applied, and the
        // victims' nausea and blindness ride their own clocks.
    }
}
