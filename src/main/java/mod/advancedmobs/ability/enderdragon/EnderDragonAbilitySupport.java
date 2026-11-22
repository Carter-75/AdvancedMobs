package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * EnderDragonAbilitySupport: helpers shared by the Ender
 * Dragon's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the dragon
 * when it is alive, is not the dragon, is not riding the
 * dragon or being ridden by it, is not in the dragon's ally
 * set (MobWarData.isAlly, the war system's alliance record),
 * and is either the dragon's current combat target or another
 * Mob. Players are only affected when they are the dragon's
 * current target and are in survival or adventure mode, so
 * area abilities never grief creative or spectator players
 * who happen to stand nearby. For the dragon the target is
 * the framework's fallback (the nearest living, non-spectator
 * player within 192 blocks; see AbilityContext.target()),
 * because the dragon never has a vanilla target. This is the
 * same rule the other implemented mobs' abilities use; it is
 * duplicated here because those classes are package-private
 * to their own packages.
 *
 * FIREBALL LAUNCHER: launchDragonFireball() fires one real
 * vanilla DragonFireball projectile owned by the dragon, the
 * construction AbstractHurtingProjectile takes (level,
 * owner, acceleration direction), mirroring the Wither
 * package's skull launcher. A fireball's own impact behavior
 * is vanilla and stays vanilla: on a hit it blooms into a
 * lingering dragon breath area effect cloud (radius 3,
 * duration 600 ticks) with an instant damage effect. The
 * abilities in this package aim the fireballs; they never
 * reimplement the impact.
 *
 * BREATH CLOUDS: spawnBreathCloud() builds one AreaEffectCloud
 * with the dragon breath look (a DRAGON_BREATH particle option
 * with power 1.0, the option type that particle takes in
 * 26.2, verified against the real jar) and the same instant
 * damage effect the vanilla fireball cloud carries. Clouds
 * are entities with a fixed lifetime: they expire on their
 * own, are not tracked by the abilities that spawn them, and
 * are not block changes, so the terrain rule does not gate
 * them.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only real block
 * changes in this package are Fountain Spike Slam's obsidian
 * spikes, and they are PERMANENT and only happen when
 * AbilityContext.mobGriefingAllowed() is true. topSolidY()
 * and the spike placement checks refuse containers and other
 * block entities and unbreakable blocks.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the abilities in this
 * package use. Transient modifiers are not persisted, so one
 * applied to a victim vanishes on its own if that victim
 * unloads; removing one that is absent is a no-op. The
 * helpers are null-tolerant because not every mob carries
 * every attribute: verified against the real 26.2 jar, the
 * Ender Dragon's attribute supplier is Mob.createMobAttributes
 * plus MAX_HEALTH and CAMERA_DISTANCE, and
 * LivingEntity.createLivingAttributes carries SCALE and
 * ATTACK_KNOCKBACK but NOT ATTACK_DAMAGE and NOT
 * FLYING_SPEED, so the dragon has no instance of those two
 * attributes and modifiers on them are inert on her. The
 * abilities that the design builds on those attributes say
 * so in their own spec comments and carry the effect another
 * way.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and callers
 * only run them on a pulse interval or a one-shot moment,
 * never as whole-level scans.
 */
final class EnderDragonAbilitySupport {

    private EnderDragonAbilitySupport() {}

    /** The dragon breath particle option (power 1.0), shared by every breath visual in this package. */
    static final PowerParticleOption BREATH_PARTICLE =
        PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f);

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

    /**
     * Fires one vanilla dragon fireball from the dragon's head
     * height toward the given aim point. Returns the fireball,
     * or null when it could not be added to the level. The
     * launch math mirrors the Wither package's skull launcher:
     * the constructor takes the acceleration direction, the
     * fireball is snapped to a mouth point in front of the
     * dragon, and given an initial velocity so it does not
     * start slow.
     */
    static DragonFireball launchDragonFireball(AbilityContext ctx, ServerLevel level, Vec3 aim) {
        Mob caster = ctx.mob();
        Vec3 from = new Vec3(caster.getX(), caster.getY() + caster.getBbHeight() * 0.55, caster.getZ());
        Vec3 direction = new Vec3(aim.x - from.x, aim.y - from.y, aim.z - from.z);
        if (direction.lengthSqr() < 1.0e-4) {
            return null;
        }
        direction = direction.normalize();
        DragonFireball fireball = new DragonFireball(level, caster, direction.scale(0.1));
        Vec3 mouth = from.add(direction.scale(3.0));
        fireball.snapTo(mouth.x, mouth.y, mouth.z, 0.0f, 0.0f);
        fireball.setDeltaMovement(direction.scale(0.7));
        if (!level.addFreshEntity(fireball)) {
            return null;
        }
        return fireball;
    }

    /**
     * Spawns one dragon breath area effect cloud at the given
     * position: the given radius, the given fixed lifetime in
     * ticks (no growth or shrink over its life), no wait time,
     * the breath particle look, and one instant damage effect
     * (amplifier 0, the vanilla fireball cloud's payload, 6
     * damage per application while an enemy stands in it).
     * The cloud is owned by the dragon so kills credit her.
     * Fire and forget: the cloud expires on its own and is not
     * tracked. See the BREATH CLOUDS note in the class comment.
     */
    static void spawnBreathCloud(ServerLevel level, Mob owner, double x, double y, double z,
                                 float radius, int durationTicks) {
        AreaEffectCloud cloud = new AreaEffectCloud(level, x, y, z);
        cloud.setOwner(owner);
        cloud.setRadius(radius);
        cloud.setDuration(durationTicks);
        cloud.setWaitTime(0);
        cloud.setRadiusPerTick(0.0f);
        cloud.setCustomParticle(BREATH_PARTICLE);
        cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 0));
        level.addFreshEntity(cloud);
    }

    /**
     * The Y of the topmost solid block in the column at (x, z),
     * scanning from fromY down to toY: a block counts as ground
     * when its collision shape is non-empty and it holds no
     * fluid. Returns Integer.MIN_VALUE when the window holds
     * no ground. Used by Fountain Spike Slam to seat its rings
     * on the end stone around the fountain.
     */
    static int topSolidY(ServerLevel level, int x, int z, int fromY, int toY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, fromY, z);
        for (int y = fromY; y >= toY; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Adds or refreshes a transient attribute modifier on the given entity. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute,
                               net.minecraft.resources.Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
