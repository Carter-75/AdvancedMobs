package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.ability.cat.NineLivesSave;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.feline.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The injection point for the Cat's Nine Lives death save.
 *
 * Vanilla processes a killing blow inside LivingEntity
 * .hurtServer: after the damage lands, a dead-or-dying
 * entity gets exactly one reprieve check,
 * checkTotemDeathProtection, and dies only when it returns
 * false (verified against the 26.2 jar). Injecting at the
 * head of that check is the one place a death can be
 * cleanly prevented with the same semantics as a totem of
 * undying: when the cat's save fires, this returns true
 * and hurtServer skips die() entirely. The whole save
 * (guards, the permanent per-cat lives counter, revival,
 * escape) lives in NineLivesSave.trySave; this mixin only
 * routes cats to it. For every other entity, and for a cat
 * whose save does not fire, the vanilla check runs
 * untouched. Same pattern as ZoglinUndyingBulkMixin and
 * EvokerTotemTyrantMixin.
 */
@Mixin(LivingEntity.class)
public abstract class CatNineLivesMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void advancedmobs$nineLivesSave(DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Cat cat)) {
            return;
        }
        boolean[] saved = {false};
        AdvancedMobsMod.runSafely("CatNineLivesMixin#nineLivesSave",
            () -> saved[0] = NineLivesSave.trySave(cat, damageSource));
        if (saved[0]) {
            cir.setReturnValue(true);
        }
    }
}
