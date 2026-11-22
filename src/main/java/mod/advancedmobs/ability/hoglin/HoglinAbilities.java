package mod.advancedmobs.ability.hoglin;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * HoglinAbilities: the Hoglin's provider for the new ability
 * system. Supplies the hoglin's 5 designed abilities
 * (ABILITY-IDEAS.md, Hoglin): Stampede Trench, Crimson Maw,
 * Fungal Frenzy Cloud, Territorial Slam, Stubborn Hide Brace.
 * The instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability objects.
 */
public final class HoglinAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new StampedeTrenchAbility(),
        new CrimsonMawAbility(),
        new FungalFrenzyCloudAbility(),
        new TerritorialSlamAbility(),
        new StubbornHideBraceAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
