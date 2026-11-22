package mod.advancedmobs.ability.bogged;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bog Mire (Bogged ability 3 of 5, terrain).
 *
 * WHAT IT DOES: the ground around the bogged softens into its
 * home turf: a wet stain of real mud with shallow water patches
 * spread through it, BUBBLEs rising and brown DUST hanging over
 * the muck. Enemies standing in the mire sink: SLOWNESS, and the
 * soft ground steals the benefit of their movement efficiency
 * outright. The bogged's own steps stay light and quick across
 * the muck it made.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two
 * modes:
 * - mobGriefing ON: the mud and water are placed and STAY. There
 *   is no restore: end() touches no blocks, and nothing is
 *   scheduled to dry the mire out later. The mire's debuff zone
 *   still lasts only the ability duration; the wet ground itself
 *   is just terrain afterwards.
 * - mobGriefing OFF: no blocks are placed at all. The ability
 *   runs as a pure debuff zone for its duration (same pulses,
 *   same visuals, no mud), and the world is left exactly as it
 *   was.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 14 blocks
 *   (distance squared 196) and the bogged is standing on the
 *   ground. The mire spreads around the BOGGED, not the target:
 *   it is home turf the bogged fights from, so the target has to
 *   come into the muck to reach it.
 * - Duration: exactly 200 ticks (10 seconds) from start(). This
 *   is the debuff zone's lifetime; in mobGriefing mode the mud
 *   and water outlive it.
 * - The stain (mobGriefing mode only): every column in the disc
 *   of radius 3 around the bogged's block position at start()
 *   (dx and dz in -3..3 with dx*dx + dz*dz <= 12, 37 columns),
 *   at the floor level one below the bogged's feet. A column is
 *   converted only when its floor block is soft natural ground
 *   (grass block, dirt, coarse dirt, podzol, mycelium block,
 *   sand, red sand, gravel, soul sand, soul soil, or mud), has
 *   no block entity, is not unbreakable (destroy speed not
 *   negative), and the block below it is face-sturdy in the live
 *   level (so the stain never floats and a water patch always
 *   sits in a solid basin). Each convertible column becomes MUD,
 *   except a 1 in 4 roll per column makes it a WATER source
 *   instead: the shallow patches. Stone, wood, planks, and every
 *   other built or hard block are left alone, so the mire claims
 *   wild ground, not floors. At most 37 blocks change.
 * - Debuff pulse every 10 ticks: enemies (BoggedAbilitySupport
 *   rules) within horizontal radius 3.6 of the stain center and
 *   between 1 below and 2.5 above its floor get SLOWNESS II
 *   (amplifier 1) for 15 ticks, refreshed by the next pulse, and
 *   a MOVEMENT_EFFICIENCY add_value modifier of -1.0 (modifier
 *   id advancedmobs:bog_mire_drag), which cancels any movement
 *   efficiency benefit they carry, exactly the design's "lose
 *   the benefit of their movement efficiency". The drag is
 *   tracked per victim in the state tag: each pulse removes it
 *   from everyone who has left the zone and end() removes it
 *   from everyone still tracked, so nobody keeps the drag after
 *   stepping out of the muck for more than one pulse interval.
 * - The bogged gets MOVEMENT_EFFICIENCY add_value +1.0 for the
 *   duration (modifier id advancedmobs:bog_mire_efficiency,
 *   removed in end()), so slow ground never hinders it, and the
 *   pulse never targets it (it is not its own enemy).
 *
 * WHAT IT LOOKS LIKE: on start, 10 BUBBLE particles rise through
 * the stain and 8 brown DUST motes (color 0.36, 0.27, 0.16,
 * scale 1.3) hang over it, with a wet MUD_BREAK squelch (volume
 * 0.8, pitch 0.9). Every 20 ticks during the mire, 4 more
 * BUBBLEs and 2 more DUST motes drift up inside the stain.
 *
 * PERFORMANCE: block work happens once in start() (at most 37
 * positions, mobGriefing mode only) and never again; end() only
 * removes attribute modifiers. tick() returns immediately except
 * every 10th tick (debuff pulse: one bounded entity query over a
 * 7.2 by 3.5 by 7.2 box, plus one UUID resolve per victim
 * tracked from the previous pulse, bounded by the crowd standing
 * in a 3 block mire) and every 20th tick (6 particles). Particle
 * budget per activation: 18 at start plus at most 10 pulses
 * times 6 = 60, total at most 78 particles.
 *
 * State tag keys: "cx", "cy", "cz" (stain center block pos, the
 * bogged's feet level at start), "dragged" (compound: UUID
 * string to 1, the victims currently carrying the drag
 * modifier). No block list is stored: placed blocks are
 * permanent and never revisited.
 */
