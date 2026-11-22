package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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

import java.util.List;

/**
 * Gravemud Mire (Zombie ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the zombie slams the ground and a two-block-high ring wall
 * of churned dirt and gravel erupts around its target, penning it in.
 * Enemies standing inside the ring are mired: repeated SLOWNESS and
 * WEAKNESS while the mire lasts. The zombie itself wades through its own
 * mire at full pace.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see IMPLEMENTATION-RULES.md
 * rule 6 and the MobAbility contract): terrain changes from abilities are
 * PERMANENT and gated by the vanilla mobGriefing game rule, checked
 * through AbilityContext.mobGriefingAllowed(). This ability has two modes:
 * - mobGriefing ON: the dirt and gravel ring is placed and STAYS. There
 *   is no restore: end() touches no blocks, and nothing is scheduled to
 *   remove the wall later. The mire's debuff zone still lasts only the
 *   ability duration; the wall itself is just terrain afterwards.
 * - mobGriefing OFF: no blocks are placed at all. The ability runs as a
 *   pure debuff zone for its duration (same pulses, same visuals, no
 *   wall), and the world is left exactly as it was.
 * (This ability originally placed temporary blocks and restored them in
 * end(), with a TickScheduler backup for caster death. The Nov 21, 2026
 * standing rule superseded that design; the restore machinery and the
 * backup were removed, not adapted.)
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 12 blocks (distance
 *   squared 144), and standing on the ground.
 * - Duration: exactly 140 ticks (7 seconds) from start(). This is the
 *   debuff zone's lifetime; in mobGriefing mode the wall outlives it.
 * - The wall (mobGriefing mode only): a Chebyshev ring of radius 3 around
 *   the target's block position at start() (positions where
 *   max(|dx|, |dz|) == 3), two layers high (the target's feet level and
 *   one above). Bottom layer is always dirt; top layer is gravel or
 *   dirt, coin flip per position. At most 48 blocks change (24 ring
 *   positions times 2 layers).
 * - Debuff pulse every 10 ticks: enemies (ZombieAbilitySupport rules)
 *   within horizontal radius 3.6 of the ring center and between 1 below
 *   and 3 above its base level get SLOWNESS II (amplifier 1) and
 *   WEAKNESS I (amplifier 0), 15 ticks each, refreshed by the next pulse.
 * - The zombie gets MOVEMENT_EFFICIENCY add_value +1.0 for the duration
 *   (modifier id advancedmobs:gravemud_mire_efficiency, removed in end()),
 *   so slow terrain never hinders it, and the pulse never targets it.
 *
 * TERRAIN SAFETY (binding, both the placement checks kept from the
 * original design): a position is only changed when ALL of these hold:
 * the current block can be replaced (BlockState.canBeReplaced, so air,
 * grass, flowers, snow layers and similar), it has no block entity
 * (containers, signs, spawners and so on are never touched), it is not
 * unbreakable (destroy speed not negative, so no bedrock or barriers),
 * and the block below is face-sturdy in the live level (so the wall never
 * floats; the top layer checks against the bottom block placed just
 * before it). Replaced vegetation is NOT dropped as items and does not
 * come back: the change is permanent, per the terrain rule.
 *
 * WHAT IT LOOKS LIKE: on start, 24 FALLING_DUST particles of dirt burst
 * over the ring area and 12 mud-brown DUST motes (color 0.42, 0.33, 0.22,
 * scale 1.4) hang in the air. Every 20 ticks during the mire, 4 more
 * FALLING_DUST particles sift down inside the ring.
 *
 * PERFORMANCE: block work happens once in start() (at most 48 positions,
 * mobGriefing mode only) and never again; end() only removes the
 * attribute modifier. tick() returns immediately except every 10th tick
 * (debuff pulse, one bounded entity query over a 7.2 by 4 by 7.2 box)
 * and every 20th tick (4 particles). Particle budget per activation:
 * 36 at start plus at most 7 interior pulses times 4 = 28, total at most
 * 64 particles.
 *
 * State tag keys: "cx", "cy", "cz" (ring center block pos). No block
 * list is stored: placed blocks are permanent and never revisited.
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
        net.minecraft.nbt.CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());

        // Terrain (mobGriefing mode only): raise the permanent ring wall.
        // With mobGriefing off, no block is touched and the ability is a
        // debuff zone only. See the TERRAIN RULE in the class comment.
        if (ctx.mobGriefingAllowed()) {
            placeRingWall(level, center, ctx);
        }

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
    }

    /**
     * Places the permanent two-layer ring wall around the center, keeping
     * every placement safety check from the original design (replaceable
     * only, no block entities, never unbreakable, never floating). The
     * blocks are placed for good: nothing records them for removal.
     */
    private static void placeRingWall(ServerLevel level, BlockPos center, AbilityContext ctx) {
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
                    level.setBlock(pos, placed, Block.UPDATE_ALL);
                }
            }
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
        net.minecraft.nbt.CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;

        // Debuff pulse: enemies inside the ring are mired. This runs in
        // both terrain modes; the zone exists whether or not the wall
        // was allowed to rise.
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
        // Only the caster's own modifier is undone. The ring wall, when
        // placed, is permanent terrain and is deliberately NOT touched
        // here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
    }

}
