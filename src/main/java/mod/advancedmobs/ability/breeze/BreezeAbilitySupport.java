package mod.advancedmobs.ability.breeze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * BreezeAbilitySupport: helpers shared by the Breeze's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package):
 * a living entity counts as an enemy of the caster when it is
 * alive, is not the caster, is not riding the caster or being
 * ridden by it, is not in the caster's ally set (MobWarData.isAlly,
 * the war system's alliance record), and is either the caster's
 * current combat target or another Mob. Players are only affected
 * when they are the caster's current target and are in survival
 * or adventure mode, so area abilities never grief creative or
 * spectator players who happen to stand nearby. This is the same
 * rule the other implemented mobs' abilities use; it is duplicated
 * here because those classes are package-private to their own
 * packages.
 *
 * ALLY RULE (used by Gust Step's tunnel, which flings friends as
 * well as foes): a living entity counts as an ally of the caster
 * when it is alive, is not the caster, and has an alliance record
 * with the caster in MobWarData (checked in the candidate's
 * direction: the candidate's own data lists the caster).
 *
 * TERRAIN: no ability in this package places or breaks blocks.
 * The Crosswind Arena's ring is block DISPLAY entities (pure
 * visuals, no hitbox, discarded in end()), the wind charges are
 * vanilla projectiles whose burst is a wind explosion that never
 * breaks blocks by vanilla design, and everything else is
 * velocity, effects, and particles. The mobGriefing gate
 * therefore does not apply anywhere in this package, and each
 * ability's comments say so.
 *
 * WIND CHARGES: fireWindCharge() builds a real vanilla
 * BreezeWindCharge (the projectile the breeze's own ranged attack
 * fires, class verified against the 26.2 jar:
 * projectile.hurtingprojectile.windcharge.BreezeWindCharge, with
 * a public (Breeze, Level) constructor). On impact the charge
 * bursts on its own: a small wind explosion that deals minor
 * damage and knocks entities away from the burst point. That
 * vanilla burst is the per-charge pop the Wind Lance Volley
 * design describes; the volley's oversized last charge adds a
 * code burst on top (see that class).
 *
 * DISPLAYS: createPaneDisplay() builds one block display through
 * EntityType.loadEntityRecursive from NBT, the supported
 * configuration path in 26.2 (display setters are private). The
 * NBT sets full brightness (block and sky light 15) and a
 * teleport duration of 2 ticks so per-interval snapTo calls glide
 * instead of snapping. A display is a pure visual: no hitbox, no
 * collision, no block change.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op. Both tolerate a missing
 * attribute instance (a mob that lacks the attribute simply
 * never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with a
 * tight AABB built from the given radius, and callers only run
 * them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class BreezeAbilitySupport {

    private BreezeAbilitySupport() {}

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
     * Whether the given living entity is an ally of the context
     * mob: alive, not the caster, and the candidate's own war
     * data lists the caster as an ally.
     */
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
     * Whether a projectile is hostile to the context mob: owned
     * by an enemy, or ownerless. The caster's own projectiles and
     * its allies' projectiles are never hostile. Used by the Wind
     * Wall's deflection and the Crosswind Arena's projectile
     * drift.
     */
    static boolean isHostileProjectile(AbilityContext ctx, Projectile projectile) {
        if (projectile == null || !projectile.isAlive()) {
            return false;
        }
        Entity owner = projectile.getOwner();
        if (owner == null) {
            return true;
        }
        if (owner == ctx.mob()) {
            return false;
        }
        if (owner instanceof LivingEntity living) {
            return isEnemy(ctx, living);
        }
        return false;
    }

    /**
     * Fires one real vanilla BreezeWindCharge from the given
     * point along the given (not necessarily normalized)
     * direction at the given speed in blocks per tick. Returns
     * the charge, or null when the caster is not a breeze or the
     * charge could not be added to the level.
     */
    static BreezeWindCharge fireWindCharge(AbilityContext ctx, Vec3 from, Vec3 direction, double speed) {
        if (!(ctx.mob() instanceof Breeze breeze)) {
            return null;
        }
        BreezeWindCharge charge = new BreezeWindCharge(breeze, ctx.level());
        charge.snapTo(from.x, from.y, from.z, 0.0f, 0.0f);
        Vec3 velocity = direction.lengthSqr() > 1.0e-6
            ? direction.normalize().scale(speed)
            : new Vec3(0.0, 0.0, speed);
        charge.setDeltaMovement(velocity);
        if (!ctx.level().addFreshEntity(charge)) {
            return null;
        }
        return charge;
    }

    /**
     * Creates (but does not add to the level) one Crosswind Arena
     * ring pane: a block display showing a white stained glass
     * pane at the given vertical scale, centered on its snap
     * position, at full brightness, gliding with a teleport
     * duration of 2 ticks. Returns null if the entity could not
     * be created. See the DISPLAYS note in the class comment.
     */
    static Entity createPaneDisplay(ServerLevel level, float scaleY) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(Blocks.STAINED_GLASS_PANE.white().defaultBlockState()));
        tag.put("transformation", transformation(scaleY));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** Resolves a tracked entity by UUID against the level, or null when gone. */
    static Entity resolve(ServerLevel level, UUID id) {
        return id == null ? null : level.getEntity(id);
    }

    /** Adds or refreshes a transient attribute modifier on the given entity. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    /**
     * The distance from a point to the segment from a to b, all
     * in three dimensions. Used by Gust Step's tunnel touch test.
     */
    static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        if (lengthSqr < 1.0e-6) {
            return point.distanceTo(a);
        }
        double t = Math.max(0.0, Math.min(1.0, point.subtract(a).dot(ab) / lengthSqr));
        Vec3 closest = a.add(ab.scale(t));
        return point.distanceTo(closest);
    }

    /** A display transformation compound: centered translation, per-axis scale, identity rotations. */
    private static CompoundTag transformation(float scaleY) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(-0.5f, -scaleY / 2.0f, -0.5f));
        transformation.put("scale", floatList(1.0f, scaleY, 1.0f));
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
