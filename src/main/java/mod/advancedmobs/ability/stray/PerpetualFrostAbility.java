package mod.advancedmobs.ability.stray;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Perpetual Frost (Stray ability 2 of 5, terrain).
 *
 * WHAT IT DOES: a sheet of packed ice spreads under the stray as
 * real blocks, SNOWFLAKE drifting down across a rink of its own
 * making. The stray's ground friction drops to nearly nothing and
 * it glides like a skater, faster than it walks. Enemies standing
 * on the rink get the same slippery footing forced on them, so
 * they slide past their turns and struggle to line up hits.
 *
 * FRICTION SEMANTICS (verified against the real 26.2 jar,
 * LivingEntity.computeModifiedFriction): effective ground
 * friction = clamp(1 - (1 - blockFriction) * FRICTION_MODIFIER
 * value, 0, 1), and ground velocity is multiplied by that result
 * each tick. The attribute's default value is 1 (block friction
 * unchanged); a value near 0 pushes effective friction toward 1,
 * meaning velocity is almost fully retained: frictionless glide.
 * The -0.92 add_value modifiers below take the attribute to 0.08,
 * so on plain ground (friction 0.6) the effective friction is
 * 0.968, slicker than vanilla ice (0.98 block friction gives a
 * 0.98 result at modifier 1), and on the packed ice rink itself
 * it is 0.9984: a true skater's glide.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 14 blocks
 *   (distance squared 196) and the stray is on the ground.
 * - Duration: exactly 300 ticks (15 seconds) from start(). This
 *   is the lifetime of the glide and slip modifiers; in
 *   mobGriefing mode the rink itself outlives it (see below).
 * - The rink (mobGriefing mode only), centered on the stray's
 *   block position at start(), a 7 by 7 area (Chebyshev radius
 *   3): the floor block (one below the stray's feet level) in
 *   each column is converted in place to packed ice when the
 *   block is convertible and the block under it is face-sturdy
 *   upward, so nothing ever floats. At most 49 blocks change.
 *   Convertible means: not air, no fluid, no block entity
 *   (containers, signs, spawners and so on are never touched),
 *   and destroy speed not negative (no bedrock or barriers).
 *   Nothing converted drops as an item: the block is replaced,
 *   not broken. Columns already made of packed ice are skipped.
 * - The stray's skating edge, for the duration: a
 *   FRICTION_MODIFIER add_value modifier of -0.92 (modifier id
 *   advancedmobs:perpetual_frost_glide) and a MOVEMENT_SPEED
 *   add_multiplied_base modifier of +0.25 (modifier id
 *   advancedmobs:perpetual_frost_skater, 25 percent faster).
 *   Both are removed in end(). The glide applies wherever the
 *   stray goes for the duration, not only on the rink: the edge
 *   is on its feet, not on the ice.
 * - Slip pulse every 10 ticks (30 pulses): enemies
 *   (StrayAbilitySupport rules) within horizontal radius 3.6 of
 *   the rink center and between 2.5 below and 2 above its feet
 *   level carry a FRICTION_MODIFIER add_value modifier of -0.92
 *   (modifier id advancedmobs:perpetual_frost_slip): the same
 *   frictionless footing as the stray, but they did not ask for
 *   it. The modifier is tracked per victim in the state tag
 *   ("slipping": UUID string keys, rebuilt every pulse, so it
 *   only ever holds the current crowd). A victim caught by a
 *   pulse keeps the modifier; at the next pulse, any tracked
 *   victim no longer in the zone has the modifier removed and is
 *   pruned. end() removes it from everyone still tracked. This
 *   tracking exists because a transient attribute modifier does
 *   not expire on its own. (In mobGriefing-off mode the zone is
 *   centered on the stray's start position the same way; only
 *   the ice is missing.)
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * the rink is PERMANENT and gated by the vanilla mobGriefing
 * game rule, checked through AbilityContext.mobGriefingAllowed():
 * - mobGriefing ON: the rink is converted and STAYS. end()
 *   touches no blocks; the ice is ordinary terrain afterwards.
 * - mobGriefing OFF: no blocks are changed at all. The ability
 *   runs as a pure slip zone for its duration (same pulses, same
 *   glide for the stray, same visuals), an effect area rather
 *   than terrain, and the world is left exactly as it was.
 *
 * WHAT IT LOOKS LIKE: the floor flashing over into a pale packed
 * ice rink under a drift of SNOWFLAKE (12 at the spread, 4 more
 * every pulse), and the stray carving across it at unnatural
 * speed while enemies skate past the turns they meant to make.
 *
 * PERFORMANCE: block work happens once in start() (at most 49
 * column checks, mobGriefing mode only) and never again. tick()
 * returns immediately except every 10th tick (slip pulse: one
 * bounded entity query over a 7.2 by 4.5 by 7.2 box, plus one
 * level.getEntity per tracked victim who left, bounded by the
 * crowd on the rink; the SNOWFLAKE drift rides the same pulse).
 * Particle budget per activation: 12 at start plus at most 30
 * pulses times 4 = 120, total at most 132 particles.
 *
 * State tag keys: "cx", "cy", "cz" (rink center block pos, the
 * feet level of the stray at start), "slipping" (compound: UUID
 * string to 1, the victims currently carrying the slip modifier).
 * No block list is stored: converted blocks are permanent and
 * never revisited.
 */
public final class PerpetualFrostAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 10;
    private static final int RINK_RADIUS = 3;
    private static final double SLIP_AMOUNT = -0.92;
    private static final Identifier GLIDE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "perpetual_frost_glide");
    private static final Identifier SKATER_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "perpetual_frost_skater");
    private static final Identifier SLIP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "perpetual_frost_slip");

    @Override
    public String id() {
        return "perpetual_frost";
    }

    @Override
    public String displayName() {
        return "Perpetual Frost";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        BlockPos center = mob.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.put("slipping", new CompoundTag());

        // Terrain (mobGriefing mode only): spread the permanent
        // rink. With mobGriefing off, no block is touched and the
        // ability is a slip zone only. See the TERRAIN RULE in
        // the class comment.
        if (level != null && ctx.mobGriefingAllowed()) {
            spreadRink(level, center);
        }

        // The skating edge: near-zero friction and a speed edge,
        // for the duration, wherever the stray goes.
        AttributeInstance friction = mob.getAttribute(Attributes.FRICTION_MODIFIER);
        if (friction != null) {
            friction.addOrUpdateTransientModifier(
                new AttributeModifier(GLIDE_ID, SLIP_AMOUNT, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.addOrUpdateTransientModifier(
                new AttributeModifier(SKATER_ID, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }

        if (level != null) {
            level.sendParticles(ParticleTypes.SNOWFLAKE,
                center.getX() + 0.5, center.getY() + 0.4, center.getZ() + 0.5,
                12, 3.0, 0.4, 3.0, 0.03);
        }
    }

    /**
     * Converts the permanent rink (see the class comment for the
     * shape and the safety checks). The blocks are converted for
     * good: nothing records them for removal.
     */
    private static void spreadRink(ServerLevel level, BlockPos center) {
        int floorY = center.getY() - 1;
        for (int dx = -RINK_RADIUS; dx <= RINK_RADIUS; dx++) {
            for (int dz = -RINK_RADIUS; dz <= RINK_RADIUS; dz++) {
                BlockPos floorPos = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                BlockPos belowPos = floorPos.below();
                if (isConvertible(level, floorPos)
                    && level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
                    level.setBlock(floorPos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * Whether the block at the position may be converted into the
     * rink: a real, dry, diggable block with no block entity that
     * is not already packed ice. Air and fluids are never
     * converted, containers and other block entities are never
     * touched, and unbreakable blocks (negative destroy speed) are
     * never touched.
     */
    private static boolean isConvertible(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.PACKED_ICE) || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: pulses run every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;

        // Slip pulse: enemies on the rink get the stray's
        // frictionless footing forced on them. This runs in both
        // terrain modes; the zone exists whether or not the rink
        // was allowed to open.
        CompoundTag previous = state.getCompoundOrEmpty("slipping");
        CompoundTag slipping = new CompoundTag();
        Set<String> current = new HashSet<>();
        List<LivingEntity> victims = StrayAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 3.6, 2.5, 2.0);
        for (LivingEntity victim : victims) {
            AttributeInstance friction = victim.getAttribute(Attributes.FRICTION_MODIFIER);
            if (friction != null) {
                friction.addOrUpdateTransientModifier(
                    new AttributeModifier(SLIP_ID, SLIP_AMOUNT, AttributeModifier.Operation.ADD_VALUE));
                String key = victim.getUUID().toString();
                slipping.putInt(key, 1);
                current.add(key);
            }
        }
        // Anyone tracked last pulse but gone now gets their grip back.
        for (String key : previous.keySet()) {
            if (!current.contains(key)) {
                removeSlip(level, key);
            }
        }
        // Replacing the tag prunes every victim that left the rink.
        state.put("slipping", slipping);

        level.sendParticles(ParticleTypes.SNOWFLAKE, cx, cy + 0.4, cz, 4, 3.0, 0.4, 3.0, 0.03);
    }

    /** Removes the slip modifier from the entity with the given UUID string, if it still exists. */
    private static void removeSlip(ServerLevel level, String uuidKey) {
        try {
            Entity entity = level.getEntity(UUID.fromString(uuidKey));
            if (entity instanceof LivingEntity living) {
                AttributeInstance friction = living.getAttribute(Attributes.FRICTION_MODIFIER);
                if (friction != null) {
                    friction.removeModifier(SLIP_ID);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed key can only come from a hand-edited save;
            // there is nothing to clean up for it.
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The stray's edge comes off, and every victim still
        // tracked gets their grip back. The rink, when converted,
        // is permanent terrain and is deliberately NOT touched
        // here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance friction = mob.getAttribute(Attributes.FRICTION_MODIFIER);
        if (friction != null) {
            friction.removeModifier(GLIDE_ID);
        }
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SKATER_ID);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag slipping = ctx.state().getCompoundOrEmpty("slipping");
        for (String key : slipping.keySet()) {
            removeSlip(level, key);
        }
    }
}
