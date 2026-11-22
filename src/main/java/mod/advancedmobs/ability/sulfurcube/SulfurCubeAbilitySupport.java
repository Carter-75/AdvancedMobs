package mod.advancedmobs.ability.sulfurcube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * SulfurCubeAbilitySupport: helpers shared by the Sulfur
 * Cube's 5 abilities.
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
 * packages. Split-off sulfur cubes are allied through the same
 * ally records, so the caster's own abilities never hit them.
 *
 * SUMMONS: spawnAlliedSulfurCube() is the one summon path for
 * this package (Noxious Split). It creates the cube with
 * EntitySpawnReason.MOB_SUMMONED, finalizes the spawn, then
 * forces the requested size (finalizeSpawn randomizes size, so
 * the size is set AFTER it), optionally sets the SCALE
 * attribute base value (the design's half scale for the split
 * minis: a permanent base value on a brand new mob, not a
 * transient modifier), allies the cube with the caster in both
 * directions, registers the pair in SummonerTracker (which
 * also tags the cube umw_summoned for the natural spawn
 * blocker), and orders it onto a target: the same flow the
 * other brood abilities in this mod use.
 *
 * STACK STORAGE: storeStack() and loadStack() round-trip an
 * ItemStack through the per-activation state tag using the
 * ItemStack codec with the level's registry access (26.2 has
 * no ItemStack.save anymore). Hot Archetype uses them to hold
 * the cube's real absorbed block item while the magma block
 * that selects the HOT archetype sits in its body slot. An
 * empty stack is stored as an absent key.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers every ability in this
 * package uses, on the caster or (Geyser Core) on launched
 * victims. Transient modifiers are not persisted, so one
 * applied to a victim vanishes on its own if that victim
 * unloads; removing one that is absent is a no-op.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class SulfurCubeAbilitySupport {

    private SulfurCubeAbilitySupport() {}

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

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    static boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    /**
     * Spawns one allied sulfur cube of the given size at the
     * given spot (the caster's own position as fallback when
     * the spot is not free), allies it with the caster in both
     * directions, registers the pair in SummonerTracker, and
     * orders it onto the given target (the caster's live target
     * when null). When halfScale is set, the new cube's SCALE
     * attribute base value is set to 0.5 (see the SUMMONS note
     * in the class comment). Returns the cube, or null when it
     * could not be created or added.
     */
    static SulfurCube spawnAlliedSulfurCube(AbilityContext ctx, ServerLevel level, double spawnX, double spawnZ,
                                            int size, boolean halfScale, LivingEntity target) {
        Mob caster = ctx.mob();
        SulfurCube cube = EntityTypes.SULFUR_CUBE.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (cube == null) {
            return null;
        }
        if (!isFreeSpot(level, cube, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        cube.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        cube.finalizeSpawn(level, level.getCurrentDifficultyAt(cube.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // finalizeSpawn randomizes a cube's size; the split
        // size is a design number, so it is forced after.
        cube.setSize(size, true);
        if (halfScale) {
            AttributeInstance scale = cube.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(0.5);
            }
        }
        if (!level.addFreshEntity(cube)) {
            return null;
        }
        ctx.data().addAlly(cube.getUUID());
        MobWarData.get(cube).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(cube.getUUID(), caster.getUUID());
        LivingEntity order = target != null ? target : ctx.target();
        if (order != null && order != cube) {
            cube.setTarget(order);
        }
        return cube;
    }

    /**
     * Stores a stack into the state tag under the key; an
     * empty (or null) stack is stored as an absent key.
     */
    static void storeStack(CompoundTag state, String key, ItemStack stack, RegistryAccess access) {
        if (stack == null || stack.isEmpty()) {
            state.remove(key);
            return;
        }
        Tag tag = ItemStack.CODEC
            .encodeStart(access.createSerializationContext(NbtOps.INSTANCE), stack)
            .result().orElse(null);
        if (tag != null) {
            state.put(key, tag);
        } else {
            state.remove(key);
        }
    }

    /** Loads a stack stored by storeStack(); ItemStack.EMPTY when the key is absent or unreadable. */
    static ItemStack loadStack(CompoundTag state, String key, RegistryAccess access) {
        Tag tag = state.get(key);
        if (tag == null) {
            return ItemStack.EMPTY;
        }
        return ItemStack.CODEC
            .parse(access.createSerializationContext(NbtOps.INSTANCE), tag)
            .result().orElse(ItemStack.EMPTY);
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
