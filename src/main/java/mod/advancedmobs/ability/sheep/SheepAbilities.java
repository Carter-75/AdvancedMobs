package mod.advancedmobs.ability.sheep;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SheepAbilities: the Sheep's provider for the new ability
 * system. Supplies the sheep's 5 designed abilities
 * (ABILITY-IDEAS.md, Sheep): Woolquake, Ram Charge, Static
 * Wool, Rainbow Mirage Flock, Shear Terror. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class SheepAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new WoolquakeAbility(),
        new RamChargeAbility(),
        new StaticWoolAbility(),
        new RainbowMirageFlockAbility(),
        new ShearTerrorAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
