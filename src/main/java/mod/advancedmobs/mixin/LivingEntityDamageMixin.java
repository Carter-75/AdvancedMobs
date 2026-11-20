package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles damage-triggered scaling abilities on the LivingEntity level so all mobs stay compatible
 * even when Mojang changes Mob's overrides.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @Inject(method = "damage", at = @At("TAIL"))
    private void advancedmobs$handleDamageAbilities(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        AdvancedMobsMod.runSafely("LivingEntityDamageMixin#handleDamageAbilities", () -> {
            if (!cir.getReturnValueZ()) {
                return;
            }

            if (!((Object) this instanceof Mob mobEntity)) {
                return;
            }

            MobWarData data = ((IMobWarDataHolder) mobEntity).getMobWarData();

            ScalingSystem.handleCriticalGlow(mobEntity, source);

            if (data == null) {
                return;
            }

            long currentTick = mobEntity.level().getGameTime();
            ScalingSystem.handleDamageAbilities(mobEntity, data, currentTick);
            if (mobEntity.level() instanceof ServerLevel serverWorld) {
                ScalingSystem.handleHordeSummon(mobEntity, data, serverWorld, currentTick);
            }
        });
    }
}
