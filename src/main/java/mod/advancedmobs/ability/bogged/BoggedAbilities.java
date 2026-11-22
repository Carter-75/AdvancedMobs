package mod.advancedmobs.ability.bogged;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * BoggedAbilities: the Bogged's provider for the new ability
 * system. Supplies the bogged's 5 designed abilities
 * (ABILITY-IDEAS.md, Bogged): Sporeburst Arrow, Fungal Spawn,
 * Bog Mire, Shelf Fungus Armor, Ancient Bog Thing. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class BoggedAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SporeburstArrowAbility(),
        new FungalSpawnAbility(),
        new BogMireAbility(),
        new ShelfFungusArmorAbility(),
        new AncientBogThingAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
