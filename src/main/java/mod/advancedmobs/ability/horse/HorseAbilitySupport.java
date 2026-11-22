package mod.advancedmobs.ability.horse;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 * HorseAbilitySupport: helpers shared by the Horse's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the caster
 * or being ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and
 * is either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * ALLY RULE: a living entity counts as an ally of the caster
 * when it is alive, is not the caster, and either side's war
 * data lists the other as an ally (the either-direction check
 * the Breeze, Bee, and Wolf packages use).
 *
 * TERRAIN: no ability in this package places or breaks
 * blocks, so the standing terrain rule's mobGriefing gate
 * does not apply anywhere in the package. Ironshod Bulwark
 * swaps an equipment item on the caster itself (not a block
 * in the world), and everything else is velocity, effects,
 * attribute modifiers, particles, and one summon pair.
 *
 * EQUIPMENT STORAGE: storeStack() and loadStack() round-trip
 * an ItemStack through the state tag via ItemStack.CODEC (the
 * Gilded Plate Swap pattern), so an equipment swap survives a
 * chunk reload and end() can restore exactly what the caster
 * was wearing.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op. Both tolerate a missing
 * attribute instance (a mob that lacks the attribute simply
 * never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class HorseAbilitySupport {

    private HorseAbilitySupport() {}

    /** The tan dust of kicked-up dirt, shared by the charge and the gallop. */
    static final DustParticleOptions TAN_DUST = new DustParticleOptions(0xC8A165, 1.2f);

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

    /** Whether the entity's bounding box at the given feet position collides with nothing. */
    static boolean isFreeSpot(ServerLevel level, Entity entity, double x, double y, double z) {
        double halfWidth = entity.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + entity.getBbHeight(), z + halfWidth);
        return level.noCollision(entity, box);
    }

    /** A BLOCK_CRUMBLE option carrying the block state under the given position (dirt when there is none). */
    static BlockParticleOption groundCrumble(ServerLevel level, double x, double y, double z) {
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
        BlockState state = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        return new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, state);
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
        DataResult<Tag> encoded = ItemStack.CODEC
            .encodeStart(access.createSerializationContext(NbtOps.INSTANCE), stack);
        Tag tag = encoded.result().orElse(null);
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
}
