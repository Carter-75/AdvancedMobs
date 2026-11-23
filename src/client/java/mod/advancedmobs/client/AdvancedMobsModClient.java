package mod.advancedmobs.client;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.net.UmwRequiredClientPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;

/**
 * Client-side initialization for Advanced Mobs.
 * Registers the custom witch renderer for the Mob Warlord boss, the
 * target-line visuals, health plates, and the inspect system
 * (right-click panel + spectator overlay + redesigned menu support).
 */
public class AdvancedMobsModClient implements ClientModInitializer {

    private static int spectateRefreshCounter = 0;

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

        // Inspect system: snapshot/plate receivers, health plates in the
        // world, spectator overlay on the HUD.
        UmwClientState.registerReceivers();
        LevelRenderEvents.COLLECT_SUBMITS.register(new MobHealthPlateRenderer());
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "spectate_inspect"),
            new UmwSpectateHud());

        // Right-click a mob opens its inspect panel (PASS so vanilla
        // interactions like trading still proceed and may replace it).
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide()
                && hand == net.minecraft.world.InteractionHand.MAIN_HAND
                && entity instanceof Mob mob) {
                Minecraft.getInstance().setScreenAndShow(new MobInspectScreen(mob.getId()));
            }
            return InteractionResult.PASS;
        });

        // While spectating from inside a mob, keep its snapshot fresh.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++spectateRefreshCounter % 20 != 0) {
                return;
            }
            int mobId = UmwClientState.spectatedMobId();
            if (mobId >= 0) {
                UmwClientState.requestInspect(mobId);
            }
        });

        // Register custom witch renderer for the Mob Warlord
        EntityRendererRegistry.register(AdvancedMobsMod.MOB_WARLORD, MobWarlordRenderer::new);

        // Register level render callback for visual features
        LevelRenderEvents.COLLECT_SUBMITS.register(new MobVisualRenderer());

        AdvancedMobsMod.LOGGER.info("Advanced Mobs client initialized - Custom witch renderer and visual features registered!");
    }
}
