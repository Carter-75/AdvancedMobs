package mod.advancedmobs.ability.husk;

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
 * Quicksand Pit (Husk ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the ground under the husk's target gives way into a
 * real pit of layered sand and gravel. The center drops a block, the
 * rim crumbles into sand and gravel, and victims caught in the pit are
 * mired under heavy SLOWNESS with their jump strength sapped, so
 * climbing out is nearly impossible while the husk skims across the
 * surface on raised MOVEMENT_EFFICIENCY.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract): terrain
 * changes from abilities are PERMANENT and gated by the vanilla
 * mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two modes:
 * - mobGriefing ON: the pit is dug and STAYS. There is no restore:
 *   end() touches no blocks, and nothing fills the pit in later. The
 *   debuff zone still lasts only the ability duration; the pit itself
 *   is just terrain afterwards.
 * - mobGriefing OFF: no blocks are changed at all. The ability runs
 *   as a pure debuff zone for its duration (same pulses, same
 *   visuals, no pit), and the world is left exactly as it was. In
 *   this mode the zone is an effect area, not terrain, so the gate
 *   has nothing to block beyond the digging itself.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 12 blocks (distance
 *   squared 144), and standing on the ground.
 * - Duration: exactly 200 ticks (10 seconds) from start(). This is
 *   the debuff zone's lifetime; in mobGriefing mode the pit outlives
 *   it.
 * - The pit (mobGriefing mode only), centered on the target's block
 *   position at start(), a 5 by 5 area (Chebyshev radius 2) around
 *   the target's feet level:
 *     Inner 3 by 3 (radius 0 to 1): the floor block (one below the
 *       target's feet) is removed, dropping the column one block, and
 *       the block below that becomes the pit floor: sand or gravel,
 *       coin flip per column. A column is only dug when the floor
 *       block is diggable, the pit floor block is diggable, and the
 *       block under the pit floor is face-sturdy upward, so the pit
 *       always has a supported sand or gravel floor and never opens
 *       a shaft over a void.
 *     Rim (radius 2): the floor block is converted in place to sand
 *       or gravel, coin flip per column, when it is diggable and the
 *       block below it is face-sturdy upward: the crumbled edge of
 *       the pit, at the original ground level.
 *   At most 34 blocks change (9 dug columns times 2, plus 16 rim
 *   conversions). Diggable means: not air, no fluid, no block entity
 *   (containers, signs, spawners and so on are never touched), and
 *   destroy speed not negative (no bedrock or barriers). Nothing that
 *   is removed or converted drops as an item.
 * - Debuff pulse every 10 ticks (20 pulses): enemies
 *   (HuskAbilitySupport rules) within horizontal radius 2.8 of the
 *   pit center and between 2.5 below and 2 above its feet level get
 *   SLOWNESS II (amplifier 1) for 15 ticks, refreshed by the next
 *   pulse, and the jump sap below.
 * - Jump sap: victims carry a JUMP_STRENGTH add_value modifier of
 *   -0.30 (modifier id advancedmobs:quicksand_jump_sap), dropping
 *   jump strength from the 0.42 base to 0.12, which cannot clear the
 *   one-block pit wall. The modifier is tracked per victim in the
 *   state tag ("sapped": UUID string keys, rebuilt every pulse like
 *   Rot Aura's exposure record, so it only ever holds the current
 *   crowd). A victim caught by a pulse keeps the modifier; at the
 *   next pulse, any tracked victim no longer in the zone has the
 *   modifier removed and is pruned. end() removes it from everyone
 *   still tracked. This tracking exists because, unlike an effect, a
 *   transient attribute modifier does not expire on its own.
 * - The husk gets MOVEMENT_EFFICIENCY add_value +1.0 for the
 *   duration (modifier id advancedmobs:quicksand_pit_efficiency,
 *   removed in end()), so slow terrain never hinders it, and the
 *   pulse never targets it.
 *
 * WHAT IT LOOKS LIKE: on start, 24 BLOCK_CRUMBLE particles of sand
 * burst over the pit area at floor level and 12 FALLING_DUST of sand
 * sift down over it. Every 20 ticks during the zone, 4 more
 * FALLING_DUST of sand drift down inside the pit. Victims show the
 * vanilla SLOWNESS effect sparkles while mired.
 *
 * PERFORMANCE: block work happens once in start() (at most 25 column
 * checks, mobGriefing mode only) and never again. tick() returns
 * immediately except every 10th tick (debuff pulse: one bounded
 * entity query over a 5.6 by 4.5 by 5.6 box, plus one level.getEntity
 * per tracked victim who left, bounded by the crowd in the pit) and
 * every 20th tick (4 particles). Particle budget per activation: 36
 * at start plus at most 10 zone pulses times 4 = 40, total at most
 * 76 particles.
 *
 * State tag keys: "cx", "cy", "cz" (pit center block pos, the feet
 * level of the target at start), "sapped" (compound: UUID string to
 * 1, the victims currently carrying the jump sap modifier). No block
 * list is stored: dug blocks are permanent and never revisited.
 */
public final class QuicksandPitAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final int PIT_RADIUS = 2;
    private static final Identifier EFFICIENCY_ID = Identifier.fromNamespaceAndPath("advancedmobs", "quicksand_pit_efficiency");
    private static final Identifier JUMP_SAP_ID = Identifier.fromNamespaceAndPath("advancedmobs", "quicksand_jump_sap");
    private static final double JUMP_SAP_AMOUNT = -0.30;
    private static final BlockParticleOption SAND_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.SAND.defaultBlockState());
    private static final BlockParticleOption SAND_FALLING =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());

    @Override
    public String id() {
        return "quicksand_pit";
    }

    @Override
    public String displayName() {
        return "Quicksand Pit";
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
        Mob mob = ctx.mob();
        BlockPos center = target.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.put("sapped", new CompoundTag());

        // Terrain (mobGriefing mode only): dig the permanent pit.
        // With mobGriefing off, no block is touched and the ability is
        // a debuff zone only. See the TERRAIN RULE in the class comment.
        if (ctx.mobGriefingAllowed()) {
            digPit(level, center, ctx);
        }

        // The husk skims across its own quicksand at full pace.
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // Collapse visuals: crumbling sand over the pit, dust sifting down.
        double cx = center.getX() + 0.5, cy = center.getY(), cz = center.getZ() + 0.5;
        level.sendParticles(SAND_CRUMBLE, cx, cy - 0.5, cz, 24, 2.2, 0.5, 2.2, 0.08);
        level.sendParticles(SAND_FALLING, cx, cy + 0.5, cz, 12, 2.2, 0.5, 2.2, 0.03);
    }

    /**
     * Digs the permanent pit (see the class comment for the shape and
     * the safety checks). The blocks are dug for good: nothing records
     * them for removal.
     */
    private static void digPit(ServerLevel level, BlockPos center, AbilityContext ctx) {
        int feetY = center.getY();
        for (int dx = -PIT_RADIUS; dx <= PIT_RADIUS; dx++) {
            for (int dz = -PIT_RADIUS; dz <= PIT_RADIUS; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                BlockPos floorPos = new BlockPos(center.getX() + dx, feetY - 1, center.getZ() + dz);
                BlockPos belowPos = floorPos.below();
                if (ring == PIT_RADIUS) {
                    // Rim: convert the floor block in place to sand or
                    // gravel, if diggable and supported from below.
                    if (isDiggable(level, floorPos)
                        && level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
                        level.setBlock(floorPos, sandOrGravel(ctx), Block.UPDATE_ALL);
                    }
                } else {
                    // Inner: drop the column one block onto a new sand
                    // or gravel pit floor. The pit floor is set first,
                    // then the old floor is cleared, and the column is
                    // only dug when the pit floor is supported.
                    BlockPos supportPos = belowPos.below();
                    if (isDiggable(level, floorPos) && isDiggable(level, belowPos)
                        && level.getBlockState(supportPos).isFaceSturdy(level, supportPos, Direction.UP)) {
                        level.setBlock(belowPos, sandOrGravel(ctx), Block.UPDATE_ALL);
                        level.setBlock(floorPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    /** Sand or gravel, coin flip per column: the layered pit mix. */
    private static BlockState sandOrGravel(AbilityContext ctx) {
        return ctx.random().nextBoolean()
            ? Blocks.SAND.defaultBlockState()
            : Blocks.GRAVEL.defaultBlockState();
    }

    /**
     * Whether the block at the position may be dug or converted by
     * this ability: a real, dry, diggable block with no block entity.
     * Air and fluids are never dug (a column over water or a ledge is
     * left alone), containers and other block entities are never
     * touched, and unbreakable blocks (negative destroy speed) are
     * never touched.
     */
    private static boolean isDiggable(ServerLevel level, BlockPos pos) {
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

        // Debuff pulse: enemies in the pit are mired and jump-sapped.
        // This runs in both terrain modes; the zone exists whether or
        // not the pit was allowed to open.
        CompoundTag previous = state.getCompoundOrEmpty("sapped");
        CompoundTag sapped = new CompoundTag();
        Set<String> current = new HashSet<>();
        List<LivingEntity> victims = HuskAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 2.8, 2.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 1));
            AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
            if (jump != null) {
                jump.addOrUpdateTransientModifier(
                    new AttributeModifier(JUMP_SAP_ID, JUMP_SAP_AMOUNT, AttributeModifier.Operation.ADD_VALUE));
                String key = victim.getUUID().toString();
                sapped.putInt(key, 1);
                current.add(key);
            }
        }
        // Anyone tracked last pulse but gone now gets their jump back.
        for (String key : previous.keySet()) {
            if (!current.contains(key)) {
                removeJumpSap(level, key);
            }
        }
        // Replacing the tag prunes every victim that left the pit.
        state.put("sapped", sapped);

        if (ticks % 20 == 0) {
            level.sendParticles(SAND_FALLING, cx, cy + 0.5, cz, 4, 2.0, 0.5, 2.0, 0.03);
        }
    }

    /** Removes the jump sap modifier from the entity with the given UUID string, if it still exists. */
    private static void removeJumpSap(ServerLevel level, String uuidKey) {
        try {
            Entity entity = level.getEntity(UUID.fromString(uuidKey));
            if (entity instanceof LivingEntity living) {
                AttributeInstance jump = living.getAttribute(Attributes.JUMP_STRENGTH);
                if (jump != null) {
                    jump.removeModifier(JUMP_SAP_ID);
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
        // The husk's own modifier is undone, and every victim still
        // tracked gets its jump strength back. The pit, when dug, is
        // permanent terrain and is deliberately NOT touched here
        // (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag sapped = ctx.state().getCompoundOrEmpty("sapped");
        for (String key : sapped.keySet()) {
            removeJumpSap(level, key);
        }
    }
}
