package mod.advancedmobs.client;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.net.UmwRequiredClientPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Client-side initialization for Advanced Mobs.
 * Registers the custom witch renderer for the Mob Warlord boss.
 */
public class AdvancedMobsModClient implements ClientModInitializer {
    
    @Override
    public void onInitializeClient() {
        // Register the payload type so this client advertises "I have AdvancedMobs" to servers.
        try {
            PayloadTypeRegistry.clientboundPlay().register(UmwRequiredClientPayload.ID, UmwRequiredClientPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegistered) {
            // ok
        }

        // Also register a receiver so servers can reliably detect support via canSend(...).
        ClientPlayNetworking.registerGlobalReceiver(UmwRequiredClientPayload.ID, (payload, context) -> {
            // no-op: presence is the signal
        });

        UmwClientEnchantCompat.init();

        // Register custom witch renderer for the Mob Warlord
        EntityRendererRegistry.register(AdvancedMobsMod.MOB_WARLORD, MobWarlordRenderer::new);
        
        // Register world render callback for visual features
        WorldRenderEvents.LAST.register(new MobVisualRenderer());
        
        AdvancedMobsMod.LOGGER.info("Advanced Mobs client initialized - Custom witch renderer and visual features registered!");
    }
}

