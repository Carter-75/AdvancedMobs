package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * SlimeAbilitySupport: helpers shared by the Slime's 5
 * abilities.
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
 * packages. Split-off slimes are allied through the same ally
 * records, so the caster's own abilities never hit them.
 *
 * SUMMONS: spawnAlliedSlime() is the one summon path for this
 * package (Gelatinous Split). It creates the slime with
 * EntitySpawnReason.MOB_SUMMONED, finalizes the spawn, then
 * forces the requested size (Slime.finalizeSpawn randomizes
 * size, so the size is set AFTER it), allies the slime with
 * the caster in both directions, registers the pair in
 * SummonerTracker (which also tags the slime umw_summoned for
 * the natural spawn blocker), and orders it onto a target: the
 * same flow the other brood abilities in this mod use.
 *
 * DISPLAYS: createCrownDisplay() builds the Royal Jelly crown,
 * an item display showing a golden helmet (vanilla has no
 * crown item; a golden helmet at 1.3 scale is the closest
 * crown silhouette), through EntityType.loadEntityRecursive
 * from NBT, the supported configuration path in 26.2 (display
 * setters are private). The NBT sets full brightness (block
 * and sky light 15) and a teleport duration of 2 ticks so the
 * crown glides as it follows the slime's head. The display is
 * a pure visual: no hitbox, no collision, no health.
 *
 * TERRAIN: isConvertibleGround() is the placement safety
 * check for Slime Slick, the one real terrain change in this
 * package: a ground block may be converted to a slime block
 * only when it is a real block (not air, no fluid), has no
 * block entity (containers are never touched), and is
 * breakable (destroy speed not negative). Slime Slick only
 * calls it when AbilityContext.mobGriefingAllowed() is true.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers every ability in this
 * package uses, on the caster or (Slime Slick) on victims.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class SlimeAbilitySupport {

    private SlimeAbilitySupport() {}

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

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    static boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    /**
     * Spawns one allied slime of the given size at the given
     * spot (the caster's own position as fallback when the spot
     * is not free), allies it with the caster in both
     * directions, registers the pair in SummonerTracker, and
     * orders it onto the given target (the caster's live target
     * when null). Returns the slime, or null when it could not
     * be created or added. See the SUMMONS note in the class
     * comment.
     */
    static Slime spawnAlliedSlime(AbilityContext ctx, ServerLevel level, double spawnX, double spawnZ,
                                  int size, LivingEntity target) {
        Mob caster = ctx.mob();
        Slime slime = EntityTypes.SLIME.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (slime == null) {
            return null;
        }
        if (!isFreeSpot(level, slime, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        slime.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        slime.finalizeSpawn(level, level.getCurrentDifficultyAt(slime.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // finalizeSpawn randomizes a slime's size; the split
        // size is a design number, so it is forced after.
        slime.setSize(size, true);
        if (!level.addFreshEntity(slime)) {
            return null;
        }
        ctx.data().addAlly(slime.getUUID());
        MobWarData.get(slime).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(slime.getUUID(), caster.getUUID());
        LivingEntity order = target != null ? target : ctx.target();
        if (order != null && order != slime) {
            slime.setTarget(order);
        }
        return slime;
    }

    /**
     * Creates (but does not add to the level) the Royal Jelly
     * crown: an item display showing a golden helmet at 1.3
     * scale, centered on its snap position, at full brightness,
     * gliding with a teleport duration of 2 ticks. Returns null
     * if the entity could not be created. See the DISPLAYS note
     * in the class comment.
     */
    static Entity createCrownDisplay(ServerLevel level) {
        CompoundTag tag = new CompoundTag();
        CompoundTag item = new CompoundTag();
        item.putString("id", BuiltInRegistries.ITEM.getKey(Items.GOLDEN_HELMET).toString());
        item.putInt("count", 1);
        tag.put("item", item);
        tag.putString("item_display", "fixed");
        tag.put("transformation", transformation(1.3f, 1.3f, 1.3f, -0.65f, -0.65f, -0.65f));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
    }

    /**
     * Whether the block at the position may be converted into
     * a slime block by Slime Slick: a real, dry, breakable
     * block with no block entity, and not already a slime
     * block. Air and fluids are never converted, containers and
     * other block entities are never touched, and unbreakable
     * blocks (negative destroy speed) are never touched. Only
     * called when mobGriefing is on.
     */
    static boolean isConvertibleGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.SLIME_BLOCK) || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
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

    /** A display transformation compound: translation, scale, identity rotations. */
    private static CompoundTag transformation(float scaleX, float scaleY, float scaleZ,
                                              float transX, float transY, float transZ) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(transX, transY, transZ));
        transformation.put("scale", floatList(scaleX, scaleY, scaleZ));
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
