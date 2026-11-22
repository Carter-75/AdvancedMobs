package mod.advancedmobs.ability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AbilityRegistry: the per-mob registry of the new ability system.
 *
 * KEYS: a provider is registered under the mob's config name, meaning the
 * "mob_name" value of its file in src/main/resources/mob_configs (for
 * example "Zombie" or "Cave Spider"). That is the same identity
 * ScalingSystem resolves a mob's upgrade config by, so a mob's abilities
 * always come from the provider registered under the name of the config the
 * scaling system is running for it. Keys are normalized on both register
 * and lookup (trimmed, lowercased, underscores treated as spaces) so
 * "Cave_Spider", "cave spider" and "Cave Spider" all name the same mob.
 *
 * A mob type with no registered provider is simply not part of the ability
 * system yet: AbilitySystem skips it entirely and never rolls access for
 * it, so a mob's access state is only ever created after its 5 abilities
 * exist. Providers are registered during mod initialization, before any
 * mob ticks, and the registry is not modified afterwards.
 */
public final class AbilityRegistry {

    private static final Map<String, MobAbilityProvider> PROVIDERS = new LinkedHashMap<>();

    private AbilityRegistry() {}

    /**
     * Register the ability provider for one mob config name. Called once
     * per mob during initialization. Registering the same name twice
     * replaces the earlier provider (last registration wins).
     */
    public static void register(String configName, MobAbilityProvider provider) {
        if (configName == null || provider == null) {
            return;
        }
        PROVIDERS.put(normalize(configName), provider);
    }

    /**
     * The provider registered for a config name, or null when the mob type
     * has no abilities implemented yet.
     */
    public static MobAbilityProvider getProvider(String configName) {
        if (configName == null) {
            return null;
        }
        return PROVIDERS.get(normalize(configName));
    }

    /** Whether any provider is registered under a config name. */
    public static boolean hasProvider(String configName) {
        return getProvider(configName) != null;
    }

    /** How many mob types have a provider registered. For logs and debug. */
    public static int registeredMobCount() {
        return PROVIDERS.size();
    }

    /** Key normalization shared by registration and lookup. */
    public static String normalize(String configName) {
        return configName.trim().toLowerCase().replace('_', ' ');
    }
}
