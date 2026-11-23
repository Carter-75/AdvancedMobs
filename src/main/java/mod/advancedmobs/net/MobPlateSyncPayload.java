package mod.advancedmobs.net;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: lightweight per-mob plate data (level, XP) for mobs
 * near the receiving player, pushed periodically by MobInspectService.
 * Health itself is already synced by vanilla, so plates only need this
 * progression data. Tag shape: {entries: [{id, level, xp, name}]}.
 */
public record MobPlateSyncPayload(CompoundTag data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MobPlateSyncPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "mob_plate_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MobPlateSyncPayload> CODEC =
        StreamCodec.of(MobPlateSyncPayload::write, MobPlateSyncPayload::read);

    public static MobPlateSyncPayload read(RegistryFriendlyByteBuf buf) {
        CompoundTag data = buf.readNbt();
        return new MobPlateSyncPayload(data != null ? data : new CompoundTag());
    }

    public static void write(RegistryFriendlyByteBuf buf, MobPlateSyncPayload payload) {
        buf.writeNbt(payload.data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
