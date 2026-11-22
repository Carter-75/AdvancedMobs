package mod.advancedmobs.ability.vindicator;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * VindicatorAbilitySupport: helpers shared by the
 * Vindicator's 5 abilities.
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
 * packages. ONE deliberate exception: Johnny Rampage does not
 * use this rule for the vindicator's own targeting, because
 * the design's whole point is indiscriminate attacks; vanilla
 * does that targeting itself through the Johnny goal (see
 * JohnnyRampageAbility). The rule still governs every
 * code-driven area effect in this package.
 *
 * ILLAGER ALLY RULE (used by the Patrol Captain's Standard):
 * an AbstractIllager (a Vindicator, Evoker, Pillager, or
 * Illusioner) counts as an ally of the caster when it is
 * alive, is not the caster, and either the alliance record
 * runs in either direction (the caster's data lists it as an
 * ally, or its own data lists the caster, the either-direction
 * check the Silverfish's Hive Mind Link uses) or it is
 * currently fighting the same target as the caster (a shared
 * fight is a de facto alliance, the Piglin package's cluster
 * rule). Wild illagers with no record and no shared fight are
 * left alone: the standard blesses the captain's own patrol,
 * not every illager in the mansion.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. The
 * Doorbreaker Siege's mined blocks are the only terrain
 * writer; isBreakable() below is its safety check, refusing
 * air, fluids, block entities (containers are never touched),
 * and unbreakable blocks (destroy speed below zero).
 *
 * DISPLAYS: createItemDisplay() builds one item display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). The stack is written through the ItemStack codec
 * (the 26.2 display "item" form), the NBT sets full brightness
 * (block and sky light 15) and a teleport duration of 2 ticks
 * so per-tick snapTo calls glide instead of snapping. An item
 * display's pivot is the model center, so no centering
 * translation is needed. A display is a pure visual: no
 * hitbox, no collision, no health.
 *
 * BITE CLOCK: primeHitClock() and consumeLandedHit() expose
 * the caster's landed melee hits one at a time, the Endermite
 * package's pattern: LivingEntity.getLastHurtMob() and
 * getLastHurtMobTimestamp() name the most recent victim of
 * the caster's melee (Mob.doHurtTarget sets them on a landed
 * hit, verified against the 26.2 jar). Each landed hit is
 * reported exactly once; a hit on an ally or a non-enemy is
 * consumed silently instead of being re-reported on every
 * poll.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op. Both tolerate a missing
 * attribute instance (the null-tolerant pattern the Zoglin
 * package uses for attributes a mob does not carry).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class VindicatorAbilitySupport {

    private VindicatorAbilitySupport() {}

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

    /** All enemies inside a box around the caster itself. See enemiesInBox. */
    static List<LivingEntity> enemiesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return enemiesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /** Whether the given illager-kind entity is an ally of the caster (see the ILLAGER ALLY RULE above). */
    static boolean isIllagerAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (!(candidate instanceof AbstractIllager)) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        if (candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID())) {
            return true;
        }
        LivingEntity shared = ctx.target();
        return shared != null && candidate instanceof Mob other && other.getTarget() == shared;
    }

    /**
     * All illager allies inside a box of the given horizontal
     * radius and vertical reach around a center point. One
     * bounded entity query; call on a pulse interval or from
     * canTrigger, not per tick.
     */
    static List<AbstractIllager> illagerAlliesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                                    double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(AbstractIllager.class, box, e -> isIllagerAlly(ctx, e));
    }

    /** All illager allies inside a box around the caster itself. See illagerAlliesInBox. */
    static List<AbstractIllager> illagerAlliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return illagerAlliesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /**
     * Whether the Doorbreaker Siege may mine the block at the
     * position: a real block (not air), dry (no fluid), with no
     * block entity (containers and other block entities are
     * never touched), and breakable (destroy speed not
     * negative, so bedrock and its kin are refused). Callers
     * must already have checked
     * AbilityContext.mobGriefingAllowed(). This is the
     * Silverfish package's isDiggable check, duplicated for the
     * same package-privacy reason as the enemy rule.
     */
    static boolean isBreakable(ServerLevel level, BlockPos pos) {
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
     * Creates (but does not add to the level) one item display
     * showing the given stack at the given uniform scale, at
     * full brightness, "fixed" context, gliding with a teleport
     * duration of 2 ticks. Returns null if the entity could not
     * be created. See the DISPLAYS note in the class comment.
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
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
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

    /** Resolves the first tracked entity under the key, or null when gone or never tracked. */
    static Entity resolveFirstTracked(ServerLevel level, CompoundTag state, String key) {
        ListTag list = state.getListOrEmpty(key);
        if (list.isEmpty()) {
            return null;
        }
        CompoundTag entry = list.getCompoundOrEmpty(0);
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /**
     * Stores the caster's current last-hurt-mob timestamp in
     * the state tag ("hit_ts"). Called from start() so hits
     * landed before the activation are never answered.
     */
    static void primeHitClock(AbilityContext ctx) {
        ctx.state().putInt("hit_ts", ctx.mob().getLastHurtMobTimestamp());
    }

    /**
     * Returns the enemy the caster has hit with melee since the
     * last call, or null when no new hit landed. Each landed hit
     * is reported exactly once: the stored timestamp advances
     * whether or not the victim turns out to be a valid enemy,
     * so a hit on an ally or a non-enemy is consumed silently
     * instead of being re-reported on every poll.
     */
    static LivingEntity consumeLandedHit(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int timestamp = mob.getLastHurtMobTimestamp();
        if (timestamp == state.getIntOr("hit_ts", -1)) {
            return null;
        }
        state.putInt("hit_ts", timestamp);
        LivingEntity victim = mob.getLastHurtMob();
        if (victim == null || !isEnemy(ctx, victim)) {
            return null;
        }
        return victim;
    }

    /** Adds or refreshes a transient attribute modifier on the given entity; tolerates a missing attribute. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent or the attribute is missing. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, net.minecraft.resources.Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    /** A display transformation compound: no translation (item pivot is the model center), uniform scale, identity rotations. */
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
