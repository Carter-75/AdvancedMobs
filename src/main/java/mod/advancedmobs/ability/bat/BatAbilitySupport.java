package mod.advancedmobs.ability.bat;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.UUID;

/**
 * BatAbilitySupport: helpers shared by the Bat's 5 abilities.
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
 * THE BAT'S SHEET (verified against the real 26.2 jar): Bat
 * extends AmbientCreature and builds its attributes from
 * Mob.createMobAttributes plus MAX_HEALTH 6.0 only. There is
 * NO ATTACK_DAMAGE instance anywhere in its chain, and a
 * vanilla bat has no attack goal at all, so every damage
 * number in this package is a flat code value dealt through a
 * damage source credited to the bat (or to a swarm copy), and
 * every flight is driven by per-tick velocity writes, the
 * steering pattern the Phantom and Allay packages use for
 * natural fliers. Bat.setResting(boolean) is public in 26.2:
 * abilities that need the bat airborne clear the resting flag
 * in start() so a bat that was hanging from a ceiling takes
 * off instead of casting from its perch.
 *
 * TERRAIN: only Stalactite Drop changes blocks in this
 * package (its falling spike and the hazard shard it pins);
 * it follows the standing terrain rule through
 * AbilityContext.mobGriefingAllowed(). The other four
 * abilities place and break nothing.
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
final class BatAbilitySupport {

    private BatAbilitySupport() {}

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

    /** Resolves an entity by a UUID stored directly in the state tag under the given key, or null. */
    static Entity resolveStored(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
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

    /** Sets the caster's velocity straight at a point and returns the distance to it before the move. */
    static double steerToward(AbilityContext ctx, double x, double y, double z, double speed) {
        return steerEntityToward(ctx.mob(), x, y, z, speed);
    }

    /** Sets any entity's velocity straight at a point and returns the distance to it before the move. */
    static double steerEntityToward(Entity entity, double x, double y, double z, double speed) {
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
        return dist;
    }

    /**
     * Whether a falling entity's underside is within one tick
     * of falling travel above the first collidable block below
     * it: scans down at most 4 blocks from the block under the
     * entity. Only used in the mobGriefing-off interception.
     * (The Icicle Fall pattern, via the Allay package.)
     */
    static boolean aboutToLand(ServerLevel level, Entity entity) {
        double bottom = entity.getBoundingBox().minY;
        double fallPerTick = -entity.getDeltaMovement().y;
        int bx = (int) Math.floor(entity.getX());
        int bz = (int) Math.floor(entity.getZ());
        int startY = (int) Math.floor(bottom) - 1;
        for (int y = startY; y >= startY - 3; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState blockState = level.getBlockState(pos);
            VoxelShape shape = blockState.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double groundTop = y + shape.max(Direction.Axis.Y);
                return bottom - groundTop <= Math.max(0.7, fallPerTick * 1.05);
            }
        }
        return false;
    }
}
