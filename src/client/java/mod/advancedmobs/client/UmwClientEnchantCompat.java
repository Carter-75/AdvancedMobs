package mod.advancedmobs.client;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.net.UmwClientEnchantmentsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class UmwClientEnchantCompat {

    private UmwClientEnchantCompat() {
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                Registry<Enchantment> enchantRegistry = handler.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
                List<Identifier> ids = new ArrayList<>(enchantRegistry.getIds());
                ClientPlayNetworking.send(new UmwClientEnchantmentsPayload(ids));
            } catch (Exception e) {
                AdvancedMobsMod.LOGGER.warn("[EnchantCompat] Failed to send client enchantment list", e);
            }
        });
    }
}
