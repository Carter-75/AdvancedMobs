package mod.advancedmobs.net;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server: max this mob out (God Mode from the inspect panel).
 * The server checks the entity and range the same way an inspect request does.
 */
public record MobGodModePayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MobGodModePayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "mob_god_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MobGodModePayload> CODEC =
        StreamCodec.of(MobGodModePayload::write, MobGodModePayload::read);

    public static MobGodModePayload read(RegistryFriendlyByteBuf buf) {
        return new MobGodModePayload(buf.readVarInt());
    }

    public static void write(RegistryFriendlyByteBuf buf, MobGodModePayload payload) {
        buf.writeVarInt(payload.entityId());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
