package mod.advancedmobs.net;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Empty payload used only to verify that a connecting client has AdvancedMobs installed.
 *
 * The server checks {@code ServerPlayNetworking.canSend(...)} for this payload ID.
 * If the client doesn't have the mod, it won't advertise this payload and will be rejected.
 */
public record UmwRequiredClientPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UmwRequiredClientPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "required_client"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UmwRequiredClientPayload> CODEC =
        StreamCodec.of(UmwRequiredClientPayload::write, UmwRequiredClientPayload::read);

    public static UmwRequiredClientPayload read(RegistryFriendlyByteBuf buf) {
        return new UmwRequiredClientPayload();
    }

    public static void write(RegistryFriendlyByteBuf buf, UmwRequiredClientPayload payload) {
        // Intentionally empty.
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
