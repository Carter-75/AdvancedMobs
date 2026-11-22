package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * PiglinAbilitySupport: helpers shared by the Piglin's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package:
 * the Gold Rush Frenzy trample): a living entity counts as an
 * enemy of the caster when it is alive, is not the caster, is not
 * riding the caster or being ridden by it, is not in the caster's
 * ally set (MobWarData.isAlly, the war system's alliance record),
 * and is either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area abilities
 * never grief creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented mobs'
 * abilities use; it is duplicated here because those classes are
 * package-private to their own packages.
 *
 * CLUSTER ALLY RULE (used by the Volley Mark's contributing
 * piglins and the Gilded Plate Swap's clustering): a Piglin
 * counts as a cluster ally of the caster when it is alive, is not
 * the caster, and either the alliance record runs in either
 * direction (the caster's data lists it as an ally, or its own
 * data lists the caster, the either-direction check the
 * Silverfish's Hive Mind Link uses) or it is currently fighting
 * the same target as the caster (a shared fight is a de facto
 * alliance). Wild piglins with no record and no shared fight are
 * left alone: the design's helpers are the piglin's own crowd,
 * not every snout in the bastion.
 *
 * GOLD ITEM RULE (used by the Gold Rush Frenzy): a dropped item
 * stack counts as gold when it is one of the loose gold goods a
 * piglin would dive for: gold ingot, gold nugget, gold block,
 * raw gold, raw gold block, golden carrot, golden apple,
 * enchanted golden apple, glistering melon slice. Golden gear a
 * mob is wearing does not count; only item entities on the
 * ground are ever scanned.
 *
 * TERRAIN: no ability in this package places or breaks blocks,
 * so the mobGriefing gate does not apply anywhere. The Barter
 * Barrage's fire charges are vanilla SmallFireballs whose own
 * block ignition checks mobGriefing itself, stated in that
 * ability's comments.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op.
 *
 * STACK STORAGE: storeStack() and loadStack() round-trip an
 * ItemStack through the per-activation state tag using the
 * ItemStack codec with the level's registry access (26.2 has no
 * ItemStack.save anymore). The Gilded Plate Swap uses them to
 * hold the piglin's real equipment while the golden set is on.
 * An empty stack is stored as an absent key.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class PiglinAbilitySupport {

    private PiglinAbilitySupport() {}

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

    /** Whether the given piglin is a cluster ally of the caster (see the CLUSTER ALLY RULE above). */
    static boolean isClusterAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (!(candidate instanceof Piglin)) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        if (candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID())) {
            return true;
        }
        LivingEntity shared = ctx.target();
        return shared != null && candidate instanceof Mob other && other.getTarget() == shared;
    }

    /**
     * All cluster-ally piglins inside a box of the given horizontal
     * radius and vertical reach around a center point, the caster
     * itself excluded. One bounded entity query; call on a pulse
     * interval or a one-shot moment, not per tick.
     */
    static List<Piglin> clusterPiglinsAround(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                             double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(Piglin.class, box, e -> isClusterAlly(ctx, e));
    }

    /** Whether the given stack is one of the loose gold goods (see the GOLD ITEM RULE above). */
    static boolean isGoldItem(ItemStack stack) {
        return stack.is(Items.GOLD_INGOT)
            || stack.is(Items.GOLD_NUGGET)
            || stack.is(Items.GOLD_BLOCK)
            || stack.is(Items.RAW_GOLD)
            || stack.is(Items.RAW_GOLD_BLOCK)
            || stack.is(Items.GOLDEN_CARROT)
            || stack.is(Items.GOLDEN_APPLE)
            || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.is(Items.GLISTERING_MELON_SLICE);
    }

    /**
     * All dropped gold item entities inside a box of the given
     * horizontal radius and vertical reach around the caster. One
     * bounded entity query; call on a pulse interval only.
     */
    static List<ItemEntity> goldItemsAround(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        AABB box = new AABB(self.getX() - radius, self.getY() - yBelow, self.getZ() - radius,
                            self.getX() + radius, self.getY() + yAbove, self.getZ() + radius);
        return ctx.level().getEntitiesOfClass(ItemEntity.class, box,
            e -> e.isAlive() && isGoldItem(e.getItem()));
    }

    /** Adds or refreshes a transient attribute modifier on the given entity. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    /**
     * Stores a stack in the state tag under the key, or clears the
     * key when the stack is empty (see STACK STORAGE above). If the
     * codec ever fails on a stack, the key is cleared and the stack
     * is treated as empty by loadStack().
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
}
