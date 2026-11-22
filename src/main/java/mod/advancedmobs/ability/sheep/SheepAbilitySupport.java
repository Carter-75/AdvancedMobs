package mod.advancedmobs.ability.sheep;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * SheepAbilitySupport: helpers shared by the Sheep's 5
 * abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the
 * caster when it is alive, is not the caster, is not
 * riding the caster or being ridden by it, is not in the
 * caster's ally set (MobWarData.isAlly, the war system's
 * alliance record), and is either the caster's current
 * combat target or another Mob. Players are only affected
 * when they are the caster's current target and are in
 * survival or adventure mode, so area abilities never
 * grief creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented
 * mobs' abilities use; it is duplicated here because those
 * classes are package-private to their own packages.
 *
 * SHEEP FACTS (verified against the real 26.2 jar): the
 * sheep is net.minecraft.world.entity.animal.sheep.Sheep;
 * createAttributes adds only MAX_HEALTH 8.0 and
 * MOVEMENT_SPEED 0.23. There is NO ATTACK_DAMAGE instance
 * and NO ATTACK_SPEED instance anywhere in its attribute
 * set (it is a passive animal with no melee attack), so
 * every damage number in this package is a flat code value
 * dealt through a damage source credited to the caster,
 * and the design's ATTACK_SPEED line (Shear Terror) is
 * applied through the null-tolerant helper and is inert:
 * the flurry rate is carried by code strike timing, the
 * Grand Hex Form precedent. Sheep carries two real,
 * server-settable look states the designs use: its wool
 * color (getColor / setColor with a DyeColor) and its
 * sheared flag (isSheared / setSheared).
 *
 * DECOYS: summonDecoy() creates one real Sheep as a
 * Rainbow Mirage Flock decoy, on the mirror sheet the
 * Illusioner package established: MAX_HEALTH base set to
 * 1.0 after finalizeSpawn (one hit pops it) and the
 * caster's own wool color, so at a glance the flock is
 * three identical sheep. Team assignment is both
 * directions plus SummonerTracker, like every other
 * summon in this mod. A decoy is given no target; a
 * sheep's own AI only wanders and grazes, and the flock
 * ability steers the decoys by move control.
 *
 * TYPED ZAPS: lightningSource() builds the Static Wool
 * zap source: the vanilla lightning bolt damage TYPE (the
 * design's typed damage source) with the caster as both
 * direct and causing entity, so zap kills credit the
 * sheep while the type's own rules (armor handling, death
 * message) stay the lightning ones. Building it does a
 * registry lookup through DamageSources, so callers build
 * it only on zap or jolt moments, never per tick.
 *
 * TERRAIN: no ability in this package places or breaks
 * blocks. The charges and flurries are velocity and code
 * damage, the mirage is summoned entities and a teleport,
 * Shear Terror flips the sheep's own sheared flag (a
 * state of the mob, not a block), and everything else is
 * effects, modifiers, and particles. The standing terrain
 * rule's mobGriefing gate therefore does not apply
 * anywhere in this package, and each ability's comment
 * says so.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses,
 * null-tolerant like the other packages' copies.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans (Ram Charge's
 * per-tick radius-2.0 hit check is the standing
 * exception).
 */
final class SheepAbilitySupport {

    private SheepAbilitySupport() {}

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
     * Summons one mirage decoy (see the DECOYS note in the
     * class comment) at the given position wearing the given
     * wool color, allied to the caster. Returns the decoy,
     * or null when the spawn failed.
     */
    static Sheep summonDecoy(AbilityContext ctx, double x, double y, double z, float yaw, DyeColor color) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return null;
        }
        Mob caster = ctx.mob();
        Sheep decoy = EntityTypes.SHEEP.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (decoy == null) {
            return null;
        }
        decoy.snapTo(x, y, z, yaw, 0.0f);
        decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // The mirror sheet (see the class comment): one
        // point of health, the caster's own color, set after
        // finalizeSpawn so nothing re-rolls them.
        AttributeInstance health = decoy.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(1.0);
        }
        decoy.setHealth(1.0f);
        decoy.setColor(color);
        if (!level.addFreshEntity(decoy)) {
            return null;
        }
        ctx.data().addAlly(decoy.getUUID());
        MobWarData.get(decoy).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());
        return decoy;
    }

    /**
     * The Static Wool typed zap source (see the TYPED ZAPS
     * note in the class comment): lightning bolt type,
     * caster credited as attacker. Build only on a zap or
     * jolt moment.
     */
    static DamageSource lightningSource(AbilityContext ctx) {
        DamageSource vanilla = ctx.level().damageSources().lightningBolt();
        return new DamageSource(vanilla.typeHolder(), ctx.mob(), ctx.mob());
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
