package mod.advancedmobs.ability.polarbear;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PolarBearAbilities: the Polar Bear's provider for the new
 * ability system. Supplies the bear's 5 designed abilities
 * (ABILITY-IDEAS.md, Polar Bear): Avalanche Charge, Blizzard
 * Aura, Deep Hibernate, Icebreaker, Ursa Major. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class PolarBearAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new AvalancheChargeAbility(),
        new BlizzardAuraAbility(),
        new DeepHibernateAbility(),
        new IcebreakerAbility(),
        new UrsaMajorAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
