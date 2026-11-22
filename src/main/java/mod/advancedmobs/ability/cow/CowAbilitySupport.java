package mod.advancedmobs.ability.cow;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.cow.CowSoundVariant;
import net.minecraft.world.entity.animal.cow.CowSoundVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * CowAbilitySupport: helpers shared by the Cow's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package: the Stampede Engine trample check, the Horned
 * Bulwark counter, the Moothquake Stomp ring, the Cud
 * Cannon splash): a living entity counts as an enemy of the
 * caster when it is alive, is not the caster, is not riding
 * the caster or being ridden by it, is not in the caster's
 * ally set (MobWarData.isAlly, the war system's alliance
 * record), and is either the caster's current combat target
 * or another Mob. Players are only affected when they are
 * the caster's current target and are in survival or
 * adventure mode, so area abilities never grief creative or
 * spectator players who happen to stand nearby. This is the
 * same rule the other implemented mobs' abilities use; it
 * is duplicated here because those classes are
 * package-private to their own packages.
 *
 * ALLY RULE (the Pasture Sanctuary's targeting): a living
 * entity counts as an ally of the caster when it is alive,
 * is not the caster, and either side's war data lists the
 * other as an ally (the either-direction check the Breeze,
 * Bee, and Wolf packages use).
 *
 * COW SOUNDS: 26.2 has no plain COW_AMBIENT SoundEvent
 * constant (verified against the jar): cow sounds live in
 * the cow sound variant registry, exposed through
 * SoundEvents.COW_SOUNDS (a map from CowSoundVariants.
 * SoundSet to CowSoundVariant). cowSound() resolves the
 * CLASSIC variant's ambient sound Holder for the
 * Level.playSound Holder overload, the Wolf and Pig
 * packages' pattern for the same 26.2 variant system.
 * Unlike the pig variant, the cow variant's record
 * components are the sound Holders directly (ambientSound(),
 * hurtSound(), deathSound(), stepSound()).
 *
 * DISPLAYS: createBlockDisplay() builds one block display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). The NBT sets full brightness (block and sky
 * light 15) and a teleport duration of 2 ticks so per-tick
 * snapTo calls glide instead of snapping. A display is a
 * pure visual: no hitbox, no collision, and NOT a block
 * change, so the terrain rule's gate does not apply to it.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to
 * a victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance.
 *
 * TERRAIN: no ability in this package places or breaks
 * real blocks, so the standing terrain rule's mobGriefing
 * gate does not apply anywhere in the package. The
 * Moothquake Stomp's flying dirt is display entities (the
 * real blocks never move), the Cud Cannon's glob is a
 * vanilla projectile entity, and everything else is
 * velocity, effects, attribute modifiers, and particles.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans.
 */
final class CowAbilitySupport {

    private CowAbilitySupport() {}

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
     * one-shot moment, not per tick unless the box is tight.
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
     * One bounded entity query; call on a pulse interval only.
     */
    static List<LivingEntity> alliesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                           double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isAlly(ctx, e));
    }

    /**
     * The classic cow variant's ambient sound, as a sound
     * Holder for Level.playSound; null when the variant map
     * has no classic entry. See the COW SOUNDS note in the
     * class comment.
     */
    static Holder<SoundEvent> cowSound() {
        CowSoundVariant variant = SoundEvents.COW_SOUNDS.get(CowSoundVariants.SoundSet.CLASSIC);
        if (variant == null) {
            return null;
        }
        return variant.ambientSound();
    }

    /**
     * Creates (but does not add to the level) one block
     * display showing the given block state at the given
     * scale, centered on its snap position, at full
     * brightness, gliding with a teleport duration of 2
     * ticks. Returns null if the entity could not be created.
     * See the DISPLAYS note in the class comment.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState state, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
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
