package mod.advancedmobs.mixin;

import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ExplosiveProjectileEntity.class)
public interface ExplosiveProjectileEntityAccessor {
    @Accessor("accelerationPower")
    double advancedmobs$getAccelerationPower();

    @Accessor("accelerationPower")
    void advancedmobs$setAccelerationPower(double value);
}
