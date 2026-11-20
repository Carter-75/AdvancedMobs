package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.config.ModConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks same-species retaliation when the gamerule is ON.
 * Uses handleAttack method which is called when an entity is attacked.
 * This prevents same-species mobs from adding each other to their revenge target lists.
 */
@Mixin(Entity.class)
public abstract class MobRevengeBlockerMixin {

	@Inject(method = "handleAttack", at = @At("HEAD"), cancellable = true)
	private void advancedmobs$preventSameSpeciesRevenge(Entity attacker, CallbackInfoReturnable<Boolean> cir) {
		AdvancedMobsMod.runSafely("MobRevengeBlockerMixin#preventSameSpeciesRevenge", () -> {
			Entity self = (Entity)(Object)this;

			// Only apply to mobs in server worlds
			if (self.level().isClientSide()) return;
			if (!(self.level() instanceof ServerLevel serverWorld)) return;
			if (!(self instanceof Mob selfMob)) return;
			if (attacker == null) return;

			// Check if ignore same species gamerule is enabled
			boolean ignoreSame = ModConfig.getInstance().ignoreSameSpecies;
			if (!ignoreSame) return;

			// If same species, prevent revenge targeting by returning false (don't handle the attack normally)
			if (attacker.getType() == selfMob.getType()) {
				// Return false to prevent the entity from handling the attack
				// This prevents them from adding each other to revenge target lists
				cir.setReturnValue(false);
			}
		});
	}
}

