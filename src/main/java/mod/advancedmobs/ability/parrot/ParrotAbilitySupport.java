package mod.advancedmobs.ability.parrot;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * ParrotAbilitySupport: helpers shared by the Parrot's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the caster
 * or being ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and
 * is either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * TERRAIN: no ability in this package places or breaks
 * blocks, so the standing terrain rule's mobGriefing gate
 * does not apply anywhere in the package. Death Squawk
 * Revenge's death burst is a pure impulse (velocity writes
 * plus particles), never an explosion, so it breaks nothing
 * either way.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op. Both tolerate a missing
 * attribute instance (the null-tolerant pattern the Zoglin
 * package uses for attributes a mob does not carry).
 *
 * STEERING: the parrot flies, and like the Phantom (the
 * closest flying relative) its flight answers a per-tick
 * velocity write: steerToward() sets the caster's velocity
 * straight at a point at a capped speed and returns the
 * distance to the point before the move. The cyclone and
 * flock abilities drive birds this way instead of fighting
 * their vanilla flying goals with navigation calls.
 *
 * DISPLAYS: createItemDisplay() builds one item display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). The NBT sets full brightness (block and sky
 * light 15) and a teleport duration of 2 ticks so per-tick
 * snapTo calls glide instead of snapping. A display is a
 * pure visual: no hitbox, no collision. Displays in this
 * package are code-snapped to their orbit points every tick
 * (the Witch package's orbiting bottle precedent) rather
 * than riding as true passengers, because a passenger
 * display inherits the bird's exact position with no orbit
 * offset; the visual result is the design's circling
 * feathers either way.
 *
 * RAINBOW DUST: RAINBOW is a pre-built cycle of six
 * DustColorTransitionOptions (hue step to hue step: red to
 * orange, orange to yellow, yellow to green, green to blue,
 * blue to violet, violet to red), shared by Flock Raid's
 * trails and Rainbow Plumage's body shimmer. Built once at
 * class load so no tick ever allocates a particle option.
 * In 26.2 the constructor takes (from RGB int, to RGB int,
 * scale), verified against the jar.
 *
 * FEATHER PARTICLE: FEATHER_MOTE is the pre-built feather
 * item particle option (in 26.2 ItemParticleOption takes
 * the particle type and the Item itself, verified against
 * the jar).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class ParrotAbilitySupport {

    private ParrotAbilitySupport() {}

    /** The shared feather item particle option (see the class comment). */
    static final ItemParticleOption FEATHER_MOTE =
        new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER);

    /** The six-step rainbow dust color transition cycle (see the class comment). */
    static final DustColorTransitionOptions[] RAINBOW = {
        new DustColorTransitionOptions(0xFF0000, 0xFF7F00, 1.0f),
        new DustColorTransitionOptions(0xFF7F00, 0xFFFF00, 1.0f),
        new DustColorTransitionOptions(0xFFFF00, 0x00FF00, 1.0f),
        new DustColorTransitionOptions(0x00FF00, 0x0000FF, 1.0f),
        new DustColorTransitionOptions(0x0000FF, 0x8B00FF, 1.0f),
        new DustColorTransitionOptions(0x8B00FF, 0xFF0000, 1.0f)
    };

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

    /** Sets the given flying entity's velocity straight at a point and returns the distance to it before the move. */
    static double steerToward(Entity bird, double x, double y, double z, double speed) {
        double dx = x - bird.getX();
        double dy = y - bird.getY();
        double dz = z - bird.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) {
            bird.setDeltaMovement(0.0, 0.0, 0.0);
            return 0.0;
        }
        double step = Math.min(speed, dist);
        bird.setDeltaMovement(dx / dist * step, dy / dist * step, dz / dist * step);
        return dist;
    }

    /**
     * Creates (but does not add to the level) one item display
     * of the given stack at the given scale, at full brightness,
     * gliding with a teleport duration of 2 ticks. When
     * billboard is non-null the display's billboard mode is set
     * to it (Rainbow Plumage's gem uses "center", so the gem
     * always faces the viewer); pass null for a fixed display.
     * Returns null if the entity could not be created. See the
     * DISPLAYS note in the class comment.
     */
    static Entity createItemDisplay(ServerLevel level, ItemStack stack, float scale, String billboard) {
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        Tag itemTag = encoded.result().orElse(null);
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemCompound);
        tag.putString("item_display", "fixed");
        if (billboard != null) {
            tag.putString("billboard", billboard);
        }
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** A display transformation compound: zero translation, uniform scale, identity rotations. */
    private static CompoundTag transformation(float scale) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0f, 0.0f, 0.0f));
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
