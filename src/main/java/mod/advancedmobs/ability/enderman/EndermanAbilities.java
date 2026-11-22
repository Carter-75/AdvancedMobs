package mod.advancedmobs.ability.enderman;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * EndermanAbilities: the Enderman's provider for the new
 * ability system. Supplies the enderman's 5 designed
 * abilities (ABILITY-IDEAS.md, Enderman): Ender Spike Field,
 * Obsidian Carapace, Mite Broodlord, Void Titan, Dimensional
 * Anchor. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class EndermanAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new EnderSpikeFieldAbility(),
        new ObsidianCarapaceAbility(),
        new MiteBroodlordAbility(),
        new VoidTitanAbility(),
        new DimensionalAnchorAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
