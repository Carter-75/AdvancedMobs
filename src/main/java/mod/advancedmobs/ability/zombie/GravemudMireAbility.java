package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.util.TickScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.nbt.NbtUtils;

import java.util.List;

/**
 * Gravemud Mire (Zombie ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the zombie slams the ground and a two-block-high ring wall
 * of churned dirt and gravel erupts around its target, penning it in.
 * Enemies standing inside the ring are mired: repeated SLOWNESS and
 * WEAKNESS while the wall stands. The zombie itself wades through its own
 * mire at full pace. When the ability ends, every placed block is removed
 * and the original terrain is restored exactly.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 12 blocks (distance
 *   squared 144), and standing on the ground.
 * - Duration: exactly 140 ticks (7 seconds) from start().
 * - The wall: a Chebyshev ring of radius 3 around the target's block
 *   position at start() (positions where max(|dx|, |dz|) == 3), two layers
 *   high (the target's feet level and one above). Bottom layer is always
 *   dirt; top layer is gravel or dirt, coin flip per position. At most 48
 *   blocks change (24 ring positions times 2 layers).
 * - Debuff pulse every 10 ticks: enemies (ZombieAbilitySupport rules)
 *   within horizontal radius 3.6 of the ring center and between 1 below
 *   and 3 above its base level get SLOWNESS II (amplifier 1) and
 *   WEAKNESS I (amplifier 0), 15 ticks each, refreshed by the next pulse.
 * - The zombie gets MOVEMENT_EFFICIENCY add_value +1.0 for the duration
 *   (modifier id advancedmobs:gravemud_mire_efficiency, removed in end()),
 *   so slow terrain never hinders it, and the pulse never targets it.
 *
 * TERRAIN SAFETY (binding): a position is only changed when ALL of these
 * hold: the current block can be replaced (BlockState.canBeReplaced, so
 * air, grass, flowers, snow layers and similar), it has no block entity
 * (containers, signs, spawners and so on are never touched), it is not
 * unbreakable (destroy speed not negative, so no bedrock or barriers),
 * and the block below is face-sturdy in the live level (so the wall never
 * floats; the top layer checks against the bottom block placed just
 * before it). Every change is recorded in the state tag ("blocks": pos,
 * original state NBT, placed state NBT) BEFORE the block is set, and
 * start() drops nothing: replaced vegetation simply returns at restore.
 *
 * RESTORE (binding): end() restores every recorded position, top layer
 * first (reverse record order, so a restored bottom block can never drop
 * a still-standing gravel block). A position is only restored when its
 * current block is still exactly the block this ability placed, so a
 * player or another system that changed the spot during the 7 seconds is
 * never overwritten. As a backup for the caster dying mid-ability (when
 * the framework never calls end()), start() also schedules the same
 * check-and-restore through TickScheduler at duration + 2 ticks, holding
 * a deep copy of the change list; the check makes the two paths safely
 * idempotent, so whichever runs second changes nothing.
 *
 * WHAT IT LOOKS LIKE: on start, 24 FALLING_DUST particles of dirt burst
 * over the ring area and 12 mud-brown DUST motes (color 0.42, 0.33, 0.22,
 * scale 1.4) hang in the air. Every 20 ticks during the mire, 4 more
 * FALLING_DUST particles sift down inside the ring.
 *
 * PERFORMANCE: block work happens once in start() and once in end() (at
 * most 48 positions each). tick() returns immediately except every 10th
 * tick (debuff pulse, one bounded entity query over a 7.2 by 4 by 7.2
 * box) and every 20th tick (4 particles). Particle budget per activation:
 * 36 at start plus at most 7 interior pulses times 4 = 28, total at most
 * 64 particles.
 *
 * State tag keys: "cx", "cy", "cz" (ring center block pos), "blocks"
 * (the change list described above).
 */
public final class GravemudMireAbility implements MobAbility {

