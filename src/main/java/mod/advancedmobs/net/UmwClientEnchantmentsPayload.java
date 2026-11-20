package mod.advancedmobs.net;

import mod.advancedmobs.AdvancedMobsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record UmwClientEnchantmentsPayload(List<Identifier> enchantmentIds) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UmwClientEnchantmentsPayload> ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AdvancedMobsMod.MODID, "client_enchantments"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UmwClientEnchantmentsPayload> CODEC =
        StreamCodec.of(UmwClientEnchantmentsPayload::write, UmwClientEnchantmentsPayload::read);

    public static UmwClientEnchantmentsPayload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Identifier> ids = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            ids.add(buf.readIdentifier());
        }
        return new UmwClientEnchantmentsPayload(ids);
    }

    public static void write(RegistryFriendlyByteBuf buf, UmwClientEnchantmentsPayload payload) {
        List<Identifier> ids = payload.enchantmentIds();
        buf.writeVarInt(ids.size());
        for (Identifier id : ids) {
            buf.writeIdentifier(id);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
