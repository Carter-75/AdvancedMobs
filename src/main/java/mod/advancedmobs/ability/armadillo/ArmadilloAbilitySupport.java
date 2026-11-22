package mod.advancedmobs.ability.armadillo;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * ArmadilloAbilitySupport: helpers shared by the Armadillo's
 * 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package: the Rollout's bounce box, the Burrow Blitz
 * eruption, the Armored Convoy's pup contact checks): a
 * living entity counts as an enemy of the caster when it is
 * alive, is not the caster, is not riding the caster or
 * being ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record),
 * and is either the caster's current combat target or
 * another Mob. Players are only affected when they are the
 * caster's current target and are in survival or adventure
 * mode, so area abilities never grief creative or spectator
 * players who happen to stand nearby. This is the same rule
 * the other implemented mobs' abilities use; it is
 * duplicated here because those classes are package-private
 * to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the
 * caster when it is alive, is not the caster, and either
 * side's war data lists the other as an ally (the
 * either-direction check the Breeze, Bee, and Wolf packages
 * use). Armored Convoy registers its pups with it.
 *
 * SCUTE PARTICLE: SCUTE_FLECK is the design's scute item
 * particle: an ItemParticleOption carrying the real
 * armadillo scute item (Items.ARMADILLO_SCUTE, verified in
 * the 26.2 jar), pre-built once as a static final so no
 * ability allocates particle options in a hot path.
 *
 * BALL FORM: the armadillo's ball is not a custom render
 * hack: vanilla carries a synced ArmadilloState (IDLE,
 * ROLLING, SCARED, UNROLLING) on the entity, switched with
 * Armadillo.switchToState. ROLLING is the tucked ball;
 * SCARED is the hunkered shell. The armadillo's brain can
 * wander out of a state on its own (its scare checks run on
 * an interval), so the roll and shield abilities re-assert
 * their state whenever getState() shows a different one,
 * the Panda roll-flag refresh pattern.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): NO ability in this
 * package places or breaks blocks, so the mobGriefing gate
 * does not apply anywhere. Burrow Blitz is particle theater
 * over a teleport (the Warden Burrow Ambush pattern); the
 * Rollout and the convoy pups bounce through the real
 * BOUNCINESS attribute, so vanilla emits the bounce game
 * event for their collisions on its own. BLOCK and
 * DUST_PILLAR particles carry a ground block's state; they
 * are not placed blocks.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to
 * a victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance (a mob that lacks the
 * attribute simply never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans.
 */
final class ArmadilloAbilitySupport {

    /** The scute item particle option (see the class comment). */
    static final ItemParticleOption SCUTE_FLECK =
        new ItemParticleOption(ParticleTypes.ITEM, Items.ARMADILLO_SCUTE);

    private ArmadilloAbilitySupport() {}

    /** Whether the given living entity is an enemy of the context mob. */
    static boolean isEnemy(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (candidate.getVehicle() == self || self.getVehicle() == candidate) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return false;
        }
        if (candidate == ctx.target()) {
            return !(candidate instanceof Player player) || (!player.isCreative() && !player.isSpectator());
        }
        return candidate instanceof Mob;
    }

    /** Whether the given living entity is an ally of the context mob (either-direction record). */
    static boolean isAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob candidateMob
            && MobWarData.get(candidateMob).isAlly(self.getUUID());
    }

    /**
     * All enemies of the context mob inside a box of the given
     * horizontal radius and vertical reach around a center point.
     * One bounded entity query; call on a pulse interval or a
     * one-shot moment, not per tick.
     */
    static List<LivingEntity> enemiesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                           double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isEnemy(ctx, e));
    }

    /** All enemies of the context mob within the given radius of the mob itself. */
    static List<LivingEntity> enemiesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return enemiesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /**
     * Sets the entity's velocity straight at the given point at
     * the given speed (capped so it never overshoots the point)
     * and returns the distance to the point before the move
     * (the Phantom / Dolphin pattern). Armored Convoy steers
     * its pups this way: a baby armadillo's vanilla brain
     * never charges an enemy on its own.
     */
    static double steerToward(LivingEntity entity, double x, double y, double z, double speed) {
        double dx = x - entity.getX();
        double dy = y - entity.getY();
        double dz = z - entity.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) {
            entity.setDeltaMovement(0.0, 0.0, 0.0);
            return 0.0;
        }
        double step = Math.min(speed, dist);
        entity.setDeltaMovement(dx / dist * step, dy / dist * step, dz / dist * step);
        entity.hurtMarked = true;
        return dist;
    }

    /**
     * Finds the feet height (the top of the highest solid,
     * non-fluid block with air directly above it) in the
     * column at x/z, scanning from 3 blocks above the hint
     * down to 8 blocks below it: at most 12 block reads, and
     * callers run it once per placement, never per tick.
     * Returns the hint unchanged when the column has no usable
     * surface in range (the Camel package's helper, same
     * contract).
     */
    static double findGroundY(ServerLevel level, double x, double yHint, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(yHint) + 3;
        int bottom = (int) Math.floor(yHint) - 8;
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockState(pos.above()).isAir()) {
                return y + 1.0;
            }
        }
        return yHint;
    }

    /** Stores a spawned entity's UUID in the state tag's named list (one compound per entity: "id"). */
    static void trackEntity(CompoundTag state, String key, Entity entity) {
        ListTag list = state.getListOrEmpty(key);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, entity.getUUID());
        list.add(entry);
        state.put(key, list);
    }

    /** Resolves a tracked entity from its list entry, or null when gone. */
    static Entity resolveTracked(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /** A BLOCK particle option carrying the block state under the given position (dirt when there is none). */
    static BlockParticleOption groundBlock(ServerLevel level, double x, double y, double z) {
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
        BlockState state = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        return new BlockParticleOption(ParticleTypes.BLOCK, state);
    }

    /** A DUST_PILLAR option carrying the block state under the given position (dirt when there is none). */
    static BlockParticleOption groundDustPillar(ServerLevel level, double x, double y, double z) {
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
        BlockState state = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        return new BlockParticleOption(ParticleTypes.DUST_PILLAR, state);
    }

    /** Adds or refreshes a transient attribute modifier on the given entity; tolerates a missing attribute. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent or the attribute is missing. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
