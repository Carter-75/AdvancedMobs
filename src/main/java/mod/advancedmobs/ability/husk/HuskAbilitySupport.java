package mod.advancedmobs.ability.husk;

import mod.advancedmobs.ability.AbilityContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * HuskAbilitySupport: helpers shared by the Husk's 5 abilities.
 *
 * ENEMY RULE (used by every area effect in this package: Sandblast
 * Barrage hit checks, Quicksand Pit pulses, Sandstorm Revenant pulses,
 * Arid Aura pulses): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the caster or
 * being ridden by it, is not in the caster's ally set
 * (MobWarData.isAlly, the war system's alliance record), and is either
 * the caster's current combat target or another Mob. Players are only
 * affected when they are the caster's current target and are in
 * survival or adventure mode, so area abilities never grief creative
 * or spectator players who happen to stand nearby. This is the same
 * rule the Zombie's and Zombie Villager's abilities use; it is
 * duplicated here because those classes are package-private to their
 * own packages.
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass with a tight
 * AABB built from the given radius, and callers only run them on a
 * pulse interval (documented per ability), never as whole-level scans.
 */
final class HuskAbilitySupport {

    private HuskAbilitySupport() {}

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
     * All enemies of the context mob inside a box of the given horizontal
     * radius and vertical reach around a center point. One bounded entity
     * query; call on a pulse interval, not per tick.
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
}
