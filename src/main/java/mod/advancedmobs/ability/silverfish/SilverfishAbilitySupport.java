package mod.advancedmobs.ability.silverfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * SilverfishAbilitySupport: helpers shared by the Silverfish's
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
 * packages.
 *
 * SWARM RULE (used by Hive Mind Link): a living entity counts
 * as a swarm ally when it is a silverfish or an endermite,
 * alive, not the caster, and allied with the caster in EITHER
 * direction (the caster's data lists it, or its own data lists
 * the caster). Alliance records are the war system's team
 * record, which is what the design's "/team team assignment"
 * means everywhere else in this mod.
 *
 * SUMMONS: spawnAlliedFish() is the one summon path for this
 * package (Infested Network). It creates the fish with
 * EntitySpawnReason.MOB_SUMMONED and finalizeSpawn, allies it
 * with the caster in both directions, registers the pair in
 * SummonerTracker (which also tags the fish umw_summoned for
 * the natural spawn blocker), and orders it onto a target:
 * the same flow Call the Cracks and Scarab Brood use.
 *
 * DISPLAYS: createBlockDisplay() builds a block display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). Unlike the endermite helper it takes a per-axis
 * scale, because Rubble Form flattens its stone. The display's
 * pivot is its bottom-north-west corner; the helper translates
 * by minus half the scale on X and Z and leaves Y
 * untranslated, so a display sits ON its snap position (feet
 * at the snap Y) and is centered horizontally. Displays are
 * pure visuals: no hitbox, no collision, no health.
 *
 * TERRAIN: isDiggable() is the placement safety check for the
 * one real terrain change in this package (Tunnel Collapse
 * eating ceiling supports): a block may be eaten only when it
 * is a real block (not air, no fluid), has no block entity
 * (containers are never touched), and is breakable (destroy
 * speed not negative). Tunnel Collapse only calls it when
 * AbilityContext.mobGriefingAllowed() is true.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class SilverfishAbilitySupport {

    private SilverfishAbilitySupport() {}

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
     * Whether the given living entity is a swarm ally of the
     * caster (see the SWARM RULE in the class comment): a
     * silverfish or endermite allied in either direction.
     */
    static boolean isSwarmAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (!(candidate instanceof Silverfish) && !(candidate instanceof Endermite)) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID());
    }

    /**
     * All swarm allies of the caster within the given horizontal
     * radius and vertical reach of the caster, nearest first,
     * capped at maxLinks entries. Two bounded typed queries (one
     * per swarm species); call on a pulse interval, or from
     * canTrigger where the answer gates the whole ability.
     */
    static List<LivingEntity> swarmAllies(AbilityContext ctx, double radius, int maxLinks) {
        Mob self = ctx.mob();
        AABB box = new AABB(self.getX() - radius, self.getY() - 3.0, self.getZ() - radius,
                            self.getX() + radius, self.getY() + 3.0, self.getZ() + radius);
        List<LivingEntity> allies = new ArrayList<>();
        for (Silverfish fish : ctx.level().getEntitiesOfClass(Silverfish.class, box, e -> isSwarmAlly(ctx, e))) {
            allies.add(fish);
        }
        for (Endermite mite : ctx.level().getEntitiesOfClass(Endermite.class, box, e -> isSwarmAlly(ctx, e))) {
            allies.add(mite);
        }
        allies.sort((a, b) -> Double.compare(self.distanceToSqr(a), self.distanceToSqr(b)));
        if (allies.size() > maxLinks) {
            return new ArrayList<>(allies.subList(0, maxLinks));
        }
        return allies;
    }

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    static boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    /**
     * Spawns one allied silverfish at the given spot (the
     * caster's own position as fallback when the spot is not
     * free), allies it with the caster in both directions,
     * registers the pair in SummonerTracker, and orders it onto
     * the given target (the caster's live target when null).
     * Returns the fish, or null when it could not be created or
     * added. See the SUMMONS note in the class comment.
     */
    static Silverfish spawnAlliedFish(AbilityContext ctx, ServerLevel level, double spawnX, double spawnZ,
                                      LivingEntity target) {
        Mob caster = ctx.mob();
        Silverfish fish = EntityTypes.SILVERFISH.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (fish == null) {
            return null;
        }
        if (!isFreeSpot(level, fish, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        fish.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        fish.finalizeSpawn(level, level.getCurrentDifficultyAt(fish.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(fish)) {
            return null;
        }
        ctx.data().addAlly(fish.getUUID());
        MobWarData.get(fish).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(fish.getUUID(), caster.getUUID());
        LivingEntity order = target != null ? target : ctx.target();
        if (order != null && order != fish) {
            fish.setTarget(order);
        }
        return fish;
    }

    /**
     * Whether the block at the position may be eaten by Tunnel
     * Collapse: a real, dry, diggable block with no block
     * entity. Air and fluids are never eaten, containers and
     * other block entities are never touched, and unbreakable
     * blocks (negative destroy speed) are never touched. Only
     * called when mobGriefing is on.
     */
    static boolean isDiggable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }

    /**
     * Creates (but does not add to the level) a block display
     * showing the given block state with a per-axis scale,
     * centered horizontally on its snap position with its base
     * at the snap Y (see the class comment). When bright is
     * set, the display renders at full brightness (block and
     * sky light 15). Returns null if the entity could not be
     * created.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState blockState,
                                     float scaleX, float scaleY, float scaleZ, boolean bright) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(blockState));
        tag.put("transformation", transformation(scaleX, scaleY, scaleZ,
            -scaleX / 2.0f, 0.0f, -scaleZ / 2.0f));
        if (bright) {
            tag.put("brightness", fullBrightness());
        }
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
    }

    /** A full brightness compound (block and sky light 15) for display NBT. */
    private static CompoundTag fullBrightness() {
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        return brightness;
    }

    /** A display transformation compound: translation, scale, identity rotations. */
    private static CompoundTag transformation(float scaleX, float scaleY, float scaleZ,
                                              float transX, float transY, float transZ) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(transX, transY, transZ));
        transformation.put("scale", floatList(scaleX, scaleY, scaleZ));
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
