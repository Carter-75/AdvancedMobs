package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.ability.evoker.TotemTyrantSave;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.Evoker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The injection point for the Evoker's Totem Tyrant death
 * save.
 *
 * Vanilla processes a killing blow inside LivingEntity
 * .hurtServer: after the damage lands, a dead-or-dying
 * entity gets exactly one reprieve check,
 * checkTotemDeathProtection, and dies only when it returns
 * false (verified against the 26.2 jar). Injecting at the
 * head of that check is the one place a death can be cleanly
 * prevented with the same semantics as a totem of undying:
 * when the tyrant's totem fires, this returns true and
 * hurtServer skips die() entirely. The whole save (guards,
 * revival, blast, effects) lives in TotemTyrantSave.trySave;
 * this mixin only routes evokers to it. For every other
 * entity, and for an evoker whose totem does not fire, the
 * vanilla check runs untouched. ZoglinUndyingBulkMixin
 * injects at the same point for zoglins; the two coexist,
 * each returning immediately for the other's mob.
 */
@Mixin(LivingEntity.class)
public abstract class EvokerTotemTyrantMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void advancedmobs$totemTyrantSave(DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Evoker evoker)) {
            return;
        }
        boolean[] saved = {false};
        AdvancedMobsMod.runSafely("EvokerTotemTyrantMixin#totemTyrantSave",
            () -> saved[0] = TotemTyrantSave.trySave(evoker, damageSource));
        if (saved[0]) {
            cir.setReturnValue(true);
        }
    }
}
