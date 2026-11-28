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
import mod.advancedmobs.system.CombatRageSystem;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles damage-triggered scaling effects (critical glow, invisibility on hit,
 * on-damage regeneration) on the LivingEntity level so all mobs stay compatible
 * even when Mojang changes Mob's overrides.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float advancedmobs$rageDamage(float amount, ServerLevel level, DamageSource source) {
        if (amount <= 0.0f || !(source.getEntity() instanceof Mob attacker)) {
            return amount;
        }
        float multiplier = CombatRageSystem.damageMultiplier(attacker);
        if (multiplier == 1.0f) {
            return amount;
        }
        return amount * multiplier;
    }

    @Inject(method = "hurtServer", at = @At("TAIL"))
    private void advancedmobs$handleDamageAbilities(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        AdvancedMobsMod.runSafely("LivingEntityDamageMixin#handleDamageAbilities", () -> {
            if (!cir.getReturnValueZ()) {
                return;
            }

            if (!((Object) this instanceof Mob mobEntity)) {
                return;
            }

            MobWarData data = ((IMobWarDataHolder) mobEntity).getMobWarData();

            // Combat stats for the inspect panel. These adders existed
            // on MobWarData but nothing ever called them, so damage
            // dealt/taken displayed as 0 forever.
            if (data != null) {
                data.addDamageTaken(amount);
            }
            if (source.getEntity() instanceof Mob attacker && attacker != mobEntity) {
                MobWarData attackerData = ((IMobWarDataHolder) attacker).getMobWarData();
                if (attackerData != null) {
                    attackerData.addDamageDealt(amount);
                }
            }

            ScalingSystem.handleCriticalGlow(mobEntity, source);

            if (data == null) {
                return;
            }

            long currentTick = mobEntity.level().getGameTime();
            // Boss combat progression (Part B): stamp the last successful
            // hurt so ScalingSystem's combat check counts a recently hurt
            // boss as in combat for the following 10 seconds, even when it
            // momentarily has no target. One long write per damage event,
            // never per tick.
            data.setLastHurtTick(currentTick);
            ScalingSystem.handleDamageAbilities(mobEntity, data, currentTick);
        });
    }
}
