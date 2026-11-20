package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.bridge.ProjectileAbilityBridge;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.world.ServerWorld;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies scaling-system ranged abilities (piercing, multishot, potion mastery)
 * to every persistent projectile fired by a mob.
 */
@Mixin(PersistentProjectileEntity.class)
public abstract class PersistentProjectileAbilityMixin implements ProjectileAbilityBridge {
    @Unique
    private boolean advancedmobs$abilitiesApplied;

    // This mixin used to extend ProjectileEntity with a constructor calling
    // super(entityType, world), but in 1.21.1 ProjectileEntity's constructor
    // is package-private in the shipped jar, so the class never compiled.
    // It now follows the same cast pattern as ProjectileAbilityMixin; mixin
    // constructors are never invoked at runtime, so behavior is unchanged.

    @Override
    public void advancedmobs$setAbilitiesApplied(boolean applied) {
        this.advancedmobs$abilitiesApplied = applied;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void advancedmobs$applyProjectileAbilities(CallbackInfo ci) {
        AdvancedMobsMod.runSafely("PersistentProjectileAbilityMixin#applyProjectileAbilities", () -> {
            ProjectileEntity self = (ProjectileEntity) (Object) this;
            if (self.getWorld().isClient() || advancedmobs$abilitiesApplied) {
                return;
            }

            Entity owner = self.getOwner();
            if (!(owner instanceof MobEntity mob)) {
                advancedmobs$abilitiesApplied = true;
                return;
            }

            MobWarData data = advancedmobs$getData(mob);
            if (data == null) {
                advancedmobs$abilitiesApplied = true;
                return;
            }

            advancedmobs$abilitiesApplied = true;

            int piercingLevel = Math.max(0, ScalingSystem.getPiercingLevel(mob, data));
            if (piercingLevel > 0) {
                ((PersistentProjectileEntityAccessor) (Object) this).invokeSetPierceLevel((byte) Math.min(127, piercingLevel));
            }

            // Multishot + extra_shot are handled in ProjectileAbilityMixin for all projectile types.
        });
    }

    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void advancedmobs$applyPotionMastery(EntityHitResult hitResult, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("PersistentProjectileAbilityMixin#applyPotionMastery", () -> {
            ProjectileEntity self = (ProjectileEntity) (Object) this;
            if (self.getWorld().isClient()) {
                return;
            }

            Entity owner = self.getOwner();
            if (!(owner instanceof MobEntity mob)) {
                return;
            }

            Entity hit = hitResult.getEntity();
            if (!(hit instanceof LivingEntity livingTarget)) {
                return;
            }

            MobWarData data = advancedmobs$getData(mob);
            if (data == null) {
                return;
            }

            ScalingSystem.applyRangedPotionEffects(mob, data, livingTarget);
        });
    }

    private MobWarData advancedmobs$getData(MobEntity mob) {
        if (mob instanceof IMobWarDataHolder holder) {
            return holder.getMobWarData();
        }
        return MobWarData.get(mob);
    }
}
