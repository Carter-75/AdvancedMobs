package mod.advancedmobs.ability.turtle;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * TurtleAbilitySupport: helpers shared by the Turtle's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package: the Shellshock Slam's toss ring): a living entity
 * counts as an enemy of the caster when it is alive, is not
 * the caster, is not riding the caster or being ridden by
 * it, is not in the caster's ally set (MobWarData.isAlly,
 * the war system's alliance record), and is either the
 * caster's current combat target or another Mob. Players
 * are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * ALLY RULE: a living entity counts as an ally of the caster
 * when it is alive, is not the caster, and either side's war
 * data lists the other as an ally (the either-direction
 * check the Breeze, Bee, and Wolf packages use). Ancient
 * Wisdom targets allies (and the caster itself) with this
 * rule, and Egg Bastion registers its hatchlings with it.
 *
 * STEERING: steerToward() sets an entity's velocity straight
 * at a point at a capped speed and returns the distance to
 * the point before the move (the Phantom / Dolphin pattern).
 * A swimming turtle's movement answers per-tick velocity
 * writes the same way a dolphin's does, so Tide Ram drives
 * its charge this way instead of fighting vanilla navigation.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only block change in
 * this package is Egg Bastion's turtle egg clutch, and it is
 * PERMANENT and gated: egg blocks are placed only when
 * AbilityContext.mobGriefingAllowed() is true at the moment
 * of placement, and placement never overwrites a container
 * or other block entity, an unbreakable block, or a
 * non-replaceable cell, and never floats (a solid, dry block
 * must sit directly below). Placed eggs stay placed; the
 * babies that "hatch" are summoned in code on the ability's
 * delay, they do not consume the blocks. With mobGriefing
 * off, no eggs are placed and the absorption, the guard
 * settle, and the delayed hatchlings all still run. Shell
 * Fortress, Tide Ram, Ancient Wisdom, and Shellshock Slam
 * change no blocks at all.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance (a mob that lacks the attribute
 * simply never feels the modifier), which matters here: the
 * turtle's attribute set carries no ATTACK_DAMAGE and no
 * guaranteed ATTACK_KNOCKBACK instance (see the provider
 * comment), so Tide Ram applies its design knockback line
 * through the tolerant helper and does the real shove with a
 * velocity write.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and callers
 * only run them on a pulse interval or a one-shot moment,
 * never as whole-level scans.
 */
final class TurtleAbilitySupport {

    private TurtleAbilitySupport() {}

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

    /**
     * All allies of the context mob inside a box of the given
     * horizontal radius and vertical reach around a center
     * point (the caster itself is never included; callers add
     * it explicitly when the design says so). One bounded
     * entity query; call on a pulse interval only.
     */
    static List<LivingEntity> alliesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                          double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isAlly(ctx, e));
    }

    /** All allies of the context mob within the given radius of the mob itself. */
    static List<LivingEntity> alliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return alliesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /**
     * Sets the entity's velocity straight at the given point at
     * the given speed (capped so it never overshoots the point)
     * and returns the distance to the point before the move.
     * See the STEERING note in the class comment.
     */
    static double steerToward(LivingEntity entity, double x, double y, double z, double speed) {
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
        entity.hurtMarked = true;
        return dist;
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
     * Finds the feet height (the top of the highest solid,
     * non-fluid block with air directly above it) in the
     * column at x/z, scanning from 3 blocks above the hint
     * down to 8 blocks below it: at most 12 block reads, and
     * callers run it once per placement, never per tick.
     * Returns the hint unchanged when the column has no usable
     * surface in range (the Camel package's helper, same
     * contract).
     */
    static double findGroundY(ServerLevel level, double x, double yHint, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(yHint) + 3;
        int bottom = (int) Math.floor(yHint) - 8;
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockState(pos.above()).isAir()) {
                return y + 1.0;
            }
        }
        return yHint;
    }

    /** A BLOCK_CRUMBLE option carrying the block state under the given position (dirt when there is none). */
    static BlockParticleOption groundCrumble(ServerLevel level, double x, double y, double z) {
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
        BlockState state = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        return new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, state);
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
