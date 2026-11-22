package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.AbilitySystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks mob deaths for the Advanced Mobs system.
 * Individual mob progression is handled by each mob's dedicated mixin.
 * This mixin provides a hook point for future kill-based features.
 */
@Mixin(LivingEntity.class)
public abstract class MobDeathTrackerMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void advancedmobs$trackKill(DamageSource damageSource, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDeathTrackerMixin#trackKill", () -> {
            LivingEntity victim = (LivingEntity)(Object)this;

            if (victim.level().isClientSide()) return;

            // Ability termination (Part C): if the dying entity is a mob
            // with an ability active, end that ability now, while the mob
            // and its level are still fully present. This runs regardless
            // of the scaling toggle: an ability that started must always
            // get its cleanup. See AbilitySystem.terminateActiveAbility.
            if (victim instanceof Mob victimMob) {
                AbilitySystem.terminateActiveAbility(
                    victimMob, victimMob.level(), MobWarData.get(victimMob));
            }

            // Check if any progression system is enabled
            boolean scalingEnabled = ModConfig.getInstance().isScalingActive();

            if (!scalingEnabled) return;

            // Kill tracking: the KILLER earns the kill, so the victim only
            // needs to be a countable target (a player or another mob).
            // BUG FIXED (Part A, Nov 21, 2026): this method used to return
            // early unless the victim was a Mob, which made the Player
            // branch of the count check below unreachable, so a mob that
            // killed a PLAYER never earned killCount and never earned the
            // kill points ScalingSystem derives from it. The victim check
            // is now the count check itself.
            if (!(victim instanceof Player) && !(victim instanceof Mob)) {
                return;
            }

            if (!(damageSource.getEntity() instanceof Mob killer)) {
                return;
            }

            if (killer == victim) {
                return;
            }

            MobWarData killerData = MobWarData.get(killer);
            killerData.addKill();
            MobWarData.save(killer, killerData);
        });
    }
}
