package mod.advancedmobs.ability.warden;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * WardenAbilitySupport: helpers shared by the Warden's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package:
 * the Cataclysm Boom rings, the Sculk Bloom slow pulse, the Heart
 * of the Deep darkness pulse, the Shrieking Brood death pops, the
 * Burrow Ambush eruption): a living entity counts as an enemy of
 * the caster when it is alive, is not the caster, is not riding
 * the caster or being ridden by it, is not in the caster's ally
 * set (MobWarData.isAlly, the war system's alliance record), and
 * is either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area abilities
 * never grief creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented mobs'
 * abilities use; it is duplicated here because those classes are
 * package-private to their own packages.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only real block changes
 * in this package are the Sculk Bloom's sculk, sculk vein, and
 * sculk catalyst conversions. They are PERMANENT and only happen
 * when AbilityContext.mobGriefingAllowed() is true, through the
 * isDiggable() safety check below (never containers or other
 * block entities, never unbreakable blocks, never fluids).
 *
 * DISPLAYS: createVeinDisplay() builds one block display through
 * EntityType.loadEntityRecursive from NBT, the supported
 * configuration path in 26.2 (display setters are private). The
 * NBT sets full brightness (block and sky light 15) and a
 * teleport duration of 2 ticks so per-tick snapTo calls glide
 * instead of snapping (the design's "slowly wave with
 * interpolation"). A display is a pure visual: no hitbox, no
 * collision.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op. Both tolerate a missing
 * attribute instance.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class WardenAbilitySupport {

    private WardenAbilitySupport() {}

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

    /** All enemies of the context mob within the given radius of the mob itself. */
    static List<LivingEntity> enemiesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return enemiesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /**
     * Whether the block at the position may be converted by the
     * Sculk Bloom: a real block (not air), dry (no fluid), with
     * no block entity (containers and other block entities are
     * never touched), and breakable (destroy speed not negative,
     * so bedrock and its kin are refused). Callers must already
     * have checked AbilityContext.mobGriefingAllowed().
     */
    static boolean isDiggable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }

    /** Whether the block state belongs to the sculk family the bloom lays down. */
    static boolean isSculkFamily(BlockState state) {
        return state.is(Blocks.SCULK) || state.is(Blocks.SCULK_VEIN) || state.is(Blocks.SCULK_CATALYST);
    }

    /**
     * The sculk vein block state lying flat on top of a floor
     * block: a multiface state with only the DOWN face set (the
     * face attached to the block below), built once and shared.
     */
    static BlockState floorVeinState() {
        return Blocks.SCULK_VEIN.defaultBlockState()
            .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), Boolean.TRUE);
    }

    /**
     * Creates (but does not add to the level) one Heart of the
     * Deep tendril: a block display showing a sculk vein at the
     * given scale, centered on its snap position, at full
     * brightness, gliding with a teleport duration of 2 ticks.
     * Returns null if the entity could not be created. See the
     * DISPLAYS note in the class comment.
     */
    static Entity createVeinDisplay(ServerLevel level, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(Blocks.SCULK_VEIN.defaultBlockState()));
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
    }

    /** Resolves a tracked entity by UUID against the level, or null when gone. */
    static Entity resolve(ServerLevel level, UUID id) {
        return id == null ? null : level.getEntity(id);
    }

    /** Adds or refreshes a transient attribute modifier on the given entity. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
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
