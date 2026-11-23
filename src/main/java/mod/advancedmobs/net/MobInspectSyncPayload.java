package mod.advancedmobs.net;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: a full inspect snapshot for one mob, as an NBT tag
 * built by MobInspectService (identity, vitals, progression level/XP,
 * abilities, effects, equipment with enchantments, target, allies,
 * warlord status, combat stats). The client renders it verbatim in the
 * inspect panel and the spectator overlay, so new server-side fields
 * need no codec changes here.
 */
public record MobInspectSyncPayload(int entityId, CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MobInspectSyncPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "mob_inspect_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MobInspectSyncPayload> CODEC =
        StreamCodec.of(MobInspectSyncPayload::write, MobInspectSyncPayload::read);

    public static MobInspectSyncPayload read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        CompoundTag data = buf.readNbt();
        return new MobInspectSyncPayload(entityId, data != null ? data : new CompoundTag());
    }

    public static void write(RegistryFriendlyByteBuf buf, MobInspectSyncPayload payload) {
        buf.writeVarInt(payload.entityId);
        buf.writeNbt(payload.data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
