package mod.advancedmobs.ability.axolotl;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * AxolotlAbilities: the Axolotl's provider for the new ability
 * system. Supplies the axolotl's 5 designed abilities
 * (ABILITY-IDEAS.md, Axolotl): Playing Dead, Gills of the
 * Deep, Predator Snap, Mud Camouflage, Regeneration Bloom.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class AxolotlAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new PlayingDeadAbility(),
        new GillsOfTheDeepAbility(),
        new PredatorSnapAbility(),
        new MudCamouflageAbility(),
        new RegenerationBloomAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
