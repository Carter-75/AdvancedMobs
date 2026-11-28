package mod.advancedmobs.client;

import mod.advancedmobs.net.MobGodModePayload;
import mod.advancedmobs.net.MobInspectRequestPayload;
import mod.advancedmobs.net.MobInspectSyncPayload;
import mod.advancedmobs.net.MobPlateSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side state for the inspect system: the latest full inspect
 * snapshots (keyed by entity id) and the lightweight plate data
 * (level/XP per entity id) pushed by the server once per second.
 * Receivers are registered here; rendering lives in
 * MobHealthPlateRenderer, MobInspectScreen and UmwSpectateHud.
 */
public final class UmwClientState {

    public record PlateInfo(int level, int xp, String name) {}

    /** Full inspect snapshots from MobInspectSyncPayload, by entity id. */
    public static final Map<Integer, CompoundTag> INSPECT_SNAPSHOTS = new ConcurrentHashMap<>();
    /** Lightweight plate data from MobPlateSyncPayload, by entity id. */
    public static final Map<Integer, PlateInfo> PLATES = new ConcurrentHashMap<>();

    private UmwClientState() {}

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(MobInspectSyncPayload.ID, (payload, context) ->
            context.client().execute(() -> INSPECT_SNAPSHOTS.put(payload.entityId(), payload.data())));

        ClientPlayNetworking.registerGlobalReceiver(MobPlateSyncPayload.ID, (payload, context) ->
            context.client().execute(() -> {
                ListTag entries = payload.data().getListOrEmpty("entries");
                for (int i = 0; i < entries.size(); i++) {
                    CompoundTag entry = entries.getCompoundOrEmpty(i);
                    int id = entry.getIntOr("id", -1);
                    if (id >= 0) {
                        PLATES.put(id, new PlateInfo(
                            entry.getIntOr("level", 0),
                            entry.getIntOr("xp", 0),
                            entry.getStringOr("name", "")));
                    }
                }
            }));
    }

    /** Asks the server for a fresh inspect snapshot (no-op without a connection). */
    public static void requestInspect(int entityId) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        if (ClientPlayNetworking.canSend(MobInspectRequestPayload.ID)) {
            ClientPlayNetworking.send(new MobInspectRequestPayload(entityId));
        }
    }

    /** Asks the server to max this mob's upgrades, level, and ability power. */
    public static void requestGodMode(int entityId) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        if (ClientPlayNetworking.canSend(MobGodModePayload.ID)) {
            ClientPlayNetworking.send(new MobGodModePayload(entityId));
        }
    }

    /** Entity id of the mob the local player is currently spectating from inside, or -1. */
    public static int spectatedMobId() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return -1;
        }
        var camera = client.getCameraEntity();
        if (camera != null && camera != client.player && camera instanceof net.minecraft.world.entity.Mob mob) {
            return mob.getId();
        }
        return -1;
    }
}
