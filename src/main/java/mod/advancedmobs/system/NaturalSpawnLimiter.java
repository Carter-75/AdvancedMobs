package mod.advancedmobs.system;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class NaturalSpawnLimiter {

    private static final ConcurrentHashMap<Level, AtomicInteger> NATURAL_MOB_COUNTS = new ConcurrentHashMap<>();

    private NaturalSpawnLimiter() {
    }

    public static void onMobLoaded(ServerLevel world, boolean isNaturalSpawnedMob) {
        if (!isNaturalSpawnedMob) {
            return;
        }
        NATURAL_MOB_COUNTS.computeIfAbsent(world, w -> new AtomicInteger()).incrementAndGet();
    }

    public static void onMobUnloaded(ServerLevel world, boolean isNaturalSpawnedMob) {
        if (!isNaturalSpawnedMob) {
            return;
        }
        AtomicInteger counter = NATURAL_MOB_COUNTS.get(world);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    public static int getNaturalMobCount(ServerLevel world) {
        AtomicInteger counter = NATURAL_MOB_COUNTS.get(world);
        return counter != null ? Math.max(0, counter.get()) : 0;
    }

    /**
     * Drops every per-level counter. The map is keyed by Level object, and
     * every singleplayer session creates fresh ServerLevel objects, so
     * without a reset the entries (and the levels they pin) accumulate
     * across menu exits. Registered on SERVER_STOPPED in AdvancedMobsMod.
     */
    public static void clearAll() {
        NATURAL_MOB_COUNTS.clear();
    }
}
