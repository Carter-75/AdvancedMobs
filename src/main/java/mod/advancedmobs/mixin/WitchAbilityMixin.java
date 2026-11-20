package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.WitchAbilityHelper;
import mod.advancedmobs.system.WitchAbilityHelper.ThrowStats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds scaling-system special abilities to Witch potion attacks.
 *
 * 26.2 API note: Witch.performRangedAttack no longer spawns its potion
 * through Level.addFreshEntity (Level has no such method anymore). It
 * builds the potion through Projectile.spawnProjectileUsingShoot, so the
 * redirect hooks that call instead: the ability-resolved stack is used
 * for the spawn, and the ability trajectory is applied to the spawned
 * potion afterwards, matching the old behavior of swapping the stack
 * and overwriting the velocity before the potion entered the world.
 */
@Mixin(Witch.class)
public abstract class WitchAbilityMixin extends Mob {

    protected WitchAbilityMixin(EntityType<? extends Mob> entityType, Level world) {
        super(entityType, world);
    }

    @Redirect(method = "performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;spawnProjectileUsingShoot(Lnet/minecraft/world/entity/projectile/Projectile$ProjectileFactory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;DDDFF)Lnet/minecraft/world/entity/projectile/Projectile;"))
    private Projectile advancedmobs$augmentPotionThrows(Projectile.ProjectileFactory<?> factory, ServerLevel serverWorld, ItemStack stack, LivingEntity shooter, double x, double y, double z, float power, float inaccuracy, LivingEntity target, float pullProgress) {
        try {
            Mob witch = (Mob) (Object) this;
            if (!(witch instanceof IMobWarDataHolder holder)) {
                return Projectile.spawnProjectileUsingShoot(factory, serverWorld, stack, shooter, x, y, z, power, inaccuracy);
            }

            MobWarData data = holder.getMobWarData();
            if (data == null) {
                return Projectile.spawnProjectileUsingShoot(factory, serverWorld, stack, shooter, x, y, z, power, inaccuracy);
            }

            ItemStack resolvedStack = WitchAbilityHelper.resolvePotionStack(witch, data, stack.copy(), witch.getRandom());
            Projectile spawned = Projectile.spawnProjectileUsingShoot(factory, serverWorld, resolvedStack, shooter, x, y, z, power, inaccuracy);

            if (spawned instanceof AbstractThrownPotion potion) {
                ThrowStats stats = WitchAbilityHelper.resolveThrowStats(witch, data);
                WitchAbilityHelper.configureTrajectory(potion, witch, target, stats, 0.0f);
            }
            return spawned;
        } catch (Throwable t) {
            AdvancedMobsMod.LOGGER.error("[WitchAbilityMixin] Potion throw augmentation failed", t);
            return Projectile.spawnProjectileUsingShoot(factory, serverWorld, stack, shooter, x, y, z, power, inaccuracy);
        }
    }
}
