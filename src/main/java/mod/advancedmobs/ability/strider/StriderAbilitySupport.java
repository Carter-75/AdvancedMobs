package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * StriderAbilitySupport: helpers shared by the Strider's
 * 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the caster
 * or being ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and is
 * either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages. NOTE the riding clause matters doubly here: the
 * Strider Stack's baby rides the caster, and is an ally besides,
 * so it can never be caught by the caster's own area checks.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. The two terrain
 * writers are Lava Wake's magma crust (it replaces a lava block
 * the caster is standing in with a magma block, and nothing
 * else) and placeFire() behind Magma Spit (dry, replaceable,
 * sturdy ground beneath, never a block entity).
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers every ability in this
 * package uses, on the caster or on victims (Lava Wake's
 * scorch). Transient modifiers are not persisted, so one
 * applied to a victim vanishes on its own if that victim
 * unloads; removing one that is absent is a no-op. Both
 * tolerate a missing attribute instance: a strider has NO
 * ATTACK_DAMAGE instance in 26.2 (verified against the jar:
 * Strider.createAttributes adds only MOVEMENT_SPEED 0.175 on
 * top of the animal set, and LivingEntity.createLivingAttributes
 * has no ATTACK_DAMAGE), so every damage number in this package
 * is dealt by code through hurtServer with a mobAttack source
 * credited to the caster, never through a melee swing.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class StriderAbilitySupport {

    private StriderAbilitySupport() {}

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
     * Places one permanent fire block at the position, when the
     * spot can hold one: the current block is replaceable, dry
     * (no fluid), not already fire, has no block entity, and the
     * block below has a sturdy top face for the fire to sit on.
     * Uses BaseFireBlock.getState, the same state vanilla picks
     * (soul fire on soul soil, normal fire elsewhere). Returns
     * whether a fire was placed. Callers must already have
     * checked AbilityContext.mobGriefingAllowed().
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
        return level.setBlock(pos, BaseFireBlock.getState(level, pos),
            net.minecraft.world.level.block.Block.UPDATE_ALL);
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
