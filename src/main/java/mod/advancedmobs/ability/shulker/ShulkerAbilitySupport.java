package mod.advancedmobs.ability.shulker;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * ShulkerAbilitySupport: helpers shared by the Shulker's 5
 * abilities.
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
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. The Endstone
 * Bunker's shell and the Skybox Artillery Perch's platform are
 * the only terrain writers. canPlaceAbilityBlock() is the
 * shared safety check: the cell must be air or replaceable,
 * dry (no fluid), free of any block entity, and not
 * unbreakable (destroy speed below zero, e.g. bedrock), so
 * ability terrain never overwrites a container, never touches
 * an unbreakable block, and never places into a fluid.
 *
 * TELEPORTS: shulkers are anchored sitters, so this package
 * moves the caster with snapTo onto a found floor spot (the
 * Monument Rising peak pattern), never with velocity.
 * findFloorSpot() scans a column for the first sturdy top with
 * a collision-free 1x1x1 space above it, which is all a closed
 * shulker needs. Shulker.teleportSomewhere() is protected in
 * vanilla, so the search is done here in code, as the design's
 * "teleport in code" calls for.
 *
 * BULLETS: fireBullet() spawns one real vanilla ShulkerBullet
 * (the same projectile the shulker's own attack goal fires:
 * 4 damage plus LEVITATION on a hit, homing on its target
 * entity). The bullet is a free projectile once spawned: no
 * ability tracks or cleans it up, exactly like a bullet from
 * the vanilla goal.
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
final class ShulkerAbilitySupport {

    private ShulkerAbilitySupport() {}

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

    /** The nearest enemy to a point inside the given radius, or null. One bounded query. */
    static LivingEntity nearestEnemy(AbilityContext ctx, double centerX, double centerY, double centerZ, double radius) {
        LivingEntity best = null;
        double bestSqr = radius * radius;
        for (LivingEntity enemy : enemiesInBox(ctx, centerX, centerY, centerZ, radius, radius, radius)) {
            double sqr = enemy.distanceToSqr(centerX, centerY, centerZ);
            if (sqr <= bestSqr) {
                bestSqr = sqr;
                best = enemy;
            }
        }
        return best;
    }

    /**
     * Whether an ability block may be placed at the position
     * (see the TERRAIN note in the class comment). Callers must
     * already have checked AbilityContext.mobGriefingAllowed().
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
     * Finds a floor spot for a teleported shulker in the column
     * at (blockX, blockZ): scans from scanTopY down to scanBottomY
     * for the first block with a sturdy top face, with a
     * collision-free 1x1x1 space directly above it. Returns the
     * feet position (block center, on top of the floor block), or
     * null when the column offers no spot.
     */
    static double[] findFloorSpot(ServerLevel level, Mob mob, int blockX, int blockZ, int scanTopY, int scanBottomY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = scanTopY; y >= scanBottomY; y--) {
            pos.set(blockX, y, blockZ);
            BlockState floor = level.getBlockState(pos);
            if (!floor.isFaceSturdy(level, pos, Direction.UP)) {
                continue;
            }
            double feetX = blockX + 0.5;
            double feetY = y + 1.0;
            double feetZ = blockZ + 0.5;
            AABB box = new AABB(feetX - 0.5, feetY, feetZ - 0.5, feetX + 0.5, feetY + 1.0, feetZ + 0.5);
            if (level.noCollision(mob, box)) {
                return new double[]{feetX, feetY, feetZ};
            }
            return null;
        }
        return null;
    }

    /**
     * Fires one real vanilla ShulkerBullet from the given
     * position at the given target entity, owned by the caster.
     * The homing axis is the caster's attach face axis when the
     * caster is a shulker (the vanilla attack goal's choice),
     * Y otherwise. Returns whether the bullet was spawned.
     */
    static boolean fireBullet(AbilityContext ctx, LivingEntity target, double fromX, double fromY, double fromZ) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || target == null || !target.isAlive()) {
            return false;
        }
        Mob caster = ctx.mob();
        Direction.Axis axis = caster instanceof Shulker shulker
            ? shulker.getAttachFace().getAxis()
            : Direction.Axis.Y;
        ShulkerBullet bullet = new ShulkerBullet(level, caster, target, axis);
        bullet.snapTo(fromX, fromY, fromZ, caster.getYRot(), 0.0f);
        return level.addFreshEntity(bullet);
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
}
