package mod.advancedmobs.ability.witch;

import com.mojang.serialization.DataResult;
import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * WitchAbilitySupport: helpers shared by the Witch's 5 abilities.
 *
 * ENEMY RULE (used by every area effect in this package): a
 * living entity counts as an enemy of the caster when it is
 * alive, is not the caster, is not riding the caster or being
 * ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and is
 * either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area
 * abilities never grief creative or spectator players who
 * happen to stand nearby. This is the same rule the Zombie's,
 * Bogged's, and other implemented mobs' abilities use; it is
 * duplicated here because those classes are package-private to
 * their own packages.
 *
 * DEBUFF TABLE: DEBUFFS is the witch's rolled payload menu, the
 * four effects the design names (poison, instant damage,
 * slowness, weakness) as ready potion holders, indexed so an
 * activation can store a roll as one int in its state tag.
 * DEBUFF_COLORS holds the EFFECT sparkle color per index
 * (vanilla potion liquid colors, the harming red brightened so
 * the sparkle reads at a distance).
 *
 * POTION THROWING: splashStack/lingeringStack build the bottle,
 * and throwSplash/throwLingering launch a real vanilla
 * ThrownSplashPotion / ThrownLingeringPotion owned by the
 * caster. A splash bottle applies its payload the vanilla way
 * when it breaks (everyone in the splash radius, friend or foe,
 * exactly like a vanilla witch's own throws); a lingering
 * bottle leaves a vanilla AreaEffectCloud on the ground where
 * it breaks. The abilities never fake potion effects in code
 * when the design says a bottle is thrown.
 *
 * DISPLAY HELPERS: displays are configured through NBT in 26.2
 * (their setters are private), so the helpers build the entity
 * through EntityType.loadEntityRecursive, the supported spawn
 * path already used by Grave Eruption, the Bogged's shelves,
 * and Rod Aegis. NBT keys verified against the real 26.2 jar:
 * block displays take "block_state" (NbtUtils.writeBlockState),
 * item displays take "item" (the ItemStack codec form) and
 * "item_display", and every display takes a "transformation"
 * compound with translation, scale, left_rotation and
 * right_rotation float lists (quaternions, x y z w), a
 * "brightness" compound, and "teleport_duration". Both helpers
 * set full brightness (block 15, sky 15): the witch's
 * constructs are magical and glow, per the designs ("glowing
 * with their contents", a cauldron hanging over the arena lit
 * by its own brew). The caller positions the returned entity
 * with snapTo and adds it to the level.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval (documented per ability), never
 * as whole-level scans. The debuff table and sparkle options
 * are pre-built static finals, resolved once, never per tick.
 */
final class WitchAbilitySupport {

    private WitchAbilitySupport() {}

    /** The rolled payload menu, in state-tag index order: poison, harming, slowness, weakness. */
    static final Holder<Potion>[] DEBUFFS = debuffTable();

    /** EFFECT sparkle color per DEBUFFS index (RGB int). */
    static final int[] DEBUFF_COLORS = {0x4E9331, 0xC02020, 0x5A6C81, 0x484D48};

    /** Pre-built EFFECT sparkle option per DEBUFFS index (power 1.0). */
    static final SpellParticleOption[] DEBUFF_SPARKLES = buildSparkles();

    @SuppressWarnings("unchecked")
    private static Holder<Potion>[] debuffTable() {
        return new Holder[]{Potions.POISON, Potions.HARMING, Potions.SLOWNESS, Potions.WEAKNESS};
    }

    private static SpellParticleOption[] buildSparkles() {
        SpellParticleOption[] sparkles = new SpellParticleOption[DEBUFF_COLORS.length];
        for (int i = 0; i < sparkles.length; i++) {
            sparkles[i] = SpellParticleOption.create(ParticleTypes.EFFECT, DEBUFF_COLORS[i], 1.0f);
        }
        return sparkles;
    }

    /** Rolls one debuff index (0 to 3) from the caster's own random source. */
    static int rollDebuff(AbilityContext ctx) {
        return ctx.random().nextInt(DEBUFFS.length);
    }

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

    /** A splash potion stack carrying the debuff at the given table index. */
    static ItemStack splashStack(int debuffIndex) {
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(DEBUFFS[debuffIndex]));
        return stack;
    }

    /** A lingering potion stack carrying the debuff at the given table index. */
    static ItemStack lingeringStack(int debuffIndex) {
        ItemStack stack = new ItemStack(Items.LINGERING_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(DEBUFFS[debuffIndex]));
        return stack;
    }

    /**
     * Throws a real splash potion from the origin toward the aim
     * point, owned by the caster. The direction is computed once
     * here; vanilla gravity arcs the bottle the rest of the way.
     */
    static void throwSplash(AbilityContext ctx, ServerLevel level, Vec3 origin, Vec3 aim,
                            ItemStack stack, float speed, float inaccuracy) {
        ThrownSplashPotion potion = new ThrownSplashPotion(level, ctx.mob(), stack);
        potion.setPos(origin.x, origin.y, origin.z);
        Vec3 dir = aim.subtract(origin);
        potion.shoot(dir.x, dir.y, dir.z, speed, inaccuracy);
        level.addFreshEntity(potion);
    }

    /** Throws a real lingering potion from the origin toward the aim point, owned by the caster. */
    static void throwLingering(AbilityContext ctx, ServerLevel level, Vec3 origin, Vec3 aim,
                               ItemStack stack, float speed, float inaccuracy) {
        ThrownLingeringPotion potion = new ThrownLingeringPotion(level, ctx.mob(), stack);
        potion.setPos(origin.x, origin.y, origin.z);
        Vec3 dir = aim.subtract(origin);
        potion.shoot(dir.x, dir.y, dir.z, speed, inaccuracy);
        level.addFreshEntity(potion);
    }

    /**
     * Creates (but does not add to the level) a block display
     * showing the given block state at full brightness, uniformly
     * scaled and centered on its snap position, tilted by the
     * given left-rotation quaternion (x, y, z, w). The
     * translation centers the block on the snap point: a block
     * display's pivot is its bottom-north-west corner, so the
     * block is shifted back by half its scale on every axis.
     * Returns null if the entity could not be created.
     */
    static Entity createBlockDisplay(ServerLevel level, BlockState blockState, float scale,
                                     float rotX, float rotY, float rotZ, float rotW) {
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(blockState));
        float half = -scale / 2.0f;
        tag.put("transformation", transformation(scale, half, half, half, rotX, rotY, rotZ, rotW));
        tag.put("brightness", fullBrightness());
        return EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    /**
     * Creates (but does not add to the level) an item display
     * showing the given stack at full brightness, "fixed"
     * context, gliding with a teleport duration of 2 ticks so
     * per-tick snapTo calls read as smooth motion. An item
     * display's pivot is the model center, so no centering
     * translation is needed. The stack is written through the
     * ItemStack codec (the 26.2 display "item" form), which is
     * what carries the potion contents that tint the bottle.
     * Returns null if the entity could not be created.
     */
    static Entity createItemDisplay(ServerLevel level, ItemStack stack, float scale) {
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack);
        Tag itemTag = encoded.result().orElse(null);
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("item", itemCompound);
        tag.putString("item_display", "fixed");
        tag.put("transformation", transformation(scale, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("brightness", fullBrightness());
        tag.putInt("teleport_duration", 2);
        return EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, EntityProcessor.NOP);
    }

    private static CompoundTag fullBrightness() {
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        return brightness;
    }

    /** A display transformation compound: translation, scale, the given left rotation, identity right rotation. */
    private static CompoundTag transformation(float scale, float tx, float ty, float tz,
                                              float rotX, float rotY, float rotZ, float rotW) {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(tx, ty, tz));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(rotX, rotY, rotZ, rotW));
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

    /** Adds or refreshes a transient attribute modifier; a no-op when the entity lacks the attribute. */
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
}
