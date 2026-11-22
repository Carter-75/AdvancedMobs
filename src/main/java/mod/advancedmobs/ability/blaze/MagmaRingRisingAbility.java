package mod.advancedmobs.ability.blaze;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Magma Ring Rising (Blaze ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the blaze slams the fight with a ring of
 * netherrack and magma blocks that erupts out of the ground
 * in a circle around the midpoint between it and its target,
 * sealing the enemies inside the arena with it. The ring is
 * two blocks tall, and every third column is magma block, so
 * anything that climbs over the wall or lingers on it burns
 * (vanilla magma block contact damage does that hurting
 * itself). DRIPPING_LAVA sheets off the fresh ring while it
 * settles.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 12 blocks
 *   (distance squared 144).
 * - The ring (start()): the center is the block midpoint
 *   between the blaze and its target, at the target's block
 *   height. The columns are every (dx, dz) offset whose
 *   Euclidean distance from the center is at least 4.5 and
 *   under 5.5 blocks: a radius 5 circle, about 32 columns.
 *   The columns are sorted by angle around the center in
 *   start(), so the eruption below sweeps around the circle
 *   in order.
 * - The eruption (tick()): three sector passes at ticks 1, 8,
 *   and 16, each covering one third of the sorted columns.
 *   Every column in the sector plays its eruption particles at
 *   its surface (2 BLOCK_CRUMBLE with a magma block state and
 *   1 DRIPPING_LAVA, at the ground the shared scan finds, or
 *   the center height when the column has no ground), and a
 *   GENERIC_EXPLODE (volume 0.35, pitch 0.8) and a LAVA_POP
 *   (volume 0.7, pitch 0.9) sound at the center for the pass.
 *   When the terrain rule allows block changes, the column is
 *   also built (see the TERRAIN RULE): its ground block
 *   converts to MAGMA_BLOCK when the column index is a
 *   multiple of 3 and to NETHERACK otherwise, and a second
 *   block is placed on top of the ground (MAGMA_BLOCK when
 *   the index mod 3 is 1, NETHERACK otherwise), so the wall
 *   is two blocks tall with magma teeth staggered through
 *   both layers. At most 64 placements per activation.
 * - The settling (tick(), every 20 ticks from tick 20 to the
 *   end): 3 ring columns are picked at random and drip 2
 *   DRIPPING_LAVA each from just above their surface. With
 *   the terrain rule on, the picks come from the columns that
 *   were actually built; with it off, from the full column
 *   list (the drips are particles only, not block changes).
 * - Duration: exactly 100 ticks (5 seconds) from start().
 *   The ring itself does NOT end with the ability: it is
 *   permanent (see the TERRAIN RULE).
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the design text for this
 * ability had the ring crumble back to debris after half a
 * minute, but the standing terrain rule supersedes it: every
 * terrain change an ability makes is PERMANENT and there is
 * no restore machinery, so the ring stays, exactly like Lava
 * Bloom's lake. The ring's block changes are gated by
 * AbilityContext.mobGriefingAllowed(), checked at every
 * sector pass:
 * - mobGriefing ON: the ground converts and the wall layer is
 *   placed as above and STAYS. Conversion safety comes from
 *   the shared checks: never a container or other block
 *   entity, never an unbreakable block, never a fluid or a
 *   block with fluid above it, never a non-solid (the scan
 *   passes plants and stops at real ground; surface growth
 *   above converted ground pops off on the block update,
 *   which is the ring cracking through, by design). The wall
 *   layer additionally requires a replaceable, dry spot.
 * - mobGriefing OFF: no block changes at all. The eruption
 *   still plays: the sector passes run their particles and
 *   sounds at the same columns and the drips still fall. Only
 *   the wall is missing.
 *
 * WHAT IT LOOKS LIKE: the ground bursting open in three
 * sweeping arcs over less than a second, BLOCK_CRUMBLE debris
 * of magma block spraying at every column as a two block wall
 * of dark netherrack studded with glowing magma teeth jumps
 * out of the earth, then lava dripping off the fresh ring
 * while it settles.
 *
 * PERFORMANCE: all block work happens on 3 sector ticks: at
 * most about 32 columns times a 4-read ground scan plus the
 * capped 64 placements. The column list is computed once in
 * start(). Drip pulses run every 20 ticks over a stored list.
 * No entity queries at all (the magma blocks do the hurting,
 * in the vanilla block tick). Particle budget per activation:
 * eruption at most 32 columns times 3 = 96, drips 4 pulses
 * times 6 = 24, total at most 120 particles.
 *
 * CLEANUP: none. The ability applies no modifiers and spawns
 * no entities, and the ring is permanent terrain, so end()
 * deliberately does nothing.
 *
 * State tag keys: "cx", "cy", "cz" (the ring center), "cols"
 * (list of compounds, one per ring column in angle order:
 * "x", "z" block columns), "built" (list of compounds, one
 * per column actually built: "x", "y", "z", the ground
 * position), "placed" (placements so far, against the cap).
 */
public final class MagmaRingRisingAbility implements MobAbility {

    static final int DURATION_TICKS = 100;
    private static final int MAX_PLACED = 64;
    private static final BlockParticleOption MAGMA_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.MAGMA_BLOCK.defaultBlockState());

    @Override
    public String id() {
        return "magma_ring_rising";
    }

    @Override
    public String displayName() {
        return "Magma Ring Rising";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        int cx = (ctx.mob().getBlockX() + (target != null ? target.getBlockX() : ctx.mob().getBlockX())) / 2;
        int cz = (ctx.mob().getBlockZ() + (target != null ? target.getBlockZ() : ctx.mob().getBlockZ())) / 2;
        int cy = target != null ? target.getBlockY() : ctx.mob().getBlockY();
        state.putInt("cx", cx);
        state.putInt("cy", cy);
        state.putInt("cz", cz);
        state.putInt("placed", 0);
        state.put("built", new ListTag());

        // The ring columns, sorted by angle so the eruption
        // sweeps around the circle in order.
        List<int[]> columns = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance >= 4.5 && distance < 5.5) {
                    columns.add(new int[]{cx + dx, cz + dz});
                }
            }
        }
        columns.sort(Comparator.comparingDouble(c -> Math.atan2(c[1] - cz, c[0] - cx)));
        ListTag cols = new ListTag();
        for (int[] column : columns) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", column[0]);
            entry.putInt("z", column[1]);
            cols.add(entry);
        }
        state.put("cols", cols);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks == 1 || ticks == 8 || ticks == 16) {
            eruptSector(ctx, level, ticks == 1 ? 0 : ticks == 8 ? 1 : 2);
        } else if (ticks >= 20 && ticks % 20 == 0) {
            drip(ctx, level);
        }
    }

    /**
     * One eruption pass: a third of the ring's columns burst
     * out of the ground, particles always, blocks only when
     * the terrain rule allows (see the TERRAIN RULE in the
     * class comment).
     */
    private static void eruptSector(AbilityContext ctx, ServerLevel level, int sector) {
        CompoundTag state = ctx.state();
        int cy = state.getIntOr("cy", ctx.mob().getBlockY());
        ListTag cols = state.getListOrEmpty("cols");
        int from = sector * cols.size() / 3;
        int to = (sector + 1) * cols.size() / 3;
        boolean mayChangeBlocks = ctx.mobGriefingAllowed();
        for (int i = from; i < to; i++) {
            CompoundTag entry = cols.getCompoundOrEmpty(i);
            int x = entry.getIntOr("x", 0);
            int z = entry.getIntOr("z", 0);
            BlockPos ground = BlazeAbilitySupport.findGround(level, x, z, cy);
            int surfaceY = ground != null ? ground.getY() + 1 : cy;
            level.sendParticles(MAGMA_CRUMBLE, x + 0.5, surfaceY + 0.3, z + 0.5,
                2, 0.35, 0.25, 0.35, 0.05);
            level.sendParticles(ParticleTypes.DRIPPING_LAVA, x + 0.5, surfaceY + 0.4, z + 0.5,
                1, 0.3, 0.2, 0.3, 0.0);
            if (!mayChangeBlocks || ground == null) {
                continue;
            }
            buildColumn(ctx, level, i, ground);
        }
        int cx = state.getIntOr("cx", ctx.mob().getBlockX());
        int cz = state.getIntOr("cz", ctx.mob().getBlockZ());
        level.playSound(null, cx + 0.5, cy + 1.0, cz + 0.5, SoundEvents.GENERIC_EXPLODE,
            SoundSource.HOSTILE, 0.35f, 0.8f);
        level.playSound(null, cx + 0.5, cy + 1.0, cz + 0.5, SoundEvents.LAVA_POP,
            SoundSource.HOSTILE, 0.7f, 0.9f);
    }

    /**
     * Builds one ring column: converts its ground block and
     * places the wall block on top of it (see the class
     * comment for the magma tooth pattern), bounded by the
     * placement cap, and records the column as built for the
     * settling drips.
     */
    private static void buildColumn(AbilityContext ctx, ServerLevel level, int index, BlockPos ground) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("placed", 0) >= MAX_PLACED) {
            return;
        }
        level.setBlock(ground,
            index % 3 == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState(),
            Block.UPDATE_ALL);
        state.putInt("placed", state.getIntOr("placed", 0) + 1);
        CompoundTag built = new CompoundTag();
        built.putInt("x", ground.getX());
        built.putInt("y", ground.getY());
        built.putInt("z", ground.getZ());
        ListTag builtList = state.getListOrEmpty("built");
        builtList.add(built);
        state.put("built", builtList);

        BlockPos above = ground.above();
        if (state.getIntOr("placed", 0) < MAX_PLACED && BlazeAbilitySupport.canPlaceWallBlock(level, above)) {
            level.setBlock(above,
                index % 3 == 1 ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState(),
                Block.UPDATE_ALL);
            state.putInt("placed", state.getIntOr("placed", 0) + 1);
        }
    }

    /** The settling drips: DRIPPING_LAVA off 3 random ring columns. */
    private static void drip(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        ListTag built = state.getListOrEmpty("built");
        ListTag cols = state.getListOrEmpty("cols");
        int cy = state.getIntOr("cy", ctx.mob().getBlockY());
        for (int pick = 0; pick < 3; pick++) {
            double x;
            double y;
            double z;
            if (!built.isEmpty()) {
                CompoundTag entry = built.getCompoundOrEmpty(ctx.random().nextInt(built.size()));
                x = entry.getIntOr("x", 0) + 0.5;
                y = entry.getIntOr("y", cy) + 1.1;
                z = entry.getIntOr("z", 0) + 0.5;
            } else if (!cols.isEmpty()) {
                CompoundTag entry = cols.getCompoundOrEmpty(ctx.random().nextInt(cols.size()));
                int bx = entry.getIntOr("x", 0);
                int bz = entry.getIntOr("z", 0);
                BlockPos ground = BlazeAbilitySupport.findGround(level, bx, bz, cy);
                x = bx + 0.5;
                y = (ground != null ? ground.getY() + 1 : cy) + 0.4;
                z = bz + 0.5;
            } else {
                return;
            }
            level.sendParticles(ParticleTypes.DRIPPING_LAVA, x, y, z, 2, 0.3, 0.2, 0.3, 0.0);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: no modifiers, no spawned
        // entities, and the ring is permanent terrain by the
        // standing terrain rule.
    }
}
