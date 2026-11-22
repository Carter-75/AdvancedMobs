package mod.advancedmobs.ability.cavespider;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * CaveSpiderAbilities: the Cave Spider's provider for the new
 * ability system. Supplies the cave spider's 5 designed
 * abilities (ABILITY-IDEAS.md, Cave Spider): Neurotoxin
 * Webbing, Silk Minefield, Chitin Plating, Cave Skitter,
 * Venom Mist. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class CaveSpiderAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new NeurotoxinWebbingAbility(),
        new SilkMinefieldAbility(),
        new ChitinPlatingAbility(),
        new CaveSkitterAbility(),
        new VenomMistAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
