package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies scaling-system ranged abilities (multishot + extra_shot follow-up cycles)
 * to any projectile fired by a mob.
 */
@Mixin(Projectile.class)
public abstract class ProjectileAbilityMixin {

    @Unique
    private boolean advancedmobs$abilitiesApplied;

    @Inject(method = "tick", at = @At("HEAD"))
    private void advancedmobs$applyProjectileAbilities(CallbackInfo ci) {
        AdvancedMobsMod.runSafely("ProjectileAbilityMixin#applyProjectileAbilities", () -> {
            Projectile self = (Projectile) (Object) this;
            Level world = self.level();
            if (world.isClient() || advancedmobs$abilitiesApplied) {
                return;
            }

            advancedmobs$abilitiesApplied = true;

            if (self.getTags().contains(ScalingSystem.MULTISHOT_CHILD_TAG)
                || self.getTags().contains(ScalingSystem.EXTRA_SHOT_CHILD_TAG)) {
                return;
            }

            Entity owner = self.getOwner();
            if (!(owner instanceof Mob mob)) {
                return;
            }

            MobWarData data = advancedmobs$getData(mob);
            if (data == null) {
                return;
            }

            if (!(world instanceof ServerLevel serverWorld)) {
                return;
            }

            LivingEntity target = mob.getTarget();

            // Extra-shot follow-up cycles (scheduled between natural cycles).
            long serverTick = serverWorld.getServer().getTickCount();
            ScalingSystem.onMobFiredProjectileForExtraShot(mob, data, self, serverWorld, serverTick);

            // Multishot extra projectiles (immediate duplicates).
            int extraProjectiles = ScalingSystem.handleRangedAbilities(mob, data, target, serverTick);
            if (extraProjectiles <= 0) {
                return;
            }

            advancedmobs$spawnMultishotProjectiles(serverWorld, mob, data, self, extraProjectiles);
        });
    }

    @Unique
    private void advancedmobs$spawnMultishotProjectiles(ServerLevel world, Mob mob, MobWarData data, Projectile original, int extraProjectiles) {
        AdvancedMobsMod.runSafely("ProjectileAbilityMixin#spawnMultishotProjectiles", () -> {
            final boolean isExplosive = original instanceof AbstractHurtingProjectile;

            double baseSpeed;
            Vec3 baseDir;

            if (isExplosive) {
                double accel = 0.1;
                try {
                    accel = ((ExplosiveProjectileEntityAccessor) (Object) original).advancedmobs$getAccelerationPower();
                } catch (Throwable ignored) {
                }
                baseSpeed = Math.max(0.05, accel);
                Vec3 v = original.getDeltaMovement();
                baseDir = v.lengthSquared() > 1.0E-6 ? v.normalize() : mob.getViewVector(1.0F);
            } else {
                Vec3 v = original.getDeltaMovement();
                baseSpeed = Math.max(0.05, v.length());
                baseDir = v.lengthSquared() > 1.0E-6 ? v.normalize() : mob.getViewVector(1.0F);
            }

            // Use small variance: ~5-10% speed and small angle jitter.
            // This keeps it basically the same shot with slight imperfection.
            final float maxAngleDeg = 4.0f;
            final double minSpeedMul = 0.90;
            final double maxSpeedMul = 1.10;

            int piercingLevel = Math.max(0, ScalingSystem.getPiercingLevel(mob, data));

            for (int i = 0; i < extraProjectiles; i++) {
                Entity duplicate = original.getType().create(world);
                if (!(duplicate instanceof Projectile projectile)) {
                    continue;
                }

                projectile.setOwner(mob);
                projectile.setPosition(original.getX(), original.getY(), original.getZ());
                projectile.addTag(ScalingSystem.MULTISHOT_CHILD_TAG);

                if (original instanceof ThrowableItemProjectile thrown && projectile instanceof ThrowableItemProjectile thrownCopy) {
                    thrownCopy.setItem(thrown.getItem().copy());
                }

                Vec3 dirJittered = advancedmobs$jitterDirection(baseDir, mob.getRandom().nextFloat() * 2f - 1f, mob.getRandom().nextFloat() * 2f - 1f, maxAngleDeg);
                double speedMul = minSpeedMul + mob.getRandom().nextDouble() * (maxSpeedMul - minSpeedMul);
                double speed = baseSpeed * speedMul;
                Vec3 velocity = dirJittered.multiply(speed);
                projectile.setDeltaMovement(velocity);

                if (projectile instanceof AbstractHurtingProjectile explosiveCopy) {
                    try {
                        ((ExplosiveProjectileEntityAccessor) (Object) explosiveCopy).advancedmobs$setAccelerationPower(speed);
                    } catch (Throwable ignored) {
                    }
                }

                if (projectile instanceof AbstractArrow persistentCopy) {
                    persistentCopy.pickupType = AbstractArrow.PickupPermission.DISALLOWED;
                    if (piercingLevel > 0) {
                        ((PersistentProjectileEntityAccessor) (Object) persistentCopy).invokeSetPierceLevel((byte) Math.min(127, piercingLevel));
                    }
                }

                world.addFreshEntity(projectile);
            }
        });
    }

    @Unique
    private static Vec3 advancedmobs$jitterDirection(Vec3 baseDir, float yawRand, float pitchRand, float maxAngleDeg) {
        // Convert small yaw/pitch offsets (degrees) into a perturbed direction.
        double yaw = Math.toRadians(yawRand * maxAngleDeg);
        double pitch = Math.toRadians(pitchRand * maxAngleDeg);

        // Apply yaw around Y axis.
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        Vec3 yawed = new Vec3(
            baseDir.x * cosYaw - baseDir.z * sinYaw,
            baseDir.y,
            baseDir.x * sinYaw + baseDir.z * cosYaw
        );

        // Apply pitch around X axis.
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        Vec3 pitched = new Vec3(
            yawed.x,
            yawed.y * cosPitch - yawed.z * sinPitch,
            yawed.y * sinPitch + yawed.z * cosPitch
        );

        return pitched.lengthSquared() > 1.0E-6 ? pitched.normalize() : baseDir;
    }

    @Unique
    private MobWarData advancedmobs$getData(Mob mob) {
        if (mob instanceof IMobWarDataHolder holder) {
            return holder.getMobWarData();
        }
        return MobWarData.get(mob);
    }
}
