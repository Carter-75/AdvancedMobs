package mod.advancedmobs.ability.snowgolem;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SnowGolemAbilities: the Snow Golem's provider for the new
 * ability system. Supplies the golem's 5 designed abilities
 * (ABILITY-IDEAS.md, Snow Golem): Blizzard Barrage,
 * Snowdrift Wake, Abominable Form, Orbiting Snow Shield,
 * Powder Snow Trap. The instances are shared singletons;
 * all per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class SnowGolemAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new BlizzardBarrageAbility(),
        new SnowdriftWakeAbility(),
        new AbominableFormAbility(),
        new OrbitingSnowShieldAbility(),
        new PowderSnowTrapAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
