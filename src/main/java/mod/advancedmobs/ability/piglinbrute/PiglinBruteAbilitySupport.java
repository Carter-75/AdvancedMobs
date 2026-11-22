package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
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
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * PiglinBruteAbilitySupport: helpers shared by the Piglin
 * Brute's 5 abilities.
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
 * AURA ALLY RULE (used by the Bastion Standard Aura): an
 * AbstractPiglin (a Piglin or another Piglin Brute) counts as
 * an aura ally of the caster when it is alive, is not the
 * caster, and either the alliance record runs in either
 * direction (the caster's data lists it as an ally, or its own
 * data lists the caster, the either-direction check the
 * Silverfish's Hive Mind Link uses) or it is currently fighting
 * the same target as the caster (a shared fight is a de facto
 * alliance, the Piglin package's cluster rule). Wild piglins
 * with no record and no shared fight are left alone: the
 * design's protected line is the brute's own crowd, not every
 * snout in the bastion.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. The War Stomp
 * Fissure's blackstone spikes and its Nether fire are the only
 * terrain writers; placeFire() below is the fire half, refusing
 * containers and other block entities and unbreakable spots,
 * and the fissure itself only ever writes into replaceable
 * cells over solid ground, so spikes never overwrite anything
 * and never float.
 *
 * DISPLAYS: createBlockDisplay() builds one block display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). The NBT sets full brightness (block and sky light
 * 15, the design's gleaming pauldrons) and a teleport duration
 * of 2 ticks so the displays glide as they track the brute.
 * The transformation centers the block model on the display's
 * snap position. A display is a pure visual: no hitbox, no
 * collision, no health.
 *
 * BITE CLOCK: primeHitClock() and consumeLandedHit() expose
 * the caster's landed melee hits one at a time, the Endermite
 * package's pattern: LivingEntity.getLastHurtMob() and
 * getLastHurtMobTimestamp() name the most recent victim of the
 * caster's melee (Mob.doHurtTarget sets them on a landed hit,
 * verified against the 26.2 jar). Each landed hit is reported
 * exactly once; a hit on an ally or a non-enemy is consumed
 * silently instead of being re-reported on every poll.
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
final class PiglinBruteAbilitySupport {

    private PiglinBruteAbilitySupport() {}

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

    /** Whether the given piglin-kind entity is an aura ally of the caster (see the AURA ALLY RULE above). */
    static boolean isAuraAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (!(candidate instanceof AbstractPiglin)) {
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
     * All aura allies inside a box of the given horizontal radius
     * and vertical reach around the caster. One bounded entity
     * query; call on a pulse interval or from canTrigger, not
     * per tick.
     */
    static List<AbstractPiglin> auraAlliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        AABB box = new AABB(self.getX() - radius, self.getY() - yBelow, self.getZ() - radius,
                            self.getX() + radius, self.getY() + yAbove, self.getZ() + radius);
        return ctx.level().getEntitiesOfClass(AbstractPiglin.class, box, e -> isAuraAlly(ctx, e));
    }

    /**
     * Places one permanent fire block at the position, when the
     * spot can hold one: the current block is replaceable, dry
     * (no fluid), not already fire, has no block entity, and the
     * block below has a sturdy top face for the fire to sit on.
     * Uses BaseFireBlock.getState, the same state vanilla picks
     * (soul fire on soul soil, normal fire elsewhere). Returns
     * whether a fire was placed. Callers must already have
     * checked AbilityContext.mobGriefingAllowed(). This is the
     * Magma Cube package's helper, duplicated for the same
     * package-privacy reason as the enemy rule.
     */
    static boolean placeFire(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (current.is(Blocks.FIRE) || current.is(Blocks.SOUL_FIRE)) {
            return false;
        }
        if (!current.canBeReplaced() || !current.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        return level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
    }

    /**
     * Creates (but does not add to the level) one block display
     * showing the given block state at the given uniform scale,
     * centered on its snap position, at full brightness, gliding
     * with a teleport duration of 2 ticks. Returns null if the
     * entity could not be created. See the DISPLAYS note in the
     * class comment.
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
