package mod.advancedmobs.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractHurtingProjectile.class)
public interface ExplosiveProjectileEntityAccessor {
    @Accessor("accelerationPower")
    double advancedmobs$getAccelerationPower();

    @Accessor("accelerationPower")
    void advancedmobs$setAccelerationPower(double value);
}
