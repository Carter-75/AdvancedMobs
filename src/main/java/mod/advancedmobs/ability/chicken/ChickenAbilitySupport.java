package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
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
import net.minecraft.world.entity.animal.chicken.ChickenSoundVariant;
import net.minecraft.world.entity.animal.chicken.ChickenSoundVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * ChickenAbilitySupport: helpers shared by the Chicken's 5 abilities.
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
 * CHICKEN SHEET (verified against the real 26.2 jar): the
 * chicken's createAttributes adds only MAX_HEALTH 4.0 and
 * MOVEMENT_SPEED 0.25 on top of the living base set, and
 * LivingEntity.createLivingAttributes carries NO
 * ATTACK_DAMAGE and NO ATTACK_SPEED instance. Every damage
 * number in this package is therefore a flat code value
 * dealt through a damage source credited to the chicken
 * (the Cow and Sheep packages' treatment), and the golden
 * chick's boosted attack is likewise code damage in its
 * babysit pass, never an attribute.
 *
 * DAMAGE SOURCE: thrownSource() builds the design's
 * projectile-typed damage: a DamageSource carrying the
 * vanilla THROWN damage type (the type a real thrown egg
 * registers its hit under) with the caster as both the
 * direct entity and the attacker, so barrage kills credit
 * the chicken while the type's rules and death message stay
 * the thrown type's. Built through the public
 * DamageSource(Holder, Entity, Entity) constructor off the
 * type holder of the source DamageSources.thrown returns,
 * the Sheep package's typed-source pattern. Callers build
 * it on a burst moment only, never per tick.
 *
 * SOUNDS: 26.2 has no plain CHICKEN_AMBIENT constant
 * (verified against the jar); chicken voices are variant
 * based. chickenSound() resolves the CLASSIC variant's
 * adult ambient or hurt sound as a Holder<SoundEvent> from
 * SoundEvents.CHICKEN_SOUNDS for Level.playSound's Holder
 * overload (the Wolf package's pattern). Callers resolve it
 * in start() or on a slow pulse, never per tick, and skip
 * the sound if the map has no classic entry. CHICKEN_EGG,
 * the lay-egg pop, IS a plain SoundEvent and is used
 * directly.
 *
 * DISPLAYS: createEggDisplay() builds the Golden Egg's item
 * display through EntityType.loadEntityRecursive from NBT,
 * the supported configuration path in 26.2 (display setters
 * are private). The NBT shows one egg item in the fixed
 * transform context (upright, like an item frame), sets
 * full brightness (block and sky light 15), the Glowing
 * flag, and a gold glow_color_override (0xFFD700), the
 * design's shining golden egg. The display never moves, so
 * its teleport duration is 0. A display is a pure visual:
 * no hitbox, no collision.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to
 * a victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance.
 *
 * STEERING: the chicken does not fly, but like the Phantom
 * (the closest flying relative) its movement answers a
 * per-tick velocity write while an ability drives it:
 * steerToward() sets the caster's velocity straight at a
 * point at a capped speed and returns the distance to the
 * point before the move. The Sky Tyrant Dive drives the
 * chicken this way instead of fighting its vanilla goals
 * with navigation calls.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans.
 */
final class ChickenAbilitySupport {

    private ChickenAbilitySupport() {}

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
     * The CLASSIC chicken variant's adult ambient (hurt false)
     * or hurt (hurt true) sound as a Holder, or null when the
     * variant map has no classic entry. See the SOUNDS note in
     * the class comment.
     */
    static Holder<SoundEvent> chickenSound(boolean hurt) {
        ChickenSoundVariant variant = SoundEvents.CHICKEN_SOUNDS.get(ChickenSoundVariants.SoundSet.CLASSIC);
        if (variant == null) {
            return null;
        }
        return hurt ? variant.adultSounds().hurtSound() : variant.adultSounds().ambientSound();
    }

    /**
     * A projectile-typed (vanilla THROWN) damage source
     * credited to the caster. See the DAMAGE SOURCE note in
     * the class comment. Build on a burst moment only.
     */
    static DamageSource thrownSource(AbilityContext ctx) {
        DamageSource vanilla = ctx.level().damageSources().thrown(ctx.mob(), ctx.mob());
        return new DamageSource(vanilla.typeHolder(), ctx.mob(), ctx.mob());
    }

    /**
     * Creates (but does not add to the level) the Golden Egg:
     * an item display showing one egg at the given scale,
     * centered on its snap position, at full brightness,
     * glowing with a gold outline override. Returns null if
     * the entity could not be created. See the DISPLAYS note
     * in the class comment.
     */
    static Entity createEggDisplay(ServerLevel level, float scale) {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:egg");
        item.putInt("count", 1);
        CompoundTag tag = new CompoundTag();
        tag.put("item", item);
        tag.putString("item_display", "fixed");
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putBoolean("Glowing", true);
        tag.putInt("glow_color_override", 0xFFD700);
        tag.putInt("teleport_duration", 0);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    static boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
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

    /** Resolves a living entity by a UUID stored in the state tag under the given key, or null. */
    static LivingEntity resolveByUuid(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof LivingEntity living) {
            return living;
        }
        return null;
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

    /** Sets the caster's velocity straight at a point and returns the distance to it before the move. */
    static double steerToward(AbilityContext ctx, double x, double y, double z, double speed) {
        Mob caster = ctx.mob();
        double dx = x - caster.getX();
        double dy = y - caster.getY();
        double dz = z - caster.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) {
            caster.setDeltaMovement(0.0, 0.0, 0.0);
            return 0.0;
        }
        double step = Math.min(speed, dist);
        caster.setDeltaMovement(dx / dist * step, dy / dist * step, dz / dist * step);
        return dist;
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
