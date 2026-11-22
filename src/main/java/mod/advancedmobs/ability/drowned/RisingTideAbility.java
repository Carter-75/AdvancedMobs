package mod.advancedmobs.ability.drowned;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * Rising Tide (Drowned ability 2 of 5, terrain).
 *
 * WHAT IT DOES: water wells up out of the ground around the
 * drowned's target in a spreading sheet, flooding a shallow arena
 * bowl. In its own tide the drowned is completely at home, swimming
 * and moving at full pace with the grace of a dolphin and no need to
 * breathe, while every land mob caught in the flood flounders at
 * reduced speed.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two modes:
 * - mobGriefing ON: the water is placed and STAYS. There is no
 *   restore: end() touches no blocks, and the flood only goes where
 *   vanilla water flow takes it afterwards. The debuff zone still
 *   lasts only the ability duration; the water itself is just
 *   terrain afterwards.
 * - mobGriefing OFF: no blocks are changed at all. The ability runs
 *   as a pure zone effect for its duration (same pulses, same self
 *   buffs, same visuals, no water), and the world is left exactly
 *   as it was. In this mode the zone is an effect area, not
 *   terrain, so the gate has nothing to block beyond the water
 *   placement itself.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 10 blocks (distance
 *   squared 100), and standing on the ground.
 * - Duration: exactly 240 ticks (12 seconds) from start(). This is
 *   the zone's lifetime; in mobGriefing mode the water outlives it.
 * - The flood (mobGriefing mode only), centered on the target's
 *   block position at start(), one layer of water source blocks at
 *   the target's feet level over a 7 by 7 area (Chebyshev radius
 *   3): a position is flooded when the block there can be replaced
 *   (air, grass, and other replaceable blocks), has no block entity,
 *   holds no fluid already, and is supported from below by either a
 *   face-sturdy block or by water, so the sheet never floats in
 *   mid-air. At most 49 blocks change. Nothing that is replaced
 *   drops as an item, and containers, unbreakable blocks, and
 *   existing fluids are never touched. From there the water flows
 *   by ordinary vanilla rules.
 * - Debuff pulse every 10 ticks (24 pulses): enemies
 *   (DrownedAbilitySupport rules) within horizontal radius 3.6 of
 *   the tide center and between 1.5 below and 2 above its feet
 *   level get SLOWNESS I (amplifier 0) for 15 ticks, refreshed by
 *   the next pulse: land mobs floundering in the flood.
 * - The drowned itself, for the duration: DOLPHINS_GRACE and
 *   WATER_BREATHING, amplifier 0, 240 ticks each, applied in
 *   start() and removed in end(), plus a WATER_MOVEMENT_EFFICIENCY
 *   add_value modifier of +1.0 (modifier id
 *   advancedmobs:rising_tide_efficiency, removed in end()), so it
 *   swims and wades through its own tide at full land speed. (A
 *   drowned that somehow had DOLPHINS_GRACE or WATER_BREATHING from
 *   another source would lose it at end(), the accepted edge case
 *   shared with every fixed-duration effect in this system.)
 *
 * WHAT IT LOOKS LIKE: at start, 24 BUBBLE_COLUMN_UP streams rise
 * over the flooded area with 10 loose BUBBLE particles among them,
 * as if the water is boiling up out of the ground. Every 20 ticks
 * during the zone, 6 more BUBBLE_COLUMN_UP particles stream up at
 * random spots inside the disc. Victims show the vanilla SLOWNESS
 * effect sparkles while they flounder.
 *
 * PERFORMANCE: block work happens once in start() (at most 49
 * position checks, mobGriefing mode only) and never again. tick()
 * returns immediately except every 10th tick (debuff pulse: one
 * bounded entity query over a 7.2 by 3.5 by 7.2 box) and every 20th
 * tick (6 particles at pseudo-random disc positions computed from
 * the context random, no allocations beyond the query result).
 * Particle budget per activation: 34 at start plus at most 12 zone
 * pulses times 6 = 72, total at most 106 particles.
 *
 * State tag keys: "cx", "cy", "cz" (tide center block pos, the feet
 * level of the target at start). No block list is stored: placed
 * water is permanent and never revisited.
 */
public final class RisingTideAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 10;
    private static final int FLOOD_RADIUS = 3;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rising_tide_efficiency");

    @Override
    public String id() {
        return "rising_tide";
    }

    @Override
    public String displayName() {
        return "Rising Tide";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && target.onGround() && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            return;
        }
        Mob mob = ctx.mob();
        BlockPos center = target.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());

        // Terrain (mobGriefing mode only): the permanent flood. With
        // mobGriefing off, no block is touched and the ability is a
        // zone effect only. See the TERRAIN RULE in the class comment.
        if (ctx.mobGriefingAllowed()) {
            flood(level, center);
        }

        // The drowned is at home in its own tide.
        mob.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, DURATION_TICKS, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, DURATION_TICKS, 0));
        AttributeInstance efficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The water boils up out of the ground.
        double cx = center.getX() + 0.5, cy = center.getY(), cz = center.getZ() + 0.5;
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, cx, cy + 0.3, cz, 24, 2.8, 0.4, 2.8, 0.15);
        level.sendParticles(ParticleTypes.BUBBLE, cx, cy + 0.5, cz, 10, 2.8, 0.5, 2.8, 0.1);
    }

    /**
     * Places the permanent water sheet (see the class comment for
     * the shape and the safety checks). The water is placed for
     * good: nothing records it for removal, and vanilla flow takes
     * it from there.
     */
    private static void flood(ServerLevel level, BlockPos center) {
        for (int dx = -FLOOD_RADIUS; dx <= FLOOD_RADIUS; dx++) {
            for (int dz = -FLOOD_RADIUS; dz <= FLOOD_RADIUS; dz++) {
                BlockPos pos = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                BlockState state = level.getBlockState(pos);
                if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                    continue;
                }
                if (level.getBlockEntity(pos) != null) {
                    continue;
                }
                BlockPos belowPos = pos.below();
                BlockState below = level.getBlockState(belowPos);
                boolean supported = below.isFaceSturdy(level, belowPos, Direction.UP)
                    || below.getFluidState().getType() == Fluids.WATER;
                if (supported) {
                    level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean pulse = ticks % PULSE_INTERVAL == 0;
        boolean bubbles = ticks % 20 == 0;
        if (!pulse && !bubbles) {
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
            // Debuff pulse: land mobs in the flood flounder. This
            // runs in both terrain modes; the zone exists whether or
            // not the water was allowed to rise.
            List<LivingEntity> victims = DrownedAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 3.6, 1.5, 2.0);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 0));
            }
        }
        if (bubbles) {
            // Bubble streams rising through the tide at random spots
            // in the disc.
            for (int i = 0; i < 6; i++) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                double distance = ctx.random().nextDouble() * 3.0;
                level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP,
                    cx + Math.cos(angle) * distance, cy + 0.2, cz + Math.sin(angle) * distance,
                    1, 0.0, 0.0, 0.0, 0.05);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The drowned's own buffs end with the tide. The water, when
        // placed, is permanent terrain and is deliberately NOT
        // touched here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
        mob.removeEffect(MobEffects.DOLPHINS_GRACE);
        mob.removeEffect(MobEffects.WATER_BREATHING);
    }
}
