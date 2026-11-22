package mod.advancedmobs.ability.guardian;

import mod.advancedmobs.ability.AbilityContext;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * GuardianAbilitySupport: helpers shared by the Guardian's 5
 * abilities.
 *
 * ENEMY RULE (used by every area effect in this package: the Laser
 * Lattice chain pick, the Tidal Bulwark Ring current, the Shard
 * Mine Bloom proximity checks, the Crushing Depth Aura pulses): a
 * living entity counts as an enemy of the caster when it is alive,
 * is not the caster, is not riding the caster or being ridden by
 * it, is not in the caster's ally set (MobWarData.isAlly, the war
 * system's alliance record), and is either the caster's current
 * combat target or another Mob. Players are only affected when
 * they are the caster's current target and are in survival or
 * adventure mode, so area abilities never grief creative or
 * spectator players who happen to stand nearby. This is the same
 * rule the Drowned's and other implemented mobs' abilities use; it
 * is duplicated here because those classes are package-private to
 * their own packages.
 *
 * DISPLAY HELPER: displays are configured through NBT in 26.2
 * (their setters are private), so the helper builds the entity
 * through EntityType.loadEntityRecursive, the supported spawn
 * path already used by Grave Eruption, the Bogged's shelves, and
 * the Drowned's Leviathan shards. NBT keys verified against the
 * real 26.2 jar: block displays take "block_state"
 * (NbtUtils.writeBlockState), every display takes a
 * "transformation" compound with translation, scale, left_rotation
 * and right_rotation float lists (quaternions, x y z w), an
 * optional "brightness" compound (block and sky light levels),
 * and "teleport_duration" so position updates glide instead of
 * snapping. The caller positions the returned entity with snapTo
 * and adds it to the level.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with a
 * tight AABB built from the given radius, and callers only run
 * them on a pulse interval (documented per ability), never as
 * whole-level scans.
 */
final class GuardianAbilitySupport {

    private GuardianAbilitySupport() {}

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
     * Creates (but does not add to the level) a block display
     * showing the given block state at full brightness (block and
     * sky light 15, so it glitters in dark water), uniformly
     * scaled and centered on its snap position, rotated around
     * the Y axis by the given angle, gliding over 5 ticks when
     * teleported. The translation centers the block on the snap
     * point: a block display's pivot is its bottom-north-west
     * corner, so the block is shifted back by half its scale on
     * every axis. Returns null if the entity could not be created.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState blockState, float scale, double yAngle) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(blockState));
        float half = -scale / 2.0f;
        float sin = (float) Math.sin(yAngle / 2.0);
        float cos = (float) Math.cos(yAngle / 2.0);
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(half, half, half));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(0.0f, sin, 0.0f, cos));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 5);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }
}
