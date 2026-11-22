package mod.advancedmobs.ability.evoker;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * EvokerAbilitySupport: helpers shared by the Evoker's 5
 * abilities.
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
 * FANGS: spawnFang() creates one real vanilla EvokerFangs
 * entity owned by the caster. Verified against the 26.2 jar:
 * EvokerFangs.dealDamageTo deals 6.0 magic damage
 * (indirectMagic credited to the owner) to every living
 * entity in its bite box EXCEPT the owner itself and any
 * entity allied to the owner (LivingEntity.isAlliedTo), so
 * fangs an ability spawns never bite the evoker or his court.
 * The fang's warmup is the constructor's warmup delay: the
 * bite lands warmup + 8 ticks after the spawn tick.
 *
 * GROUND: findGroundY() scans one column downward for the
 * first block with a sturdy top face and returns the Y of
 * the space on top of it (the feet Y a fang sits at), or
 * Double.NaN when the column offers no ground in range.
 * findFloorSpot() is the teleport version: the same scan,
 * additionally requiring the caster's own bounding box to
 * fit collision-free at the spot (the Shulker package's
 * pattern).
 *
 * TERRAIN: no ability in this package places or breaks
 * blocks. The fangs are entities that erupt through the
 * ground without changing it, the decoys and guards are
 * summoned entities, and the caster's repositioning is a
 * teleport. The standing terrain rule's mobGriefing gate
 * therefore does not apply anywhere in this package, and
 * each ability's comment says so.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses,
 * null-tolerant like the other packages' copies.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and callers
 * only run them on a pulse interval or a one-shot moment,
 * never as whole-level scans. Column scans are bounded to
 * the drop range the caller passes.
 */
final class EvokerAbilitySupport {

    private EvokerAbilitySupport() {}

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
     * horizontal radius and vertical reach around a center
     * point. One bounded entity query; call on a pulse
     * interval or a one-shot moment, not per tick.
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

    /**
     * The feet Y of the ground in one column: scans from
     * fromY downward at most maxDrop blocks for the first
     * block with a sturdy top face and returns one above it.
     * Double.NaN when no ground is found in range.
     */
    static double findGroundY(ServerLevel level, int blockX, int blockZ, int fromY, int maxDrop) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = fromY; y >= fromY - maxDrop; y--) {
            pos.set(blockX, y, blockZ);
            BlockState floor = level.getBlockState(pos);
            if (floor.isFaceSturdy(level, pos, Direction.UP)) {
                return y + 1.0;
            }
        }
        return Double.NaN;
    }

    /**
     * Finds a teleport spot for the caster in the column at
     * (blockX, blockZ): scans from scanTopY down to
     * scanBottomY for the first sturdy top where the caster's
     * own bounding box fits collision-free. Returns the feet
     * position (block center, on top of the floor block), or
     * null when the column offers no spot.
     */
    static double[] findFloorSpot(ServerLevel level, Mob mob, int blockX, int blockZ, int scanTopY, int scanBottomY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double halfWidth = mob.getBbWidth() / 2.0;
        for (int y = scanTopY; y >= scanBottomY; y--) {
            pos.set(blockX, y, blockZ);
            BlockState floor = level.getBlockState(pos);
            if (!floor.isFaceSturdy(level, pos, Direction.UP)) {
                continue;
            }
            double feetX = blockX + 0.5;
            double feetY = y + 1.0;
            double feetZ = blockZ + 0.5;
            AABB box = new AABB(feetX - halfWidth, feetY, feetZ - halfWidth,
                                feetX + halfWidth, feetY + mob.getBbHeight(), feetZ + halfWidth);
            if (level.noCollision(mob, box)) {
                return new double[]{feetX, feetY, feetZ};
            }
            return null;
        }
        return null;
    }

    /**
     * Spawns one real vanilla EvokerFangs owned by the caster
     * at the given position, with the given warmup delay in
     * ticks (the bite lands warmup + 8 ticks later, per the
     * class comment). Returns whether the fang was spawned.
     */
    static boolean spawnFang(AbilityContext ctx, double x, double y, double z, int warmupTicks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return false;
        }
        Mob caster = ctx.mob();
        EvokerFangs fang = new EvokerFangs(level, x, y, z, caster.getYRot(), warmupTicks, caster);
        return level.addFreshEntity(fang);
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
