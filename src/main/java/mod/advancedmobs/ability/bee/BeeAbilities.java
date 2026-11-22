package mod.advancedmobs.ability.bee;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * BeeAbilities: the Bee's provider for the new ability
 * system. Supplies the bee's 5 designed abilities
 * (ABILITY-IDEAS.md, Bee): Sting Barrage, Swarm Call,
 * Pollen Cloud, Honey Coat, Queen Ascendant. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class BeeAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new StingBarrageAbility(),
        new SwarmCallAbility(),
        new PollenCloudAbility(),
        new HoneyCoatAbility(),
        new QueenAscendantAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
