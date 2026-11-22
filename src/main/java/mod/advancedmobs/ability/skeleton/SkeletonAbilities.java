package mod.advancedmobs.ability.skeleton;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SkeletonAbilities: the Skeleton's provider for the new ability
 * system. Supplies the skeleton's 5 designed abilities
 * (ABILITY-IDEAS.md, Skeleton): Executioner Arrow, Headless Cavalry,
 * Rib Shield, Grave Soil, Ancient Deadeye. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class SkeletonAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new ExecutionerArrowAbility(),
        new HeadlessCavalryAbility(),
        new RibShieldAbility(),
        new GraveSoilAbility(),
        new AncientDeadeyeAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
