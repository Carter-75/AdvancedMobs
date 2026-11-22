package mod.advancedmobs.ability.tropicalfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Coral Crown Garden (Tropical Fish ability 2 of 5,
 * terrain).
 *
 * WHAT IT DOES: bright coral sprouts in a defensive
 * ring around the fish: real coral blocks set into
 * the seafloor, with coral fan pieces tilted at
 * jaunty angles above them. The ring is real
 * terrain while it stands: it blocks enemy movement
 * and line of sight, and anything that pushes into
 * the coral gets pricked by the sharp branches,
 * small piercing damage on a steady pulse. The fans
 * are display pieces and vanish when the ability
 * ends; the coral blocks themselves are permanent.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the fish's current target within 12
 *   blocks (distance squared 144), plus the fish in
 *   water (a coral garden grows on a seafloor).
 * - Duration: 400 ticks (20 seconds). The garden's
 *   center is fixed at the fish's start position
 *   and stored in the state tag; the pulses run
 *   from that recorded center even if the fish
 *   swims off.
 * - The planting (start(), the design's setblock
 *   ring in code): 8 ring points at radius 2.2
 *   around the center, one every 45 degrees. The
 *   coral cycles through the five living corals by
 *   point index (tube, brain, bubble, fire, horn,
 *   then tube again). Each point's column is
 *   scanned from 2 above the center's height down
 *   to 4 below (at most 7 cell reads) for the first
 *   legal cell. TERRAIN RULE (Carter, Nov 21,
 *   2026): the coral blocks are PERMANENT and are
 *   placed only when
 *   AbilityContext.mobGriefingAllowed() is true at
 *   planting time; they are never removed, not even
 *   in end(). A legal cell must pass every check:
 *   - the cell state canBeReplaced() and holds no
 *     block entity, so a container, an unbreakable
 *     block, or any other block entity is never
 *     overwritten;
 *   - the cell is water (its fluid state is in the
 *     water tag): coral only grows submerged, and a
 *     dry cell would kill it on vanilla's own tick;
 *   - the block directly beneath is solid ground:
 *     not air, holding no fluid, and not itself
 *     replaceable, so the coral is set INTO the
 *     seafloor and never floats;
 *   - the coral's own vanilla canSurvive rule
 *     passes there.
 *   A point that finds no legal cell plants nothing.
 *   Planted positions are recorded in the state
 *   tag. TROPICAL_FISH_FLOP plays at planting
 *   (volume 1.0, pitch 0.9, a pleased wriggle). With
 *   mobGriefing off, no coral is placed and every
 *   pulse below still runs from the recorded ring
 *   geometry (the Seed Vault precedent: the zone is
 *   code-driven and behaves the same with or
 *   without the physical coral).
 * - The fans (also start(), only over planted
 *   points): one block_display per planted point,
 *   showing the matching coral FAN state at scale
 *   1.1, hovering 0.85 above the coral block's cell,
 *   tilted 0.3 radians (alternating sign) about the
 *   X axis on even points and the Z axis on odd
 *   points, yaw facing outward: the design's
 *   block_display coral pieces at jaunty angles.
 *   Displays are pure visuals, not block changes:
 *   they are not gated, and end() discards every
 *   one of them.
 * - The pierce (pulse, every 20 ticks): one bounded
 *   query (radius 3.2 around the center, 2.0 below
 *   and 2.0 above): every enemy (the package enemy
 *   rule) inside takes 2.0 points of piercing
 *   damage through the package's cactus typed
 *   source credited to the fish (the design's typed
 *   damage line: sharp coral branches, cactus being
 *   vanilla's own pricking type), plus 3 BLOCK
 *   particles of the first planted coral's live
 *   block state read at that moment (coral debris;
 *   skipped when nothing was planted). Twice a
 *   second of standing in the ring is 2 health a
 *   second: small, steady, and entirely avoidable
 *   by staying out of the garden.
 * - The shimmer (every 10 ticks): 2 BLOCK particles
 *   of a planted coral's live state at that point,
 *   cycling through the planted points in order, so
 *   the ring glitters with drifting coral dust.
 *   Skipped when nothing was planted.
 * - end(): discards the fan displays and nothing
 *   else. The coral blocks are permanent terrain
 *   (see above). Idempotent.
 *
 * WHAT IT LOOKS LIKE: the seafloor erupts in a
 * crown of pink brain coral, blue tube coral, and
 * golden horn coral around the little fish, fans
 * leaning every which way above the blocks, coral
 * dust drifting off the branches, and anything that
 * shoulders through the ring coming out scratched
 * and bleeding.
 *
 * PERFORMANCE: planting is at most 8 points times
 * 7 cell reads plus at most 8 display spawns, once,
 * in start(). The pierce pulse is one bounded query
 * every 20 ticks plus one block read; the shimmer
 * is 2 particles every 10 ticks. No other per-tick
 * work. Particle budget per activation: pierce 20
 * pulses times 3 = 60, shimmer 40 times 2 = 80,
 * total at most 140.
 *
 * State tag keys: "cx", "cy", "cz" (the garden
 * center), "planted" (coral blocks successfully
 * planted), "ring" (list of compounds: "x", "y",
 * "z" per planted coral cell, block coordinates),
 * "fans" (list of compounds: "id", one per fan
 * display).
 */
public final class CoralCrownGardenAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int RING_POINTS = 8;
    private static final double RING_RADIUS = 2.2;
    private static final double PIERCE_RADIUS = 3.2;
    private static final int PIERCE_INTERVAL = 20;
    private static final float PIERCE_DAMAGE = 2.0f;
    private static final int SHIMMER_INTERVAL = 10;

    /** The five living coral blocks, cycled by ring point index. */
    private static final Block[] CORAL_BLOCKS = {
        Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK,
        Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK
    };

    /** The five living coral fans, matching CORAL_BLOCKS, for the display pieces. */
    private static final Block[] CORAL_FANS = {
        Blocks.TUBE_CORAL_FAN, Blocks.BRAIN_CORAL_FAN, Blocks.BUBBLE_CORAL_FAN,
        Blocks.FIRE_CORAL_FAN, Blocks.HORN_CORAL_FAN
    };

    @Override
    public String id() {
        return "coral_crown_garden";
    }

    @Override
    public String displayName() {
        return "Coral Crown Garden";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());
        state.putInt("planted", 0);
        state.put("ring", new ListTag());
        state.put("fans", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ctx.mobGriefingAllowed()) {
            plantRing(ctx, level);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.TROPICAL_FISH_FLOP, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    /** Plants the coral ring and its fan displays (class comment): permanent, gated, legal cells only. */
    private void plantRing(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);
        int centerY = (int) Math.floor(cy);
        ListTag ring = state.getListOrEmpty("ring");
        int planted = 0;
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = i * Math.PI / 4.0;
            int bx = (int) Math.floor(cx + Math.cos(angle) * RING_RADIUS);
            int bz = (int) Math.floor(cz + Math.sin(angle) * RING_RADIUS);
            BlockState coral = CORAL_BLOCKS[i % CORAL_BLOCKS.length].defaultBlockState();
            for (int y = centerY + 2; y >= centerY - 4; y--) {
                BlockPos pos = new BlockPos(bx, y, bz);
                BlockState cell = level.getBlockState(pos);
                if (!cell.canBeReplaced() || level.getBlockEntity(pos) != null) {
                    continue;
                }
                if (!cell.getFluidState().is(FluidTags.WATER)) {
                    continue;
                }
                BlockState below = level.getBlockState(pos.below());
                if (below.isAir() || !below.getFluidState().isEmpty() || below.canBeReplaced()) {
                    continue;
                }
                if (!coral.canSurvive(level, pos)) {
                    continue;
                }
                level.setBlock(pos, coral, Block.UPDATE_ALL);
                CompoundTag spot = new CompoundTag();
                spot.putInt("x", pos.getX());
                spot.putInt("y", pos.getY());
                spot.putInt("z", pos.getZ());
                ring.add(spot);
                planted++;
                spawnFan(ctx, level, pos, i, angle);
                break;
            }
        }
        state.put("ring", ring);
        state.putInt("planted", planted);
    }

    /** Spawns the tilted fan display piece over one planted coral block (class comment). */
    private void spawnFan(AbilityContext ctx, ServerLevel level, BlockPos pos, int index, double angle) {
        BlockState fan = CORAL_FANS[index % CORAL_FANS.length].defaultBlockState();
        double tilt = (index % 2 == 0 ? 0.3 : -0.3);
        Entity display = TropicalFishAbilitySupport.createBlockDisplay(level, fan, 1.1f, tilt, index % 2 == 1);
        if (display == null) {
            return;
        }
        display.snapTo(pos.getX() + 0.5, pos.getY() + 0.85, pos.getZ() + 0.5,
            (float) Math.toDegrees(angle), 0.0f);
        if (level.addFreshEntity(display)) {
            TropicalFishAbilitySupport.trackEntity(ctx.state(), "fans", display);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());
        ListTag ring = state.getListOrEmpty("ring");

        // The shimmer: coral dust drifting off one planted point at a time.
        if (ticks % SHIMMER_INTERVAL == 0 && !ring.isEmpty()) {
            CompoundTag spot = ring.getCompoundOrEmpty((ticks / SHIMMER_INTERVAL) % ring.size());
            BlockPos pos = new BlockPos(spot.getIntOr("x", 0), spot.getIntOr("y", 0), spot.getIntOr("z", 0));
            BlockState coral = level.getBlockState(pos);
            if (!coral.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, coral),
                    pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 2, 0.3, 0.2, 0.3, 0.02);
            }
        }

        // The pierce: sharp branches prick everything hostile inside the ring.
        if (ticks % PIERCE_INTERVAL == 0) {
            List<LivingEntity> enemies = TropicalFishAbilitySupport.enemiesInBox(ctx, cx, cy, cz,
                PIERCE_RADIUS, 2.0, 2.0);
            for (LivingEntity enemy : enemies) {
                enemy.hurtServer(level, TropicalFishAbilitySupport.cactusSource(ctx), PIERCE_DAMAGE);
            }
            if (!enemies.isEmpty() && !ring.isEmpty()) {
                CompoundTag spot = ring.getCompoundOrEmpty(0);
                BlockPos pos = new BlockPos(spot.getIntOr("x", 0), spot.getIntOr("y", 0), spot.getIntOr("z", 0));
                BlockState coral = level.getBlockState(pos);
                if (!coral.isAir()) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, coral),
                        cx, cy + 0.6, cz, 3, 1.2, 0.5, 1.2, 0.03);
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The fan displays are pure visuals and are discarded; the
        // coral blocks are permanent terrain (standing terrain rule)
        // and are deliberately not removed here.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag fans = ctx.state().getListOrEmpty("fans");
        for (int i = 0; i < fans.size(); i++) {
            Entity found = TropicalFishAbilitySupport.resolveTracked(level, fans.getCompoundOrEmpty(i));
            if (found != null) {
                found.discard();
            }
        }
    }
}
