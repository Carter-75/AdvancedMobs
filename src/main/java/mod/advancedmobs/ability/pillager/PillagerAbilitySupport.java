package mod.advancedmobs.ability.pillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
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
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PillagerAbilitySupport: helpers shared by the Pillager's
 * 5 abilities.
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
 * ILLAGER ALLY RULE (used by the Raid Captain's Call): an
 * AbstractIllager (a Pillager, Vindicator, Evoker, or
 * Illusioner) counts as an ally of the caster when it is
 * alive, is not the caster, and either the alliance record
 * runs in either direction (the caster's data lists it as an
 * ally, or its own data lists the caster, the either-direction
 * check the Silverfish's Hive Mind Link uses) or it is
 * currently fighting the same target as the caster (a shared
 * fight is a de facto alliance, the Piglin package's cluster
 * rule). Wild illagers with no record and no shared fight are
 * left alone: the call rallies the captain's own warband, not
 * every illager in the outpost.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. The Outpost
 * Sniper Perch's tower is the only terrain writer;
 * canPlaceAbilityBlock() below is its safety check, refusing
 * anything that is not air or replaceable, fluids, block
 * entities (containers are never touched), and unbreakable
 * blocks (destroy speed below zero). The Powder Keg Toss's
 * explosion is the other terrain-relevant effect, and it
 * routes its block damage through the explosion interaction
 * mode the gate selects (MOB when on, NONE when off) instead
 * of placing blocks itself.
 *
 * DISPLAYS: createBlockDisplay() builds one block display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). The NBT sets full brightness (block and sky light
 * 15) and a teleport duration of 2 ticks. The transformation
 * centers the scaled block on the entity position (translation
 * of minus half the scale on each axis), so callers position a
 * display by its center. A display is a pure visual: no
 * hitbox, no collision, no health; abilities that give one
 * health track it in code (the Turret Stance wall does).
 *
 * BOLTS: fireBolt() spawns one real vanilla Arrow owned by
 * the caster, the projectile its crossbow would loose (the
 * Bogged package's arrow pattern: pickup disallowed, base
 * damage set in code, shot from the eye position at the
 * target's mid-body). A fired bolt is a free projectile once
 * spawned: no ability tracks or cleans it up.
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
final class PillagerAbilitySupport {

    private PillagerAbilitySupport() {}

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
     * Whether the Outpost Sniper Perch may place an ability
     * block at the position: the cell must be air or
     * replaceable, dry (no fluid), free of any block entity
     * (containers and other block entities are never touched),
     * and not unbreakable (destroy speed below zero, e.g.
     * bedrock). Callers must already have checked
     * AbilityContext.mobGriefingAllowed(). This is the Shulker
     * package's placement check, duplicated for the same
     * package-privacy reason as the enemy rule.
     */
    static boolean canPlaceAbilityBlock(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!current.isAir() && !current.canBeReplaced()) {
            return false;
        }
        if (!current.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return current.getDestroySpeed(level, pos) >= 0.0f;
    }

    /**
     * Creates (but does not add to the level) one block display
     * showing the given block state at the given per-axis scale,
     * centered on its entity position, at full brightness.
     * Returns null if the entity could not be created. See the
     * DISPLAYS note in the class comment.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState blockState, float scaleX, float scaleY, float scaleZ) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(blockState));
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(-scaleX / 2.0f, -scaleY / 2.0f, -scaleZ / 2.0f));
        transformation.put("scale", floatList(scaleX, scaleY, scaleZ));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * Fires one real crossbow bolt (a vanilla Arrow owned by the
     * caster) from the caster's eye position at the target's
     * mid-body, or straight along the caster's look angle when
     * the target is null. The bolt's base damage, speed, and
     * inaccuracy are the caller's numbers. Returns true when a
     * bolt was spawned.
     */
    static boolean fireBolt(AbilityContext ctx, LivingEntity target, double baseDamage, float speed, float inaccuracy) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return false;
        }
        Mob mob = ctx.mob();
        Vec3 from = mob.getEyePosition();
        Vec3 dir;
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - mob.getX(),
                               (target.getY() + target.getBbHeight() * 0.6) - mob.getEyeY(),
                               target.getZ() - mob.getZ());
            dir = to.lengthSqr() > 1.0e-6 ? to.normalize() : mob.getLookAngle();
        } else {
            dir = mob.getLookAngle();
        }
        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(baseDamage);
        arrow.snapTo(from.x, from.y, from.z,
            (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)), 0.0f);
        arrow.shoot(dir.x, dir.y, dir.z, speed, inaccuracy);
        return level.addFreshEntity(arrow);
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

    /** Resolves every tracked entity under the key that still exists in the level. */
    static List<Entity> resolveTracked(ServerLevel level, CompoundTag state, String key) {
        List<Entity> resolved = new ArrayList<>();
        ListTag list = state.getListOrEmpty(key);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (entity != null) {
                resolved.add(entity);
            }
        }
        return resolved;
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

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }
}
