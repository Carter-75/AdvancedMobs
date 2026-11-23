package mod.advancedmobs.net;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server: "send me a full inspect snapshot for this entity".
 * Sent when a player right-clicks a mob, while the inspect panel is open
 * (periodic refresh), and while spectating from inside a mob. The server
 * validates the entity and the requester's range before replying with a
 * MobInspectSyncPayload; see MobInspectService.
 */
public record MobInspectRequestPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MobInspectRequestPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "mob_inspect_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MobInspectRequestPayload> CODEC =
        StreamCodec.of(MobInspectRequestPayload::write, MobInspectRequestPayload::read);

    public static MobInspectRequestPayload read(RegistryFriendlyByteBuf buf) {
        return new MobInspectRequestPayload(buf.readVarInt());
    }

    public static void write(RegistryFriendlyByteBuf buf, MobInspectRequestPayload payload) {
        buf.writeVarInt(payload.entityId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
