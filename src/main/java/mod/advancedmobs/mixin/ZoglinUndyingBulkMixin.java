package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.ability.zoglin.UndyingBulkSave;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The injection point for the Zoglin's Undying Bulk death save.
 *
 * Vanilla processes a killing blow inside LivingEntity
 * .hurtServer: after the damage lands, a dead-or-dying entity
 * gets exactly one reprieve check, checkTotemDeathProtection,
 * and dies only when it returns false (verified against the
 * 26.2 jar). Injecting at the head of that check is the one
 * place a death can be cleanly prevented with the same
 * semantics as a totem of undying: when the zoglin's save
 * fires, this returns true and hurtServer skips die()
 * entirely. The whole save (guards, revival, effects) lives
 * in UndyingBulkSave.trySave; this mixin only routes zoglins
 * to it. For every other entity, and for a zoglin whose save
 * does not fire, the vanilla check runs untouched.
 */
@Mixin(LivingEntity.class)
public abstract class ZoglinUndyingBulkMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void advancedmobs$undyingBulkSave(DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Zoglin zoglin)) {
            return;
        }
        boolean[] saved = {false};
        AdvancedMobsMod.runSafely("ZoglinUndyingBulkMixin#undyingBulkSave",
            () -> saved[0] = UndyingBulkSave.trySave(zoglin, damageSource));
        if (saved[0]) {
            cir.setReturnValue(true);
        }
    }
}
