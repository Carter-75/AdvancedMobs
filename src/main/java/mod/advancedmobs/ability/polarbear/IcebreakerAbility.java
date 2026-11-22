package mod.advancedmobs.ability.polarbear;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Icebreaker (Polar Bear ability 4 of 5, terrain).
 *
 * WHAT IT DOES: the bear rears up on its hind legs and
 * brings its whole weight down on the shore. Open water
 * around it freezes into a sheet of real ice the bear and
 * its allies can cross, while enemies standing on existing
 * ice have it shattered out from under them and are dunked
 * into the water below, chilled to the bone.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bear on the ground and its current target within 12
 *   blocks (distance squared 144).
 * - The rear-up (start()): the center is recorded in the
 *   state tag ("cx", "cy", "cz", the bear's block position)
 *   and, when the caster is a real PolarBear, it is set
 *   standing (the vanilla rear-up pose) for the wind-up;
 *   POLAR_BEAR_WARNING plays at volume 1.5, pitch 0.8.
 *   Navigation is stopped each tick until the slam (never
 *   NoAi).
 * - The slam (tick 10, once, "slammed" in the state tag):
 *   GLASS_BREAK at volume 1.5, pitch 0.7, 24 BLOCK particles
 *   of ice and 12 SPLASH at the center, and the terrain
 *   pass (mobGriefing mode only): a Chebyshev radius 6
 *   (13 by 13 columns) around the center, scanning each
 *   column from 2 above the center's feet level down to 4
 *   below, first surface cell wins, at most 80 block
 *   changes total:
 *     FREEZE: a water cell with air above it becomes ICE.
 *     SHATTER: an ice cell (Blocks.ICE only, never packed
 *       or blue ice) with water directly below it becomes
 *       WATER, dropping whatever stood on it into the
 *       drink.
 *   Both changes go through the shared safety rule: the
 *   exact cells named above only, never a block entity,
 *   never an unbreakable block (the freeze target is water
 *   and the shatter target is plain ice, so the safety
 *   check is the PolarBearAbilitySupport.isDiggable rule
 *   applied to the shatter target; water has no block
 *   entity and is replaced, not broken).
 * - The dunk (same tick, one bounded query, radius 6.5
 *   around the center, 3 below to 3 above): every enemy
 *   (PolarBearAbilitySupport rules) standing on ice or
 *   water (the block below its feet is ICE or WATER) or
 *   already in water gains SLOWNESS I (amplifier 0) for
 *   100 ticks and a splash of 6 SPLASH particles. In
 *   mobGriefing mode the shattered columns are water by
 *   the time this runs, so their riders qualify through
 *   the water branch; in mobGriefing-off mode the ice
 *   never breaks, and its riders qualify through the ice
 *   branch instead: the dunk lands either way, only the
 *   terrain differs.
 * - Duration: the ability finishes at tick 30 (the slam at
 *   tick 10, then 20 ticks of settling spray: 2 SPLASH
 *   every 5 ticks at the center). end() sets the bear back
 *   down (standing false) if it is a real PolarBear.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility
 * contract): the freeze and the shatter are PERMANENT and
 * gated by the vanilla mobGriefing game rule, checked
 * through AbilityContext.mobGriefingAllowed() at the slam:
 * - mobGriefing ON: the water freezes and the ice shatters
 *   for good. end() touches no blocks; the new shoreline is
 *   ordinary terrain afterwards.
 * - mobGriefing OFF: no blocks are changed at all. The
 *   slam, the sounds, the particles, and the dunk all run
 *   exactly the same; only the shoreline stays as it was.
 *
 * WHAT IT LOOKS LIKE: a rearing bear, a crack like a frozen
 * lake giving way, ice shards and spray bursting off the
 * shore, and enemies floundering in suddenly open water.
 *
 * PERFORMANCE: the wind-up is one navigation stop per
 * tick; the slam is one pass of at most 169 column scans
 * of at most 7 block reads (capped at 80 changes) plus one
 * bounded entity query; the settle ticks emit 2 particles
 * every 5 ticks. Particle budget per activation: 36 at the
 * slam plus at most 6 per dunked enemy (bounded by the
 * crowd in radius 6.5) plus 8 settle = at most 44 plus the
 * dunk splashes.
 *
 * State tag keys: "cx", "cy", "cz" (the slam center, the
 * bear's block position at start), "slammed" (1 once the
 * slam has fired).
 */
public final class IcebreakerAbility implements MobAbility {

