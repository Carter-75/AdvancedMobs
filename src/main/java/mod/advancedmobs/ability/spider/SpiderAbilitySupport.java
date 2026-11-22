package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * SpiderAbilitySupport: helpers shared by the Spider's 5 abilities.
 *
 * ENEMY RULE (used by every area effect in this package): a living
 * entity counts as an enemy of the caster when it is alive, is not
 * the caster, is not riding the caster or being ridden by it, is
 * not in the caster's ally set (MobWarData.isAlly, the war
 * system's alliance record), and is either the caster's current
 * combat target or another Mob. Players are only affected when
 * they are the caster's current target and are in survival or
 * adventure mode, so area abilities never grief creative or
 * spectator players who happen to stand nearby. This is the same
 * rule the Zombie's, Stray's, and other implemented mobs'
 * abilities use; it is duplicated here because those classes are
 * package-private to their own packages.
 *
 * COBWEB PLACEMENT: placeCobweb is the one placement path for
 * every real cobweb this package sets (Strangling Web Shot, Web
 * Fortress). Callers gate the whole placement pass on
 * AbilityContext.mobGriefingAllowed() (the standing terrain rule:
 * permanent changes, only with mobGriefing on). A single position
 * is only webbed when the current block can be replaced, holds no
 * fluid, has no block entity (containers are never overwritten),
 * and is not unbreakable (destroy speed not negative). Placed
 * webs are permanent terrain: nothing records them for removal.
 *
 * DISPLAY HELPER: displays are configured through NBT in 26.2
 * (their setters are private), so the helper builds the entity
 * through EntityType.loadEntityRecursive, the supported spawn
 * path already used by Grave Eruption and the Stray's abilities.
 * NBT keys verified against the real 26.2 jar: block displays
 * take "block_state" (NbtUtils.writeBlockState) and every display
 * takes a "transformation" compound with translation, scale,
 * left_rotation and right_rotation float lists (quaternions,
 * x y z w), plus a "brightness" compound with "block" and "sky"
 * light values. This helper always sets full brightness (15 and
 * 15) so the egg sacs read clearly in dark caves. The caller
 * positions the returned entity with snapTo and adds it to the
 * level.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with a
 * tight AABB built from the given radius, and callers only run
 * them on a pulse interval (documented per ability), never as
 * whole-level scans.
 */
final class SpiderAbilitySupport {

    private SpiderAbilitySupport() {}

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
     * Places one permanent cobweb at the position when the safety
     * checks pass (see the class comment). Callers must already
     * have gated the placement pass on mobGriefing. Returns
     * whether a web was placed.
     */
    static boolean placeCobweb(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false; // Never overwrite containers or other block entities.
        }
        if (state.getDestroySpeed(level, pos) < 0.0f) {
            return false; // Never touch unbreakable blocks.
        }
        level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    /**
     * Whether a cobweb placed at the position would hang on
     * something: the block below is face-sturdy on top, or at
     * least one horizontal neighbor is a solid non-replaceable
     * block (a wall, a door frame, a tree trunk). Web Fortress
     * uses this so its lattice strings across gaps and doorways
     * instead of floating in open air.
     */
    static boolean hasWebSupport(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!neighborState.canBeReplaced() && neighborState.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates (but does not add to the level) a block display
     * showing the given block state at full brightness, uniformly
     * scaled and centered on its snap position, with identity
     * rotation. The translation centers the block on the snap
     * point: a block display's pivot is its bottom-north-west
     * corner, so the block is shifted back by half its scale on
     * every axis. Returns null if the entity could not be created.
     */
    static Entity createBrightBlockDisplay(ServerLevel level, BlockState blockState, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(blockState));
        float half = -scale / 2.0f;
        tag.put("transformation", transformation(scale, half));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** A display transformation compound: centered translation, uniform scale, identity rotations. */
    private static CompoundTag transformation(float scale, float half) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(half, half, half));
        transformation.put("scale", floatList(scale, scale, scale));
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
