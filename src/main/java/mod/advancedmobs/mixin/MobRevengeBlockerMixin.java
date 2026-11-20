package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.config.ModConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks same-species retaliation when the gamerule is ON.
 *
 * 26.2 API note: the old target, Entity.handleAttack, no longer exists.
 * The revenge system reads LivingEntity.getLastHurtByMob(), which is set
 * through setLastHurtByMob exactly when an entity is attacked, so
 * cancelling that setter for same-species attackers has the same effect:
 * the victim never adds the attacker to its revenge targeting.
 */
@Mixin(LivingEntity.class)
public abstract class MobRevengeBlockerMixin {

	@Inject(method = "setLastHurtByMob", at = @At("HEAD"), cancellable = true)
	private void advancedmobs$preventSameSpeciesRevenge(LivingEntity attacker, CallbackInfo ci) {
		AdvancedMobsMod.runSafely("MobRevengeBlockerMixin#preventSameSpeciesRevenge", () -> {
			LivingEntity self = (LivingEntity)(Object)this;

			// Only apply to mobs in server worlds
			if (self.level().isClientSide()) return;
			if (!(self.level() instanceof ServerLevel serverWorld)) return;
			if (!(self instanceof Mob selfMob)) return;
			if (attacker == null) return;

			// Check if ignore same species gamerule is enabled
			boolean ignoreSame = ModConfig.getInstance().ignoreSameSpecies;
			if (!ignoreSame) return;

			// If same species, prevent revenge targeting by cancelling the
			// last-hurt-by-mob record, so the revenge goal never picks the
			// attacker up.
			if (attacker.getType() == selfMob.getType()) {
				ci.cancel();
			}
		});
	}
}
