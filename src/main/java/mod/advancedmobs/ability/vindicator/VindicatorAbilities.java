package mod.advancedmobs.ability.vindicator;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * VindicatorAbilities: the Vindicator's provider for the new
 * ability system. Supplies the vindicator's 5 designed
 * abilities (ABILITY-IDEAS.md, Vindicator): Axe Cyclone,
 * Johnny Rampage, Doorbreaker Siege, Patrol Captain's
 * Standard, Executioner's Leap. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class VindicatorAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new AxeCycloneAbility(),
        new JohnnyRampageAbility(),
        new DoorbreakerSiegeAbility(),
        new PatrolCaptainsStandardAbility(),
        new ExecutionersLeapAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
