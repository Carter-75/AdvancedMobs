package mod.advancedmobs.ability.illusioner;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * IllusionerAbilitySupport: helpers shared by the
 * Illusioner's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the
 * caster or being ridden by it, is not in the caster's ally
 * set (MobWarData.isAlly, the war system's alliance record),
 * and is either the caster's current combat target or another
 * Mob. Players are only affected when they are the caster's
 * current target and are in survival or adventure mode, so
 * area abilities never grief creative or spectator players
 * who happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * MIRRORS: summonMirror() creates one real Illusioner as a
 * mirror image, on the design's summon sheet (the same sheet
 * Hall of Mirrors, Blindspot Barrage, and Shattered Image
 * Nova all use): MAX_HEALTH base set to 1.0 (a single point
 * of health) and ATTACK_DAMAGE base set to 0.0, both set
 * after finalizeSpawn so nothing re-rolls them. A mirror is
 * deliberately given NO target: a targetless illusioner never
 * engages its bow goal or its spell goals, so a mirror never
 * casts and never shoots on its own; the abilities steer the
 * mirrors by move control and loose any arrows in code (the
 * Hall's pantomime arrows are code arrows with base damage
 * 0.0, which is what "harmless arrows" means in code: the
 * arrow flies, sticks, and deals nothing). Team assignment
 * is both directions plus SummonerTracker, like every other
 * summon in this mod.
 *
 * ARROWS: fireArrow() spawns one real vanilla Arrow owned by
 * the caster from an arbitrary point (the caster's eye, a
 * mirror's hands, a floating bow), aimed at the target's
 * mid-body. A fired arrow is a free projectile once spawned:
 * no ability tracks or cleans it up.
 *
 * DISPLAYS: createItemDisplay() builds one item display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private), following the Witch package's factory: the stack
 * is written through the ItemStack codec, the display context
 * is "fixed", brightness is full (block and sky light 15),
 * and teleport_duration is 2 ticks so per-tick teleports glide
 * instead of snapping. A display is a pure visual: no hitbox,
 * no collision, no health; abilities that let one be struck
 * track the strikes in code by proximity (the Phantom Archer
 * Ring does).
 *
 * TERRAIN: no ability in this package places or breaks
 * blocks. The mirrors are summoned entities, the bows are
 * display entities, the blinks and shuffles are teleports and
 * move-control steering, and the novas are particles, damage,
 * and effects. The standing terrain rule's mobGriefing gate
 * therefore does not apply anywhere in this package, and each
 * ability's comment says so.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses,
 * null-tolerant like the other packages' copies.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and callers
 * only run them on a pulse interval or a one-shot moment,
 * never as whole-level scans.
 */
final class IllusionerAbilitySupport {

    private IllusionerAbilitySupport() {}

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
     * horizontal radius and vertical reach around a center
     * point. One bounded entity query; call on a pulse
     * interval or a one-shot moment, not per tick.
     */
    static List<LivingEntity> enemiesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                           double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isEnemy(ctx, e));
    }

    /** All enemies inside a box around the caster itself. See enemiesInBox. */
    static List<LivingEntity> enemiesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return enemiesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
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

    /**
     * Summons one mirror image (see the MIRRORS note in the
     * class comment) at the given position and yaw, allied to
     * the caster. Returns the mirror, or null when the spawn
     * failed.
     */
    static Illusioner summonMirror(AbilityContext ctx, double x, double y, double z, float yaw) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return null;
        }
        Mob caster = ctx.mob();
        Illusioner mirror = EntityTypes.ILLUSIONER.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (mirror == null) {
            return null;
        }
        mirror.snapTo(x, y, z, yaw, 0.0f);
        mirror.finalizeSpawn(level, level.getCurrentDifficultyAt(mirror.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // The mirror's sheet (see the class comment): base
        // values, set after finalizeSpawn so nothing re-rolls
        // them.
        AttributeInstance health = mirror.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(1.0);
        }
        mirror.setHealth(1.0f);
        AttributeInstance attack = mirror.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(0.0);
        }
        if (!level.addFreshEntity(mirror)) {
            return null;
        }
        ctx.data().addAlly(mirror.getUUID());
        MobWarData.get(mirror).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(mirror.getUUID(), caster.getUUID());
        return mirror;
    }

    /**
     * Fires one real vanilla Arrow owned by the caster from
     * the given point at the target's mid-body, or straight
     * along the caster's look angle when the target is null.
     * The arrow's base damage, speed, and inaccuracy are the
     * caller's numbers; a base damage of 0.0 is the harmless
     * pantomime arrow of the mirror designs. Returns true when
     * an arrow was spawned.
     */
    static boolean fireArrow(AbilityContext ctx, Vec3 from, LivingEntity target,
                             double baseDamage, float speed, float inaccuracy) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return false;
        }
        Mob mob = ctx.mob();
        Vec3 dir;
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - from.x,
                               (target.getY() + target.getBbHeight() * 0.6) - from.y,
                               target.getZ() - from.z);
            dir = to.lengthSqr() > 1.0e-6 ? to.normalize() : mob.getLookAngle();
        } else {
            dir = mob.getLookAngle();
        }
        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(baseDamage);
        arrow.snapTo(from.x, from.y, from.z,
            (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)), 0.0f);
        arrow.shoot(dir.x, dir.y, dir.z, speed, inaccuracy);
        return level.addFreshEntity(arrow);
    }

    /**
     * Creates (but does not add to the level) one item display
     * showing the given stack at the given uniform scale, at
     * full brightness, display context "fixed". Returns null
     * if the entity could not be created. See the DISPLAYS
     * note in the class comment.
     */
    static Entity createItemDisplay(ServerLevel level, ItemStack stack, float scale) {
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        Tag itemTag = encoded.result().orElse(null);
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemCompound);
        tag.putString("item_display", "fixed");
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0f, 0.0f, 0.0f));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** An NBT float list of the given values, for display transformation compounds. */
    private static net.minecraft.nbt.ListTag floatList(float... values) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (float value : values) {
            list.add(net.minecraft.nbt.FloatTag.valueOf(value));
        }
        return list;
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
