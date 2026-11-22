package mod.advancedmobs.ability.witch;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Cauldron Cataclysm (Witch ability 1 of 5, attack).
 *
 * WHAT IT DOES: the witch summons a colossal cauldron hanging
 * over the arena, and after a short ominous gather she tips it
 * with a cackle and rains a deluge of real splash potions down
 * across the whole fight. Every bottle's payload is rolled
 * fresh from her debuff menu (poison, harming, slowness,
 * weakness), and every fifth volley swaps one bottle for a
 * lingering potion, so patches of the barrage pool on the
 * ground as lingering clouds. Standing under the cauldron when
 * it tips is the worst place in the world.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the witch's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: exactly 140 ticks (7 seconds) from start().
 * - The cauldron (start()): a block display showing a cauldron
 *   at scale 3.0, full brightness, snapped to 7 blocks above
 *   the target's feet position at cast time. That position is
 *   the arena center and is recorded in the state tag; the
 *   cauldron does not track the target afterwards, so running
 *   out from under it is the counterplay. The witch plays a
 *   BREWING_STAND_BREW at the center as the brew gathers.
 * - The gather (ticks 1 to 19): every 4 ticks, 6 EFFECT
 *   sparkles (color rolled per pulse from the debuff table)
 *   swirl under the cauldron and 4 BUBBLEs foam at its rim.
 * - The tip (tick 20): the upright display is discarded and
 *   replaced by the same cauldron tilted 125 degrees about the
 *   X axis (left rotation quaternion 0.887, 0, 0, 0.462), the
 *   witch cackles (WITCH_CELEBRATE, volume 1.2), and the rain
 *   begins.
 * - The rain (ticks 20 to 100, one volley every 4 ticks, 21
 *   volleys): each volley throws 2 real splash potions from
 *   the cauldron's mouth (center, 6.5 blocks up) aimed at
 *   random points in the disc of radius 5 around the center at
 *   the center's ground height, speed 1.2, inaccuracy 1.0.
 *   Each bottle's payload is rolled fresh per bottle from the
 *   debuff table (WitchAbilitySupport.DEBUFFS). On every 5th
 *   volley (volleys 5, 10, 15, 20) the second bottle is a
 *   lingering potion instead, which pools into a vanilla
 *   AreaEffectCloud where it breaks. Each volley also sheds 4
 *   EFFECT sparkles in the first bottle's payload color at the
 *   mouth and 3 BUBBLEs of foam at a random point in the disc.
 * - The bottles are real vanilla projectiles owned by the
 *   witch: their splashes and clouds apply the vanilla way to
 *   everyone in range, friend or foe, exactly like a vanilla
 *   witch's own throws (documented, not a bug: do not stand
 *   under your own witch). The lingering clouds are vanilla
 *   entities with vanilla lifetimes; they are fire and forget
 *   and are not tracked or cleaned up.
 * - end(): the tipped cauldron display is discarded. If the
 *   witch dies mid-gather, the framework termination path runs
 *   this same end(), so the cauldron never hangs ownerless.
 *
 * WHAT IT LOOKS LIKE: a three-block-wide cauldron floating
 * over the fight, sparkles gathering under it, then the tilt,
 * the cackle, and a glittering cascade of bottles arcing down
 * with EFFECT sparkles in each payload's color and BUBBLE foam
 * where they break.
 *
 * PERFORMANCE: start() creates one display. tick() returns
 * immediately except every 4th tick; a rain tick builds 2
 * projectiles and sends 7 particles, a gather tick sends 10
 * particles. No entity queries anywhere: the bottles do their
 * own work on impact. Particle budget per activation: gather
 * 4 pulses times 10 = 40, rain 21 volleys times 7 = 147,
 * total at most 187 particles, plus the bottles' own vanilla
 * splash particles.
 *
 * TERRAIN: this ability places and breaks no blocks, so the
 * mobGriefing gate does not apply. The cauldron is a display
 * entity, not a block.
 *
 * State tag keys: "cx", "cy", "cz" (arena center, the
 * target's feet position at cast), "display" (the cauldron
 * display's UUID), "volley" (volleys thrown so far).
 */
public final class CauldronCataclysmAbility implements MobAbility {

    static final int DURATION_TICKS = 140;
    private static final int TIP_TICK = 20;
    private static final int RAIN_END_TICK = 100;
    private static final int VOLLEY_INTERVAL = 4;
    private static final double CAULDRON_HEIGHT = 7.0;
    private static final double MOUTH_HEIGHT = 6.5;
    private static final double RAIN_RADIUS = 5.0;
    private static final float CAULDRON_SCALE = 3.0f;
    // Left rotation for the tip: 125 degrees about the X axis,
    // quaternion (sin(62.5 deg), 0, 0, cos(62.5 deg)).
    private static final float TIP_ROT_X = 0.887f;
    private static final float TIP_ROT_W = 0.462f;

    @Override
    public String id() {
        return "cauldron_cataclysm";
    }

    @Override
    public String displayName() {
        return "Cauldron Cataclysm";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            return;
        }
        CompoundTag state = ctx.state();
        state.putDouble("cx", target.getX());
        state.putDouble("cy", target.getY());
        state.putDouble("cz", target.getZ());
        state.putInt("volley", 0);

        Entity cauldron = WitchAbilitySupport.createBlockDisplay(level,
            Blocks.CAULDRON.defaultBlockState(), CAULDRON_SCALE, 0.0f, 0.0f, 0.0f, 1.0f);
        if (cauldron != null) {
            cauldron.snapTo(target.getX(), target.getY() + CAULDRON_HEIGHT, target.getZ(), 0.0f, 0.0f);
            if (level.addFreshEntity(cauldron)) {
                state.store("display", UUIDUtil.CODEC, cauldron.getUUID());
            }
        }
        // The brew gathers: a low bubbling under the cauldron.
        level.playSound(null, target.getX(), target.getY() + 2.0, target.getZ(),
            SoundEvents.BREWING_STAND_BREW, SoundSource.HOSTILE, 1.0f, 0.7f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);

        if (ticks == TIP_TICK) {
            tipCauldron(ctx, level, cx, cy, cz);
        }
        if (ticks % VOLLEY_INTERVAL != 0) {
            return; // Cheap early-out: gather and rain pulses run every 4 ticks only.
        }
        if (ticks < TIP_TICK) {
            // The gather: sparkles swirl under the cauldron,
            // foam gathers at its rim.
            int roll = WitchAbilitySupport.rollDebuff(ctx);
            level.sendParticles(WitchAbilitySupport.DEBUFF_SPARKLES[roll],
                cx, cy + CAULDRON_HEIGHT - 1.6, cz, 6, 1.2, 0.3, 1.2, 0.05);
            level.sendParticles(ParticleTypes.BUBBLE,
                cx, cy + CAULDRON_HEIGHT + 0.6, cz, 4, 1.1, 0.2, 1.1, 0.02);
            return;
        }
        if (ticks > RAIN_END_TICK) {
            return; // The cauldron is empty; it hangs tipped until end().
        }

        // One rain volley: two bottles from the mouth, payloads
        // rolled fresh per bottle.
        int volley = state.getIntOr("volley", 0) + 1;
        state.putInt("volley", volley);
        Vec3 mouth = new Vec3(cx, cy + MOUTH_HEIGHT, cz);
        int firstRoll = WitchAbilitySupport.rollDebuff(ctx);
        WitchAbilitySupport.throwSplash(ctx, level, mouth, randomRainPoint(ctx, cx, cy, cz),
            WitchAbilitySupport.splashStack(firstRoll), 1.2f, 1.0f);
        int secondRoll = WitchAbilitySupport.rollDebuff(ctx);
        if (volley % 5 == 0) {
            // Every 5th volley pools: a lingering bottle leaves
            // a cloud where it breaks.
            WitchAbilitySupport.throwLingering(ctx, level, mouth, randomRainPoint(ctx, cx, cy, cz),
                WitchAbilitySupport.lingeringStack(secondRoll), 1.2f, 1.0f);
        } else {
            WitchAbilitySupport.throwSplash(ctx, level, mouth, randomRainPoint(ctx, cx, cy, cz),
                WitchAbilitySupport.splashStack(secondRoll), 1.2f, 1.0f);
        }
        level.sendParticles(WitchAbilitySupport.DEBUFF_SPARKLES[firstRoll],
            cx, cy + MOUTH_HEIGHT, cz, 4, 0.8, 0.3, 0.8, 0.05);
        Vec3 foam = randomRainPoint(ctx, cx, cy + 0.4, cz);
        level.sendParticles(ParticleTypes.BUBBLE, foam.x, foam.y, foam.z, 3, 0.5, 0.2, 0.5, 0.02);
    }

    /** Discards the upright cauldron and hangs the tipped one in its place, with the cackle. */
    private void tipCauldron(AbilityContext ctx, ServerLevel level, double cx, double cy, double cz) {
        CompoundTag state = ctx.state();
        discardDisplay(level, state);
        Entity tipped = WitchAbilitySupport.createBlockDisplay(level,
            Blocks.CAULDRON.defaultBlockState(), CAULDRON_SCALE, TIP_ROT_X, 0.0f, 0.0f, TIP_ROT_W);
        if (tipped != null) {
            tipped.snapTo(cx, cy + CAULDRON_HEIGHT, cz, 0.0f, 0.0f);
            if (level.addFreshEntity(tipped)) {
                state.store("display", UUIDUtil.CODEC, tipped.getUUID());
            }
        }
        level.playSound(null, cx, cy + 2.0, cz,
            SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    /** A random point in the rain disc at the given height. */
    private Vec3 randomRainPoint(AbilityContext ctx, double cx, double y, double cz) {
        double angle = ctx.random().nextDouble() * Math.PI * 2.0;
        double distance = Math.sqrt(ctx.random().nextDouble()) * RAIN_RADIUS;
        return new Vec3(cx + Math.cos(angle) * distance, y, cz + Math.sin(angle) * distance);
    }

    /** Discards the tracked cauldron display, if it is still in the world. */
    private void discardDisplay(ServerLevel level, CompoundTag state) {
        UUID id = state.read("display", UUIDUtil.CODEC).orElse(null);
        if (id != null) {
            Entity display = level.getEntity(id);
            if (display != null) {
                display.discard();
            }
        }
        state.remove("display");
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The cauldron vanishes with the spell. The lingering
        // clouds the rain left are vanilla entities and expire
        // on their own clocks; they are not touched here.
        discardDisplay(level, ctx.state());
    }
}
