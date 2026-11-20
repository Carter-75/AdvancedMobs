package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wires Enderman-specific scaling abilities (shadow_step + void_grasp).
 */
@Mixin(EnderMan.class)
public abstract class EndermanAbilityMixin extends Monster {

    @Unique
    private BlockPos advancedmobs$lastTeleportFrom = BlockPos.ZERO;

    protected EndermanAbilityMixin(EntityType<? extends Monster> entityType, Level world) {
        super(entityType, world);
    }

    @Inject(method = "teleportTo(DDD)Z", at = @At("HEAD"))
    private void advancedmobs$rememberTeleportOrigin(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        AdvancedMobsMod.runSafely("EndermanAbilityMixin#rememberTeleportOrigin", () -> {
            this.advancedmobs$lastTeleportFrom = this.blockPosition();
        });
    }

    @Inject(method = "teleportTo(DDD)Z", at = @At("RETURN"))
    private void advancedmobs$triggerShadowStep(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        AdvancedMobsMod.runSafely("EndermanAbilityMixin#triggerShadowStep", () -> {
            if (!cir.getReturnValueZ()) {
                return;
            }
            if (!(this.level() instanceof ServerLevel serverWorld)) {
                return;
            }
            MobWarData data = advancedmobs$getData();
            if (data == null) {
                return;
            }
            ScalingSystem.handleShadowStep((Mob) (Object) this, data, serverWorld, advancedmobs$lastTeleportFrom, serverWorld.getGameTime());
        });
    }

    @Inject(method = "tickMovement", at = @At("TAIL"))
    private void advancedmobs$handleVoidGrasp(CallbackInfo ci) {
        AdvancedMobsMod.runSafely("EndermanAbilityMixin#handleVoidGrasp", () -> {
            if (!(this.level() instanceof ServerLevel serverWorld)) {
                return;
            }
            // Run roughly every 10 ticks per Enderman to avoid constant scanning.
            if ((serverWorld.getGameTime() + this.getId()) % 10 != 0) {
                return;
            }
            MobWarData data = advancedmobs$getData();
            if (data == null) {
                return;
            }
            ScalingSystem.handleVoidGrasp((Mob) (Object) this, data, serverWorld, serverWorld.getGameTime());
        });
    }

    private MobWarData advancedmobs$getData() {
        if (this instanceof IMobWarDataHolder holder) {
            return holder.getMobWarData();
        }
        return MobWarData.get((Mob) (Object) this);
    }
}
