package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PiglinAbilities: the Piglin's provider for the new ability
 * system. Supplies the piglin's 5 designed abilities
 * (ABILITY-IDEAS.md, Piglin): Gold Rush Frenzy, Barter Barrage,
 * Volley Mark, Gilded Plate Swap, Hoglin Cavalry. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class PiglinAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new GoldRushFrenzyAbility(),
        new BarterBarrageAbility(),
        new VolleyMarkAbility(),
        new GildedPlateSwapAbility(),
        new HoglinCavalryAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
