package mod.advancedmobs.ability.frog;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * FrogAbilitySupport: helpers shared by the Frog's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package: the marsh gas pulse, the leapfrog splashdown,
 * the frogspawn slow band, the golden hour blindness
 * pulse): a living entity counts as an enemy of the
 * caster when it is alive, is not the caster, is not
 * riding the caster or being ridden by it, is not in the
 * caster's ally set (MobWarData.isAlly, the war system's
 * alliance record), and is either the caster's current
 * combat target or another Mob. Players are only affected
 * when they are the caster's current target and are in
 * survival or adventure mode, so area abilities never
 * grief creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented
 * mobs' abilities use; it is duplicated here because those
 * classes are package-private to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the
 * caster when it is alive, is not the caster, and either
 * side's war data lists the other as an ally (the
 * either-direction check the Breeze, Bee, and Wolf
 * packages use). No frog ability buffs allies today; the
 * helper is kept for parity with the other packages.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21,
 * 2026; IMPLEMENTATION-RULES.md rule 6): the only block
 * change in this package is the Frogspawn Shield's
 * frogspawn ring, placed PERMANENTLY and only when
 * AbilityContext.mobGriefingAllowed() is true, only into
 * a replaceable cell with no block entity, and only
 * where frogspawn can actually survive (on a supporting
 * fluid surface; see that ability's class comment).
 * Nothing in this package ever overwrites a container or
 * an unbreakable block, and no block is ever removed.
 *
 * DISPLAYS: createItemDisplay() builds one item display
 * through EntityType.loadEntityRecursive from NBT, the
 * supported configuration path in 26.2 (display setters
 * are private). The NBT sets full brightness (block and
 * sky light 15) and a teleport duration of 2 ticks so
 * per-tick snapTo calls glide instead of snapping. A
 * display is a pure visual: no hitbox, no collision.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied
 * to a victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance (a mob that lacks the
 * attribute simply never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans.
 */
final class FrogAbilitySupport {

    private FrogAbilitySupport() {}

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

    /** Whether the given living entity is an ally of the context mob (either-direction record). */
    static boolean isAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob candidateMob
            && MobWarData.get(candidateMob).isAlly(self.getUUID());
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

    /** Stores a spawned entity's UUID in the state tag's named list (one compound per entity: "id"). */
    static void trackEntity(CompoundTag state, String key, Entity entity) {
        ListTag list = state.getListOrEmpty(key);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, entity.getUUID());
        list.add(entry);
        state.put(key, list);
    }

    /** Resolves a tracked entity from its list entry, or null when gone. */
    static Entity resolveTracked(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /** Resolves a single entity UUID stored directly in the state tag under the key, or null. */
    static Entity resolveStored(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /** A BLOCK_CRUMBLE option carrying the block state under the given position (dirt when there is none). */
    static BlockParticleOption groundCrumble(ServerLevel level, double x, double y, double z) {
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
        BlockState state = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        return new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, state);
    }

    /**
     * Finds the feet height (the top of the highest solid,
     * non-fluid block with air directly above it) in the
     * column at x/z, scanning from 3 blocks above the hint
     * down to 8 blocks below it: at most 12 block reads, and
     * callers run it once per placement, never per tick.
     * Returns the hint unchanged when the column has no
     * usable surface in range (the Rabbit package's helper,
     * same contract).
     */
    static double findGroundY(ServerLevel level, double x, double yHint, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(yHint) + 3;
        int bottom = (int) Math.floor(yHint) - 8;
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockState(pos.above()).isAir()) {
                return y + 1.0;
            }
        }
        return yHint;
    }

    /**
     * Creates (but does not add to the level) one item display
     * of the given stack at the given scale, at full brightness,
     * gliding with a teleport duration of 2 ticks. When
     * billboard is non-null the display's billboard mode is set
     * to it (Golden Hour Form's froglights use "center", so
     * they always face the viewer); pass null for a fixed
     * display. Returns null if the entity could not be created.
     * See the DISPLAYS note in the class comment.
     */
    static Entity createItemDisplay(ServerLevel level, ItemStack stack, float scale, String billboard) {
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack);
        Tag itemTag = encoded.result().orElse(null);
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemCompound);
        tag.putString("item_display", "fixed");
        if (billboard != null) {
            tag.putString("billboard", billboard);
        }
        tag.put("transformation", transformation(scale));
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /** Adds or refreshes a transient attribute modifier on the given entity; tolerates a missing attribute. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent or the attribute is missing. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
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
