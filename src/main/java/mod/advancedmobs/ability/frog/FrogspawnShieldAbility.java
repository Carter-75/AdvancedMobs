package mod.advancedmobs.ability.frog;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Frogspawn Shield (Frog ability 4 of 5, defense / terrain).
 *
 * WHAT IT DOES: the frog lays a glistening mat of
 * frogspawn blocks in a ring around itself and settles
 * into the middle with fresh absorption. Enemies pushing
 * through the spawn mat are slowed by the jelly, and the
 * frog regenerates while it sits in its nursery.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the frog's current target within 10 blocks
 *   (distance squared 100).
 * - Duration: 300 ticks. The frog settles into the middle
 *   of the ring and is planted by per-tick navigation
 *   stops (never NoAi).
 * - The nursery buffs (start(), the design's absorption
 *   and regeneration lines): ABSORPTION II (8 absorption
 *   points) and REGENERATION I, each for 320 ticks on the
 *   frog, both removed exactly in end().
 * - The ring (start(), the design's /setblock frogspawn
 *   ring line): 8 ring points at radius 2.0 around the
 *   frog, one every 45 degrees. TERRAIN RULE (Carter,
 *   Nov 21, 2026): the frogspawn is placed PERMANENTLY
 *   and only when AbilityContext.mobGriefingAllowed() is
 *   true; it is never removed, not even in end(). With
 *   mobGriefing off, no blocks are placed and every other
 *   part of the ability (buffs, slow zone, bubbles) still
 *   runs. Placement safety, per point: the column is
 *   scanned from 1 above the frog's feet down to 3 below
 *   (at most 5 fluid reads) for the first cell whose
 *   fluid is in FluidTags.SUPPORTS_FROGSPAWN, because
 *   vanilla frogspawn can only survive on a supporting
 *   fluid surface (verified against the jar:
 *   FrogspawnBlock.canSurvive delegates to mayPlaceOn,
 *   which requires that fluid tag below); the frogspawn
 *   goes into the cell directly above that fluid when
 *   that cell canBeReplaced() and holds no block entity,
 *   so a container or an unbreakable block is never
 *   overwritten. On dry ground a point simply places
 *   nothing: a frog far from water gets the buffs and the
 *   slow zone but no mat.
 * - The jelly slow (pulse, every 10 ticks): one bounded
 *   query (radius 3.4, 2 below and 2 above the frog);
 *   every enemy whose horizontal distance from the frog
 *   is between 1.2 and 3.4 blocks (the ring band) gains
 *   SLOWNESS I refreshed to 30 ticks (the design's
 *   slowness on crossing). The slow is code-driven from
 *   the ring geometry, so it behaves identically whether
 *   or not the blocks themselves were placed.
 * - The look: FROG_LAY_SPAWN plays in start() at volume
 *   1.0, pitch 1.0, and 2 BUBBLE particles (the design's
 *   bubble accents) rise at a random ring point every 4
 *   ticks.
 * - end(): the two buffs are removed from the frog. The
 *   placed frogspawn stays, permanently, per the terrain
 *   rule. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a frog squatting in a glistening
 * ring of egg jelly on the water's edge, bubbles rising
 * off the mat, while anything that wades into the ring
 * starts dragging its feet.
 *
 * PERFORMANCE: the ring is at most 8 points times 5
 * block reads, once, in start(); the pulse is one bounded
 * query every 10 ticks; the per-tick work is one
 * navigation stop and 2 particle sends every 4 ticks.
 * Particle budget per activation: at most 150 bubbles.
 *
 * State tag keys: "placed" (how many frogspawn blocks
 * the ring actually laid; diagnostics only).
 */
public final class FrogspawnShieldAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BUFF_TICKS = 320;
    private static final int PULSE_INTERVAL = 10;
    private static final double RING_RADIUS = 2.0;
    private static final double BAND_INNER = 1.2;
    private static final double BAND_OUTER = 3.4;
    private static final int SLOW_TICKS = 30;

    @Override
    public String id() {
        return "frogspawn_shield";
    }

    @Override
    public String displayName() {
        return "Frogspawn Shield";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, BUFF_TICKS, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, BUFF_TICKS, 0));
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FROG_LAY_SPAWN, SoundSource.HOSTILE, 1.0f, 1.0f);
        layRing(ctx, level);
    }

    /**
     * Lays the frogspawn ring (class comment): permanent,
     * gated on mobGriefing, only onto supporting fluid
     * surfaces, never overwriting a block entity.
     */
    private void layRing(AbilityContext ctx, ServerLevel level) {
        if (!ctx.mobGriefingAllowed()) {
            ctx.state().putInt("placed", 0);
            return;
        }
        Mob caster = ctx.mob();
        int feetY = (int) Math.floor(caster.getY());
        int placed = 0;
        for (int k = 0; k < 8; k++) {
            double angle = k * Math.PI / 4.0;
            int bx = (int) Math.floor(caster.getX() + Math.cos(angle) * RING_RADIUS);
            int bz = (int) Math.floor(caster.getZ() + Math.sin(angle) * RING_RADIUS);
            for (int y = feetY + 1; y >= feetY - 3; y--) {
                BlockPos fluidPos = new BlockPos(bx, y, bz);
                if (!level.getFluidState(fluidPos).is(FluidTags.SUPPORTS_FROGSPAWN)) {
                    continue;
                }
                BlockPos cell = fluidPos.above();
                BlockState cellState = level.getBlockState(cell);
                if (cellState.canBeReplaced() && level.getBlockEntity(cell) == null) {
                    level.setBlock(cell, Blocks.FROGSPAWN.defaultBlockState(), Block.UPDATE_ALL);
                    placed++;
                }
                break;
            }
        }
        ctx.state().putInt("placed", placed);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        caster.getNavigation().stop();
        if (ticks % 4 == 0) {
            // Bubble accents rising off the mat.
            RandomSource random = ctx.random();
            double angle = random.nextDouble() * Math.PI * 2.0;
            level.sendParticles(ParticleTypes.BUBBLE,
                caster.getX() + Math.cos(angle) * RING_RADIUS, caster.getY() + 0.2,
                caster.getZ() + Math.sin(angle) * RING_RADIUS,
                2, 0.15, 0.1, 0.15, 0.02);
        }
        if (ticks % PULSE_INTERVAL == 0) {
            // The jelly slow: enemies inside the ring band drag.
            List<LivingEntity> enemies = FrogAbilitySupport.enemiesAroundSelf(ctx, BAND_OUTER, 2.0, 2.0);
            for (LivingEntity enemy : enemies) {
                double dx = enemy.getX() - caster.getX();
                double dz = enemy.getZ() - caster.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal >= BAND_INNER && horizontal <= BAND_OUTER) {
                    enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOW_TICKS, 0));
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
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.ABSORPTION);
        caster.removeEffect(MobEffects.REGENERATION);
        // The placed frogspawn is permanent (terrain rule)
        // and is deliberately not removed here.
    }
}
