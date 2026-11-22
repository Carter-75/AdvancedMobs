package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SpiderAbilities: the Spider's provider for the new ability
 * system. Supplies the spider's 5 designed abilities
 * (ABILITY-IDEAS.md, Spider): Strangling Web Shot, Brood Eggs,
 * Web Fortress, Egg Sac Shield, Web Slinger. The instances are
 * shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class SpiderAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new StranglingWebShotAbility(),
        new BroodEggsAbility(),
        new WebFortressAbility(),
        new EggSacShieldAbility(),
        new WebSlingerAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
