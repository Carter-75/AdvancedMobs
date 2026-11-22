package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ChickenAbilities: the Chicken's provider for the new
 * ability system. Supplies the chicken's 5 designed
 * abilities (ABILITY-IDEAS.md, Chicken): Egg Barrage,
 * Chick Swarm, Feather Storm, Sky Tyrant Dive, Golden
 * Egg. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class ChickenAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new EggBarrageAbility(),
        new ChickSwarmAbility(),
        new FeatherStormAbility(),
        new SkyTyrantDiveAbility(),
        new GoldenEggAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
