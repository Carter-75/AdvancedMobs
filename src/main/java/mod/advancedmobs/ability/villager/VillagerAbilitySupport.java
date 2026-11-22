package mod.advancedmobs.ability.villager;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * VillagerAbilitySupport: helpers shared by the Villager's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package: the
 * Emerald Barrage bursts, the Cursed Deal signing check): a living
 * entity counts as an enemy of the caster when it is alive, is not the
 * caster, is not riding the caster or being ridden by it, is not in the
 * caster's ally set (MobWarData.isAlly, the war system's alliance
 * record), and is either the caster's current combat target or another
 * Mob. Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area abilities
 * never grief creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented mobs' abilities
 * use; it is duplicated here because those classes are package-private
 * to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the caster when it
 * is alive, is not the caster, and either side's war data lists the
 * other as an ally (the either-direction check the Breeze, Bee, and
 * Wolf packages use). The Hero of the Village Rally buffs allies by
 * this rule.
 *
 * DISPLAYS: displays are configured through NBT in 26.2 (their
 * setters are private), so the factories here build entities through
 * EntityType.loadEntityRecursive, the supported path the Zombie
 * Villager and Camel packages use. Every display gets full brightness
 * (block and sky light 15) and a teleport duration of 2 ticks so
 * per-tick snapTo calls glide instead of snapping. The text factory
 * encodes its component through ComponentSerialization.CODEC into the
 * "text" key (the jar-verified save key), sets billboard "center" so
 * the banner always faces its reader, a semi-transparent dark
 * "background", and a "line_width" of 200. A display is a pure
 * visual: no hitbox, no collision, and explicitly NOT a block change
 * under the standing terrain rule.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only block change in this
 * package is the Marketplace Barricade's backing planks, placed
 * PERMANENTLY and only when AbilityContext.mobGriefingAllowed() is
 * true, only into a replaceable cell with no block entity and solid
 * ground below (never overwriting a container or unbreakable block,
 * never floating). Everything else in the package is projectiles,
 * summons, effects, particles, and displays.
 *
 * MODIFIERS: addModifier() and removeModifier() are the transient
 * attribute modifier helpers the package uses. Transient modifiers
 * are not persisted, so one applied to a victim vanishes on its own
 * if that victim unloads; removing one that is absent is a no-op.
 * Both tolerate a missing attribute instance (a mob that lacks the
 * attribute simply never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with a
 * tight AABB built from the given radius, and callers only run them
 * on a pulse interval or a one-shot moment, never as whole-level
 * scans.
 */
final class VillagerAbilitySupport {

    private VillagerAbilitySupport() {}

    /** The emerald ITEM particle option the barrage trails and bursts with (pre-built, no per-tick allocation). */
    static final ItemParticleOption EMERALD_ITEM = new ItemParticleOption(ParticleTypes.ITEM, Items.EMERALD);

    /** The paper ITEM particle option the Cursed Deal signing bursts with (pre-built). */
    static final ItemParticleOption PAPER_ITEM = new ItemParticleOption(ParticleTypes.ITEM, Items.PAPER);

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

    /** All allies of the context mob within the given radius of the mob itself. One bounded query. */
    static List<LivingEntity> alliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        AABB box = new AABB(self.getX() - radius, self.getY() - yBelow, self.getZ() - radius,
                            self.getX() + radius, self.getY() + yAbove, self.getZ() + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isAlly(ctx, e));
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

    /**
     * Finds the Y of the first standable ground surface at or below
     * the given position: scans down from 2 above to 5 below the
     * start Y (at most 8 block reads) for the first non-air block
     * whose cell above is air, and returns the top of that block.
     * Falls back to the start Y when nothing is found.
     */
    static int findGroundY(ServerLevel level, double x, double startY, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = (int) Math.floor(startY) + 2;
        int bottom = (int) Math.floor(startY) - 5;
        for (int y = top; y >= bottom; y--) {
            pos.set((int) Math.floor(x), y, (int) Math.floor(z));
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                pos.setY(y + 1);
                if (level.getBlockState(pos).isAir()) {
                    return y + 1;
                }
            }
        }
        return (int) Math.floor(startY);
    }

    /**
     * Creates (but does not add to the level) one block display of
     * the given state with the given per-axis scale, centered
     * horizontally on its snap position (the block model pivot is
     * its bottom-north-west corner, so the translation centers it),
     * at full brightness, gliding with a teleport duration of 2.
     * Returns null if the entity could not be created.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState state,
                                     float scaleX, float scaleY, float scaleZ) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(state));
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(-scaleX / 2.0f, 0.0f, -scaleZ / 2.0f));
        transformation.put("scale", floatList(scaleX, scaleY, scaleZ));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * Creates (but does not add to the level) one item display of the
     * given item at the given uniform scale, centered on its snap
     * position, at full brightness, gliding with a teleport duration
     * of 2. The item stack is written as display NBT ("id" plus
     * "count"). Returns null if the entity could not be created.
     */
    static Entity createItemDisplay(ServerLevel level, net.minecraft.world.item.Item item, float scale) {
        CompoundTag itemTag = new CompoundTag();
        itemTag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
        itemTag.putInt("count", 1);
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemTag);
        tag.putString("item_display", "fixed");
        float half = -scale / 2.0f;
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(half, half, half));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * Creates (but does not add to the level) one text display
     * showing the given component at the given uniform scale,
     * billboarded to "center" so it always faces the reader, with
     * a semi-transparent dark background (ARGB 0x55000000) and a
     * line width of 200, at full brightness, gliding with a
     * teleport duration of 2. The text pivot is the bottom center
     * of the text block, so the display is centered horizontally
     * on its snap position by construction. Returns null if the
     * entity could not be created or the text failed to encode.
     */
    static Entity createTextDisplay(ServerLevel level, Component text, float scale) {
        DataResult<Tag> encoded = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, text);
        Tag textTag = encoded.result().orElse(null);
        if (textTag == null) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("text", textTag);
        tag.putString("billboard", "center");
        tag.putInt("background", 0x55000000);
        tag.putInt("line_width", 200);
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0f, 0.0f, 0.0f));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.TEXT_DISPLAY, tag, level,
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

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }
}
