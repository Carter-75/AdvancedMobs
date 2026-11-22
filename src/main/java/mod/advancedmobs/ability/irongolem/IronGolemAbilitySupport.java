package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * IronGolemAbilitySupport: helpers shared by the Iron Golem's
 * 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the
 * caster or being ridden by it, is not in the caster's ally
 * set (MobWarData.isAlly, the war system's alliance record),
 * and is either the caster's current combat target or another
 * Mob. Players are only affected when they are the caster's
 * current target and are in survival or adventure mode, so
 * area abilities never grief creative or spectator players
 * who happen to stand nearby. This is the same rule the other
 * implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * ALLY RULE: a living entity counts as an ally when the
 * alliance record runs in either direction (the caster's
 * data lists it, or its own data lists the caster).
 *
 * PROTECTED ALLY RULE (Poppy's Revenge and Vine Snare): an
 * ally the golem guards: any villager type (a Villager or a
 * WanderingTrader, allied or not: defending villagers is
 * what iron golems are for), or an ally whose max health is
 * below the golem's own max health (a "smaller ally" in the
 * design's words).
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the real block changes in
 * this package are Ground Slam's cracked surface conversions
 * and Vine Snare's cobwebs. Both are PERMANENT and only
 * happen when AbilityContext.mobGriefingAllowed() is true,
 * through the safety checks below (never containers or other
 * block entities, never unbreakable blocks, never fluids,
 * cobwebs only into replaceable cells).
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and callers
 * only run them on a pulse interval or a one-shot moment,
 * never as whole-level scans.
 */
final class IronGolemAbilitySupport {

    private IronGolemAbilitySupport() {}

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

    /** Whether the given living entity is an ally of the caster (either-direction record). */
    static boolean isAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID());
    }

    /** Whether the entity is a villager type (villager or wandering trader). */
    static boolean isVillagerType(LivingEntity candidate) {
        return candidate instanceof Villager || candidate instanceof WanderingTrader;
    }

    /**
     * Whether the entity is one the golem guards: a villager
     * type, or an ally smaller than the golem (max health
     * below the golem's own max health).
     */
    static boolean isProtectedAlly(AbilityContext ctx, LivingEntity candidate) {
        if (candidate == null || candidate == ctx.mob() || !candidate.isAlive()) {
            return false;
        }
        if (isVillagerType(candidate)) {
            return true;
        }
        return isAlly(ctx, candidate) && candidate.getMaxHealth() < ctx.mob().getMaxHealth();
    }

    /**
     * All enemies of the context mob inside a box of the given
     * horizontal radius and vertical reach around a center
     * point. One bounded entity query; call on a pulse
     * interval or a one-shot moment, not per tick.
     */
    static List<LivingEntity> enemiesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                           double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isEnemy(ctx, e));
    }

    /** All protected allies inside a box around the given center. One bounded entity query. */
    static List<LivingEntity> protectedAlliesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                                   double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isProtectedAlly(ctx, e));
    }

    /**
     * Whether the block at the position is surface earth
     * Ground Slam may crack: a real solid block (collision
     * shape present, so plants and other non-solids are
     * scanned past to the ground under them), dry itself and
     * with no fluid directly above, no block entity
     * (containers are never touched), breakable (destroy
     * speed not negative), and not already cracked stone
     * bricks or gravel. Only called when mobGriefing is on.
     */
    static boolean isCrackableGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.CRACKED_STONE_BRICKS) || state.is(Blocks.GRAVEL)) {
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

    /**
     * The topmost crackable ground block in the column at
     * (x, z), scanning from one block above the base height
     * down to two below it, or null when the column has no
     * crackable ground in that window.
     */
    static BlockPos findGround(ServerLevel level, int x, int z, int baseY) {
        for (int y = baseY + 1; y >= baseY - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isCrackableGround(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Places one permanent cobweb at the position when the
     * safety checks pass: the cell must be replaceable and
     * dry, with no block entity, and not unbreakable.
     * Callers must already have gated the placement pass on
     * mobGriefing. Returns whether a web was placed.
     */
    static boolean placeCobweb(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        if (state.getDestroySpeed(level, pos) < 0.0f) {
            return false;
        }
        level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        return true;
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
}
