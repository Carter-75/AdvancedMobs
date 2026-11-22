package mod.advancedmobs.ability.giant;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * GiantAbilities: the Giant's provider for the new ability
 * system. Supplies the giant's 5 designed abilities
 * (ABILITY-IDEAS.md, Giant): Seismic Stomp, Colossus Form,
 * Boulder Throw, Living Mountain, Stomp Call. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class GiantAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SeismicStompAbility(),
        new ColossusFormAbility(),
        new BoulderThrowAbility(),
        new LivingMountainAbility(),
        new StompCallAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
