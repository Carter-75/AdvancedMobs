package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * EndermiteAbilities: the Endermite's provider for the new
 * ability system. Supplies the endermite's 5 designed
 * abilities (ABILITY-IDEAS.md, Endermite): Infestation Bite,
 * Call the Cracks, Living Stone Shell, Flicker Hop, Gorged
 * Mite. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class EndermiteAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new InfestationBiteAbility(),
        new CallTheCracksAbility(),
        new LivingStoneShellAbility(),
        new FlickerHopAbility(),
        new GorgedMiteAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
