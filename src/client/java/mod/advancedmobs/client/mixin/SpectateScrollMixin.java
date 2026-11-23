package mod.advancedmobs.client.mixin;

import mod.advancedmobs.client.UmwSpectateHud;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While spectating from inside a mob, the mouse wheel would normally
 * change spectator fly speed and the spectator info panel could not be
 * scrolled at all (there is no open screen to receive scroll events).
 * This client-only mixin redirects wheel events to the spectator panel
 * in that state and cancels the fly-speed change; in every other state
 * it does nothing. Registered only on the client via
 * advancedmobs.client.mixins.json.
 */
@Mixin(MouseHandler.class)
public abstract class SpectateScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void umw$scrollSpectatePanel(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (UmwSpectateHud.handleScroll(yOffset)) {
            ci.cancel();
        }
    }
}
