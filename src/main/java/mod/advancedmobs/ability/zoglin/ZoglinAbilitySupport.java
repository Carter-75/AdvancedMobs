package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * ZoglinAbilitySupport: helpers shared by the Zoglin's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this package:
 * the Rotten Rampage clip checks, the Plague Breath cone and
 * cloud pulses, the Carrion Call bellow shove): a living entity
 * counts as an enemy of the caster when it is alive, is not the
 * caster, is not riding the caster or being ridden by it, is not
 * in the caster's ally set (MobWarData.isAlly, the war system's
 * alliance record), and is either the caster's current combat
 * target or another Mob. Players are only affected when they are
 * the caster's current target and are in survival or adventure
 * mode, so area abilities never grief creative or spectator
 * players who happen to stand nearby. This is the same rule the
 * other implemented mobs' abilities use; it is duplicated here
 * because those classes are package-private to their own
 * packages.
 *
 * UNDEAD ALLY RULE (used by the Carrion Call's rally pulse and
 * its trigger check): a living entity counts as an undead ally
 * when it is not the caster, is alive, is one of the undead kin
 * the design names (the zombie family: Zombie and its subclasses
 * Zombie Villager, Husk and Drowned; the skeleton family:
 * AbstractSkeleton and its subclasses Skeleton, Stray, Bogged
 * and Wither Skeleton; plus Zoglin and Phantom), and the
 * alliance record runs in either direction (the caster's data
 * lists it as an ally, or its own data lists the caster), the
 * either-direction check the Silverfish's Hive Mind Link uses.
 * Wild undead with no alliance record to this caster are NOT
 * rallied: the design rallies allies, not every corpse that
 * wanders into earshot. The Wither is deliberately not in the
 * kin list: a boss answers no zoglin's call.
 *
 * TERRAIN: no ability in this package places or breaks blocks.
 * The Plague Breath cloud is a zone effect (particles and
 * debuffs around a recorded point), not terrain, so the
 * mobGriefing gate does not apply anywhere in this package.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses. They
 * are null-tolerant: an entity whose attribute supplier has no
 * instance of the attribute simply gets no modifier (the
 * Frenzy Gore ATTACK_SPEED case, documented in that class).
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads; removing
 * one that is absent is a no-op.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with
 * a tight AABB built from the given radius, and callers only
 * run them on a pulse interval or a one-shot moment, never as
 * whole-level scans.
 */
final class ZoglinAbilitySupport {

    private ZoglinAbilitySupport() {}

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

    /** Whether the given living entity is an undead ally of the caster (see the UNDEAD ALLY RULE above). */
    static boolean isUndeadAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (!(candidate instanceof Zombie) && !(candidate instanceof AbstractSkeleton)
            && !(candidate instanceof Zoglin) && !(candidate instanceof Phantom)) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob other && MobWarData.get(other).isAlly(self.getUUID());
    }

    /**
     * Every undead ally inside a box of the given horizontal
     * radius and vertical reach around the caster. One bounded
     * entity query; call on a pulse interval or in a trigger
     * check, not per tick.
     */
    static List<LivingEntity> undeadAlliesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        AABB box = new AABB(self.getX() - radius, self.getY() - yBelow, self.getZ() - radius,
                            self.getX() + radius, self.getY() + yAbove, self.getZ() + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isUndeadAlly(ctx, e));
    }

    /** Adds or refreshes a transient attribute modifier on the given entity; a no-op when the entity lacks the attribute. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent or when the entity lacks the attribute. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
