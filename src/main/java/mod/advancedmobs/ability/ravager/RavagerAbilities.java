package mod.advancedmobs.ability.ravager;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * RavagerAbilities: the Ravager's provider for the new
 * ability system. Supplies the ravager's 5 designed
 * abilities (ABILITY-IDEAS.md, Ravager): Siege Ram Charge,
 * Armored Juggernaut, Roar of the Raid, Ground Shatter
 * Fissure, Rider of Ruin. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class RavagerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SiegeRamChargeAbility(),
        new ArmoredJuggernautAbility(),
        new RoarOfTheRaidAbility(),
        new GroundShatterFissureAbility(),
        new RiderOfRuinAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
