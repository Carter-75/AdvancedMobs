package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * War Stomp Fissure (Piglin Brute ability 3 of 5, terrain).
 *
 * WHAT IT DOES: the brute stomps, and a line of blackstone
 * spikes erupts out of the ground in front of it in sequence,
 * one after another like a row of falling dominoes of stone.
 * Each spike launches any enemy standing on its spot straight
 * up as it rises, and the finished line stands as a wall of
 * cover across the fight. In the Nether, each spike also
 * ignites a patch of fire on the ground beside it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the brute's current target within 9 blocks (distance
 *   squared 81) and the brute on the ground.
 * - The line (fixed in start()): origin at the brute's feet,
 *   direction the horizontal unit vector toward the target's
 *   position at that moment. 7 spike columns at 1.3 block
 *   spacing, the first 1.3 blocks ahead of the brute, the
 *   last 9.1 blocks ahead. The line does not track the
 *   target after start().
 * - Eruption schedule: column i erupts at tick 3 + 3 times i
 *   (ticks 3, 6, 9, 12, 15, 18, 21). Column heights in spikes
 *   are 1, 2, 3, 2, 3, 2, 1: a wall that peaks mid-line. The
 *   activation finishes at tick 30, a beat after the last
 *   spike stands.
 * - Each eruption, in order:
 *   1. The column's ground is found by scanning the column
 *      from 2 above the brute's feet down to 5 below for the
 *      top block with a collision shape and no fluid: that
 *      block's top is the surface. If no ground is found in
 *      the window, the column places nothing and its launch
 *      is read at the brute's own feet height instead.
 *   2. The launch: enemies (PiglinBruteAbilitySupport rules)
 *      within 1.2 blocks horizontally of the column center
 *      and between 1 below and 3 above the surface take 3.0
 *      points of typed mob-attack damage credited to the
 *      brute and are launched: vertical velocity set to 0.9,
 *      horizontal velocity kept, hurtMarked set so it syncs.
 *      An enemy standing between two columns can be launched
 *      by both eruptions; each spike is its own eruption.
 *   3. The spike (only when mobGriefing is on, re-checked at
 *      every eruption in case the rule is toggled mid-line):
 *      blackstone blocks are set into the cells above the
 *      surface, one per height step, stopping the column at
 *      the first cell that is not replaceable, holds fluid,
 *      or holds a block entity. Spikes only ever fill empty
 *      or replaceable space over solid ground: they never
 *      overwrite a block, never touch a container, and never
 *      float. Enemies on the spot are launched BEFORE the
 *      spike is placed, so nothing is entombed standing.
 *   4. The Nether fire (only in the Nether dimension, and
 *      only when mobGriefing is on): one fire is attempted on
 *      the ground beside the spike, on the perpendicular side
 *      chosen by the column's parity (even columns left, odd
 *      columns right), through the shared placeFire safety
 *      check (dry, replaceable, sturdy block beneath, no
 *      block entity; soul fire on soul soil, normal fire
 *      elsewhere, as vanilla picks).
 *   5. The look: 6 DUST_PILLAR and 4 BLOCK_CRUMBLE particles
 *      of blackstone burst at the column, and a BASALT_BREAK
 *      (volume 0.7, pitch rising 0.9 plus 0.05 per column)
 *      cracks as the stone tears out of the ground.
 *
 * TERRAIN RULE: the design text says the spikes crumble away
 * after a few seconds. Carter's standing terrain rule
 * (Nov 21, 2026) supersedes that: ability terrain is
 * PERMANENT and there is no restore machinery, exactly per
 * the Gravemud Mire, Magma Ring Rising, and Graveyard Bloom
 * precedents. With mobGriefing on, the spike wall and the
 * Nether fire patches stay in the world for good. With
 * mobGriefing off, no block changes at all: no spikes, no
 * fire, and the launches, damage, particles, and sounds of
 * every eruption still run in full.
 *
 * PERFORMANCE: start() does arithmetic only. Each eruption
 * tick is at most 8 block reads of ground scan, one bounded
 * entity query, at most 3 block writes, and one fire attempt;
 * the other 23 ticks return immediately (the modulo and
 * column checks are the only cost). Particle budget per
 * activation: 7 columns times 10 = 70, plus the 8-particle
 * stomp burst at start, total at most 78.
 *
 * State tag keys: "dir_x", "dir_z" (the line direction),
 * "origin_x", "origin_z" (the brute's position at start, so
 * the line stays where the stomp fell even if the brute
 * moves during the 30 ticks), "base_y" (the brute's feet
 * block Y at start, the ground scan's reference height).
 */
public final class WarStompFissureAbility implements MobAbility {

    static final int DURATION_TICKS = 30;
    private static final int COLUMN_COUNT = 7;
    private static final double COLUMN_SPACING = 1.3;
    private static final int FIRST_ERUPTION_TICK = 3;
    private static final int ERUPTION_INTERVAL = 3;
    private static final int[] COLUMN_HEIGHTS = {1, 2, 3, 2, 3, 2, 1};
    private static final float LAUNCH_DAMAGE = 3.0f;
    private static final double LAUNCH_UP = 0.9;
    private static final BlockParticleOption BLACKSTONE_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.BLACKSTONE.defaultBlockState());
    private static final BlockParticleOption BLACKSTONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.BLACKSTONE.defaultBlockState());

    @Override
    public String id() {
        return "war_stomp_fissure";
    }

    @Override
    public String displayName() {
        return "War Stomp Fissure";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 81.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        double dx = target != null ? target.getX() - mob.getX() : mob.getLookAngle().x;
        double dz = target != null ? target.getZ() - mob.getZ() : mob.getLookAngle().z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            dx = 1.0;
            length = 1.0;
        }
        ctx.state().putDouble("dir_x", dx / length);
        ctx.state().putDouble("dir_z", dz / length);
        ctx.state().putDouble("origin_x", mob.getX());
        ctx.state().putDouble("origin_z", mob.getZ());
        ctx.state().putInt("base_y", mob.blockPosition().getY());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The stomp itself: a roar and the first crack of stone dust.
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_BRUTE_ANGRY,
                SoundSource.HOSTILE, 1.2f, 0.8f);
            level.sendParticles(BLACKSTONE_CRUMBLE, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                8, 1.0, 0.2, 1.0, 0.15);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        int sinceFirst = ticks - FIRST_ERUPTION_TICK;
        if (sinceFirst < 0 || sinceFirst % ERUPTION_INTERVAL != 0) {
            return; // Cheap early-out: only eruption ticks do work.
        }
        int column = sinceFirst / ERUPTION_INTERVAL;
        if (column >= COLUMN_COUNT) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            eruptColumn(ctx, level, column);
        }
    }

    /** One spike of the line: find the ground, launch whoever stands there, raise the stone. */
    private void eruptColumn(AbilityContext ctx, ServerLevel level, int column) {
        Mob mob = ctx.mob();
        double dirX = ctx.state().getDoubleOr("dir_x", 1.0);
        double dirZ = ctx.state().getDoubleOr("dir_z", 0.0);
        int baseY = ctx.state().getIntOr("base_y", mob.blockPosition().getY());
        double distance = COLUMN_SPACING * (column + 1);
        double cx = ctx.state().getDoubleOr("origin_x", mob.getX()) + dirX * distance;
        double cz = ctx.state().getDoubleOr("origin_z", mob.getZ()) + dirZ * distance;
        int bx = (int) Math.floor(cx);
        int bz = (int) Math.floor(cz);

        // The surface: the top solid, dry block in the window.
        int groundY = Integer.MIN_VALUE;
        for (int y = baseY + 2; y >= baseY - 5; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()) {
                groundY = y;
                break;
            }
        }
        double surfaceY = groundY != Integer.MIN_VALUE ? groundY + 1.0 : baseY;

        // The launch comes first: nothing gets entombed by its own spike.
        List<LivingEntity> victims = PiglinBruteAbilitySupport.enemiesInBox(ctx, cx, surfaceY, cz, 1.2, 1.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), LAUNCH_DAMAGE);
            net.minecraft.world.phys.Vec3 current = victim.getDeltaMovement();
            victim.setDeltaMovement(current.x, LAUNCH_UP, current.z);
            victim.hurtMarked = true;
        }

        boolean griefing = ctx.mobGriefingAllowed();
        if (griefing && groundY != Integer.MIN_VALUE) {
            // The spike: blackstone into replaceable cells only,
            // stopping at the first cell that refuses.
            int height = COLUMN_HEIGHTS[column];
            for (int h = 1; h <= height; h++) {
                BlockPos pos = new BlockPos(bx, groundY + h, bz);
                BlockState current = level.getBlockState(pos);
                if (!current.canBeReplaced() || !current.getFluidState().isEmpty()
                    || level.getBlockEntity(pos) != null) {
                    break;
                }
                level.setBlock(pos, Blocks.BLACKSTONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            // The Nether fire patch beside the spike.
            if (level.dimension() == Level.NETHER) {
                double side = column % 2 == 0 ? 1.0 : -1.0;
                int fx = (int) Math.floor(cx + dirZ * side);
                int fz = (int) Math.floor(cz - dirX * side);
                PiglinBruteAbilitySupport.placeFire(level, new BlockPos(fx, groundY + 1, fz));
            }
        }

        level.sendParticles(BLACKSTONE_PILLAR, cx, surfaceY + 0.2, cz, 6, 0.4, 0.5, 0.4, 0.10);
        level.sendParticles(BLACKSTONE_CRUMBLE, cx, surfaceY + 0.2, cz, 4, 0.5, 0.4, 0.5, 0.15);
        level.playSound(null, cx, surfaceY, cz, SoundEvents.BASALT_BREAK,
            SoundSource.HOSTILE, 0.7f, 0.9f + 0.05f * column);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: the spikes and fire are permanent
        // terrain by the standing rule, and nothing else was made.
    }
}
