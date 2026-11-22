package mod.advancedmobs.ability.polarbear;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * PolarBearAbilitySupport: helpers shared by the Polar Bear's
 * 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the
 * caster or being ridden by it, is not in the caster's ally
 * set (MobWarData.isAlly, the war system's alliance record),
 * and is either the caster's current combat target or
 * another Mob. Players are only affected when they are the
 * caster's current target and are in survival or adventure
 * mode, so area abilities never grief creative or spectator
 * players who happen to stand nearby. This is the same rule
 * the other implemented mobs' abilities use; it is
 * duplicated here because those classes are package-private
 * to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the caster
 * when it is alive, is not the caster, and either side's war
 * data lists the other as an ally (the either-direction
 * check the Breeze and Bee packages use).
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the real block changes in
 * this package are the Blizzard Aura's snow layers and the
 * Icebreaker's frozen and shattered surface. Both are
 * PERMANENT and only happen when AbilityContext.
 * mobGriefingAllowed() is true, through the safety checks
 * below (never containers or other block entities, never
 * unbreakable blocks, never overwrite a block that is not
 * the exact air, snow, water, or ice cell the ability means
 * to change). With mobGriefing off, no block changes and
 * the non-terrain effects still run. The Deep Hibernate's
 * snow mound is a block DISPLAY entity, a pure visual with
 * no hitbox and no block change, so it is not gated.
 *
 * DISPLAYS: createSnowMoundDisplay() builds one block
 * display through EntityType.loadEntityRecursive from NBT,
 * the supported configuration path in 26.2 (display setters
 * are private). The NBT sets full brightness (block and sky
 * light 15) and a teleport duration of 2 ticks so per-tick
 * snapTo calls glide instead of snapping.
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
final class PolarBearAbilitySupport {

    private PolarBearAbilitySupport() {}

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

    /**
     * Whether the block at the position is a real block an
     * ability may change in place: not air, dry (no fluid),
     * no block entity (containers and other block entities
     * are never touched), and breakable (destroy speed not
     * negative, so bedrock and its kin are refused). Callers
     * must already have checked AbilityContext.
     * mobGriefingAllowed() and must only convert the exact
     * block types their spec names.
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

    /**
     * Whether the block at the position is a solid obstacle
     * that stops the Avalanche Charge: any block with a
     * non-empty collision shape (walls, fences, tree trunks).
     * Air, fluids, tall grass, and snow layers have empty or
     * passable collision for this purpose only when their
     * collision shape is empty; snow layers DO have a small
     * collision shape, so a deep drift can stop the charge,
     * which is intended.
     */
    static boolean isWallBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Creates (but does not add to the level) the Deep
     * Hibernate's snow mound: a block display showing a snow
     * block at the given scale, centered on its snap
     * position, at full brightness, gliding with a teleport
     * duration of 2 ticks. Returns null if the entity could
     * not be created. See the DISPLAYS note in the class
     * comment.
     */
    static Entity createSnowMoundDisplay(ServerLevel level, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(Blocks.SNOW_BLOCK.defaultBlockState()));
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** Stores an entity's UUID in the state tag under the given key. */
    static void storeEntityId(CompoundTag state, String key, Entity entity) {
        state.store(key, UUIDUtil.CODEC, entity.getUUID());
    }

    /** Resolves an entity stored by storeEntityId, or null when gone or never stored. */
    static Entity resolveStored(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
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
