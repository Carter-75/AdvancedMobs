package mod.advancedmobs.ability.stray;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * StrayAbilities: the Stray's provider for the new ability system.
 * Supplies the stray's 5 designed abilities (ABILITY-IDEAS.md,
 * Stray): Icicle Fall, Perpetual Frost, Frozen Carapace, Howling
 * Blizzard, Wind Rider. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never in
 * the ability objects.
 */
public final class StrayAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new IcicleFallAbility(),
        new PerpetualFrostAbility(),
        new FrozenCarapaceAbility(),
        new HowlingBlizzardAbility(),
        new WindRiderAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
