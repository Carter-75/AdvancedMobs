package mod.advancedmobs.ability.hoglin;

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
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * HoglinAbilitySupport: helpers shared by the Hoglin's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package:
 * the Stampede Trench hit checks and trench pulses, the Fungal
 * Frenzy Cloud nausea pulses, the Territorial Slam radial, the
 * Stubborn Hide Brace chip damage): a living entity counts as an
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
 * SWARM ALLY RULE (used by the Fungal Frenzy Cloud's buff pulse):
 * a living entity counts as a frenzy ally when it is a Hoglin or
 * a Zoglin and the alliance record runs in either direction (the
 * caster's data lists it as an ally, or its own data lists the
 * caster), the either-direction check the Silverfish's Hive Mind
 * Link uses. Wild hoglins and zoglins with no alliance record to
 * this caster are NOT buffed: the design buffs allies, not every
 * beast that wanders into the spores.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): the only real block change in
 * this package is the Stampede Trench's coarse dirt, PERMANENT
 * and only placed when AbilityContext.mobGriefingAllowed() is
 * true, through the isDiggable() safety check below (never
 * containers or other block entities, never unbreakable blocks).
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class HoglinAbilitySupport {

    private HoglinAbilitySupport() {}

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

    /** Whether the given living entity is a frenzy ally of the caster (see the SWARM ALLY RULE above). */
    static boolean isSwarmAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || !candidate.isAlive()) {
            return false;
        }
        if (!(candidate instanceof Hoglin) && !(candidate instanceof Zoglin)) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID());
    }

    /**
     * The caster itself plus every frenzy ally inside a box of the
     * given horizontal radius and vertical reach around the caster.
     * One bounded entity query; call on a pulse interval only.
     */
    static List<LivingEntity> frenzyAlliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        AABB box = new AABB(self.getX() - radius, self.getY() - yBelow, self.getZ() - radius,
                            self.getX() + radius, self.getY() + yAbove, self.getZ() + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box,
            e -> e == self || isSwarmAlly(ctx, e));
    }

    /**
     * Whether the block at the position may be converted to coarse
     * dirt by the Stampede Trench: a real, dry, diggable block with
     * no block entity. Air and fluids are never converted (a charge
     * over water or a ledge leaves no trench there), containers and
     * other block entities are never touched, and unbreakable
     * blocks (negative destroy speed) are never touched. Only
     * called when mobGriefing is on.
     */
    static boolean isDiggable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
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
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
