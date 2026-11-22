package mod.advancedmobs.ability;

import mod.advancedmobs.ability.zombie.ZombieAbilities;

/**
 * AbilityProviders: THE registration site for the new ability system.
 *
 * Every implemented mob adds exactly one line in registerAll(): its config
 * mob_name (the "mob_name" value of its file in mob_configs, the same
 * identity ScalingSystem resolves the mob's upgrade config by) mapped to
 * its MobAbilityProvider. registerAll() is called once from
 * AdvancedMobsMod.onInitialize(), before any mob ticks; the registry is
 * not modified afterwards.
 *
 * A mob type with no line here is simply not part of the ability system
 * yet: AbilitySystem skips it entirely and never rolls access for it.
 */
public final class AbilityProviders {

    private AbilityProviders() {}

    /** Registers every implemented mob's ability provider. Called once at mod init. */
    public static void registerAll() {
        AbilityRegistry.register("Zombie", new ZombieAbilities());
    }
}
