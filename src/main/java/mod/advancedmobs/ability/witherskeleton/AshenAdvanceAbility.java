package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * Ashen Advance (Wither Skeleton ability 4 of 5, terrain).
 *
 * WHAT IT DOES: every step the wither skeleton takes in this
 * state scorches the earth it just left: real fire blooms over
 * a trail of blackstone set into the ground, ASH and
 * SOUL_FIRE_FLAME streaming behind it. Enemies who cross the
 * burning trail catch fire and burn twice as long as they
 * should, and the lane of flame cuts the battlefield in two.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two
 * modes:
 * - mobGriefing ON: the blackstone and fire are placed and
 *   STAY (the fire burns out on its own vanilla schedule; the
 *   blackstone is permanent). There is no restore: end()
 *   touches no blocks. The burn zone lasts only the ability
 *   duration; the scorched lane itself is just terrain
 *   afterwards.
 * - mobGriefing OFF: no blocks are placed at all. The ability
 *   still runs: the burn pulse below checks the ground the
 *   enemy stands on, and natural fire or blackstone (a nether
 *   fortress is full of blackstone) triggers it exactly the
 *   same way, with the same visuals, and the world is left
 *   exactly as it was.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 14 blocks
 *   (distance squared 196) and the wither skeleton is standing
 *   on the ground. The advance is a charge across the field, so
 *   it needs footing.
 * - Duration: exactly 160 ticks (8 seconds) from start().
 * - The trail (mobGriefing mode only): the ability remembers
 *   the caster's block position. Whenever the caster's block
 *   position changes, the column it just LEFT is scorched:
 *     Floor: the block below the old feet position becomes
 *       BLACKSTONE, but only when it is an existing solid block
 *       with no block entity, is not unbreakable (destroy speed
 *       not negative), and is not already blackstone. The
 *       blackstone is set INTO the ground (it replaces the
 *       floor block itself), so the trail never floats and
 *       never raises the walking surface.
 *     Fire: if the old feet position itself is replaceable
 *       (canBeReplaced, no block entity) and the new blackstone
 *       floor is face-sturdy on top, a FIRE block is set there.
 *   Scorched columns are capped at 48 per activation: at the
 *   wither skeleton's walking speed an 8 second advance lays
 *   roughly 35 columns, so the cap only bites for a sprinting
 *   or knocked-back caster, and it bounds the block work no
 *   matter what. At most 96 blocks change (48 floors plus 48
 *   fires).
 * - The burn pulse every 10 ticks: enemies
 *   (WitherSkeletonAbilitySupport rules) within horizontal
 *   radius 3.0 of the caster, between 1 below and 2.5 above its
 *   feet, who are standing ON the trail (the block below their
 *   feet is blackstone) or IN it (the block at their feet is
 *   fire) are ignited for 4 seconds (igniteForSeconds) and
 *   carry a BURNING_TIME add_multiplied_base modifier of +1.0
 *   (modifier id advancedmobs:ashen_advance_burning): their
 *   fire ticks last twice as long, the design's extended
 *   BURNING_TIME, so the 4 second ignition burns for about 8.
 *   The modifier is tracked per victim in the state tag: each
 *   pulse removes it from everyone who has left the trail, and
 *   end() removes it from everyone still tracked, so nobody
 *   keeps the doubled burn after stepping off the lane for more
 *   than one pulse interval. The caster is never its own enemy
 *   and is never ignited or modified.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: on start, 6 ASH and 4
 * SOUL_FIRE_FLAME motes stream off the caster's back with a
 * FIRECHARGE_USE whoosh (volume 0.8, pitch 0.8). Every 4 ticks
 * during the advance, 2 ASH and 1 SOUL_FIRE_FLAME drift off the
 * last scorched spot behind it. The trail itself is real fire
 * on real blackstone and needs no further dressing.
 *
 * PERFORMANCE: the per-tick work is one block position compare
 * (and, on a move tick, at most 2 block reads and 2 block
 * writes in mobGriefing mode, capped at 48 columns); the burn
 * pulse runs every 10 ticks (one bounded entity query over a
 * 6 by 3.5 by 6 box, plus one UUID resolve per victim tracked
 * from the previous pulse, bounded by the crowd standing in a
 * 3 block lane) and the particle drift every 4 ticks. No
 * whole-level scans, no allocations in the tick path beyond
 * the pulse's bookkeeping set. Particle budget per activation:
 * 10 at start plus at most 40 drift pulses times 3 = 120,
 * total at most 130 particles.
 *
 * State tag keys: "lx", "ly", "lz" (the caster's last recorded
 * block position), "placed" (scorched columns so far, capped
 * at 48), "burned" (compound: UUID string to 1, the victims
 * currently carrying the BURNING_TIME modifier). No block list
 * is stored: placed blocks are permanent and never revisited.
 */
public final class AshenAdvanceAbility implements MobAbility {

    static final int DURATION_TICKS = 160;
    private static final int PULSE_INTERVAL = 10;
    private static final int MAX_COLUMNS = 48;
    private static final Identifier BURNING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ashen_advance_burning");

    @Override
    public String id() {
        return "ashen_advance";
    }