    static final int DURATION_TICKS = 30;
    private static final int SLAM_TICK = 10;
    private static final int SLAM_RADIUS = 6;
    private static final int MAX_CHANGES = 80;

    @Override
    public String id() {
        return "icebreaker";
    }

    @Override
    public String displayName() {
        return "Icebreaker";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        BlockPos center = caster.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.putInt("slammed", 0);
        if (caster instanceof PolarBear bear) {
            bear.setStanding(true);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 1.5f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (ticks < SLAM_TICK) {
            caster.getNavigation().stop();
            return;
        }
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        if (state.getIntOr("slammed", 0) == 0) {
            state.putInt("slammed", 1);
            slam(ctx, level);
            return;
        }
        // Settling spray over the worked shoreline.
        if (ticks % 5 == 0) {
            double cx = state.getIntOr("cx", 0) + 0.5;
            double cy = state.getIntOr("cy", 0);
            double cz = state.getIntOr("cz", 0) + 0.5;
            level.sendParticles(ParticleTypes.SPLASH, cx, cy + 0.4, cz, 2, 2.5, 0.3, 2.5, 0.05);
        }
    }

    /** The slam: sounds, burst, the terrain pass, and the dunk. See the class comment. */
    private void slam(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        int cx = state.getIntOr("cx", 0);
        int cy = state.getIntOr("cy", 0);
        int cz = state.getIntOr("cz", 0);
        double centerX = cx + 0.5;
        double centerZ = cz + 0.5;

        level.playSound(null, centerX, cy, centerZ,
            SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5f, 0.7f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
            centerX, cy + 0.5, centerZ, 24, 2.5, 0.5, 2.5, 0.2);
        level.sendParticles(ParticleTypes.SPLASH, centerX, cy + 0.5, centerZ,
            12, 2.5, 0.5, 2.5, 0.15);

        // Terrain (mobGriefing mode only): freeze open water,
        // shatter thin ice over water. See the TERRAIN RULE
        // in the class comment.
        if (ctx.mobGriefingAllowed()) {
            workShoreline(level, cx, cy, cz);
        }

        // The dunk: enemies standing on ice or water, or in
        // water, are chilled where they flounder.
        List<LivingEntity> victims = PolarBearAbilitySupport.enemiesInBox(
            ctx, centerX, cy, centerZ, 6.5, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            BlockState below = level.getBlockState(victim.blockPosition().below());
            if (victim.isInWater() || below.is(Blocks.ICE) || below.is(Blocks.WATER)) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 0));
                level.sendParticles(ParticleTypes.SPLASH,
                    victim.getX(), victim.getY() + 0.5, victim.getZ(),
                    6, 0.4, 0.3, 0.4, 0.1);
            }
        }
    }

    /**
     * The terrain pass (see the class comment for the exact
     * rules): per column, the first surface cell from 2 above
     * to 4 below the center's feet level; at most MAX_CHANGES
     * blocks change.
     */
    private static void workShoreline(ServerLevel level, int cx, int cy, int cz) {
        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        int changes = 0;
        for (int dx = -SLAM_RADIUS; dx <= SLAM_RADIUS && changes < MAX_CHANGES; dx++) {
            for (int dz = -SLAM_RADIUS; dz <= SLAM_RADIUS && changes < MAX_CHANGES; dz++) {
                for (int y = cy + 2; y >= cy - 4; y--) {
                    BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.WATER)) {
                        // Freeze: open water with air above
                        // becomes a sheet of real ice.
                        if (level.getBlockState(pos.above()).isAir()) {
                            level.setBlock(pos, ice, Block.UPDATE_ALL);
                            changes++;
                        }
                        break;
                    }
                    if (state.is(Blocks.ICE)) {
                        // Shatter: plain ice with water below
                        // drops its rider into the drink. The
                        // shared safety check applies (plain
                        // ice passes it; a block entity or an
                        // unbreakable lookalike never would).
                        if (level.getBlockState(pos.below()).is(Blocks.WATER)
                            && PolarBearAbilitySupport.isDiggable(level, pos)) {
                            level.setBlock(pos, water, Block.UPDATE_ALL);
                            changes++;
                        }
                        break;
                    }
                    if (state.isAir()) {
                        continue;
                    }
                    break;
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
        // The bear comes back down. The shoreline, when
        // worked, is permanent terrain and is deliberately
        // NOT touched here (terrain rule, see the class
        // comment).
        if (ctx.mob() instanceof PolarBear bear) {
            bear.setStanding(false);
        }
    }
}
