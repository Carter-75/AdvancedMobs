package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * IronGolemAbilities: the Iron Golem's provider for the new
 * ability system. Supplies the golem's 5 designed abilities
 * (ABILITY-IDEAS.md, Iron Golem): Catapult Arms, Poppy's
 * Revenge, Fortress Form, Ground Slam, Vine Snare. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class IronGolemAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CatapultArmsAbility(),
        new PoppysRevengeAbility(),
        new FortressFormAbility(),
        new GroundSlamAbility(),
        new VineSnareAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
