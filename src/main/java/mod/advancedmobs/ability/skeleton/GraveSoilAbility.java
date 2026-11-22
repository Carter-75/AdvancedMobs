package mod.advancedmobs.ability.skeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
 * Grave Soil (Skeleton ability 4 of 5, terrain).
 *
 * WHAT IT DOES: the skeleton stamps, and the battlefield floor
 * sours into soul soil and gravel: real blocks spreading in a
 * patchwork under SOUL wisps and FALLING_DUST. Enemies crossing the
 * grave soil are slowed, and the clinging dead cut their follow
 * range, so they lose track of targets beyond the patch.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two modes:
 * - mobGriefing ON: the patch is converted and STAYS. There is no
 *   restore: end() touches no blocks, and nothing un-sours the soil
 *   later. The debuff zone still lasts only the ability duration;
 *   the patch itself is just terrain afterwards (soul soil and
 *   gravel are ordinary blocks once placed).
 * - mobGriefing OFF: no blocks are changed at all. The ability runs
 *   as a pure debuff zone for its duration (same pulses, same
 *   visuals, no patch), and the world is left exactly as it was.
 *   In this mode the zone is an effect area, not terrain, so the
 *   gate has nothing to block beyond the conversion itself.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 12 blocks (distance
 *   squared 144), and standing on the ground.
 * - Duration: exactly 200 ticks (10 seconds) from start(). This is
 *   the debuff zone's lifetime; in mobGriefing mode the patch
 *   outlives it.
 * - The patch (mobGriefing mode only), centered on the target's
 *   block position at start(), a 5 by 5 area (Chebyshev radius 2):
 *   the floor block (one below the target's feet level) in each
 *   column is converted in place to soul soil or gravel, coin flip
 *   per column, when the block is convertible and the block under
 *   it is face-sturdy upward, so nothing ever floats. At most 25
 *   blocks change. Convertible means: not air, no fluid, no block
 *   entity (containers, signs, spawners and so on are never
 *   touched), and destroy speed not negative (no bedrock or
 *   barriers). Nothing that is converted drops as an item: the
 *   block is replaced, not broken.
 * - Debuff pulse every 10 ticks (20 pulses): enemies
 *   (SkeletonAbilitySupport rules) within horizontal radius 2.8 of
 *   the patch center and between 2.5 below and 2 above its feet
 *   level get SLOWNESS I (amplifier 0) for 15 ticks, refreshed by
 *   the next pulse, and the clinging modifier below.
 * - The clinging dead: victims carry a FOLLOW_RANGE
 *   add_multiplied_base modifier of -0.5 (modifier id
 *   advancedmobs:grave_soil_clinging), halving the follow range
 *   their base value gives them, so a victim whose prey steps out
 *   of its halved range loses the trail at the patch's edge. The
 *   modifier is tracked per victim in the state tag ("clinging":
 *   UUID string keys, rebuilt every pulse like Quicksand Pit's
 *   jump sap record, so it only ever holds the current crowd). A
 *   victim caught by a pulse keeps the modifier; at the next
 *   pulse, any tracked victim no longer in the zone has the
 *   modifier removed and is pruned. end() removes it from everyone
 *   still tracked. This tracking exists because, unlike an effect,
 *   a transient attribute modifier does not expire on its own.
 *   (Players carry the attribute too but nothing reads it for
 *   them; the modifier is simply inert on a player victim.)
 *
 * WHAT IT LOOKS LIKE: on start, 16 SOUL wisps rise over the patch
 * and 10 FALLING_DUST of soul soil sift down into it. Every 20
 * ticks during the zone, 3 more SOUL wisps curl up from the soil.
 * Victims show the vanilla SLOWNESS effect sparkles while crossing.
 *
 * PERFORMANCE: block work happens once in start() (at most 25
 * column checks, mobGriefing mode only) and never again. tick()
 * returns immediately except every 10th tick (debuff pulse: one
 * bounded entity query over a 5.6 by 4.5 by 5.6 box, plus one
 * level.getEntity per tracked victim who left, bounded by the
 * crowd on the patch) and the SOUL wisps ride the same pulse every
 * 20 ticks. Particle budget per activation: 26 at start plus at
 * most 10 wisp pulses times 3 = 30, total at most 56 particles.
 *
 * State tag keys: "cx", "cy", "cz" (patch center block pos, the
 * feet level of the target at start), "clinging" (compound: UUID
 * string to 1, the victims currently carrying the follow range
 * modifier). No block list is stored: converted blocks are
 * permanent and never revisited.
 */
public final class GraveSoilAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final int PATCH_RADIUS = 2;
    private static final Identifier CLINGING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "grave_soil_clinging");
    private static final double CLINGING_AMOUNT = -0.5;
    private static final BlockParticleOption SOUL_SOIL_FALLING =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SOUL_SOIL.defaultBlockState());

    @Override
    public String id() {
        return "grave_soil";
    }

    @Override
    public String displayName() {
        return "Grave Soil";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && target.onGround() && ctx.mob().distanceToSqr(target) <= 144.0;
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
        state.put("clinging", new CompoundTag());

        // Terrain (mobGriefing mode only): sour the permanent
        // patch. With mobGriefing off, no block is touched and the
        // ability is a debuff zone only. See the TERRAIN RULE in
        // the class comment.
        if (ctx.mobGriefingAllowed()) {
            convertPatch(level, center, ctx);
        }

        // The souring: soul wisps rise, grave dust sifts down. The
        // visuals run in both terrain modes.
        double cx = center.getX() + 0.5, cy = center.getY(), cz = center.getZ() + 0.5;
        level.sendParticles(ParticleTypes.SOUL, cx, cy + 0.3, cz, 16, 2.2, 0.5, 2.2, 0.05);
        level.sendParticles(SOUL_SOIL_FALLING, cx, cy + 0.6, cz, 10, 2.2, 0.4, 2.2, 0.03);
    }

    /**
     * Converts the permanent patch (see the class comment for the
     * shape and the safety checks). The blocks are converted for
     * good: nothing records them for removal.
     */
    private static void convertPatch(ServerLevel level, BlockPos center, AbilityContext ctx) {
        int floorY = center.getY() - 1;
        for (int dx = -PATCH_RADIUS; dx <= PATCH_RADIUS; dx++) {
            for (int dz = -PATCH_RADIUS; dz <= PATCH_RADIUS; dz++) {
                BlockPos floorPos = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                BlockPos belowPos = floorPos.below();
                if (isConvertible(level, floorPos)
                    && level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
                    level.setBlock(floorPos, soulSoilOrGravel(ctx), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Soul soil or gravel, coin flip per column: the patchwork mix. */
    private static BlockState soulSoilOrGravel(AbilityContext ctx) {
        return ctx.random().nextBoolean()
            ? Blocks.SOUL_SOIL.defaultBlockState()
            : Blocks.GRAVEL.defaultBlockState();
    }

    /**
     * Whether the block at the position may be converted by this
     * ability: a real, dry, diggable block with no block entity.
     * Air and fluids are never converted, containers and other
     * block entities are never touched, and unbreakable blocks
     * (negative destroy speed) are never touched.
     */
    private static boolean isConvertible(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
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

        // Debuff pulse: enemies on the patch are slowed and their
        // follow range is halved by the clinging dead. This runs in
        // both terrain modes; the zone exists whether or not the
        // patch was allowed to open.
        CompoundTag previous = state.getCompoundOrEmpty("clinging");
        CompoundTag clinging = new CompoundTag();
        Set<String> current = new HashSet<>();
        List<LivingEntity> victims = SkeletonAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 2.8, 2.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 0));
            AttributeInstance follow = victim.getAttribute(Attributes.FOLLOW_RANGE);
            if (follow != null) {
                follow.addOrUpdateTransientModifier(
                    new AttributeModifier(CLINGING_ID, CLINGING_AMOUNT, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                String key = victim.getUUID().toString();
                clinging.putInt(key, 1);
                current.add(key);
            }
        }
        // Anyone tracked last pulse but gone now shakes the dead off.
        for (String key : previous.keySet()) {
            if (!current.contains(key)) {
                removeClinging(level, key);
            }
        }
        // Replacing the tag prunes every victim that left the patch.
        state.put("clinging", clinging);

        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.SOUL, cx, cy + 0.3, cz, 3, 2.0, 0.4, 2.0, 0.04);
        }
    }

    /** Removes the clinging modifier from the entity with the given UUID string, if it still exists. */
    private static void removeClinging(ServerLevel level, String uuidKey) {
        try {
            Entity entity = level.getEntity(UUID.fromString(uuidKey));
            if (entity instanceof LivingEntity living) {
                AttributeInstance follow = living.getAttribute(Attributes.FOLLOW_RANGE);
                if (follow != null) {
                    follow.removeModifier(CLINGING_ID);
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
        // Every victim still tracked shakes the clinging dead off.
        // The patch, when converted, is permanent terrain and is
        // deliberately NOT touched here (terrain rule, see the
        // class comment).
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag clinging = ctx.state().getCompoundOrEmpty("clinging");
        for (String key : clinging.keySet()) {
            removeClinging(level, key);
        }
    }
}
