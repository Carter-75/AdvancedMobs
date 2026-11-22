package mod.advancedmobs.ability.wither;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * WitherAbilitySupport: helpers shared by the Wither's 5
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
 * SKULL LAUNCHER: launchSkull() fires one real vanilla
 * WitherSkull projectile owned by the caster, the same
 * construction WitherBoss.performRangedAttack uses (the
 * projectile class lives in
 * net.minecraft.world.entity.projectile.hurtingprojectile in
 * 26.2, verified against the real jar). A skull's own impact
 * behavior is vanilla and stays vanilla: it explodes with
 * power 1 on hit (checking the mobGriefing rule itself before
 * breaking blocks), and applies the WITHER effect on a direct
 * entity hit (Wither II for the blue "dangerous" skulls).
 * Abilities in this package add their own effects around the
 * skulls; they never reimplement the impact.
 *
 * DISPLAYS: createSkullDisplay() builds one block display
 * showing a wither skeleton skull block state, through
 * EntityType.loadEntityRecursive from NBT, the supported
 * configuration path in 26.2 (display setters are private).
 * The NBT sets full brightness (block and sky light 15) and a
 * teleport duration of 2 ticks so displays glide as they move.
 * A display is a pure visual: no hitbox, no collision, no
 * health.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): every real block change in
 * this package is PERMANENT and only happens when
 * AbilityContext.mobGriefingAllowed() is true. findBloomGround
 * and isBloomConvertible are the terrain readers behind
 * Graveyard Bloom; both refuse containers and other block
 * entities and unbreakable blocks.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the abilities in this
 * package use. Transient modifiers are not persisted, so one
 * applied to a victim vanishes on its own if that victim
 * unloads; removing one that is absent is a no-op.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class WitherAbilitySupport {

    private WitherAbilitySupport() {}

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
     * Fires one vanilla wither skull from the caster's head
     * height toward the target's center, with a random spread
     * (in blocks, applied to the aim point) so the barrage walks
     * instead of stacking on one spot. dangerous marks the blue
     * charged skull (WitherSkull.setDangerous). Returns the
     * skull, or null when it could not be added to the level.
     * The launch math mirrors Colossal Grief's siege meteors:
     * the constructor takes the acceleration direction, the
     * skull is snapped to a mouth point in front of the caster,
     * and given an initial velocity so it does not start slow.
     */
    static WitherSkull launchSkull(AbilityContext ctx, ServerLevel level, LivingEntity target, double spread,
                                   boolean dangerous) {
        Mob caster = ctx.mob();
        Vec3 from = new Vec3(caster.getX(), caster.getY() + caster.getBbHeight() * 0.7, caster.getZ());
        double aimX = target.getX() + (ctx.random().nextDouble() * 2.0 - 1.0) * spread;
        double aimY = target.getY() + target.getBbHeight() * 0.5
            + (ctx.random().nextDouble() * 2.0 - 1.0) * spread * 0.4;
        double aimZ = target.getZ() + (ctx.random().nextDouble() * 2.0 - 1.0) * spread;
        Vec3 direction = new Vec3(aimX - from.x, aimY - from.y, aimZ - from.z);
        if (direction.lengthSqr() < 1.0e-4) {
            return null;
        }
        direction = direction.normalize();
        WitherSkull skull = new WitherSkull(level, caster, direction.scale(0.1));
        skull.setDangerous(dangerous);
        Vec3 mouth = from.add(direction.scale(1.2));
        skull.snapTo(mouth.x, mouth.y, mouth.z, 0.0f, 0.0f);
        skull.setDeltaMovement(direction.scale(0.5));
        if (!level.addFreshEntity(skull)) {
            return null;
        }
        return skull;
    }

    /**
     * Creates (but does not add to the level) one block display
     * showing a wither skeleton skull at the given scale,
     * centered on its snap position, at full brightness, gliding
     * with a teleport duration of 2 ticks. Returns null if the
     * entity could not be created. See the DISPLAYS note in the
     * class comment.
     */
    static Entity createSkullDisplay(ServerLevel level, float scale) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(Blocks.WITHER_SKELETON_SKULL.defaultBlockState()));
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * The topmost convertible ground block in the column at
     * (x, z) for Graveyard Bloom, scanning from four blocks
     * above the base height down to eight below it (the wither
     * flies, so its recorded base height can sit well above the
     * ground it poisons), or null when the column has no
     * convertible ground in that window.
     */
    static BlockPos findBloomGround(ServerLevel level, int x, int z, int baseY) {
        for (int y = baseY + 4; y >= baseY - 8; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isBloomConvertible(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Whether the block at the position may be converted into
     * soul sand or soul soil by Graveyard Bloom: a real solid
     * block (collision shape present, so plants and other
     * non-solids are scanned past to the ground under them), dry
     * itself and with no fluid directly above, no block entity
     * (containers are never touched), breakable (destroy speed
     * not negative), and not already soul sand or soul soil.
     * Only called when mobGriefing is on.
     */
    static boolean isBloomConvertible(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || !level.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
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

    /** A display transformation compound: centered translation, uniform scale, identity rotations. */
    private static CompoundTag transformation(float scale) {
        float half = -scale / 2.0f;
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
