package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.system.ProgressionTicker;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the Ender Dragon the progression tick every other mob gets from
 * MobDataMixin (Part A of the Nov 21, 2026 audit).
 *
 * THE BUG THIS FIXES: MobDataMixin hooks Mob.serverAiStep, which is
 * invoked from LivingEntity.aiStep when the entity has effective AI.
 * EnderDragon replaces aiStep() entirely with its own phase-driven
 * implementation and never calls super (verified in the 26.2 jar
 * bytecode: EnderDragon.aiStep contains no invokespecial to Mob.aiStep
 * or LivingEntity.aiStep). The dragon therefore skipped the entire
 * progression pipeline: no world-age points, no upgrade spending, no
 * ability access evaluation, even though Ender_Dragon.json exists and
 * the ability framework guarantees the dragon access from the start.
 *
 * THE HOOK: HEAD of EnderDragon.aiStep, mirroring the HEAD placement of
 * the serverAiStep hook for normal mobs, so progression runs before the
 * dragon's phase logic for the tick just as it runs before normal AI.
 * The dragon's aiStep IS its AI step (it runs the phase manager there),
 * so this also matches the framework's standing contract that abilities
 * tick from the mob's AI step (see MobAbility). Client side is skipped:
 * aiStep runs on both sides, progression is server only.
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonProgressionMixin {

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void advancedmobs$onDragonAiStep(CallbackInfo ci) {
        AdvancedMobsMod.runSafely("EnderDragonProgressionMixin#aiStep", () -> {
            EnderDragon self = (EnderDragon) (Object) this;
            if (self.level().isClientSide()) {
                return;
            }
            if (self instanceof IMobWarDataHolder holder) {
                ProgressionTicker.tick(self, holder.getMobWarData());
            }
        });
    }
}
