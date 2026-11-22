package mod.advancedmobs.ability.wanderingtrader;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The Wandering Road (Wandering Trader ability 4 of 5,
 * mobility / terrain).
 *
 * WHAT IT DOES: the trader snaps its fingers, portal motes
 * spiral, and it simply is somewhere else: across the fight,
 * next to a freshly kindled campfire with cosy smoke curling
 * up. The little camp is its fallback point for the rest of
 * the activation. The trader fights at range from it, and
 * whenever an enemy closes in and the road calls again, it
 * blinks straight back to the fire.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader's current target within 18 blocks (distance
 *   squared 324).
 * - The camp pick (start()): the anchor is 12 blocks from
 *   the trader directly away from the target (falling back
 *   to the reverse of the trader's facing with no target).
 *   Candidate anchors are tried in order: the straight point,
 *   then lateral offsets +3 and -3 blocks, then distance 9
 *   straight; each candidate's ground height comes from one
 *   bounded surface scan, and the first whose cell fits the
 *   trader's bounding box (Level.noCollision) wins. If none
 *   fits, the trader stays put and the camp is its own feet.
 *   The anchor is stored in the state tag (camp_x/y/z) and
 *   never moves for the rest of the activation (the design's
 *   "position memory in code").
 * - The blink (start()): 20 PORTAL particles spiral at the
 *   old position with WANDERING_TRADER_DISAPPEARED, the
 *   trader teleports (the design's "/teleport in code") to
 *   the anchor, and 20 REVERSE_PORTAL particles with
 *   WANDERING_TRADER_REAPPEARED mark the arrival.
 * - The campfire (start(), the design's "/setblock campfire
 *   in code"): one lit campfire (Blocks.CAMPFIRE default
 *   state) is placed at the anchor's feet cell, or the first
 *   of its 4 cardinal neighbor cells that qualifies, under
 *   the standing terrain rule: PERMANENTLY, only when
 *   AbilityContext.mobGriefingAllowed() is true, only into
 *   a cell that canBeReplaced() with no block entity and
 *   solid ground directly below (never overwriting a
 *   container or unbreakable block, never floating). With
 *   mobGriefing off, no campfire is placed and everything
 *   else still runs. The campfire is never removed, not in
 *   end(), not ever: it stays in the world as a real block
 *   (terrain rule). Whether one was placed is kept in the
 *   state tag ("camp_lit") along with its position
 *   (fire_x/y/z), which the smoke below reads.
 * - The smoke (tick(), every 4 ticks): when a campfire was
 *   placed, 1 CAMPFIRE_COSY_SMOKE particle curls up from
 *   0.6 blocks above it (the design's campfire_cosy_smoke
 *   line, as particles over the real block).
 * - The camp rest (tick(), every 20 ticks): while the
 *   trader is within 6.0 blocks of the anchor, it gains
 *   REGENERATION I refreshed to 40 ticks: fighting from
 *   camp keeps it patched up.
 * - The retreat (tick(), every 5 ticks): when the trader's
 *   current target is within 3.0 blocks of the trader, the
 *   trader is more than 4.0 blocks from the anchor, and at
 *   least 100 ticks have passed since the last retreat, the
 *   road calls again: 10 PORTAL plus WANDERING_TRADER_
 *   DISAPPEARED at the old position, a teleport back to the
 *   anchor, and 10 REVERSE_PORTAL plus WANDERING_TRADER_
 *   REAPPEARED at the fire. The last-retreat tick is kept
 *   in the state tag, initialized 100 ticks in the past so
 *   the first call can come early.
 * - Duration: 600 ticks (30 seconds), hard finish. end()
 *   undoes nothing: the campfire is permanent terrain, the
 *   regeneration rides its own short clock, and there are
 *   no modifiers to strip.
 *
 * WHAT IT LOOKS LIKE: a snap, a spiral of purple motes, and
 * the trader is gone, reappearing across the arena in a
 * reverse swirl beside a little lit campfire, smoke curling
 * up. It trades insults and merchandise from the firelight,
 * and every time something gets in its face, pop, it is back
 * at the camp again.
 *
 * PERFORMANCE: start() is at most 4 surface scans of at
 *   most 8 block reads, 4 collision checks, 5 campfire cell
 *   checks, and 40 particles. tick() is one smoke send every
 *   4 ticks, one distance check every 5 ticks, and one
 *   distance check every 20 ticks; a retreat adds 20
 *   particles and one teleport, at most 5 times per
 *   activation under the 100-tick spacing. No entity
 *   queries anywhere. Particle budget per activation: 40 at
 *   start, at most 150 of smoke, at most 5 times 20 = 100
 *   of retreats, total at most 290 particles.
 *
 * TERRAIN: the campfire is this package's only block
 * change, and it follows the standing rule exactly (see
 * the campfire note above): permanent, gated on
 * mobGriefingAllowed(), never overwriting a container or
 * unbreakable block, never floating, never removed.
 *
 * State tag keys: "camp_x", "camp_y", "camp_z" (the anchor),
 * "camp_lit" (whether the campfire was placed), "fire_x",
 * "fire_y", "fire_z" (the campfire's position), "last_retreat"
 * (the tick of the last retreat blink).
 */
public final class TheWanderingRoadAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final double CAMP_DISTANCE = 12.0;
    private static final double RETREAT_TARGET_RANGE = 3.0;
    private static final double RETREAT_MIN_CAMP_DISTANCE = 4.0;
    private static final int RETREAT_SPACING_TICKS = 100;
    private static final double CAMP_REST_RANGE = 6.0;

    @Override
    public String id() {
        return "the_wandering_road";
    }

    @Override
    public String displayName() {
        return "The Wandering Road";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("last_retreat", -RETREAT_SPACING_TICKS);
        state.putBoolean("camp_lit", false);
        if (level == null) {
            state.putDouble("camp_x", caster.getX());
            state.putDouble("camp_y", caster.getY());
            state.putDouble("camp_z", caster.getZ());
            return;
        }

        // The direction away from the fight.
        LivingEntity target = ctx.target();
        double dirX;
        double dirZ;
        if (target != null) {
            dirX = caster.getX() - target.getX();
            dirZ = caster.getZ() - target.getZ();
        } else {
            dirX = -caster.getLookAngle().x;
            dirZ = -caster.getLookAngle().z;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            dirX = 1.0;
            dirZ = 0.0;
        } else {
            dirX /= length;
            dirZ /= length;
        }
        double latX = -dirZ;
        double latZ = dirX;

        // Candidate anchors, in order (see the class comment).
        double[][] candidates = {
            {CAMP_DISTANCE, 0.0},
            {CAMP_DISTANCE, 3.0},
            {CAMP_DISTANCE, -3.0},
            {9.0, 0.0}
        };
        double anchorX = caster.getX();
        double anchorY = caster.getY();
        double anchorZ = caster.getZ();
        double halfWidth = caster.getBbWidth() / 2.0;
        for (double[] candidate : candidates) {
            double candidateX = caster.getX() + dirX * candidate[0] + latX * candidate[1];
            double candidateZ = caster.getZ() + dirZ * candidate[0] + latZ * candidate[1];
            int groundY = WanderingTraderAbilitySupport.findGroundY(level, candidateX, caster.getY(), candidateZ);
            AABB box = new AABB(candidateX - halfWidth, groundY, candidateZ - halfWidth,
                candidateX + halfWidth, groundY + caster.getBbHeight(), candidateZ + halfWidth);
            if (level.noCollision(caster, box)) {
                anchorX = candidateX;
                anchorY = groundY;
                anchorZ = candidateZ;
                break;
            }
        }
        state.putDouble("camp_x", anchorX);
        state.putDouble("camp_y", anchorY);
        state.putDouble("camp_z", anchorZ);

        // The blink to the camp.
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            20, 0.4, 0.6, 0.4, 0.3);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.WANDERING_TRADER_DISAPPEARED, SoundSource.HOSTILE, 1.0f, 1.0f);
        caster.snapTo(anchorX, anchorY, anchorZ, caster.getYRot(), 0.0f);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, anchorX, anchorY + 1.0, anchorZ,
            20, 0.4, 0.6, 0.4, 0.3);
        level.playSound(null, anchorX, anchorY, anchorZ,
            SoundEvents.WANDERING_TRADER_REAPPEARED, SoundSource.HOSTILE, 1.0f, 1.0f);

        placeCampfire(ctx, level, anchorX, anchorY, anchorZ);
    }

    /**
     * Places the camp's lit campfire under the standing
     * terrain rule (see the class comment): at the anchor's
     * feet cell, or the first qualifying cardinal neighbor.
     */
    private void placeCampfire(AbilityContext ctx, ServerLevel level, double anchorX, double anchorY, double anchorZ) {
        if (!ctx.mobGriefingAllowed()) {
            return; // Terrain rule: with mobGriefing off, no block changes at all.
        }
        BlockPos feet = BlockPos.containing(anchorX, anchorY, anchorZ);
        BlockPos[] cells = {feet, feet.north(), feet.south(), feet.east(), feet.west()};
        BlockState campfire = Blocks.CAMPFIRE.defaultBlockState();
        for (BlockPos cell : cells) {
            BlockState existing = level.getBlockState(cell);
            BlockState below = level.getBlockState(cell.below());
            if (!existing.canBeReplaced() || level.getBlockEntity(cell) != null || below.isAir()) {
                continue;
            }
            level.setBlock(cell, campfire, Block.UPDATE_ALL);
            CompoundTag state = ctx.state();
            state.putBoolean("camp_lit", true);
            state.putDouble("fire_x", cell.getX() + 0.5);
            state.putDouble("fire_y", cell.getY());
            state.putDouble("fire_z", cell.getZ() + 0.5);
            return;
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        // The cosy smoke over the real campfire.
        if (ticks % 4 == 0 && state.getBooleanOr("camp_lit", false)) {
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                state.getDoubleOr("fire_x", caster.getX()),
                state.getDoubleOr("fire_y", caster.getY()) + 0.6,
                state.getDoubleOr("fire_z", caster.getZ()),
                1, 0.1, 0.1, 0.1, 0.02);
        }

        double campX = state.getDoubleOr("camp_x", caster.getX());
        double campY = state.getDoubleOr("camp_y", caster.getY());
        double campZ = state.getDoubleOr("camp_z", caster.getZ());
        double campDistanceSqr = caster.distanceToSqr(campX, campY, campZ);

        // The camp rest: fighting from the firelight patches the trader up.
        if (ticks % 20 == 0 && campDistanceSqr <= CAMP_REST_RANGE * CAMP_REST_RANGE) {
            caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0));
        }

        // The retreat: the road calls when something gets in its face.
        if (ticks % 5 == 0
            && ticks - state.getIntOr("last_retreat", -RETREAT_SPACING_TICKS) >= RETREAT_SPACING_TICKS
            && campDistanceSqr > RETREAT_MIN_CAMP_DISTANCE * RETREAT_MIN_CAMP_DISTANCE) {
            LivingEntity target = ctx.target();
            if (target != null && caster.distanceToSqr(target) <= RETREAT_TARGET_RANGE * RETREAT_TARGET_RANGE) {
                state.putInt("last_retreat", ticks);
                level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                    10, 0.4, 0.6, 0.4, 0.3);
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.WANDERING_TRADER_DISAPPEARED, SoundSource.HOSTILE, 1.0f, 1.0f);
                caster.snapTo(campX, campY, campZ, caster.getYRot(), 0.0f);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, campX, campY + 1.0, campZ,
                    10, 0.4, 0.6, 0.4, 0.3);
                level.playSound(null, campX, campY, campZ,
                    SoundEvents.WANDERING_TRADER_REAPPEARED, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the campfire is permanent terrain
        // under the standing rule, and the regeneration rides
        // its own short clock.
    }
}
