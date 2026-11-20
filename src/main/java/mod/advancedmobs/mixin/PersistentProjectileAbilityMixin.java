package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.bridge.ProjectileAbilityBridge;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies scaling-system ranged abilities (piercing, multishot, potion mastery)
 * to every persistent projectile fired by a mob.
 */
@Mixin(AbstractArrow.class)
public abstract class PersistentProjectileAbilityMixin implements ProjectileAbilityBridge {
    @Unique
    private boolean advancedmobs$abilitiesApplied;

    // This mixin used to extend Projectile with a constructor calling
    // super(entityType, world), but in 1.21.1 Projectile's constructor
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
            Projectile self = (Projectile) (Object) this;
            if (self.level().isClientSide() || advancedmobs$abilitiesApplied) {
                return;
            }

            Entity owner = self.getOwner();
            if (!(owner instanceof Mob mob)) {
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

    @Inject(method = "onHitEntity", at = @At("TAIL"))
    private void advancedmobs$applyPotionMastery(EntityHitResult hitResult, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("PersistentProjectileAbilityMixin#applyPotionMastery", () -> {
            Projectile self = (Projectile) (Object) this;
            if (self.level().isClientSide()) {
                return;
            }

            Entity owner = self.getOwner();
            if (!(owner instanceof Mob mob)) {
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

    private MobWarData advancedmobs$getData(Mob mob) {
        if (mob instanceof IMobWarDataHolder holder) {
            return holder.getMobWarData();
        }
        return MobWarData.get(mob);
    }
}
