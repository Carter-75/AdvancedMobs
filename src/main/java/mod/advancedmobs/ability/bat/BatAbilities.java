package mod.advancedmobs.ability.bat;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * BatAbilities: the Bat's provider for the new ability
 * system. Supplies the bat's 5 designed abilities
 * (ABILITY-IDEAS.md, Bat): Sonar Pulse, Dive Bomb, Darkness
 * Shroud, Rabid Swarm, Stalactite Drop. The instances are
 * shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 * This is the eighty-first and final mob of the build.
 */
public final class BatAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SonarPulseAbility(),
        new DiveBombAbility(),
        new DarknessShroudAbility(),
        new RabidSwarmAbility(),
        new StalactiteDropAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
