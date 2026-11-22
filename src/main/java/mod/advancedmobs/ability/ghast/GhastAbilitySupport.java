package mod.advancedmobs.ability.ghast;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * GhastAbilitySupport: helpers shared by the Ghast's 5 abilities.
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
 * packages.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only real block change
 * in this package is placeFire(), used by Ember Wake Descent
 * for its burning line. Placed fire is PERMANENT and only
 * happens when AbilityContext.mobGriefingAllowed() is true.
 * placeFire() refuses containers and other block entities and
 * unbreakable blocks, and additionally requires a dry,
 * replaceable spot with a sturdy block beneath, so fire never
 * floats and never appears inside water. The fire charges
 * Ember Wake Descent drops are vanilla SmallFireballs, which
 * check the mobGriefing rule themselves before setting a
 * block on fire, and the LargeFireballs Colossal Grief and
 * Nether Rift Maw launch explode through vanilla's own
 * mobGriefing-respecting explosion interaction.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers every ability in this
 * package uses. Transient modifiers are not persisted, so one
 * applied to a victim vanishes on its own if that victim
 * unloads; removing one that is absent is a no-op. Both
 * helpers tolerate a mob that does not carry the attribute at
 * all (the instance is simply null).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class GhastAbilitySupport {

    private GhastAbilitySupport() {}

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
        return level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
    }

    /**
     * The Y of the standing surface in the column at (x, z):
     * one above the topmost block with a collision shape,
     * scanning from 6 above the reference height down to 12
     * below it. Returns the reference height itself when the
     * column has no solid block in that window. Used by Nether
     * Rift Maw to seat its vortex on the ground and by Ember
     * Wake Descent to seat its burning line.
     */
    static int findSurfaceY(ServerLevel level, int x, int z, int refY) {
        for (int y = refY + 6; y >= refY - 12; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return y + 1;
            }
        }
        return refY;
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
