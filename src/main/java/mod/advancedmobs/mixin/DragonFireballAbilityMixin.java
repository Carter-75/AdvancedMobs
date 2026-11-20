package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Augments Ender Dragon fireballs with void bombardment scaling effects.
 */
@Mixin(DragonFireball.class)
public abstract class DragonFireballAbilityMixin extends AbstractHurtingProjectile {

    protected DragonFireballAbilityMixin(EntityType<? extends DragonFireball> entityType, Level world) {
        super(entityType, world);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)V", at = @At("TAIL"))
    private void advancedmobs$configureBombardment(Level world, LivingEntity owner, Vec3 direction, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("DragonFireballAbilityMixin#configureBombardment", () -> {
            if (!(world instanceof ServerLevel serverWorld)) {
                return;
            }

            if (!(owner instanceof EnderDragon dragon)) {
                return;
            }

            MobWarData data = advancedmobs$getData(dragon);
            if (!advancedmobs$hasVoidBombardment(data)) {
                return;
            }

            ScalingSystem.handleVoidBombardment(dragon, data, serverWorld, (DragonFireball) (Object) this, serverWorld.getGameTime());
        });
    }

    @Inject(method = "onCollision", at = @At("TAIL"))
    private void advancedmobs$applyBombardmentEffects(HitResult hitResult, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("DragonFireballAbilityMixin#applyBombardmentEffects", () -> {
            if (!(this.level() instanceof ServerLevel serverWorld)) {
                return;
            }

            Mob owner = advancedmobs$getOwner();
            if (owner == null) {
                return;
            }

            MobWarData data = advancedmobs$getData(owner);
            if (!advancedmobs$hasVoidBombardment(data)) {
                return;
            }

            LivingEntity directHit = null;
            if (hitResult instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living) {
                directHit = living;
            }

            Vec3 impactPos = hitResult.getLocation();
            ScalingSystem.applyVoidBombardmentEffects(owner, data, serverWorld, impactPos, directHit);
        });
    }

    @Unique
    private boolean advancedmobs$hasVoidBombardment(MobWarData data) {
        return data != null && data.getSkillData().getIntOr("ability_void_bombardment", 0) > 0;
    }

    @Unique
    private MobWarData advancedmobs$getData(Mob mob) {
        if (mob instanceof IMobWarDataHolder holder) {
            return holder.getMobWarData();
        }
        return MobWarData.get(mob);
    }

    @Unique
    private Mob advancedmobs$getOwner() {
        Entity owner = this.getOwner();
        return owner instanceof Mob mob ? mob : null;
    }
}
