package mod.advancedmobs.ability.drowned;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Kelp Snare (Drowned ability 4 of 5, defense).
 *
 * WHAT IT DOES: when an enemy presses in close, the drowned lashes
 * out with living kelp and real cobwebs snap into place around the
 * attacker's feet, rooting it in place under crushing slowness and
 * weakness while the drowned backs off and lines up its trident.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two modes:
 * - mobGriefing ON: the cobwebs are placed and STAY. There is no
 *   restore: end() touches no blocks, and a snared enemy (or anyone
 *   else) has to break or burn its own way out afterwards. The
 *   debuff zone still lasts only the ability duration; the webs
 *   themselves are just terrain afterwards.
 * - mobGriefing OFF: no blocks are changed at all. The ability runs
 *   as a pure debuff zone for its duration (same pulses, same
 *   visuals, no webs), and the world is left exactly as it was. In
 *   this mode the zone is an effect area, not terrain, so the gate
 *   has nothing to block beyond the web placement itself.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 6 blocks
 *   (distance squared 36): this is the pressed-in-close defense, it
 *   only fires when the attacker is right on top of the drowned.
 * - Duration: exactly 100 ticks (5 seconds) from start(). This is
 *   the debuff zone's lifetime; in mobGriefing mode the cobwebs
 *   outlive it.
 * - The webs (mobGriefing mode only), centered on the target's
 *   block position at start(): cobweb blocks at the target's feet,
 *   at the block above its feet (the two blocks its body occupies),
 *   and at the four horizontal neighbors of its feet, at most 6
 *   blocks. A position is webbed only when the block there can be
 *   replaced (air, grass, and other replaceable blocks), has no
 *   block entity, and holds no fluid: a target standing in water
 *   gets no webs, only the zone below. Nothing replaced drops as an
 *   item. The webs root by ordinary vanilla cobweb rules (movement
 *   through a cobweb is cut to a crawl) on top of the zone effects.
 * - Debuff pulse every 10 ticks (10 pulses): enemies
 *   (DrownedAbilitySupport rules) within horizontal radius 2.4 of
 *   the snare center and between 1 below and 2 above its feet level
 *   get SLOWNESS IV (amplifier 3) and WEAKNESS I (amplifier 0) for
 *   15 ticks each, refreshed by the next pulse: rooted and feeble
 *   while tangled, in both terrain modes.
 *
 * WHAT IT LOOKS LIKE: at start, 16 ITEM_COBWEB flecks burst around
 * the target as the webs snap into place, with 8 DRIPPING_WATER
 * particles dripping off the fresh kelp. Every 20 ticks during the
 * zone, 4 more DRIPPING_WATER particles drip at the snare center.
 * Victims show the vanilla SLOWNESS and WEAKNESS effect sparkles
 * while tangled.
 *
 * PERFORMANCE: block work happens once in start() (at most 6
 * position checks, mobGriefing mode only) and never again. tick()
 * returns immediately except every 10th tick (debuff pulse: one
 * bounded entity query over a 4.8 by 3 by 4.8 box) and every 20th
 * tick (4 particles). ITEM_COBWEB is a simple particle type in
 * 26.2 (verified against the jar), so the hot path allocates
 * nothing beyond the pulse query result. Particle budget per
 * activation: 24 at start plus
 * at most 5 zone pulses times 4 = 20, total at most 44 particles.
 *
 * State tag keys: "cx", "cy", "cz" (snare center block pos, the
 * feet level of the target at start). No block list is stored:
 * placed cobwebs are permanent and never revisited.
 */
public final class KelpSnareAbility implements MobAbility {

    static final int DURATION_TICKS = 100;
    private static final int PULSE_INTERVAL = 10;
    private static final int[][] WEB_OFFSETS = {
        {0, 0, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override
    public String id() {
        return "kelp_snare";
    }

    @Override
    public String displayName() {
        return "Kelp Snare";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 36.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            return;
        }
        BlockPos center = target.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());

        // Terrain (mobGriefing mode only): the permanent cobwebs.
        // With mobGriefing off, no block is touched and the ability
        // is a debuff zone only. See the TERRAIN RULE in the class
        // comment.
        if (ctx.mobGriefingAllowed()) {
            placeWebs(level, center);
        }

        // The webs snap into place, dripping wet.
        double cx = center.getX() + 0.5, cy = center.getY(), cz = center.getZ() + 0.5;
        level.sendParticles(ParticleTypes.ITEM_COBWEB, cx, cy + 0.8, cz, 16, 0.6, 0.7, 0.6, 0.12);
        level.sendParticles(ParticleTypes.DRIPPING_WATER, cx, cy + 1.4, cz, 8, 0.6, 0.4, 0.6, 0.05);
    }

    /**
     * Places the permanent cobwebs around the target's feet (see
     * the class comment for the shape and the safety checks). The
     * webs are placed for good: nothing records them for removal.
     */
    private static void placeWebs(ServerLevel level, BlockPos center) {
        for (int[] offset : WEB_OFFSETS) {
            BlockPos pos = new BlockPos(center.getX() + offset[0], center.getY() + offset[1], center.getZ() + offset[2]);
            BlockState state = level.getBlockState(pos);
            if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockEntity(pos) != null) {
                continue;
            }
            level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean pulse = ticks % PULSE_INTERVAL == 0;
        boolean drips = ticks % 20 == 0;
        if (!pulse && !drips) {
            return; // Cheap early-out: work runs on the 10 and 20 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;

        if (pulse) {
            // Debuff pulse: whatever is tangled in the snare is
            // rooted and feeble. This runs in both terrain modes;
            // the zone exists whether or not the webs were allowed
            // to snap into place.
            List<LivingEntity> victims = DrownedAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 2.4, 1.0, 2.0);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 3));
                victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 15, 0));
            }
        }
        if (drips) {
            level.sendParticles(ParticleTypes.DRIPPING_WATER, cx, cy + 1.4, cz, 4, 0.8, 0.4, 0.8, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the pulse effects lapse on their own once
        // the pulses stop, and the cobwebs, when placed, are
        // permanent terrain and are deliberately NOT touched here
        // (terrain rule, see the class comment).
    }
}