public final class BogMireAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final int STAIN_RADIUS = 3;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bog_mire_efficiency");
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bog_mire_drag");
    private static final double DRAG_AMOUNT = -1.0;
    // 26.2 DustParticleOptions takes an RGB int color: 0.36, 0.27, 0.16 as 0x5C4529.
    private static final DustParticleOptions BOG_DUST = new DustParticleOptions(0x5C4529, 1.3f);

    @Override
    public String id() {
        return "bog_mire";
    }

    @Override
    public String displayName() {
        return "Bog Mire";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround() && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        BlockPos center = mob.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.put("dragged", new CompoundTag());

        // Terrain (mobGriefing mode only): soften the permanent
        // stain. With mobGriefing off, no block is touched and
        // the ability is a debuff zone only. See the TERRAIN RULE
        // in the class comment.
        if (ctx.mobGriefingAllowed()) {
            softenGround(level, center, ctx);
        }

        // The bogged's own steps stay light across its muck.
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The stain wakes: bubbles rise, brown dust hangs, the
        // ground squelches.
        double cx = center.getX() + 0.5, cy = center.getY(), cz = center.getZ() + 0.5;
        level.sendParticles(ParticleTypes.BUBBLE, cx, cy + 0.4, cz, 10, 2.6, 0.5, 2.6, 0.03);
        level.sendParticles(BOG_DUST, cx, cy + 0.6, cz, 8, 2.6, 0.5, 2.6, 0.01);
        level.playSound(null, cx, cy, cz, SoundEvents.MUD_BREAK, SoundSource.HOSTILE, 0.8f, 0.9f);
    }

    /**
     * Softens the permanent stain (see the class comment for the
     * shape, the soft-ground list, and the safety checks). The
     * blocks are placed for good: nothing records them for
     * removal.
     */
    private static void softenGround(ServerLevel level, BlockPos center, AbilityContext ctx) {
        int floorY = center.getY() - 1;
        for (int dx = -STAIN_RADIUS; dx <= STAIN_RADIUS; dx++) {
            for (int dz = -STAIN_RADIUS; dz <= STAIN_RADIUS; dz++) {
                if (dx * dx + dz * dz > 12) {
                    continue;
                }
                BlockPos floorPos = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                if (!isSoftGround(level, floorPos)) {
                    continue;
                }
                BlockPos belowPos = floorPos.below();
                if (!level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
                    continue; // The stain never floats; a water patch needs its basin.
                }
                BlockState placed = ctx.random().nextInt(4) == 0
                    ? Blocks.WATER.defaultBlockState()
                    : Blocks.MUD.defaultBlockState();
                level.setBlock(floorPos, placed, Block.UPDATE_ALL);
            }
        }
    }

    /**
     * Whether the block at the position is soft natural ground
     * this ability may soften: one of the listed wild ground
     * blocks, with no block entity, and not unbreakable. Built
     * and hard blocks (stone, wood, planks, brick) are never
     * converted: the mire claims wild ground, not floors.
     */
    private static boolean isSoftGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.SAND)
            || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.SOUL_SAND)
            || state.is(Blocks.SOUL_SOIL) || state.is(Blocks.MUD))) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false; // Never overwrite containers or other block entities.
        }
        return state.getDestroySpeed(level, pos) >= 0.0f; // Never touch unbreakable blocks.
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

        // Debuff pulse: enemies in the mire sink, and the drag
        // follows whoever is standing in it right now. This runs
        // in both terrain modes; the zone exists whether or not
        // the ground was allowed to soften.
        CompoundTag previous = state.getCompoundOrEmpty("dragged");
        CompoundTag dragged = new CompoundTag();
        Set<String> current = new HashSet<>();
        List<LivingEntity> victims = BoggedAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 3.6, 1.0, 2.5);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 1));
            AttributeInstance efficiency = victim.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
            if (efficiency != null) {
                efficiency.addOrUpdateTransientModifier(
                    new AttributeModifier(DRAG_ID, DRAG_AMOUNT, AttributeModifier.Operation.ADD_VALUE));
                String key = victim.getUUID().toString();
                dragged.putInt(key, 1);
                current.add(key);
            }
        }
        // Anyone tracked last pulse but gone now steps out of the
        // muck clean.
        for (String key : previous.keySet()) {
            if (!current.contains(key)) {
                removeDrag(level, key);
            }
        }
        // Replacing the tag prunes every victim that left the mire.
        state.put("dragged", dragged);

        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, cx, cy + 0.4, cz, 4, 2.6, 0.4, 2.6, 0.03);
            level.sendParticles(BOG_DUST, cx, cy + 0.6, cz, 2, 2.6, 0.4, 2.6, 0.01);
        }
    }

    /** Removes the drag modifier from the entity with the given UUID string, if it still exists. */
    private static void removeDrag(ServerLevel level, String uuidKey) {
        try {
            Entity entity = level.getEntity(UUID.fromString(uuidKey));
            if (entity instanceof LivingEntity living) {
                AttributeInstance efficiency = living.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
                if (efficiency != null) {
                    efficiency.removeModifier(DRAG_ID);
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
        // The caster's own modifier comes off, and every victim
        // still tracked loses the drag. The mud and water, when
        // placed, are permanent terrain and are deliberately NOT
        // touched here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag dragged = ctx.state().getCompoundOrEmpty("dragged");
        for (String key : dragged.keySet()) {
            removeDrag(level, key);
        }
    }
}
