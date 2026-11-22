package mod.advancedmobs.ability.cow;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * CowAbilities: the Cow's provider for the new ability
 * system. Supplies the cow's 5 designed abilities
 * (ABILITY-IDEAS.md, Cow): Stampede Engine, Horned Bulwark,
 * Moothquake Stomp, Cud Cannon, Pasture Sanctuary. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class CowAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new StampedeEngineAbility(),
        new HornedBulwarkAbility(),
        new MoothquakeStompAbility(),
        new CudCannonAbility(),
        new PastureSanctuaryAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
