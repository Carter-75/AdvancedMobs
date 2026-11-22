package mod.advancedmobs.ability.panda;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * PandaAbilitySupport: helpers shared by the Panda's 5 abilities.
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
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): no ability in this
 * package places or breaks blocks, so the mobGriefing gate
 * does not apply anywhere. Everything is velocity, effects,
 * particles, one item display, and one summon.
 *
 * DISPLAYS: createBambooDisplay() builds one item display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). The NBT sets full brightness (block and sky
 * light 15) and a teleport duration of 2 ticks so per-tick
 * snapTo calls glide instead of snapping. A display is a
 * pure visual: no hitbox, no collision.
 *
 * SUMMONS: spawnBabyPanda() is the Sneeze Bomb summon path:
 * MOB_SUMMONED, baby age forced after finalizeSpawn, the
 * design's half scale as a permanent SCALE attribute base
 * value of 0.5 on the new mob (a real mob, not a prop, so
 * the half scale is a base value rather than a transient
 * modifier to clean up: the Noxious Split precedent),
 * allied in both directions, SummonerTracker registered,
 * ordered onto the caster's target.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to
 * a victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance (a mob that lacks the
 * attribute simply never feels the modifier: Kung Fu Form's
 * ATTACK_SPEED line depends on this, see that class).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans (Rolling Boulder's
 * per-tick radius-2.2 hit check is the standing exception,
 * the Ram Catapult precedent).
 */
final class PandaAbilitySupport {

    /**
     * The shared TINTED_LEAVES particle option: in 26.2 the
     * type is a ParticleType of ColorParticleOption, so the
     * leaves carry a fixed leafy green tint (0.35, 0.60,
     * 0.20) instead of a biome color. Pre-built once, never
     * allocated in a hot path.
     */
    static final ColorParticleOption TINTED_LEAVES =
        ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0.35f, 0.60f, 0.20f);

    private PandaAbilitySupport() {}

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

    /** Whether the given living entity is an ally of the caster (alliance record in either direction). */
    static boolean isAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID());
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

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    static boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    /**
     * Creates (but does not add to the level) the Bamboo
     * Feast's bamboo stalk: an item display showing one
     * bamboo item at the given scale, centered on its snap
     * position, in the thirdperson_righthand transform
     * context (held in a paw), at full brightness, gliding
     * with a teleport duration of 2 ticks. Returns null if
     * the entity could not be created. See the DISPLAYS note
     * in the class comment.
     */
    static Entity createBambooDisplay(ServerLevel level, float scale) {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:bamboo");
        item.putInt("count", 1);
        CompoundTag tag = new CompoundTag();
        tag.put("item", item);
        tag.putString("item_display", "thirdperson_righthand");
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * Spawns the Sneeze Bomb's baby panda at the given spot
     * (the caster's own position as fallback when the spot is
     * not free): a baby (age -24000) with the SCALE attribute
     * base value set to 0.5 permanently, allied with the
     * caster in both directions, registered in
     * SummonerTracker, and ordered onto the caster's current
     * target. Returns the baby, or null when it could not be
     * created or added. See the SUMMONS note in the class
     * comment.
     */
    static Panda spawnBabyPanda(AbilityContext ctx, ServerLevel level, double spawnX, double spawnZ) {
        Mob caster = ctx.mob();
        Panda baby = EntityTypes.PANDA.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (baby == null) {
            return null;
        }
        if (!isFreeSpot(level, baby, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        baby.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        baby.finalizeSpawn(level, level.getCurrentDifficultyAt(baby.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // finalizeSpawn can hand back an adult; the design's
        // baby is forced after it, age and half scale both.
        baby.setAge(-24000);
        AttributeInstance scale = baby.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(0.5);
        }
        if (!level.addFreshEntity(baby)) {
            return null;
        }
        ctx.data().addAlly(baby.getUUID());
        MobWarData.get(baby).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(baby.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null && target != baby) {
            baby.setTarget(target);
        }
        return baby;
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