    @Override
    public String displayName() {
        return "Ashen Advance";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround() && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        BlockPos pos = mob.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("lx", pos.getX());
        state.putInt("ly", pos.getY());
        state.putInt("lz", pos.getZ());
        state.putInt("placed", 0);
        state.put("burned", new CompoundTag());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ASH, mob.getX(), mob.getY() + 1.4, mob.getZ(),
                6, 0.5, 0.6, 0.5, 0.03);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + 0.9, mob.getZ(),
                4, 0.4, 0.5, 0.4, 0.03);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 0.8f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // The trail: scorch the column the caster just left.
        BlockPos pos = mob.blockPosition();
        int lx = state.getIntOr("lx", pos.getX());
        int ly = state.getIntOr("ly", pos.getY());
        int lz = state.getIntOr("lz", pos.getZ());
        if (pos.getX() != lx || pos.getY() != ly || pos.getZ() != lz) {
            if (ctx.mobGriefingAllowed() && state.getIntOr("placed", 0) < MAX_COLUMNS) {
                scorchColumn(level, new BlockPos(lx, ly, lz));
                state.putInt("placed", state.getIntOr("placed", 0) + 1);
            }
            state.putInt("lx", pos.getX());
            state.putInt("ly", pos.getY());
            state.putInt("lz", pos.getZ());

            if (ticks % 4 == 0) {
                // Ash and soul fire streaming off the fresh scorch.
                level.sendParticles(ParticleTypes.ASH, lx + 0.5, ly + 0.6, lz + 0.5,
                    2, 0.4, 0.3, 0.4, 0.02);
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, lx + 0.5, ly + 0.5, lz + 0.5,
                    1, 0.3, 0.25, 0.3, 0.02);
            }
        }

        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the burn pulse runs every 10 ticks only.
        }
        burnPulse(ctx, level, state);
    }

    /**
     * Scorches one trail column (see the class comment for the
     * two placements and their safety checks). The blocks are
     * placed for good: nothing records them for removal.
     */
    private static void scorchColumn(ServerLevel level, BlockPos feetPos) {
        BlockPos floorPos = feetPos.below();
        BlockState floor = level.getBlockState(floorPos);
        if (floor.isAir() || floor.is(Blocks.BLACKSTONE)) {
            return;
        }
        if (level.getBlockEntity(floorPos) != null) {
            return; // Never overwrite containers or other block entities.
        }
        if (floor.getDestroySpeed(level, floorPos) < 0.0f) {
            return; // Never touch unbreakable blocks.
        }
        level.setBlock(floorPos, Blocks.BLACKSTONE.defaultBlockState(), Block.UPDATE_ALL);

        // The bloom: fire over the fresh blackstone, when the
        // feet space is free for it and the floor holds it up.
        BlockState atFeet = level.getBlockState(feetPos);
        if (atFeet.canBeReplaced() && level.getBlockEntity(feetPos) == null
            && level.getBlockState(floorPos).isFaceSturdy(level, floorPos, Direction.UP)) {
            level.setBlock(feetPos, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * The burn pulse: enemies standing on or in the trail catch
     * fire and burn long; the BURNING_TIME modifier tracks
     * whoever is on the lane right now. This runs in both
     * terrain modes: the lane exists whether this ability laid
     * it or the nether did.
     */
    private void burnPulse(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        Mob mob = ctx.mob();
        CompoundTag previous = state.getCompoundOrEmpty("burned");
        CompoundTag burned = new CompoundTag();
        Set<String> current = new HashSet<>();
        List<LivingEntity> victims = WitherSkeletonAbilitySupport.enemiesInBox(
            ctx, mob.getX(), mob.getY(), mob.getZ(), 3.0, 1.0, 2.5);
        for (LivingEntity victim : victims) {
            if (!standingOnTrail(level, victim)) {
                continue;
            }
            victim.igniteForSeconds(4.0f);
            AttributeInstance burning = victim.getAttribute(Attributes.BURNING_TIME);
            if (burning != null) {
                burning.addOrUpdateTransientModifier(new AttributeModifier(
                    BURNING_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                String key = victim.getUUID().toString();
                burned.putInt(key, 1);
                current.add(key);
            }
        }
        // Anyone tracked last pulse but off the lane now loses
        // the doubled burn.
        for (String key : previous.keySet()) {
            if (!current.contains(key)) {
                removeBurning(level, key);
            }
        }
        // Replacing the tag prunes every victim that left the trail.
        state.put("burned", burned);
    }

    /** Whether the victim stands on blackstone or in fire: on the burning trail. */
    private static boolean standingOnTrail(ServerLevel level, LivingEntity victim) {
        BlockPos feet = victim.blockPosition();
        if (level.getBlockState(feet).is(Blocks.FIRE)) {
            return true;
        }
        return level.getBlockState(feet.below()).is(Blocks.BLACKSTONE);
    }

    /** Removes the BURNING_TIME modifier from the entity with the given UUID string, if it still exists. */
    private static void removeBurning(ServerLevel level, String uuidKey) {
        try {
            Entity entity = level.getEntity(UUID.fromString(uuidKey));
            if (entity instanceof LivingEntity living) {
                AttributeInstance burning = living.getAttribute(Attributes.BURNING_TIME);
                if (burning != null) {
                    burning.removeModifier(BURNING_ID);
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
        // Every victim still tracked loses the doubled burn. The
        // blackstone and fire, when placed, are permanent terrain
        // and are deliberately NOT touched here (terrain rule,
        // see the class comment).
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag burned = ctx.state().getCompoundOrEmpty("burned");
        for (String key : burned.keySet()) {
            removeBurning(level, key);
        }
    }
}
