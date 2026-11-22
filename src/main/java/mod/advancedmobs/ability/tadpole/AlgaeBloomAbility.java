package mod.advancedmobs.ability.tadpole;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Algae Bloom (Tadpole ability 5 of 5, terrain / support).
 *
 * WHAT IT DOES: the tadpole stirs its pond into bloom.
 * Seagrass and kelp sprout through the water around it
 * in green pulses until it is hiding in a little garden
 * of its own making. Anything that pushes into the
 * garden drags against the growth and slows, while the
 * tadpole breathes in the green and regenerates.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the tadpole's current target within 12 blocks
 *   (distance squared 144), AND the tadpole in water: a
 *   garden needs a pond.
 * - Duration: 400 ticks (20 seconds).
 * - The gardener's boon (start(), the design's
 *   regeneration line): REGENERATION I for 420 ticks on
 *   the tadpole, removed exactly in end().
 * - The bloom (growth pulses in start() and then every
 *   40 ticks, at most 10 pulses per activation): each
 *   pulse makes at most 6 placement tries and places at
 *   most 3 new plants, up to a hard cap of 24 plants per
 *   activation (the placed count is kept in the state
 *   tag). TERRAIN RULE (Carter, Nov 21, 2026): the
 *   plants are placed PERMANENTLY and only when
 *   AbilityContext.mobGriefingAllowed() is true at that
 *   pulse; they are never removed, not even in end().
 *   With mobGriefing off, no plants are placed and every
 *   other part of the ability (regeneration, slow zone,
 *   particles) still runs. Each try picks a random point
 *   at a random angle and a distance of 0.5 to 4.0
 *   blocks from the tadpole's live position and scans
 *   its column from 2 above the tadpole's feet down to
 *   3 below (at most 6 cell reads) for the first cell
 *   that passes every placement check:
 *   - the cell's fluid is water (FluidTags.WATER) and
 *     the cell state canBeReplaced() and holds no block
 *     entity, so a container or an unbreakable block is
 *     never overwritten;
 *   - the plant is KELP when the cell directly above is
 *     also water (room to grow a stalk) and SEAGRASS
 *     otherwise (a carpet on the pond floor);
 *   - the plant's own vanilla canSurvive rule passes at
 *     that cell (seagrass needs a sturdy floor or
 *     existing growth beneath it, kelp needs kelp, kelp
 *     plant, or a sturdy floor beneath it; both need to
 *     sit in water), so nothing ever floats or sprouts
 *     somewhere vanilla would pop it off next tick.
 *   A placed plant answers with 3 HAPPY_VILLAGER motes.
 *   A try that finds no legal cell in its column simply
 *   grows nothing.
 * - The tangle (pulse, every 10 ticks): one bounded
 *   query (radius 4.5, 2.5 below and 2.5 above the
 *   tadpole); every enemy in it gains SLOWNESS I
 *   refreshed to 30 ticks (the design's slowness line).
 *   The slow is code-driven from the garden's radius, so
 *   it behaves identically whether or not the plants
 *   themselves were placed.
 * - The murk: 2 UNDERWATER particles (the design's
 *   particle line) drift at a random point within 3
 *   blocks of the tadpole every 4 ticks.
 * - end(): REGENERATION is removed from the tadpole.
 *   The placed plants stay, permanently, per the
 *   terrain rule. Idempotent.
 *
 * WHAT IT LOOKS LIKE: green sparkles popping through the
 * water pulse after pulse as grass and kelp unroll
 * around a tadpole, the pond going murky and thick while
 * everything chasing it starts swimming through syrup.
 *
 * PERFORMANCE: a growth pulse is at most 6 tries times
 * 6 block reads, once every 40 ticks; the tangle is one
 * bounded query every 10 ticks; the murk is 2 particle
 * sends every 4 ticks. No per-tick queries or scans.
 * Particle budget per activation: at most 24 plants
 * times 3 = 72 growth sparkles, at most 100 murk passes
 * times 2 = 200, total at most 272.
 *
 * State tag keys: "placed" (how many plants this
 * activation has grown so far).
 */
public final class AlgaeBloomAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int REGEN_TICKS = 420;
    private static final int GROWTH_INTERVAL = 40;
    private static final int MAX_PLANTS = 24;
    private static final int TRIES_PER_PULSE = 6;
    private static final int PLACES_PER_PULSE = 3;
    private static final double GARDEN_RADIUS = 4.0;
    private static final int SLOW_PULSE_INTERVAL = 10;
    private static final int SLOW_TICKS = 30;

    @Override
    public String id() {
        return "algae_bloom";
    }

    @Override
    public String displayName() {
        return "Algae Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0 && ctx.mob().isInWater();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_TICKS, 0));
        ctx.state().putInt("placed", 0);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            growPulse(ctx, level);
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
        if (ticks % GROWTH_INTERVAL == 0) {
            growPulse(ctx, level);
        }
        if (ticks % SLOW_PULSE_INTERVAL == 0) {
            // The tangle: enemies in the garden drag against the growth.
            List<LivingEntity> enemies = TadpoleAbilitySupport.enemiesAroundSelf(ctx, 4.5, 2.5, 2.5);
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOW_TICKS, 0));
            }
        }
        if (ticks % 4 == 0) {
            // The murk: bloom motes drifting through the garden.
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double dist = ctx.random().nextDouble() * 3.0;
            level.sendParticles(ParticleTypes.UNDERWATER,
                caster.getX() + Math.cos(angle) * dist, caster.getY() + 0.3,
                caster.getZ() + Math.sin(angle) * dist,
                2, 0.2, 0.2, 0.2, 0.02);
        }
    }

    /**
     * One growth pulse (class comment): permanent,
     * gated on mobGriefing, only into legal water cells
     * where the plant's own canSurvive rule passes.
     */
    private void growPulse(AbilityContext ctx, ServerLevel level) {
        if (!ctx.mobGriefingAllowed()) {
            return;
        }
        int placed = ctx.state().getIntOr("placed", 0);
        if (placed >= MAX_PLANTS) {
            return;
        }
        Mob caster = ctx.mob();
        int feetY = (int) Math.floor(caster.getY());
        int grown = 0;
        for (int t = 0; t < TRIES_PER_PULSE && grown < PLACES_PER_PULSE && placed + grown < MAX_PLANTS; t++) {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double dist = 0.5 + ctx.random().nextDouble() * (GARDEN_RADIUS - 0.5);
            int bx = (int) Math.floor(caster.getX() + Math.cos(angle) * dist);
            int bz = (int) Math.floor(caster.getZ() + Math.sin(angle) * dist);
            for (int y = feetY + 2; y >= feetY - 3; y--) {
                BlockPos pos = new BlockPos(bx, y, bz);
                BlockState cell = level.getBlockState(pos);
                if (!cell.getFluidState().is(FluidTags.WATER) || !cell.canBeReplaced()
                    || level.getBlockEntity(pos) != null) {
                    continue;
                }
                boolean roomAbove = level.getFluidState(pos.above()).is(FluidTags.WATER);
                BlockState plant = (roomAbove ? Blocks.KELP : Blocks.SEAGRASS).defaultBlockState();
                if (!plant.canSurvive(level, pos)) {
                    continue;
                }
                level.setBlock(pos, plant, Block.UPDATE_ALL);
                grown++;
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5,
                    pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.25, 0.25, 0.25, 0.03);
                break;
            }
        }
        ctx.state().putInt("placed", placed + grown);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.REGENERATION);
        // The placed plants are permanent (terrain rule)
        // and are deliberately not removed here.
    }
}
