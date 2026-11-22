package mod.advancedmobs.ability.witch;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * WitchAbilities: the Witch's provider for the new ability
 * system. Supplies the witch's 5 designed abilities
 * (ABILITY-IDEAS.md, Witch): Cauldron Cataclysm, Potion
 * Roulette, Grand Hex Form, Swamp Hut Ward, Bottled Tempest.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class WitchAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CauldronCataclysmAbility(),
        new PotionRouletteAbility(),
        new GrandHexFormAbility(),
        new SwampHutWardAbility(),
        new BottledTempestAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