    static final int DURATION_TICKS = 140;
    private static final int RING_RADIUS = 3;
    private static final Identifier EFFICIENCY_ID = Identifier.fromNamespaceAndPath("advancedmobs", "gravemud_mire_efficiency");
    // 26.2 DustParticleOptions takes an RGB int color: 0.42, 0.33, 0.22 as 0x6B5438.
    private static final DustParticleOptions MUD_DUST = new DustParticleOptions(0x6B5438, 1.4f);
    private static final BlockParticleOption DIRT_FALLING_DUST = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "gravemud_mire";
    }

    @Override
    public String displayName() {
        return "Gravemud Mire";
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

        ListTag changes = new ListTag();
        // Bottom layer first, then the top layer, so the top layer's
        // support check sees the bottom blocks this ability just placed.
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -RING_RADIUS; dx <= RING_RADIUS; dx++) {
                for (int dz = -RING_RADIUS; dz <= RING_RADIUS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != RING_RADIUS) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState current = level.getBlockState(pos);
                    if (!current.canBeReplaced()) {
                        continue;
                    }
                    if (level.getBlockEntity(pos) != null) {
                        continue; // Never overwrite containers or other block entities.
                    }
                    if (current.getDestroySpeed(level, pos) < 0.0f) {
                        continue; // Never touch unbreakable blocks.
                    }
                    BlockPos belowPos = pos.below();
                    if (!level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
                        continue; // The wall never floats.
                    }
                    BlockState placed = dy == 0
                        ? Blocks.DIRT.defaultBlockState()
                        : (ctx.random().nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : Blocks.DIRT.defaultBlockState());
                    CompoundTag entry = new CompoundTag();
                    entry.putLong("pos", pos.asLong());
                    entry.put("old", NbtUtils.writeBlockState(current));
                    entry.put("new", NbtUtils.writeBlockState(placed));
                    changes.add(entry);
                    level.setBlock(pos, placed, Block.UPDATE_ALL);
                }
            }
        }
        state.put("blocks", changes);

        // The zombie wades through its own mire at full pace.
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // Eruption visuals: falling dirt dust over the ring, mud motes in the air.
        double cx = center.getX() + 0.5, cy = center.getY() + 0.5, cz = center.getZ() + 0.5;
        level.sendParticles(DIRT_FALLING_DUST, cx, cy + 0.5, cz, 24, 3.0, 1.0, 3.0, 0.05);
        level.sendParticles(MUD_DUST, cx, cy + 0.5, cz, 12, 2.8, 0.8, 2.8, 0.01);

        // Death backup: if the caster dies mid-ability the framework never
        // calls end(), so schedule the same idempotent restore. The list is
        // deep-copied so later state edits cannot corrupt the backup.
        if (level.getServer() != null && !changes.isEmpty()) {
            ListTag backup = changes.copy();
            TickScheduler.schedule(level.getServer(), DURATION_TICKS + 2, () -> restoreBlocks(level, backup));
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 10 != 0) {
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

        // Debuff pulse: enemies inside the ring are mired.
        List<LivingEntity> victims = ZombieAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 3.6, 1.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 1));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 15, 0));
        }

        if (ticks % 20 == 0) {
            level.sendParticles(DIRT_FALLING_DUST, cx, cy + 1.5, cz, 4, 2.5, 0.5, 2.5, 0.03);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.state().contains("blocks")) {
            restoreBlocks(level, ctx.state().getListOrEmpty("blocks"));
        }
    }

    /**
     * Restores every recorded position whose current block is still the
     * block this ability placed, top layer first (reverse record order).
     * Idempotent: safe to call from both end() and the TickScheduler
     * death backup; whichever runs second finds nothing left to restore.
     */
    private static void restoreBlocks(ServerLevel level, ListTag changes) {
        HolderGetter<net.minecraft.world.level.block.Block> blockGetter =
            level.registryAccess().lookupOrThrow(Registries.BLOCK);
        for (int i = changes.size() - 1; i >= 0; i--) {
            CompoundTag entry = changes.getCompoundOrEmpty(i);
            BlockPos pos = BlockPos.of(entry.getLongOr("pos", 0L));
            BlockState placed = NbtUtils.readBlockState(blockGetter, entry.getCompoundOrEmpty("new"));
            if (level.getBlockState(pos) != placed) {
                continue; // Someone else changed this spot; never overwrite them.
            }
            BlockState original = NbtUtils.readBlockState(blockGetter, entry.getCompoundOrEmpty("old"));
            level.setBlock(pos, original, Block.UPDATE_ALL);
        }
    }
}
