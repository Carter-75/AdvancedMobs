package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * WitherSkeletonAbilitySupport: helpers shared by the Wither
 * Skeleton's 5 abilities.
 *
 * ENEMY RULE (used by every area effect in this package: the
 * Ashen Advance burn pulse and the Wither Herald darkness
 * pulses, and by the Wither Brand's victim checks): a living
 * entity counts as an enemy of the caster when it is alive, is
 * not the caster, is not riding the caster or being ridden by
 * it, is not in the caster's ally set (MobWarData.isAlly, the
 * war system's alliance record), and is either the caster's
 * current combat target or another Mob. Players are only
 * affected when they are the caster's current target and are in
 * survival or adventure mode, so area abilities never grief
 * creative or spectator players who happen to stand nearby.
 * This is the same rule every other implemented mob's abilities
 * use; it is duplicated here because those classes are
 * package-private to their own packages.
 *
 * DISPLAY HELPER: item displays are configured through NBT in
 * 26.2 (their setters are private), so the helper builds the
 * entity through EntityType.loadEntityRecursive, the supported
 * spawn path already used by Emerald Shrapnel and Grave
 * Eruption. NBT keys verified against the real 26.2 jar: item
 * displays take "item" (an item stack compound) and
 * "item_display" (the transform context name), and every
 * display takes a "transformation" compound with translation,
 * scale, left_rotation and right_rotation float lists
 * (quaternions, x y z w). The caller positions the returned
 * entity with snapTo and adds it to the level.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with a
 * tight AABB built from the given radius, and callers only run
 * them on a pulse interval (documented per ability), never as
 * whole-level scans.
 */
final class WitherSkeletonAbilitySupport {

    private WitherSkeletonAbilitySupport() {}

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
     * One bounded entity query; call on a pulse interval, not per
     * tick.
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
     * Creates (but does not add to the level) an item display
     * showing the given item stack tag at the given uniform
     * scale in the given transform context (for example
     * "fixed"). The translation centers the item model on the
     * display's snap point: an item display's pivot is the model
     * center offset by the transformation, so the item is
     * shifted back by half its scale on every axis. Returns null
     * if the entity could not be created.
     */
    static Entity createItemDisplay(ServerLevel level, CompoundTag itemStackTag, float scale,
                                    String itemDisplayContext) {
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemStackTag);
        tag.putString("item_display", itemDisplayContext);
        float half = -scale / 2.0f;
        tag.put("transformation", transformation(scale, half));
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** A display transformation compound: translation, scale, identity rotations. */
    private static CompoundTag transformation(float scale, float half) {
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
