package mod.advancedmobs.ability.husk;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * HuskAbilities: the Husk's provider for the new ability system.
 * Supplies the husk's 5 designed abilities (ABILITY-IDEAS.md, Husk):
 * Sandblast Barrage, Quicksand Pit, Sandstorm Revenant, Arid Aura,
 * Scarab Brood. The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability objects.
 */
public final class HuskAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SandblastBarrageAbility(),
        new QuicksandPitAbility(),
        new SandstormRevenantAbility(),
        new AridAuraAbility(),
        new ScarabBroodAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
