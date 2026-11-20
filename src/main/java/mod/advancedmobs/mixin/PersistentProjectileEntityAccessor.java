package mod.advancedmobs.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractArrow.class)
public interface PersistentProjectileEntityAccessor {
    @Accessor("inGround")
    boolean advancedmobs$isInGround();

    @Invoker("setPierceLevel")
    void invokeSetPierceLevel(byte pierceLevel);
    
    @Invoker("getPierceLevel")
    byte invokeGetPierceLevel();
}
