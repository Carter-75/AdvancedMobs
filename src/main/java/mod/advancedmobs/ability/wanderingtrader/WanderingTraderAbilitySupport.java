package mod.advancedmobs.ability.wanderingtrader;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * WanderingTraderAbilitySupport: helpers shared by the Wandering
 * Trader's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package:
 * the Roulette bursts, the Bargain Bin Blast wave, the caravan
 * guards' interception pick): a living entity counts as an enemy
 * of the caster when it is alive, is not the caster, is not riding
 * the caster or being ridden by it, is not in the caster's ally
 * set (MobWarData.isAlly, the war system's alliance record), and
 * is either the caster's current combat target or another Mob.
 * Players are only affected when they are the caster's current
 * target and are in survival or adventure mode, so area abilities
 * never grief creative or spectator players who happen to stand
 * nearby. This is the same rule the other implemented mobs'
 * abilities use; it is duplicated here because those classes are
 * package-private to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the caster when
 * it is alive, is not the caster, and either side's war data lists
 * the other as an ally (the either-direction check the Breeze,
 * Bee, and Wolf packages use).
 *
 * THE GOODS: GOODS is the trader's stock list for the Exotic
 * Goods Roulette and the Bargain Bin Blast: dyes, a sapling, a
 * bucket, and stranger pack-fill (bone, string, feather, a glass
 * bottle, wheat seeds, a clay ball). GOOD_PARTICLES holds one
 * pre-built ITEM particle option per stock entry, in the same
 * order, so neither ability allocates particle options in its
 * hot path; an entry's index in GOODS is stored in the state tag
 * and indexes both arrays.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only block change in this
 * package is The Wandering Road's campfire, placed PERMANENTLY
 * and only when AbilityContext.mobGriefingAllowed() is true,
 * only into a replaceable cell with no block entity and solid
 * ground below (never overwriting a container or unbreakable
 * block, never floating), and never removed in end().
 * Everything else in the package is projectiles, summons,
 * effects, particles, teleports, and code impulses.
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
final class WanderingTraderAbilitySupport {

    private WanderingTraderAbilitySupport() {}

    /** The trader's stock list: the Roulette throws these, the Bargain Bin Blast sprays them. */
    static final Item[] GOODS = {
        Items.DYE.red(), Items.DYE.blue(), Items.OAK_SAPLING, Items.BUCKET, Items.BONE,
        Items.STRING, Items.FEATHER, Items.GLASS_BOTTLE, Items.WHEAT_SEEDS, Items.CLAY_BALL
    };

    /** One pre-built ITEM particle option per GOODS entry, same order (no per-tick allocation). */
    static final ItemParticleOption[] GOOD_PARTICLES = buildGoodParticles();

    private static ItemParticleOption[] buildGoodParticles() {
        ItemParticleOption[] options = new ItemParticleOption[GOODS.length];
        for (int i = 0; i < GOODS.length; i++) {
            options[i] = new ItemParticleOption(ParticleTypes.ITEM, GOODS[i]);
        }
        return options;
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

    /** All enemies of the context mob within the given radius of the mob itself. One bounded query. */
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
