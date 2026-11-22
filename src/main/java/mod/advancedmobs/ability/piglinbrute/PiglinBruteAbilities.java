package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PiglinBruteAbilities: the Piglin Brute's provider for the
 * new ability system. Supplies the brute's 5 designed
 * abilities (ABILITY-IDEAS.md, Piglin Brute): Bastion Leap
 * Slam, Blackstone Bulwark Form, War Stomp Fissure,
 * Executioner's Mark, Bastion Standard Aura. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class PiglinBruteAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new BastionLeapSlamAbility(),
        new BlackstoneBulwarkFormAbility(),
        new WarStompFissureAbility(),
        new ExecutionersMarkAbility(),
        new BastionStandardAuraAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
