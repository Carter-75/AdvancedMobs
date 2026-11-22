package mod.advancedmobs.ability.zombievillager;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ZombieVillagerAbilities: the Zombie Villager's provider for the new
 * ability system. Supplies the zombie villager's 5 designed abilities
 * (ABILITY-IDEAS.md, Zombie Villager): Emerald Shrapnel, Golden Apple
 * Ward, Bell of the Fallen Village, Cursed Market, Frenzied Merchant.
 * The instances are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class ZombieVillagerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new EmeraldShrapnelAbility(),
        new GoldenAppleWardAbility(),
        new BellOfTheFallenVillageAbility(),
        new CursedMarketAbility(),
        new FrenziedMerchantAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
