package mod.advancedmobs.ability.cavespider;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * CaveSpiderAbilitySupport: helpers shared by the Cave Spider's
 * 5 abilities.
 *
 * ENEMY RULE (used by every area effect in this package): a
 * living entity counts as an enemy of the caster when it is
 * alive, is not the caster, is not riding the caster or being
 * ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and is
 * either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the Zombie's,
 * Spider's, and other implemented mobs' abilities use; it is
 * duplicated here because those classes are package-private to
 * their own packages.
 *
 * BLOCK PLACEMENT: placeCobweb and placeTripwire are the
 * placement paths for every real block this package sets
 * (Neurotoxin Webbing's wrap, Silk Minefield's strings and
 * patches). Callers gate every placement pass on
 * AbilityContext.mobGriefingAllowed() (the standing terrain
 * rule: permanent changes, only with mobGriefing on). A single
 * position is only filled when the current block can be
 * replaced, holds no fluid, has no block entity (containers
 * are never overwritten), and is not unbreakable (destroy
 * speed not negative). A tripwire additionally needs a
 * face-sturdy floor under it: string laid across a cave floor,
 * never floating. Placed blocks are permanent terrain: nothing
 * records them for removal, except that a tripped minefield
 * wire consumes itself (see SilkMinefieldAbility).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval (documented per ability), never
 * as whole-level scans.
 */
final class CaveSpiderAbilitySupport {

    private CaveSpiderAbilitySupport() {}

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
     * One bounded entity query; call on a pulse interval, not per
     * tick.
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
     * Whether a position may be filled by an ability-placed
     * block: the current block can be replaced, holds no fluid,
     * has no block entity (containers are never overwritten),
     * and is not unbreakable (destroy speed not negative).
     * Callers must already have gated the placement pass on
     * mobGriefing.
     */
    static boolean canPlaceAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }

    /**
     * Places one permanent cobweb at the position when the
     * shared safety checks pass (see the class comment).
     * Returns whether a web was placed.
     */
    static boolean placeCobweb(ServerLevel level, BlockPos pos) {
        if (!canPlaceAt(level, pos)) {
            return false;
        }
        level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    /**
     * Places one permanent tripwire (string) at the position
     * when the shared safety checks pass AND the block below is
     * face-sturdy on top, so the string lies across a floor
     * instead of floating in open air (a tripwire with nothing
     * under it would not survive its own neighbor update).
     * Returns whether a wire was placed.
     */
    static boolean placeTripwire(ServerLevel level, BlockPos pos) {
        if (!canPlaceAt(level, pos)) {
            return false;
        }
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        level.setBlock(pos, Blocks.TRIPWIRE.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }
}
