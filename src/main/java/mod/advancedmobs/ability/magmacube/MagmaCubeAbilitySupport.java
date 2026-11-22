package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * MagmaCubeAbilitySupport: helpers shared by the Magma Cube's
 * 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the caster
 * or being ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and is
 * either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. placeFire() and
 * the ground conversion behind Lava Bloom are the two terrain
 * writers; both refuse containers and other block entities and
 * unbreakable blocks, and placeFire() additionally requires a
 * dry, replaceable spot with a sturdy block beneath, so fire
 * never floats and never appears inside water.
 *
 * DISPLAYS: createCrustDisplay() builds one Obsidian Crust
 * plate, a block display showing a magma block, through
 * EntityType.loadEntityRecursive from NBT, the supported
 * configuration path in 26.2 (display setters are private).
 * The NBT sets full brightness (block and sky light 15) and a
 * teleport duration of 2 ticks so the plates glide as they
 * orbit. A display is a pure visual: no hitbox, no collision,
 * no health.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers every ability in this
 * package uses, on the caster or (Magma Mortar) on victims.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class MagmaCubeAbilitySupport {

    private MagmaCubeAbilitySupport() {}

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

    /**
     * Places one permanent fire block at the position, when the
     * spot can hold one: the current block is replaceable, dry
     * (no fluid), not already fire, has no block entity, and the
     * block below has a sturdy top face for the fire to sit on.
     * Uses BaseFireBlock.getState, the same state vanilla picks
     * (soul fire on soul soil, normal fire elsewhere). Returns
     * whether a fire was placed. Callers must already have
     * checked AbilityContext.mobGriefingAllowed().
     */
    static boolean placeFire(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (current.is(Blocks.FIRE) || current.is(Blocks.SOUL_FIRE)) {
            return false;
        }
        if (!current.canBeReplaced() || !current.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        return level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
    }

    /**
     * The topmost convertible ground block in the column at
     * (x, z), scanning from one block above the base height down
     * to two below it, or null when the column has no convertible
     * ground in that window. Used by Lava Bloom to find the
     * surface each pool and crust block replaces.
     */
    static BlockPos findGround(ServerLevel level, int x, int z, int baseY) {
        for (int y = baseY + 1; y >= baseY - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isConvertibleGround(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Whether the block at the position may be converted into a
     * lava source or a magma block by Lava Bloom: a real solid
     * block (collision shape present, so plants and other
     * non-solids are scanned past to the ground under them), dry
     * itself and with no fluid directly above, no block entity
     * (containers are never touched), breakable (destroy speed
     * not negative), and not already lava or magma block. Only
     * called when mobGriefing is on.
     */
    static boolean isConvertibleGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }

    /**
     * Creates (but does not add to the level) one Obsidian Crust
     * plate: a block display showing a magma block at the given
     * scale, centered on its snap position, at full brightness,
     * gliding with a teleport duration of 2 ticks. Returns null
     * if the entity could not be created. See the DISPLAYS note
     * in the class comment.
     */
    static Entity createCrustDisplay(ServerLevel level, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.MAGMA_BLOCK.defaultBlockState()));
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
    }

    /** Adds or refreshes a transient attribute modifier on the given entity. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute,
                               net.minecraft.resources.Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    /** A display transformation compound: centered translation, uniform scale, identity rotations. */
    private static CompoundTag transformation(float scale) {
        float half = -scale / 2.0f;
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(half, half, half));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        return transformation;
    }

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }
}
