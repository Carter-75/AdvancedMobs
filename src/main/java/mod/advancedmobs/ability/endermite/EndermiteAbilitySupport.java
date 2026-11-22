package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * EndermiteAbilitySupport: helpers shared by the Endermite's 5
 * abilities.
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
 * BITE CLOCK: two of the mite's abilities (Infestation Bite,
 * Gorged Mite) react to bites the mite lands. A landed melee
 * hit is read off the caster itself: Mob.doHurtTarget calls
 * LivingEntity.setLastHurtMob on the attacker when a hit lands
 * (verified against the 26.2 jar), so the caster's
 * getLastHurtMob() and getLastHurtMobTimestamp() name the most
 * recent victim and the tick it was bitten. primeBiteClock()
 * stores the current timestamp in the state tag at start(), so
 * a bite that landed before the ability began is never
 * answered; consumeLandedBite() is then polled on an interval
 * and returns the bitten enemy exactly once per landed bite.
 *
 * DISPLAYS: the create helpers build block and item displays
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters are
 * private). A display's pivot is its bottom-north-west corner;
 * the helpers translate by minus half the scale on X and Z and
 * leave Y untranslated, so a display sits ON its snap position
 * (feet at the snap Y) and is centered horizontally. Displays
 * are pure visuals: no hitbox, no collision, no health.
 *
 * TERRAIN: nothing in this package places or breaks real
 * blocks. The cracks of Call the Cracks are particle theater
 * and the shell of Living Stone Shell is a display, so the
 * mobGriefing terrain rule does not gate any of it; each
 * ability's comments say so where the design could be misread
 * as real block placement.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class EndermiteAbilitySupport {

    private EndermiteAbilitySupport() {}

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
     * Stores the caster's current last-hurt-mob timestamp in
     * the state tag ("hit_ts"). Called from start() so bites
     * landed before the activation are never answered.
     */
    static void primeBiteClock(AbilityContext ctx) {
        ctx.state().putInt("hit_ts", ctx.mob().getLastHurtMobTimestamp());
    }

    /**
     * Returns the enemy the caster has bitten since the last
     * call, or null when no new bite landed. Each landed bite
     * is reported exactly once: the stored timestamp advances
     * whether or not the victim turns out to be a valid enemy,
     * so a bite on an ally or a non-enemy is consumed silently
     * instead of being re-reported on every poll.
     */
    static LivingEntity consumeLandedBite(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int timestamp = mob.getLastHurtMobTimestamp();
        if (timestamp == state.getIntOr("hit_ts", -1)) {
            return null;
        }
        state.putInt("hit_ts", timestamp);
        LivingEntity victim = mob.getLastHurtMob();
        if (victim == null || !isEnemy(ctx, victim)) {
            return null;
        }
        return victim;
    }

    /**
     * Creates (but does not add to the level) a block display
     * showing the given block state, uniformly scaled, centered
     * horizontally on its snap position with its base at the
     * snap Y (see the class comment). When bright is set, the
     * display renders at full brightness (block and sky light
     * 15). Returns null if the entity could not be created.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState blockState, float scale, boolean bright) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(blockState));
        tag.put("transformation", transformation(scale, scale, scale,
            -scale / 2.0f, 0.0f, -scale / 2.0f));
        if (bright) {
            tag.put("brightness", fullBrightness());
        }
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
    }

    /**
     * Creates (but does not add to the level) an item display
     * showing one of the given item, uniformly scaled and
     * centered on its snap position on all three axes, with
     * the given item_display transform context and full
     * brightness (block and sky light 15) so the displayed
     * item stays readable in the dark. Returns null if the
     * entity could not be created.
     */
    static Entity createItemDisplay(ServerLevel level, Item item, float scale, String itemDisplayContext) {
        CompoundTag itemTag = new CompoundTag();
        itemTag.putString("id", BuiltInRegistries.ITEM.getKey(item).toString());
        itemTag.putInt("count", 1);
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemTag);
        tag.putString("item_display", itemDisplayContext);
        float half = -scale / 2.0f;
        tag.put("transformation", transformation(scale, scale, scale, half, half, half));
        tag.put("brightness", fullBrightness());
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
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
