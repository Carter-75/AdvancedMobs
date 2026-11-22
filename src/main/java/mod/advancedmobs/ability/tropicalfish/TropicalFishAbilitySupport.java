package mod.advancedmobs.ability.tropicalfish;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * TropicalFishAbilitySupport: helpers shared by the
 * Tropical Fish's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in
 * this package: the kaleidoscope's blind check, the
 * coral ring's pierce pulse, the anemone's sting
 * check): a living entity counts as an enemy of the
 * caster when it is alive, is not the caster, is not
 * riding the caster or being ridden by it, is not in
 * the caster's ally set (MobWarData.isAlly, the war
 * system's alliance record), and is either the
 * caster's current combat target or another Mob.
 * Players are only affected when they are the
 * caster's current target and are in survival or
 * adventure mode, so area abilities never grief
 * creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented
 * mobs' abilities use; it is duplicated here because
 * those classes are package-private to their own
 * packages.
 *
 * ALLY RULE: a living entity counts as an ally of
 * the caster when it is alive, is not the caster,
 * and either side's war data lists the other as an
 * ally (the either-direction check the Breeze, Bee,
 * Wolf, Cod, and Sniffer packages use). The Prism
 * Scale Aura buffs by this rule.
 *
 * STEERING: steerToward() sets an entity's velocity
 * straight at a point at a capped speed and returns
 * the distance to the point before the move (the
 * Dolphin, Tadpole, and Cod packages' helper, copied
 * because it is package-private there). A swimming
 * tropical fish's movement answers per-tick velocity
 * writes the same way a swimming cod's does, so the
 * kaleidoscope's darting, the anemone's sheltering,
 * and the mirage school's milling drive their
 * swimmers this way instead of fighting vanilla fish
 * navigation, which has no combat steering at all.
 *
 * DISPLAYS: createBlockDisplay() and
 * createItemDisplay() build displays through
 * EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display
 * setters are private). The NBT sets full brightness
 * (block and sky light 15, the Anemone Guard design's
 * full brightness glow) and a teleport duration of 2
 * ticks so per-tick snapTo calls glide instead of
 * snapping. createBlockDisplay() additionally takes
 * a tilt in radians, written as the transformation's
 * left rotation quaternion about the X axis (even
 * pieces) or the Z axis (odd pieces), so the Coral
 * Crown Garden's fan pieces lean at jaunty angles.
 * A display is a pure visual: no hitbox, no
 * collision, and NOT a block change, so displays
 * are never gated by the terrain rule and are
 * always discarded in end().
 *
 * TYPED DAMAGE: cactusSource() builds the coral
 * ring's piercing source: the vanilla cactus damage
 * type with the caster credited as attacker (the
 * Sheep package's typed source pattern: the public
 * DamageSource(Holder, Entity, Entity) constructor
 * over the vanilla source's type holder).
 *
 * TERRAIN (the standing terrain rule, Carter, Nov
 * 21, 2026; IMPLEMENTATION-RULES.md rule 6): the
 * only block changes in this package are the Coral
 * Crown Garden's coral blocks, which are PERMANENT
 * and placed only when AbilityContext.
 * mobGriefingAllowed() is true, into cells that
 * pass the garden's own legality checks (replaceable
 * water cell, no block entity, solid seafloor
 * beneath, the coral's own canSurvive). Everything
 * else in the package is effects, velocity,
 * particles, displays, and summons.
 *
 * MODIFIERS: addModifier() and removeModifier() are
 * the transient attribute modifier helpers the
 * package uses. Transient modifiers are not
 * persisted, so one applied to a victim vanishes on
 * its own if that victim unloads; removing one that
 * is absent is a no-op. Both tolerate a missing
 * attribute instance (a mob that lacks the attribute
 * simply never feels the modifier).
 *
 * PERFORMANCE: area queries use
 * Level.getEntitiesOfClass with a tight AABB built
 * from the given radius, and callers only run them
 * on a pulse interval or a one-shot moment, never
 * as whole-level scans.
 */
final class TropicalFishAbilitySupport {

    private TropicalFishAbilitySupport() {}

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
     * All allies of the context mob inside a box of the given
     * horizontal radius and vertical reach around a center point.
     * One bounded entity query; call on a pulse interval or a
     * one-shot moment, not per tick.
     */
    static List<LivingEntity> alliesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                          double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isAlly(ctx, e));
    }

    /** All allies of the context mob within the given radius of the mob itself. */
    static List<LivingEntity> alliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return alliesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /**
     * Sets the entity's velocity straight at the given point at
     * the given speed (capped so it never overshoots the point)
     * and returns the distance to the point before the move.
     * See the STEERING note in the class comment.
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

    /** Resolves a single entity UUID stored directly in the state tag under the key, or null. */
    static Entity resolveStored(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /**
     * The Coral Crown Garden's piercing source: vanilla
     * cactus damage type, the caster credited as the
     * attacker (see the TYPED DAMAGE note in the class
     * comment). Build only on a pierce pulse, never per
     * tick.
     */
    static DamageSource cactusSource(AbilityContext ctx) {
        DamageSource vanilla = ctx.level().damageSources().cactus();
        return new DamageSource(vanilla.typeHolder(), ctx.mob(), ctx.mob());
    }

    /**
     * Creates (but does not add to the level) one block
     * display of the given state at the given scale, at
     * full brightness, gliding with a teleport duration
     * of 2 ticks, tilted by the given angle in radians
     * about the X axis (even tiltAxis) or the Z axis
     * (odd tiltAxis): the Coral Crown Garden's jaunty
     * fan pieces. Returns null if the entity could not
     * be created. See the DISPLAYS note in the class
     * comment.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState state, float scale, double tilt, boolean tiltOnZ) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.put("transformation", transformation(scale, tilt, tiltOnZ));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * Creates (but does not add to the level) one item
     * display of the given stack at the given scale, at
     * full brightness, gliding with a teleport duration
     * of 2 ticks, in the "fixed" display context.
     * Returns null if the entity could not be created
     * (including when the stack fails to encode). See
     * the DISPLAYS note in the class comment.
     */
    static Entity createItemDisplay(ServerLevel level, ItemStack stack, float scale) {
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack);
        Tag itemTag = encoded.result().orElse(null);
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemCompound);
        tag.putString("item_display", "fixed");
        tag.put("transformation", transformation(scale, 0.0, false));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
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

    /**
     * A display transformation compound: centered
     * translation, uniform scale, and a left rotation
     * quaternion for the given tilt (about X, or about
     * Z when tiltOnZ is set); a zero tilt yields the
     * identity rotation.
     */
    private static CompoundTag transformation(float scale, double tilt, boolean tiltOnZ) {
        float half = -scale / 2.0f;
        float sin = (float) Math.sin(tilt / 2.0);
        float cos = (float) Math.cos(tilt / 2.0);
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(half, half, half));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", tiltOnZ
            ? floatList(0.0f, 0.0f, sin, cos)
            : floatList(sin, 0.0f, 0.0f, cos));
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
